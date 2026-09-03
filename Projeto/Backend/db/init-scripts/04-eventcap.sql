-- Table: public.eventcap
-- Agregado ISOLADO de capacidade/vagas de um evento.
-- Encoding: UTF-8
--
-- Fronteira de consistência forte, à parte do resto do domínio (ver Backend-Architecture
-- nas notas do TCC).
--
-- capacity = -1  =>  SEM LIMITE de vendas (evento ilimitado). Nesse caso quant também é -1
--                    (sentinela), a invariante de capacidade não se aplica e a reserva NÃO
--                    passa pelo UPDATE condicional — a aplicação libera direto.
-- capacity >= 0  =>  limitado. A invariante 0 <= quant <= capacity é protegida por:
--   1. o CHECK eventcap_quant_bounds (defesa no banco);
--   2. reserva via UPDATE condicional atômico, feita pela aplicação:
--        UPDATE public.eventcap SET quant = quant - 1
--          WHERE event_id = ? AND capacity <> -1 AND quant >= 1;
--      rowcount = 0  =>  sem vaga (resposta definitiva, sem lock aberto, sem retry).
--   liberação de vaga (cancelamento / expiração de reserva):
--        UPDATE public.eventcap SET quant = quant + 1
--          WHERE event_id = ? AND capacity <> -1 AND quant < capacity;

SET client_encoding = 'UTF8';

DROP TABLE IF EXISTS public.eventcap CASCADE;

CREATE TABLE IF NOT EXISTS public.eventcap
(
    event_id integer NOT NULL,
    capacity integer NOT NULL,
    quant integer NOT NULL,
    buy_time_limit timestamp without time zone,
    max_time_in_line integer NOT NULL DEFAULT 120,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT eventcap_pkey PRIMARY KEY (event_id),
    CONSTRAINT eventcap_event_id_fkey FOREIGN KEY (event_id)
        REFERENCES public.event (event_id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE CASCADE,
    -- -1 = ilimitado; caso contrário capacity >= 0
    CONSTRAINT eventcap_capacity_valid CHECK (capacity >= -1),
    -- invariante só vale para eventos limitados
    CONSTRAINT eventcap_quant_bounds
        CHECK (capacity = -1 OR (quant >= 0 AND quant <= capacity)),
    CONSTRAINT eventcap_max_time_in_line_positive CHECK (max_time_in_line > 0)
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS public.eventcap
    OWNER to admin;

COMMENT ON TABLE public.eventcap IS
    'Capacidade/vagas isoladas do evento. capacity = -1 => sem limite de vendas (quant = -1). '
    'Caso contrário quant = vagas disponíveis e capacity = total. '
    'buy_time_limit = até quando aceita compra. max_time_in_line = segundos que uma reserva '
    'fica em walletevent.status = pending antes de expirar e devolver a vaga.';
