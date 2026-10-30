// Cenario k6 do dry-run de carga com autoescala (issue #197, etapa 4b). Mesmo gerador do kit de carga (k6, login em lotes de 25 no setup, fora da medicao), mas em MODELO ABERTO e
// em fases: roda DENTRO do cluster (pod grafana/k6) e fala com o gateway pelo Service (http://backend:8081), como um cliente fora da malha.
//   MODO=reserva  POST /bff/event-wallets no MESMO evento (EVENT_ID), usuarios em rodizio (cada tentativa tem Idempotency-Key propria). 201/202 = aceita; 400/422/429 = recusa de negocio.
//   MODO=leitura  GET /bff/events/<EVENT_ID> (publico: gateway -> catalog -> reservation/stock), sem login.
//   MODO=fundo    trafego leve e continuo (1 reserva para cada 2 leituras): um 5xx em qualquer descida de replica aparece aqui.
// Variaveis (-e): BASE_URL, MODO, EVENT_ID, STAGES ("10s:20,10s:2": duracao:taxa em req/s, em ordem), PREFIXO (e-mail dos usuarios: <PREFIXO>-<i>@example.com, senha senha123),
// USUARIOS (60), MAX_VUS (400), SUMMARY_FILE (resumo JSON opcional).
// Saida que o dry-run.sh le: uma linha `ERRO5XX <epoch_s> <status> <nome>` por resposta 5xx ou falha de conexao (status 0) e, no fim, `RESUMO ...` com os contadores por classe.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://backend:8081';
const MODO = __ENV.MODO;
if (!['reserva', 'leitura', 'fundo'].includes(MODO)) throw new Error(`MODO invalido: ${MODO}`);
const EVENT_ID = parseInt(__ENV.EVENT_ID, 10);
const USUARIOS = parseInt(__ENV.USUARIOS || '60', 10);
const PREFIXO = __ENV.PREFIXO || '';
const MAX_VUS = parseInt(__ENV.MAX_VUS || '400', 10);

const stages = (__ENV.STAGES || '').split(',').filter((s) => s).map((s) => {
  const [duration, alvo] = s.split(':');
  return { duration, target: parseFloat(alvo) };
});
if (stages.length === 0) throw new Error('STAGES vazio (ex.: 10s:20,10s:2)');

const c2xx = new Counter('r_2xx');
const c4xx = new Counter('r_4xx');
const c5xx = new Counter('r_5xx');
const c202 = new Counter('r_202');

export const options = {
  scenarios: {
    carga: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(0, stages[0].target),
      timeUnit: '1s',
      preAllocatedVUs: Math.min(100, MAX_VUS),
      maxVUs: MAX_VUS,
      stages,
    },
  },
  setupTimeout: '300s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function setup() {
  if (MODO === 'leitura') return [];
  const sessoes = [];
  for (let i = 1; i <= USUARIOS; i += 25) {
    const reqs = [];
    for (let j = i; j < i + 25 && j <= USUARIOS; j++) {
      reqs.push(['POST', `${BASE}/bff/users/login`, JSON.stringify({ email: `${PREFIXO}-${j}@example.com`, password: 'senha123' }),
        { headers: JSON_HEADERS, tags: { name: 'login' } }]);
    }
    http.batch(reqs).forEach((r) => { if (r.status === 200) sessoes.push(r.json().token); });
  }
  if (sessoes.length === 0) exec.test.abort('nenhum login funcionou: confira os usuarios do dry-run e BASE_URL');
  console.log(`setup: ${sessoes.length}/${USUARIOS} logins ok`);
  return sessoes;
}

function classificar(res, nome) {
  const s = res.status;
  if (s >= 200 && s < 300) { c2xx.add(1); if (s === 202) c202.add(1); }
  else if (s >= 400 && s < 500) c4xx.add(1);
  else { c5xx.add(1); console.log(`ERRO5XX ${Math.floor(Date.now() / 1000)} ${s} ${nome}`); } // 0 = falha de conexao/timeout
}

function ler() {
  classificar(http.get(`${BASE}/bff/events/${EVENT_ID}`, { timeout: '30s', tags: { name: 'leitura' } }), 'leitura');
}

function reservar(tokens) {
  const i = exec.scenario.iterationInTest;
  const res = http.post(`${BASE}/bff/event-wallets`, JSON.stringify({ eventId: EVENT_ID }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokens[i % tokens.length]}`, 'Idempotency-Key': `dry-${Date.now()}-${i}` },
    timeout: '30s', tags: { name: 'reserva' },
  });
  classificar(res, 'reserva');
}

export default function (tokens) {
  if (MODO === 'leitura') ler();
  else if (MODO === 'reserva') reservar(tokens);
  else if (exec.scenario.iterationInTest % 3 === 0) reservar(tokens);
  else ler();
}

export function handleSummary(data) {
  const v = (n) => (data.metrics[n] ? data.metrics[n].values.count : 0);
  const resumo = { modo: MODO, req: v('http_reqs'), r2xx: v('r_2xx'), r202: v('r_202'), r4xx: v('r_4xx'), r5xx: v('r_5xx'),
    p95_ms: data.metrics.http_req_duration ? Math.round(data.metrics.http_req_duration.values['p(95)']) : null,
    descartadas: v('dropped_iterations') };
  const saida = { stdout: `RESUMO ${JSON.stringify(resumo)}\n` };
  if (__ENV.SUMMARY_FILE) saida[__ENV.SUMMARY_FILE] = JSON.stringify(resumo);
  return saida;
}
