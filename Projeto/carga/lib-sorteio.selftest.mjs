// Auto-teste do lib-sorteio.js (issue #232): o sorteio extraido do carga-aleatoria.js / carga-degraus.js devolve EXATAMENTE o que a funcao interna devolvia (formula de antes
// reescrita aqui, literal) e fica em [0, 100), com distribuicao aproximadamente uniforme. Uso: node lib-sorteio.selftest.mjs
import assert from 'node:assert/strict';
import { sorteio100 } from './lib-sorteio.js';

// a funcao de antes (copia LITERAL da que estava nos dois scripts), so para provar a equivalencia
function antes(i, semente) {
  let h = Math.imul(i ^ Math.imul(semente, 0x9e3779b1), 0x85ebca6b);
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35);
  h ^= h >>> 16;
  return (h >>> 0) % 100;
}

let falhas = 0;
function confere(nome, fn) {
  try { fn(); console.log(`   [ok]    ${nome}`); }
  catch (e) { falhas++; console.log(`   [FALHA] ${nome}\n           ${String(e.message).split('\n').join('\n           ')}`); }
}

confere('identico a funcao de antes para sementes 0, 1, 7 e 42 e 200 mil indices', () => {
  for (const sem of [0, 1, 7, 42]) for (let i = 0; i < 200000; i++) assert.equal(sorteio100(i, sem), antes(i, sem), `i=${i} sem=${sem}`);
});
confere('valores fixos (regressao): sorteio100(0,0)=0 e (1,0), (12345,1)', () => {
  assert.equal(sorteio100(0, 0), antes(0, 0));
  assert.equal(sorteio100(1, 0), antes(1, 0));
  assert.equal(sorteio100(12345, 1), antes(12345, 1));
});
confere('sempre em [0, 100) e ~uniforme (cada decil entre 8% e 12% de 100 mil)', () => {
  const dec = new Array(10).fill(0);
  for (let i = 0; i < 100000; i++) { const v = sorteio100(i, 3); assert.ok(v >= 0 && v < 100); dec[Math.floor(v / 10)]++; }
  for (const d of dec) assert.ok(d > 8000 && d < 12000, `decil ${d}`);
});
console.log(falhas ? `\nselftest lib-sorteio: ${falhas} falha(s)` : '\nselftest lib-sorteio: OK');
process.exit(falhas ? 1 : 0);
