// Cenario k6 de TAXA FIXA (modelo aberto): RPM reservas por minuto, espalhadas em varios eventos.
// Cada iteracao e um par (usuario, evento) unico: i -> usuario i % U, evento floor(i / U).
// Assim poucos usuarios (login com bcrypt e caro) geram milhares de reservas sem duplicidade.
//   201                  -> aceita
//   400 "lotado"         -> rejeitada por falta de vaga
//   5xx                  -> erro de servidor (ex.: too many clients no Postgres)
//   outro                -> inesperado
//
// Variaveis (passadas pelo rodar-rpm.ps1):
//   BASE_URL, RPM, DURACAO_S, USERS_FILE (json [{email,password}]), EVENTS_FILE (json [id,...]),
//   SUMMARY_FILE, MAX_VUS (opcional)
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const RPM = parseInt(__ENV.RPM, 10);
const DURACAO_S = parseInt(__ENV.DURACAO_S, 10);
// PowerShell 5.1 grava UTF8 com BOM; JSON.parse nao aceita BOM.
const lerJson = (f) => JSON.parse(open(f).replace(/^﻿/, ''));
const USERS = lerJson(__ENV.USERS_FILE);
const EVENTS = lerJson(__ENV.EVENTS_FILE);
const MAX_PARES = USERS.length * EVENTS.length;

const aceitas = new Counter('reservas_aceitas');
const lotado = new Counter('reservas_lotado');
const erros5xx = new Counter('reservas_5xx');
const outros = new Counter('reservas_outros');
const semLogin = new Counter('usuarios_sem_login');
const alemDosPares = new Counter('iteracoes_alem_dos_pares');
const reservaDur = new Trend('reserva_duration', true);

export const options = {
  scenarios: {
    taxa: {
      executor: 'constant-arrival-rate',
      rate: RPM,
      timeUnit: '1m',
      duration: `${DURACAO_S}s`,
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
  console.log(`setup: ${ok}/${USERS.length} logins ok; ${EVENTS.length} eventos; ${MAX_PARES} pares unicos`);
  return sessoes;
}

export default function (sessoes) {
  const i = exec.scenario.iterationInTest;
  if (i >= MAX_PARES) {
    alemDosPares.add(1); // pares repetidos dariam 400 de duplicidade: nao envia
    return;
  }
  const s = sessoes[i % USERS.length];
  if (!s) {
    semLogin.add(1);
    return;
  }
  const eventId = EVENTS[Math.floor(i / USERS.length)];
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
  } else if (res.status === 400 && String(res.body).toLowerCase().indexOf('lotado') >= 0) {
    lotado.add(1);
  } else if (res.status >= 500) {
    erros5xx.add(1);
    if (exec.scenario.iterationInTest < 5) console.warn(`5xx: HTTP ${res.status} ${String(res.body).slice(0, 160)}`);
  } else {
    outros.add(1);
    if (exec.scenario.iterationInTest < 5) console.warn(`inesperado: HTTP ${res.status} ${String(res.body).slice(0, 160)}`);
  }
}

function contador(data, nome) {
  const m = data.metrics[nome];
  return m && m.values && m.values.count ? m.values.count : 0;
}

export function handleSummary(data) {
  const t = (data.metrics.reserva_duration || {}).values || {};
  const duracaoS = (data.state.testRunDurationMs || 0) / 1000;
  const a = contador(data, 'reservas_aceitas');
  const l = contador(data, 'reservas_lotado');
  const e5 = contador(data, 'reservas_5xx');
  const o = contador(data, 'reservas_outros');
  const tentativas = a + l + e5 + o;
  const out = {
    rpm_alvo: RPM,
    rpm_real: duracaoS > 0 ? (tentativas / duracaoS) * 60 : 0,
    duracao_s: duracaoS,
    usuarios: USERS.length,
    eventos: EVENTS.length,
    aceitas: a,
    lotado: l,
    erros_5xx: e5,
    outros: o,
    sem_login: contador(data, 'usuarios_sem_login'),
    descartadas: contador(data, 'dropped_iterations'),
    alem_dos_pares: contador(data, 'iteracoes_alem_dos_pares'),
    vus_max: ((data.metrics.vus_max || {}).values || {}).max || 0,
    p50_ms: t.med || 0,
    p95_ms: t['p(95)'] || 0,
    p99_ms: t['p(99)'] || 0,
    max_ms: t.max || 0,
  };
  return {
    [__ENV.SUMMARY_FILE]: JSON.stringify(out, null, 2),
    stdout: `\nresumo: rpm_real=${out.rpm_real.toFixed(0)}/${RPM} aceitas=${a} lotado=${l} 5xx=${e5} outros=${o} descartadas=${out.descartadas} p95=${out.p95_ms.toFixed(1)}ms\n`,
  };
}
