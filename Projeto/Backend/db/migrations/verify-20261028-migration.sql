-- verify-20261028-migration.sql
-- Harness da migração 20261028-identity-schema.sql (issue #36, casos 9 e 14 do spec). Roda SEMPRE num banco DESCARTÁVEL (o
-- verify-20261028-migration.sh monta os bancos a partir do fixture legacy-fixtures/public-identity-pre-20261028.sql + os init-scripts
-- atuais) — NUNCA em meu_banco: o passo "preparar" insere dados no estado ANTERIOR ao cutover.
--
-- Etapas, escolhidas por variável do psql:
--   psql -v preparar=1   -f verify-20261028-migration.sql  -> popula o estado pré-cutover (users/mywallet/pending em public, streams
--                                                              identity.notification no event store do monólito)
--   psql -v conferir=1   -f verify-20261028-migration.sql  -> confere o estado depois da migração (aplicada 2x pelo .sh)
--   psql -v restaurado=1 -f verify-20261028-migration.sql  -> confere o rollback (DROP SCHEMA identity CASCADE + restore do dump)
--
-- Estado pré-cutover populado: usuários 1..3 (o 4, o maior id, é criado e APAGADO: caso "id apagado não volta"), uma carteira por
-- usuário (trigger), uma verificação pendente, dois streams identity.notification em public (um com outbox pendente, snapshot e
-- checkpoint) e um stream de outro tipo que NÃO pode sair de public. Os valores de referência vão para o schema verif (fora de
-- public e de identity: sobrevive ao rollback).

\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';

\if :{?preparar}
\echo '=== preparar: estado pré-cutover com dados ==='

DROP SCHEMA IF EXISTS verif CASCADE;
CREATE SCHEMA verif;
CREATE TABLE verif.ref (chave text PRIMARY KEY, valor bigint NOT NULL);

DO $$
DECLARE
    u integer; gp bigint; gp_sem_outbox bigint;
BEGIN
    FOR i IN 1..4 LOOP
        INSERT INTO public.users (user_name, email, fone, password, birthdate)
        VALUES ('mig28 usuario ' || i, 'mig28-' || i || '@test.com', '55' || i || '1000000', 'pw' || i, ('1990-01-0' || i)::date)
        RETURNING user_id INTO u;
        IF u <> i THEN RAISE EXCEPTION 'fixture: esperado user_id %, veio %', i, u; END IF;
    END LOOP;
    DELETE FROM public.users WHERE user_id = 4; -- a carteira cai em cascata; o próximo user_id tem de ser 5

    INSERT INTO public.pending_verifications (email, fone, user_name, password_hash, birthdate, verification_code, expires_at)
    VALUES ('pendente28@test.com', '5599000000', 'pendente 28', 'hash', '1991-01-01', '123456', now() + interval '1 hour');

    -- stream com outbox pendente + checkpoint + snapshot
    INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    VALUES (gen_random_uuid(), 'identity.notification', 'n1', 1, 'VerificationCodeRequested', '{"email":"a@test.com"}', 'n1')
    RETURNING global_position INTO gp;
    INSERT INTO public.event_outbox (global_position) VALUES (gp);
    INSERT INTO public.event_snapshot (stream_type, stream_id, version, state) VALUES ('identity.notification', 'n1', 1, '{"n":1}');
    INSERT INTO public.projection_checkpoint (projecao, stream_type, stream_id, version) VALUES ('p', 'identity.notification', 'n1', 1);
    INSERT INTO verif.ref VALUES ('gp_notif_outbox', gp);

    -- stream já publicado (sem outbox)
    INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    VALUES (gen_random_uuid(), 'identity.notification', 'n2', 1, 'PasswordResetMfaRequested', '{"email":"b@test.com"}', 'n2')
    RETURNING global_position INTO gp_sem_outbox;
    INSERT INTO verif.ref VALUES ('gp_notif_publicado', gp_sem_outbox);

    -- outro tipo de stream, com outbox: tem de ficar em public
    INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    VALUES (gen_random_uuid(), 'teste.outro', 'x', 1, 'T', '{}', 'x') RETURNING global_position INTO gp;
    INSERT INTO public.event_outbox (global_position) VALUES (gp);
    INSERT INTO verif.ref VALUES ('gp_outro', gp);

    INSERT INTO verif.ref SELECT 'users', count(*) FROM public.users;
    INSERT INTO verif.ref SELECT 'mywallet', count(*) FROM public.mywallet;
    INSERT INTO verif.ref SELECT 'pending', count(*) FROM public.pending_verifications;
    INSERT INTO verif.ref SELECT 'max_gp_notif', max(global_position) FROM public.event_store WHERE stream_type = 'identity.notification';
END $$;

\echo 'preparado: public.users com 3 usuários (o id 4 foi apagado), 3 carteiras, 1 pendente, 2 streams identity.notification e 1 outro'
\endif

\if :{?conferir}
\echo '=== conferir: estado depois da migração ==='
DO $$
DECLARE
    n bigint; v text; ref_users bigint; ref_wallet bigint; ref_pend bigint; ref_gp_out bigint; ref_gp_pub bigint; ref_outro bigint;
    ref_max bigint; novo integer; nova_id bigint;
BEGIN
    SELECT valor INTO ref_users FROM verif.ref WHERE chave = 'users';
    SELECT valor INTO ref_wallet FROM verif.ref WHERE chave = 'mywallet';
    SELECT valor INTO ref_pend FROM verif.ref WHERE chave = 'pending';
    SELECT valor INTO ref_gp_out FROM verif.ref WHERE chave = 'gp_notif_outbox';
    SELECT valor INTO ref_gp_pub FROM verif.ref WHERE chave = 'gp_notif_publicado';
    SELECT valor INTO ref_outro FROM verif.ref WHERE chave = 'gp_outro';
    SELECT valor INTO ref_max FROM verif.ref WHERE chave = 'max_gp_notif';

    -- caso 9: as tabelas SAÍRAM de public
    IF to_regclass('public.users') IS NOT NULL OR to_regclass('public.mywallet') IS NOT NULL
       OR to_regclass('public.pending_verifications') IS NOT NULL THEN
        RAISE EXCEPTION 'FALHA: users/mywallet/pending_verifications ainda existem em public';
    END IF;
    IF to_regclass('public.users') IS NOT NULL THEN RAISE EXCEPTION 'FALHA: to_regclass(public.users) deveria ser NULL'; END IF;

    -- dados preservados, ids idênticos
    SELECT count(*) INTO n FROM identity.users;
    IF n <> ref_users THEN RAISE EXCEPTION 'FALHA: identity.users tem % usuários, esperado %', n, ref_users; END IF;
    SELECT string_agg(user_id::text, ',' ORDER BY user_id) INTO v FROM identity.users;
    IF v <> '1,2,3' THEN RAISE EXCEPTION 'FALHA: user_ids = % (esperado 1,2,3: ids preservados)', v; END IF;
    SELECT count(*) INTO n FROM identity.mywallet;
    IF n <> ref_wallet THEN RAISE EXCEPTION 'FALHA: identity.mywallet tem % carteiras, esperado %', n, ref_wallet; END IF;
    SELECT count(*) INTO n FROM identity.pending_verifications WHERE email = 'pendente28@test.com';
    IF n <> ref_pend THEN RAISE EXCEPTION 'FALHA: a verificação pendente não veio (%)', n; END IF;

    -- caso 9: FKs (nenhuma cruzando o schema identity; mywallet -> users DENTRO do identity, em cascata)
    SELECT count(*) INTO n FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace ns ON ns.oid = t.relnamespace
      JOIN pg_class r ON r.oid = c.confrelid JOIN pg_namespace rn ON rn.oid = r.relnamespace
     WHERE c.contype = 'f' AND (ns.nspname = 'identity') <> (rn.nspname = 'identity');
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: % FK(s) cruzando o schema identity', n; END IF;
    SELECT count(*) INTO n FROM pg_constraint WHERE conname = 'mywallet_user_id_fkey'
       AND conrelid = 'identity.mywallet'::regclass AND confrelid = 'identity.users'::regclass AND confdeltype = 'c';
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: mywallet_user_id_fkey (ON DELETE CASCADE) não está em identity.mywallet'; END IF;

    -- funções e triggers apontam para identity.*; as de public sumiram
    SELECT count(*) INTO n FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
     WHERE ns.nspname = 'public' AND p.proname IN ('create_wallet_for_user', 'cleanup_expired_verifications', 'trigger_cleanup_on_insert');
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: % função(ões) de usuário ainda em public', n; END IF;
    SELECT tgfoid::regproc::text INTO v FROM pg_trigger WHERE tgname = 'trigger_create_wallet' AND tgrelid = 'identity.users'::regclass;
    IF v IS DISTINCT FROM 'identity.create_wallet_for_user' THEN RAISE EXCEPTION 'FALHA: trigger_create_wallet chama %', v; END IF;
    SELECT tgfoid::regproc::text INTO v FROM pg_trigger
     WHERE tgname = 'trigger_cleanup_on_create_verification' AND tgrelid = 'identity.pending_verifications'::regclass;
    IF v IS DISTINCT FROM 'identity.trigger_cleanup_on_insert' THEN RAISE EXCEPTION 'FALHA: trigger de limpeza chama %', v; END IF;

    -- owners
    SELECT count(*) INTO n FROM pg_tables WHERE schemaname = 'identity' AND tableowner <> 'admin';
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: % tabela(s) de identity com dono diferente de admin', n; END IF;
    SELECT count(*) INTO n FROM pg_tables WHERE schemaname = 'identity';
    IF n <> 7 THEN RAISE EXCEPTION 'FALHA: identity tem % tabelas (esperado 7: 3 de usuário + 4 do event store)', n; END IF;

    -- a identity de user_id continua: o 4 apagado NÃO volta; o trigger cria a carteira em identity.mywallet
    INSERT INTO identity.users (user_name, email, fone, password, birthdate)
    VALUES ('mig28 depois', 'mig28-depois@test.com', '5599111111', 'pw', '1990-01-01') RETURNING user_id INTO novo;
    IF novo <> 5 THEN RAISE EXCEPTION 'FALHA: o próximo user_id foi % (esperado 5: o 4 apagado não volta)', novo; END IF;
    SELECT count(*) INTO n FROM identity.mywallet WHERE user_id = novo;
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: o trigger não criou a carteira em identity.mywallet (%)', n; END IF;
    DELETE FROM identity.users WHERE user_id = novo;
    SELECT count(*) INTO n FROM identity.mywallet WHERE user_id = novo;
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: a carteira não caiu em cascata'; END IF;

    -- o trigger de limpeza roda a função de identity: uma verificação já vencida some no próprio INSERT
    INSERT INTO identity.pending_verifications (email, fone, user_name, password_hash, birthdate, verification_code, expires_at)
    VALUES ('vencida28@test.com', '5599222222', 'vencida 28', 'hash', '1991-01-01', '654321', now() - interval '1 hour');
    SELECT count(*) INTO n FROM identity.pending_verifications WHERE email = 'vencida28@test.com';
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: o trigger de limpeza não apagou a verificação vencida'; END IF;
    SELECT count(*) INTO n FROM identity.pending_verifications WHERE email = 'pendente28@test.com';
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: a limpeza apagou a verificação válida'; END IF;
    INSERT INTO identity.pending_verifications (email, fone, user_name, password_hash, birthdate, verification_code, expires_at)
    VALUES ('nova28@test.com', '5599333333', 'nova 28', 'hash', '1991-01-01', '111111', now() + interval '1 hour')
    RETURNING id INTO nova_id;
    IF nova_id <= 1 THEN RAISE EXCEPTION 'FALHA: a identity de pending_verifications.id reiniciou (%)', nova_id; END IF;
    DELETE FROM identity.pending_verifications WHERE email = 'nova28@test.com';

    -- streams identity.notification movidos com a global_position preservada; o de outro tipo ficou em public
    SELECT count(*) INTO n FROM identity.event_store WHERE stream_type = 'identity.notification';
    IF n <> 2 THEN RAISE EXCEPTION 'FALHA: identity.event_store tem % streams identity.notification (esperado 2)', n; END IF;
    SELECT count(*) INTO n FROM identity.event_store WHERE stream_type = 'identity.notification'
       AND global_position IN (ref_gp_out, ref_gp_pub);
    IF n <> 2 THEN RAISE EXCEPTION 'FALHA: global_position dos streams não foi preservada'; END IF;
    SELECT count(*) INTO n FROM public.event_store WHERE stream_type = 'identity.notification';
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: % linha(s) de identity.notification ficaram em public.event_store', n; END IF;
    SELECT count(*) INTO n FROM identity.event_outbox WHERE global_position = ref_gp_out;
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: o outbox pendente não foi movido para identity'; END IF;
    SELECT count(*) INTO n FROM identity.event_outbox WHERE global_position = ref_gp_pub;
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: o stream já publicado ganhou outbox em identity'; END IF;
    SELECT count(*) INTO n FROM public.event_outbox WHERE global_position = ref_gp_out;
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: o outbox pendente continua em public'; END IF;
    SELECT count(*) INTO n FROM identity.event_snapshot WHERE stream_type = 'identity.notification';
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: snapshot não foi movido (%)', n; END IF;
    SELECT count(*) INTO n FROM identity.projection_checkpoint WHERE stream_type = 'identity.notification';
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: checkpoint não foi movido (%)', n; END IF;
    SELECT count(*) INTO n FROM public.event_snapshot WHERE stream_type = 'identity.notification';
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: snapshot ficou em public'; END IF;
    SELECT count(*) INTO n FROM public.projection_checkpoint WHERE stream_type = 'identity.notification';
    IF n <> 0 THEN RAISE EXCEPTION 'FALHA: checkpoint ficou em public'; END IF;
    SELECT count(*) INTO n FROM public.event_store s JOIN public.event_outbox o USING (global_position)
     WHERE s.stream_type = 'teste.outro' AND s.global_position = ref_outro;
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: o stream de outro tipo (e o outbox dele) tinha de ficar em public'; END IF;

    -- só a sequence de global_position do identity.event_store foi ajustada: o próximo vem depois do maior copiado
    INSERT INTO identity.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    VALUES (gen_random_uuid(), 'teste.seq', 's', 1, 'T', '{}', 's') RETURNING global_position INTO n;
    IF n <= ref_max THEN RAISE EXCEPTION 'FALHA: global_position novo (%) não passa do maior copiado (%)', n, ref_max; END IF;
    DELETE FROM identity.event_store WHERE stream_type = 'teste.seq';

    RAISE NOTICE 'OK conferir: % usuários (ids 1,2,3 preservados; próximo = 5), carteiras, pendente, FKs (caso 9), triggers e streams (global_position preservada)', ref_users;
END $$;
\endif

\if :{?restaurado}
\echo '=== restaurado: rollback (DROP SCHEMA identity CASCADE + restore do dump pre-migração) ==='
DO $$
DECLARE
    n bigint; ref_users bigint; ref_wallet bigint;
BEGIN
    SELECT valor INTO ref_users FROM verif.ref WHERE chave = 'users';
    SELECT valor INTO ref_wallet FROM verif.ref WHERE chave = 'mywallet';
    IF to_regnamespace('identity') IS NOT NULL THEN RAISE EXCEPTION 'FALHA: o schema identity ainda existe'; END IF;
    SELECT count(*) INTO n FROM public.users;
    IF n <> ref_users THEN RAISE EXCEPTION 'FALHA: public.users restaurada com % usuários, esperado %', n, ref_users; END IF;
    SELECT count(*) INTO n FROM public.mywallet;
    IF n <> ref_wallet THEN RAISE EXCEPTION 'FALHA: public.mywallet restaurada com % carteiras, esperado %', n, ref_wallet; END IF;
    SELECT count(*) INTO n FROM public.event_store WHERE stream_type = 'identity.notification';
    IF n <> 2 THEN RAISE EXCEPTION 'FALHA: streams identity.notification restaurados: % (esperado 2)', n; END IF;
    SELECT count(*) INTO n FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
     WHERE ns.nspname = 'public' AND p.proname = 'create_wallet_for_user';
    IF n <> 1 THEN RAISE EXCEPTION 'FALHA: public.create_wallet_for_user não voltou'; END IF;
    RAISE NOTICE 'OK restaurado: count(public.users) = % (igual ao de antes) e to_regnamespace(identity) IS NULL', ref_users;
END $$;
\endif
