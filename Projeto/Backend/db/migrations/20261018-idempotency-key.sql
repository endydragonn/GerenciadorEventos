-- Migração: tabela public.idempotency_key (issue #24, idempotência do POST /bff/event-wallets).
-- Idempotente. Aplicável a quente num banco já existente, SEM recriar o volume:
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco \
--     < Projeto/Backend/db/migrations/20261018-idempotency-key.sql
--
-- Para bancos NOVOS o schema já vem do init-script 10-idempotency-key.sql. Roda 2× sem efeito
-- colateral (CREATE TABLE IF NOT EXISTS). Roda como supabase_admin, por isso o OWNER TO admin explícito
-- (no init a tabela já nasce da role admin).

SET client_encoding = 'UTF8';

BEGIN;

CREATE TABLE IF NOT EXISTS public.idempotency_key
(
    idempotency_key text        NOT NULL,
    user_id         integer     NOT NULL,
    event_id        integer     NOT NULL,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_key_pkey PRIMARY KEY (idempotency_key)
)
TABLESPACE pg_default;

ALTER TABLE IF EXISTS public.idempotency_key OWNER to admin;

COMMENT ON TABLE public.idempotency_key IS
    'Chaves de idempotência do POST /bff/event-wallets. Uma linha por reserva bem-sucedida, gravada na '
    'mesma transação do CAS em eventcap. Sem FK (fronteira de módulo). expires_at = expires_at da reserva.';

COMMIT;
