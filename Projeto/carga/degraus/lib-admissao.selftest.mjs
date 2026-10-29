// Auto-teste de lib-admissao.js (issue #178): classificacao das respostas 201/202/429/..., acompanhamento do estado da fila, parametros e
// parada por latencia. Sem k6, sem rede, sem banco: so node:assert. Uso: node degraus/lib-admissao.selftest.mjs
import assert from 'node:assert/strict';
import {
  classificarReserva, classificarEstado, acompanhar, gracefulS, erroParametros, esperaDoSetup, indiceJanela, limiaresParada,
  avaliarParada, situacaoDoDegrau, textoParada, nomeMetricaParada,
} from './lib-admissao.js';

let falhas = 0;
function confere(nome, fn) {
  try { fn(); console.log(`   [ok]    ${nome}`); }
  catch (e) { falhas++; console.log(`   [FALHA] ${nome}\n           ${String(e.message).split('\n').join('\n           ')}`); }
}

console.log('==> classificarReserva');
confere('201 aceita; 202 enfileirada (NUNCA outros)', () => {
  assert.equal(classificarReserva(201, ''), 'aceita');
  assert.equal(classificarReserva(202, '{"status":"queued"}'), 'enfileirada');
});
confere('400 lotado (qualquer caixa); 400 sem lotado e outros', () => {
  assert.equal(classificarReserva(400, 'Evento Lotado'), 'lotado');
  assert.equal(classificarReserva(400, 'userId invalido'), 'outros');
});
confere('429 e 422 tem classe propria (nao sao 5xx nem outros)', () => {
  assert.equal(classificarReserva(429, 'limite'), 'recusada_429');
  assert.equal(classificarReserva(422, 'chave de outro evento'), 'conflito_422');
});
confere('5xx; status 0 (timeout/conexao) e 401 sao outros', () => {
  assert.equal(classificarReserva(503, ''), 'erro_5xx');
  assert.equal(classificarReserva(500, ''), 'erro_5xx');
  assert.equal(classificarReserva(0, ''), 'outros');
  assert.equal(classificarReserva(401, ''), 'outros');
});

console.log('==> classificarEstado');
const resp = (status) => ({ status: 200, body: JSON.stringify({ status, eventId: 1 }) });
confere('queued nao e final; pending, rejected e timed_out sao finais', () => {
  assert.deepEqual(classificarEstado(200, resp('queued').body), { estado: 'queued', final: false });
  for (const s of ['pending', 'rejected', 'timed_out']) assert.deepEqual(classificarEstado(200, resp(s).body), { estado: s, final: true });
});
confere('404, 5xx, corpo que nao e JSON e status desconhecido viram erro (nao final)', () => {
  assert.deepEqual(classificarEstado(404, ''), { estado: 'erro', final: false });
  assert.deepEqual(classificarEstado(503, '{}'), { estado: 'erro', final: false });
  assert.deepEqual(classificarEstado(200, '<html>'), { estado: 'erro', final: false });
  assert.deepEqual(classificarEstado(200, '{"status":"xyz"}'), { estado: 'erro', final: false });
});

console.log('==> acompanhar (maquina de estados do acompanhamento)');
function ambiente(respostas, consultaMs = 100) {
  let t = 0; let i = 0;
  return {
    consultar: () => { const r = respostas[Math.min(i, respostas.length - 1)]; i++; return r; },
    dormir: (ms) => { t += ms; },
    agora: () => t,
    consultaMs,
  };
}
confere('queued, queued, pending: 3 consultas e espera = 3 x consultaMs', () => {
  const r = acompanhar({ ...ambiente([resp('queued'), resp('queued'), resp('pending')]), tetoMs: 5000 });
  assert.deepEqual(r, { estado: 'pending', consultas: 3, esperaMs: 300 });
});
confere('rejected e timed_out terminam o acompanhamento', () => {
  assert.equal(acompanhar({ ...ambiente([resp('rejected')]), tetoMs: 5000 }).estado, 'rejected');
  assert.equal(acompanhar({ ...ambiente([resp('queued'), resp('timed_out')]), tetoMs: 5000 }).estado, 'timed_out');
});
confere('queued para sempre: para no teto com sem_estado (nao fica preso)', () => {
  const r = acompanhar({ ...ambiente([resp('queued')]), tetoMs: 1000 });
  assert.equal(r.estado, 'sem_estado');
  assert.equal(r.consultas, 10);
});
confere('erros transitorios (503, 404) sao contados e o acompanhamento segue ate o estado final', () => {
  const r = acompanhar({ ...ambiente([{ status: 503, body: '' }, { status: 404, body: '' }, resp('pending')]), tetoMs: 5000 });
  assert.deepEqual(r, { estado: 'pending', consultas: 3, esperaMs: 300 });
});

console.log('==> gracefulS e parametros');
confere('graceful: 30 s sem acompanhamento; teto + 10 s com (nunca abaixo de 30)', () => {
  assert.equal(gracefulS(0, 120), 30);
  assert.equal(gracefulS(100, 35), 45);
  assert.equal(gracefulS(10, 5), 30);
});
const ok = { pctAcompanha: 100, consultaMs: 1000, tetoConsultaS: 35, paradaP95Ms: 0, paradaJanelaS: 60, duracaoS: 60 };
confere('parametros validos passam; invalidos dao mensagem', () => {
  assert.equal(erroParametros(ok), null);
  assert.match(erroParametros({ ...ok, pctAcompanha: 101 }), /PCT_ACOMPANHA/);
  assert.match(erroParametros({ ...ok, consultaMs: 10 }), /CONSULTA_MS/);
  assert.match(erroParametros({ ...ok, tetoConsultaS: 0 }), /TETO_CONSULTA_S/);
  assert.match(erroParametros({ ...ok, paradaP95Ms: -1 }), /PARADA_P95_MS/);
});
confere('janela de parada: so valida com a parada ligada; multiplo de 30 e divisor da duracao', () => {
  assert.equal(erroParametros({ ...ok, paradaJanelaS: 45 }), null);                            // parada desligada: ignora
  assert.match(erroParametros({ ...ok, paradaP95Ms: 5000, paradaJanelaS: 45 }), /PARADA_JANELA_S/);
  assert.match(erroParametros({ ...ok, paradaP95Ms: 5000, paradaJanelaS: 120 }), /PARADA_JANELA_S/);   // 120 nao divide 60
  assert.equal(erroParametros({ ...ok, paradaP95Ms: 5000, paradaJanelaS: 30 }), null);
  assert.equal(erroParametros({ ...ok, paradaP95Ms: 5000, paradaJanelaS: 60, duracaoS: 120 }), null);
});

console.log('==> janela de parada e thresholds');
confere('indiceJanela: alinhada ao inicio do degrau, jitter nas pontas vai para a ponta, fora e -1', () => {
  assert.equal(indiceJanela(0, 60, 120, 1000), 0);
  assert.equal(indiceJanela(59999, 60, 120, 1000), 0);
  assert.equal(indiceJanela(60000, 60, 120, 1000), 1);
  assert.equal(indiceJanela(-500, 60, 120, 1000), 0);
  assert.equal(indiceJanela(120500, 60, 120, 1000), 1);
  assert.equal(indiceJanela(122000, 60, 120, 1000), -1);
});
const plano = [{ nome: 'degrau_1000', inicioS: 0 }, { nome: 'degrau_2000', inicioS: 91 }];   // duracao 60, graceful 30 -> espaco 31
confere('limiaresParada: um threshold por janela, p95 < limite, abortOnFail, atraso = fim da janela contado do inicio do TESTE', () => {
  const th = limiaresParada(plano, 30, 60, 5000);
  assert.equal(Object.keys(th).length, 4);
  assert.deepEqual(th['reserva_duration{parada:degrau_1000_0}'], [{ threshold: 'p(95)<5000', abortOnFail: true, delayAbortEval: '30s' }]);
  assert.equal(th['reserva_duration{parada:degrau_1000_1}'][0].delayAbortEval, '60s');
  assert.equal(th['reserva_duration{parada:degrau_2000_0}'][0].delayAbortEval, '121s');
  assert.equal(th['reserva_duration{parada:degrau_2000_1}'][0].delayAbortEval, '151s');
});
confere('avaliarParada: acha a 1a janela reprovada (thresholds.ok === false); nada reprovado = null; limite 0 = desligado', () => {
  const metricas = {
    [nomeMetricaParada('degrau_1000', 0)]: { values: { 'p(95)': 80 }, thresholds: { 'p(95)<5000': { ok: true } } },
    [nomeMetricaParada('degrau_2000', 0)]: { values: { 'p(95)': 9000 }, thresholds: { 'p(95)<5000': { ok: false } } },
  };
  const p = avaliarParada(plano, 30, 60, 5000, (n) => metricas[n]);
  assert.deepEqual(p, { degrau: 'degrau_2000', indice: 1, pw: 0, p95_ms: 9000, limite_ms: 5000, janela_s: 30 });
  assert.equal(avaliarParada(plano, 30, 60, 5000, () => undefined), null);
  assert.equal(avaliarParada(plano, 30, 60, 0, (n) => metricas[n]), null);
});
confere('avaliarParada: sem o campo thresholds (k6 antigo) cai no p95 final > limite', () => {
  const m = { [nomeMetricaParada('degrau_1000', 1)]: { values: { 'p(95)': 5001 } } };
  assert.equal(avaliarParada(plano, 30, 60, 5000, (n) => m[n]).pw, 1);
  const m2 = { [nomeMetricaParada('degrau_1000', 1)]: { values: { 'p(95)': 5000 } } };
  assert.equal(avaliarParada(plano, 30, 60, 5000, (n) => m2[n]), null);
});
confere('situacaoDoDegrau: sem parada tudo completo; com parada completo / parcial (ate o fim da janela) / omitido', () => {
  const parada = { indice: 1, pw: 0, janela_s: 30 };
  assert.deepEqual(situacaoDoDegrau(null, 3, 60), { situacao: 'completo', duracaoEfetivaS: 60 });
  assert.deepEqual(situacaoDoDegrau(parada, 0, 60), { situacao: 'completo', duracaoEfetivaS: 60 });
  assert.deepEqual(situacaoDoDegrau(parada, 1, 60), { situacao: 'parcial', duracaoEfetivaS: 30 });
  assert.deepEqual(situacaoDoDegrau({ ...parada, pw: 1 }, 1, 60), { situacao: 'parcial', duracaoEfetivaS: 60 });
  assert.deepEqual(situacaoDoDegrau(parada, 2, 60), { situacao: 'omitido', duracaoEfetivaS: 0 });
});
confere('situacaoDoDegrau: parcial = min(duracao, (pw+1) x janela_s, ultima janela com amostras); amostras da janela seguinte NAO estendem', () => {
  const parada = { indice: 1, pw: 0, janela_s: 30 };
  // caso normal: o abort cai ~2 s depois do fim da janela 0 e as reservas desses ~2 s caem na janela 1 -> continua 30 s (nao 60)
  assert.equal(situacaoDoDegrau(parada, 1, 60, [{ w: 0, n: 5000 }, { w: 1, n: 3000 }]).duracaoEfetivaS, 30);
  assert.equal(situacaoDoDegrau({ ...parada, pw: 1 }, 1, 90, [{ w: 0, n: 50 }, { w: 1, n: 50 }, { w: 2, n: 2 }]).duracaoEfetivaS, 60);
  // abort ADIANTADO: parada julgada na janela pw=1, mas so ha amostras ate a janela 0 -> os dados encurtam
  assert.equal(situacaoDoDegrau({ ...parada, pw: 1 }, 1, 90, [{ w: 0, n: 5 }]).duracaoEfetivaS, 30);
  assert.equal(situacaoDoDegrau({ ...parada, pw: 2 }, 1, 90, [{ w: 0, n: 50 }, { w: 1, n: 0 }, { w: 2, n: 0 }]).duracaoEfetivaS, 30);   // janela sem amostras nao conta
  assert.equal(situacaoDoDegrau({ ...parada, pw: 1 }, 1, 60, []).duracaoEfetivaS, 60);                                  // sem janelas: so o teto
  assert.equal(situacaoDoDegrau({ ...parada, pw: 3, janela_s: 60 }, 1, 120, [{ w: 3, n: 9 }]).duracaoEfetivaS, 120);   // teto na duracao do degrau
});
confere('limiaresParada: delayAbortEval soma o orcamento do setup (o relogio do k6 conta o setup(); medido no dry-run)', () => {
  const p2 = [{ nome: 'degrau_1000', inicioS: 0 }, { nome: 'degrau_2000', inicioS: 91 }];
  assert.equal(limiaresParada(p2, 30, 60, 5000)['reserva_duration{parada:degrau_2000_1}'][0].delayAbortEval, '151s');          // setupS padrao 0
  assert.equal(limiaresParada(p2, 30, 60, 5000, 45)['reserva_duration{parada:degrau_2000_1}'][0].delayAbortEval, '196s');       // 45 + 91 + 2 x 30
  assert.equal(limiaresParada(p2, 30, 60, 5000, 45)['reserva_duration{parada:degrau_1000_0}'][0].delayAbortEval, '75s');        // 45 + 0 + 30
});
confere('esperaDoSetup: sem SETUP_S nada; espera o resto ate o orcamento; estourou o orcamento com parada ligada = erro (sem parada so segue)', () => {
  assert.deepEqual(esperaDoSetup(99000, 0, true), { esperaMs: 0 });
  assert.deepEqual(esperaDoSetup(20000, 60, true), { esperaMs: 40000 });
  assert.deepEqual(esperaDoSetup(60000, 60, true), { esperaMs: 0 });
  assert.deepEqual(esperaDoSetup(70000, 60, false), { esperaMs: 0 });
  const e = esperaDoSetup(70000, 60, true);
  assert.match(e.erro, /70 s, mais que SETUP_S=60 s.*Suba SETUP_S \(ex\.: 105\)/);
});
confere('erroParametros: SETUP_S negativo recusado', () => {
  const ok = { pctAcompanha: 10, consultaMs: 1000, tetoConsultaS: 35, paradaP95Ms: 0, paradaJanelaS: 60, duracaoS: 60 };
  assert.equal(erroParametros(ok), null);
  assert.match(erroParametros({ ...ok, setupS: -1 }), /SETUP_S invalido/);
});
confere('textoParada: sem virgula (e CSV); nenhuma sem parada', () => {
  assert.equal(textoParada(null), 'nenhuma');
  const t = textoParada({ degrau: 'degrau_2000', limite_ms: 5000, janela_s: 30 });
  assert.equal(t, 'p95_acima_de_5000ms_em_janela_de_30s_no_degrau_2000');
  assert.ok(!t.includes(','));
});

console.log(falhas === 0 ? '\nselftest lib-admissao: OK' : `\nselftest lib-admissao: ${falhas} falha(s)`);
process.exit(falhas === 0 ? 0 : 1);
