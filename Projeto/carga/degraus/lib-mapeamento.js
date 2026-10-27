// Mapeamento iteracao -> par (usuario, evento) da escada de carga (issue #113). Modulo puro (sem k6, sem open()):
// o carga-degraus.js o importa e o lib-mapeamento.selftest.mjs o testa com node.
//
// i = indice GLOBAL da iteracao (base do degrau + iterationInTest); U = usuarios; E = eventos.
//   sequencial  (padrao): usuario = i % U,        evento = floor(i / U)  -> U reservas seguidas no MESMO evento
//                         (evento disputado: mede a linha quente do eventcap)
//   intercalado:          usuario = floor(i / E), evento = i % E         -> E reservas seguidas em eventos DIFERENTES
//                         (capacidade do host sem a linha quente de um evento)
// Nos dois modos o par e unico para i < U x E; o setup() do carga-degraus.js ja aborta se U x E < soma das cotas.

export const MAPEAMENTOS = ['sequencial', 'intercalado'];

// Mensagem de erro para um valor de MAPEAMENTO invalido; null se for valido.
export function erroMapeamento(modo) {
  if (MAPEAMENTOS.includes(modo)) return null;
  return `MAPEAMENTO invalido: ${JSON.stringify(modo)}. Valores aceitos (minusculas): ${MAPEAMENTOS.join(', ')}.`;
}

// Indices (u, e) do par da iteracao i. Lanca se o modo for invalido (nunca cai num modo por omissao).
export function parDe(i, usuarios, eventos, modo) {
  const erro = erroMapeamento(modo);
  if (erro) throw new Error(erro);
  return modo === 'intercalado'
    ? { u: Math.floor(i / eventos), e: i % eventos }
    : { u: i % usuarios, e: Math.floor(i / usuarios) };
}
