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
