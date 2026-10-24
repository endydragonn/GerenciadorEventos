-- Schema: catalog  |  event, eventpresenter + infra de event sourcing
-- Terceira extração de microsserviço (issue #35, Fase 7): o serviço catalog tem schema PRÓPRIO e é o único que lê e escreve
-- estas tabelas. Consolida 03-event.sql e 05-eventpresenter.sql (mesmas colunas) + o event store do serviço (mesmo desenho do
-- 11-event-store.sql / 13-reservation.sql). Todas as queries do serviço qualificam com "catalog." (o Supavisor em transaction mode
-- proíbe search_path).
--
-- FATIA 2 da #35: este schema CONVIVE com public.event/public.eventpresenter (03/05), que o monólito ainda usa. A migração dos dados
-- e a saída de 03/05 são da fatia 3 (db/migrations/20261026-catalog-schema.sql).
--
-- IDEMPOTENTE e NÃO destrutivo (sem DROP, tudo IF NOT EXISTS): o run-tests.sh o aplica a cada rodada num banco existente.
--
-- SEM FK cross-schema: event.creator_id é integer SEM FK para public.users (referência solta; quem confere o criador é o gateway,
-- UserApi.exists). A única FK é interna: eventpresenter -> event, ON DELETE CASCADE.

SET client_encoding = 'UTF8';

CREATE SCHEMA IF NOT EXISTS catalog;
ALTER SCHEMA catalog OWNER to admin;

-- ===== event =====
-- Projeção INLINE do stream catalog.event. version = versão do último evento aplicado (0 = pré-ES, sem stream; o command recusa);
-- é a versão esperada do próximo append. image_data NÃO vem do log (blob, fora do ES). Capacidade/janela de venda são do
-- serviço reservation (reservation.eventcap), não daqui.
CREATE TABLE IF NOT EXISTS catalog.event
(
    event_id integer NOT NULL GENERATED ALWAYS AS IDENTITY ( INCREMENT 1 START 1 MINVALUE 1 MAXVALUE 2147483647 CACHE 1 ),
    creator_id integer NOT NULL,
    event_name text COLLATE pg_catalog."default" NOT NULL,
    ead boolean NOT NULL DEFAULT false,
    address text COLLATE pg_catalog."default",
    event_date timestamp without time zone NOT NULL,
    description text COLLATE pg_catalog."default" NOT NULL,
    image_data bytea,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version integer NOT NULL DEFAULT 0,
    CONSTRAINT "Event_pkey" PRIMARY KEY (event_id),
    CONSTRAINT "Event_event_name_key" UNIQUE (event_name)
)
TABLESPACE pg_default;

ALTER TABLE IF EXISTS catalog.event OWNER to admin;

-- "Meus eventos" (GET /internal/events?creatorId=) filtra no banco: WHERE creator_id = ?.
CREATE INDEX IF NOT EXISTS idx_event_creator_id ON catalog.event (creator_id);

-- ===== eventpresenter =====
-- Apresentadores por evento (0..N linhas). Cai junto com o evento (ON DELETE CASCADE).
CREATE TABLE IF NOT EXISTS catalog.eventpresenter
(
    event_id integer NOT NULL,
    presenter_name text COLLATE pg_catalog."default" NOT NULL,
    CONSTRAINT eventpresenter_pkey PRIMARY KEY (event_id, presenter_name),
    CONSTRAINT eventpresenter_event_id_fkey FOREIGN KEY (event_id)
        REFERENCES catalog.event (event_id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE CASCADE
)
TABLESPACE pg_default;

ALTER TABLE IF EXISTS catalog.eventpresenter OWNER to admin;

COMMENT ON TABLE catalog.eventpresenter IS
    'Apresentadores por evento; pode não ter linhas (lista vazia).';

-- ===== infra de event sourcing do serviço (mesmo desenho do 11-event-store.sql) =====
CREATE TABLE IF NOT EXISTS catalog.event_store
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

CREATE TABLE IF NOT EXISTS catalog.event_outbox
(
    global_position bigint NOT NULL,
    CONSTRAINT event_outbox_pkey PRIMARY KEY (global_position)
)
WITH (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_threshold = 100)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS catalog.event_snapshot
(
    stream_type text        NOT NULL,
    stream_id   text        NOT NULL,
    version     integer     NOT NULL,
    state       jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT event_snapshot_pkey PRIMARY KEY (stream_type, stream_id)
)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS catalog.projection_checkpoint
(
    projecao    text        NOT NULL,
    stream_type text        NOT NULL,
    stream_id   text        NOT NULL,
    version     integer     NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT projection_checkpoint_pkey PRIMARY KEY (projecao, stream_type, stream_id)
)
TABLESPACE pg_default;

ALTER TABLE IF EXISTS catalog.event_store OWNER to admin;
ALTER TABLE IF EXISTS catalog.event_outbox OWNER to admin;
ALTER TABLE IF EXISTS catalog.event_snapshot OWNER to admin;
ALTER TABLE IF EXISTS catalog.projection_checkpoint OWNER to admin;

COMMENT ON TABLE catalog.event_store IS
    'Log imutável de eventos de domínio (fonte de verdade dos streams). UNIQUE (stream_type, stream_id, version) '
    'dá a concorrência otimista; global_position é ordem de alocação, não de commit.';
COMMENT ON TABLE catalog.event_outbox IS
    'global_position dos eventos ainda não publicados no broker. Inserida na transação do append, apagada pelo relay.';
COMMENT ON TABLE catalog.event_snapshot IS
    'Cache descartável do estado de um stream (último snapshot). Não é fonte de verdade.';
COMMENT ON TABLE catalog.projection_checkpoint IS
    'Última version aplicada de cada stream por projeção; avanço atômico = idempotência do consumer.';
