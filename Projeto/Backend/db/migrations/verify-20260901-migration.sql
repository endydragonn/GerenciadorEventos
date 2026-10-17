-- verify-20260901-migration.sql
-- Harness de verificação da issue #6 ("05 · Aplicar a migration idempotente em banco
-- populado + 99-tests.sql").
--
-- Prova, ponta a ponta, que db/migrations/20260901-eventcap-isolation.sql:
--   * roda via psql sobre um banco POPULADO no schema ANTIGO, SEM `down -v`;
--   * é IDEMPOTENTE (roda 2x sem erro);
--   * PRESERVA os dados (contagem E identidade das linhas);
--   * CONVERTE a semântica de `quant` corretamente
--       (ANTIGO: ingressos vendidos  ->  NOVO: vagas disponíveis = capacity - vendidos),
--     inclusive esgotamento (piso 0, capacity preservada) e evento ilimitado (-1 / -1).
--
-- Executar SEMPRE num banco DESCARTÁVEL já inicializado pelos init-scripts 01–09
-- (schema NOVO) — NUNCA em `meu_banco`. O reshape abaixo é destrutivo.
--
-- Uso (ver verify-20260901-migration.sh, que monta o banco descartável):
--   psql -v ON_ERROR_STOP=1 -f verify-20260901-migration.sql
--
-- É seguro re-executar este arquivo no mesmo banco: o reshape (passo 1) é idempotente
-- e recompõe o schema ANTIGO tanto a partir do schema NOVO quanto de um estado já migrado.

\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';

\echo ''
\echo '=== Harness de verificação da migração 20260901 — início ==='

-- ---------------------------------------------------------------------------
-- 1) Reshape: schema NOVO  ->  schema ANTIGO (pré-migração). Idempotente.
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS public.eventpresenter;
DROP TABLE IF EXISTS public.eventcap;

TRUNCATE TABLE public.walletevent, public.event, public.mywallet, public.users
    RESTART IDENTITY CASCADE;

ALTER TABLE public.event
    ADD COLUMN IF NOT EXISTS capacity       integer,
    ADD COLUMN IF NOT EXISTS quant          integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS buy_time_limit timestamp without time zone;
-- No modelo ANTIGO, event.quant era NOT NULL e SEM default; a fixture sempre informa
-- quant explicitamente, então removemos o DEFAULT para não mascarar um erro de fixture.
ALTER TABLE public.event ALTER COLUMN quant DROP DEFAULT;

ALTER TABLE public.walletevent DROP COLUMN IF EXISTS status;
ALTER TABLE public.walletevent DROP COLUMN IF EXISTS expires_at;
DROP INDEX IF EXISTS idx_walletevent_pending_expires;

-- ---------------------------------------------------------------------------
-- 2) Popular o banco no estado ANTIGO (com linhas).
--    event.quant = ingressos VENDIDOS (semântica ANTIGA).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_org  integer;
    v_p1   integer;
    v_p2   integer;
    v_lim  integer;
    v_esg  integer;
    v_ilim integer;
BEGIN
    INSERT INTO public.users (user_name, email, fone, password, birthdate)
    VALUES ('Organizador Mig', 'org.mig@example.com', '5511900000001', 'pw', '1990-01-01')
    RETURNING user_id INTO v_org;
    INSERT INTO public.users (user_name, email, fone, password, birthdate)
    VALUES ('Participante Um', 'part1.mig@example.com', '5511900000002', 'pw', '1991-02-02')
    RETURNING user_id INTO v_p1;
    INSERT INTO public.users (user_name, email, fone, password, birthdate)
    VALUES ('Participante Dois', 'part2.mig@example.com', '5511900000003', 'pw', '1992-03-03')
    RETURNING user_id INTO v_p2;

    -- limitado normal: capacidade 100, 2 vendidos   -> espera eventcap (100, 98)
    INSERT INTO public.event (creator_id, event_name, event_date, description,
                              capacity, quant, buy_time_limit)
    VALUES (v_org, 'Mig Limitado Normal', now() + interval '30 days', 'limitado normal',
            100, 2, now() + interval '20 days')
    RETURNING event_id INTO v_lim;

    -- esgotado / oversold: capacidade 10, 15 vendidos -> espera (10, 0) [piso 0]
    INSERT INTO public.event (creator_id, event_name, event_date, description,
                              capacity, quant, buy_time_limit)
    VALUES (v_org, 'Mig Esgotado', now() + interval '30 days', 'esgotado / oversold',
            10, 15, now() + interval '20 days')
    RETURNING event_id INTO v_esg;

    -- ilimitado: capacity NULL (regra antiga), 0 vendidos -> espera (-1, -1)
    INSERT INTO public.event (creator_id, event_name, event_date, description,
                              capacity, quant, buy_time_limit)
    VALUES (v_org, 'Mig Ilimitado', now() + interval '30 days', 'sem limite de vendas',
            NULL, 0, NULL)
    RETURNING event_id INTO v_ilim;

    -- inscrições no modelo ANTIGO (sem status / expires_at) — provam preservação
    INSERT INTO public.walletevent (user_id, event_id) VALUES (v_p1, v_lim);
    INSERT INTO public.walletevent (user_id, event_id) VALUES (v_p2, v_lim);
    INSERT INTO public.walletevent (user_id, event_id) VALUES (v_p1, v_esg);

    RAISE NOTICE 'Fixture ANTIGA criada: % users, % events, % walletevent',
        (SELECT count(*) FROM public.users),
        (SELECT count(*) FROM public.event),
        (SELECT count(*) FROM public.walletevent);
END $$;

-- ---------------------------------------------------------------------------
-- 3) Snapshot ANTES da migração: contagem + identidade das linhas.
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS _mig_baseline;
CREATE TEMP TABLE _mig_baseline AS
             SELECT 'event'::text  AS tabela, (SELECT count(*) FROM public.event)       AS n
    UNION ALL SELECT 'walletevent',           (SELECT count(*) FROM public.walletevent)
    UNION ALL SELECT 'users',                 (SELECT count(*) FROM public.users)
    UNION ALL SELECT 'mywallet',              (SELECT count(*) FROM public.mywallet);

DROP TABLE IF EXISTS _mig_eventos_antes;
CREATE TEMP TABLE _mig_eventos_antes AS
    SELECT event_id, event_name, buy_time_limit FROM public.event;

DROP TABLE IF EXISTS _mig_inscricoes_antes;
CREATE TEMP TABLE _mig_inscricoes_antes AS
    SELECT user_id, event_id FROM public.walletevent;

-- ---------------------------------------------------------------------------
-- 4) + 5) Rodar a migração DUAS vezes (idempotência). Com ON_ERROR_STOP,
--         qualquer erro em qualquer passada aborta o harness com exit != 0.
-- ---------------------------------------------------------------------------
\echo '--- migração: 1a execução ---'
\ir 20260901-eventcap-isolation.sql
\echo '--- migração: 2a execução (idempotência) ---'
\ir 20260901-eventcap-isolation.sql

-- ---------------------------------------------------------------------------
-- 6) Asserções pós-migração (cada falha => RAISE EXCEPTION => exit != 0).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_cap integer;
    v_qnt integer;
    v_now bigint;
    r     record;
BEGIN
    -- 6a) NEGATIVA: colunas antigas removidas de public.event
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'event'
          AND column_name IN ('capacity', 'quant', 'buy_time_limit')
    ) THEN
        RAISE EXCEPTION 'REGRESSAO: coluna antiga (capacity/quant/buy_time_limit) ainda existe em public.event';
    END IF;

    -- 6b) walletevent ganhou a máquina de estados
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='public' AND table_name='walletevent' AND column_name='status') THEN
        RAISE EXCEPTION 'Falha: walletevent.status ausente apos a migracao';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='public' AND table_name='walletevent' AND column_name='expires_at') THEN
        RAISE EXCEPTION 'Falha: walletevent.expires_at ausente apos a migracao';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname='walletevent_status_check'
                     AND conrelid = 'public.walletevent'::regclass) THEN
        RAISE EXCEPTION 'Falha: constraint walletevent_status_check ausente';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                   WHERE schemaname='public' AND indexname='idx_walletevent_pending_expires') THEN
        RAISE EXCEPTION 'Falha: indice parcial idx_walletevent_pending_expires ausente';
    END IF;

    -- 6c) tabelas novas + constraints da invariante de capacidade
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='public' AND table_name='eventcap') THEN
        RAISE EXCEPTION 'Falha: tabela eventcap ausente';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='public' AND table_name='eventpresenter') THEN
        RAISE EXCEPTION 'Falha: tabela eventpresenter ausente';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname='eventcap_quant_bounds'
                     AND conrelid = 'public.eventcap'::regclass) THEN
        RAISE EXCEPTION 'Falha: constraint eventcap_quant_bounds ausente';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname='eventcap_capacity_valid'
                     AND conrelid = 'public.eventcap'::regclass) THEN
        RAISE EXCEPTION 'Falha: constraint eventcap_capacity_valid ausente';
    END IF;

    -- 6d) conversão de semântica: vendidos -> vagas disponíveis
    SELECT ec.capacity, ec.quant INTO v_cap, v_qnt
    FROM public.eventcap ec JOIN public.event e USING (event_id)
    WHERE e.event_name = 'Mig Limitado Normal';
    IF v_cap IS DISTINCT FROM 100 OR v_qnt IS DISTINCT FROM 98 THEN
        RAISE EXCEPTION 'Conversao limitado: esperado (100, 98), obtido (%, %)', v_cap, v_qnt;
    END IF;

    SELECT ec.capacity, ec.quant INTO v_cap, v_qnt
    FROM public.eventcap ec JOIN public.event e USING (event_id)
    WHERE e.event_name = 'Mig Esgotado';
    IF v_cap IS DISTINCT FROM 10 OR v_qnt IS DISTINCT FROM 0 THEN
        RAISE EXCEPTION 'Conversao esgotado: esperado (10, 0) [piso 0, capacity preservada], obtido (%, %)', v_cap, v_qnt;
    END IF;

    SELECT ec.capacity, ec.quant INTO v_cap, v_qnt
    FROM public.eventcap ec JOIN public.event e USING (event_id)
    WHERE e.event_name = 'Mig Ilimitado';
    IF v_cap IS DISTINCT FROM -1 OR v_qnt IS DISTINCT FROM -1 THEN
        RAISE EXCEPTION 'Conversao ilimitado: esperado (-1, -1), obtido (%, %)', v_cap, v_qnt;
    END IF;

    -- 6e) exatamente 1 linha em eventcap por evento
    SELECT count(*) INTO v_now FROM public.eventcap;
    IF v_now <> 3 THEN
        RAISE EXCEPTION 'Esperado 3 linhas em eventcap (1 por evento), obtido %', v_now;
    END IF;

    -- 6f) CONTAGEM de linhas preservada (event, walletevent, users, mywallet). NAO PULAR.
    FOR r IN SELECT tabela, n FROM _mig_baseline LOOP
        EXECUTE format('SELECT count(*) FROM public.%I', r.tabela) INTO v_now;
        IF v_now <> r.n THEN
            RAISE EXCEPTION 'Dados perdidos: contagem de % era %, ficou %', r.tabela, r.n, v_now;
        END IF;
    END LOOP;

    -- 6g) IDENTIDADE das linhas preservada (não só a cardinalidade)
    IF EXISTS (SELECT event_id, event_name FROM _mig_eventos_antes
               EXCEPT SELECT event_id, event_name FROM public.event) THEN
        RAISE EXCEPTION 'Dados alterados: linhas de public.event nao batem com o snapshot pre-migracao';
    END IF;
    IF EXISTS (SELECT user_id, event_id FROM _mig_inscricoes_antes
               EXCEPT SELECT user_id, event_id FROM public.walletevent) THEN
        RAISE EXCEPTION 'Dados alterados: linhas de public.walletevent nao batem com o snapshot pre-migracao';
    END IF;

    -- 6h) backfill: as inscrições antigas (sem status) viram 'confirmed'
    SELECT count(*) INTO v_now FROM public.walletevent WHERE status <> 'confirmed';
    IF v_now <> 0 THEN
        RAISE EXCEPTION 'Backfill de status: % inscricoes antigas ficaram fora de confirmed', v_now;
    END IF;

    -- 6i) buy_time_limit copiado de event -> eventcap sem perda (2 valores não-nulos + 1 NULL)
    FOR r IN
        SELECT a.event_name,
               a.buy_time_limit AS esperado,
               ec.buy_time_limit AS obtido
        FROM _mig_eventos_antes a
        JOIN public.eventcap ec USING (event_id)
    LOOP
        IF r.obtido IS DISTINCT FROM r.esperado THEN
            RAISE EXCEPTION 'buy_time_limit de %: esperado %, obtido %', r.event_name, r.esperado, r.obtido;
        END IF;
    END LOOP;

    RAISE NOTICE 'HARNESS OK: migracao idempotente sobre banco populado; dados preservados (contagem + identidade); conversao de quant correta';
END $$;

\echo '=== Harness de verificação da migração 20260901 — SUCESSO ==='
\echo ''
