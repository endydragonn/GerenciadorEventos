# Etapa 03: Supabase Postgres + Supavisor

## O que estava implementado

- Monólito Spring Boot (JDBC puro, HikariCP com 1000 conexões).
- Banco: `supabase/postgres:17.6.1.136` (PG 17.6, locale en_US) no lugar do `postgres:16-alpine`
  (issue #18, `c959440`).
- Pooler: Supavisor (`supabase/supavisor:2.9.13`) em **transaction mode** entre o backend e o Postgres
  (issue #19, `051fb64`).
  - O backend conecta em `supabase-pooler:6543` com o usuário `<role>.gerenciador-eventos` e `prepareThreshold=0`.
  - O Hikari só enfileira no pooler.
- Pool real no Postgres: `POOLER_DEFAULT_POOL_SIZE` = `max_connections − 55`.
  - Padrão: 45, com `max_connections` 100.
  - Teste de taxa: 245, com `max_connections` 300 (`carga/rpm/docker-compose.rpm.yml`).
- A role `admin` do app não é superuser e é dona do banco (`91c807c`).
- Código: a partir de `66a46b4` (merge do PR #119); as rodadas da #22 rodaram na branch `Issue-22`.

## Período

A partir de 2026-10-16.

## Arquivos

- `matriz.csv`: matriz CAS × ingênuo depois do pooler (#22), 50 linhas (10 CAS + 40 ingênuo; commit `12fc154`),
  no mesmo protocolo da `../02-hikari/matriz-prepooler-20261016.csv`. Rodadas de 2026-10-16, no commit `8ba01b4`.
  Condições: banco limpo, `max_connections` 300 e pool do Supavisor de 245. É o ponto 1 da série.

## Análise

- [`docs/carga/resultados-matriz-pospooler-2026-10-16.md`](../../../../docs/carga/resultados-matriz-pospooler-2026-10-16.md):
  resultados e comparação com o pré-pooler.
- [`docs/artigo/experimento-monolito.md`](../../../../docs/artigo/experimento-monolito.md): texto do artigo, subseção
  "Ponto 1 (pós-pooler)".
- Pré-pooler (referência): [`docs/carga/resultados-matriz-2026-10-16.md`](../../../../docs/carga/resultados-matriz-2026-10-16.md).
