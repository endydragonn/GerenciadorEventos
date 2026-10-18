-- Migração: infra de event sourcing (issue #27) — tabelas public.event_store, event_outbox, event_snapshot e
-- projection_checkpoint. Idempotente. Aplicável a quente num banco já existente, SEM recriar o volume:
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco \
--     < Projeto/Backend/db/migrations/20261021-event-store.sql
--
-- Para bancos NOVOS o schema já vem do init-script 11-event-store.sql (a documentação das colunas está lá).
-- Roda 2x sem efeito colateral (CREATE TABLE IF NOT EXISTS). Roda como supabase_admin, por isso o OWNER TO admin
-- explícito (no init as tabelas já nascem da role admin).

SET client_encoding = 'UTF8';

BEGIN;

CREATE TABLE IF NOT EXISTS public.event_store
(
    global_position bigint GENERATED ALWAYS AS IDENTITY,
    event_id        uuid        NOT NULL,
    stream_type     text        NOT NULL,
    stream_id       text        NOT NULL,
    version         integer     NOT NULL,
    event_type      text        NOT NULL,
    schema_version  integer     NOT NULL DEFAULT 1,
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    payload         jsonb       NOT NULL,
    metadata        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    partition_key   text        NOT NULL,
    CONSTRAINT event_store_pkey PRIMARY KEY (global_position),
    CONSTRAINT event_store_stream_version_key UNIQUE (stream_type, stream_id, version),
    CONSTRAINT event_store_version_valid CHECK (version >= 1)
)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS public.event_outbox
(
    global_position bigint NOT NULL,
    CONSTRAINT event_outbox_pkey PRIMARY KEY (global_position)
)
WITH (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_threshold = 100)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS public.event_snapshot
(
    stream_type text        NOT NULL,
    stream_id   text        NOT NULL,
    version     integer     NOT NULL,
    state       jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT event_snapshot_pkey PRIMARY KEY (stream_type, stream_id)
)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS public.projection_checkpoint
(
    projecao    text        NOT NULL,
    stream_type text        NOT NULL,
    stream_id   text        NOT NULL,
    version     integer     NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT projection_checkpoint_pkey PRIMARY KEY (projecao, stream_type, stream_id)
)
TABLESPACE pg_default;

ALTER TABLE IF EXISTS public.event_store OWNER to admin;
ALTER TABLE IF EXISTS public.event_outbox OWNER to admin;
ALTER TABLE IF EXISTS public.event_snapshot OWNER to admin;
ALTER TABLE IF EXISTS public.projection_checkpoint OWNER to admin;

COMMENT ON TABLE public.event_store IS
    'Log imutável de eventos de domínio (fonte de verdade dos streams). UNIQUE (stream_type, stream_id, version) '
    'dá a concorrência otimista; global_position é ordem de alocação, não de commit.';
COMMENT ON TABLE public.event_outbox IS
    'global_position dos eventos ainda não publicados no broker. Inserida na transação do append, apagada pelo relay.';
COMMENT ON TABLE public.event_snapshot IS
    'Cache descartável do estado de um stream (último snapshot). Não é fonte de verdade.';
COMMENT ON TABLE public.projection_checkpoint IS
    'Última version aplicada de cada stream por projeção; avanço atômico = idempotência do consumer.';

COMMIT;
