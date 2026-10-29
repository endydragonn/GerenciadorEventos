-- Schema: stock  |  eventcap (capacidade, CAS) + hold (ledger das reservas de vaga)
-- Saga stock + wallet (issue #47, Fase 7): o serviço stock tem schema PRÓPRIO e é o único que lê e escreve estas tabelas. O
-- reservation vira o lado wallet (reserva, event sourcing) e orquestra a saga; o stock guarda a capacidade e o ledger de holds.
-- Spec: docs/superpowers/specs/2026-10-29-saga-stock-wallet-design.md.
--
-- Passada a1: o reservation.eventcap (13-reservation.sql) ainda existe e coexiste com este; a passada a2 o tira do 13 e traz a
-- migração a quente (db/migrations/20261029-stock-schema.sql, que MOVE reservation.eventcap para cá preservando quant).
--
-- Idempotente (sem DROP, tudo IF NOT EXISTS). SEM FK cross-schema: hold.event_id/user_id são referências soltas.
--
-- O stock NÃO tem event store: não publica eventos, só consome (reservation.wallet para devolver vaga, catalog.event para reconciliar
-- o eventcap).

SET client_encoding = 'UTF8';

CREATE SCHEMA IF NOT EXISTS stock;
ALTER SCHEMA stock OWNER to admin;

-- ===== eventcap =====
-- Mesmo agregado e mesmos CHECKs do antigo reservation.eventcap (ver 13-reservation.sql):
--   capacity = -1 => SEM LIMITE (quant também -1, a reserva não passa pelo CAS);
--   capacity >= 1 => limitado, invariante 0 <= quant <= capacity (CHECK) e reserva por UPDATE condicional atômico:
--     UPDATE stock.eventcap SET quant = quant - 1 WHERE event_id = ? AND capacity <> -1 AND quant >= 1;
--   devolução: quant = LEAST(quant + n, capacity).
-- Nasce por chamada síncrona do catalog (PUT /internal/eventcaps/{id}, idempotente por event_id) e é reconciliada pelo EventCreated
-- do tópico catalog.event.
CREATE TABLE IF NOT EXISTS stock.eventcap
(
    event_id integer NOT NULL,
    capacity integer NOT NULL,
    quant integer NOT NULL,
    buy_time_limit timestamptz,
    max_time_in_line integer NOT NULL DEFAULT 120,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT eventcap_pkey PRIMARY KEY (event_id),
    CONSTRAINT eventcap_capacity_valid CHECK (capacity = -1 OR capacity >= 1),
    CONSTRAINT eventcap_quant_bounds
        CHECK (capacity = -1 OR (quant >= 0 AND quant <= capacity)),
    CONSTRAINT eventcap_max_time_in_line_positive CHECK (max_time_in_line > 0)
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS stock.eventcap
    OWNER to admin;

COMMENT ON TABLE stock.eventcap IS
    'Capacidade/vagas isoladas do evento. capacity = -1 => sem limite de vendas (quant = -1). '
    'Caso contrário quant = vagas disponíveis e capacity = total. '
    'buy_time_limit = até quando aceita compra. max_time_in_line = segundos que uma reserva '
    'fica pending antes de expirar e devolver a vaga.';

-- ===== hold =====
-- Ledger das reservas de vaga: uma linha por tentativa de reserva (hold_id = UUID gerado pelo wallet, a chave de idempotência dos
-- passos no stock).
--   decremented = true só no braço cas de evento limitado (o hold tirou uma vaga do eventcap; a devolução só acontece se for true).
--   status held     = a vaga está presa por este hold.
--   status released = devolvida, ou LÁPIDE (release que chegou antes do "reservar": barra o reservar atrasado e nunca debita).
-- Um hold liberado nunca volta a held. A conversão ilimitado -> limitado conta os holds held do evento (e os marca decremented), e
-- o delete do eventcap olha holds de qualquer status (o antigo ON DELETE RESTRICT).
CREATE TABLE IF NOT EXISTS stock.hold
(
    hold_id uuid NOT NULL,
    event_id integer NOT NULL,
    user_id integer NOT NULL,
    decremented boolean NOT NULL,
    status text COLLATE pg_catalog."default" NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT hold_pkey PRIMARY KEY (hold_id),
    CONSTRAINT hold_status_check CHECK (status IN ('held', 'released'))
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS stock.hold
    OWNER to admin;

-- Contagem dos holds ativos de um evento (conversão ilimitado -> limitado) e existência de hold de qualquer status (delete).
CREATE INDEX IF NOT EXISTS idx_hold_event_status
    ON stock.hold USING btree (event_id, status);

COMMENT ON TABLE stock.hold IS
    'Ledger de holds da saga stock + wallet (#47). hold_id = idempotency key do passo; released sem débito = lápide.';
