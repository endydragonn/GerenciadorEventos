-- Fixture dos harnesses de migração (issue #36, cutover): o schema ANTERIOR ao cutover do identity, com users, mywallet e
-- pending_verifications (+ funções e triggers) em `public`, exatamente como os init-scripts 01-functions, 02-users, 06-mywallet e
-- 08-pending-verifications os criavam (esses quatro saíram do repo no cutover; o 15-identity.sql os substituiu). Os verify-*.sh
-- aplicam este arquivo em bancos DESCARTÁVEIS para reconstruir o estado pré-cutover; NUNCA em meu_banco (os DROP TABLE ... CASCADE
-- abaixo apagariam os usuários).

-- ===== 01-functions.sql (como era antes do cutover) =====
-- FUNCTION: public.create_wallet_for_user()
-- Esta função é usada pelo trigger em Users para criar automaticamente uma carteira

DROP FUNCTION IF EXISTS public.create_wallet_for_user();

CREATE OR REPLACE FUNCTION public.create_wallet_for_user()
    RETURNS trigger
    LANGUAGE 'plpgsql'
    COST 100
    VOLATILE NOT LEAKPROOF
AS $BODY$
BEGIN
    INSERT INTO public.MyWallet (user_id)
    VALUES (NEW.user_id);
    RETURN NEW;
END;
$BODY$;

ALTER FUNCTION public.create_wallet_for_user()
    OWNER TO admin;


-- ===== 02-users.sql (como era antes do cutover) =====
-- Table: public.users
-- Tabela principal de usuários (deve ser criada antes de Event e MyWallet pois ambas referenciam user_id)

DROP TABLE IF EXISTS public.users CASCADE;

CREATE TABLE IF NOT EXISTS public.users
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

ALTER TABLE IF EXISTS public.users
    OWNER to admin;

-- Trigger: trigger_create_wallet
-- Este trigger chama a função create_wallet_for_user após inserção de um novo usuário

DROP TRIGGER IF EXISTS trigger_create_wallet ON public.users;

CREATE OR REPLACE TRIGGER trigger_create_wallet
    AFTER INSERT
    ON public.users
    FOR EACH ROW
    EXECUTE FUNCTION public.create_wallet_for_user();

-- ===== 06-mywallet.sql (como era antes do cutover) =====
-- Table: public.mywallet
-- Tabela de carteiras (depende de users.user_id via FK)

DROP TABLE IF EXISTS public.mywallet CASCADE;

CREATE TABLE IF NOT EXISTS public.mywallet
(
    user_id integer NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "MyWallet_pkey" PRIMARY KEY (user_id),
    CONSTRAINT mywallet_user_id_fkey FOREIGN KEY (user_id)
        REFERENCES public.users (user_id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE CASCADE
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS public.mywallet
    OWNER to admin;


-- ===== 08-pending-verifications.sql (como era antes do cutover) =====
-- Table: public.pending_verifications
-- Descrição: Armazena dados temporários de usuários aguardando verificação de email
-- Encoding: UTF-8

SET client_encoding = 'UTF8';

DROP TABLE IF EXISTS public.pending_verifications CASCADE;

CREATE TABLE IF NOT EXISTS public.pending_verifications
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

ALTER TABLE IF EXISTS public.pending_verifications
    OWNER to admin;

-- Índices para otimizar buscas
CREATE INDEX IF NOT EXISTS idx_pending_verifications_email
    ON public.pending_verifications USING btree
    (email ASC NULLS LAST)
    TABLESPACE pg_default;

CREATE INDEX IF NOT EXISTS idx_pending_verifications_code
    ON public.pending_verifications USING btree
    (verification_code ASC NULLS LAST)
    TABLESPACE pg_default;

CREATE INDEX IF NOT EXISTS idx_pending_verifications_expires_at
    ON public.pending_verifications USING btree
    (expires_at ASC NULLS LAST)
    TABLESPACE pg_default;

-- Function: public.cleanup_expired_verifications()
-- Descrição: Remove automaticamente registros de verificação que expiraram

DROP FUNCTION IF EXISTS public.cleanup_expired_verifications() CASCADE;

CREATE OR REPLACE FUNCTION public.cleanup_expired_verifications()
    RETURNS void
    LANGUAGE 'plpgsql'
    COST 100
    VOLATILE PARALLEL UNSAFE
AS $BODY$
DECLARE
    v_deleted_count INT;
BEGIN
    DELETE FROM public.pending_verifications
    WHERE expires_at < CURRENT_TIMESTAMP;
    
    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;
    
    IF v_deleted_count > 0 THEN
        RAISE NOTICE 'Limpeza de verificações expiradas: % registros deletados', v_deleted_count;
    END IF;
END;
$BODY$;

ALTER FUNCTION public.cleanup_expired_verifications()
    OWNER to admin;

-- Function: public.trigger_cleanup_on_insert()
-- Descrição: Trigger que executa a limpeza automaticamente quando um novo registro é criado

DROP FUNCTION IF EXISTS public.trigger_cleanup_on_insert() CASCADE;

CREATE OR REPLACE FUNCTION public.trigger_cleanup_on_insert()
    RETURNS trigger
    LANGUAGE 'plpgsql'
    COST 100
    VOLATILE NOT LEAKPROOF
AS $BODY$
BEGIN
    -- Executar limpeza de registros expirados a cada novo registro criado
    PERFORM public.cleanup_expired_verifications();
    RETURN NEW;
END;
$BODY$;

ALTER FUNCTION public.trigger_cleanup_on_insert()
    OWNER to admin;

-- Trigger: trigger_cleanup_on_create_verification
-- Descrição: Executa limpeza automaticamente quando um novo registro é inserido

DROP TRIGGER IF EXISTS trigger_cleanup_on_create_verification ON public.pending_verifications;

CREATE OR REPLACE TRIGGER trigger_cleanup_on_create_verification
    AFTER INSERT
    ON public.pending_verifications
    FOR EACH STATEMENT
    EXECUTE FUNCTION public.trigger_cleanup_on_insert();

-- Limpeza inicial de registros expirados no startup
SELECT public.cleanup_expired_verifications();
