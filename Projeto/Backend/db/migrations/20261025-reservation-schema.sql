-- Migração: schema do serviço reservation (issue #33, Fase 7). Idempotente. Aplicável a quente num banco já existente,
-- SEM recriar o volume, e com o gerenciador-backend (e o reservation) PARADOS:
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco --     < Projeto/Backend/db/migrations/20261025-reservation-schema.sql
--
-- O que faz (cada passo só age se ainda houver o que fazer, então roda 2x sem efeito colateral):
--   1) cria o schema reservation e MOVE de public para ele, com os dados, eventcap, walletevent, event_availability e
--      idempotency_key (ALTER TABLE ... SET SCHEMA mantém dados, índices e constraints). Antes derruba as FKs que cruzariam o
--      schema (eventcap -> event; walletevent -> event e -> users): nenhuma FK cross-schema. mywallet FICA em public.
--   2) cria o event store DO SERVIÇO (event_store, event_outbox, event_snapshot, projection_checkpoint em reservation) e
--      MOVE para ele os streams reservation.wallet que estavam no event_store do monólito (linhas do event_store com
--      global_position preservada, do event_outbox ainda não publicado, do projection_checkpoint e do event_snapshot).
--      Sem isso o consumer do serviço acharia buraco em todo stream que já existia (walletevent.version > 0 sem eventos) e
--      o replay deixaria de bater. Os streams catalog.event e identity.notification ficam em public.
--   3) reposiciona a sequence de global_position do event_store novo logo depois do maior valor copiado.
-- Roda como supabase_admin, por isso os OWNER TO admin explícitos. lock_timeout de 5 s: o ALTER TABLE ... SET SCHEMA pede
-- ACCESS EXCLUSIVE; com o backend parado não há disputa, e se houver a migração falha limpa em vez de travar o banco.
-- Para bancos NOVOS o schema já vem do init-script 13-reservation.sql (a documentação das colunas está lá).

SET client_encoding = 'UTF8';

BEGIN;

SET LOCAL lock_timeout = '5s';

CREATE SCHEMA IF NOT EXISTS reservation;
ALTER SCHEMA reservation OWNER to admin;

-- 1) tabelas de domínio -------------------------------------------------------------------------------------------
DO $$
BEGIN
    IF to_regclass('public.eventcap') IS NOT NULL AND to_regclass('reservation.eventcap') IS NULL THEN
        ALTER TABLE public.eventcap DROP CONSTRAINT IF EXISTS eventcap_event_id_fkey;
        ALTER TABLE public.eventcap SET SCHEMA reservation;
    END IF;
    IF to_regclass('public.walletevent') IS NOT NULL AND to_regclass('reservation.walletevent') IS NULL THEN
        ALTER TABLE public.walletevent DROP CONSTRAINT IF EXISTS "WalletEvent_event_id_fkey";
        ALTER TABLE public.walletevent DROP CONSTRAINT IF EXISTS "WalletEvent_user_id_fkey";
        ALTER TABLE public.walletevent SET SCHEMA reservation;
    END IF;
    IF to_regclass('public.event_availability') IS NOT NULL AND to_regclass('reservation.event_availability') IS NULL THEN
        ALTER TABLE public.event_availability SET SCHEMA reservation;
    END IF;
    IF to_regclass('public.idempotency_key') IS NOT NULL AND to_regclass('reservation.idempotency_key') IS NULL THEN
        ALTER TABLE public.idempotency_key SET SCHEMA reservation;
    END IF;
END $$;

-- 2) event store do serviço ---------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reservation.event_store
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

CREATE TABLE IF NOT EXISTS reservation.event_outbox
(
    global_position bigint NOT NULL,
    CONSTRAINT event_outbox_pkey PRIMARY KEY (global_position)
)
WITH (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_threshold = 100)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS reservation.event_snapshot
(
    stream_type text        NOT NULL,
    stream_id   text        NOT NULL,
    version     integer     NOT NULL,
    state       jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT event_snapshot_pkey PRIMARY KEY (stream_type, stream_id)
)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS reservation.projection_checkpoint
(
    projecao    text        NOT NULL,
    stream_type text        NOT NULL,
    stream_id   text        NOT NULL,
    version     integer     NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT projection_checkpoint_pkey PRIMARY KEY (projecao, stream_type, stream_id)
)
TABLESPACE pg_default;

ALTER TABLE reservation.event_store OWNER to admin;
ALTER TABLE reservation.event_outbox OWNER to admin;
ALTER TABLE reservation.event_snapshot OWNER to admin;
ALTER TABLE reservation.projection_checkpoint OWNER to admin;

COMMENT ON TABLE reservation.event_store IS
    'Log imutável de eventos de domínio (fonte de verdade dos streams). UNIQUE (stream_type, stream_id, version) '
    'dá a concorrência otimista; global_position é ordem de alocação, não de commit.';
COMMENT ON TABLE reservation.event_outbox IS
    'global_position dos eventos ainda não publicados no broker. Inserida na transação do append, apagada pelo relay.';
COMMENT ON TABLE reservation.event_snapshot IS
    'Cache descartável do estado de um stream (último snapshot). Não é fonte de verdade.';
COMMENT ON TABLE reservation.projection_checkpoint IS
    'Última version aplicada de cada stream por projeção; avanço atômico = idempotência do consumer.';

-- Move os streams reservation.wallet do event store do monólito (public) para o do serviço. Ordem: copia (event_store,
-- outbox, checkpoint, snapshot) e só então apaga de public; ON CONFLICT DO NOTHING torna a cópia repetível.
INSERT INTO reservation.event_store
    (global_position, event_id, stream_type, stream_id, version, event_type, schema_version, occurred_at, payload, metadata, partition_key)
OVERRIDING SYSTEM VALUE
SELECT global_position, event_id, stream_type, stream_id, version, event_type, schema_version, occurred_at, payload, metadata, partition_key
FROM public.event_store WHERE stream_type = 'reservation.wallet'
ON CONFLICT DO NOTHING;

INSERT INTO reservation.event_outbox (global_position)
SELECT o.global_position FROM public.event_outbox o
JOIN public.event_store s USING (global_position) WHERE s.stream_type = 'reservation.wallet'
ON CONFLICT DO NOTHING;

DELETE FROM public.event_outbox o USING public.event_store s
WHERE o.global_position = s.global_position AND s.stream_type = 'reservation.wallet';

INSERT INTO reservation.projection_checkpoint (projecao, stream_type, stream_id, version, updated_at)
SELECT projecao, stream_type, stream_id, version, updated_at
FROM public.projection_checkpoint WHERE stream_type = 'reservation.wallet'
ON CONFLICT DO NOTHING;
DELETE FROM public.projection_checkpoint WHERE stream_type = 'reservation.wallet';

INSERT INTO reservation.event_snapshot (stream_type, stream_id, version, state, created_at)
SELECT stream_type, stream_id, version, state, created_at
FROM public.event_snapshot WHERE stream_type = 'reservation.wallet'
ON CONFLICT DO NOTHING;
DELETE FROM public.event_snapshot WHERE stream_type = 'reservation.wallet';

DELETE FROM public.event_store WHERE stream_type = 'reservation.wallet';

-- 3) a identity continua a partir do maior global_position copiado
SELECT setval(pg_get_serial_sequence('reservation.event_store', 'global_position'),
              COALESCE((SELECT max(global_position) FROM reservation.event_store), 0) + 1, false);

COMMIT;
