-- Migração: schema do serviço catalog (issue #35, Fase 7: cutover da terceira extração). Idempotente. Aplicável a quente num
-- banco já existente, SEM recriar o volume, com o gerenciador-backend e o gerenciador-catalog PARADOS (o reservation pode ficar
-- de pé: ele não lê event nem o event store do catalog). ANTES, o backup (válido só até reabrir o tráfego; --clean --if-exists
-- para o restore poder rodar por cima do public que continua existindo):
--   docker exec gerenciador-db pg_dump -h localhost -U admin --clean --if-exists -n public meu_banco > <fora do repo>/meu_banco-public-<data>.sql
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 \
--     < Projeto/Backend/db/migrations/20261026-catalog-schema.sql
-- Rollback (só antes de reabrir o tráfego; tópico e groups ficam fora; backend e catalog parados), o mesmo roteiro que o
-- db/migrations/verify-20261026-migration.sh prova num banco descartável:
--   docker exec gerenciador-db psql -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 -c 'DROP SCHEMA catalog CASCADE;'
--   docker exec -i gerenciador-db psql -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 < <fora do repo>/meu_banco-public-<data>.sql
-- (como supabase_admin: o dump recria o schema public com os ALTER DEFAULT PRIVILEGES das roles do Supabase, que o admin não pode
-- aplicar; as tabelas voltam com dono admin). Sem script reverso.
--
-- Pré-requisito: a 20261025 (schema reservation) já aplicada.
--
-- O que faz (cada passo só age se ainda houver o que fazer, então roda 2x sem efeito colateral):
--   0) cria o schema catalog (dono admin) e o event store DO SERVIÇO (event_store, event_outbox, event_snapshot,
--      projection_checkpoint) com IF NOT EXISTS, como a 20261025 fez para o reservation.
--   1) SÓ se public.event ainda existe (banco não migrado):
--      a) sobras da fatia 2 da #35: o 14-catalog.sql criou catalog.event/catalog.eventpresenter VAZIAS (com constraints, índices
--         e sequence de MESMO nome: "Event_pkey", "Event_event_name_key", event_event_id_seq...), que impediriam o SET SCHEMA.
--         Elas são derrubadas, mas SÓ se vazias (com linhas, a migração aborta com mensagem clara: estado inesperado, ninguém
--         escreve no catalog antes do cutover). O que houver no event store do serviço é sobra de teste e é esvaziado (senão o
--         ON CONFLICT da cópia do passo 3 engoliria streams reais). Reaproveitar a tabela do 14 em vez de mover a de public
--         reiniciaria a identity de event_id (um evento novo herdaria o eventcap e as reservas de um id apagado).
--      b) backfill de version = 0 em public (o mesmo da 20261022), ANTES de mover: todo evento ganha o EventCreated v1.
--      c) ALTER TABLE ... SET SCHEMA catalog em event e eventpresenter (dados, índices, constraints e a sequence da identity de
--         event_id vão junto: a identity NÃO é tocada).
--   2) derruba SÓ as FKs que saem de catalog.* para fora (o "Event_creator_id_fkey" -> users). FK de FORA apontando para
--      event/eventpresenter (ex.: public.walletevent/eventcap -> event, num banco sem a 20261025) NÃO é derrubada: a migração
--      ABORTA antes de alterar qualquer coisa, pedindo a 20261025. Nunca toca FK que não envolve catalog (mywallet -> users
--      fica); eventpresenter -> event fica (interna, ON DELETE CASCADE).
--   3) move os streams catalog.event do event store do monólito (public) para o do serviço, preservando global_position
--      (event_store, outbox ainda não publicado, checkpoint, snapshot). Os streams identity.notification ficam em public.
--   4) setval SÓ da sequence de global_position do catalog.event_store (logo depois do maior copiado).
--   5) índice idx_event_creator_id e owners.
-- Roda como supabase_admin, por isso os OWNER TO admin explícitos. lock_timeout de 5 s: o SET SCHEMA pede ACCESS EXCLUSIVE; com
-- o backend parado não há disputa, e se houver a migração falha limpa em vez de travar o banco.
-- Para bancos NOVOS o schema já vem do init-script 14-catalog.sql (a documentação das colunas está lá).

SET client_encoding = 'UTF8';

BEGIN;

SET LOCAL lock_timeout = '5s';

CREATE SCHEMA IF NOT EXISTS catalog;
ALTER SCHEMA catalog OWNER to admin;

-- 0) event store do serviço -------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS catalog.event_store
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

CREATE TABLE IF NOT EXISTS catalog.event_outbox
(
    global_position bigint NOT NULL,
    CONSTRAINT event_outbox_pkey PRIMARY KEY (global_position)
)
WITH (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_threshold = 100)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS catalog.event_snapshot
(
    stream_type text        NOT NULL,
    stream_id   text        NOT NULL,
    version     integer     NOT NULL,
    state       jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT event_snapshot_pkey PRIMARY KEY (stream_type, stream_id)
)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS catalog.projection_checkpoint
(
    projecao    text        NOT NULL,
    stream_type text        NOT NULL,
    stream_id   text        NOT NULL,
    version     integer     NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT projection_checkpoint_pkey PRIMARY KEY (projecao, stream_type, stream_id)
)
TABLESPACE pg_default;

-- 1) tabelas de domínio: public -> catalog --------------------------------------------------------------------------------
DO $mig$
DECLARE
    linhas   bigint;
    motivo   text;
    eventcap regclass := COALESCE(to_regclass('reservation.eventcap'), to_regclass('public.eventcap'));
BEGIN
    IF to_regclass('public.event') IS NULL THEN
        RAISE NOTICE 'public.event não existe: event/eventpresenter já estão no schema catalog (nada a mover)';
        RETURN;
    END IF;

    -- 1a) guarda: nenhuma FK de FORA apontando para event/eventpresenter (ex.: public.walletevent/public.eventcap -> event, de um
    --     banco sem a 20261025). Depois do SET SCHEMA ela cruzaria o schema; derrubá-la em silêncio apagaria uma regra de outro
    --     serviço. Aborta antes de alterar qualquer coisa.
    SELECT string_agg(format('%s (%s -> %s)', c.conname, c.conrelid::regclass, c.confrelid::regclass), ', ') INTO motivo
      FROM pg_constraint c
     WHERE c.contype = 'f'
       AND c.confrelid IN (to_regclass('public.event'), to_regclass('public.eventpresenter'))
       AND c.conrelid NOT IN (COALESCE(to_regclass('public.event'), 0::regclass),
                              COALESCE(to_regclass('public.eventpresenter'), 0::regclass)); -- NOT IN com NULL não casaria nada
    IF motivo IS NOT NULL THEN
        RAISE EXCEPTION 'FK de outra tabela aponta para public.event/eventpresenter: %. Aplique antes a '
            'db/migrations/20261025-reservation-schema.sql (ela derruba essas FKs ao mover eventcap/walletevent para o schema '
            'reservation); nada foi alterado.', motivo;
    END IF;

    -- 1b) sobras VAZIAS do 14-catalog.sql (fatia 2) e do event store de teste
    IF to_regclass('catalog.eventpresenter') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM catalog.eventpresenter' INTO linhas;
        IF linhas > 0 THEN
            RAISE EXCEPTION 'catalog.eventpresenter tem % linha(s) e public.event ainda existe: estado inesperado (ninguém escreve '
                'no schema catalog antes do cutover). Confira de onde vieram antes de migrar; nada foi alterado.', linhas;
        END IF;
    END IF;
    IF to_regclass('catalog.event') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM catalog.event' INTO linhas;
        IF linhas > 0 THEN
            RAISE EXCEPTION 'catalog.event tem % linha(s) e public.event ainda existe: estado inesperado (ninguém escreve no '
                'schema catalog antes do cutover). Confira de onde vieram antes de migrar; nada foi alterado.', linhas;
        END IF;
    END IF;
    DROP TABLE IF EXISTS catalog.eventpresenter;
    DROP TABLE IF EXISTS catalog.event;
    SELECT count(*) INTO linhas FROM catalog.event_store;
    IF linhas > 0 THEN
        RAISE NOTICE 'catalog.event_store tinha % linha(s) de teste (o serviço nunca rodou antes do cutover): esvaziado', linhas;
    END IF;
    TRUNCATE TABLE catalog.event_store, catalog.event_outbox, catalog.event_snapshot, catalog.projection_checkpoint;

    -- 1c) backfill de version = 0 (o mesmo da 20261022), ainda em public: todo evento sai daqui com o EventCreated v1
    ALTER TABLE public.event ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 0;
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
               'capacity', %1$s,
               'buyTimeLimit', %2$s,
               'presenters', COALESCE((SELECT jsonb_agg(p.presenter_name ORDER BY p.presenter_name)
                                         FROM public.eventpresenter p WHERE p.event_id = e.event_id), '[]'::jsonb)),
           e.event_id::text
      FROM public.event e
      %3$s
     WHERE e.version = 0
    ON CONFLICT (stream_type, stream_id, version) DO NOTHING
    RETURNING global_position, stream_id
),
saida AS (
    INSERT INTO public.event_outbox (global_position) SELECT global_position FROM novos
)
UPDATE public.event e SET version = 1 FROM novos n WHERE e.event_id = n.stream_id::integer;
    $sql$,
        CASE WHEN eventcap IS NULL THEN 'NULL::integer' ELSE 'c.capacity' END,
        CASE WHEN eventcap IS NULL THEN 'NULL::timestamp' ELSE 'c.buy_time_limit' END,
        CASE WHEN eventcap IS NULL THEN '' ELSE format('LEFT JOIN %s c ON c.event_id = e.event_id', eventcap) END);

    -- 1d) move as tabelas (a sequence da identity de event_id vai junto e continua de onde estava)
    ALTER TABLE public.event SET SCHEMA catalog;
    IF to_regclass('public.eventpresenter') IS NOT NULL THEN
        ALTER TABLE public.eventpresenter SET SCHEMA catalog;
    END IF;
END
$mig$;

-- eventpresenter num banco que (por algum motivo) não a tinha em public: mesma DDL do 14-catalog.sql
CREATE TABLE IF NOT EXISTS catalog.eventpresenter
(
    event_id integer NOT NULL,
    presenter_name text COLLATE pg_catalog."default" NOT NULL,
    CONSTRAINT eventpresenter_pkey PRIMARY KEY (event_id, presenter_name),
    CONSTRAINT eventpresenter_event_id_fkey FOREIGN KEY (event_id)
        REFERENCES catalog.event (event_id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE CASCADE
)
TABLESPACE pg_default;

-- 2) derruba SÓ as FKs que saem de catalog.* para fora (o "Event_creator_id_fkey" -> users). FK de fora entrando em catalog.*
--    não é derrubada: aborta (a guarda 1a cobre o 1º run; esta cobre um banco em que ela tenha aparecido depois).
DO $fk$
DECLARE
    r record;
    motivo text;
BEGIN
    SELECT string_agg(format('%s (%s -> %s)', c.conname, c.conrelid::regclass, c.confrelid::regclass), ', ') INTO motivo
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace
      JOIN pg_class rt ON rt.oid = c.confrelid JOIN pg_namespace rn ON rn.oid = rt.relnamespace
     WHERE c.contype = 'f' AND n.nspname <> 'catalog' AND rn.nspname = 'catalog';
    IF motivo IS NOT NULL THEN
        RAISE EXCEPTION 'FK de fora aponta para o schema catalog: %. Aplique antes a db/migrations/20261025-reservation-schema.sql '
            '(ou remova a FK conscientemente); nada foi alterado.', motivo;
    END IF;
    FOR r IN
        SELECT c.conname, c.conrelid::regclass AS tabela, c.confrelid::regclass AS alvo
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace
          JOIN pg_class rt ON rt.oid = c.confrelid JOIN pg_namespace rn ON rn.oid = rt.relnamespace
         WHERE c.contype = 'f' AND n.nspname = 'catalog' AND rn.nspname <> 'catalog'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.tabela, r.conname);
        RAISE NOTICE 'FK cross-schema derrubada: % (% -> %)', r.conname, r.tabela, r.alvo;
    END LOOP;
END
$fk$;

-- 3) streams catalog.event: event store do monólito (public) -> do serviço (catalog) ----------------------------------------
-- Ordem: copia (event_store, outbox, checkpoint, snapshot) e só então apaga de public. Sem ON CONFLICT no event_store: uma
-- global_position que já existisse no do serviço é estado inesperado e deve abortar a migração, não sumir em silêncio (na 2ª
-- execução não há o que copiar: os streams já saíram de public).
INSERT INTO catalog.event_store
    (global_position, event_id, stream_type, stream_id, version, event_type, schema_version, occurred_at, payload, metadata, partition_key)
OVERRIDING SYSTEM VALUE
SELECT global_position, event_id, stream_type, stream_id, version, event_type, schema_version, occurred_at, payload, metadata, partition_key
FROM public.event_store WHERE stream_type = 'catalog.event';

INSERT INTO catalog.event_outbox (global_position)
SELECT o.global_position FROM public.event_outbox o
JOIN public.event_store s USING (global_position) WHERE s.stream_type = 'catalog.event'
ON CONFLICT DO NOTHING;

DELETE FROM public.event_outbox o USING public.event_store s
WHERE o.global_position = s.global_position AND s.stream_type = 'catalog.event';

INSERT INTO catalog.projection_checkpoint (projecao, stream_type, stream_id, version, updated_at)
SELECT projecao, stream_type, stream_id, version, updated_at
FROM public.projection_checkpoint WHERE stream_type = 'catalog.event'
ON CONFLICT DO NOTHING;
DELETE FROM public.projection_checkpoint WHERE stream_type = 'catalog.event';

INSERT INTO catalog.event_snapshot (stream_type, stream_id, version, state, created_at)
SELECT stream_type, stream_id, version, state, created_at
FROM public.event_snapshot WHERE stream_type = 'catalog.event'
ON CONFLICT DO NOTHING;
DELETE FROM public.event_snapshot WHERE stream_type = 'catalog.event';

DELETE FROM public.event_store WHERE stream_type = 'catalog.event';

-- 4) SÓ a sequence de global_position do event store do serviço (a identity de event_id não é tocada: max+1 reciclaria um id
--    apagado e o evento novo herdaria eventcap/reservas antigos)
SELECT setval(pg_get_serial_sequence('catalog.event_store', 'global_position'),
              COALESCE((SELECT max(global_position) FROM catalog.event_store), 0) + 1, false);

-- 5) índice de "Meus eventos" (WHERE creator_id = ?) e owners
CREATE INDEX IF NOT EXISTS idx_event_creator_id ON catalog.event (creator_id);

ALTER TABLE catalog.event OWNER to admin;
ALTER TABLE catalog.eventpresenter OWNER to admin;
ALTER TABLE catalog.event_store OWNER to admin;
ALTER TABLE catalog.event_outbox OWNER to admin;
ALTER TABLE catalog.event_snapshot OWNER to admin;
ALTER TABLE catalog.projection_checkpoint OWNER to admin;

COMMENT ON TABLE catalog.eventpresenter IS
    'Apresentadores por evento; pode não ter linhas (lista vazia).';
COMMENT ON TABLE catalog.event_store IS
    'Log imutável de eventos de domínio (fonte de verdade dos streams). UNIQUE (stream_type, stream_id, version) '
    'dá a concorrência otimista; global_position é ordem de alocação, não de commit.';
COMMENT ON TABLE catalog.event_outbox IS
    'global_position dos eventos ainda não publicados no broker. Inserida na transação do append, apagada pelo relay.';
COMMENT ON TABLE catalog.event_snapshot IS
    'Cache descartável do estado de um stream (último snapshot). Não é fonte de verdade.';
COMMENT ON TABLE catalog.projection_checkpoint IS
    'Última version aplicada de cada stream por projeção; avanço atômico = idempotência do consumer.';

COMMIT;
