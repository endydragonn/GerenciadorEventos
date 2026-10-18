-- Migração: PK de public.idempotency_key passa de (idempotency_key) para (user_id, idempotency_key)
-- (issue #129): a chave de idempotência passa a ter escopo por usuário.
-- Idempotente. Aplicável a quente num banco já existente, SEM recriar o volume:
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco \
--     < Projeto/Backend/db/migrations/20261019-idempotency-key-pk-por-usuario.sql
--
-- Pré-requisito: a tabela já existe (migração 20261018-idempotency-key.sql ou init-script 10).
-- Para bancos NOVOS o schema já vem correto pelo init-script 10-idempotency-key.sql. Roda 2× sem efeito
-- colateral: só troca a PK se as colunas dela ainda forem as antigas. Os dados antigos satisfazem a PK
-- nova (idempotency_key já era única, logo (user_id, idempotency_key) também é). O DROP/ADD CONSTRAINT
-- reconstrói o índice da PK sob ACCESS EXCLUSIVE (bloqueia leitura e escrita na tabela, que cresce 1 linha
-- por reserva); aceitável fora de rodada de carga. Sem CONCURRENTLY.

SET client_encoding = 'UTF8';

BEGIN;

DO $$
BEGIN
    IF (SELECT array_agg(a.attname::text ORDER BY k.ord)
          FROM pg_constraint c
          CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
         WHERE c.conrelid = 'public.idempotency_key'::regclass AND c.contype = 'p')
       IS DISTINCT FROM ARRAY['user_id', 'idempotency_key'] THEN
        ALTER TABLE public.idempotency_key DROP CONSTRAINT IF EXISTS idempotency_key_pkey;
        ALTER TABLE public.idempotency_key ADD CONSTRAINT idempotency_key_pkey PRIMARY KEY (user_id, idempotency_key);
    END IF;
END $$;

COMMIT;
