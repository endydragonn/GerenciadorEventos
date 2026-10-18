-- Migração: índice em public.idempotency_key(created_at) para o job de retenção (issue #130).
-- Idempotente. Aplicável a quente num banco já existente, SEM recriar o volume:
--   docker cp Projeto/Backend/db/migrations/20261020-idempotency-key-created-at-index.sql gerenciador-db:/tmp/
--   docker exec gerenciador-db sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U admin -d meu_banco \
--     -v ON_ERROR_STOP=1 -f /tmp/20261020-idempotency-key-created-at-index.sql'
--
-- Independe da 20261019-idempotency-key-pk-por-usuario.sql (#129); pré-requisito: a tabela existir.
-- Para bancos NOVOS o índice já vem do init-script 10-idempotency-key.sql. Roda 2× sem efeito colateral
-- (CREATE INDEX IF NOT EXISTS). Sem CONCURRENTLY: o CREATE INDEX comum bloqueia ESCRITA na tabela enquanto
-- constrói (leitura segue); aceitável fora de rodada de carga. Numa tabela já gigante, prefira rodar à mão
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS (não pode ficar dentro de BEGIN/COMMIT, por isso não está aqui).

SET client_encoding = 'UTF8';

BEGIN;

CREATE INDEX IF NOT EXISTS idx_idempotency_key_created_at ON public.idempotency_key (created_at);

COMMIT;
