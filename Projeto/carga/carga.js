// Cenario k6: N clientes concorrentes disputando as vagas de UM evento.
// Cada usuario faz 1 tentativa: POST /bff/event-wallets {userId, eventId}.
//   201                    -> reserva aceita
//   400 "Evento lotado"    -> rejeitada por falta de vaga
//   outro                  -> erro inesperado
// Overselling = aceitas - capacidade (esperado 0 no caminho CAS).
// Cada reserva aceita (201) e finalizada na mesma iteracao com POST /bff/event-wallets/confirm
// (200 = confirmada) com PCT_CONFIRMA % de chance (padrao 100: ao fim as vagas ficam `confirmed`). Metricas de reserva e de
// confirma sao separadas.
//
// Chamado pelo rodar-teste.ps1 com as variaveis de ambiente:
//   BASE_URL, EVENT_ID, USERS_FILE (json [{email,password}]), SUMMARY_FILE, CAPACITY,
//   PCT_CONFIRMA (0-100, padrao 100: % das aceitas que confirmam; as demais ficam pending e expiram)
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const EVENT_ID = parseInt(__ENV.EVENT_ID, 10);
const CAPACITY = parseInt(__ENV.CAPACITY, 10);
const PCT_CONFIRMA = parseInt(__ENV.PCT_CONFIRMA || '100', 10);
// PowerShell 5.1 grava UTF8 com BOM; JSON.parse nao aceita BOM.
const USERS = JSON.parse(open(__ENV.USERS_FILE).replace(/^﻿/, ''));

const aceitas = new Counter('reservas_aceitas');
const lotado = new Counter('reservas_lotado');
const outros = new Counter('reservas_outros');
const semLogin = new Counter('usuarios_sem_login');
const reservaDur = new Trend('reserva_duration', true);
const confirmadas = new Counter('confirma_aceitas');
const confirmaEsperado = new Counter('confirma_4xx_esperado');
const confirma5xx = new Counter('confirma_5xx');
const confirmaOutros = new Counter('confirma_outros');
const confirmaDur = new Trend('confirma_duration', true);

export const options = {
  scenarios: {
    disputa: {
      executor: 'shared-iterations',
      vus: USERS.length,
      iterations: USERS.length,
      maxDuration: '120s',
    },
  },
  setupTimeout: '300s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Login de todos os usuarios ANTES da disputa (nao entra na medicao).
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
    const resps = http.batch(reqs);
    resps.forEach((r) => {
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
  if (ok === 0) {
    exec.test.abort('Nenhum login funcionou: confira usuarios semeados e BASE_URL.');
  }
  console.log(`setup: ${ok}/${USERS.length} logins ok`);
  return sessoes;
}

// Sorteio deterministico em [0, 100) a partir do indice: decide quais aceitas confirmar
// (mesma funcao do rpm/aleatorio, semente 0; nao consome Math.random).
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
    console.warn(`confirma 5xx: HTTP ${res.status} ${String(res.body).slice(0, 120)}`);
  } else {
    confirmaOutros.add(1);
    console.warn(`confirma inesperada: HTTP ${res.status} ${String(res.body).slice(0, 120)}`);
  }
}

export default function (sessoes) {
  const s = sessoes[exec.scenario.iterationInTest];
  if (!s) {
    semLogin.add(1);
    return;
  }
  const res = http.post(
    `${BASE}/bff/event-wallets`,
    JSON.stringify({ userId: s.id, eventId: EVENT_ID }),
    {
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${s.token}` },
      tags: { name: 'reserva' },
    }
  );
  reservaDur.add(res.timings.duration);
  if (res.status === 201) {
    aceitas.add(1);
    if (sorteio100(exec.scenario.iterationInTest, 0) < PCT_CONFIRMA) confirmar(s, EVENT_ID);
  } else if (res.status === 400 && String(res.body).toLowerCase().indexOf('lotado') >= 0) {
    lotado.add(1);
  } else {
    outros.add(1);
    if (exec.scenario.iterationInTest < 5) {
      console.warn(`resposta inesperada: HTTP ${res.status} ${String(res.body).slice(0, 120)}`);
    }
  }
}

function contador(data, nome) {
  const m = data.metrics[nome];
  return m && m.values && m.values.count ? m.values.count : 0;
}

export function handleSummary(data) {
  const t = (data.metrics.reserva_duration || {}).values || {};
  const tc = (data.metrics.confirma_duration || {}).values || {};
  const duracaoS = (data.state.testRunDurationMs || 0) / 1000;
  const tentativas = contador(data, 'reservas_aceitas') + contador(data, 'reservas_lotado') + contador(data, 'reservas_outros');
  const out = {
    capacidade: CAPACITY,
    clientes: USERS.length,
    aceitas: contador(data, 'reservas_aceitas'),
    lotado: contador(data, 'reservas_lotado'),
    outros: contador(data, 'reservas_outros'),
    sem_login: contador(data, 'usuarios_sem_login'),
    overselling_cliente: Math.max(0, contador(data, 'reservas_aceitas') - CAPACITY),
    p50_ms: t.med || 0,
    p95_ms: t['p(95)'] || 0,
    p99_ms: t['p(99)'] || 0,
    max_ms: t.max || 0,
    pct_confirma: PCT_CONFIRMA,
    confirmadas: contador(data, 'confirma_aceitas'),
    confirma_4xx_esperado: contador(data, 'confirma_4xx_esperado'),
    confirma_5xx: contador(data, 'confirma_5xx'),
    confirma_outros: contador(data, 'confirma_outros'),
    confirma_p50_ms: tc.med || 0,
    confirma_p95_ms: tc['p(95)'] || 0,
    confirma_p99_ms: tc['p(99)'] || 0,
    duracao_s: duracaoS,
    reservas_por_s: duracaoS > 0 ? tentativas / duracaoS : 0,
  };
  return {
    [__ENV.SUMMARY_FILE]: JSON.stringify(out, null, 2),
    stdout: `\nresumo: aceitas=${out.aceitas} lotado=${out.lotado} outros=${out.outros} confirmadas=${out.confirmadas} confirma_5xx=${out.confirma_5xx} p95=${out.p95_ms.toFixed(1)}ms\n`,
  };
}
