// Cenario k6 de ESCADA DE CARGA (issue #14), a partir do teste de taxa fixa (rpm/carga-rpm.js): um cenario
// constant-arrival-rate (modelo aberto) por degrau, em sequencia via startTime, numa UNICA execucao do k6
// (seed e login uma vez so). Mede onde o fluxo sincrono (reserva + confirma) degrada: o limiar X.
//
// Cada iteracao e um par (usuario, evento) unico. MAPEAMENTO (lib-mapeamento.js) escolhe como o indice i vira o par:
// sequencial (padrao, como no teste 2) = usuario i % U, evento floor(i / U) (evento disputado); intercalado = usuario
// floor(i / E), evento i % E (E reservas seguidas em eventos diferentes: capacidade do host).
// O k6 reinicia scenario.iterationInTest em 0 a CADA cenario, entao o degrau k usa a faixa de indices
// [base_k, base_k + cota_k), com base_k = soma das cotas anteriores e cota = ceil(RPM * DURACAO_S / 60) + 1
// (o executor pode emitir 1 iteracao a mais). Iteracoes alem da cota nao sao enviadas (contador
// iteracoes_alem_dos_pares). O setup() aborta se usuarios x eventos < soma das cotas. A mesma conta
// esta em degraus/lib-degraus.ps1 (Get-PlanoEscada), que dimensiona o seed.
//
// Degraus separados: startTime_k = k * (DURACAO_S + ESPACO_S), gracefulStop = GRACEFUL_S e ESPACO_S = GRACEFUL_S + 1,
// entao as iteracoes em voo de um degrau terminam (e contam NELE) antes do proximo comecar, e nem os planos de VUs
// dos cenarios se tocam (cada cenario pre-aloca MAX_VUS: dois ao mesmo tempo dobrariam a memoria).
//
// VUs: preAllocatedVUs = maxVUs = MAX_VUS. O constant-arrival-rate DESCARTA a iteracao quando nao ha VU livre e so
// aloca VU extra depois, em segundo plano (e isso pesa no gerador): com tudo pre-alocado, so ha descarte quando a
// iteracao dura mais que MAX_VUS / (iteracoes por segundo), ou seja, quando o host ja degradou.
//
// Metricas por degrau: submetricas por tag scenario (declaradas em thresholds, sempre verdadeiros: so existem
// no handleSummary se declaradas). p95 sustentado por 30 s: cada reserva e tagueada com
// janela = "<degrau>_<w>", w = floor((instante de ENVIO - exec.scenario.startTime) / 30 s), e o handleSummary
// emite o p95 de cada janela (submetrica reserva_duration{janela:...}); p95_max_janela_ms = o maior. Pelo envio, nao
// pelo termino: a reserva lenta enviada no fim do degrau conta na janela em que foi enviada, sem retardatarias.
//
// Respostas da reserva: 201 aceita | 400 "lotado" | >= 500 erro de servidor | outro (inclui status 0:
// timeout/conexao) inesperado. Cada aceita e confirmada (POST /bff/event-wallets/confirm) com PCT_CONFIRMA %.
// 5xx da reserva (reservas_5xx -> erros_5xx) e da confirmacao (confirma_5xx) sao contados SEPARADOS por degrau: so o da
// reserva entra na taxa do limiar X (taxa_5xx = erros_5xx / reservas, em lib-degraus.ps1); o da confirmacao e informativo.
//
// Escada ACIMA do X com o admission ligado (issue #178, Rodada 4). Acima de ~80% de X por evento o POST /bff/event-wallets responde 202
// (modo fila, #43) em vez de 201. Cada 202 conta como ACEITA (reservas_202, nunca "outros"); uma fracao PCT_ACOMPANHA % das 202 acompanha o estado
// (GET /bff/event-wallets/admission a cada CONSULTA_MS, ate pending / rejected / timed_out ou TETO_CONSULTA_S) e confirma o pending, como o
// sincrono. 429 (limite por usuario / fila cheia) e 422 tem contador proprio. Logica pura (classificacao, acompanhamento, parada) em
// lib-admissao.js, testada por lib-admissao.selftest.mjs. Metricas por degrau (alem das de antes): reservas_202, acompanhadas, admitidas
// (pending), rejected, timed_out, sem_estado, consultas_estado, tempo ate admitir (202 -> pending; resolucao = CONSULTA_MS) e latencia
// ponta a ponta (envio -> resultado final: 201 + confirma, ou estado final + confirma). Sem Idempotency-Key propria por padrao (o caminho sincrono fica identico ao da R, X = 56k); a chave do acompanhamento vem do 202 (header ou corpo). IDEMPOTENCY_KEY=1 manda chave propria por iteracao.
//
// ORCAMENTO DE VUs: acima do X uma iteracao acompanhada dura ate o TTL da fila (30 s), nao ~10 ms. VUs necessarios ~= it/s x (PCT_ACOMPANHA/100 x
// espera media + (1 - PCT_ACOMPANHA/100) x 0,2 s). Com MAX_VUS=2000 e ~1000 it/s so cabe uma espera media de 2 s: com 100% acompanhado isso vira
// dropped_iterations e o report diria "gerador saturado". Por isso o acompanhamento e AMOSTRADO (PCT_ACOMPANHA): as demais 202 so contam o 202.
// As nao acompanhadas continuam na fila e viram pending (seguram vaga ate expirar, ~120 s): isso desloca "lotado"/rejected, documente a fracao.
// O gracefulStop passa a ser max(30, TETO_CONSULTA_S + 10) quando ha acompanhamento (a iteracao acompanhada vive ate o teto), e ESPACO_S = graceful + 1.
//
// CONDICAO DE PARADA (latencia que nao se recupera; PARADA_P95_MS > 0): um threshold por janela de PARADA_JANELA_S s de cada degrau (tag `parada`,
// unica por janela, entao o p95 cumulativo do threshold vale so para a janela) com abortOnFail e delayAbortEval no fim da janela. Reprovou: o k6
// aborta (codigo 99), o handleSummary ainda grava o resumo com `parada`, o degrau da parada vira PARCIAL (duracao efetiva ate o fim da janela) e os
// seguintes sao OMITIDOS. O runner grava o CSV dos completos + o parcial, com o motivo na coluna `parada`.
//
// Variaveis (passadas pelo rodar-degraus.ps1):
//   BASE_URL, RPMS (lista crescente "12000,16000,..."), DURACAO_S (por degrau), USERS_FILE, EVENTS_FILE,
//   (ou, SEM arquivos, como no gerador da madrugada: USUARIOS + SUFIXO e EVENT_IDS "101-350,360"; ver README, "Rodada 4 no gerador"),
//   SUMMARY_FILE, MAX_VUS (opcional, padrao 2000), PCT_CONFIRMA (0-100, padrao 100),
//   MAPEAMENTO (opcional: sequencial, padrao | intercalado; outro valor aborta o setup)
//   Issue #178 (todas opcionais): PCT_ACOMPANHA (0-100, padrao 100), CONSULTA_MS (padrao 1000, o Retry-After), TETO_CONSULTA_S (padrao 35, > TTL da
//   fila de 30 s), PARADA_P95_MS (0 = sem parada, padrao), PARADA_JANELA_S (padrao 60), IDEMPOTENCY_KEY (1 = chave propria por iteracao; padrao 0 = sem chave, a do 202 e usada),
//   RUN_ID (prefixo das chaves; padrao = instante do init)
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { erroMapeamento, parDe } from './lib-mapeamento.js';
import {
  acompanhar, avaliarParada, classificarReserva, erroParametros, esperaDoSetup, gracefulS, indiceJanela, limiaresParada,
  idsDeEventos, situacaoDoDegrau, textoParada, usuariosDoSufixo,
} from './lib-admissao.js';

const BASE = __ENV.BASE_URL;
const DURACAO_S = parseInt(__ENV.DURACAO_S, 10);
const RPMS = __ENV.RPMS.split(',').map((r) => parseInt(r, 10));
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2000', 10);
const PCT_CONFIRMA = parseInt(__ENV.PCT_CONFIRMA || '100', 10);
const MAPEAMENTO = __ENV.MAPEAMENTO || 'sequencial';   // validado no setup() (antes do login)
const PCT_ACOMPANHA = parseFloat(__ENV.PCT_ACOMPANHA || '100');
const CONSULTA_MS = parseInt(__ENV.CONSULTA_MS || '1000', 10);
const TETO_CONSULTA_S = parseInt(__ENV.TETO_CONSULTA_S || '35', 10);
const PARADA_P95_MS = parseInt(__ENV.PARADA_P95_MS || '0', 10);
const PARADA_JANELA_S = parseInt(__ENV.PARADA_JANELA_S || '60', 10);
const SETUP_S = parseInt(__ENV.SETUP_S || '0', 10);   // orcamento do setup() no relogio do teste (o delayAbortEval conta o setup); ver lib-admissao.js
const COM_CHAVE = (__ENV.IDEMPOTENCY_KEY || '0') === '1';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const GRACEFUL_S = gracefulS(PCT_ACOMPANHA, TETO_CONSULTA_S);
const ESPACO_S = GRACEFUL_S + 1;     // entre degraus (ver acima; mesma conta em lib-degraus.ps1, Get-GracefulS)
const JANELA_MS = 30000;
const TOL_JANELA_MS = 1000;      // jitter de relogio tolerado nas pontas do degrau
const LIM_FORA_PCT = 1;          // % das reservas do degrau fora das janelas a partir do qual a trava recusa (> 1%)
const NJ = Math.floor(DURACAO_S / 30);   // janelas inteiras por degrau (o runner exige D >= 60 e multiplo de 30)
// PowerShell 5.1 grava UTF8 com BOM; JSON.parse nao aceita BOM.
const lerJson = (f) => JSON.parse(open(f).replace(/^\uFEFF/, ''));
// Sem USERS_FILE/EVENTS_FILE (o gerador da madrugada so roda `k6 run`): usuarios por USUARIOS + SUFIXO e eventos por EVENT_IDS (preparar-degraus.ps1 no host).
const USERS = __ENV.USERS_FILE ? lerJson(__ENV.USERS_FILE) : usuariosDoSufixo(parseInt(__ENV.USUARIOS, 10), __ENV.SUFIXO);
const EVENTS = __ENV.EVENTS_FILE ? lerJson(__ENV.EVENTS_FILE) : idsDeEventos(__ENV.EVENT_IDS);
const MAX_PARES = USERS.length * EVENTS.length;

let base = 0;
const PLANO = RPMS.map((rpm, k) => {
  const cota = Math.ceil((rpm * DURACAO_S) / 60) + 1;
  const p = { nome: `degrau_${rpm}`, rpm, inicioS: k * (DURACAO_S + ESPACO_S), base, cota };
  base += cota;
  return p;
});
const TOTAL_PARES = base;
const DO_CENARIO = {};
PLANO.forEach((p) => { DO_CENARIO[p.nome] = p; });

const aceitas = new Counter('reservas_aceitas');
const lotado = new Counter('reservas_lotado');
const erros5xx = new Counter('reservas_5xx');
const outros = new Counter('reservas_outros');
const semLogin = new Counter('usuarios_sem_login');
const alemDosPares = new Counter('iteracoes_alem_dos_pares');
const reservaDur = new Trend('reserva_duration', true);
const confirmadas = new Counter('confirma_aceitas');
const confirmaEsperado = new Counter('confirma_4xx_esperado');
const confirma5xx = new Counter('confirma_5xx');
const confirmaOutros = new Counter('confirma_outros');
const confirmaDur = new Trend('confirma_duration', true);
// issue #178: modo fila
const enfileiradas = new Counter('reservas_202');
const recusadas429 = new Counter('reservas_429');
const conflitos422 = new Counter('reservas_422');
const acompanhadas = new Counter('adm_acompanhadas');
const admitidas = new Counter('adm_admitidas');
const rejeitadas = new Counter('adm_rejected');
const expiradas = new Counter('adm_timed_out');
const semEstado = new Counter('adm_sem_estado');
const consultas = new Counter('adm_consultas');
const admitirDur = new Trend('adm_admitir_duration', true);   // 202 -> pending (resolucao = CONSULTA_MS)
const fimAFimDur = new Trend('fim_a_fim_duration', true);     // envio -> resultado final (201 + confirma | estado final + confirma)

// metricas do resumo por degrau (nome -> tipo); dropped_iterations e do proprio k6
const SUB_TREND = ['reserva_duration', 'confirma_duration', 'adm_admitir_duration', 'fim_a_fim_duration'];
const SUB_COUNTER = ['reservas_5xx', 'confirma_5xx', 'reservas_outros', 'usuarios_sem_login', 'iteracoes_alem_dos_pares', 'dropped_iterations',
  'reservas_202', 'reservas_429', 'reservas_422', 'adm_acompanhadas', 'adm_admitidas', 'adm_rejected', 'adm_timed_out', 'adm_sem_estado', 'adm_consultas'];

const scenarios = {};
const thresholds = {};
for (const p of PLANO) {
  scenarios[p.nome] = {
    executor: 'constant-arrival-rate',
    rate: p.rpm,
    timeUnit: '1m',
    duration: `${DURACAO_S}s`,
    startTime: `${p.inicioS}s`,
    gracefulStop: `${GRACEFUL_S}s`,
    preAllocatedVUs: MAX_VUS,
    maxVUs: MAX_VUS,
  };
  // sem threshold a submetrica "metrica{scenario:degrau}" nao existe no handleSummary; max>=0 / count>=0 nunca reprovam
  for (const m of SUB_TREND) thresholds[`${m}{scenario:${p.nome}}`] = ['max>=0'];
  for (const m of SUB_COUNTER) thresholds[`${m}{scenario:${p.nome}}`] = ['count>=0'];
  // p95 por janela de 30 s: tag de chave unica "janela", valor <degrau>_<w>
  for (let w = 0; w < NJ; w++) thresholds[`reserva_duration{janela:${p.nome}_${w}}`] = ['max>=0'];
}
// condicao de parada (issue #178): so com PARADA_P95_MS > 0 e parametros validos (se invalidos o setup() aborta antes de qualquer carga)
const ERRO_PARAMS = erroParametros({ pctAcompanha: PCT_ACOMPANHA, consultaMs: CONSULTA_MS, tetoConsultaS: TETO_CONSULTA_S,
  paradaP95Ms: PARADA_P95_MS, paradaJanelaS: PARADA_JANELA_S, duracaoS: DURACAO_S, setupS: SETUP_S });
if (!ERRO_PARAMS && PARADA_P95_MS > 0) Object.assign(thresholds, limiaresParada(PLANO, PARADA_JANELA_S, DURACAO_S, PARADA_P95_MS, SETUP_S));

// Janela de 30 s do instante de ENVIO `t` no degrau p. Ate TOL_JANELA_MS de jitter nas pontas vai para a janela da
// ponta; mais que isso vai para "_fora", que nao tem submetrica: a reserva conta no p50/p95/p99 do degrau mas nao em
// nenhuma janela. Poucas "_fora" sao atraso de agendamento (gerador saturado no fim do degrau): so sao contadas
// (fora_das_janelas). Muitas (> LIM_FORA_PCT % das reservas do degrau) sao sistematicas (ex.: exec.scenario.startTime
// com outra semantica): ver avaliarJanelas.
function janelaDe(p, t) {
  const dec = t - exec.scenario.startTime;
  if (!(dec >= -TOL_JANELA_MS && dec < DURACAO_S * 1000 + TOL_JANELA_MS)) return `${p.nome}_fora`;
  return `${p.nome}_${Math.min(NJ - 1, Math.max(0, Math.floor(dec / JANELA_MS)))}`;
}

// Reservas fora das janelas de um degrau (reservas - soma das janelas). Pura (exportada so para o teste; o k6 ignora
// exports que nenhum cenario usa). Sistemico = soma maior que as reservas (impossivel) ou fora > LIM_FORA_PCT % das
// reservas, em inteiros (exatamente 1% NAO e sistemico). Ate la o resumo so registra a contagem e o runner avisa;
// acima, as janelas nao representam o degrau e o runner recusa o CSV.
export function avaliarJanelas(reservas, somaJanelas) {
  const fora = reservas - somaJanelas;
  return { fora, sistemico: fora < 0 || fora * 100 > reservas * LIM_FORA_PCT };
}

export const options = {
  scenarios,
  thresholds,
  setupTimeout: '300s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Login de todos os usuarios ANTES da carga (nao entra na medicao).
export function setup() {
  const erroMap = erroMapeamento(MAPEAMENTO);
  if (erroMap) exec.test.abort(erroMap);
  if (ERRO_PARAMS) exec.test.abort(ERRO_PARAMS);
  if (MAX_PARES < TOTAL_PARES) {
    exec.test.abort(`Faltam pares unicos: ${USERS.length} usuarios x ${EVENTS.length} eventos = ${MAX_PARES} < ${TOTAL_PARES} (soma das cotas dos degraus). Semeie mais eventos.`);
  }
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
  let loginsS = -1;
  if (SETUP_S > 0) {   // o relogio do delayAbortEval inclui o setup(): os degraus so comecam quando o relogio do teste chega em SETUP_S
    loginsS = Math.round(exec.instance.currentTestRunDuration / 100) / 10;
    const w = esperaDoSetup(exec.instance.currentTestRunDuration, SETUP_S, PARADA_P95_MS > 0);
    if (w.erro) exec.test.abort(w.erro);
    if (w.esperaMs > 0) sleep(w.esperaMs / 1000);
  }
  console.log(`setup: ${ok}/${USERS.length} logins ok; ${EVENTS.length} eventos; ${MAX_PARES} pares unicos; ${PLANO.length} degraus usam ${TOTAL_PARES}; mapeamento ${MAPEAMENTO}; acompanha ${PCT_ACOMPANHA}% (consulta ${CONSULTA_MS} ms, teto ${TETO_CONSULTA_S} s); graceful ${GRACEFUL_S} s; parada ${PARADA_P95_MS > 0 ? `p95 > ${PARADA_P95_MS} ms em janela de ${PARADA_JANELA_S} s (SETUP_S ${SETUP_S}${loginsS >= 0 ? `; logins levaram ${loginsS} s` : ''})` : 'desligada'}`);
  return sessoes;
}

// Sorteio deterministico em [0, 100) a partir de (indice, semente): decide quais aceitas confirmar
// sem consumir Math.random.
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
    if (exec.scenario.iterationInTest < 5) console.warn(`confirma 5xx: HTTP ${res.status} ${String(res.body).slice(0, 160)}`);
  } else {
    confirmaOutros.add(1);
    if (exec.scenario.iterationInTest < 5) console.warn(`confirma inesperada: HTTP ${res.status} ${String(res.body).slice(0, 160)}`);
  }
}

// Acompanha uma reserva enfileirada (202): consulta o estado ate pending / rejected / timed_out (ou o teto) e confirma o pending.
// Devolve true se chegou a um estado final (entra na latencia ponta a ponta).
// `acompanhadas` e `adm_consultas` entram JUNTO do destino, depois do acompanhamento: uma iteracao que o k6 corta no meio (a parada por latencia aborta
// o teste com acompanhamentos de ate 30 s em voo; o gracefulStop tambem pode vencer) nao conta nenhum dos dois (acompanhadas e destinos andam juntos).
// Efeito: as cortadas somem destes contadores (so aparecem no reservas_202), o que inclina as taxas para os desfechos mais rapidos; documentado no README.
function acompanharFila(s, eventId, chave, i, envio) {
  const r = acompanhar({
    consultar: () => {
      const res = http.get(`${BASE}/bff/event-wallets/admission?idempotencyKey=${encodeURIComponent(chave)}`, {
        headers: { Authorization: `Bearer ${s.token}` },
        tags: { name: 'estado' },
      });
      return { status: res.status, body: res.body };
    },
    dormir: (ms) => sleep(ms / 1000),
    agora: () => Date.now(),
    consultaMs: CONSULTA_MS,
    tetoMs: TETO_CONSULTA_S * 1000,
  });
  acompanhadas.add(1);
  consultas.add(r.consultas);
  if (r.estado === 'sem_estado') { semEstado.add(1); return false; }
  if (r.estado === 'rejected') rejeitadas.add(1);
  else if (r.estado === 'timed_out') expiradas.add(1);
  else {
    admitidas.add(1);
    admitirDur.add(r.esperaMs);
    if (sorteio100(i, 0) < PCT_CONFIRMA) confirmar(s, eventId);
  }
  fimAFimDur.add(Date.now() - envio);
  return true;
}

function chaveDo202(res) {
  const h = res.headers || {};
  const doHeader = h['Idempotency-Key'] || h['idempotency-key'];
  if (doHeader) return doHeader;
  try { return JSON.parse(String(res.body)).idempotencyKey || ''; } catch (e) { return ''; }
}

export default function (sessoes) {
  const p = DO_CENARIO[exec.scenario.name];
  const j = exec.scenario.iterationInTest;
  if (j >= p.cota) {
    alemDosPares.add(1); // alem da cota do degrau: o indice cairia na faixa do proximo (par repetido): nao envia
    return;
  }
  const i = p.base + j;
  const par = parDe(i, USERS.length, EVENTS.length, MAPEAMENTO);
  const s = sessoes[par.u];
  if (!s) {
    semLogin.add(1);
    return;
  }
  const eventId = EVENTS[par.e];
  const envio = Date.now();   // instante de ENVIO: define a janela de 30 s da amostra
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${s.token}` };
  if (COM_CHAVE) headers['Idempotency-Key'] = `k6-${RUN_ID}-${i}`;
  const res = http.post(
    `${BASE}/bff/event-wallets`,
    JSON.stringify({ userId: s.id, eventId }),
    { headers, tags: { name: 'reserva' } }
  );
  const tags = { janela: janelaDe(p, envio) };
  if (PARADA_P95_MS > 0) {
    const pw = indiceJanela(envio - exec.scenario.startTime, PARADA_JANELA_S, DURACAO_S, TOL_JANELA_MS);
    tags.parada = pw < 0 ? `${p.nome}_fora` : `${p.nome}_${pw}`;
  }
  reservaDur.add(res.timings.duration, tags);
  switch (classificarReserva(res.status, res.body)) {
    case 'aceita':
      aceitas.add(1);
      if (sorteio100(i, 0) < PCT_CONFIRMA) confirmar(s, eventId);
      fimAFimDur.add(Date.now() - envio);
      break;
    case 'enfileirada': {
      enfileiradas.add(1);
      // a chave do acompanhamento: a propria (IDEMPOTENCY_KEY=1) ou a do 202 (header Idempotency-Key ou idempotencyKey do corpo)
      const chave = COM_CHAVE ? headers['Idempotency-Key'] : chaveDo202(res);
      if (chave && sorteio100(i, 1) < PCT_ACOMPANHA) acompanharFila(s, eventId, chave, i, envio);
      break;
    }
    case 'lotado': lotado.add(1); break;
    case 'recusada_429': recusadas429.add(1); break;
    case 'conflito_422': conflitos422.add(1); break;
    case 'erro_5xx':
      erros5xx.add(1);
      if (j < 5) console.warn(`5xx: HTTP ${res.status} ${String(res.body).slice(0, 160)}`);
      break;
    default:
      outros.add(1);
      if (j < 5) console.warn(`inesperado: HTTP ${res.status} ${String(res.body).slice(0, 160)}`);
  }
}

function contador(data, nome) {
  const m = data.metrics[nome];
  return m && m.values && m.values.count ? m.values.count : 0;
}

export function handleSummary(data) {
  // faltando = so informativo: uma submetrica sem amostras no degrau (ex.: nenhuma confirma com PCT_CONFIRMA=0, ou
  // um degrau em que toda reserva deu 5xx) pode nao aparecer e vale 0. O que barra o CSV e a conferencia abaixo.
  const faltando = [];
  const sub = (nome, p) => (data.metrics[`${nome}{scenario:${p.nome}}`] || {}).values || null;
  const cont = (nome, p) => { const v = sub(nome, p); return v && v.count ? v.count : 0; };
  // parada por latencia (issue #178): a primeira janela cujo threshold abortOnFail reprovou; degrau da parada = parcial, os seguintes = omitidos
  const parada = PARADA_P95_MS > 0 && !ERRO_PARAMS
    ? avaliarParada(PLANO, PARADA_JANELA_S, DURACAO_S, PARADA_P95_MS, (nome) => data.metrics[nome])
    : null;
  const degraus = PLANO.map((p, k) => {
    // p95 de cada janela de 30 s (submetrica reserva_duration{janela:<degrau>_<w>}); janela sem amostras nao conta
    const janelas = [];
    for (let w = 0; w < NJ; w++) {
      const v = (data.metrics[`reserva_duration{janela:${p.nome}_${w}}`] || {}).values;
      if (v && v.count) janelas.push({ w, n: v.count, p95_ms: v['p(95)'] || 0 });
    }
    const sit = situacaoDoDegrau(parada, k, DURACAO_S, janelas);   // parcial: duracao efetiva = ultima janela com amostras
    const r = sub('reserva_duration', p);
    const c = sub('confirma_duration', p);
    // degrau omitido pela parada nunca rodou: sem amostras e normal, nao entra em `faltando`
    if (!r && sit.situacao !== 'omitido') faltando.push(`reserva_duration{scenario:${p.nome}}`);
    if (!c && sit.situacao !== 'omitido') faltando.push(`confirma_duration{scenario:${p.nome}}`);
    const rv = r || {};
    const adm = sub('adm_admitir_duration', p) || {};
    const ff = sub('fim_a_fim_duration', p) || {};
    return {
      nome: p.nome,
      situacao: sit.situacao,                 // completo | parcial | omitido (so com parada)
      duracao_efetiva_s: sit.duracaoEfetivaS,
      rpm_alvo: p.rpm,
      cota: p.cota,
      reservas: rv.count || 0,
      confirmas: (c || {}).count || 0,
      erros_5xx: cont('reservas_5xx', p),
      confirma_5xx: cont('confirma_5xx', p),
      outros: cont('reservas_outros', p),
      sem_login: cont('usuarios_sem_login', p),
      alem_dos_pares: cont('iteracoes_alem_dos_pares', p),
      descartadas: cont('dropped_iterations', p),
      reservas_202: cont('reservas_202', p),
      acompanhadas: cont('adm_acompanhadas', p),
      admitidas: cont('adm_admitidas', p),
      rejected: cont('adm_rejected', p),
      timed_out: cont('adm_timed_out', p),
      sem_estado: cont('adm_sem_estado', p),
      recusadas_429: cont('reservas_429', p),
      conflitos_422: cont('reservas_422', p),
      consultas_estado: cont('adm_consultas', p),
      admitir_p50_ms: adm.med || 0,
      admitir_p95_ms: adm['p(95)'] || 0,
      admitir_p99_ms: adm['p(99)'] || 0,
      fim_a_fim_n: ff.count || 0,
      fim_a_fim_p50_ms: ff.med || 0,
      fim_a_fim_p95_ms: ff['p(95)'] || 0,
      fim_a_fim_p99_ms: ff['p(99)'] || 0,
      p50_ms: rv.med || 0,
      p95_ms: rv['p(95)'] || 0,
      p99_ms: rv['p(99)'] || 0,
      janelas,
      p95_max_janela_ms: janelas.length ? Math.max(...janelas.map((j) => j.p95_ms)) : -1,
      fora_das_janelas: rv.count ? avaliarJanelas(rv.count, janelas.reduce((a, j) => a + j.n, 0)).fora : 0,
    };
  });
  // Conferencia (a UNICA trava): o global de cada metrica tem de ser a soma dos degraus (tudo roda dentro de um
  // degrau). Se nao for (ex.: a metrica nao leva a tag scenario, ou o k6 nao materializou as submetricas), o CSV
  // subnotificaria 5xx/descartadas: o runner recusa. Submetrica ausente por falta de amostras fecha (0 = 0).
  const inconsistencias = [];
  const confere = (nome, campo, nomeMetrica) => {
    const soma = degraus.reduce((a, d) => a + d[campo], 0);
    const global = contador(data, nomeMetrica || nome);
    if (soma !== global) inconsistencias.push(`${nomeMetrica || nome}: global ${global} != soma dos degraus ${soma}`);
  };
  confere('reservas_5xx', 'erros_5xx');
  confere('confirma_5xx', 'confirma_5xx');
  confere('reservas_outros', 'outros');
  confere('usuarios_sem_login', 'sem_login');
  confere('iteracoes_alem_dos_pares', 'alem_dos_pares');
  confere('dropped_iterations', 'descartadas');
  confere('reserva_duration', 'reservas');
  confere('confirma_duration', 'confirmas');
  confere('reservas_202', 'reservas_202');
  confere('reservas_429', 'recusadas_429');
  confere('reservas_422', 'conflitos_422');
  confere('adm_acompanhadas', 'acompanhadas');
  confere('adm_admitidas', 'admitidas');
  confere('adm_rejected', 'rejected');
  confere('adm_timed_out', 'timed_out');
  confere('adm_sem_estado', 'sem_estado');
  confere('adm_consultas', 'consultas_estado');
  confere('fim_a_fim_duration', 'fim_a_fim_n');
  // As reservas tem de cair nas janelas do proprio degrau. Poucas fora (<= 1%: atraso de agendamento com o gerador
  // saturado no fim do degrau) so ficam registradas em fora_das_janelas; acima disso (ex.: exec.scenario.startTime com
  // outra semantica) o p95 por janela nao representa o degrau: inconsistencia.
  for (const d of degraus) {
    const soma = d.janelas.reduce((a, j) => a + j.n, 0);
    if (avaliarJanelas(d.reservas, soma).sistemico) inconsistencias.push(`${d.nome}: ${d.reservas - soma} de ${d.reservas} reservas fora das janelas (> ${LIM_FORA_PCT}%): tag janela ou exec.scenario.startTime fora do esperado`);
  }
  const out = {
    duracao_degrau_s: DURACAO_S,
    duracao_s: (data.state.testRunDurationMs || 0) / 1000,
    usuarios: USERS.length,
    eventos: EVENTS.length,
    pares_planejados: TOTAL_PARES,
    max_vus: MAX_VUS,
    vus_max: ((data.metrics.vus_max || {}).values || {}).max || 0,
    pct_confirma: PCT_CONFIRMA,
    mapeamento: MAPEAMENTO,
    pct_acompanha: PCT_ACOMPANHA,
    consulta_ms: CONSULTA_MS,
    teto_consulta_s: TETO_CONSULTA_S,
    graceful_s: GRACEFUL_S,
    parada_p95_ms: PARADA_P95_MS,
    parada_janela_s: PARADA_JANELA_S,
    parada,
    parada_texto: textoParada(parada),
    degraus,
    faltando,
    inconsistencias,
  };
  let texto = '\nresumo por degrau:\n';
  if (parada) texto += `  PARADA: ${textoParada(parada)} (p95 da janela ${parada.p95_ms.toFixed(0)} ms); degraus seguintes omitidos\n`;
  for (const d of degraus.filter((x) => x.situacao !== 'omitido')) {
    texto += `  ${d.nome}${d.situacao === 'parcial' ? ' (parcial)' : ''}: reservas=${d.reservas} 5xx_reservas=${d.erros_5xx} 5xx_confirma=${d.confirma_5xx} outros=${d.outros} descartadas=${d.descartadas} p50=${d.p50_ms.toFixed(1)}ms p95=${d.p95_ms.toFixed(1)}ms p99=${d.p99_ms.toFixed(1)}ms p95_max_janela=${d.p95_max_janela_ms.toFixed(1)}ms${d.fora_das_janelas ? ` fora_das_janelas=${d.fora_das_janelas}` : ''}${d.reservas_202 || d.recusadas_429 ? ` 202=${d.reservas_202} acomp=${d.acompanhadas} admitidas=${d.admitidas} rejected=${d.rejected} timed_out=${d.timed_out} sem_estado=${d.sem_estado} 429=${d.recusadas_429} admitir_p95=${d.admitir_p95_ms.toFixed(0)}ms fim_a_fim_p95=${d.fim_a_fim_p95_ms.toFixed(0)}ms` : ''}\n`;
  }
  if (faltando.length) texto += `  sem amostras (tratadas como 0): ${faltando.join(', ')}\n`;
  if (inconsistencias.length) texto += `  INCONSISTENTE: ${inconsistencias.join('; ')}\n`;
  return {
    [__ENV.SUMMARY_FILE]: JSON.stringify(out, null, 2),
    stdout: texto,
  };
}
