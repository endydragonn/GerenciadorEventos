-- Table: public.idempotency_key
-- Chaves de idempotência do POST /bff/event-wallets (issue #24, Fase 5). Uma linha por reserva
-- BEM-SUCEDIDA: a chave é gravada na MESMA transação JDBC do CAS em eventcap + walletevent, então
-- reserva que falha (lotado, vínculo já existente) faz rollback e não deixa chave.
--
-- Repetição com a mesma chave e o mesmo (user_id, event_id) devolve o resultado da primeira
-- (expires_at salvo) sem tocar eventcap; mesma chave com outro (user_id, event_id) = conflito (HTTP 422).
-- Duas requisições concorrentes com a mesma chave: o INSERT ... ON CONFLICT DO NOTHING da segunda
-- bloqueia até a primeira dar commit/rollback (a PK serializa), sem decremento duplo.
--
-- Sem FK para users/event de propósito (fronteira de módulo: reservation guarda só os ids; o schema por
-- serviço é Fase 7). Sem retenção/limpeza por ora (uma linha por reserva; ver Backend/CLAUDE.md).
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
    CONSTRAINT idempotency_key_pkey PRIMARY KEY (idempotency_key)
)
TABLESPACE pg_default;

ALTER TABLE IF EXISTS public.idempotency_key OWNER to admin;

COMMENT ON TABLE public.idempotency_key IS
    'Chaves de idempotência do POST /bff/event-wallets. Uma linha por reserva bem-sucedida, gravada na '
    'mesma transação do CAS em eventcap. Sem FK (fronteira de módulo). expires_at = expires_at da reserva.';
