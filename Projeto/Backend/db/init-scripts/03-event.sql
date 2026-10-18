-- Table: public.event
-- Tabela de eventos (depende de users.user_id via FK)
-- Encoding: UTF-8
--
-- NOTA DE ARQUITETURA: capacidade/vagas (capacity, quant) e a janela de venda
-- (buy_time_limit) NÃO ficam aqui. Foram isoladas no agregado public.eventcap
-- (ver 04-eventcap.sql), que tem sua própria fronteira de consistência forte e é
-- protegido por UPDATE condicional atômico. Aqui ficam só os metadados do evento.
--
-- NOTA DE EVENT SOURCING (issue #28): esta tabela é a PROJEÇÃO INLINE do stream 'catalog.event' do
-- event_store. 'version' = versão do último evento aplicado (0 = evento pré-ES, sem stream); é a versão
-- esperada do próximo append (concorrência otimista). image_data NÃO vem do log (blob, fora do ES).

SET client_encoding = 'UTF8';

DROP TABLE IF EXISTS public.event CASCADE;

CREATE TABLE IF NOT EXISTS public.event
(
    event_id integer NOT NULL GENERATED ALWAYS AS IDENTITY ( INCREMENT 1 START 1 MINVALUE 1 MAXVALUE 2147483647 CACHE 1 ),
    creator_id integer NOT NULL,
    event_name text COLLATE pg_catalog."default" NOT NULL,
    ead boolean NOT NULL DEFAULT false,
    address text COLLATE pg_catalog."default",
    event_date timestamp without time zone NOT NULL,
    description text COLLATE pg_catalog."default" NOT NULL,
    image_data bytea,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version integer NOT NULL DEFAULT 0,
    CONSTRAINT "Event_pkey" PRIMARY KEY (event_id),
    CONSTRAINT "Event_event_name_key" UNIQUE (event_name),
    CONSTRAINT "Event_creator_id_fkey" FOREIGN KEY (creator_id)
        REFERENCES public.users (user_id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE NO ACTION
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS public.event
    OWNER to admin;
