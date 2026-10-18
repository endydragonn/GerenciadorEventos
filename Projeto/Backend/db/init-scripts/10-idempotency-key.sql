-- Table: public.idempotency_key
-- Chaves de idempotência do POST /bff/event-wallets (issue #24, Fase 5). Uma linha por reserva
-- BEM-SUCEDIDA: a chave é gravada na MESMA transação JDBC do CAS em eventcap + walletevent, então
-- reserva que falha (lotado, vínculo já existente) faz rollback e não deixa chave.
--
-- A chave tem escopo por usuário (issue #129): PK = (user_id, idempotency_key). Dois usuários podem usar a
-- mesma chave sem colidir; cada um tem a sua reserva.
-- Repetição com a mesma chave, o mesmo usuário e o mesmo event_id devolve o resultado da primeira
-- (expires_at salvo) sem tocar eventcap; mesma chave e mesmo usuário com outro event_id = conflito (HTTP 422).
-- Duas requisições concorrentes com a mesma chave e o mesmo usuário: o INSERT ... ON CONFLICT DO NOTHING da
-- segunda bloqueia até a primeira dar commit/rollback (a PK serializa), sem decremento duplo.
--
-- Sem FK para users/event de propósito (fronteira de módulo: reservation guarda só os ids; o schema por
-- serviço é Fase 7). Retenção (issue #130): o job IdempotencyKeyRetentionService apaga, em lotes, as linhas com
-- created_at mais antigo que idempotency.retention.hours (default 24h); o índice em created_at serve esse filtro.
-- Usa timestamptz (código novo não propaga a dívida da issue #58).

SET client_encoding = 'UTF8';

DROP TABLE IF EXISTS public.idempotency_key CASCADE;

CREATE TABLE IF NOT EXISTS public.idempotency_key
(
    idempotency_key text        NOT NULL,
    user_id         integer     NOT NULL,
    event_id        integer     NOT NULL,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_key_pkey PRIMARY KEY (user_id, idempotency_key)
)
TABLESPACE pg_default;

-- Job de retenção (issue #130): filtra por created_at; a PK (user_id, idempotency_key) não serve. Sem o índice
-- cada rodada faria seq scan da tabela inteira. Migração a quente: migrations/20261020-...sql.
CREATE INDEX IF NOT EXISTS idx_idempotency_key_created_at ON public.idempotency_key (created_at);

ALTER TABLE IF EXISTS public.idempotency_key OWNER to admin;

COMMENT ON TABLE public.idempotency_key IS
    'Chaves de idempotência do POST /bff/event-wallets. Uma linha por reserva bem-sucedida, gravada na '
    'mesma transação do CAS em eventcap. Sem FK (fronteira de módulo). expires_at = expires_at da reserva.';
