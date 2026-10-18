-- Migração: lado wallet de reservation em event sourcing (issue #29).
--   1) public.walletevent ganha a coluna version (a guarda "SET version = version + 1 ... RETURNING version" dá a
--      versão do evento no stream 'reservation.wallet');
--   2) public.event_availability passa ao schema da projeção assíncrona (event_id, ativos, last_occurred_at,
--      applied_at): sai available/refreshed_at/created_at/FK; a disponibilidade agora é calculada na leitura.
-- Idempotente. Aplicável a quente num banco já existente, SEM recriar o volume:
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 \
--     < Projeto/Backend/db/migrations/20261023-wallet-es-projecao.sql
--
-- PARE O BACKEND ANTES (docker stop gerenciador-backend): a imagem antiga ainda tem o ReadModelRefreshService
-- (INSERT em event_availability.available/refreshed_at, que esta migração derruba) e, depois do rebuild, o relay e o
-- consumer do backend novo escrevem nas mesmas tabelas. O ALTER TABLE walletevent pede ACCESS EXCLUSIVE: com o backend
-- parado não há disputa; o lock_timeout evita ficar pendurado atrás de uma transação longa (a migração falha e pode
-- ser repetida).
--
-- Para bancos NOVOS o schema já vem dos init-scripts 07 e 09 (a documentação das colunas está lá). Roda 2x sem
-- efeito colateral: a recriação da event_availability só acontece se a tabela ainda não tem a coluna "ativos".
-- Roda como supabase_admin, por isso o OWNER TO admin explícito. Só SQL padrão (sem meta-comandos do psql), então
-- roda por qualquer cliente.
--
-- A tabela antiga é um read model descartável (derivado), então é recriada e não convertida. O baseline de
-- "ativos" sai das reservas ativas que já existem em walletevent: elas não têm eventos no log (walletevent.version = 0),
-- então a próxima transição delas gera o version 1 do stream e o -1 do cancelamento/expiração bate com esse baseline.
-- Consequência: o replay só reproduz a projeção para streams que nasceram depois desta migração.

SET client_encoding = 'UTF8';

BEGIN;

SET LOCAL lock_timeout = '5s';

ALTER TABLE public.walletevent ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'event_availability' AND column_name = 'ativos') THEN
        DROP TABLE IF EXISTS public.event_availability CASCADE;

        CREATE TABLE public.event_availability
        (
            event_id         integer     NOT NULL,
            ativos           integer     NOT NULL DEFAULT 0,
            last_occurred_at timestamptz NOT NULL,
            applied_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
            CONSTRAINT event_availability_pkey PRIMARY KEY (event_id)
        )
        TABLESPACE pg_default;

        ALTER TABLE public.event_availability OWNER TO admin;

        COMMENT ON TABLE public.event_availability IS
            'Projeção assíncrona (consumer do tópico reservation.wallet): só o nº de reservas ativas por evento. '
            'A disponibilidade é calculada na leitura (eventcap LEFT JOIN esta tabela). Não é fonte de verdade.';

        INSERT INTO public.event_availability (event_id, ativos, last_occurred_at, applied_at)
        SELECT event_id, count(*), clock_timestamp(), clock_timestamp()
        FROM public.walletevent
        WHERE status IN ('pending', 'confirmed')
        GROUP BY event_id;
    END IF;
END $$;

COMMIT;
