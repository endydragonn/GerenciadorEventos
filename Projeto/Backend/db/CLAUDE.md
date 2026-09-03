# CLAUDE.md — Banco de dados (PostgreSQL)

PostgreSQL 16 (imagem `alpine`), rodando em container. É a **fonte de verdade do schema** —
não há migração via ORM. Leia também o CLAUDE.md da raiz e o de `Backend/`.

## Container

- Definido em `Projeto/docker-compose.yml` (serviço `db`) + `db/Dockerfile`.
- Host: `localhost:5433` → dentro da rede Docker: `db:5432`. Container: `gerenciador-db`.
- Credenciais/nome do banco vêm de `Projeto/.env` (`POSTGRES_USER`, `POSTGRES_PASSWORD`,
  `POSTGRES_DB=meu_banco`). Não commitar/imprimir.
- Locale/encoding: UTF-8 (pt_BR) — configurado no `db/Dockerfile`.
- Volume de dados: `db-data`.

## Scripts de init — `db/init-scripts/` (ordem por prefixo numérico)

| Arquivo | Conteúdo |
|---|---|
| `00-create-db.sql` | cria `meu_banco` se não existir (`\gexec`) |
| `01-functions.sql` | `create_wallet_for_user()` (usada pelo trigger de `users`) |
| `02-users.sql` | tabela `users` + trigger `trigger_create_wallet` |
| `03-event.sql` | tabela `event` — só metadados (FK `creator_id → users`) |
| `04-eventcap.sql` | tabela `eventcap` — capacidade/vagas isoladas (CHECK `0<=quant<=capacity`) |
| `05-eventpresenter.sql` | tabela `eventpresenter` — apresentadores (0..N por evento) |
| `06-mywallet.sql` | tabela `mywallet` (FK `user_id → users`, `ON DELETE CASCADE`) |
| `07-walletevent.sql` | tabela `walletevent` (PK composta, `status`, `expires_at`) |
| `08-pending-verifications.sql` | tabela + funções/trigger de limpeza de códigos expirados |
| `99-tests.sql` | suíte de sanidade em `DO $$ ... $$` (trigger, CAS, CHECK, N:N, cascata, TRUNCATE) |

`db/migrations/` guarda scripts de migração idempotentes para bancos já existentes
(ex.: `20260901-eventcap-isolation.sql`), aplicáveis a quente via `psql` sem recriar o volume.
Nessa migração, a semântica de `quant` **inverte**: no modelo antigo `event.quant` = ingressos
vendidos, no novo `eventcap.quant` = vagas disponíveis, então a etapa 2 converte
`quant = GREATEST(capacity - vendidos, 0)` (e `capacity IS NULL` do modelo antigo vira o
sentinela `-1/-1` de evento ilimitado). Coberto por `EventCapMigrationTest`.

**Os scripts de init só rodam na PRIMEIRA criação do volume `db-data`.** Depois disso são
ignorados. Para reaplicar do zero:

```bash
docker compose -f Projeto/docker-compose.yml down -v
docker compose -f Projeto/docker-compose.yml up -d --build
```

`db/seed-data.sql` popula dados de exemplo (`ON CONFLICT DO NOTHING`); rode manualmente:
`docker exec -i gerenciador-db psql -U admin -d meu_banco < Projeto/Backend/db/seed-data.sql`.

## Modelo atual

- **`users`** — PK `user_id` (identity). `email`, `fone`, `user_name` são `UNIQUE` e
  `NOT NULL`. Flags de papel: `admin` e **`Host`** (coluna com maiúscula → sempre entre aspas
  em SQL: `"Host"`). Campos de MFA de reset de senha: `password_reset_mfa_code`,
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

## Regras ao alterar o schema

- Edite o `.sql` correspondente em `init-scripts/`. Se a mudança precisar valer para bancos
  já existentes (o volume não vai ser recriado), escreva também um script idempotente
  (`ALTER TABLE ... IF NOT EXISTS`, `CREATE OR REPLACE`) e rode manualmente via `psql`.
- Reflita a mudança no repository JDBC correspondente do backend (nome de coluna, tipo,
  nullability) e ajuste `SchemaValidationTest`.
- Atualize `99-tests.sql` se a mudança afetar trigger/relacionamento.
- Depois de mexer, valide com `down -v` + `up` e rode `Projeto/Backend/run-tests.sh`.
- SQL sempre parametrizado no lado da aplicação; identificadores com maiúscula entre aspas.
