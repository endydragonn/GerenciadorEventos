-- Schema: reservation  |  eventcap, walletevent, event_availability, idempotency_key + infra de event sourcing
-- Segunda extração de microsserviço (issue #33, Fase 7): o serviço reservation tem schema PRÓPRIO e é o único que lê e
-- escreve estas tabelas. Os agregados que moravam em public (04-eventcap, 07-walletevent, 09-event-availability e
-- 10-idempotency-key, removidos) vieram para cá; o histórico de cada tabela está nesses arquivos no git.
--
-- SEM FK PARA public.event / public.users (fronteira de serviço; nenhuma FK cross-schema). Consequências, todas tratadas
-- na aplicação e documentadas no Backend/CLAUDE.md ("Serviço reservation"):
--   * eventcap NÃO cai em cascata quando o evento é apagado: o catalog chama o reservation (DELETE /internal/eventcaps/{id}),
--     que recusa (409) se há walletevent do evento, como o antigo ON DELETE RESTRICT fazia;
--   * walletevent não valida user_id/event_id: o gateway confere a carteira e o evento antes de reservar, e a existência
--     do eventcap faz o papel de "evento existe" dentro do serviço;
--   * mywallet NÃO veio: é uma extensão 1:1 de users (criada por trigger), continua em public e é do identity.
--
-- Event store próprio (event_store, event_outbox, event_snapshot, projection_checkpoint): mesmo desenho do 11-event-store.sql,
-- em reservation.*. O serviço appenda o stream reservation.wallet na MESMA transação do CAS/guarda, o relay dele drena o
-- outbox dele e o consumer idempotente dele confere o eventId no event_store dele: nenhum serviço lê tabela de outro.
-- Usa timestamptz nas tabelas novas (código novo não propaga a dívida da issue #58); eventcap/walletevent mantêm os
-- tipos que já tinham.

SET client_encoding = 'UTF8';

CREATE SCHEMA IF NOT EXISTS reservation;
ALTER SCHEMA reservation OWNER to admin;

-- ===== eventcap =====
-- Agregado ISOLADO de capacidade/vagas de um evento (fronteira de consistência forte).
--   capacity = -1 => SEM LIMITE (quant também -1, a reserva não passa pelo CAS).
--   capacity >= 0 => limitado; invariante 0 <= quant <= capacity (CHECK) e reserva por UPDATE condicional atômico:
--     UPDATE reservation.eventcap SET quant = quant - 1 WHERE event_id = ? AND capacity <> -1 AND quant >= 1;
--     rowcount = 0 => sem vaga. Liberação: quant + 1 ... WHERE capacity <> -1 AND quant < capacity.
--   Nasce por chamada síncrona do catalog (PUT /internal/eventcaps/{id}, idempotente por event_id) e é reconciliada pelo
--   EventCreated do tópico catalog.event.
DROP TABLE IF EXISTS reservation.eventcap CASCADE;

CREATE TABLE IF NOT EXISTS reservation.eventcap
(
    event_id integer NOT NULL,
    capacity integer NOT NULL,
    quant integer NOT NULL,
    buy_time_limit timestamp without time zone,
    max_time_in_line integer NOT NULL DEFAULT 120,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT eventcap_pkey PRIMARY KEY (event_id),
    -- -1 = ilimitado; caso contrário capacity >= 0
    CONSTRAINT eventcap_capacity_valid CHECK (capacity >= -1),
    -- invariante só vale para eventos limitados
    CONSTRAINT eventcap_quant_bounds
        CHECK (capacity = -1 OR (quant >= 0 AND quant <= capacity)),
    CONSTRAINT eventcap_max_time_in_line_positive CHECK (max_time_in_line > 0)
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS reservation.eventcap
    OWNER to admin;

COMMENT ON TABLE reservation.eventcap IS
    'Capacidade/vagas isoladas do evento. capacity = -1 => sem limite de vendas (quant = -1). '
    'Caso contrário quant = vagas disponíveis e capacity = total. '
    'buy_time_limit = até quando aceita compra. max_time_in_line = segundos que uma reserva '
    'fica em walletevent.status = pending antes de expirar e devolver a vaga.';

-- ===== walletevent =====
-- Inscrição/reserva (user_id, event_id). Máquina de estados: pending -> confirmed / expired / cancelled; a vaga de
-- eventcap é debitada ANTES da linha nascer (mesma transação). Projeção INLINE do stream reservation.wallet: cada
-- transição é um UPDATE condicional com "SET version = version + 1 ... RETURNING version" e o evento só é appendado
-- quando afeta 1 linha, na mesma transação. version = 0: linha anterior ao event sourcing.
DROP TABLE IF EXISTS reservation.walletevent CASCADE;

CREATE TABLE IF NOT EXISTS reservation.walletevent
(
    user_id integer NOT NULL,
    event_id integer NOT NULL,
    status text COLLATE pg_catalog."default" NOT NULL DEFAULT 'confirmed',
    expires_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version integer NOT NULL DEFAULT 0,
    CONSTRAINT "WalletEvent_pkey" PRIMARY KEY (user_id, event_id),
    CONSTRAINT walletevent_status_check
        CHECK (status IN ('confirmed', 'pending', 'expired', 'cancelled'))
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS reservation.walletevent
    OWNER to admin;

-- Índice para o job de expiração varrer reservas pending vencidas.
CREATE INDEX IF NOT EXISTS idx_walletevent_pending_expires
    ON reservation.walletevent USING btree (expires_at)
    WHERE status = 'pending';

-- ===== event_availability =====
-- Projeção ASSÍNCRONA do tópico reservation.wallet (só ativos por evento); a disponibilidade é calculada na leitura
-- (eventcap LEFT JOIN esta tabela) e é lida só pelo braço naive. NÃO é fonte de verdade. Sem FK e sem CHECK em ativos
-- de propósito (qualquer violação no consumer travaria a partição; a leitura trata ativos negativo como 0).
DROP TABLE IF EXISTS reservation.event_availability CASCADE;

CREATE TABLE IF NOT EXISTS reservation.event_availability
(
    event_id         integer     NOT NULL,
    ativos           integer     NOT NULL DEFAULT 0,
    last_occurred_at timestamptz NOT NULL,
    applied_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT event_availability_pkey PRIMARY KEY (event_id)
)
TABLESPACE pg_default;

ALTER TABLE IF EXISTS reservation.event_availability OWNER to admin;

COMMENT ON TABLE reservation.event_availability IS
    'Projeção assíncrona (consumer do tópico reservation.wallet): só o nº de reservas ativas por evento. '
    'A disponibilidade é calculada na leitura (eventcap LEFT JOIN esta tabela). Não é fonte de verdade.';

-- ===== idempotency_key =====
-- Chaves de idempotência do POST /bff/event-wallets (#24), escopo por usuário (#129), retenção por job (#130). Uma linha
-- por reserva BEM-SUCEDIDA, na mesma transação do CAS + walletevent.
DROP TABLE IF EXISTS reservation.idempotency_key CASCADE;

CREATE TABLE IF NOT EXISTS reservation.idempotency_key
(
    idempotency_key text        NOT NULL,
    user_id         integer     NOT NULL,
    event_id        integer     NOT NULL,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_key_pkey PRIMARY KEY (user_id, idempotency_key)
)
TABLESPACE pg_default;

-- Job de retenção (issue #130): filtra por created_at; a PK (user_id, idempotency_key) não serve. Sem o índice
-- cada rodada faria seq scan da tabela inteira. Migração a quente: migrations/20261020-...sql.
CREATE INDEX IF NOT EXISTS idx_idempotency_key_created_at ON reservation.idempotency_key (created_at);

ALTER TABLE IF EXISTS reservation.idempotency_key OWNER to admin;

COMMENT ON TABLE reservation.idempotency_key IS
    'Chaves de idempotência do POST /bff/event-wallets. Uma linha por reserva bem-sucedida, gravada na '
    'mesma transação do CAS em eventcap. Sem FK (fronteira de módulo). expires_at = expires_at da reserva.';

-- ===== infra de event sourcing do serviço (mesmo desenho do 11-event-store.sql) =====
DROP TABLE IF EXISTS reservation.event_store CASCADE;
DROP TABLE IF EXISTS reservation.event_outbox CASCADE;
DROP TABLE IF EXISTS reservation.event_snapshot CASCADE;
DROP TABLE IF EXISTS reservation.projection_checkpoint CASCADE;

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

ALTER TABLE IF EXISTS reservation.event_store OWNER to admin;
ALTER TABLE IF EXISTS reservation.event_outbox OWNER to admin;
ALTER TABLE IF EXISTS reservation.event_snapshot OWNER to admin;
ALTER TABLE IF EXISTS reservation.projection_checkpoint OWNER to admin;

COMMENT ON TABLE reservation.event_store IS
    'Log imutável de eventos de domínio (fonte de verdade dos streams). UNIQUE (stream_type, stream_id, version) '
    'dá a concorrência otimista; global_position é ordem de alocação, não de commit.';
COMMENT ON TABLE reservation.event_outbox IS
    'global_position dos eventos ainda não publicados no broker. Inserida na transação do append, apagada pelo relay.';
COMMENT ON TABLE reservation.event_snapshot IS
    'Cache descartável do estado de um stream (último snapshot). Não é fonte de verdade.';
COMMENT ON TABLE reservation.projection_checkpoint IS
    'Última version aplicada de cada stream por projeção; avanço atômico = idempotência do consumer.';
