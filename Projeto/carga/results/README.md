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
| [`07-rodada4-admission/`](07-rodada4-admission/) | Batch 2 (`35955ef`): `admission` (#41–#43: contador no Redis, fila RabbitMQ, gatilho 80/70, 202) e autoescala (#111/#112: override + HAProxy + scaler), imagens slim (#177), kit da #178. Rodada 4: sem autoescala estável até 98k (0 5xx, fila drenando), braço C parado em 32k/38k pelo health check do `lb` (#207); CAS 0 | 2026-10-29 |
| [`08-rodada5-saga/`](08-rodada5-saga/) | Batch 2 (`850d38e`): a configuração da 07 mais a saga `stock` + `wallet` (#47: serviço `stock` com hold + CAS, `reservation` orquestrador, compensação inline e reaper) e o kit da #204. Rodada 5: sem autoescala estável até 98k (0 5xx, 0 órfãs, 0 compensações, lag de devolução p95 448 ms), braço C parado em 44k (R4: 38k) pelo health check do `lb` (#207); CAS 0 | 2026-10-29 e 2026-10-30 |
| [`09-r5-db-remoto/`](09-r5-db-remoto/) | O código e as imagens da 08 (`850d38e`), com o Postgres num Dell dedicado (#227, opção A: só o `db` sai, pooler e serviços no host; RTT p50 0,45 ms). Braço base da R5: estável até 98k (0 5xx, 0 órfãs, 0 compensações, CAS 0), 1º degrau degradado em 32k (R5: 38k), p95 de janela de 0,27–0,47 s de 50k a 98k (R5: 0,27–0,87 s), lag de devolução p95 640 ms (R5: 448 ms); Dell sem saturar (CPU ≤ 43% de média), host no teto de CPU | 2026-11-01 |

A comparação lado a lado das 3 configurações (tabela e gráficos) está na subseção "Comparação das 3 configurações (issue #39)" de [`docs/artigo/experimento-monolito.md`](../../../docs/artigo/experimento-monolito.md).

A análise das rodadas de 2026-10-04 (antes e depois do pool) está em [`02-hikari/ANALISE.md`](02-hikari/ANALISE.md).

## Regra

- **A rodada corrente grava aqui na raiz.** Os scripts de `Projeto/carga` usam `results/` fixo: `matriz.csv`,
  `degraus-<id>.csv`, `summary-*.json`, `resultados*.csv`, `verificacoes.log` e `baseline-*.txt`.
- **Ao fechar uma etapa, mova os arquivos dela para a pasta da etapa** com `git mv` e atualize o README da etapa.
  Ajuste também as citações em `docs/` que apontam para o arquivo.
- Mova só resultados (CSV, `summary-*.json`, `perfil-*.json`, logs, `baseline-*.txt`). **Nunca mova
  `users-*.json` nem `events-*.json`**: eles têm credenciais de teste, e mesmo com o `.gitignore` cobrindo `results/**`, não os mova de propósito.
