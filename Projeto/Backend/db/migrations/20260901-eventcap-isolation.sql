-- Migração: isolar capacidade/vagas no agregado eventcap + máquina de estados de reserva
-- Idempotente. Aplicável a quente num banco já existente, SEM recriar o volume:
--   docker exec -i gerenciador-db psql -h localhost -U admin -d meu_banco \
--     < Projeto/Backend/db/migrations/20260901-eventcap-isolation.sql
--
-- Para bancos NOVOS o schema já vem correto pelos init-scripts; esta migração é só para
-- ambientes que foram criados antes da mudança.

SET client_encoding = 'UTF8';

BEGIN;

-- 1) Novas tabelas -----------------------------------------------------------------

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
        REFERENCES public.event (event_id) ON DELETE CASCADE,
    CONSTRAINT eventcap_capacity_valid CHECK (capacity >= -1),
    CONSTRAINT eventcap_quant_bounds CHECK (capacity = -1 OR (quant >= 0 AND quant <= capacity)),
    CONSTRAINT eventcap_max_time_in_line_positive CHECK (max_time_in_line > 0)
);
ALTER TABLE public.eventcap OWNER TO admin;

CREATE TABLE IF NOT EXISTS public.eventpresenter
(
    event_id integer NOT NULL,
    presenter_name text NOT NULL,
    CONSTRAINT eventpresenter_pkey PRIMARY KEY (event_id, presenter_name),
    CONSTRAINT eventpresenter_event_id_fkey FOREIGN KEY (event_id)
        REFERENCES public.event (event_id) ON DELETE CASCADE
);
ALTER TABLE public.eventpresenter OWNER TO admin;

-- 2) Migrar dados de event.* -> eventcap (só se as colunas antigas ainda existirem) ---

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'event' AND column_name = 'quant'
    ) THEN
        -- Conversão de semântica: no modelo ANTIGO event.quant = ingressos VENDIDOS;
        -- no modelo NOVO eventcap.quant = vagas DISPONÍVEIS. Logo quant_novo = capacity - vendidos.
        --   event.capacity NULL (regra antiga) => evento sem limite => -1/-1
        --   caso contrário: capacity preservada; quant = GREATEST(capacity - vendidos, 0)
        INSERT INTO public.eventcap (event_id, capacity, quant, buy_time_limit)
        SELECT event_id,
               CASE WHEN capacity IS NULL THEN -1
                    ELSE capacity END AS capacity,
               CASE WHEN capacity IS NULL THEN -1
                    ELSE GREATEST(capacity - quant, 0) END AS quant,
               buy_time_limit
        FROM public.event
        ON CONFLICT (event_id) DO NOTHING;
    END IF;
END $$;

-- 3) Remover colunas antigas de event -------------------------------------------------

ALTER TABLE public.event DROP COLUMN IF EXISTS capacity;
ALTER TABLE public.event DROP COLUMN IF EXISTS quant;
ALTER TABLE public.event DROP COLUMN IF EXISTS buy_time_limit;

-- 4) walletevent: máquina de estados ------------------------------------------------

ALTER TABLE public.walletevent
    ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'confirmed';
ALTER TABLE public.walletevent
    ADD COLUMN IF NOT EXISTS expires_at timestamp without time zone;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'walletevent_status_check'
    ) THEN
        ALTER TABLE public.walletevent
            ADD CONSTRAINT walletevent_status_check
            CHECK (status IN ('confirmed', 'pending', 'expired', 'cancelled'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_walletevent_pending_expires
    ON public.walletevent USING btree (expires_at)
    WHERE status = 'pending';

COMMIT;
