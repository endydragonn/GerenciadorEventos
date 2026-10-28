-- Migração: schema do serviço identity (issue #36, Fase 7: cutover da quarta extração). Idempotente. Aplicável a quente num banco
-- já existente, SEM recriar o volume, com o gerenciador-backend e o gerenciador-identity PARADOS (o reservation, o catalog e o
-- notification podem ficar de pé: nenhum lê users/mywallet/pending_verifications nem o event store do identity). ANTES, o backup
-- (válido só até reabrir o tráfego; --clean --if-exists para o restore poder rodar por cima do public que continua existindo):
--   docker exec gerenciador-db pg_dump -h localhost -U admin --clean --if-exists -n public meu_banco > <fora do repo>/meu_banco-public-<data>.sql
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 \
--     < Projeto/Backend/db/migrations/20261028-identity-schema.sql
-- Rollback (só antes de reabrir o tráfego; tópico e groups ficam fora; backend e identity parados), o mesmo roteiro que o
-- db/migrations/verify-20261028-migration.sh prova num banco descartável:
--   docker exec gerenciador-db psql -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 -c 'DROP SCHEMA identity CASCADE;'
--   docker exec -i gerenciador-db psql -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 < <fora do repo>/meu_banco-public-<data>.sql
-- (como supabase_admin: o dump recria o schema public com os ALTER DEFAULT PRIVILEGES das roles do Supabase, que o admin não pode
-- aplicar; as tabelas voltam com dono admin). Sem script reverso.
--
-- Pré-requisitos: a 20261025 (schema reservation) e a 20261026 (schema catalog) já aplicadas: sem elas há FK de walletevent/event
-- para users e a migração ABORTA (nada é alterado).
--
-- O que faz (cada passo só age se ainda houver o que fazer, então roda 2x sem efeito colateral):
--   0) cria o schema identity (dono admin) e o event store DO SERVIÇO (event_store, event_outbox, event_snapshot,
--      projection_checkpoint) com IF NOT EXISTS, como a 20261025/20261026 fizeram.
--   1) SÓ se public.users ainda existe (banco não migrado):
--      a) guarda de FK: nenhuma FK de FORA apontando para users/mywallet/pending_verifications (ex.: public.walletevent/event de um
--         banco sem a 20261025/20261026); depois do SET SCHEMA ela cruzaria o schema, e derrubá-la em silêncio apagaria uma regra de
--         outro serviço. Aborta antes de alterar qualquer coisa;
--      b) guarda das sobras da fatia 2: o 15-identity.sql pode ter criado o schema identity com as 3 tabelas de usuário E as 4 do event
--         store, vazias (nada escreve nelas antes do cutover). Se QUALQUER uma das 7 tiver linha, ABORTA (estado inesperado; nunca
--         recicla user_id nem mistura streams). Vazias: as 3 de usuário são derrubadas (os nomes de constraint/índice/sequence
--         colidiriam com o SET SCHEMA; reaproveitar a tabela do 15 em vez de mover a de public reiniciaria a identity de user_id) e as
--         4 do event store são reaproveitadas;
--      c) ALTER TABLE ... SET SCHEMA identity em users, mywallet e pending_verifications (dados, índices, constraints e as sequences
--         da identity de user_id / id vão junto: a identity NÃO é tocada, sem setval).
--   2) funções e triggers: os triggers movidos continuam chamando as funções public.* (que escrevem em public.mywallet /
--      public.pending_verifications, que não existem mais). Recria as 3 funções em identity (corpo qualificado), aponta os 2 triggers
--      para elas e derruba as funções de public.
--   3) move os streams identity.notification do event store do monólito (public) para o do serviço (identity), preservando
--      global_position (event_store, outbox ainda não publicado, checkpoint, snapshot). Os demais streams ficam em public.
--   4) setval SÓ da sequence de global_position do identity.event_store (logo depois do maior copiado).
--   5) owners.
-- Roda como supabase_admin, por isso os OWNER TO admin explícitos. lock_timeout de 5 s: o SET SCHEMA pede ACCESS EXCLUSIVE; com
-- o backend parado não há disputa, e se houver a migração falha limpa em vez de travar o banco.
-- Para bancos NOVOS o schema já vem do init-script 15-identity.sql (a documentação das colunas está lá).

SET client_encoding = 'UTF8';

BEGIN;

SET LOCAL lock_timeout = '5s';

CREATE SCHEMA IF NOT EXISTS identity;
ALTER SCHEMA identity OWNER to admin;

-- 0) event store do serviço -------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS identity.event_store
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

CREATE TABLE IF NOT EXISTS identity.event_outbox
(
    global_position bigint NOT NULL,
    CONSTRAINT event_outbox_pkey PRIMARY KEY (global_position)
)
WITH (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_threshold = 100)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS identity.event_snapshot
(
    stream_type text        NOT NULL,
    stream_id   text        NOT NULL,
    version     integer     NOT NULL,
    state       jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT event_snapshot_pkey PRIMARY KEY (stream_type, stream_id)
)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS identity.projection_checkpoint
(
    projecao    text        NOT NULL,
    stream_type text        NOT NULL,
    stream_id   text        NOT NULL,
    version     integer     NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT projection_checkpoint_pkey PRIMARY KEY (projecao, stream_type, stream_id)
)
TABLESPACE pg_default;

-- 1) tabelas de usuário: public -> identity -------------------------------------------------------------------------------
DO $mig$
DECLARE
    alvos  oid[] := array_remove(ARRAY[to_regclass('public.users')::oid, to_regclass('public.mywallet')::oid,
                                       to_regclass('public.pending_verifications')::oid], NULL);
    motivo text;
    sobras text;
    n      bigint;
    t      text;
BEGIN
    IF to_regclass('public.users') IS NULL THEN
        RAISE NOTICE 'public.users não existe: users/mywallet/pending_verifications já estão no schema identity (nada a mover)';
        RETURN;
    END IF;

    -- 1a) nenhuma FK de FORA para as 3 tabelas (mywallet -> users é interna e fica)
    SELECT string_agg(format('%s (%s -> %s)', c.conname, c.conrelid::regclass, c.confrelid::regclass), ', ') INTO motivo
      FROM pg_constraint c
     WHERE c.contype = 'f' AND c.confrelid = ANY (alvos) AND NOT (c.conrelid = ANY (alvos));
    IF motivo IS NOT NULL THEN
        RAISE EXCEPTION 'FK de outra tabela aponta para public.users/mywallet/pending_verifications: %. Aplique antes a '
            'db/migrations/20261025-reservation-schema.sql e a 20261026-catalog-schema.sql (elas derrubam essas FKs ao mover '
            'walletevent/event para os schemas dos serviços); nada foi alterado.', motivo;
    END IF;

    -- 1b) as 7 tabelas identity.* que o 15-identity.sql (fatia 2) possa ter criado: todas vazias, ou aborta
    FOREACH t IN ARRAY ARRAY['users', 'mywallet', 'pending_verifications', 'event_store', 'event_outbox', 'event_snapshot',
                             'projection_checkpoint']
    LOOP
        IF to_regclass('identity.' || t) IS NOT NULL THEN
            EXECUTE format('SELECT count(*) FROM identity.%I', t) INTO n;
            IF n > 0 THEN
                sobras := concat_ws(', ', sobras, format('identity.%s (%s linha(s))', t, n));
            END IF;
        END IF;
    END LOOP;
    IF sobras IS NOT NULL THEN
        RAISE EXCEPTION 'Tabela(s) do schema identity com dados enquanto public.users ainda existe: %. Estado inesperado (ninguém '
            'escreve no schema identity antes do cutover; reciclar user_id misturaria usuários). Confira de onde vieram antes de '
            'migrar; nada foi alterado.', sobras;
    END IF;
    DROP TABLE IF EXISTS identity.mywallet;
    DROP TABLE IF EXISTS identity.pending_verifications;
    DROP TABLE IF EXISTS identity.users;
    DROP FUNCTION IF EXISTS identity.create_wallet_for_user();
    DROP FUNCTION IF EXISTS identity.cleanup_expired_verifications();
    DROP FUNCTION IF EXISTS identity.trigger_cleanup_on_insert();

    -- 1c) move as tabelas (a identity de user_id e a de pending_verifications.id vão junto e continuam de onde estavam)
    ALTER TABLE public.users SET SCHEMA identity;
    IF to_regclass('public.mywallet') IS NOT NULL THEN
        ALTER TABLE public.mywallet SET SCHEMA identity;
    END IF;
    IF to_regclass('public.pending_verifications') IS NOT NULL THEN
        ALTER TABLE public.pending_verifications SET SCHEMA identity;
    END IF;
END
$mig$;

-- Banco que não tinha mywallet / pending_verifications em public (não deveria acontecer): mesma DDL do 15-identity.sql
DO $chk$
BEGIN
    IF to_regclass('identity.users') IS NULL THEN
        RAISE EXCEPTION 'Nem public.users nem identity.users existem: aplique o db/init-scripts/15-identity.sql (banco novo) antes.';
    END IF;
END
$chk$;

CREATE TABLE IF NOT EXISTS identity.mywallet
(
    user_id integer NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "MyWallet_pkey" PRIMARY KEY (user_id),
    CONSTRAINT mywallet_user_id_fkey FOREIGN KEY (user_id)
        REFERENCES identity.users (user_id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE CASCADE
)
TABLESPACE pg_default;

CREATE TABLE IF NOT EXISTS identity.pending_verifications
(
    id bigint NOT NULL GENERATED ALWAYS AS IDENTITY ( INCREMENT 1 START 1 MINVALUE 1 MAXVALUE 9223372036854775807 CACHE 1 ),
    email character varying(255) COLLATE pg_catalog."default" NOT NULL,
    fone character varying(20) COLLATE pg_catalog."default" NOT NULL,
    user_name character varying(255) COLLATE pg_catalog."default" NOT NULL,
    password_hash character varying(255) COLLATE pg_catalog."default" NOT NULL,
    birthdate date NOT NULL,
    verification_code character varying(6) COLLATE pg_catalog."default" NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 5,
    CONSTRAINT pending_verifications_pkey PRIMARY KEY (id),
    CONSTRAINT pending_verifications_email_key UNIQUE (email)
)
TABLESPACE pg_default;

CREATE INDEX IF NOT EXISTS idx_pending_verifications_email
    ON identity.pending_verifications USING btree (email ASC NULLS LAST) TABLESPACE pg_default;
CREATE INDEX IF NOT EXISTS idx_pending_verifications_code
    ON identity.pending_verifications USING btree (verification_code ASC NULLS LAST) TABLESPACE pg_default;
CREATE INDEX IF NOT EXISTS idx_pending_verifications_expires_at
    ON identity.pending_verifications USING btree (expires_at ASC NULLS LAST) TABLESPACE pg_default;

-- 2) funções e triggers apontando para identity.* (os movidos ainda chamavam public.*) -------------------------------------
CREATE OR REPLACE FUNCTION identity.create_wallet_for_user()
    RETURNS trigger
    LANGUAGE 'plpgsql'
    COST 100
    VOLATILE NOT LEAKPROOF
AS $BODY$
BEGIN
    INSERT INTO identity.mywallet (user_id)
    VALUES (NEW.user_id);
    RETURN NEW;
END;
$BODY$;

CREATE OR REPLACE FUNCTION identity.cleanup_expired_verifications()
    RETURNS void
    LANGUAGE 'plpgsql'
    COST 100
    VOLATILE PARALLEL UNSAFE
AS $BODY$
DECLARE
    v_deleted_count INT;
BEGIN
    DELETE FROM identity.pending_verifications
    WHERE expires_at < CURRENT_TIMESTAMP;

    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;

    IF v_deleted_count > 0 THEN
        RAISE NOTICE 'Limpeza de verificações expiradas: % registros deletados', v_deleted_count;
    END IF;
END;
$BODY$;

CREATE OR REPLACE FUNCTION identity.trigger_cleanup_on_insert()
    RETURNS trigger
    LANGUAGE 'plpgsql'
    COST 100
    VOLATILE NOT LEAKPROOF
AS $BODY$
BEGIN
    -- Executar limpeza de registros expirados a cada novo registro criado
    PERFORM identity.cleanup_expired_verifications();
    RETURN NEW;
END;
$BODY$;

CREATE OR REPLACE TRIGGER trigger_create_wallet
    AFTER INSERT
    ON identity.users
    FOR EACH ROW
    EXECUTE FUNCTION identity.create_wallet_for_user();

CREATE OR REPLACE TRIGGER trigger_cleanup_on_create_verification
    AFTER INSERT
    ON identity.pending_verifications
    FOR EACH STATEMENT
    EXECUTE FUNCTION identity.trigger_cleanup_on_insert();

DROP FUNCTION IF EXISTS public.create_wallet_for_user();
DROP FUNCTION IF EXISTS public.cleanup_expired_verifications();
DROP FUNCTION IF EXISTS public.trigger_cleanup_on_insert();

-- 3) streams identity.notification: event store do monólito (public) -> do serviço (identity) ----------------------------------
-- Ordem: copia (event_store, outbox, checkpoint, snapshot) e só então apaga de public. Sem ON CONFLICT no event_store: uma
-- global_position que já existisse no do serviço é estado inesperado e deve abortar a migração, não sumir em silêncio (na 2ª
-- execução não há o que copiar: os streams já saíram de public).
INSERT INTO identity.event_store
    (global_position, event_id, stream_type, stream_id, version, event_type, schema_version, occurred_at, payload, metadata, partition_key)
OVERRIDING SYSTEM VALUE
SELECT global_position, event_id, stream_type, stream_id, version, event_type, schema_version, occurred_at, payload, metadata, partition_key
FROM public.event_store WHERE stream_type = 'identity.notification';

INSERT INTO identity.event_outbox (global_position)
SELECT o.global_position FROM public.event_outbox o
JOIN public.event_store s USING (global_position) WHERE s.stream_type = 'identity.notification'
ON CONFLICT DO NOTHING;

DELETE FROM public.event_outbox o USING public.event_store s
WHERE o.global_position = s.global_position AND s.stream_type = 'identity.notification';

INSERT INTO identity.projection_checkpoint (projecao, stream_type, stream_id, version, updated_at)
SELECT projecao, stream_type, stream_id, version, updated_at
FROM public.projection_checkpoint WHERE stream_type = 'identity.notification'
ON CONFLICT DO NOTHING;
DELETE FROM public.projection_checkpoint WHERE stream_type = 'identity.notification';

INSERT INTO identity.event_snapshot (stream_type, stream_id, version, state, created_at)
SELECT stream_type, stream_id, version, state, created_at
FROM public.event_snapshot WHERE stream_type = 'identity.notification'
ON CONFLICT DO NOTHING;
DELETE FROM public.event_snapshot WHERE stream_type = 'identity.notification';

DELETE FROM public.event_store WHERE stream_type = 'identity.notification';

-- 4) SÓ a sequence de global_position do event store do serviço (a identity de user_id NÃO é tocada: max+1 reciclaria um id
--    apagado e o usuário novo herdaria eventos e reservas de outro)
SELECT setval(pg_get_serial_sequence('identity.event_store', 'global_position'),
              COALESCE((SELECT max(global_position) FROM identity.event_store), 0) + 1, false);

-- 5) owners (a migração roda como supabase_admin)
ALTER TABLE identity.users OWNER to admin;
ALTER TABLE identity.mywallet OWNER to admin;
ALTER TABLE identity.pending_verifications OWNER to admin;
ALTER TABLE identity.event_store OWNER to admin;
ALTER TABLE identity.event_outbox OWNER to admin;
ALTER TABLE identity.event_snapshot OWNER to admin;
ALTER TABLE identity.projection_checkpoint OWNER to admin;
ALTER FUNCTION identity.create_wallet_for_user() OWNER TO admin;
ALTER FUNCTION identity.cleanup_expired_verifications() OWNER TO admin;
ALTER FUNCTION identity.trigger_cleanup_on_insert() OWNER TO admin;

COMMENT ON TABLE identity.event_store IS
    'Log imutável de eventos de domínio do serviço identity (identity.notification, congelado, e identity.user). UNIQUE '
    '(stream_type, stream_id, version) dá a concorrência otimista; global_position é ordem de alocação, não de commit.';
COMMENT ON TABLE identity.event_outbox IS
    'global_position dos eventos ainda não publicados no broker. Inserida na transação do append, apagada pelo relay.';

COMMIT;
