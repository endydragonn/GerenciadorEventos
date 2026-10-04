-- Table: public.event_availability
-- Read model desnormalizado e DEFASADO da disponibilidade de vagas de um evento.
-- NÃO é fonte de verdade: eventcap + CAS continuam sendo. Esta tabela é atualizada
-- só pelo job ReadModelRefreshService num timer (lag configurável) e serve o
-- "caminho ingênuo" de reserva (feature flag reservation.readpath=naive), usado como
-- braço de controle do experimento de concorrência da Fase 2.
--
-- available = eventcap.capacity - (nº de reservas ativas em walletevent), piso 0.
--   -1  => evento ilimitado (espelha o sentinela de eventcap.capacity = -1).
-- refreshed_at = quando o job recalculou esta linha (o harness da #11 mede o lag
--   observado por now() - refreshed_at).
--
-- Tipos de tempo: usa timestamptz (não "timestamp without time zone" como as tabelas
-- vizinhas). É deliberado — código novo não deve propagar a dívida que a issue #58 vai
-- corrigir no schema inteiro.

SET client_encoding = 'UTF8';

DROP TABLE IF EXISTS public.event_availability CASCADE;

CREATE TABLE IF NOT EXISTS public.event_availability
(
    event_id     integer     NOT NULL,
    available    integer     NOT NULL,
    refreshed_at timestamptz NOT NULL DEFAULT now(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT event_availability_pkey PRIMARY KEY (event_id),
    CONSTRAINT event_availability_event_id_fkey FOREIGN KEY (event_id)
        REFERENCES public.event (event_id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE CASCADE,
    CONSTRAINT event_availability_available_valid CHECK (available >= -1)
)
TABLESPACE pg_default;

ALTER TABLE IF EXISTS public.event_availability OWNER to admin;

COMMENT ON TABLE public.event_availability IS
    'Read model desnormalizado e defasado da disponibilidade de vagas. Atualizado só '
    'pelo job em timer (lag configurável). available = capacity - reservas ativas (piso 0); '
    '-1 = ilimitado. Serve o caminho ingênuo de reserva (feature flag). Não é fonte de verdade.';
