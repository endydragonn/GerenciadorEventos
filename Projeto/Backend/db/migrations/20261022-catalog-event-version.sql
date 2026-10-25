-- Migração: coluna event.version (issue #28 — catalog em CQRS + Event Sourcing). Idempotente. Aplicável a quente
-- num banco já existente, SEM recriar o volume:
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco \
--     < Projeto/Backend/db/migrations/20261022-catalog-event-version.sql
--
-- Pré-requisito: o event_store/event_outbox do schema em que event está e o eventcap (public.eventcap, ou reservation.eventcap
-- depois da #33) existirem (migração 20261021-event-store.sql).
-- Para bancos NOVOS a coluna já vem do init-script (14-catalog.sql, desde a #35; e não há evento a preencher).
--
-- 1) ADD COLUMN IF NOT EXISTS: 'version' = versão do último evento do stream 'catalog.event' aplicado na linha
--    (projeção inline). 0 = evento "pré-ES", ainda sem stream.
-- 2) Backfill: todo evento com version = 0 (e sem stream) ganha um EventCreated v1 no event_store (com o estado atual: metadados,
--    capacidade e apresentadores; sem image_data, que fica fora do ES) + a linha do outbox, e passa a version = 1.
--    Só vira v1 a linha cujo EventCreated foi de fato inserido (RETURNING). Assim o replay do catálogo reconstrói também
--    os eventos que já existiam; o command RECUSA evento com version = 0. Roda 2x sem efeito colateral
--    (depois da 1ª vez nenhuma linha tem version = 0). Reaplique-a depois de carregar o db/seed-data.sql, que
--    insere em 'event' direto (version 0).
--
-- (Editada na #33 e de novo na #35, depois de já aplicada em bancos existentes, e por isso escrita para ser idempotente em TODOS
-- os estados do banco.) As tabelas são escolhidas na hora:
--   - event/eventpresenter/event_store/event_outbox: em public até a migração 20261026 (cutover do serviço catalog, #35) e no
--     schema catalog depois dela. public.event existindo = o banco ainda não migrou, e é lá que estão os dados (o 14-catalog.sql
--     da fatia 2 pode ter criado catalog.event VAZIA ao lado); senão, catalog. A 20261026 roda este mesmo backfill em public antes
--     de mover as tabelas.
--   - eventcap (capacidade do payload): reservation.eventcap quando existe (desde a #33), senão public.eventcap; sem nenhum dos
--     dois, a capacidade vai nula (o replay do catálogo não a usa; quem precisa dela é a reconciliação do reservation).

SET client_encoding = 'UTF8';

BEGIN;

DO $mig$
DECLARE
    esquema  text     := CASE WHEN to_regclass('public.event') IS NOT NULL THEN 'public'
                              WHEN to_regclass('catalog.event') IS NOT NULL THEN 'catalog' END;
    eventcap regclass := COALESCE(to_regclass('reservation.eventcap'), to_regclass('public.eventcap'));
    capacidade text;
    junta      text;
BEGIN
    IF esquema IS NULL THEN
        RAISE EXCEPTION 'Nem public.event nem catalog.event existem: nada a migrar (banco sem o schema do app?)';
    END IF;

    EXECUTE format('ALTER TABLE %I.event ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 0', esquema);

    IF eventcap IS NULL THEN
        capacidade := 'NULL::integer';
        junta      := '';
        RAISE NOTICE 'Sem eventcap (nem reservation nem public): o EventCreated do backfill vai sem capacidade';
    ELSE
        capacidade := 'c.capacity';
        junta      := format('LEFT JOIN %s c ON c.event_id = e.event_id', eventcap);
    END IF;

    EXECUTE format($sql$
WITH novos AS (
    INSERT INTO %1$I.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    SELECT gen_random_uuid(), 'catalog.event', e.event_id::text, 1, 'EventCreated',
           jsonb_build_object(
               'eventId', e.event_id,
               'creatorId', e.creator_id,
               'eventName', e.event_name,
               'isEad', e.ead,
               'address', e.address,
               'eventDate', e.event_date,
               'description', e.description,
               'capacity', %2$s,
               'buyTimeLimit', %3$s,
               'presenters', COALESCE((SELECT jsonb_agg(p.presenter_name ORDER BY p.presenter_name)
                                         FROM %1$I.eventpresenter p WHERE p.event_id = e.event_id), '[]'::jsonb)),
           e.event_id::text
      FROM %1$I.event e
      %4$s
     WHERE e.version = 0
    ON CONFLICT (stream_type, stream_id, version) DO NOTHING
    RETURNING global_position, stream_id
),
saida AS (
    INSERT INTO %1$I.event_outbox (global_position) SELECT global_position FROM novos
)
UPDATE %1$I.event e SET version = 1 FROM novos n WHERE e.event_id = n.stream_id::integer;
    $sql$, esquema, capacidade, CASE WHEN eventcap IS NULL THEN 'NULL::timestamp' ELSE 'c.buy_time_limit' END, junta);
END
$mig$;

COMMIT;
