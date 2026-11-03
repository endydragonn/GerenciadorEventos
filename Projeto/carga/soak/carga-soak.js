// Cenario k6 do TESTE LONGO (soak, issue #232): 5 cenarios ramping-arrival-rate EM PARALELO, um por tipo de carga, cada um com o perfil proprio de picos e vales
// sorteado por soak/perfil.py (perfil-<id>.json, lido de PERFIL_FILE; um perfil com `so` (perfil.py --so cadastro, o dry do Mailpit) roda SO esse tipo). Corretude + autoescala + troca de replica sob carga de horas, no cluster Kubernetes (overlay soak).
//   leitura   60%  GET /bff/events/{id} | /bff/events/search?term=<nome exato de 1 evento> | /bff/event-wallets/user/{id}   (sem guardar o corpo: responseType none)
//   reserva   30%  POST /bff/event-wallets (braco cas; 202 acompanhado em PCT_ACOMPANHA %), depois 60% confirma, 10% cancela, 30% expira sozinha.
//                  Sem QUENTES_IDS (S1): a reserva fica ESPALHADA pelos eventos do seed (mapeamento do kit) com o 60/10/30 acima. Com QUENTES_IDS (S2 apenas, decisao 2 da spec): PCT_QUENTE % (95) das reservas miram o(s) evento(s) quente(s) (o RPM do evento mais quente liga a fila e o KEDA do reservation); nelas o par
//                  (usuario, evento) se repete, entao TODA 202 e acompanhada e a reserva aceita e cancelada logo (o par volta a ficar livre). INSISTENTES usuarios recebem PCT_INSISTENTE % das
//                  quentes (provocar o 429 do limite por usuario do admission, so por carga)
//   login      7%  POST /bff/users/login (usuario do seed, bcrypt)
//   cadastro   2%  request-verification -> codigo lido no MAILPIT (dentro do cluster) -> verify-email. Endereco SEMPRE soak-<sufixo>-<n>@example.com: nenhum e-mail real.
//                  O codigo sai das mensagens MAIS NOVAS (GET /api/v1/messages?start=&limit=100, ~3 ms constantes), NUNCA de uma busca: a /api/v1/search?query=to: varre a caixa inteira (O(n), 80 ms
//                  com 26 mil mensagens) e saturou o Mailpit no S2. Consultas em 2, 2, 4, 4... s dentro de MAILPIT_TETO_S (30)
//   escrita    1%  o host cria evento (POST /bff/events) e edita (PUT, com apresentador). O host do seed e promovido por SQL no preparar-soak.ps1
//
// COMO RODAR (flags): k6 run --no-thresholds --summary-mode=disabled --out csv=<arquivo>.csv.gz carga-soak.js   (k6 >= 1.3; o gerador tem a v2.2.0. `--no-summary` e K6_NO_SUMMARY foram
// REMOVIDOS na v2.0.0). Sem thresholds nem resumo o k6 nao acumula metricas em memoria; esta script NAO cria nenhuma Trend (so um Counter `reserva_400` com 3 valores de tag `motivo`, que nao pesa; e o CSV bruto, que o soak/agregar.py resume por streaming,
// lendo o .gz). O preparar-soak.ps1 (host) semeia e imprime a linha exata do gerador.
//
// Variaveis (ambiente do processo ou -e; MAILPIT_AUTH SO pelo ambiente, nunca por -e/argumento):
//   BASE_URL, SUFIXO + USUARIOS + EVENT_IDS (o seed, como no carga-degraus.js), PREFIXO (nome dos eventos: <PREFIXO>-E<k>; os criados aqui: <PREFIXO>-W<n>), PERFIL_FILE (absoluto),
//   QUENTES_IDS (ids dos eventos quentes, formato do EVENT_IDS; vazio = sem quentes), PCT_QUENTE (95), INSISTENTES (1), PCT_INSISTENTE (25),
//   MAILPIT_URL (http://<host>:8025), MAILPIT_AUTH (<usuario>:<senha>), MAX_VUS (2000; ~1-2 MB cada, dimensione pela RAM do gerador), PCT_ACOMPANHA (10), CONSULTA_MS (1000),
//   TETO_CONSULTA_S (35), MAPEAMENTO (intercalado), SEMENTE (1), PCT_CONFIRMA (60), PCT_CANCELA (10), MAILPIT_TETO_S (30).
// Tags em TODA amostra: tipo (cenario; `mailpit` nas chamadas ao captador), fase (pico | vale, do perfil), min (minuto do bloco), name (o caminho com {id}, cardinalidade fixa).
// `url` fica FORA das systemTags (uma URL por reserva/consulta criaria series sem fim). Nenhum console.* imprime corpo de resposta do Mailpit nem a credencial dele.
import http from 'k6/http';
import { sleep } from 'k6';
import encoding from 'k6/encoding';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { acompanhar, classificarReserva, gracefulS, idsDeEventos, usuariosDoSufixo } from '../degraus/lib-admissao.js';
import { erroMapeamento, parDe } from '../degraus/lib-mapeamento.js';
import { sorteio100 } from '../lib-sorteio.js';
import { acharNaPagina, alvoDaReserva, cenarios, destinoDaReserva, enderecoDoCadastro, erroParametros, esperasDaBusca, extrairCodigo, faseEm, foneDoCadastro, hostDoSufixo, minutoEm, motivoDo400, termoDeBusca } from './lib-soak.js';

const BASE = __ENV.BASE_URL;
const SUFIXO = __ENV.SUFIXO || '';
const PREFIXO = __ENV.PREFIXO || `Soak-${SUFIXO}`;
const MAILPIT_URL = (__ENV.MAILPIT_URL || '').replace(/\/+$/, '');
const MAILPIT_AUTH = __ENV.MAILPIT_AUTH || '';
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2000', 10);
const PCT_ACOMPANHA = parseFloat(__ENV.PCT_ACOMPANHA || '10');
const CONSULTA_MS = parseInt(__ENV.CONSULTA_MS || '1000', 10);
const TETO_CONSULTA_S = parseInt(__ENV.TETO_CONSULTA_S || '35', 10);
const MAPEAMENTO = (__ENV.MAPEAMENTO || 'intercalado').toLowerCase();
const SEMENTE = parseInt(__ENV.SEMENTE || '1', 10);
const PCT_CONFIRMA = parseInt(__ENV.PCT_CONFIRMA || '60', 10);
const PCT_CANCELA = parseInt(__ENV.PCT_CANCELA || '10', 10);
const MAILPIT_TETO_S = parseInt(__ENV.MAILPIT_TETO_S || '30', 10);
const QUENTES = __ENV.QUENTES_IDS ? idsDeEventos(__ENV.QUENTES_IDS) : [];
const PCT_QUENTE = parseInt(__ENV.PCT_QUENTE || '95', 10);
const INSISTENTES = parseInt(__ENV.INSISTENTES || '1', 10);
const PCT_INSISTENTE = parseInt(__ENV.PCT_INSISTENTE || '25', 10);
// PowerShell 5.1 grava UTF8 com BOM; JSON.parse nao aceita BOM.
const PERFIL = JSON.parse(open(__ENV.PERFIL_FILE).replace(/^﻿/, ''));
const USERS = usuariosDoSufixo(parseInt(__ENV.USUARIOS || '2000', 10), SUFIXO || 'x');
const EVENTS = idsDeEventos(__ENV.EVENT_IDS || '1');
const ERRO_PARAMS = erroParametros({ perfil: PERFIL, maxVus: MAX_VUS, mailpitUrl: MAILPIT_URL, mailpitAuth: MAILPIT_AUTH, sufixo: SUFIXO, prefixo: PREFIXO })
  || erroMapeamento(MAPEAMENTO);
const BASIC = `Basic ${encoding.b64encode(MAILPIT_AUTH)}`; // so no header; a URL nao leva usuario:senha (iria para o CSV)

export const options = {
  scenarios: cenarios(PERFIL, { maxVus: MAX_VUS, gracefulStopS: gracefulS(PCT_ACOMPANHA, TETO_CONSULTA_S) + 30 }),
  systemTags: ['status', 'method', 'name', 'scenario', 'error', 'error_code'],
  setupTimeout: '1800s', // logins de milhares de usuarios (bcrypt no identity)
};

// 400 da reserva por motivo (tirado do corpo): o k6 nao grava o corpo no CSV. O agregar.py soma por `motivo` (lotado | vinculo | outro).
const reserva400 = new Counter('reserva_400');

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// ---- tags POR REQUISICAO: tipo, fase (do perfil) e minuto do bloco, pelo relogio do CENARIO (nao inclui o setup). Calculadas a cada chamada, nao uma vez por iteracao: um fluxo longo
// (cadastro esperando o e-mail, 202 acompanhado por ate 35 s) cruzaria a fronteira do minuto e carimbaria o minuto do COMECO nas requisicoes do fim (o agregar.py ja teria fechado
// aquele minuto e descartaria a amostra, 5xx inclusive).
function ctx(tipo) { return { tipo }; }
function tg(c, name, tipo) {
  const decMs = Date.now() - exec.scenario.startTime;
  return { name, tipo: tipo || c.tipo, fase: faseEm(PERFIL.tipos[c.tipo].fases, decMs / 1000), min: String(minutoEm(decMs)) };
}

// semCorpo: a iteracao so olha o status (o k6 nem guarda o corpo: memoria estavel em horas)
function bff(c, metodo, caminho, corpo, s, name, semCorpo) {
  const headers = s ? { 'Content-Type': 'application/json', Authorization: `Bearer ${s.token}` } : JSON_HEADERS;
  const params = { headers, tags: tg(c, name) };
  if (semCorpo) params.responseType = 'none';
  return http.request(metodo, `${BASE}${caminho}`, corpo === null ? null : JSON.stringify(corpo), params);
}

// chamadas ao Mailpit: tipo=mailpit, header Authorization (a credencial nunca vai na URL nem no log)
function mailpit(c, caminho, name) {
  return http.get(`${MAILPIT_URL}${caminho}`, { headers: { Authorization: BASIC }, tags: tg(c, name, 'mailpit') });
}

let avisos = 0; // ate 3 avisos por VU, sem corpo de resposta do Mailpit (so das chamadas ao BFF)
function avisar(nome, res) {
  if (avisos >= 3) return;
  avisos++;
  console.warn(`${nome}: HTTP ${res.status}${res.body ? ` ${String(res.body).slice(0, 160)}` : ''}`);
}

// ---- setup: o Mailpit PRIMEIRO (sem ele nenhum cadastro roda: aborta antes de qualquer chamada ao BFF), depois os logins do seed e do host
export function setup() {
  if (ERRO_PARAMS) exec.test.abort(ERRO_PARAMS);
  const r = http.get(`${MAILPIT_URL}/api/v1/messages?limit=1`, { headers: { Authorization: BASIC }, tags: { name: '/api/v1/messages', tipo: 'mailpit' }, timeout: '10s' });
  if (r.status !== 200) exec.test.abort(`Mailpit nao respondeu em ${MAILPIT_URL} (HTTP ${r.status}): o soak nao roda sem o captador de e-mail.`);
  const t0 = Date.now();
  const sessoes = [];
  for (let i = 0; i < USERS.length; i += 25) {
    const reqs = USERS.slice(i, i + 25).map((u) => ['POST', `${BASE}/bff/users/login`, JSON.stringify({ email: u.email, password: u.password }), { headers: JSON_HEADERS, tags: { name: '/bff/users/login' } }]);
    http.batch(reqs).forEach((x) => {
      if (x.status === 200) { const b = x.json(); sessoes.push({ id: b.user.user_id, token: b.token }); } else sessoes.push(null);
    });
  }
  const ok = sessoes.filter((s) => s).length;
  if (ok === 0) exec.test.abort('Nenhum login funcionou: confira o seed (SUFIXO/USUARIOS) e BASE_URL.');
  const h = hostDoSufixo(SUFIXO);
  const lh = http.post(`${BASE}/bff/users/login`, JSON.stringify(h), { headers: JSON_HEADERS, tags: { name: '/bff/users/login' } });
  const host = lh.status === 200 ? { id: lh.json().user.user_id, token: lh.json().token } : null;
  console.log(`setup: Mailpit ok; ${ok}/${USERS.length} logins em ${Math.round((Date.now() - t0) / 1000)} s; host ${host ? 'logado' : 'SEM login (escrita de catalogo desligada)'}; ${EVENTS.length} eventos; mapeamento ${MAPEAMENTO}; maxVUs ${MAX_VUS}`);
  return { sessoes, host };
}

// ---- leitura (60%)
export function leitura(d) {
  const c = ctx('leitura');
  const x = Math.random();
  let res;
  if (x < 0.5) {
    res = http.get(`${BASE}/bff/events/${EVENTS[Math.floor(Math.random() * EVENTS.length)]}`, { tags: tg(c, '/bff/events/{id}'), responseType: 'none' });
  } else if (x < 0.8) {
    const s = d.sessoes[Math.floor(Math.random() * d.sessoes.length)];
    if (!s) return;
    res = http.get(`${BASE}/bff/event-wallets/user/${s.id}`, { headers: { Authorization: `Bearer ${s.token}` }, tags: tg(c, '/bff/event-wallets/user/{id}'), responseType: 'none' });
  } else {
    res = http.get(`${BASE}/bff/events/search?term=${encodeURIComponent(termoDeBusca(PREFIXO, EVENTS.length, Math.random()))}`, { tags: tg(c, '/bff/events/search'), responseType: 'none' });
  }
  if (res.status >= 500) avisar('leitura 5xx', res);
}

// ---- reserva (30%): braco cas. 201 direto ou 202 (fila) acompanhado em PCT_ACOMPANHA %, depois confirma / cancela / expira. Na reserva QUENTE: cancela sempre (o par se repete)
function finalizar(c, s, eventId, i, quente) {
  const destino = quente ? 'cancela' : destinoDaReserva(sorteio100(i, 7), PCT_CONFIRMA, PCT_CANCELA);
  if (destino === 'confirma') {
    const r = bff(c, 'POST', '/bff/event-wallets/confirm', { userId: s.id, eventId }, s, '/bff/event-wallets/confirm', true);
    if (r.status >= 500) avisar('confirma 5xx', r);
  } else if (destino === 'cancela') {
    const r = bff(c, 'DELETE', `/bff/event-wallets?userId=${s.id}&eventId=${eventId}`, null, s, '/bff/event-wallets', true);
    if (r.status >= 500) avisar('cancela 5xx', r);
  } // expira: a vaga volta sozinha na expiracao
}

function chaveDo202(res) {
  const h = res.headers || {};
  const doHeader = h['Idempotency-Key'] || h['idempotency-key'];
  if (doHeader) return doHeader;
  try { return JSON.parse(String(res.body)).idempotencyKey || ''; } catch (e) { return ''; }
}

export function reserva(d) {
  const c = ctx('reserva');
  const i = exec.scenario.iterationInTest;
  const alvo = alvoDaReserva(i, { quentes: QUENTES, pctQuente: PCT_QUENTE, insistentes: INSISTENTES, pctInsistente: PCT_INSISTENTE, usuarios: USERS.length });
  let u, eventId;
  if (alvo.quente) {
    u = alvo.u; eventId = QUENTES[alvo.e];
  } else {
    if (i >= USERS.length * EVENTS.length) return; // pares unicos esgotados (o seed de 2000 x 2000 cobre horas): nao repete par
    const par = parDe(i, USERS.length, EVENTS.length, MAPEAMENTO);
    u = par.u; eventId = EVENTS[par.e];
  }
  const s = d.sessoes[u];
  if (!s) return;
  const res = bff(c, 'POST', '/bff/event-wallets', { userId: s.id, eventId }, s, '/bff/event-wallets');
  switch (classificarReserva(res.status, res.body)) {
    case 'aceita':
      finalizar(c, s, eventId, i, alvo.quente);
      break;
    case 'enfileirada': {
      const chave = chaveDo202(res);
      // as demais 202 so contam o 202 (acima do X acompanhar todas esgota os VUs); nas quentes TODA 202 e acompanhada (sem isso o par ficaria pending ate expirar e travaria o usuario)
      if (!chave || (!alvo.quente && sorteio100(i, 1) >= PCT_ACOMPANHA)) break;
      const r = acompanhar({
        consultar: () => { const q = bff(c, 'GET', `/bff/event-wallets/admission?idempotencyKey=${encodeURIComponent(chave)}`, null, s, '/bff/event-wallets/admission'); return { status: q.status, body: q.body }; },
        dormir: (ms) => sleep(ms / 1000),
        agora: () => Date.now(),
        consultaMs: CONSULTA_MS,
        tetoMs: TETO_CONSULTA_S * 1000,
      });
      if (r.estado === 'pending') finalizar(c, s, eventId, i, alvo.quente);
      break;
    }
    case 'erro_5xx': avisar('reserva 5xx', res); break;
    default: // lotado (400), 429, 422 e o resto: esperados no S2 / contados pelo codigo no CSV; o 400 tambem por motivo
      if (res.status === 400) reserva400.add(1, { ...tg(c, '/bff/event-wallets'), motivo: motivoDo400(res.body) });
  }
}

// ---- login (7%)
export function login() {
  const c = ctx('login');
  const u = USERS[(exec.scenario.iterationInTest * 7919) % USERS.length];
  const r = bff(c, 'POST', '/bff/users/login', { email: u.email, password: u.password }, null, '/bff/users/login', true);
  if (r.status >= 500) avisar('login 5xx', r);
}

// ---- cadastro (2%): request-verification -> codigo no Mailpit -> verify-email
// O codigo sai da pagina das mensagens MAIS NOVAS (`GET /api/v1/messages?start=&limit=100`, ~3 ms constantes), nunca de uma busca: a `search?query=to:` varre a caixa inteira (O(n)) e saturou o Mailpit no S2.
// Uma consulta le paginas ate achar o endereco; o criterio de parada (pagina vazia, mais antiga que o request-verification, teto de paginas) e o `acharNaPagina` do lib-soak.js.
function codigoNoMailpit(c, email, desdeMs) {
  for (let pg = 0, continua = true; continua; pg++) {
    const lista = mailpit(c, `/api/v1/messages?start=${pg * 100}&limit=100`, '/api/v1/messages');
    if (lista.status !== 200) return null;
    let r;
    try { r = acharNaPagina(lista.json().messages || [], email, desdeMs, pg); } catch (e) { return null; }
    if (!r.msg) { continua = r.continua; continue; }
    let codigo = extrairCodigo(r.msg.Snippet);
    if (!codigo) { // o resumo do Mailpit e curto: se o codigo nao veio nele, le a mensagem
      const m = mailpit(c, `/api/v1/message/${r.msg.ID}`, '/api/v1/message/{id}');
      if (m.status === 200) { try { codigo = extrairCodigo(m.json().Text); } catch (e) { codigo = null; } }
    }
    return codigo;
  }
  return null;
}

export function cadastro() {
  const c = ctx('cadastro');
  const n = exec.scenario.iterationInTest;
  const email = enderecoDoCadastro(SUFIXO, n);
  const desdeMs = Date.now();
  const r1 = bff(c, 'POST', '/bff/users/request-verification', {
    name: `Soak ${SUFIXO} ${n}`, email, password: 'SoakSenha#123', fone: foneDoCadastro(SUFIXO, n), birthdate: '1990-01-15',
  }, null, '/bff/users/request-verification', true);
  if (r1.status >= 500) { avisar('request-verification 5xx', r1); return; }
  if (r1.status !== 201) return; // 400 de negocio: sem e-mail para esperar
  let codigo = null;
  for (const espera of esperasDaBusca(MAILPIT_TETO_S)) { // 2, 2, 4, 4... s: menos polling no Mailpit; o prazo total segue MAILPIT_TETO_S
    sleep(espera);
    codigo = codigoNoMailpit(c, email, desdeMs);
    if (codigo) break;
  }
  if (!codigo) return; // sem e-mail no prazo: o verificar-soak confere o Mailpit no fim (perdido sempre reprova)
  const r2 = bff(c, 'POST', '/bff/users/verify-email', { email, verificationCode: codigo }, null, '/bff/users/verify-email', true);
  if (r2.status >= 500) avisar('verify-email 5xx', r2);
}

// ---- escrita de catalogo (1%): o host cria e edita um evento (nome <PREFIXO>-W<n>: o verificar-rpm cobre o prefixo)
export function escrita(d) {
  if (!d.host) return;
  const c = ctx('escrita');
  const n = exec.scenario.iterationInTest;
  const ev = { event_name: `${PREFIXO}-W${n}`, is_EAD: true, event_date: '2035-12-01T14:00:00', description: 'soak #232', lot_quantity: 100, presenters: [] };
  const r1 = bff(c, 'POST', '/bff/events', ev, d.host, '/bff/events');
  if (r1.status >= 500) { avisar('cria evento 5xx', r1); return; }
  if (r1.status !== 201) return;
  let id = null;
  try { id = r1.json().event_id; } catch (e) { id = null; }
  if (!id) return;
  const r2 = bff(c, 'PUT', `/bff/events/${id}`, Object.assign({}, ev, { event_id: id, presenters: [`Apresentador ${n}`] }), d.host, '/bff/events/{id}', true);
  if (r2.status >= 500) avisar('edita evento 5xx', r2);
}
