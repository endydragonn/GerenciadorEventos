// Sorteio deterministico em [0, 100) a partir de (indice, semente) (modulo puro: sem k6 e sem open()). Decide quais reservas confirmar/acompanhar/cancelar sem consumir
// Math.random (o perfil de taxa e a ordem dos outros sorteios nao mudam). Era uma funcao interna do aleatorio/carga-aleatoria.js e do degraus/carga-degraus.js (copias
// identicas); agora e uma so, importada por eles e pelo soak/carga-soak.js. Testada por lib-sorteio.selftest.mjs (inclui a equivalencia com a formula de antes).
export function sorteio100(i, semente) {
  let h = Math.imul(i ^ Math.imul(semente, 0x9e3779b1), 0x85ebca6b);
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35);
  h ^= h >>> 16;
  return (h >>> 0) % 100;
}
