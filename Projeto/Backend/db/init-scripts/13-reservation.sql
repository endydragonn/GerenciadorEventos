-- Schema: reservation  |  walletevent, event_availability, idempotency_key (log da saga) + infra de event sourcing
-- Segunda extração de microsserviço (issue #33, Fase 7): o serviço reservation tem schema PRÓPRIO e é o único que lê e
-- escreve estas tabelas. Os agregados que moravam em public (04-eventcap, 07-walletevent, 09-event-availability e
-- 10-idempotency-key, removidos) vieram para cá; o histórico de cada tabela está nesses arquivos no git.
--
-- SEM FK PARA public.event / public.users (fronteira de serviço; nenhuma FK cross-schema). Consequências, todas tratadas
-- na aplicação e documentadas no Backend/CLAUDE.md ("Serviço reservation"):
--   * o eventcap (capacidade) saiu daqui na #47: é do schema stock (16-stock.sql), com o ledger de holds; o reservation é o lado
--     wallet e ORQUESTRA a saga stock + wallet (a linha de idempotency_key é o log da saga);
--   * walletevent não valida user_id/event_id: o gateway confere a carteira e o evento antes de reservar, e a existência
--     do eventcap (no stock) faz o papel de "evento existe";
--   * mywallet NÃO veio: é uma extensão 1:1 de users (criada por trigger), continua em public e é do identity.
--
-- Event store próprio (event_store, event_outbox, event_snapshot, projection_checkpoint): mesmo desenho do 11-event-store.sql,
-- em reservation.*. O serviço appenda o stream reservation.wallet na MESMA transação do CAS/guarda, o relay dele drena o
-- outbox dele e o consumer idempotente dele confere o eventId no event_store dele: nenhum serviço lê tabela de outro.
-- Instantes (buy_time_limit, expires_at, last_occurred_at, ...) são timestamptz em todo o schema (issue #58 migrou o
-- eventcap/walletevent; migrations/20261027-reservation-timestamptz.sql, a quente). created_at/updated_at do eventcap e do
-- walletevent seguem timestamp: são carimbos de auditoria, não entram em comparação.

SET client_encoding = 'UTF8';

CREATE SCHEMA IF NOT EXISTS reservation;
ALTER SCHEMA reservation OWNER to admin;

-- ===== walletevent =====
-- Inscrição/reserva (user_id, event_id). Máquina de estados: pending -> confirmed / expired / cancelled; a vaga é segurada no
-- stock (hold) ANTES da linha nascer, e hold_id liga a reserva corrente ao hold (revivê-la grava o hold novo). Projeção INLINE do stream reservation.wallet: cada
-- transição é um UPDATE condicional com "SET version = version + 1 ... RETURNING version" e o evento só é appendado
-- quando afeta 1 linha, na mesma transação. version = 0: linha anterior ao event sourcing.
DROP TABLE IF EXISTS reservation.walletevent CASCADE;

CREATE TABLE IF NOT EXISTS reservation.walletevent
(
    user_id integer NOT NULL,
    event_id integer NOT NULL,
    status text COLLATE pg_catalog."default" NOT NULL DEFAULT 'confirmed',
    expires_at timestamptz,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version integer NOT NULL DEFAULT 0,
    hold_id uuid,
    CONSTRAINT "WalletEvent_pkey" PRIMARY KEY (user_id, event_id),
    CONSTRAINT walletevent_status_check
        CHECK (status IN ('confirmed', 'pending', 'expired', 'cancelled'))
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS reservation.walletevent
    OWNER to admin;

-- Busca só por event_id (a PK é (user_id, event_id) e não serve): contagem/varredura das reservas de um evento.
CREATE INDEX IF NOT EXISTS idx_walletevent_event_id
    ON reservation.walletevent USING btree (event_id);

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
-- Chaves de idempotência do POST /bff/event-wallets (#24), escopo por usuário (#129), retenção por job (#130). Desde a #47 a linha é
-- também o LOG DA SAGA stock + wallet: a reivindicação da chave é o início da saga (tx1), e toda transição é um UPDATE condicional
-- por (PK, hold_id, status esperado). reserving (hold pedido ao stock, sem resposta ainda) -> reserved (tx2 comitou) | compensating
-- (desfazendo: libera o hold no stock) -> compensated. Linhas anteriores à #47 ficam reserved.
DROP TABLE IF EXISTS reservation.idempotency_key CASCADE;

CREATE TABLE IF NOT EXISTS reservation.idempotency_key
(
    idempotency_key text        NOT NULL,
    user_id         integer     NOT NULL,
    event_id        integer     NOT NULL,
    expires_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    hold_id         uuid,
    saga_status     text        NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    resolved_at     timestamptz,
    resolved_by     text,
    CONSTRAINT idempotency_key_pkey PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT idempotency_key_saga_status_check
        CHECK (saga_status IN ('reserving', 'reserved', 'compensating', 'compensated')),
    CONSTRAINT idempotency_key_resolved_by_check
        CHECK (resolved_by IN ('inline', 'reaper'))
)
TABLESPACE pg_default;

-- Job de retenção (issue #130): filtra por created_at; a PK (user_id, idempotency_key) não serve. Sem o índice
-- cada rodada faria seq scan da tabela inteira. Migração a quente: migrations/20261020-...sql.
CREATE INDEX IF NOT EXISTS idx_idempotency_key_created_at ON reservation.idempotency_key (created_at);

-- Reaper de sagas órfãs (#47): só as não terminais, por antiguidade.
CREATE INDEX IF NOT EXISTS idx_idempotency_key_saga_pendente ON reservation.idempotency_key (updated_at)
    WHERE saga_status IN ('reserving', 'compensating');

ALTER TABLE IF EXISTS reservation.idempotency_key OWNER to admin;

COMMENT ON TABLE reservation.idempotency_key IS
    'Chaves de idempotência do POST /bff/event-wallets e log da saga stock + wallet (#47). Sem FK (fronteira de módulo). '
    'expires_at = expires_at da reserva, gravado na tx2 (nulo enquanto reserving).';

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
