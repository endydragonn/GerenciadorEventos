// Cenario k6 de CARGA ALEATORIA (modelo aberto): a taxa muda a cada passo para um alvo sorteado
// (perfil gerado pelo rodar-aleatorio.ps1 a partir de uma semente, entao reprodutivel) e cada
// iteracao sorteia o que fazer:
//   reserva  POST /bff/event-wallets         par (usuario, evento) unico, eventos embaralhados
//   leitura  GET  /bff/events/{id}           (publico)
//            GET  /bff/event-wallets/user/N  (autenticado)
//            GET  /bff/events/search?term=   (publico)
//
// Respostas da reserva: 201 aceita | 400 "lotado" | 5xx erro de servidor | outro inesperado.
// Leitura: 2xx ok | 5xx erro | outro inesperado.
// Cada reserva aceita (201) e confirmada na mesma iteracao (POST /bff/event-wallets/confirm, 200)
// com PCT_CONFIRMA % de chance, sorteada de forma deterministica por (SEMENTE, par) sem usar
// Math.random (o perfil de taxa e a ordem dos sorteios nao mudam). Metricas separadas.
//
// Overselling NAO e medido no cliente: em rodadas longas as reservas pending expiram (~120 s) e a
// vaga volta a ser vendida, entao "aceitas" pode passar da capacidade legitimamente. A invariante
// (ativas <= capacidade, por evento) e conferida no banco pelo -Verificar do host.
//
// Variaveis (passadas pelo rodar-aleatorio.ps1):
//   BASE_URL, STAGES_FILE (json [{target, duration}] em RPM), USERS_FILE, EVENTS_FILE,
//   SUMMARY_FILE, PCT_LEITURA (0-100), SEMENTE, PREFIXO, MAX_VUS (opcional),
//   PCT_CONFIRMA (0-100, padrao 100)
import http from 'k6/http';
import exec from 'k6/execution';
import { randomSeed } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const PCT_LEITURA = parseInt(__ENV.PCT_LEITURA || '0', 10);
const PCT_CONFIRMA = parseInt(__ENV.PCT_CONFIRMA || '100', 10);
const PREFIXO = __ENV.PREFIXO || 'Ale';
// PowerShell 5.1 grava UTF8 com BOM; JSON.parse nao aceita BOM.
const lerJson = (f) => JSON.parse(open(f).replace(/^﻿/, ''));
const STAGES = lerJson(__ENV.STAGES_FILE);
const USERS = lerJson(__ENV.USERS_FILE);
const EVENTS = lerJson(__ENV.EVENTS_FILE);
const MAX_PARES = USERS.length * EVENTS.length;

const SEMENTE = parseInt(__ENV.SEMENTE || '1', 10);

// Passo coprimo com MAX_PARES: i -> (i * PASSO) % MAX_PARES e uma permutacao, entao os pares
// continuam unicos, mas espalhados entre os eventos em vez de lotar um por um.
function mdc(a, b) { return b === 0 ? a : mdc(b, a % b); }
let PASSO = 7919;
while (MAX_PARES > 1 && mdc(PASSO, MAX_PARES) !== 1) PASSO += 2;

const aceitas = new Counter('reservas_aceitas');
const lotado = new Counter('reservas_lotado');
const erros5xx = new Counter('reservas_5xx');
const outros = new Counter('reservas_outros');
const leiturasOk = new Counter('leituras_ok');
const leituras5xx = new Counter('leituras_5xx');
const leiturasOutros = new Counter('leituras_outros');
const semLogin = new Counter('usuarios_sem_login');
const alemDosPares = new Counter('iteracoes_alem_dos_pares');
const reservaDur = new Trend('reserva_duration', true);
const leituraDur = new Trend('leitura_duration', true);
// Leitura por endpoint (leituraDur segue sendo o agregado das tres).
const leituraEventoDur = new Trend('leitura_evento_duration', true);
const leituraCarteiraDur = new Trend('leitura_carteira_duration', true);
const leituraBuscaDur = new Trend('leitura_busca_duration', true);
const leituraEventoN = new Counter('leitura_evento_n');
const leituraCarteiraN = new Counter('leitura_carteira_n');
const leituraBuscaN = new Counter('leitura_busca_n');
const confirmadas = new Counter('confirma_aceitas');
const confirmaEsperado = new Counter('confirma_4xx_esperado');
const confirma5xx = new Counter('confirma_5xx');
const confirmaOutros = new Counter('confirma_outros');
const confirmaDur = new Trend('confirma_duration', true);

export const options = {
  scenarios: {
    aleatoria: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1m',
      stages: STAGES,
      preAllocatedVUs: 100,
      maxVUs: parseInt(__ENV.MAX_VUS || '500', 10),
    },
  },
  setupTimeout: '300s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Login de todos os usuarios ANTES da carga (nao entra na medicao).
export function setup() {
  const sessoes = [];
  for (let i = 0; i < USERS.length; i += 25) {
    const lote = USERS.slice(i, i + 25);
    const reqs = lote.map((u) => [
      'POST',
      `${BASE}/bff/users/login`,
      JSON.stringify({ email: u.email, password: u.password }),
      { headers: JSON_HEADERS, tags: { name: 'login' } },
    ]);
    http.batch(reqs).forEach((r) => {
      if (r.status === 200) {
        const b = r.json();
        // User serializa o id como "user_id" (@JsonProperty), nao "id".
        sessoes.push({ id: b.user.user_id, token: b.token });
      } else {
        sessoes.push(null);
      }
    });
  }
  const ok = sessoes.filter((s) => s).length;
  if (ok === 0) exec.test.abort('Nenhum login funcionou: confira usuarios semeados e BASE_URL.');
  console.log(`setup: ${ok}/${USERS.length} logins ok; ${EVENTS.length} eventos; ${MAX_PARES} pares unicos; ${STAGES.length} passos`);
  return sessoes;
}

// Ate 3 avisos por VU: mostra o corpo dos erros sem inundar o console.
let avisos = 0;
function avisar(tipo, res) {
  if (avisos >= 3) return;
  avisos++;
  console.warn(`${tipo}: HTTP ${res.status} ${String(res.body).slice(0, 160)}`);
}

// Sorteio deterministico em [0, 100) a partir de (indice, semente): decide quais aceitas confirmar
// sem consumir Math.random (nao altera o perfil/ordem do restante do cenario).
function sorteio100(i, semente) {
  let h = Math.imul(i ^ Math.imul(semente, 0x9e3779b1), 0x85ebca6b);
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35);
  h ^= h >>> 16;
  return (h >>> 0) % 100;
}

// Finaliza a reserva aceita: POST /bff/event-wallets/confirm (pending -> confirmed).
//   200                                    -> confirmada
//   400 "ja confirmada"/"nenhuma pendente" -> esperado (reserva expirou ou ja estava confirmada)
//   5xx                                    -> erro de servidor
//   outro                                  -> inesperado
function confirmar(s, eventId) {
  const res = http.post(
    `${BASE}/bff/event-wallets/confirm`,
    JSON.stringify({ userId: s.id, eventId }),
    {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${s.token}` },
      tags: { name: 'confirma' },
    }
  );
  confirmaDur.add(res.timings.duration);
  if (res.status === 200) {
    confirmadas.add(1);
  } else if (res.status === 400 && /confirmada|pendente/i.test(String(res.body))) {
    confirmaEsperado.add(1);
  } else if (res.status >= 500) {
    confirma5xx.add(1);
    avisar('confirma 5xx', res);
  } else {
    confirmaOutros.add(1);
    avisar('confirma inesperada', res);
  }
}

function reservar(sessoes, i) {
  if (i >= MAX_PARES) {
    alemDosPares.add(1); // pares repetidos dariam 400 de duplicidade: nao envia
    return;
  }
  const p = (i * PASSO) % MAX_PARES;
  const s = sessoes[p % USERS.length];
  if (!s) {
    semLogin.add(1);
    return;
  }
  const eventId = EVENTS[Math.floor(p / USERS.length)];
  const res = http.post(
    `${BASE}/bff/event-wallets`,
    JSON.stringify({ userId: s.id, eventId }),
    {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${s.token}` },
      tags: { name: 'reserva' },
    }
  );
  reservaDur.add(res.timings.duration);
  if (res.status === 201) {
    aceitas.add(1);
    if (sorteio100(p, SEMENTE) < PCT_CONFIRMA) confirmar(s, eventId);
  } else if (res.status === 400 && String(res.body).toLowerCase().indexOf('lotado') >= 0) {
    lotado.add(1);
  } else if (res.status >= 500) {
    erros5xx.add(1);
    avisar('reserva 5xx', res);
  } else {
    outros.add(1);
    avisar('reserva inesperada', res);
  }
}

function ler(sessoes) {
  const sorteio = Math.random();
  let res;
  let trend;
  let cont;
  if (sorteio < 0.5) {
    const eventId = EVENTS[Math.floor(Math.random() * EVENTS.length)];
    res = http.get(`${BASE}/bff/events/${eventId}`, { tags: { name: 'leitura_evento' } });
    trend = leituraEventoDur;
    cont = leituraEventoN;
  } else if (sorteio < 0.8) {
    const s = sessoes[Math.floor(Math.random() * sessoes.length)];
    if (!s) {
      semLogin.add(1);
      return;
    }
    res = http.get(`${BASE}/bff/event-wallets/user/${s.id}`, {
      headers: { Authorization: `Bearer ${s.token}` },
      tags: { name: 'leitura_carteira' },
    });
    trend = leituraCarteiraDur;
    cont = leituraCarteiraN;
  } else {
    res = http.get(`${BASE}/bff/events/search?term=${encodeURIComponent(PREFIXO)}`, {
      tags: { name: 'leitura_busca' },
    });
    trend = leituraBuscaDur;
    cont = leituraBuscaN;
  }
  leituraDur.add(res.timings.duration);
  trend.add(res.timings.duration);
  cont.add(1);
  if (res.status >= 200 && res.status < 300) {
    leiturasOk.add(1);
  } else if (res.status >= 500) {
    leituras5xx.add(1);
    avisar('leitura 5xx', res);
  } else {
    leiturasOutros.add(1);
    avisar('leitura inesperada', res);
  }
}

// Semente por VU (o id do VU nao existe no init context): sorteios diferentes entre VUs,
// mas reprodutiveis para a mesma SEMENTE.
let semeado = false;

export default function (sessoes) {
  if (!semeado) {
    randomSeed(SEMENTE * 1000 + exec.vu.idInTest);
    semeado = true;
  }
  if (Math.random() * 100 < PCT_LEITURA) {
    ler(sessoes);
  } else {
    reservar(sessoes, exec.scenario.iterationInTest);
  }
}

function contador(data, nome) {
  const m = data.metrics[nome];
  return m && m.values && m.values.count ? m.values.count : 0;
}

export function handleSummary(data) {
  const tr = (data.metrics.reserva_duration || {}).values || {};
  const tl = (data.metrics.leitura_duration || {}).values || {};
  const tle = (data.metrics.leitura_evento_duration || {}).values || {};
  const tlc = (data.metrics.leitura_carteira_duration || {}).values || {};
  const tlb = (data.metrics.leitura_busca_duration || {}).values || {};
  const tc = (data.metrics.confirma_duration || {}).values || {};
  const duracaoS = (data.state.testRunDurationMs || 0) / 1000;
  const a = contador(data, 'reservas_aceitas');
  const l = contador(data, 'reservas_lotado');
  const e5 = contador(data, 'reservas_5xx');
  const o = contador(data, 'reservas_outros');
  const lok = contador(data, 'leituras_ok');
  const l5 = contador(data, 'leituras_5xx');
  const lo = contador(data, 'leituras_outros');
  const total = a + l + e5 + o + lok + l5 + lo;
  const rpmAlvoMax = STAGES.reduce((m, s) => Math.max(m, s.target), 0);
  const out = {
    rpm_medio: duracaoS > 0 ? (total / duracaoS) * 60 : 0,
    rpm_alvo_max: rpmAlvoMax,
    duracao_s: duracaoS,
    passos: STAGES.length,
    usuarios: USERS.length,
    eventos: EVENTS.length,
    aceitas: a,
    lotado: l,
    erros_5xx: e5,
    outros: o,
    leituras_ok: lok,
    leituras_5xx: l5,
    leituras_outros: lo,
    sem_login: contador(data, 'usuarios_sem_login'),
    descartadas: contador(data, 'dropped_iterations'),
    alem_dos_pares: contador(data, 'iteracoes_alem_dos_pares'),
    vus_max: ((data.metrics.vus_max || {}).values || {}).max || 0,
    reserva_p50_ms: tr.med || 0,
    reserva_p95_ms: tr['p(95)'] || 0,
    reserva_p99_ms: tr['p(99)'] || 0,
    leitura_p50_ms: tl.med || 0,
    leitura_p95_ms: tl['p(95)'] || 0,
    leitura_p99_ms: tl['p(99)'] || 0,
    max_ms: Math.max(tr.max || 0, tl.max || 0),
    pct_confirma: PCT_CONFIRMA,
    confirmadas: contador(data, 'confirma_aceitas'),
    confirma_4xx_esperado: contador(data, 'confirma_4xx_esperado'),
    confirma_5xx: contador(data, 'confirma_5xx'),
    confirma_outros: contador(data, 'confirma_outros'),
    confirma_p50_ms: tc.med || 0,
    confirma_p95_ms: tc['p(95)'] || 0,
    confirma_p99_ms: tc['p(99)'] || 0,
    leitura_evento_p50_ms: tle.med || 0,
    leitura_evento_p95_ms: tle['p(95)'] || 0,
    leitura_evento_p99_ms: tle['p(99)'] || 0,
    leitura_carteira_p50_ms: tlc.med || 0,
    leitura_carteira_p95_ms: tlc['p(95)'] || 0,
    leitura_carteira_p99_ms: tlc['p(99)'] || 0,
    leitura_busca_p50_ms: tlb.med || 0,
    leitura_busca_p95_ms: tlb['p(95)'] || 0,
    leitura_busca_p99_ms: tlb['p(99)'] || 0,
    leitura_evento_n: contador(data, 'leitura_evento_n'),
    leitura_carteira_n: contador(data, 'leitura_carteira_n'),
    leitura_busca_n: contador(data, 'leitura_busca_n'),
  };
  return {
    [__ENV.SUMMARY_FILE]: JSON.stringify(out, null, 2),
    stdout: `\nresumo: rpm_medio=${out.rpm_medio.toFixed(0)} (pico alvo ${rpmAlvoMax}) aceitas=${a} confirmadas=${out.confirmadas} lotado=${l} 5xx=${e5 + l5 + out.confirma_5xx} leituras=${lok} descartadas=${out.descartadas}\nleitura p95 (n): evento=${out.leitura_evento_p95_ms.toFixed(0)}ms (${contador(data, 'leitura_evento_n')}) carteira=${out.leitura_carteira_p95_ms.toFixed(0)}ms (${contador(data, 'leitura_carteira_n')}) busca=${out.leitura_busca_p95_ms.toFixed(0)}ms (${contador(data, 'leitura_busca_n')})\n`,
  };
}
