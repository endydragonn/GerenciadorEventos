-- Table: public.event_availability
-- Projeção ASSÍNCRONA (issue #29; ADR Event-Backbone-Event-Store) alimentada pelo consumer do tópico
-- reservation.wallet. NÃO é fonte de verdade (eventcap + CAS são): serve só o "caminho ingênuo" de reserva
-- (feature flag reservation.readpath=naive), braço de controle do experimento de concorrência.
--
-- Guarda SÓ o nº de reservas ativas por evento (ativos): SlotReserved +1; ReservationExpired/ReservationCancelled -1;
-- ReservationConfirmed 0. A disponibilidade NÃO é gravada: é calculada na leitura
-- (eventcap LEFT JOIN esta tabela; capacity = -1 => -1; senão GREATEST(capacity - GREATEST(COALESCE(ativos, 0), 0), 0)),
-- então um evento sem nenhuma reserva já tem disponibilidade e a mudança de capacidade vale na hora.
-- A defasagem fica só em ativos, que é o que o experimento mede. Nunca lê eventcap.quant (viraria o CAS).
--
-- Lag observável (ADR, seção 4): last_occurred_at = occurredAt do último evento aplicado (maior occurredAt visto)
-- e applied_at = instante do commit do consumer (clock_timestamp(), não now(): now() é o início da transação).
-- O lag do último evento é applied_at - last_occurred_at.
--
-- Sem FK para event e sem CHECK em ativos, de propósito: qualquer violação no consumer é uma SQLException, que a
-- política de erro trata como transitória (retry sem limite) e travaria a partição. ativos pode ficar negativo
-- se o log tiver um Cancelled sem SlotReserved (linha anterior ao ES): a leitura trata ativos negativo como 0 (GREATEST(ativos, 0)); sem isso a disponibilidade passaria da capacidade.
-- Sem FK também é a fronteira de módulo (schema por serviço é a Fase 7).
--
-- Tipos de tempo: usa timestamptz (não "timestamp without time zone" como as tabelas vizinhas). É deliberado
-- — código novo não deve propagar a dívida que a issue #58 vai corrigir no schema inteiro.

SET client_encoding = 'UTF8';

DROP TABLE IF EXISTS public.event_availability CASCADE;

CREATE TABLE IF NOT EXISTS public.event_availability
(
    event_id         integer     NOT NULL,
    ativos           integer     NOT NULL DEFAULT 0,
    last_occurred_at timestamptz NOT NULL,
    applied_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT event_availability_pkey PRIMARY KEY (event_id)
)
TABLESPACE pg_default;

ALTER TABLE IF EXISTS public.event_availability OWNER to admin;

COMMENT ON TABLE public.event_availability IS
    'Projeção assíncrona (consumer do tópico reservation.wallet): só o nº de reservas ativas por evento. '
    'A disponibilidade é calculada na leitura (eventcap LEFT JOIN esta tabela). Não é fonte de verdade.';
