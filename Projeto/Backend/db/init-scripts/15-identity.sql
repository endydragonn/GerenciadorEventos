-- Schema: identity  |  users, mywallet, pending_verifications + event store do serviço
-- Quarta extração de microsserviço (issue #36, Fase 7): o serviço identity tem schema PRÓPRIO e é o único que lê e escreve
-- estas tabelas. Consolida o 02-users.sql, o 06-mywallet.sql, o 08-pending-verifications.sql e as funções de usuário do
-- 01-functions.sql (mesmas colunas, constraints e índices; funções e triggers apontam para "identity.") + o event store do
-- serviço (mesmo desenho do 11-event-store.sql / 13-reservation.sql / 14-catalog.sql). O CRUD NÃO é event sourcing: o event store
-- guarda o stream identity.notification (pedido de e-mail; contrato e tópico congelados, #32) e o stream identity.user (eventos de
-- ciclo de vida, payload só com ids). Todas as queries do serviço qualificam com "identity." (o Supavisor em transaction mode
-- proíbe search_path).
--
-- FATIA 2 da #36: este script CONVIVE com o 02/06/08 (o monólito ainda usa public.users até o cutover, fatia 3). No cutover, o
-- 02/06/08 saem do supabase-app-init.sql e, num banco EXISTENTE, quem move os dados é a migração 20261028-identity-schema.sql
-- (ela derruba as tabelas identity.* que ESTE script tenha criado, SÓ se vazias: nunca recicla user_id).
--
-- SEM FK cross-schema: a única FK é interna (mywallet -> users, ON DELETE CASCADE; é por ela e pelo trigger 1:1 que mywallet mora
-- no schema identity junto com users). event.creator_id (catalog) e walletevent.user_id (reservation) são inteiros soltos.
--
-- Idempotente (sem DROP de tabela, tudo IF NOT EXISTS / CREATE OR REPLACE).

SET client_encoding = 'UTF8';

CREATE SCHEMA IF NOT EXISTS identity;
ALTER SCHEMA identity OWNER to admin;

-- ===== users =====
CREATE TABLE IF NOT EXISTS identity.users
(
    user_id integer NOT NULL GENERATED ALWAYS AS IDENTITY ( INCREMENT 1 START 1 MINVALUE 1 MAXVALUE 2147483647 CACHE 1 ),
    user_name text COLLATE pg_catalog."default" NOT NULL,
    email text COLLATE pg_catalog."default" NOT NULL,
    fone text COLLATE pg_catalog."default" NOT NULL,
    password text COLLATE pg_catalog."default" NOT NULL,
    birthdate date NOT NULL,
    admin boolean NOT NULL DEFAULT false,
    Host boolean NOT NULL DEFAULT false,
    isActive boolean NOT NULL DEFAULT true,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    password_reset_mfa_code text,
    password_reset_mfa_expiration timestamp without time zone,
    CONSTRAINT "User_id" PRIMARY KEY (user_id),
    CONSTRAINT "Users_email_key" UNIQUE (email),
    CONSTRAINT "Users_fone_key" UNIQUE (fone),
    CONSTRAINT "Users_user_name_key" UNIQUE (user_name)
)
TABLESPACE pg_default;

ALTER TABLE IF EXISTS identity.users OWNER to admin;

-- ===== mywallet =====
-- Depende de users.user_id via FK. A carteira nasce no MESMO INSERT do usuário (trigger abaixo).
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

ALTER TABLE IF EXISTS identity.mywallet OWNER to admin;

-- Function: identity.create_wallet_for_user() + trigger em identity.users
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

ALTER FUNCTION identity.create_wallet_for_user()
    OWNER TO admin;

CREATE OR REPLACE TRIGGER trigger_create_wallet
    AFTER INSERT
    ON identity.users
    FOR EACH ROW
    EXECUTE FUNCTION identity.create_wallet_for_user();

-- ===== pending_verifications =====
-- Dados temporários de usuários aguardando verificação de e-mail (cadastro em duas fases).
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

ALTER TABLE IF EXISTS identity.pending_verifications OWNER to admin;

CREATE INDEX IF NOT EXISTS idx_pending_verifications_email
    ON identity.pending_verifications USING btree (email ASC NULLS LAST) TABLESPACE pg_default;

CREATE INDEX IF NOT EXISTS idx_pending_verifications_code
    ON identity.pending_verifications USING btree (verification_code ASC NULLS LAST) TABLESPACE pg_default;

CREATE INDEX IF NOT EXISTS idx_pending_verifications_expires_at
    ON identity.pending_verifications USING btree (expires_at ASC NULLS LAST) TABLESPACE pg_default;

-- Function: identity.cleanup_expired_verifications() (remove verificações vencidas) e o trigger que a chama a cada INSERT
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

ALTER FUNCTION identity.cleanup_expired_verifications()
    OWNER to admin;

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

ALTER FUNCTION identity.trigger_cleanup_on_insert()
    OWNER to admin;

CREATE OR REPLACE TRIGGER trigger_cleanup_on_create_verification
    AFTER INSERT
    ON identity.pending_verifications
    FOR EACH STATEMENT
    EXECUTE FUNCTION identity.trigger_cleanup_on_insert();

-- ===== infra de event sourcing do serviço (mesmo desenho do 11-event-store.sql) =====
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

ALTER TABLE IF EXISTS identity.event_store OWNER to admin;
ALTER TABLE IF EXISTS identity.event_outbox OWNER to admin;
ALTER TABLE IF EXISTS identity.event_snapshot OWNER to admin;
ALTER TABLE IF EXISTS identity.projection_checkpoint OWNER to admin;
