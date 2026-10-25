-- verify-20261026-migration.sql
-- Harness da migração 20261026-catalog-schema.sql (issue #35, casos 7, 9 e 10 do spec). Roda SEMPRE num banco DESCARTÁVEL
-- (o verify-20261026-migration.sh cria meu_banco_mig a partir dos init-scripts atuais) — NUNCA em meu_banco: o passo
-- "preparar" é destrutivo e reconstrói o estado ANTERIOR ao cutover.
--
-- Duas etapas, escolhidas por variável do psql:
--   psql -v preparar=1 -f verify-20261026-migration.sql   -> monta o estado pré-cutover COM dados
--   psql -v conferir=1 -f verify-20261026-migration.sql   -> confere o estado depois da migração (aplicada 2x pelo .sh)
--   psql -v restaurado=1 -f verify-20261026-migration.sql -> confere o rollback (DROP SCHEMA catalog CASCADE + restore do dump)
--
-- Estado pré-cutover montado (o de um banco que rodou a fatia 2 da #35):
--   - public.event/public.eventpresenter com a DDL dos antigos 03-event.sql/05-eventpresenter.sql (FK "Event_creator_id_fkey"
--     para users, cascata do eventpresenter, índice idx_event_creator_id);
--   - catalog.event/catalog.eventpresenter VAZIAS do 14-catalog.sql (a armadilha: mesmos nomes de constraint/índice/sequence) e
--     uma SOBRA de teste em catalog.event_store/event_outbox;
--   - eventos 1..5 pela identity; o 5 (o maior id) é apagado (caso 9); o 1 tem stream catalog.event com outbox pendente,
--     snapshot e checkpoint em public; o 2 é pré-ES (version 0) com apresentadores e eventcap (backfill); 3 e 4 com stream;
--   - um stream identity.notification em public (com outbox), que NÃO pode sair de public.
-- Os valores de referência vão para o schema verif (fora de public e de catalog: sobrevive ao rollback).

\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';

\if :{?preparar}
\echo '=== preparar: estado pré-cutover com dados ==='

CREATE TABLE IF NOT EXISTS public.event
(
    event_id integer NOT NULL GENERATED ALWAYS AS IDENTITY ( INCREMENT 1 START 1 MINVALUE 1 MAXVALUE 2147483647 CACHE 1 ),
    creator_id integer NOT NULL,
    event_name text COLLATE pg_catalog."default" NOT NULL,
    ead boolean NOT NULL DEFAULT false,
    address text COLLATE pg_catalog."default",
    event_date timestamp without time zone NOT NULL,
    description text COLLATE pg_catalog."default" NOT NULL,
    image_data bytea,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version integer NOT NULL DEFAULT 0,
    CONSTRAINT "Event_pkey" PRIMARY KEY (event_id),
    CONSTRAINT "Event_event_name_key" UNIQUE (event_name),
    CONSTRAINT "Event_creator_id_fkey" FOREIGN KEY (creator_id) REFERENCES public.users (user_id)
);
CREATE INDEX IF NOT EXISTS idx_event_creator_id ON public.event (creator_id);
CREATE TABLE IF NOT EXISTS public.eventpresenter
(
    event_id integer NOT NULL,
    presenter_name text COLLATE pg_catalog."default" NOT NULL,
    CONSTRAINT eventpresenter_pkey PRIMARY KEY (event_id, presenter_name),
    CONSTRAINT eventpresenter_event_id_fkey FOREIGN KEY (event_id) REFERENCES public.event (event_id) ON DELETE CASCADE
);

DROP SCHEMA IF EXISTS verif CASCADE;
CREATE SCHEMA verif;
CREATE TABLE verif.ref (chave text PRIMARY KEY, valor bigint NOT NULL);

DO $$
DECLARE
    u1 integer; u2 integer; e integer; gp bigint;
BEGIN
    INSERT INTO public.users (user_name, email, fone, password, birthdate)
    VALUES ('mig criador', 'mig-criador@test.com', '5501000000', 'pw', '1990-01-01') RETURNING user_id INTO u1;
    INSERT INTO public.users (user_name, email, fone, password, birthdate)
    VALUES ('mig outro', 'mig-outro@test.com', '5502000000', 'pw', '1990-01-01') RETURNING user_id INTO u2;

    FOR i IN 1..5 LOOP
        INSERT INTO public.event (creator_id, event_name, ead, event_date, description, version)
        VALUES (CASE WHEN i % 2 = 0 THEN u2 ELSE u1 END, 'Mig evento ' || i, true, now() + interval '30 days', 'desc ' || i,
                CASE WHEN i = 2 THEN 0 ELSE 1 END)
        RETURNING event_id INTO e;
        IF e <> i THEN RAISE EXCEPTION 'fixture: esperado event_id %, veio %', i, e; END IF;
        IF i <> 2 THEN
            INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
            VALUES (gen_random_uuid(), 'catalog.event', i::text, 1, 'EventCreated',
                    jsonb_build_object('eventId', i, 'eventName', 'Mig evento ' || i), i::text)
            RETURNING global_position INTO gp;
            IF i = 1 THEN
                INSERT INTO public.event_outbox (global_position) VALUES (gp); -- ainda não publicado: tem de ir para o catalog
                INSERT INTO verif.ref VALUES ('gp_evento1', gp);
            END IF;
        END IF;
    END LOOP;
    INSERT INTO public.eventpresenter (event_id, presenter_name) VALUES (2, 'Bia'), (2, 'Ana');
    INSERT INTO reservation.eventcap (event_id, capacity, quant) VALUES (2, 7, 7) ON CONFLICT (event_id) DO NOTHING;
    INSERT INTO public.event_snapshot (stream_type, stream_id, version, state) VALUES ('catalog.event', '1', 1, '{"n":1}');
    INSERT INTO public.projection_checkpoint (projecao, stream_type, stream_id, version) VALUES ('p', 'catalog.event', '1', 1);

    -- caso 9: o maior id é apagado antes da migração
    DELETE FROM public.event WHERE event_id = 5;
    DELETE FROM public.event_store WHERE stream_type = 'catalog.event' AND stream_id = '5';

    -- o stream do identity fica em public
    INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    VALUES (gen_random_uuid(), 'identity.notification', 'x', 1, 'VerificationCodeRequested', '{}', 'x')
    RETURNING global_position INTO gp;
    INSERT INTO public.event_outbox (global_position) VALUES (gp);
    INSERT INTO verif.ref VALUES ('gp_identity', gp);

    -- sobra de teste no event store do serviço (o 14-catalog.sql já criou as tabelas vazias): global_position colidindo
    INSERT INTO catalog.event_store (global_position, event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    OVERRIDING SYSTEM VALUE
    SELECT (SELECT valor FROM verif.ref WHERE chave = 'gp_evento1'), gen_random_uuid(), 'teste.sobra', 's', 1, 'Sobra', '{}', 's';
    INSERT INTO catalog.event_outbox (global_position) SELECT valor FROM verif.ref WHERE chave = 'gp_evento1';

    INSERT INTO verif.ref SELECT 'eventos', count(*) FROM public.event;
    INSERT INTO verif.ref SELECT 'apresentadores', count(*) FROM public.eventpresenter;
    INSERT INTO verif.ref SELECT 'max_gp_catalog', max(global_position) FROM public.event_store WHERE stream_type = 'catalog.event';
END $$;

\echo 'preparado: public.event com 4 eventos (o id 5 foi apagado), catalog.event vazia (14) e sobra em catalog.event_store'
\endif

\if :{?conferir}
\echo '=== conferir: estado depois da migração ==='
DO $$
DECLARE
    n bigint; v text; ref_eventos bigint; ref_gp bigint; ref_ident bigint; novo integer;
BEGIN
    SELECT valor INTO ref_eventos FROM verif.ref WHERE chave = 'eventos';
    SELECT valor INTO ref_gp FROM verif.ref WHERE chave = 'gp_evento1';
    SELECT valor INTO ref_ident FROM verif.ref WHERE chave = 'gp_identity';

    IF to_regclass('public.event') IS NOT NULL OR to_regclass('public.eventpresenter') IS NOT NULL THEN
        RAISE EXCEPTION 'FALHA: public.event/eventpresenter ainda existem';
    END IF;
    SELECT count(*) INTO n FROM catalog.event;
    IF n <> ref_eventos THEN RAISE EXCEPTION 'FALHA: catalog.event tem % eventos, esperado % (dados preservados)', n, ref_eventos; END IF;
    SELECT count(*) INTO n FROM catalog.eventpresenter WHERE event_id = 2;
    IF n <> 2 THEN RAISE EXCEPTION 'FALHA: apresentadores do evento 2 não vieram (%)', n; END IF;
    SELECT count(*) INTO n FROM catalog.event WHERE version = 0;
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: % evento(s) pré-ES depois do backfill', n; END IF;

    -- backfill do evento pré-ES: EventCreated v1 com capacidade e apresentadores, no event store do SERVIÇO, com outbox
    SELECT payload->>'capacity' || '|' || (payload->'presenters')::text INTO v FROM catalog.event_store
     WHERE stream_type = 'catalog.event' AND stream_id = '2' AND version = 1 AND event_type = 'EventCreated';
    IF v IS DISTINCT FROM '7|["Ana", "Bia"]' THEN RAISE EXCEPTION 'FALHA: EventCreated do backfill do evento 2 = %', v; END IF;
    SELECT count(*) INTO n FROM catalog.event_outbox o JOIN catalog.event_store s USING (global_position)
     WHERE s.stream_type = 'catalog.event' AND s.stream_id IN ('1', '2');
    IF n <> 2 THEN RAISE EXCEPTION 'FALHA: outbox pendente do evento 1 + o do backfill do 2 deveriam estar no catalog (%)', n; END IF;

    -- streams movidos com a global_position preservada; a sobra de teste sumiu; o identity ficou em public
    SELECT count(*) INTO n FROM catalog.event_store WHERE stream_type = 'catalog.event' AND stream_id = '1' AND global_position = ref_gp;
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: o EventCreated do evento 1 não manteve a global_position %', ref_gp; END IF;
    SELECT count(*) INTO n FROM catalog.event_store WHERE stream_type = 'catalog.event';
    IF n <> ref_eventos THEN RAISE EXCEPTION 'FALHA: catalog.event_store tem % streams de evento, esperado %', n, ref_eventos; END IF;
    SELECT count(*) INTO n FROM catalog.event_store WHERE stream_type <> 'catalog.event';
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: sobra de teste continua em catalog.event_store (%)', n; END IF;
    SELECT count(*) INTO n FROM public.event_store WHERE stream_type = 'catalog.event';
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: % linha(s) de catalog.event ficaram em public.event_store', n; END IF;
    SELECT count(*) INTO n FROM public.event_store s JOIN public.event_outbox o USING (global_position)
     WHERE s.stream_type = 'identity.notification' AND s.global_position = ref_ident;
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: o stream identity.notification (e o outbox dele) tinha de ficar em public'; END IF;
    SELECT count(*) INTO n FROM catalog.event_snapshot WHERE stream_type = 'catalog.event';
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: snapshot não foi movido (%)', n; END IF;
    SELECT count(*) INTO n FROM catalog.projection_checkpoint WHERE stream_type = 'catalog.event';
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: checkpoint não foi movido (%)', n; END IF;

    -- caso 7: FKs
    SELECT count(*) INTO n FROM pg_constraint WHERE conname = 'mywallet_user_id_fkey'
       AND conrelid = 'public.mywallet'::regclass AND confrelid = 'public.users'::regclass;
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: mywallet_user_id_fkey sumiu de public.mywallet'; END IF;
    SELECT count(*) INTO n FROM pg_constraint WHERE conname = 'eventpresenter_event_id_fkey'
       AND conrelid = 'catalog.eventpresenter'::regclass AND confrelid = 'catalog.event'::regclass AND confdeltype = 'c';
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: eventpresenter_event_id_fkey (ON DELETE CASCADE) não está em catalog.eventpresenter'; END IF;
    SELECT count(*) INTO n FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace ns ON ns.oid = t.relnamespace
      JOIN pg_class r ON r.oid = c.confrelid JOIN pg_namespace rn ON rn.oid = r.relnamespace
     WHERE c.contype = 'f' AND (ns.nspname = 'catalog') <> (rn.nspname = 'catalog');
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: % FK(s) cruzando o schema catalog', n; END IF;
    IF to_regclass('catalog.idx_event_creator_id') IS NULL THEN RAISE EXCEPTION 'FALHA: índice idx_event_creator_id'; END IF;
    SELECT tableowner INTO v FROM pg_tables WHERE schemaname = 'catalog' AND tablename = 'event';
    IF v <> 'admin' THEN RAISE EXCEPTION 'FALHA: dono de catalog.event é %', v; END IF;

    -- caso 9: a identity continua da sequence (o id 5 apagado NÃO é reaproveitado)
    INSERT INTO catalog.event (creator_id, event_name, ead, event_date, description, version)
    VALUES (1, 'Mig evento depois', true, now() + interval '30 days', 'depois', 1) RETURNING event_id INTO novo;
    IF novo <> 6 THEN RAISE EXCEPTION 'FALHA: o próximo event_id foi % (esperado 6: o 5 apagado não volta)', novo; END IF;
    DELETE FROM catalog.event WHERE event_id = novo;

    -- o global_position novo do catalog.event_store vem depois do maior copiado
    INSERT INTO catalog.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    VALUES (gen_random_uuid(), 'teste.seq', 's', 1, 'T', '{}', 's') RETURNING global_position INTO n;
    IF n <= (SELECT max(global_position) FROM catalog.event_store WHERE stream_type = 'catalog.event') THEN
        RAISE EXCEPTION 'FALHA: global_position novo (%) não passa do maior copiado', n;
    END IF;
    DELETE FROM catalog.event_store WHERE stream_type = 'teste.seq';

    RAISE NOTICE 'OK conferir: % eventos em catalog.event, streams movidos (global_position %), FKs (caso 7) e identity (caso 9)',
        ref_eventos, ref_gp;
END $$;
\endif

\if :{?restaurado}
\echo '=== restaurado: rollback (DROP SCHEMA catalog CASCADE + restore do dump pre-migração) ==='
DO $$
DECLARE
    n bigint; ref_eventos bigint;
BEGIN
    SELECT valor INTO ref_eventos FROM verif.ref WHERE chave = 'eventos';
    IF to_regnamespace('catalog') IS NOT NULL THEN RAISE EXCEPTION 'FALHA: o schema catalog ainda existe'; END IF;
    SELECT count(*) INTO n FROM public.event;
    IF n <> ref_eventos THEN RAISE EXCEPTION 'FALHA: public.event restaurada com % eventos, esperado %', n, ref_eventos; END IF;
    SELECT count(*) INTO n FROM public.event_store WHERE stream_type = 'catalog.event';
    IF n <> ref_eventos - 1 THEN RAISE EXCEPTION 'FALHA: streams catalog.event restaurados: % (esperado %)', n, ref_eventos - 1; END IF;
    RAISE NOTICE 'OK restaurado (caso 10): count(public.event) = % (igual ao de antes) e to_regnamespace(catalog) IS NULL',
        ref_eventos;
END $$;
\endif
