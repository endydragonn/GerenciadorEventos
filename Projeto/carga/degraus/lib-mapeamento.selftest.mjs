// Auto-teste de lib-mapeamento.js (issue #113): mapeamento iteracao -> (usuario, evento) da escada. Sem k6, sem rede,
// sem banco: so node:assert. Uso: node degraus/lib-mapeamento.selftest.mjs   (sai com codigo != 0 se algo falhar)
import assert from 'node:assert/strict';
import { MAPEAMENTOS, erroMapeamento, parDe } from './lib-mapeamento.js';

let falhas = 0;
function confere(nome, fn) {
  try { fn(); console.log(`   [ok]    ${nome}`); }
  catch (e) { falhas++; console.log(`   [FALHA] ${nome}\n           ${String(e.message).split('\n').join('\n           ')}`); }
}

// Plano no formato do Get-PlanoEscada (lib-degraus.ps1): cota = ceil(rpm * D / 60) + 1, base = soma das cotas
// anteriores, E = ceil(total * 1.05 / U). Os indices globais de um degrau sao base + j, j em [0, cota).
function plano(rpms, duracaoS, usuarios) {
  let base = 0;
  const degraus = rpms.map((rpm) => {
    const cota = Math.ceil((rpm * duracaoS) / 60) + 1;
    const d = { rpm, base, cota };
    base += cota;
    return d;
  });
  return { degraus, total: base, usuarios, eventos: Math.ceil((base * 1.05) / usuarios) };
}

// percorre TODOS os indices globais de todos os degraus (base + j) e devolve os pares
function* indices(pl) {
  for (const d of pl.degraus) for (let j = 0; j < d.cota; j++) yield d.base + j;
}

const casos = [
  ['escada 12k/16k/20k, 60 s, U=200', plano([12000, 16000, 20000], 60, 200)],
  ['caso pequeno U != E, total que nao e multiplo de E', { degraus: [{ base: 0, cota: 7 }, { base: 7, cota: 6 }], total: 13, usuarios: 4, eventos: 5 }],
];

console.log('\n==> Modos validos e mensagem de erro');
confere('modos validos: sequencial e intercalado', () => assert.deepEqual([...MAPEAMENTOS], ['sequencial', 'intercalado']));
for (const ok of MAPEAMENTOS) confere(`'${ok}' e aceito`, () => assert.equal(erroMapeamento(ok), null));
// o validador puro recusa '' e undefined; o carga-degraus.js trata MAPEAMENTO vazio/ausente como o padrao (sequencial) ANTES de validar
for (const ruim of ['Intercalado', 'aleatorio', 'sequencial ', ' sequencial']) {
  confere(`${JSON.stringify(ruim)} e rejeitado com mensagem clara`, () => {
    const msg = erroMapeamento(ruim);
    assert.equal(typeof msg, 'string');
    assert.match(msg, /MAPEAMENTO/);
    assert.match(msg, /sequencial/);
    assert.match(msg, /intercalado/);
  });
}
confere('validador puro: undefined e "" tambem sao rejeitados (o padrao e aplicado pelo carga-degraus.js, nao aqui)', () => { assert.ok(erroMapeamento(undefined)); assert.ok(erroMapeamento('')); });
confere('parDe com modo invalido lanca (nunca cai num modo por omissao)', () => assert.throws(() => parDe(0, 4, 5, 'aleatorio'), /MAPEAMENTO/));

for (const [nome, pl] of casos) {
  const { usuarios: U, eventos: E, total } = pl;
  console.log(`\n==> ${nome}: U=${U} E=${E} total=${total} (U x E=${U * E})`);
  confere('a conta usuarios x eventos >= total vale', () => assert.ok(U * E >= total));

  confere("sequencial: par exatamente (i % U, floor(i / U)) -- o comportamento de antes, byte a byte", () => {
    for (const i of indices(pl)) assert.deepEqual(parDe(i, U, E, 'sequencial'), { u: i % U, e: Math.floor(i / U) }, `i=${i}`);
  });
  confere('intercalado: par exatamente (floor(i / E), i % E)', () => {
    for (const i of indices(pl)) assert.deepEqual(parDe(i, U, E, 'intercalado'), { u: Math.floor(i / E), e: i % E }, `i=${i}`);
  });

  for (const modo of MAPEAMENTOS) {
    confere(`${modo}: indices dentro de [0,U) x [0,E) e cada par (usuario, evento) unico na escada`, () => {
      const vistos = new Set();
      let n = 0;
      for (const i of indices(pl)) {
        const { u, e } = parDe(i, U, E, modo);
        assert.ok(Number.isInteger(u) && u >= 0 && u < U, `usuario fora de [0,${U}): ${u} (i=${i})`);
        assert.ok(Number.isInteger(e) && e >= 0 && e < E, `evento fora de [0,${E}): ${e} (i=${i})`);
        const k = `${u}:${e}`;
        assert.ok(!vistos.has(k), `par repetido ${k} (i=${i})`);
        vistos.add(k);
        n++;
      }
      assert.equal(n, total);
      assert.equal(vistos.size, total);
    });
  }
}

console.log('\n==> Os modos diferem onde devem: U reservas seguidas');
confere('sequencial: as U primeiras iteracoes caem todas no MESMO evento (linha quente)', () => {
  const U = 200, E = 253;
  assert.equal(new Set(Array.from({ length: U }, (_, i) => parDe(i, U, E, 'sequencial').e)).size, 1);
});
confere('intercalado: E iteracoes seguidas caem em E eventos DIFERENTES (e no mesmo usuario)', () => {
  const U = 200, E = 253;
  const pares = Array.from({ length: E }, (_, i) => parDe(i, U, E, 'intercalado'));
  assert.equal(new Set(pares.map((p) => p.e)).size, E);
  assert.equal(new Set(pares.map((p) => p.u)).size, 1);
});

console.log('\n==================================================');
console.log(falhas === 0 ? '   RESULTADO: OK -- lib-mapeamento.js passou em todas as assercoes.' : `   RESULTADO: ${falhas} ASSERCAO(OES) FALHARAM.`);
console.log('==================================================');
process.exit(falhas === 0 ? 0 : 1);
