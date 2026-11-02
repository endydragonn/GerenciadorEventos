// Auto-teste do carga-soak.js e do lib-soak.js (issue #232) SEM k6: um loader do node troca k6, k6/http, k6/execution e k6/encoding por stubs (mesmo padrao do
// degraus/carga-degraus.selftest.mjs) e o script roda de verdade (options, setup e os 5 fluxos) contra um servidor falso. Cobre: Mailpit fora aborta o setup() sem nenhuma
// chamada ao BFF (0 request-verification), o fluxo do cadastro (codigo lido do Mailpit, credencial so no header), enderecos so @example.com, tags tipo/fase/min em toda chamada,
// destinos da reserva, 202 acompanhado e a escrita de catalogo. Uso: node soak/carga-soak.selftest.mjs   (node >= 22.7)
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { register } from 'node:module';
import { pathToFileURL } from 'node:url';

const STUBS = {
  'k6': `export function sleep(s) {}`,
  'k6/http': `const k = () => globalThis.__k6;
    export default { request: (m, u, b, p) => k().http(m, u, b, p), get: (u, p) => k().http('GET', u, null, p), post: (u, b, p) => k().http('POST', u, b, p),
      batch: (reqs) => reqs.map((r) => k().http(r[0], r[1], r[2], r[3])) };`,
  'k6/execution': `export default globalThis.__k6.exec;`,
  'k6/metrics': `export class Counter { constructor(n) { this.n = n; } add(v, t) { (globalThis.__k6.metricas = globalThis.__k6.metricas || []).push({ nome: this.n, v, tags: t }); } }`,
  'k6/encoding': `export default { b64encode: (s) => Buffer.from(String(s)).toString('base64') };`,
};
register('data:text/javascript,' + encodeURIComponent(`
  const S = ${JSON.stringify(STUBS)};
  export async function resolve(spec, ctx, next) { if (S[spec]) return { url: 'stub:' + spec, shortCircuit: true }; return next(spec, ctx); }
  export async function load(url, ctx, next) { if (url.startsWith('stub:')) return { format: 'module', source: S[url.slice(5)], shortCircuit: true }; return next(url, ctx); }
`));

let falhas = 0;
async function confere(nome, fn) {
  try { await fn(); process.stdout.write(`   [ok]    ${nome}\n`); }
  catch (e) { falhas++; process.stdout.write(`   [FALHA] ${nome}\n           ${String(e.message).split('\n').join('\n           ')}\n`); }
}

const SEGREDO = 'CANARIOsenha123456';
const CODIGO = '123456';
const TEXTO_DO_EMAIL = `Ola Soak, Codigo de Verificacao: ${CODIGO} ... corpo do e-mail que nunca pode ir para o console`;
const logs = [];
console.log = (...a) => logs.push(a.join(' '));
console.warn = (...a) => logs.push(a.join(' '));
const saida = (...a) => process.stdout.write(a.join(' ') + '\n');

// ---- servidor falso
const k6 = globalThis.__k6 = { exec: { scenario: { name: 'x', iterationInTest: 0, startTime: Date.now() - 5 * 60000 - 1000 }, test: { abort: (m) => { throw new Error('abort: ' + m); } } } };
const chamadas = [];
let mailpitNoAr = true;
let mailpitSemMensagem = false;
let postReserva = 201;
let corpoReserva = '{}';    // corpo do POST /bff/event-wallets quando nao e 202
let avancaMinuto = false;   // cada chamada ao k6 consome 1 minuto de relogio do cenario (fluxo longo que cruza a fronteira do minuto)
let estadosFila = ['pending'];
k6.http = (metodo, url, corpo, params) => {
  chamadas.push({ metodo, url, corpo, params: params || {} });
  if (avancaMinuto) k6.exec.scenario.startTime -= 60000;
  const resp = (status, body = '', headers = {}) => ({ status, body, headers, timings: { duration: 5 }, json: () => JSON.parse(body) });
  if (url.startsWith('http://mp:8025')) {
    if (!mailpitNoAr) return resp(0);
    if (url.includes('/api/v1/messages')) return resp(200, '{"total":0,"messages":[]}');
    if (url.includes('/api/v1/search')) return mailpitSemMensagem ? resp(200, '{"messages":[]}') : resp(200, JSON.stringify({ messages: [{ ID: 'm1', Snippet: 'sem codigo aqui' }] }));
    if (url.includes('/api/v1/message/')) return resp(200, JSON.stringify({ Text: TEXTO_DO_EMAIL }));
  }
  if (url.endsWith('/bff/users/login')) return resp(200, JSON.stringify({ user: { user_id: 7 }, token: 'tok' }));
  if (url.endsWith('/bff/users/request-verification')) return resp(201, '{}');
  if (url.endsWith('/bff/users/verify-email')) return resp(201, '{}');
  if (url.endsWith('/bff/event-wallets')) return metodo === 'DELETE' ? resp(200) : resp(postReserva, postReserva === 202 ? '{"status":"queued","idempotencyKey":"k-1"}' : corpoReserva, postReserva === 202 ? { 'Idempotency-Key': 'k-1' } : {});
  if (url.includes('/bff/event-wallets?userId=')) return resp(200);
  if (url.endsWith('/confirm')) return resp(200);
  if (url.includes('/bff/event-wallets/admission')) { const e = estadosFila.length > 1 ? estadosFila.shift() : estadosFila[0]; return resp(200, JSON.stringify({ status: e })); }
  if (url.endsWith('/bff/events') && metodo === 'POST') return resp(201, '{"event_id":77}');
  if (/\/bff\/events\/77$/.test(url) && metodo === 'PUT') return resp(200, '{}');
  return resp(200, '{}');
};

const perfil = (() => {
  const t = (rpm) => ({ teto_rpm: rpm, fases: [{ inicio_s: 0, fim_s: 300, rampa_s: 30, pico: true, rpm }, { inicio_s: 300, fim_s: 600, rampa_s: 30, pico: false, rpm: 1 }],
    stages: [{ target: rpm, duration: '30s' }, { target: rpm, duration: '270s' }, { target: 1, duration: '30s' }, { target: 1, duration: '270s' }] });
  return { id: 'T', tipos: { leitura: t(600), reserva: t(300), login: t(70), cadastro: t(20), escrita: t(10) } };
})();
globalThis.__ENV = { BASE_URL: 'http://gw:8081', SUFIXO: 'T1', USUARIOS: '40', EVENT_IDS: '10-49', PERFIL_FILE: 'perfil', MAILPIT_URL: 'http://mp:8025', MAILPIT_AUTH: `soak:${SEGREDO}`,
  MAX_VUS: '100', MAILPIT_TETO_S: '3', PCT_ACOMPANHA: '100', CONSULTA_MS: '50', TETO_CONSULTA_S: '1', PREFIXO: 'Soak-T1' };
globalThis.open = () => JSON.stringify(perfil);

const aqui = new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const script = await import(pathToFileURL(aqui + 'carga-soak.js').href);
const lib = await import(pathToFileURL(aqui + 'lib-soak.js').href);
const zera = () => { chamadas.length = 0; logs.length = 0; };
const bff = () => chamadas.filter((c) => c.url.startsWith('http://gw:8081'));
const mp = () => chamadas.filter((c) => c.url.startsWith('http://mp:8025'));

saida('==> options');
await confere('5 cenarios ramping-arrival-rate em paralelo, tag de cenario tipo, stages do perfil, timeUnit 1m', () => {
  const sc = script.options.scenarios;
  assert.deepEqual(Object.keys(sc).sort(), ['cadastro', 'escrita', 'leitura', 'login', 'reserva']);
  for (const [nome, s] of Object.entries(sc)) {
    assert.equal(s.executor, 'ramping-arrival-rate');
    assert.equal(s.timeUnit, '1m');
    assert.equal(s.exec, nome);
    assert.deepEqual(s.tags, { tipo: nome });
    assert.deepEqual(s.stages, perfil.tipos[nome].stages);
    assert.ok(s.maxVUs >= s.preAllocatedVUs);
  }
  assert.equal(Object.values(sc).reduce((a, s) => a + s.maxVUs, 0) >= 95, true);
});
await confere('url fora das systemTags (cardinalidade); nenhuma Trend em memoria', () => {
  assert.ok(!script.options.systemTags.includes('url'));
  assert.ok(script.options.systemTags.includes('name'));
  assert.ok(!/new Trend|new Rate/.test(readFileSync(aqui + 'carga-soak.js', 'utf8').replace(/\/\/.*$/gm, '')));
});

saida('==> setup');
await confere('Mailpit fora do ar: o setup() aborta e NENHUMA chamada ao BFF foi feita (0 request-verification)', () => {
  zera(); mailpitNoAr = false;
  assert.throws(() => script.setup(), /Mailpit nao respondeu/);
  assert.equal(bff().length, 0);
  assert.equal(chamadas.filter((c) => c.url.includes('request-verification')).length, 0);
  mailpitNoAr = true;
});
let d;
await confere('Mailpit no ar: logins do seed e do host; a verificacao do Mailpit vem ANTES dos logins', () => {
  zera();
  d = script.setup();
  assert.equal(d.sessoes.length, 40);
  assert.ok(d.host && d.host.token);
  assert.equal(chamadas[0].url.startsWith('http://mp:8025/api/v1/messages'), true);
  assert.ok(chamadas.some((c) => c.corpo && String(c.corpo).includes('carga-host-T1@example.com')));
});
await confere('MAILPIT_AUTH so no header Authorization (Basic), nunca na URL nem no console', () => {
  const m = mp();
  assert.ok(m.length >= 1);
  for (const c of m) {
    assert.equal(c.params.headers.Authorization, `Basic ${Buffer.from(`soak:${SEGREDO}`).toString('base64')}`);
    assert.ok(!c.url.includes(SEGREDO) && !c.url.includes('soak:'));
  }
  assert.ok(!logs.join('\n').includes(SEGREDO));
});

saida('==> cadastro');
await confere('request-verification -> busca no Mailpit -> codigo da mensagem -> verify-email; enderecos so @example.com', () => {
  zera(); k6.exec.scenario.name = 'cadastro'; k6.exec.scenario.iterationInTest = 42;
  script.cadastro();
  const seq = chamadas.map((c) => `${c.metodo} ${c.url.replace('http://gw:8081', '').replace('http://mp:8025', 'MP').split('?')[0]}`);
  assert.deepEqual(seq, ['POST /bff/users/request-verification', 'GET MP/api/v1/search', 'GET MP/api/v1/message/m1', 'POST /bff/users/verify-email']);
  const rv = JSON.parse(chamadas[0].corpo);
  assert.equal(rv.email, 'soak-T1-42@example.com');
  assert.equal(rv.fone, '120000010000042'); // SUFIXO T1 -> digitos "1"
  const ve = JSON.parse(chamadas[3].corpo);
  assert.deepEqual(ve, { email: 'soak-T1-42@example.com', verificationCode: CODIGO });
  assert.ok(decodeURIComponent(chamadas[1].url).includes('to:soak-T1-42@example.com'));
  for (const e of chamadas.map((c) => String(c.corpo || '') + decodeURIComponent(c.url)).join(' ').match(/[A-Za-z0-9._+-]+@[A-Za-z0-9.-]+/g) || []) assert.ok(e.endsWith('@example.com'), e);
});
await confere('toda chamada leva tags tipo, fase, min e name; as do Mailpit levam tipo=mailpit com a fase/min do cadastro', () => {
  for (const c of chamadas) {
    const t = c.params.tags;
    assert.ok(t && t.name && t.tipo && t.fase && t.min !== undefined, JSON.stringify(t));
    assert.equal(t.tipo, c.url.startsWith('http://mp:8025') ? 'mailpit' : 'cadastro');
    assert.ok(['pico', 'vale'].includes(t.fase));
  }
  assert.equal(chamadas[0].params.tags.min, '5');      // 5 min e 1 s de cenario
  assert.equal(chamadas[0].params.tags.fase, 'vale');  // 301 s de cenario: ja na 2a fase do perfil (300 a 600 s e vale)
});
await confere('o corpo do e-mail nunca vai para o console', () => {
  assert.ok(!logs.join('\n').includes('corpo do e-mail'));
  assert.ok(!logs.join('\n').includes(CODIGO));
});
await confere('sem mensagem no Mailpit no prazo: nao chama verify-email (e nao imprime nada)', () => {
  zera(); mailpitSemMensagem = true; k6.exec.scenario.iterationInTest = 43;
  script.cadastro();
  mailpitSemMensagem = false;
  assert.equal(chamadas.filter((c) => c.url.endsWith('/verify-email')).length, 0);
  assert.equal(chamadas.filter((c) => c.url.includes('/api/v1/search')).length, 3);   // MAILPIT_TETO_S=3
});

await confere('min e fase sao calculados POR REQUISICAO: um fluxo que cruza a fronteira do minuto carimba o minuto de cada chamada, nao o do comeco', () => {
  zera(); const t0 = k6.exec.scenario.startTime; k6.exec.scenario.startTime = Date.now() - 290000; k6.exec.scenario.iterationInTest = 50; mailpitSemMensagem = true; avancaMinuto = true;
  script.cadastro();
  avancaMinuto = false; mailpitSemMensagem = false; k6.exec.scenario.startTime = t0;
  const mins = chamadas.map((c) => Number(c.params.tags.min));
  assert.ok(mins.length >= 4, `${mins.length} chamadas`);
  for (let j = 1; j < mins.length; j++) assert.equal(mins[j], mins[j - 1] + 1, `min por chamada: ${mins}`);
  assert.equal(new Set(chamadas.map((c) => c.params.tags.fase)).size, 2, 'a fase tambem muda ao cruzar a fronteira 300 s (pico -> vale)');
});
await confere('a sonda do Mailpit no setup() leva tipo=mailpit', () => {
  zera(); script.setup();
  const sonda = chamadas.find((c) => c.url.includes('/api/v1/messages?limit=1'));
  assert.equal(sonda.params.tags.tipo, 'mailpit');
});

saida('==> reserva, login, leitura, escrita');
await confere('reserva 201: confirma ~60%, cancela ~10%, o resto expira (200 iteracoes)', () => {
  zera(); k6.exec.scenario.name = 'reserva'; postReserva = 201;
  for (let i = 0; i < 200; i++) { k6.exec.scenario.iterationInTest = i; script.reserva(d); }
  const conf = bff().filter((c) => c.url.endsWith('/confirm')).length;
  const canc = bff().filter((c) => c.metodo === 'DELETE').length;
  assert.ok(conf > 100 && conf < 140, `confirma ${conf}`);
  assert.ok(canc > 8 && canc < 35, `cancela ${canc}`);
  assert.equal(bff().filter((c) => c.metodo === 'POST' && c.url.endsWith('/bff/event-wallets')).length, 200);
});
await confere('S1 (sem QUENTES_IDS): a reserva fica ESPALHADA pelos eventos do seed (mapeamento do kit) e confirma de verdade (~60%)', () => {
  assert.equal(globalThis.__ENV.QUENTES_IDS, undefined);
  zera(); k6.exec.scenario.name = 'reserva'; postReserva = 201;
  for (let i = 0; i < 400; i++) { k6.exec.scenario.iterationInTest = i; script.reserva(d); }
  const posts = bff().filter((c) => c.metodo === 'POST' && c.url.endsWith('/bff/event-wallets')).map((c) => JSON.parse(c.corpo));
  assert.equal(posts.length, 400);
  assert.equal(new Set(posts.map((p) => p.eventId)).size, 40, 'todos os 40 eventos do seed recebem reservas (intercalado)');
  assert.ok(Math.max(...Object.values(posts.reduce((m, p) => ({ ...m, [p.eventId]: (m[p.eventId] || 0) + 1 }), {}))) <= 10, 'nenhum evento concentra a carga (400 reservas / 40 eventos = 10)');
  const conf = bff().filter((c) => c.url.endsWith('/confirm')).length;
  assert.ok(conf > 200 && conf < 280, `confirma ${conf} (~60% de 400)`);
});
await confere('reserva 202: acompanha o estado ate pending (pelo header Idempotency-Key) e so entao decide o destino', () => {
  zera(); postReserva = 202; estadosFila = ['queued', 'pending']; k6.exec.scenario.iterationInTest = 3;
  script.reserva(d);
  const adm = bff().filter((c) => c.url.includes('/admission?idempotencyKey=k-1'));
  assert.equal(adm.length, 2);
  postReserva = 201;
});
await confere('reserva 429/400/5xx nao confirma nem cancela', () => {
  zera();
  for (const st of [429, 400, 503]) { postReserva = st; k6.exec.scenario.iterationInTest = 5; script.reserva(d); }
  assert.equal(bff().filter((c) => c.url.endsWith('/confirm') || c.metodo === 'DELETE').length, 0);
  postReserva = 201;
});
await confere('motivoDo400: lotado | vinculo | outro (tolera acento, caixa, nulo e JSON)', () => {
  const m = lib.motivoDo400;
  assert.equal(m('Evento lotado'), 'lotado');
  assert.equal(m('EVENTO LOTADO'), 'lotado');
  assert.equal(m('{"error":"Evento lotado"}'), 'lotado');
  assert.equal(m('Vínculo já existente'), 'vinculo');
  assert.equal(m('Vinculo ja existente'), 'vinculo');
  assert.equal(m('Vínculo já existente'.normalize('NFD')), 'vinculo');
  assert.equal(m(null), 'outro');
  assert.equal(m(undefined), 'outro');
  assert.equal(m(''), 'outro');
  assert.equal(m('userId inválido'), 'outro');
  assert.equal(m('{"a":1}'), 'outro');
});
await confere('reserva 400: conta reserva_400 com a tag motivo (e as tags tipo/fase/min); 201 e 429 nao contam', () => {
  zera(); k6.metricas = [];
  const casos = [['Evento lotado', 'lotado'], ['Vínculo já existente', 'vinculo'], ['userId inválido', 'outro']];
  for (const [corpo, motivo] of casos) {
    corpoReserva = corpo; postReserva = 400; k6.exec.scenario.iterationInTest = 5; script.reserva(d);
    const ult = k6.metricas[k6.metricas.length - 1];
    assert.equal(ult.nome, 'reserva_400');
    assert.equal(ult.v, 1);
    assert.equal(ult.tags.motivo, motivo);
    assert.equal(ult.tags.tipo, 'reserva');
    assert.ok(ult.tags.fase && ult.tags.min !== undefined, 'mantem fase e min');
  }
  assert.equal(k6.metricas.length, 3);
  for (const st of [201, 429]) { postReserva = st; script.reserva(d); }
  assert.equal(k6.metricas.length, 3, 'so o 400 conta');
  postReserva = 201; corpoReserva = '{}';
});
await confere('login usa usuario do seed; leitura cobre evento, carteira e busca', () => {
  zera(); k6.exec.scenario.name = 'login'; k6.exec.scenario.iterationInTest = 2; script.login();
  assert.equal(chamadas.length, 1);
  assert.match(JSON.parse(chamadas[0].corpo).email, /^carga-att\d+-T1@example\.com$/);
  zera(); k6.exec.scenario.name = 'leitura';
  for (let i = 0; i < 100; i++) script.leitura(d);
  const nomes = new Set(chamadas.map((c) => c.params.tags.name));
  assert.deepEqual([...nomes].sort(), ['/bff/event-wallets/user/{id}', '/bff/events/search', '/bff/events/{id}']);
});
await confere('escrita: o host cria <PREFIXO>-W<n> e edita com apresentador (PUT no id devolvido)', () => {
  zera(); k6.exec.scenario.name = 'escrita'; k6.exec.scenario.iterationInTest = 9; script.escrita(d);
  assert.equal(chamadas.length, 2);
  assert.equal(JSON.parse(chamadas[0].corpo).event_name, 'Soak-T1-W9');
  assert.equal(chamadas[1].metodo, 'PUT');
  assert.ok(chamadas[1].url.endsWith('/bff/events/77'));
  assert.deepEqual(JSON.parse(chamadas[1].corpo).presenters, ['Apresentador 9']);
  assert.equal(chamadas[0].params.headers.Authorization, 'Bearer tok');
});

saida('==> reserva em evento quente (decisao C)');
await confere('alvoDaReserva: sem quentes nunca e quente; com quentes ~PCT_QUENTE %; insistentes recebem ~PCT_INSISTENTE % das quentes; usuarios dentro de [0, U)', () => {
  const sem = { quentes: [], pctQuente: 90, insistentes: 1, pctInsistente: 25, usuarios: 40 };
  for (let i = 0; i < 200; i++) assert.equal(lib.alvoDaReserva(i, sem).quente, false);
  const cfg = { ...sem, quentes: [900] };
  let q = 0, ins = 0;
  for (let i = 0; i < 4000; i++) {
    const a = lib.alvoDaReserva(i, cfg);
    if (!a.quente) continue;
    q++; if (a.u === 0) ins++;
    assert.ok(a.u >= 0 && a.u < 40 && a.e === 0);
  }
  assert.ok(q > 3400 && q < 3800, `quentes ${q}`);
  assert.ok(ins / q > 0.2 && ins / q < 0.3, `insistentes ${ins / q}`);
  const dois = lib.alvoDaReserva(5, { ...cfg, quentes: [900, 901], pctQuente: 100 });
  assert.equal(dois.e, 1);
  assert.deepEqual(lib.alvoDaReserva(7, cfg), lib.alvoDaReserva(7, cfg));   // deterministico
  assert.equal(lib.alvoDaReserva(3, { ...cfg, insistentes: 0 }).u < 40, true);
});
await confere('script com QUENTES_IDS: ~95% das reservas vao ao evento quente e sao canceladas; as frias seguem o mapeamento de sempre', async () => {
  globalThis.__ENV = { ...globalThis.__ENV, QUENTES_IDS: '900', PCT_QUENTE: '95', PCT_ACOMPANHA: '0' };   // PCT_ACOMPANHA=0: so as quentes acompanham a 202
  const q = await import(pathToFileURL(aqui + 'carga-soak.js').href + '?quente');
  zera(); k6.exec.scenario.name = 'reserva'; postReserva = 201;
  for (let i = 0; i < 300; i++) { k6.exec.scenario.iterationInTest = i; q.reserva(d); }
  const posts = bff().filter((c) => c.metodo === 'POST' && c.url.endsWith('/bff/event-wallets')).map((c) => JSON.parse(c.corpo));
  const quentes = posts.filter((p) => p.eventId === 900).length;
  assert.equal(posts.length, 300);
  assert.ok(quentes > 270 && quentes < 298, `quentes ${quentes}`);
  const dels = bff().filter((c) => c.metodo === 'DELETE');
  assert.ok(dels.some((c) => !/eventId=900/.test(c.url)));   // as frias ainda cancelam ~10%
  assert.ok(dels.filter((c) => /eventId=900/.test(c.url)).length === quentes, 'toda reserva quente aceita e cancelada');
  assert.ok(bff().filter((c) => c.url.endsWith('/confirm')).every((c) => JSON.parse(c.corpo).eventId !== 900), 'a quente nunca confirma');
  zera(); postReserva = 202; estadosFila = ['pending'];
  for (let i = 0; i < 40; i++) { k6.exec.scenario.iterationInTest = i; q.reserva(d); }
  const acompanhadas = bff().filter((c) => c.url.includes('/admission?idempotencyKey=')).length;
  assert.ok(acompanhadas > 32 && acompanhadas < 40, `202 acompanhadas ${acompanhadas} (so as ~38 quentes; as frias nao, PCT_ACOMPANHA=0)`);
  postReserva = 201; globalThis.__ENV.PCT_ACOMPANHA = '100';
});

saida('==> codigo-fonte');
await confere('grep: nenhum endereco de e-mail fora de @example.com no carga-soak.js e no lib-soak.js', () => {
  for (const f of ['carga-soak.js', 'lib-soak.js']) {
    const txt = readFileSync(aqui + f, 'utf8');
    for (const e of txt.match(/[A-Za-z0-9._+${}-]+@[A-Za-z0-9.${}-]+/g) || []) assert.ok(e.endsWith('@example.com'), `${f}: ${e}`);
  }
});
await confere('nenhum console.* no carga-soak.js imprime o corpo do Mailpit', () => {
  const txt = readFileSync(aqui + 'carga-soak.js', 'utf8').replace(/\/\/.*$/gm, '');
  const consoles = txt.match(/console\.\w+\([^;]*;/g) || [];
  for (const c of consoles) assert.ok(!/busca|msg|mailpit\(|Text|Snippet/.test(c), c);
});

saida('==> lib-soak');
await confere('foneDoCadastro: depende do sufixo, cabe em varchar(20) ate n=999999 e nunca colide com os telefones do seed (11...)', () => {
  const f = lib.foneDoCadastro;
  for (const n of [1, 999999]) {
    assert.match(f('364888', n), /^\d{15}$/);
    assert.notEqual(f('364888', n), f('367775', n));
  }
  assert.notEqual(f('364888', 1), f('364888', 2));
  // seed (setup.sh): host = "119" + 8 digitos; attendees = "11" + indice de 3 digitos + 6 do sufixo; os quentes usam "q0"+5 digitos
  const doSeed = new Set();
  for (const s of ['364888', '367775', 'q012345']) {
    const d = s.replace(/\D/g, '');
    doSeed.add(`119${d.slice(-8).padStart(8, '0')}`);
    for (let i = 0; i < 1000; i++) doSeed.add(`11${String(i).padStart(3, '0')}${d.slice(-6).padStart(6, '0')}`);
  }
  for (const s of ['364888', '367775']) for (const n of [1, 42, 100000, 999999]) assert.ok(!doSeed.has(f(s, n)) && !f(s, n).startsWith('11'));
  assert.match(f('T1', 1), /^\d{15}$/); // sufixo sem digitos suficientes ou com letras (selftest)
});
await confere('faseEm, minutoEm, extrairCodigo, destinoDaReserva, erroParametros', () => {
  const f = perfil.tipos.leitura.fases;
  assert.equal(lib.faseEm(f, 0), 'pico'); assert.equal(lib.faseEm(f, 299.9), 'pico'); assert.equal(lib.faseEm(f, 300), 'vale'); assert.equal(lib.faseEm(f, 9999), 'vale');
  assert.equal(lib.minutoEm(59999), 0); assert.equal(lib.minutoEm(60000), 1); assert.equal(lib.minutoEm(-5), 0);
  assert.equal(lib.extrairCodigo('Codigo de Verificacao: 654321'), '654321');
  assert.equal(lib.extrairCodigo('📝 Código de Verificação: 000123\n'), '000123');
  assert.equal(lib.extrairCodigo('Código MFA: 123456'), null);
  assert.equal(lib.extrairCodigo('Verificação: 12345'), null);
  assert.equal(lib.destinoDaReserva(0), 'confirma'); assert.equal(lib.destinoDaReserva(59), 'confirma'); assert.equal(lib.destinoDaReserva(60), 'cancela');
  assert.equal(lib.destinoDaReserva(69), 'cancela'); assert.equal(lib.destinoDaReserva(70), 'expira'); assert.equal(lib.destinoDaReserva(99), 'expira');
  const ok = { perfil, maxVus: 100, mailpitUrl: 'http://mp:8025', mailpitAuth: 'soak:abcdefgh12345678', sufixo: 'T1', prefixo: 'Soak-T1' };
  assert.equal(lib.erroParametros(ok), null);
  assert.match(lib.erroParametros({ ...ok, mailpitAuth: '' }), /MAILPIT_AUTH/);
  assert.match(lib.erroParametros({ ...ok, mailpitAuth: 'soak:curta12345' }), /MAILPIT_AUTH/);   // senha < 16: a mesma regra do cluster.sh e do verificar-soak.sh
  assert.match(lib.erroParametros({ ...ok, mailpitUrl: '' }), /MAILPIT_URL/);
  assert.match(lib.erroParametros({ ...ok, maxVus: 5 }), /MAX_VUS/);
  assert.match(lib.erroParametros({ ...ok, perfil: { tipos: {} } }), /leitura/);
});

await confere('busca de leitura: o termo acerta exatamente 1 evento do seed (LIKE %termo%, N = 2000 e N = 37)', () => {
  for (const n of [2000, 37, 10, 1000]) {
    const nomes = Array.from({ length: n }, (_, i) => `Soak-9-E${i + 1}`);
    for (let r = 0; r < 200; r++) {
      const t = lib.termoDeBusca('Soak-9', n, r / 200);
      const achados = nomes.filter((x) => x.toLowerCase().includes(t.toLowerCase())).length;
      assert.equal(achados, 1, `${t} (N=${n}) acertou ${achados}`);
    }
  }
  zera(); k6.exec.scenario.name = 'leitura';
  for (let i = 0; i < 100; i++) script.leitura(d);
  const busca = chamadas.filter((c) => c.params.tags.name === '/bff/events/search');
  assert.ok(busca.length > 5);
  for (const c of busca) assert.match(decodeURIComponent(c.url), /term=Soak-T1-E\d+$/);
});
await confere('chamadas que so olham o status nao guardam o corpo (responseType none): leitura, confirma, cancela, login, request-verification, verify-email, PUT', () => {
  zera(); k6.exec.scenario.name = 'leitura';
  for (let i = 0; i < 30; i++) script.leitura(d);
  assert.ok(chamadas.every((c) => c.params.responseType === 'none'));
  zera(); postReserva = 201; k6.exec.scenario.name = 'reserva';
  for (let i = 0; i < 60; i++) { k6.exec.scenario.iterationInTest = i; script.reserva(d); }
  const so = bff().filter((c) => c.url.endsWith('/confirm') || c.metodo === 'DELETE');
  assert.ok(so.length > 10 && so.every((c) => c.params.responseType === 'none'));
  assert.ok(bff().filter((c) => c.metodo === 'POST' && c.url.endsWith('/bff/event-wallets')).every((c) => c.params.responseType === undefined));   // a reserva precisa do corpo (202: chave)
  zera(); k6.exec.scenario.name = 'cadastro'; k6.exec.scenario.iterationInTest = 5; script.cadastro();
  assert.equal(chamadas.filter((c) => c.url.endsWith('/request-verification') || c.url.endsWith('/verify-email')).every((c) => c.params.responseType === 'none'), true);
  assert.ok(mp().every((c) => c.params.responseType === undefined));   // o Mailpit: a busca e a mensagem PRECISAM do corpo
  zera(); k6.exec.scenario.name = 'escrita'; k6.exec.scenario.iterationInTest = 9; script.escrita(d);
  assert.equal(chamadas[1].params.responseType, 'none');
});

process.stdout.write(falhas ? `\nselftest carga-soak: ${falhas} falha(s)\n` : '\nselftest carga-soak: OK\n');
process.exit(falhas ? 1 : 0);
