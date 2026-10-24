-- Schema: notification  |  Table: notification.processed_event
-- Primeira extração de microsserviço (issue #32, Fase 7): o serviço notification tem schema PRÓPRIO. Ele só
-- consome o tópico identity.notification do backbone e envia e-mail; não lê nenhuma tabela do monólito.
--
-- processed_event: checkpoint de idempotência do consumer. Uma linha por evento (event_id do envelope) JÁ ENVIADO;
--   o consumer faz INSERT ... ON CONFLICT DO NOTHING na MESMA transação do envio: rowcount 0 = reentrega, descarta.
--   Cada notificação é um stream de uma versão só (streamId novo, version 1), então o checkpoint por stream da
--   shared.events (projection_checkpoint, que depende do event_store do monólito) não se aplica: aqui é por evento.
--   Sem dado pessoal (só o id e o tipo do evento). Linhas mais velhas que a retenção do tópico não serão mais
--   reentregues, então podem ser apagadas (a limpeza não existe ainda: ver Pendências no Backend/CLAUDE.md).
-- Usa timestamptz (código novo não propaga a dívida da issue #58).

SET client_encoding = 'UTF8';

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
