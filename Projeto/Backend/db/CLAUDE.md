# CLAUDE.md — Banco de dados (PostgreSQL)

PostgreSQL 17 (imagem `supabase/postgres`), rodando em container. É a **fonte de verdade do schema** —
não há migração via ORM. Leia também o CLAUDE.md da raiz e o de `Backend/`.

## Container

- Definido em `Projeto/docker-compose.yml` (serviço `db`), imagem `supabase/postgres:17.6.1.136`
  (a mesma do upstream fixado em `Projeto/supabase/README.md`). O antigo `db/Dockerfile`
  (`postgres:16-alpine`, pt_BR) foi removido na issue #18 e fica como fallback no histórico do git.
- **Pré-requisito:** `Projeto/supabase/.env` precisa existir antes do `up` (o `db` lê os segredos
  do Supabase de lá; ver `Projeto/supabase/README.md`).
- Host: `localhost:5433` → dentro da rede Docker: `db:5432`. Container: `gerenciador-db`.
- O usuário do app, a senha e o nome do banco vêm de `Projeto/.env` (`POSTGRES_USER`,
  `POSTGRES_PASSWORD`, `POSTGRES_DB=meu_banco`). Não commitar/imprimir. No container eles entram
  como `APP_DB_USER`/`APP_DB_PASSWORD`/`POSTGRES_DB`: o `POSTGRES_USER` do container fica o
  default da imagem (`supabase_admin`) — definir outro (ex. `admin`) quebra o init por peer auth.
  Por isso o `db` usa `env_file: ./supabase/.env` (segredos das roles internas do Supabase) e não
  mais `./.env`.
- **Banco `meu_banco`, não `postgres`** (decisão da issue #18): `POSTGRES_DB=meu_banco` faz a
  imagem criar o banco já com esse nome e rodar o init do Supabase nele (schemas `auth`,
  `storage`, `extensions`, `pgbouncer`). Assim o backend, o `run-tests.sh` e o gerador de carga
  (`PGDATABASE=meu_banco`) não mudam, e o tenant do Supavisor (#19) aponta para um banco só, que
  já tem o `pgbouncer.get_auth` e as tabelas do app (tenant criado em `supabase/volumes/pooler/pooler-upsert.exs`, #19).
- Role do app: `admin` (de `APP_DB_USER`) é criada por `supabase-app-init.sql` como
  `LOGIN` **sem superuser** (menor privilégio) e vira **dona do banco** `meu_banco` — no PG15+
  o schema `public` pertence ao dono do banco, então isso lhe dá `CREATE` no `public` e no banco
  (o `TestSchemasTest` cria um schema temporário). É dona das tabelas (os testes dão `TRUNCATE`
  como dona). Tarefa que exija superuser: `docker exec gerenciador-db psql -U supabase_admin`
  (trust no socket). Os init-scripts têm `OWNER TO admin` fixo, então `POSTGRES_USER` do
  `Projeto/.env` precisa continuar `admin`.
- Locale/encoding: UTF-8, locale `en_US.UTF-8` (ICU) da imagem — antes era pt_BR. Postgres 17.6
  (antes 16).
- Volumes: `supabase-db-data` (PGDATA; novo — o `db-data` antigo é PG16 e não sobe no PG17, fica
  órfão, não apagar) e `db-config` (`/etc/postgresql-custom`, guarda a chave do pgsodium).
- Acesso: no socket do container só `supabase_admin` entra (trust); `admin` por socket falha
  (peer). Use `-h localhost` (127.0.0.1 é trust) ou TCP de fora com senha. O healthcheck usa
  `pg_isready -U postgres -h localhost`.

## Inspeção visual (Supabase Studio)

Com a stack no ar, o Studio fica em `http://localhost:8000` (via Kong; login com `DASHBOARD_USERNAME` e
`DASHBOARD_PASSWORD` de `Projeto/supabase/.env`). Table Editor → schema `public` mostra `eventcap`,
`walletevent` etc.; o SQL Editor roda consultas em `meu_banco`. Studio e `meta` conectam como
`supabase_admin`, não como `admin`: é leitura/edição manual de apoio, o backend continua usando só JDBC.
Detalhes e limites (sem GoTrue/PostgREST/Realtime/Storage/functions) em `Projeto/supabase/README.md`.

## Como os init-scripts rodam (imagem `supabase/postgres`)

O `migrate.sh` da imagem roda `init-scripts/*.sql` como `postgres` e depois `migrations/*.sql`
como `supabase_admin` (um `psql` por arquivo, ordem lexical, no `POSTGRES_DB`). No compose:

- `Backend/db/supabase-app-init.sql` → `/docker-entrypoint-initdb.d/migrations/99-app.sql`. O
  prefixo `99` roda depois de todas as migrations do Supabase. Ele cria a role do app, faz
  `SET ROLE` para ela (tabelas nascem com dono `admin`) e inclui com `\i` os scripts abaixo,
  montados read-only em `/app-init` (fora de `/docker-entrypoint-initdb.d`, para a imagem não
  rodá-los sozinhos com o dono errado). **Script novo em `init-scripts/` precisa de um `\i` lá.**
- `supabase/volumes/db/roles.sql` → `init-scripts/99-roles.sql` (senhas das roles internas) e
  `supabase/volumes/db/functions-role.sql` → `init-scripts/98-functions-role.sql` (cria a role
  `supabase_functions_admin` que o `roles.sql` altera; ver `supabase/README.md`).
- `supabase/volumes/db/_supabase.sql` → `migrations/97-_supabase.sql` e
  `supabase/volumes/db/pooler.sql` → `migrations/99-pooler.sql` (issue #19): criam o banco
  **`_supabase`** (dono `supabase_admin`) e o schema **`_supavisor`** dentro dele. É o estado interno do
  Supavisor (tenants, métricas, `schema_migrations` do Ecto), **não** o schema do app: o app só usa o
  `meu_banco`. O `99-pooler.sql` ordena depois do `99-app.sql` (lexical) e termina com `\c postgres`;
  não depende dele. O Supavisor conecta no `_supabase` como `supabase_admin` e, no `meu_banco`, como o
  usuário do cliente (`admin`, via `pgbouncer.get_auth`, role `pgbouncer` do init do Supabase).
  O `db` **não** é mais o endpoint do backend em Docker: o backend usa `supabase-pooler:6543` (ver
  `Backend/CLAUDE.md`, "Pooler"); `5433` do host e os testes seguem direto no `db`.

## Scripts de init — `db/init-scripts/` (ordem por prefixo numérico)

(O antigo `00-create-db.sql` foi removido na issue #18: o banco já nasce `meu_banco`. Está no histórico do git.)

| Arquivo | Conteúdo |
|---|---|
| `01-functions.sql` | `create_wallet_for_user()` (usada pelo trigger de `users`) |
| `02-users.sql` | tabela `users` + trigger `trigger_create_wallet` |
| `03-event.sql` | tabela `event` — só metadados (FK `creator_id → users`) |
| `04-eventcap.sql` | tabela `eventcap` — capacidade/vagas isoladas (CHECK `0<=quant<=capacity`) |
| `05-eventpresenter.sql` | tabela `eventpresenter` — apresentadores (0..N por evento) |
| `06-mywallet.sql` | tabela `mywallet` (FK `user_id → users`, `ON DELETE CASCADE`) |
| `07-walletevent.sql` | tabela `walletevent` (PK composta, `status`, `expires_at`) |
| `08-pending-verifications.sql` | tabela + funções/trigger de limpeza de códigos expirados |
| `09-event-availability.sql` | tabela `event_availability` — read model defasado de vagas (não é fonte de verdade) |
| `10-idempotency-key.sql` | tabela `idempotency_key` — chaves de idempotência do `POST /bff/event-wallets` (sem FK) |
| `99-tests.sql` | suíte de sanidade em `DO $$ ... $$` (trigger, CAS, CHECK, N:N, cascata, read model, TRUNCATE) |

`db/migrations/` guarda scripts de migração idempotentes para bancos já existentes
(ex.: `20260901-eventcap-isolation.sql`), aplicáveis a quente via `psql` sem recriar o volume.
Nessa migração, a semântica de `quant` **inverte**: no modelo antigo `event.quant` = ingressos
vendidos, no novo `eventcap.quant` = vagas disponíveis, então a etapa 2 converte
`quant = GREATEST(capacity - vendidos, 0)` (e `capacity IS NULL` do modelo antigo vira o
sentinela `-1/-1` de evento ilimitado). Coberto por `EventCapMigrationTest` (só a conversão)
e, ponta a ponta, pelo harness `migrations/verify-20260901-migration.sh`.

`migrations/verify-20260901-migration.sh` (harness da issue #6, cross-platform como
`run-tests.sh`): cria um banco **descartável** `eventcap_mig_check` no container, aplica os
init-scripts `01`–`09`, roda `99-tests.sql`, depois roda o harness SQL
`verify-20260901-migration.sql` — que reconstrói o schema ANTIGO **com dados**, aplica a
migração **2×** (idempotência) e checa colunas removidas/criadas, constraints, índice parcial,
conversão de `quant` (limitado / esgotado-piso-0 / ilimitado) e **preservação dos dados**
(contagem + identidade das linhas). Não toca em `meu_banco`. Exit `!= 0` se algo falhar.

**Os scripts de init só rodam na PRIMEIRA criação do volume `supabase-db-data`** (inclusive o `_supabase`/`_supavisor` do pooler; num volume antigo, recrie-o). Depois disso são
ignorados. Para reaplicar do zero:

```bash
docker compose -f Projeto/docker-compose.yml down -v
docker compose -f Projeto/docker-compose.yml up -d --build
```

`db/seed-data.sql` popula dados de exemplo (`ON CONFLICT DO NOTHING`); rode manualmente:
`docker exec -i gerenciador-db psql -h localhost -U admin -d meu_banco < Projeto/Backend/db/seed-data.sql`.

## Modelo atual

- **`users`** — PK `user_id` (identity). `email`, `fone`, `user_name` são `UNIQUE` e
  `NOT NULL`. Flags de papel: `admin` e **`Host`** — DDL declara `Host boolean` **sem aspas**
  (`db/init-scripts/02-users.sql`), então o Postgres dobra o identificador para minúsculas:
  a coluna real é `host`, acessada sem aspas em SQL (igual `isactive`). Campos de MFA de
  reset de senha: `password_reset_mfa_code`,
  `password_reset_mfa_expiration`. `created_at` / `updated_at` com default.
  - Trigger `trigger_create_wallet` (`AFTER INSERT`): cria automaticamente a linha em
    `mywallet`. A aplicação **nunca** insere carteira à mão.
- **`event`** — PK `event_id`. FK `creator_id → users(user_id)` (`ON DELETE NO ACTION`).
  `event_name` `UNIQUE`, `ead` (bool), `image_data bytea`. **Só metadados** — capacidade/venda
  saíram daqui para `eventcap`.
- **`eventcap`** — agregado ISOLADO de capacidade. PK `event_id`, FK `→ event` `ON DELETE
  CASCADE`. `capacity` (`NOT NULL`), `quant` = vagas disponíveis (`NOT NULL`), `buy_time_limit`
  (nullable), `max_time_in_line` (segundos, default 120).
  - **`capacity = -1` ⇒ sem limite de vendas** (evento ilimitado); nesse caso `quant = -1`
    também, o CHECK `eventcap_quant_bounds` (`capacity = -1 OR 0 <= quant <= capacity`) não
    restringe, e a reserva **não** passa pelo CAS — o serviço libera direto.
  - Limitado: reserva = `UPDATE eventcap SET quant = quant - 1 WHERE event_id = ? AND
    capacity <> -1 AND quant >= 1` (CAS atômico; `rowcount = 0` ⇒ lotado). Liberação =
    `+ 1 ... WHERE capacity <> -1 AND quant < capacity`.
- **`eventpresenter`** — apresentadores por evento. PK composta `(event_id, presenter_name)`,
  FK `→ event` `ON DELETE CASCADE`. Substitui o antigo campo único `presenters`.
- **`mywallet`** — PK `user_id`, FK `→ users` `ON DELETE CASCADE`.
- **`walletevent`** — inscrição/reserva N:N. PK composta `(user_id, event_id)`. FKs para
  `users`/`event` são `ON DELETE RESTRICT`. `status` (`confirmed`|`pending`|`expired`|
  `cancelled`, default `confirmed`, CHECK) + `expires_at` (só quando `pending`). Índice parcial
  `idx_walletevent_pending_expires` para o job de expiração.
- **`pending_verifications`** — cadastro pré-confirmação (`email` `UNIQUE`, `password_hash`,
  `verification_code`, `expires_at`, `attempts`/`max_attempts`). Trigger
  `trigger_cleanup_on_create_verification` + função `cleanup_expired_verifications()`
  removem expirados a cada INSERT e no startup.
- **`event_availability`** — read model **desnormalizado e DEFASADO** da disponibilidade de
  vagas. PK `event_id`, FK `→ event` `ON DELETE CASCADE`. `available` = `capacity` − reservas
  ativas (`pending`/`confirmed`) em `walletevent`, piso 0; `-1` = evento ilimitado.
  `refreshed_at` = quando o job recalculou a linha. **NÃO é fonte de verdade** (`eventcap` +
  CAS continuam sendo): é atualizado só pelo job `ReadModelRefreshService` num timer
  (`readmodel.availability.refresh.ms`) e serve o "caminho ingênuo" de reserva
  (feature flag `reservation.readpath=naive`), braço de controle do experimento da Fase 2.
  Usa `timestamptz` de propósito (código novo não propaga a dívida que a issue #58 corrige).
  Migração a quente: `db/migrations/20260903-event-availability.sql`.
- **`idempotency_key`** (issue #24) — PK composta `(user_id, idempotency_key)` (a chave tem escopo por
  usuário, issue #129; `idempotency_key` é text, até 255 caracteres validado no backend),
  `event_id` (**sem FK** para `users`/`event`, de propósito: fronteira de módulo, schema por
  serviço é Fase 7), `expires_at` (o da reserva original, devolvido na repetição), `created_at`
  (`timestamptz`). Uma linha por reserva **bem-sucedida**: o `INSERT ... ON CONFLICT DO NOTHING` entra na
  MESMA transação JDBC do CAS em `eventcap` + `walletevent`, então falha faz rollback e não deixa chave;
  a PK serializa duas requisições do mesmo usuário com a mesma chave. **Sem retenção/limpeza por ora** (cresce 1 linha por
  reserva). Migração a quente: `db/migrations/20261018-idempotency-key.sql` (idempotente; roda como
  `supabase_admin`, por isso o `OWNER TO admin` explícito) e, para trocar a PK antiga `(idempotency_key)`
  pela composta, `db/migrations/20261019-idempotency-key-pk-por-usuario.sql` (idempotente: só troca se as
  colunas da PK ainda forem as antigas). Coberta por `SchemaValidationTest`,
  `IdempotencyKeyTest` e o passo 8b do `99-tests.sql`.

## Regras ao alterar o schema

- Edite o `.sql` correspondente em `init-scripts/`. Se a mudança precisar valer para bancos
  já existentes (o volume não vai ser recriado), escreva também um script idempotente
  (`ALTER TABLE ... IF NOT EXISTS`, `CREATE OR REPLACE`) e rode manualmente via `psql`.
- Reflita a mudança no repository JDBC correspondente do backend (nome de coluna, tipo,
  nullability) e ajuste `SchemaValidationTest`.
- Atualize `99-tests.sql` se a mudança afetar trigger/relacionamento.
- Depois de mexer, valide com `down -v` + `up` e rode `Projeto/Backend/run-tests.sh`.
- SQL sempre parametrizado no lado da aplicação; identificadores com maiúscula entre aspas.
