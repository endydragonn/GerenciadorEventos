-- Migração: schema do serviço notification (issue #32) — notification.processed_event. Idempotente. Aplicável a
-- quente num banco já existente, SEM recriar o volume:
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco \
--     < Projeto/Backend/db/migrations/20261024-notification-schema.sql
--
-- Para bancos NOVOS o schema já vem do init-script 12-notification.sql (a documentação das colunas está lá).
-- Roda 2x sem efeito colateral. Roda como supabase_admin, por isso o OWNER TO admin explícito (no init a role admin
-- já cria tudo como dona).

SET client_encoding = 'UTF8';

BEGIN;

CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE IF NOT EXISTS notification.processed_event
(
    event_id     uuid        NOT NULL,
    event_type   text        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT processed_event_pkey PRIMARY KEY (event_id)
)
TABLESPACE pg_default;

ALTER SCHEMA notification OWNER to admin;
ALTER TABLE notification.processed_event OWNER to admin;

COMMENT ON TABLE notification.processed_event IS
    'Eventos do tópico identity.notification já enviados por e-mail; PK = event_id do envelope (idempotência do consumer).';

COMMIT;
