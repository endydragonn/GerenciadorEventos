// Auto-teste do carga-degraus.js (issue #178) SEM k6: um loader do node troca k6, k6/http, k6/execution e k6/metrics por stubs e o script roda de
// verdade (options, setup, a iteracao e o handleSummary) contra um servidor falso. Cobre o que o k6 nao deixa testar sem a stack: 201/202/429, o
// acompanhamento do estado, os contadores, a trava do resumo, a parada e o degrau parcial/omitido.
// Uso: node degraus/carga-degraus.selftest.mjs   (node >= 22.7; sai com codigo != 0 se algo falhar)
import assert from 'node:assert/strict';
import { register } from 'node:module';
import { pathToFileURL } from 'node:url';

const STUBS = {
  'k6': `export function sleep(s) { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, s * 1000); }`,
  'k6/http': `export default { post: (u, b, p) => globalThis.__k6.http('POST', u, b, p), get: (u, p) => globalThis.__k6.http('GET', u, null, p),
    batch: (reqs) => reqs.map((r) => globalThis.__k6.http(r[0], r[1], r[2], r[3])) };`,
  'k6/execution': `export default globalThis.__k6.exec;`,
  'k6/metrics': `const reg = globalThis.__k6.metricas;
    export class Counter { constructor(n) { this.n = n; reg[n] = []; } add(v, t) { reg[this.n].push({ v, t: t || {} }); } }
    export class Trend { constructor(n) { this.n = n; reg[n] = []; } add(v, t) { reg[this.n].push({ v, t: t || {} }); } }`,
};
register('data:text/javascript,' + encodeURIComponent(`
  const S = ${JSON.stringify(STUBS)};
  export async function resolve(spec, ctx, next) { if (S[spec]) return { url: 'stub:' + spec, shortCircuit: true }; return next(spec, ctx); }
  export async function load(url, ctx, next) { if (url.startsWith('stub:')) return { format: 'module', source: S[url.slice(5)], shortCircuit: true }; return next(url, ctx); }
`));

let falhas = 0;
async function confere(nome, fn) {
  try { await fn(); console.log(`   [ok]    ${nome}`); }
  catch (e) { falhas++; console.log(`   [FALHA] ${nome}\n           ${String(e.message).split('\n').join('\n           ')}`); }
}

// ---- servidor falso: respostas roteiradas por rota
const k6 = globalThis.__k6 = { metricas: {}, exec: { scenario: { name: 'degrau_1000', iterationInTest: 0, startTime: Date.now() }, test: { abort: (m) => { throw new Error('abort: ' + m); } } } };
let estados = [];            // fila de respostas do GET /admission
let postStatus = 201;
let postHeaders = { 'Idempotency-Key': 'srv-key' };   // o 202 do gateway devolve a chave no header (e no corpo)
const chamadas = [];
k6.http = (metodo, url, corpo, params) => {
  chamadas.push({ metodo, url, corpo, params });
  const resp = (status, body = '', headers = {}) => ({ status, body, headers, timings: { duration: 12 }, json: () => JSON.parse(body) });
  if (url.endsWith('/bff/event-wallets')) return resp(postStatus, postStatus === 202 ? '{"status":"queued","idempotencyKey":"srv-key"}' : '{}', postHeaders);
  if (url.includes('/bff/event-wallets/admission')) { const e = estados.length > 1 ? estados.shift() : estados[0]; return resp(200, JSON.stringify({ status: e })); }
  if (url.endsWith('/confirm')) return resp(200);
  if (url.endsWith('/login')) return resp(200, JSON.stringify({ user: { user_id: 7 }, token: 't' }));
  return resp(404);
};

const env = { BASE_URL: 'http://h:8081', DURACAO_S: '60', RPMS: '1000,2000', USERS_FILE: 'u', EVENTS_FILE: 'e', SUMMARY_FILE: 's.json',
  CONSULTA_MS: '50', TETO_CONSULTA_S: '1', PARADA_P95_MS: '5000', PARADA_JANELA_S: '30', RUN_ID: 'teste' };
globalThis.__ENV = env;
globalThis.open = (f) => JSON.stringify(f === 'u' ? Array.from({ length: 100 }, (_, i) => ({ email: `u${i}@x`, password: 'p' })) : Array.from({ length: 40 }, (_, i) => 101 + i));

const script = await import(pathToFileURL(new URL('./carga-degraus.js', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')).href);
const soma = (nome) => (k6.metricas[nome] || []).reduce((a, x) => a + x.v, 0);
const zera = () => { for (const k of Object.keys(k6.metricas)) k6.metricas[k] = []; chamadas.length = 0; };

console.log('==> options');
await confere('gracefulStop = max(30, teto + 10) com acompanhamento; thresholds de parada: 1 por janela, abortOnFail, atraso no fim da janela', () => {
  const sc = script.options.scenarios;
  assert.equal(sc.degrau_1000.gracefulStop, '30s');
  assert.equal(sc.degrau_2000.startTime, '91s');
  const t = script.options.thresholds['reserva_duration{parada:degrau_2000_1}'];
  assert.deepEqual(t, [{ threshold: 'p(95)<5000', abortOnFail: true, delayAbortEval: '151s' }]);
  assert.equal(Object.keys(script.options.thresholds).filter((k) => k.includes('parada:')).length, 4);
  assert.ok(script.options.thresholds['adm_admitir_duration{scenario:degrau_1000}']);
});

const sessoes = script.setup();
console.log('==> iteracao');
await confere('201: aceita, confirma e latencia ponta a ponta; SEM Idempotency-Key propria por padrao (sincrono identico ao da R)', () => {
  zera(); postStatus = 201; k6.exec.scenario.iterationInTest = 0;
  script.default(sessoes);
  assert.equal(soma('reservas_aceitas'), 1);
  assert.equal(soma('confirma_aceitas'), 1);
  assert.equal(k6.metricas.fim_a_fim_duration.length, 1);
  assert.equal(chamadas[0].params.headers['Idempotency-Key'], undefined);
  assert.equal(k6.metricas.reserva_duration[0].t.parada, 'degrau_1000_0');
});
await confere('202 + queued, queued, pending: nao e outros; acompanha, confirma, tempo ate admitir e 3 consultas', () => {
  zera(); postStatus = 202; estados = ['queued', 'queued', 'pending']; k6.exec.scenario.iterationInTest = 1;
  script.default(sessoes);
  assert.equal(soma('reservas_202'), 1);
  assert.equal(soma('reservas_outros'), 0);
  assert.equal(soma('adm_acompanhadas'), 1);
  assert.equal(soma('adm_admitidas'), 1);
  assert.equal(soma('adm_consultas'), 3);
  assert.equal(soma('confirma_aceitas'), 1);
  assert.ok(soma('adm_admitir_duration') >= 100);
  assert.equal(k6.metricas.fim_a_fim_duration.length, 1);
  assert.ok(chamadas.some((c) => c.url.includes('idempotencyKey=srv-key')));   // a chave veio do 202
});
await confere('202 + rejected / timed_out: contados, sem confirma; fim a fim registrado', () => {
  zera(); postStatus = 202; estados = ['rejected']; k6.exec.scenario.iterationInTest = 2; script.default(sessoes);
  estados = ['timed_out']; k6.exec.scenario.iterationInTest = 3; script.default(sessoes);
  assert.equal(soma('adm_rejected'), 1);
  assert.equal(soma('adm_timed_out'), 1);
  assert.equal(soma('confirma_aceitas'), 0);
  assert.equal(k6.metricas.fim_a_fim_duration.length, 2);
});
await confere('202 que nunca sai de queued: para no teto (sem_estado), sem fim a fim', () => {
  zera(); postStatus = 202; estados = ['queued']; k6.exec.scenario.iterationInTest = 4;
  script.default(sessoes);
  assert.equal(soma('adm_sem_estado'), 1);
  assert.equal(k6.metricas.fim_a_fim_duration.length, 0);
});
await confere('iteracao cortada no meio do acompanhamento (k6 aborta/gracefulStop): nada de acompanhada sem destino, a trava do resumo segue fechando', () => {
  zera(); postStatus = 202; estados = ['queued']; k6.exec.scenario.iterationInTest = 8;
  const http0 = k6.http; let gets = 0;
  k6.http = (m, u, b, p) => { if (u.includes('/admission')) { gets++; if (gets === 2) throw new Error('interrompido pelo k6'); } return http0(m, u, b, p); };
  try { assert.throws(() => script.default(sessoes), /interrompido/); } finally { k6.http = http0; }
  assert.equal(soma('reservas_202'), 1);
  assert.equal(soma('adm_acompanhadas'), 0);
  assert.equal(soma('adm_consultas'), 0);
  assert.equal(soma('adm_admitidas') + soma('adm_rejected') + soma('adm_timed_out') + soma('adm_sem_estado'), 0);
});
await confere('429 e 422 tem contador proprio (nao viram outros nem 5xx)', () => {
  zera(); postStatus = 429; k6.exec.scenario.iterationInTest = 5; script.default(sessoes);
  postStatus = 422; k6.exec.scenario.iterationInTest = 6; script.default(sessoes);
  assert.equal(soma('reservas_429'), 1);
  assert.equal(soma('reservas_422'), 1);
  assert.equal(soma('reservas_outros') + soma('reservas_5xx'), 0);
});
await confere('503 da reserva = 5xx; 201 sem acompanhar nada: so a confirma', () => {
  zera(); postStatus = 503; k6.exec.scenario.iterationInTest = 7; script.default(sessoes);
  assert.equal(soma('reservas_5xx'), 1);
});

console.log('==> handleSummary');
const metrica = (valores, extra = {}) => ({ type: 'x', contains: 'default', values: valores, ...extra });
const cnt = (n) => metrica({ count: n });
const trend = (n, p95) => metrica({ count: n, med: p95 / 2, 'p(95)': p95, 'p(99)': p95 * 1.2 });
function dados({ comParada }) {
  const m = {};
  const d1 = 'scenario:degrau_1000';
  m[`reserva_duration{${d1}}`] = trend(100, 80);
  m[`confirma_duration{${d1}}`] = trend(80, 20);
  m[`reserva_duration{janela:degrau_1000_0}`] = trend(50, 70);
  m[`reserva_duration{janela:degrau_1000_1}`] = trend(50, 90);
  m[`reservas_202{${d1}}`] = cnt(20); m[`adm_acompanhadas{${d1}}`] = cnt(10); m[`adm_admitidas{${d1}}`] = cnt(6);
  m[`adm_rejected{${d1}}`] = cnt(2); m[`adm_timed_out{${d1}}`] = cnt(1); m[`adm_sem_estado{${d1}}`] = cnt(1); m[`adm_consultas{${d1}}`] = cnt(30);
  m[`fim_a_fim_duration{${d1}}`] = trend(9, 400); m[`adm_admitir_duration{${d1}}`] = trend(6, 1500);
  const d2 = 'scenario:degrau_2000';
  m[`reserva_duration{${d2}}`] = trend(30, 9000);
  m[`reserva_duration{janela:degrau_2000_0}`] = trend(30, 9000);
  m[`reserva_duration{parada:degrau_2000_0}`] = trend(30, 9000);
  m[`reserva_duration{parada:degrau_2000_0}`].thresholds = { 'p(95)<5000': { ok: !comParada } };   // o k6 sempre traz o campo thresholds
  m['reservas_202'] = cnt(20); m['adm_acompanhadas'] = cnt(10); m['adm_admitidas'] = cnt(6); m['adm_rejected'] = cnt(2);
  m['adm_timed_out'] = cnt(1); m['adm_sem_estado'] = cnt(1); m['adm_consultas'] = cnt(30); m['fim_a_fim_duration'] = trend(9, 400);
  m['reserva_duration'] = cnt(130); m['confirma_duration'] = cnt(80);
  return { metrics: m, state: { testRunDurationMs: 100000 } };
}
function resumo(opts) {
  const out = script.handleSummary(dados(opts));
  return { json: JSON.parse(out['s.json']), texto: out.stdout };
}
await confere('sem parada: todos completos, trava fecha (sem inconsistencias), contadores do modo fila no resumo', () => {
  const { json } = resumo({ comParada: false });
  assert.equal(json.parada, null);
  assert.equal(json.parada_texto, 'nenhuma');
  const d = json.degraus[0];
  assert.equal(d.situacao, 'completo');
  assert.deepEqual([d.reservas_202, d.acompanhadas, d.admitidas, d.rejected, d.timed_out, d.sem_estado, d.consultas_estado], [20, 10, 6, 2, 1, 1, 30]);
  assert.equal(d.admitir_p95_ms, 1500);
  assert.equal(d.fim_a_fim_p95_ms, 400);
  assert.deepEqual(json.inconsistencias.filter((x) => !/reserva_duration|confirma_duration|fora das janelas/.test(x)), []);
});
await confere('com parada na janela 0 do degrau 2000: degrau parcial (30 s efetivos), anterior completo, parada registrada', () => {
  const { json, texto } = resumo({ comParada: true });
  assert.equal(json.parada.degrau, 'degrau_2000');
  assert.equal(json.degraus[0].situacao, 'completo');
  assert.equal(json.degraus[1].situacao, 'parcial');
  assert.equal(json.degraus[1].duracao_efetiva_s, 30);
  assert.equal(json.parada_texto, 'p95_acima_de_5000ms_em_janela_de_30s_no_degrau_2000');
  assert.match(texto, /PARADA: /);
});
await confere('parada na janela 0 com amostras na janela 1 (o abort cai ~2 s depois do fim da janela): a duracao efetiva continua (pw+1) x 30 = 30 s', () => {
  const dd = dados({ comParada: true });
  dd.metrics['reserva_duration{janela:degrau_2000_1}'] = trend(5, 9500);
  const json = JSON.parse(script.handleSummary(dd)['s.json']);
  assert.equal(json.degraus[1].situacao, 'parcial');
  assert.equal(json.degraus[1].duracao_efetiva_s, 30);
});

console.log('==> chave de idempotencia');
await confere('202 sem header: a chave vem do corpo (idempotencyKey)', () => {
  zera(); postStatus = 202; postHeaders = {}; estados = ['pending']; k6.exec.scenario.iterationInTest = 9;
  script.default(sessoes);
  postHeaders = { 'Idempotency-Key': 'srv-key' };
  assert.equal(soma('adm_acompanhadas'), 1);
  assert.ok(chamadas.some((c) => c.url.includes('idempotencyKey=srv-key')));
});
await confere('202 sem chave nenhuma (nem header nem corpo): nao acompanha (nao inventa chave) mas conta o 202', () => {
  zera(); postStatus = 202; postHeaders = {}; k6.exec.scenario.iterationInTest = 10;
  const http0 = k6.http;
  k6.http = (m, u, b, p) => (u.endsWith('/bff/event-wallets') ? { status: 202, body: '{"status":"queued"}', headers: {}, timings: { duration: 1 } } : http0(m, u, b, p));
  try { script.default(sessoes); } finally { k6.http = http0; postHeaders = { 'Idempotency-Key': 'srv-key' }; }
  assert.equal(soma('reservas_202'), 1);
  assert.equal(soma('adm_acompanhadas'), 0);
});
// opt-in: IDEMPOTENCY_KEY=1 num import novo do mesmo script (a constante e lida no carregamento)
globalThis.__ENV = { ...env, IDEMPOTENCY_KEY: '1' };
const comChave = await import(pathToFileURL(new URL('./carga-degraus.js', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')).href + '?comchave');
await confere('IDEMPOTENCY_KEY=1: chave propria por iteracao no POST e usada no acompanhamento', () => {
  zera(); postStatus = 202; estados = ['pending']; k6.exec.scenario.iterationInTest = 11;
  comChave.default(comChave.setup());
  const post = chamadas.find((c) => c.url.endsWith('/bff/event-wallets'));
  assert.equal(post.params.headers['Idempotency-Key'], 'k6-teste-11');
  assert.ok(chamadas.some((c) => c.url.includes('idempotencyKey=k6-teste-11')));
});

// sem USERS_FILE/EVENTS_FILE (o gerador da madrugada so roda k6 run): usuarios por USUARIOS + SUFIXO e eventos por EVENT_IDS; nenhum open()
globalThis.__ENV = { ...env, USERS_FILE: undefined, EVENTS_FILE: undefined, USUARIOS: '100', SUFIXO: '777-r4', EVENT_IDS: '201-239,250' };
globalThis.open = () => { throw new Error('open() nao deveria ser chamado sem USERS_FILE/EVENTS_FILE'); };
const semArquivos = await import(pathToFileURL(new URL('./carga-degraus.js', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')).href + '?semarquivos');
await confere('sem arquivos: logins pelo padrao do seed (e-mail e senha do sufixo), eventos de EVENT_IDS, resumo conta usuarios/eventos', () => {
  zera();
  const s = semArquivos.setup();
  assert.equal(s.length, 100);
  const login = chamadas.filter((c) => c.url.endsWith('/login'));
  assert.equal(login.length, 100);
  assert.deepEqual(JSON.parse(login[0].corpo), { email: 'carga-att1-777-r4@example.com', password: 'CargaTeste#777-r4' });
  const j = JSON.parse(semArquivos.handleSummary(dados({ comParada: false }))['s.json']);
  assert.equal(j.usuarios, 100);
  assert.equal(j.eventos, 40);
});

console.log(falhas === 0 ? '\nselftest carga-degraus: OK' : `\nselftest carga-degraus: ${falhas} falha(s)`);
process.exit(falhas === 0 ? 0 : 1);
