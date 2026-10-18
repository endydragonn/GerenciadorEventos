-- Tables: public.event_store, public.event_outbox, public.event_snapshot, public.projection_checkpoint
-- Infra de event sourcing da Fase 6 (issue #27; ADR "Event-Backbone-Event-Store"). Genérica, sem domínio:
-- #28 (catalog) e #29 (reservation) só appendam eventos com seu streamType.
--
-- event_store: log IMUTÁVEL (nenhuma linha é atualizada depois do append) e fonte de verdade dos streams.
--   Colunas = envelope do evento (global_position, event_id, stream_type, stream_id, version, event_type,
--   schema_version, occurred_at, payload, metadata) + partition_key (chave da mensagem no tópico: o id do
--   evento DE DOMÍNIO, event.event_id, não o event_id do envelope; só o relay lê).
--   Concorrência otimista: UNIQUE (stream_type, stream_id, version) + INSERT ... ON CONFLICT DO NOTHING
--   (rowcount 0 = outra transação gravou essa versão antes).
--   global_position é alocado na ordem do INSERT, NÃO na do commit: não serve de cursor de acompanhamento.
--   occurred_at = clock_timestamp() (instante do append, não o início da transação).
--   Sem dado pessoal no payload (a tabela é imutável e não dá para apagar o que entra).
--
-- event_outbox: só a global_position dos eventos ainda não publicados. Inserida na MESMA transação do append e
--   apagada pelo relay depois do ack do broker; INSERT+DELETE contínuo gera tuplas mortas, por isso o autovacuum
--   mais agressivo. Sem FK de propósito (caminho quente: o INSERT não precisa travar/consultar o event_store).
--
-- event_snapshot: cache DESCARTÁVEL do estado de um stream (último snapshot por stream). Mudou o formato do estado:
--   apaga e reconstrói.
--
-- projection_checkpoint: última version aplicada de cada stream por projeção; o avanço atômico
--   (UPDATE ... WHERE version = v-1 / INSERT ... ON CONFLICT DO NOTHING para a v1) é o que torna o consumer idempotente.
--
-- Tudo em public por ora (schema por serviço é Fase 7). Usa timestamptz.

SET client_encoding = 'UTF8';

DROP TABLE IF EXISTS public.event_store CASCADE;
DROP TABLE IF EXISTS public.event_outbox CASCADE;
DROP TABLE IF EXISTS public.event_snapshot CASCADE;
DROP TABLE IF EXISTS public.projection_checkpoint CASCADE;

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
