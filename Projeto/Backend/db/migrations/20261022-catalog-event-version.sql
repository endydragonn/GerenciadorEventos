-- Migração: coluna public.event.version (issue #28 — catalog em CQRS + Event Sourcing). Idempotente. Aplicável a quente
-- num banco já existente, SEM recriar o volume:
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco \
--     < Projeto/Backend/db/migrations/20261022-catalog-event-version.sql
--
-- Pré-requisito: public.event_store, public.event_outbox e o eventcap (public.eventcap, ou reservation.eventcap depois da #33) existirem (migração 20261021-event-store.sql).
-- Para bancos NOVOS a coluna já vem do init-script 03-event.sql (e não há evento a preencher).
--
-- 1) ADD COLUMN IF NOT EXISTS: 'version' = versão do último evento do stream 'catalog.event' aplicado na linha
--    (projeção inline). 0 = evento "pré-ES", ainda sem stream.
-- 2) Backfill: todo evento com version = 0 (e sem stream) ganha um EventCreated v1 no event_store (com o estado atual: metadados,
--    capacidade e apresentadores; sem image_data, que fica fora do ES) + a linha do outbox, e passa a version = 1.
--    Só vira v1 a linha cujo EventCreated foi de fato inserido (RETURNING). Assim o replay do catálogo reconstrói também
--    os eventos que já existiam; o command RECUSA evento com version = 0. Roda 2x sem efeito colateral
--    (depois da 1ª vez nenhuma linha tem version = 0). Reaplique-a depois de carregar o db/seed-data.sql, que
--    insere em 'event' direto (version 0).

SET client_encoding = 'UTF8';

BEGIN;

ALTER TABLE public.event ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 0;

-- (Editada na #33, depois de já aplicada em bancos existentes, e por isso escrita para ser idempotente nos DOIS estados do banco:
-- o eventcap saiu de public e o passo abaixo, que antes lia public.eventcap, passou a escolher a tabela na hora.)
-- A capacidade vem do eventcap: em public até a #33 e em reservation.eventcap depois dela (Fase 7: o eventcap saiu do
-- monólito). Esta migração continua valendo nos dois estados do banco (e depois de recarregar o seed-data.sql), então a
-- tabela é escolhida na hora: reservation.eventcap quando existe, senão public.eventcap.
DO $mig$
DECLARE
    eventcap regclass := COALESCE(to_regclass('reservation.eventcap'), to_regclass('public.eventcap'));
BEGIN
    EXECUTE format($sql$
WITH novos AS (
    INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    SELECT gen_random_uuid(), 'catalog.event', e.event_id::text, 1, 'EventCreated',
           jsonb_build_object(
               'eventId', e.event_id,
               'creatorId', e.creator_id,
               'eventName', e.event_name,
               'isEad', e.ead,
               'address', e.address,
               'eventDate', e.event_date,
               'description', e.description,
               'capacity', c.capacity,
               'buyTimeLimit', c.buy_time_limit,
               'presenters', COALESCE((SELECT jsonb_agg(p.presenter_name ORDER BY p.presenter_name)
                                         FROM public.eventpresenter p WHERE p.event_id = e.event_id), '[]'::jsonb)),
           e.event_id::text
      FROM public.event e
      LEFT JOIN %s c ON c.event_id = e.event_id
     WHERE e.version = 0
    ON CONFLICT (stream_type, stream_id, version) DO NOTHING
    RETURNING global_position, stream_id
),
saida AS (
    INSERT INTO public.event_outbox (global_position) SELECT global_position FROM novos
)
UPDATE public.event e SET version = 1 FROM novos n WHERE e.event_id = n.stream_id::integer;
    $sql$, eventcap);
END
$mig$;

COMMIT;
