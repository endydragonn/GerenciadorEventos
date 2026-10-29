// Logica pura da escada ACIMA do X com o admission ligado (issue #178), sem k6 e sem open(): o carga-degraus.js a importa e o
// lib-admissao.selftest.mjs a testa com node (mesmo padrao do lib-mapeamento.js).
//
// Contrato do modo fila (#43): POST /bff/event-wallets -> 201 (sincrono) ou 202 (corpo EstadoDaReserva "queued", Idempotency-Key,
// Retry-After: 1); GET /bff/event-wallets/admission?idempotencyKey= -> 200 com status queued | pending | rejected | timed_out
// (404 = chave desconhecida do usuario). 429 (limite por usuario ou fila cheia) e 422 (chave de outro evento) vem do admission.

export const ESTADOS_FINAIS = ['pending', 'rejected', 'timed_out'];

// Resposta do POST /bff/event-wallets -> classe. 202 e "aceita" (NUNCA outros); 429/422 tem contador proprio (nao sao 5xx nem "outros":
// acima do X o limite por usuario e a fila cheia sao respostas esperadas do admission).
export function classificarReserva(status, corpo) {
  if (status === 201) return 'aceita';
  if (status === 202) return 'enfileirada';
  if (status === 400 && String(corpo).toLowerCase().indexOf('lotado') >= 0) return 'lotado';
  if (status === 429) return 'recusada_429';
  if (status === 422) return 'conflito_422';
  if (status >= 500) return 'erro_5xx';
  return 'outros';
}

// Resposta do GET de estado -> { estado, final }. Estado 'erro' = nao deu para ler (404, 5xx, corpo que nao e JSON, status desconhecido):
// nao e final, o acompanhamento tenta de novo ate o teto.
export function classificarEstado(status, corpo) {
  if (status !== 200) return { estado: 'erro', final: false };
  let s = null;
  try { s = JSON.parse(String(corpo)).status; } catch (e) { return { estado: 'erro', final: false }; }
  if (s === 'queued') return { estado: 'queued', final: false };
  if (ESTADOS_FINAIS.includes(s)) return { estado: s, final: true };
  return { estado: 'erro', final: false };
}

// Acompanha uma reserva enfileirada: consulta o estado a cada consultaMs ate virar pending / rejected / timed_out, ou ate tetoMs
// (resultado 'sem_estado'). Funcoes injetadas (consultar() -> {status, body}, dormir(ms), agora() -> ms) para o teste nao depender do k6.
// Devolve { estado, consultas, esperaMs }: esperaMs = do primeiro dormir ate a resposta final (resolucao = consultaMs).
export function acompanhar({ consultar, dormir, agora, consultaMs, tetoMs }) {
  const inicio = agora();
  let consultas = 0;
  for (;;) {
    if (agora() - inicio >= tetoMs) return { estado: 'sem_estado', consultas, esperaMs: agora() - inicio };
    dormir(consultaMs);
    const r = consultar();
    consultas++;
    const e = classificarEstado(r.status, r.body);
    if (e.final) return { estado: e.estado, consultas, esperaMs: agora() - inicio };
  }
}

// gracefulStop dos degraus: a iteracao acompanhada vive ate tetoConsultaS (+ a confirmacao); sem acompanhamento (pct 0) fica o 30 s de antes.
// ESPACO_S = graceful + 1 (as iteracoes em voo de um degrau terminam antes do proximo comecar). Mesma conta em lib-degraus.ps1 (Get-GracefulS).
export function gracefulS(pctAcompanha, tetoConsultaS) {
  return pctAcompanha > 0 ? Math.max(30, tetoConsultaS + 10) : 30;
}

// Valida os parametros do acompanhamento e da parada. Devolve a mensagem do 1o problema, ou null.
export function erroParametros({ pctAcompanha, consultaMs, tetoConsultaS, paradaP95Ms, paradaJanelaS, duracaoS, setupS = 0 }) {
  if (!(pctAcompanha >= 0 && pctAcompanha <= 100)) return `PCT_ACOMPANHA invalido: ${pctAcompanha} (0 a 100).`;
  if (!(consultaMs >= 50)) return `CONSULTA_MS invalido: ${consultaMs} (>= 50).`;
  if (!(tetoConsultaS >= 1)) return `TETO_CONSULTA_S invalido: ${tetoConsultaS} (>= 1).`;
  if (!(paradaP95Ms >= 0)) return `PARADA_P95_MS invalido: ${paradaP95Ms} (0 = sem parada).`;
  if (!(setupS >= 0)) return `SETUP_S invalido: ${setupS} (>= 0).`;
  if (paradaP95Ms > 0) {
    if (!(paradaJanelaS >= 30 && paradaJanelaS % 30 === 0 && duracaoS % paradaJanelaS === 0)) {
      return `PARADA_JANELA_S invalido: ${paradaJanelaS} (multiplo de 30 e divisor de DURACAO_S=${duracaoS}, para a parada cair no fim de uma janela).`;
    }
  }
  return null;
}

// Janela de parada do instante de ENVIO (dec = envio - inicio do degrau, ms): indice da janela de janelaS segundos, ou -1 se fora do degrau
// (mais que tolMs de jitter). Ate tolMs nas pontas vai para a janela da ponta.
export function indiceJanela(decMs, janelaS, duracaoS, tolMs) {
  if (!(decMs >= -tolMs && decMs < duracaoS * 1000 + tolMs)) return -1;
  const n = Math.floor(duracaoS / janelaS);
  return Math.min(n - 1, Math.max(0, Math.floor(decMs / (janelaS * 1000))));
}

export const nomeMetricaParada = (degrau, pw) => `reserva_duration{parada:${degrau}_${pw}}`;

// Thresholds de parada (um por janela de cada degrau): o p95 da reserva DENTRO da janela (a tag `parada` e unica por janela, entao o
// threshold, cumulativo, vale so para as amostras da janela) acima de limiteMs derruba o teste (abortOnFail). delayAbortEval conta do
// INICIO DO TESTE E INCLUI O setup() (medido no dry-run da #178, k6 2.2.0: com setup de 45 s o abort caiu 2 s depois do inicio do degrau,
// nao 30 s): cada um e adiado para o fim da propria janela NO RELOGIO DO TESTE = setupS + inicioS do degrau + (pw+1) * janelaS, senao a
// janela seria julgada com poucas amostras. setupS e o ORCAMENTO do setup (SETUP_S): o setup() espera o relogio chegar nele (esperaDoSetup),
// entao os degraus comecam exatamente em setupS. O k6 avalia thresholds a cada ~2 s, entao a parada cai ate ~2 s depois do fim da janela.
export function limiaresParada(plano, janelaS, duracaoS, limiteMs, setupS = 0) {
  const th = {};
  const n = Math.floor(duracaoS / janelaS);
  for (const p of plano) {
    for (let pw = 0; pw < n; pw++) {
      th[nomeMetricaParada(p.nome, pw)] = [{ threshold: `p(95)<${limiteMs}`, abortOnFail: true, delayAbortEval: `${setupS + p.inicioS + (pw + 1) * janelaS}s` }];
    }
  }
  return th;
}

// O que o fim do setup() faz com o relogio do teste (`runMs` = exec.instance.currentTestRunDuration): sem SETUP_S (0) nada; com parada ligada e
// o setup JA passou do orcamento, a parada seria julgada adiantada (janela incompleta): erro (antes de qualquer carga); senao espera o resto.
export function esperaDoSetup(runMs, setupS, paradaLigada) {
  if (!(setupS > 0)) return { esperaMs: 0 };
  if (runMs > setupS * 1000) {
    return paradaLigada ? { erro: `o setup() levou ${Math.ceil(runMs / 1000)} s, mais que SETUP_S=${setupS} s: a parada por latencia seria julgada adiantada (janela incompleta). Suba SETUP_S (ex.: ${Math.ceil(runMs / 1000 * 1.5)}).` } : { esperaMs: 0 };
  }
  return { esperaMs: setupS * 1000 - runMs };
}

// Primeira janela (na ordem dos degraus) cujo threshold de parada reprovou. `metrica(nome)` devolve o objeto da metrica no resumo do k6
// ({values, thresholds}) ou undefined. thresholds = { "<expr>": { ok } } (k6 >= 0.4x): reprovou = algum ok === false. Sem o campo
// `thresholds` (k6 antigo) cai no p95 final > limite. Devolve { degrau, indice, pw, p95_ms, limite_ms, janela_s } ou null.
export function avaliarParada(plano, janelaS, duracaoS, limiteMs, metrica) {
  if (!(limiteMs > 0)) return null;
  const n = Math.floor(duracaoS / janelaS);
  for (let k = 0; k < plano.length; k++) {
    for (let pw = 0; pw < n; pw++) {
      const m = metrica(nomeMetricaParada(plano[k].nome, pw));
      if (!m) continue;
      const p95 = ((m.values || {})['p(95)']) || 0;
      const th = m.thresholds;
      const reprovou = th ? Object.values(th).some((t) => t && t.ok === false) : p95 > limiteMs;
      if (reprovou) return { degrau: plano[k].nome, indice: k, pw, p95_ms: p95, limite_ms: limiteMs, janela_s: janelaS };
    }
  }
  return null;
}

// Situacao de cada degrau depois de uma parada: 'completo' (antes do degrau da parada, ou sem parada), 'parcial' (o degrau da parada:
// so o que rodou) e 'omitido' (depois: nunca comecou, nao vira linha de CSV). Sem parada: tudo 'completo'.
// Duracao efetiva do parcial = min(duracao do degrau, (pw + 1) x janela_s, (indice da ULTIMA janela de 30 s com amostras + 1) x 30 s): o teto
// (pw + 1) x janela_s e o instante em que a parada deveria cair; os dados (`janelas` = [{w, n}]) so encurtam, se o abort veio ADIANTADO (nao
// ha trafego depois dele). As amostras das ~2 s entre o fim da janela e o abort (avaliacao do k6 a cada ~2 s) caem na janela seguinte e,
// por isso, NAO estendem a duracao. Sem janelas, so o teto.
export function situacaoDoDegrau(parada, indice, duracaoS, janelas = []) {
  if (!parada || indice < parada.indice) return { situacao: 'completo', duracaoEfetivaS: duracaoS };
  if (indice > parada.indice) return { situacao: 'omitido', duracaoEfetivaS: 0 };
  const ultima = janelas.reduce((m, j) => (j.n > 0 && j.w > m ? j.w : m), -1);
  const efetiva = Math.min(duracaoS, (parada.pw + 1) * parada.janela_s, ultima >= 0 ? (ultima + 1) * 30 : Infinity);
  return { situacao: 'parcial', duracaoEfetivaS: efetiva };
}

// Texto da coluna `parada` do CSV ('nenhuma' sem parada). Sem virgula (e CSV).
export function textoParada(parada) {
  if (!parada) return 'nenhuma';
  return `p95_acima_de_${parada.limite_ms}ms_em_janela_de_${parada.janela_s}s_no_${parada.degrau}`;
}
