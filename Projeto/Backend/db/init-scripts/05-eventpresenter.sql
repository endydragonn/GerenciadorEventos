-- Table: public.eventpresenter
-- Lista de apresentadores de um evento (0..N linhas por evento).
-- Encoding: UTF-8
--
-- Substitui o antigo campo único "presenters" — o README trata apresentadores como lista
-- (que pode ser vazia), então a modelagem correta é uma tabela filha.

SET client_encoding = 'UTF8';

DROP TABLE IF EXISTS public.eventpresenter CASCADE;

CREATE TABLE IF NOT EXISTS public.eventpresenter
(
    event_id integer NOT NULL,
    presenter_name text COLLATE pg_catalog."default" NOT NULL,
    CONSTRAINT eventpresenter_pkey PRIMARY KEY (event_id, presenter_name),
    CONSTRAINT eventpresenter_event_id_fkey FOREIGN KEY (event_id)
        REFERENCES public.event (event_id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE CASCADE
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS public.eventpresenter
    OWNER to admin;

COMMENT ON TABLE public.eventpresenter IS
    'Apresentadores por evento; pode não ter linhas (lista vazia).';
