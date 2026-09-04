-- Migração: read model desnormalizado e defasado public.event_availability (issue #10 / #09 do BACKLOG).
-- Idempotente. Aplicável a quente num banco já existente, SEM recriar o volume:
--   docker exec -i gerenciador-db psql -U admin -d meu_banco \
--     < Projeto/Backend/db/migrations/20260903-event-availability.sql
--
-- Para bancos NOVOS o schema já vem correto pelo init-script 09-event-availability.sql;
-- esta migração é só para ambientes criados antes da mudança. Roda 2× sem efeito colateral
-- (CREATE TABLE IF NOT EXISTS + INSERT ... ON CONFLICT DO NOTHING).
--
-- A tabela é populada a partir do estado ATUAL de eventcap/walletevent; depois disso quem a
-- mantém atualizada é o job ReadModelRefreshService (@Scheduled, intervalo
-- readmodel.availability.refresh.ms). available = capacity - reservas ativas (piso 0);
-- -1 = evento ilimitado. Usa timestamptz de propósito (ver cabeçalho do init-script).

SET client_encoding = 'UTF8';

BEGIN;

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

-- Popular a partir do estado atual (mesmo SELECT do job; aqui com DO NOTHING = idempotente).
INSERT INTO public.event_availability (event_id, available, refreshed_at)
SELECT ec.event_id,
       CASE WHEN ec.capacity = -1 THEN -1
            ELSE GREATEST(ec.capacity - COALESCE(a.ativos, 0), 0) END,
       now()
FROM public.eventcap ec
LEFT JOIN (
    SELECT event_id, count(*) AS ativos
    FROM public.walletevent
    WHERE status IN ('pending', 'confirmed')
    GROUP BY event_id
) a ON a.event_id = ec.event_id
ON CONFLICT (event_id) DO NOTHING;

COMMIT;
