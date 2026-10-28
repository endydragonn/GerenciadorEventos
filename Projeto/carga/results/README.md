# Resultados de carga

Uma pasta por etapa da arquitetura. O `README.md` de cada uma diz o que estava implementado.

| Etapa | O que estava implementado | Período |
|---|---|---|
| [`01-sem-pool/`](01-sem-pool/) | Monólito sem pool: uma conexão nova por operação (`DriverManager`), PG 16 direto. Números invalidados (#96) | 2026-10-04, manhã |
| [`02-hikari/`](02-hikari/) | Monólito + HikariCP (pool de 1000), PG 16 direto, sem pooler | 2026-10-04 (tarde) a 2026-10-16 |
| [`03-supabase-pooler/`](03-supabase-pooler/) | `supabase/postgres` 17.6 + Supavisor em transaction mode | a partir de 2026-10-16 |
| [`04-fase6-es/`](04-fase6-es/) | Monólito modular com `catalog` e `reservation` (lado wallet) em CQRS + Event Sourcing, Redpanda, relay e consumer no processo. Rodada 2, Wi-Fi | 2026-10-19 |
| [`04b-fase6-es-cabo/`](04b-fase6-es-cabo/) | O mesmo código do 04 refeito no cabo, com Docker em SSD (controle da Rodada 3) | 2026-10-24 |
| [`05-distribuido/`](05-distribuido/) | Fase 7: `reservation` e `notification` como serviços; `gateway` chama o `reservation` por HTTP. Rodada 3, cabo, SSD | 2026-10-24 |
| [`06-batch1-final/`](06-batch1-final/) | Batch 1 (`b072346`): `notification`, `reservation`, `catalog` e `identity` como serviços, `gateway` só roteia e valida o JWT pelo JWKS; harness da #38 (escada `sequencial` e `intercalado`). Rodada R, cabo, SSD | 2026-10-28 |

A análise das rodadas de 2026-10-04 (antes e depois do pool) está em [`02-hikari/ANALISE.md`](02-hikari/ANALISE.md).

## Regra

- **A rodada corrente grava aqui na raiz.** Os scripts de `Projeto/carga` usam `results/` fixo: `matriz.csv`,
  `degraus-<id>.csv`, `summary-*.json`, `resultados*.csv`, `verificacoes.log` e `baseline-*.txt`.
- **Ao fechar uma etapa, mova os arquivos dela para a pasta da etapa** com `git mv` e atualize o README da etapa.
  Ajuste também as citações em `docs/` que apontam para o arquivo.
- Mova só resultados (CSV, `summary-*.json`, `perfil-*.json`, logs, `baseline-*.txt`). **Nunca mova
  `users-*.json` nem `events-*.json`**: eles têm credenciais de teste, e mesmo com o `.gitignore` cobrindo `results/**`, não os mova de propósito.
