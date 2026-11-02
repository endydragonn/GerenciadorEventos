// Logica pura do soak (issue #232), sem k6 e sem open(): o carga-soak.js a importa e o lib-soak.selftest.mjs a testa com node (mesmo padrao do lib-admissao.js).

import { sorteio100 } from '../lib-sorteio.js';

export const TIPOS = ['leitura', 'reserva', 'login', 'cadastro', 'escrita'];

// Fatia dos VUs (maxVUs do k6) de cada cenario. Os fluxos que ESPERAM (reserva acompanhada na fila, cadastro esperando o e-mail) seguram VU por segundos.
export const FATIA_VUS = { leitura: 0.30, reserva: 0.40, login: 0.10, cadastro: 0.15, escrita: 0.05 };

// Fase (pico | vale) do tipo no instante `segundos` do cenario, pelas fases do perfil (perfil.py). Fora do intervalo cai na ultima fase.
export function faseEm(fases, segundos) {
  for (const f of fases) if (segundos >= f.inicio_s && segundos < f.fim_s) return f.pico ? 'pico' : 'vale';
  return fases[fases.length - 1].pico ? 'pico' : 'vale';
}

// Minuto do bloco (0 = o primeiro) a partir do tempo decorrido do cenario (ms). E a tag `min` que o agregar.py usa.
export const minutoEm = (decorridoMs) => Math.max(0, Math.floor(decorridoMs / 60000));

// Enderecos de cadastro: SO @example.com (nenhum e-mail real), unicos por bloco (SUFIXO do seed) e por iteracao (n).
export const enderecoDoCadastro = (sufixo, n) => `soak-${sufixo}-${n}@example.com`;
// fone UNIQUE no banco e os blocos rodam sobre o MESMO banco (o limpar-banco pode nao rodar entre eles): o telefone depende do SUFIXO e do n, nunca so do n.
// Formato: "12" + 6 ultimos digitos do sufixo (zeros a esquerda) + n com 7 digitos = 15 digitos. O prefixo "12" nunca colide com o seed (setup.sh e seed-data.sql: tudo "11...",
// inclusive os quentes). Nenhum codigo valida o formato do fone; o limite real e varchar(20) de pending_verifications (users.fone e text), por isso 15 digitos e nao os 11 de um
// celular: 11 nao deixaria espaco para sufixo + n ate 999999 (o pior perfil faz ~120 mil cadastros). Sufixo sem digitos ("T1" no selftest) vale como zeros.
export const foneDoCadastro = (sufixo, n) => `12${String(sufixo).replace(/\D/g, '').slice(-6).padStart(6, '0')}${String(n).padStart(7, '0')}`;

// Codigo de 6 digitos do e-mail de verificacao ("Código de Verificação: 123456", EmailTemplates do notification); null se nao achar.
export function extrairCodigo(texto) {
  const m = /Verifica\S*:\s*(\d{6})/i.exec(String(texto || ''));
  return m ? m[1] : null;
}

// Destino da reserva ACEITA (201, ou 202 que chegou a pending): 60% confirma, 10% cancela, 30% expira sozinha (a expiracao devolve a vaga).
// `sorteio` = sorteio100(i, semente) em [0, 100).
export function destinoDaReserva(sorteio, pctConfirma = 60, pctCancela = 10) {
  if (sorteio < pctConfirma) return 'confirma';
  if (sorteio < pctConfirma + pctCancela) return 'cancela';
  return 'expira';
}

// Usuario do host do seed (setup.sh): e-mail carga-host-<sufixo>@example.com, senha CargaTeste#<sufixo>. O preparar-soak o promove a host por SQL (nenhuma rota faz isso).
export const hostDoSufixo = (sufixo) => ({ email: `carga-host-${sufixo}@example.com`, password: `CargaTeste#${sufixo}` });

// Alvo da iteracao `i` da RESERVA (issue #232, decisao C): com eventos QUENTES, `pctQuente` % das reservas mira UM evento quente (k pequeno, de preferencia 1: e o RPM do evento mais
// quente que liga o gatilho do admission e o KEDA do reservation) e o resto segue o mapeamento par-unico de sempre (`quente: false`). Nas quentes o par (usuario, evento) se repete,
// entao o fluxo cancela a reserva logo depois de aceita (o par volta a ficar livre). `insistentes` = os primeiros usuarios do seed, que recebem `pctInsistente` % das quentes
// para bater no limite de mensagens em espera por usuario do admission (429); os demais usuarios giram em ordem. Deterministico em `i`.
// -> { quente: true, u, e } (e = indice em `quentes`) | { quente: false }
export function alvoDaReserva(i, { quentes, pctQuente, insistentes, pctInsistente, usuarios }) {
  if (!quentes || !quentes.length || sorteio100(i, 11) >= pctQuente) return { quente: false };
  const ins = Math.min(insistentes, usuarios - 1);
  const u = ins > 0 && sorteio100(i, 13) < pctInsistente ? i % ins : ins + (i % (usuarios - ins));
  return { quente: true, u, e: i % quentes.length };
}

// Mensagem do 1o problema nos parametros, ou null.
export function erroParametros(p) {
  if (!p.perfil || !p.perfil.tipos) return 'PERFIL_FILE invalido: sem "tipos" (gere com soak/perfil.py).';
  for (const t of TIPOS) {
    if (!p.perfil.tipos[t] || !Array.isArray(p.perfil.tipos[t].stages) || !p.perfil.tipos[t].stages.length || !Array.isArray(p.perfil.tipos[t].fases)) {
      return `PERFIL_FILE sem stages/fases do tipo ${t}.`;
    }
  }
  if (!(p.maxVus >= 20)) return `MAX_VUS invalido: ${p.maxVus} (>= 20).`;
  if (!p.mailpitUrl || !/^https?:\/\//.test(p.mailpitUrl)) return 'MAILPIT_URL ausente ou invalida (http://<host>:<porta>).';
  if (!/^[A-Za-z0-9._-]+:[A-Za-z0-9._~+-]{16,}$/.test(p.mailpitAuth || '')) return 'MAILPIT_AUTH ausente ou fora do formato (variavel de ambiente <usuario>:<senha de >= 16 caracteres A-Za-z0-9._~+-; nunca por -e).';
  if (!/^[A-Za-z0-9._-]+$/.test(p.sufixo || '')) return `SUFIXO invalido: '${p.sufixo}'.`;
  if (!/^[A-Za-z0-9-]+$/.test(p.prefixo || '')) return `PREFIXO invalido: '${p.prefixo}'.`;
  return null;
}

// scenarios do k6: 5 ramping-arrival-rate em paralelo (timeUnit 1m: o alvo dos stages e RPM de iteracoes), cada um com a tag de cenario `tipo`.
export function cenarios(perfil, { maxVus, gracefulStopS }) {
  const sc = {};
  for (const t of TIPOS) {
    const max = Math.max(5, Math.round(maxVus * FATIA_VUS[t]));
    sc[t] = {
      executor: 'ramping-arrival-rate',
      exec: t,
      startRate: 0,
      timeUnit: '1m',
      stages: perfil.tipos[t].stages,
      preAllocatedVUs: Math.max(2, Math.ceil(max / 2)),
      maxVUs: max,
      gracefulStop: `${gracefulStopS}s`,
      tags: { tipo: t },
    };
  }
  return sc;
}

// Termo da busca de leitura que acerta O(1) evento. `GET /bff/events/search?term=` e um LIKE '%termo%' no nome: o prefixo do bloco acertaria os milhares de eventos do seed
// (respostas de centenas de KB a 20% da leitura). O nome do seed e <PREFIXO>-E<k>, k = 1..N: com k > N/10 nenhum outro nome comeca com o mesmo texto (E201 nao e prefixo de nada
// quando N = 2000), entao o termo acerta exatamente 1 evento. `sorteio` em [0, 1).
export function termoDeBusca(prefixo, nEventos, sorteio) {
  const piso = Math.floor(nEventos / 10);
  const k = piso + 1 + Math.floor(sorteio * (nEventos - piso));
  return `${prefixo}-E${Math.min(k, nEventos)}`;
}
