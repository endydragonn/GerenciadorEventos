// =============================================================================
//  scenario.js · harness de carga (issue #11)
// =============================================================================
//
//  N clientes concorrentes disputando uma capacity finita, disparados ao mesmo
//  tempo (per-vu-iterations: cada VU roda exatamente 1 iteração, todas
//  iniciando juntas). Cada VU representa 1 attendee autenticado (JWT já obtido
//  por setup.sh) fazendo o fluxo completo de reserva em duas fases:
//
//    1. POST /bff/event-wallets          (addLink)  -> 201 "pending" ou
//                                                       400 "Evento lotado"
//    2. se (1) deu 201: POST /bff/event-wallets/confirm -> 200 "confirmed"
//
//  "Corretude" no critério de aceite da issue (`confirmed` vs `capacity`) é
//  medida como count(*) em walletevent com status='confirmed' comparado à
//  capacity -- lido direto do banco por verify.sh, não pela resposta HTTP daqui
//  (a fonte de verdade é o banco, não o que o cliente de carga observou).
//  Isso é uma escolha de interpretação, documentada no README.
//
//  Entrada (variáveis de ambiente, todas obrigatórias -- ver run.sh/README):
//    BASE_URL     URL do backend visto de DENTRO do container do k6
//                 (ex.: http://backend:8081 na rede gerenciador-net)
//    EVENT_ID     id do evento-alvo (criado por setup.sh)
//    TOKENS_FILE  caminho (dentro do container) do JSON de tokens gerado por
//                 setup.sh: [{"userId":N,"token":"..."}, ...] -- precisa ter
//                 pelo menos VUS entradas (setup.sh gera N = VUS).
//    VUS          nº de VUs (== nº de attendees em TOKENS_FILE)
//
//  Métricas customizadas (Counter, além das nativas do k6 -- http_req_duration
//  cobre p50/p90/p95/p99 de latência, http_reqs cobre throughput):
//    reservas_confirmadas  addLink 201 + confirm 200
//    reservas_lotadas      addLink 400 "Evento lotado"
//    reservas_erro         qualquer resposta inesperada (rede, 401, 500, ...)
//
//  Saída: --summary-export (ver run.sh) com os stats configurados abaixo
//  (p(50)/p(95)/p(99) além dos defaults) -- report.sh lê esse JSON.
// =============================================================================

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://backend:8081';
const EVENT_ID = __ENV.EVENT_ID;
const TOKENS_FILE = __ENV.TOKENS_FILE || '/scripts/tokens.json';
const VUS = parseInt(__ENV.VUS || '10', 10);

if (!EVENT_ID) {
  throw new Error('EVENT_ID não informado (env var obrigatória)');
}

// SharedArray: o JSON é lido e parseado 1x só, e compartilhado entre VUs sem
// duplicar memória por VU (recomendado pela doc do k6 para dados de setup).
const tokens = new SharedArray('tokens dos attendees', function () {
  return JSON.parse(open(TOKENS_FILE));
});

export const options = {
  scenarios: {
    disputa_capacity: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  // p(50)/p(95)/p(99) explícitos (o Aceite pede p50/p95/p99; o default do k6
  // é avg/min/med/max/p(90)/p(95)) -- ver README/report.sh.
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)', 'count'],
};

const reservasConfirmadas = new Counter('reservas_confirmadas');
const reservasLotadas = new Counter('reservas_lotadas');
const reservasErro = new Counter('reservas_erro');

export default function () {
  // __VU é 1-based; cada VU usa seu próprio token (1 attendee por VU, como o
  // setup.sh gera N = VUS attendees).
  const attendee = tokens[(__VU - 1) % tokens.length];
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${attendee.token}`,
  };
  const body = JSON.stringify({ userId: attendee.userId, eventId: Number(EVENT_ID) });

  const addLinkRes = http.post(`${BASE_URL}/bff/event-wallets`, body, { headers, tags: { name: 'addLink' } });

  if (addLinkRes.status === 201) {
    const confirmRes = http.post(`${BASE_URL}/bff/event-wallets/confirm`, body, {
      headers,
      tags: { name: 'confirm' },
    });
    const confirmOk = check(confirmRes, {
      'confirm: HTTP 200': (r) => r.status === 200,
    });
    if (confirmOk) {
      reservasConfirmadas.add(1);
    } else {
      reservasErro.add(1);
    }
  } else if (addLinkRes.status === 400 && addLinkRes.body && addLinkRes.body.includes('Evento lotado')) {
    check(addLinkRes, { "addLink: 400 'Evento lotado'": () => true });
    reservasLotadas.add(1);
  } else {
    check(addLinkRes, {
      'addLink: resposta inesperada': () => false,
    });
    reservasErro.add(1);
  }
}
