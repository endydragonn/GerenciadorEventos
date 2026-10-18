-- Table: public.walletevent
-- Inscrição/reserva de um usuário num evento (relação N:N usuário<->evento).
-- Encoding: UTF-8
--
-- Máquina de estados da reserva:
--   pending    -> reserva feita: vaga já debitada de eventcap.quant, aguardando
--                 confirmação até expires_at (= criação + eventcap.max_time_in_line).
--   confirmed  -> inscrição efetivada.
--   expired    -> reserva não confirmada a tempo; a vaga foi devolvida a eventcap.
--   cancelled  -> inscrição cancelada pelo usuário; a vaga foi devolvida a eventcap.
-- A linha só é criada/atualizada DEPOIS que o UPDATE condicional em eventcap teve sucesso.
--
-- Projeção INLINE do stream 'reservation.wallet' (issue #29; ADR Event-Backbone-Event-Store): cada transição
-- (reservePending/confirm/cancelIfActive/expireIfStale) é um UPDATE condicional com
-- "SET version = version + 1 ... RETURNING version" e o evento (SlotReserved/ReservationConfirmed/
-- ReservationCancelled/ReservationExpired) é appendado em event_store SÓ quando ele afeta 1 linha, na mesma
-- transação, com essa version. Reserva nova depois de cancelada/expirada continua o mesmo stream (version segue).
-- version = 0: linha anterior ao event sourcing (sem eventos no log).

SET client_encoding = 'UTF8';

DROP TABLE IF EXISTS public.walletevent CASCADE;

CREATE TABLE IF NOT EXISTS public.walletevent
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
        CHECK (status IN ('confirmed', 'pending', 'expired', 'cancelled')),
    CONSTRAINT "WalletEvent_event_id_fkey" FOREIGN KEY (event_id)
        REFERENCES public.event (event_id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE RESTRICT,
    CONSTRAINT "WalletEvent_user_id_fkey" FOREIGN KEY (user_id)
        REFERENCES public.users (user_id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE RESTRICT
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS public.walletevent
    OWNER to admin;

-- Índice para o job de expiração varrer reservas pending vencidas.
CREATE INDEX IF NOT EXISTS idx_walletevent_pending_expires
    ON public.walletevent USING btree (expires_at)
    WHERE status = 'pending';
