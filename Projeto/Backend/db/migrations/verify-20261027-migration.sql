-- verify-20261027-migration.sql
-- Harness da migração 20261027-reservation-timestamptz.sql (issue #58; harness da issue #59). Roda SEMPRE num banco DESCARTÁVEL
-- (o verify-20261027-migration.sh cria meu_banco_mig27) — NUNCA em meu_banco: o passo "preparar" cria o estado ANTERIOR da migração.
--
-- Etapas, escolhidas por variável do psql:
--   psql -v preparar=1  -f verify-20261027-migration.sql   -> monta o schema reservation ANTERIOR (timestamp, CHECK capacity >= -1,
--                                                              sem idx_walletevent_event_id) COM linhas de horário conhecido
--   psql -v migrado=1   -f verify-20261027-migration.sql   -> confere o estado DEPOIS da migração (aplicada pelo .sh em sessão NÃO-UTC)
--   psql -v inalterado=1 -f ...                            -> confere que a migração ABORTADA (capacity = 0) não mudou NADA
--   psql -v restaurado=1 -f ...                            -> confere o rollback do cabeçalho da migração (valores e tipos originais)
--   psql -v snapshot=1  -f ...                             -> imprime colunas, constraints, índices e donos de eventcap/walletevent
--                                                              (o .sh compara com o de um banco montado pelo 13-reservation.sql atual)
--
-- Os valores de referência (horário de parede ANTIGO, timestamp sem fuso) vão para o schema verif, fora de reservation.

\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';

\if :{?preparar}
\echo '=== preparar: schema reservation anterior à 20261027, com dados ==='

CREATE SCHEMA reservation;

-- DDL do 13-reservation.sql de antes da #58 (só as duas tabelas que a migração toca)
CREATE TABLE reservation.eventcap
(
    event_id integer NOT NULL,
    capacity integer NOT NULL,
    quant integer NOT NULL,
    buy_time_limit timestamp without time zone,
    max_time_in_line integer NOT NULL DEFAULT 120,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT eventcap_pkey PRIMARY KEY (event_id),
    CONSTRAINT eventcap_capacity_valid CHECK (capacity >= -1),
    CONSTRAINT eventcap_quant_bounds
        CHECK (capacity = -1 OR (quant >= 0 AND quant <= capacity)),
    CONSTRAINT eventcap_max_time_in_line_positive CHECK (max_time_in_line > 0)
);

CREATE TABLE reservation.walletevent
(
    user_id integer NOT NULL,
    event_id integer NOT NULL,
    status text COLLATE pg_catalog."default" NOT NULL DEFAULT 'confirmed',
    expires_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version integer NOT NULL DEFAULT 0,
    CONSTRAINT "WalletEvent_pkey" PRIMARY KEY (user_id, event_id),
    CONSTRAINT walletevent_status_check
        CHECK (status IN ('confirmed', 'pending', 'expired', 'cancelled'))
);

CREATE INDEX idx_walletevent_pending_expires
    ON reservation.walletevent USING btree (expires_at)
    WHERE status = 'pending';

-- dono admin, como no meu_banco real (a role é do cluster; se não existir, fica o dono que criou)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'admin') THEN
        ALTER SCHEMA reservation OWNER TO admin;
        ALTER TABLE reservation.eventcap OWNER TO admin;
        ALTER TABLE reservation.walletevent OWNER TO admin;
    END IF;
END
$$;

-- Horários de parede conhecidos (gravados em UTC, como a JVM/Postgres dos containers faziam), inclusive NULL, microssegundos
-- e um horário que NÃO EXISTE em America/New_York (2030-03-10 02:30, salto do horário de verão): o cast implícito mexeria nele.
INSERT INTO reservation.eventcap (event_id, capacity, quant, buy_time_limit) VALUES
    (1, 10, 10, '2030-01-01 10:00:00'),
    (2, -1, -1, NULL),
    (3, 5, 2, '2030-06-15 23:30:45.123456'),
    (4, 1, 0, '2030-03-10 02:30:00');
INSERT INTO reservation.walletevent (user_id, event_id, status, expires_at) VALUES
    (100, 1, 'pending', '2030-01-01 10:02:00'),
    (101, 1, 'confirmed', NULL),
    (102, 3, 'pending', '2030-06-15 23:31:00.5'),
    (103, 3, 'expired', NULL),
    (104, 4, 'pending', '2030-03-10 02:30:30');

CREATE SCHEMA verif;
CREATE TABLE verif.eventcap_antes AS SELECT * FROM reservation.eventcap;
CREATE TABLE verif.walletevent_antes AS SELECT * FROM reservation.walletevent;
\echo 'ok: estado anterior montado (4 eventcap, 5 walletevent)'
\endif

\if :{?migrado}
\echo '=== migrado: estado depois da 20261027 ==='

DO $$
DECLARE
    n bigint;
    def text;
    dono text;
BEGIN
    -- a sessão tem de ser NÃO-UTC, senão a prova não distingue AT TIME ZONE 'UTC' de cast implícito
    IF current_setting('TimeZone') = 'UTC' THEN
        RAISE EXCEPTION 'a sessão do harness deveria ser não-UTC (TimeZone = %)', current_setting('TimeZone');
    END IF;
    RAISE NOTICE 'sessão de conferência em %', current_setting('TimeZone');

    -- tipos: as duas colunas viram timestamptz; os carimbos de auditoria seguem timestamp
    FOR n IN SELECT count(*) FROM information_schema.columns
              WHERE table_schema = 'reservation' AND data_type = 'timestamp with time zone'
                AND (table_name, column_name) IN (('eventcap', 'buy_time_limit'), ('walletevent', 'expires_at')) LOOP
        IF n <> 2 THEN RAISE EXCEPTION 'buy_time_limit/expires_at deveriam ser timestamptz (achei % de 2)', n; END IF;
    END LOOP;
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema = 'reservation' AND table_name IN ('eventcap', 'walletevent')
       AND column_name IN ('created_at', 'updated_at') AND data_type <> 'timestamp without time zone';
    IF n <> 0 THEN RAISE EXCEPTION 'created_at/updated_at deveriam seguir timestamp sem fuso (% mudaram)', n; END IF;

    -- nenhuma linha perdida nem criada
    SELECT count(*) INTO n FROM reservation.eventcap;
    IF n <> (SELECT count(*) FROM verif.eventcap_antes) THEN RAISE EXCEPTION 'eventcap mudou de contagem: %', n; END IF;
    SELECT count(*) INTO n FROM reservation.walletevent;
    IF n <> (SELECT count(*) FROM verif.walletevent_antes) THEN RAISE EXCEPTION 'walletevent mudou de contagem: %', n; END IF;

    -- linha a linha: novo = antigo AT TIME ZONE 'UTC' (o horário de parede gravado em UTC virou o instante certo)
    SELECT count(*) INTO n FROM reservation.eventcap e JOIN verif.eventcap_antes a USING (event_id)
     WHERE e.buy_time_limit IS DISTINCT FROM (a.buy_time_limit AT TIME ZONE 'UTC')
        OR (e.capacity, e.quant, e.max_time_in_line, e.created_at, e.updated_at)
           IS DISTINCT FROM (a.capacity, a.quant, a.max_time_in_line, a.created_at, a.updated_at);
    IF n <> 0 THEN RAISE EXCEPTION '% linha(s) de eventcap com instante/dados diferentes do esperado', n; END IF;
    SELECT count(*) INTO n FROM reservation.walletevent w JOIN verif.walletevent_antes a USING (user_id, event_id)
     WHERE w.expires_at IS DISTINCT FROM (a.expires_at AT TIME ZONE 'UTC')
        OR (w.status, w.version, w.created_at, w.updated_at) IS DISTINCT FROM (a.status, a.version, a.created_at, a.updated_at);
    IF n <> 0 THEN RAISE EXCEPTION '% linha(s) de walletevent com instante/dados diferentes do esperado', n; END IF;

    -- valores literais, independentes do fuso da sessão (instante UTC escrito com +00)
    IF (SELECT buy_time_limit FROM reservation.eventcap WHERE event_id = 1) <> timestamptz '2030-01-01 10:00:00+00'
       OR (SELECT buy_time_limit FROM reservation.eventcap WHERE event_id = 3) <> timestamptz '2030-06-15 23:30:45.123456+00'
       OR (SELECT buy_time_limit FROM reservation.eventcap WHERE event_id = 4) <> timestamptz '2030-03-10 02:30:00+00'
       OR (SELECT expires_at FROM reservation.walletevent WHERE user_id = 100) <> timestamptz '2030-01-01 10:02:00+00'
       OR (SELECT expires_at FROM reservation.walletevent WHERE user_id = 102) <> timestamptz '2030-06-15 23:31:00.5+00'
       OR (SELECT expires_at FROM reservation.walletevent WHERE user_id = 104) <> timestamptz '2030-03-10 02:30:30+00' THEN
        RAISE EXCEPTION 'um instante convertido não bate com o UTC esperado';
    END IF;
    IF (SELECT buy_time_limit FROM reservation.eventcap WHERE event_id = 2) IS NOT NULL
       OR (SELECT count(*) FROM reservation.walletevent WHERE user_id IN (101, 103) AND expires_at IS NOT NULL) <> 0 THEN
        RAISE EXCEPTION 'NULL deveria continuar NULL';
    END IF;

    -- o harness tem dentes: nesta sessão o cast implícito daria outro instante em alguma linha
    SELECT count(*) INTO n FROM verif.eventcap_antes
     WHERE buy_time_limit IS NOT NULL AND buy_time_limit::timestamptz <> (buy_time_limit AT TIME ZONE 'UTC');
    IF n = 0 THEN RAISE EXCEPTION 'sessão não distingue cast implícito de AT TIME ZONE UTC: o harness não prova nada'; END IF;

    -- CHECK novo: definição exata (capacity = -1 OR capacity >= 1), validado, e a regra vale
    SELECT pg_get_constraintdef(oid) INTO def FROM pg_constraint
     WHERE conrelid = 'reservation.eventcap'::regclass AND conname = 'eventcap_capacity_valid' AND convalidated;
    IF def IS DISTINCT FROM 'CHECK (((capacity = ''-1''::integer) OR (capacity >= 1)))' THEN
        RAISE EXCEPTION 'CHECK de capacity inesperado: %', def;
    END IF;
    BEGIN
        INSERT INTO reservation.eventcap (event_id, capacity, quant) VALUES (990, 0, 0);
        RAISE EXCEPTION 'o CHECK deveria recusar capacity = 0';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'capacity = 0 recusado pelo CHECK (como esperado)';
    END;
    BEGIN
        INSERT INTO reservation.eventcap (event_id, capacity, quant) VALUES (991, -2, 0);
        RAISE EXCEPTION 'o CHECK deveria recusar capacity = -2';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'capacity = -2 recusado pelo CHECK (como esperado)';
    END;

    -- índice por event_id
    SELECT count(*) INTO n FROM pg_indexes
     WHERE schemaname = 'reservation' AND tablename = 'walletevent' AND indexname = 'idx_walletevent_event_id'
       AND indexdef LIKE '%USING btree (event_id)';
    IF n <> 1 THEN RAISE EXCEPTION 'idx_walletevent_event_id (btree em event_id) não existe'; END IF;
    SELECT count(*) INTO n FROM pg_indexes
     WHERE schemaname = 'reservation' AND tablename = 'walletevent' AND indexname = 'idx_walletevent_pending_expires';
    IF n <> 1 THEN RAISE EXCEPTION 'idx_walletevent_pending_expires sumiu'; END IF;

    -- dono preservado (admin, se a role existe)
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'admin') THEN
        SELECT string_agg(DISTINCT tableowner, ',') INTO dono FROM pg_tables
         WHERE schemaname = 'reservation' AND tablename IN ('eventcap', 'walletevent');
        IF dono <> 'admin' THEN RAISE EXCEPTION 'dono das tabelas deveria continuar admin, achei %', dono; END IF;
    END IF;
END
$$;
\echo 'ok: instantes, tipos, CHECK, índice e dono conferidos'
\endif

\if :{?inalterado}
\echo '=== inalterado: migração abortada (capacity = 0) não mudou nada ==='
DO $$
DECLARE
    n bigint;
    def text;
BEGIN
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema = 'reservation' AND data_type = 'timestamp without time zone'
       AND (table_name, column_name) IN (('eventcap', 'buy_time_limit'), ('walletevent', 'expires_at'));
    IF n <> 2 THEN RAISE EXCEPTION 'a migração abortada deixou o tipo mudado (% de 2 ainda timestamp)', n; END IF;
    SELECT pg_get_constraintdef(oid) INTO def FROM pg_constraint
     WHERE conrelid = 'reservation.eventcap'::regclass AND conname = 'eventcap_capacity_valid';
    IF def IS DISTINCT FROM 'CHECK ((capacity >= ''-1''::integer))' THEN
        RAISE EXCEPTION 'a migração abortada mexeu no CHECK: %', def;
    END IF;
    SELECT count(*) INTO n FROM pg_indexes WHERE schemaname = 'reservation' AND indexname = 'idx_walletevent_event_id';
    IF n <> 0 THEN RAISE EXCEPTION 'a migração abortada criou o índice'; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM reservation.eventcap EXCEPT SELECT * FROM verif.eventcap_antes) x;
    IF n <> 0 THEN RAISE EXCEPTION 'eventcap mudou (% linhas)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM verif.eventcap_antes EXCEPT SELECT * FROM reservation.eventcap) x;
    IF n <> 0 THEN RAISE EXCEPTION 'eventcap perdeu linhas (%)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM reservation.walletevent EXCEPT SELECT * FROM verif.walletevent_antes) x;
    IF n <> 0 THEN RAISE EXCEPTION 'walletevent mudou (% linhas)', n; END IF;
END
$$;
\echo 'ok: tipos antigos, CHECK antigo, sem índice, linhas iguais'
\endif

\if :{?restaurado}
\echo '=== restaurado: rollback do cabeçalho da migração ==='
DO $$
DECLARE
    n bigint;
    def text;
BEGIN
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema = 'reservation' AND data_type = 'timestamp without time zone'
       AND (table_name, column_name) IN (('eventcap', 'buy_time_limit'), ('walletevent', 'expires_at'));
    IF n <> 2 THEN RAISE EXCEPTION 'o rollback não devolveu o tipo timestamp (% de 2)', n; END IF;
    SELECT pg_get_constraintdef(oid) INTO def FROM pg_constraint
     WHERE conrelid = 'reservation.eventcap'::regclass AND conname = 'eventcap_capacity_valid';
    IF def IS DISTINCT FROM 'CHECK ((capacity >= ''-1''::integer))' THEN
        RAISE EXCEPTION 'o rollback não devolveu o CHECK antigo: %', def;
    END IF;
    SELECT count(*) INTO n FROM pg_indexes WHERE schemaname = 'reservation' AND indexname = 'idx_walletevent_event_id';
    IF n <> 0 THEN RAISE EXCEPTION 'o rollback não removeu o índice'; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM reservation.eventcap EXCEPT SELECT * FROM verif.eventcap_antes) x;
    IF n <> 0 THEN RAISE EXCEPTION 'eventcap não voltou aos valores originais (% linhas)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM verif.eventcap_antes EXCEPT SELECT * FROM reservation.eventcap) x;
    IF n <> 0 THEN RAISE EXCEPTION 'eventcap perdeu linhas no rollback (%)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM reservation.walletevent EXCEPT SELECT * FROM verif.walletevent_antes) x;
    IF n <> 0 THEN RAISE EXCEPTION 'walletevent não voltou aos valores originais (% linhas)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM verif.walletevent_antes EXCEPT SELECT * FROM reservation.walletevent) x;
    IF n <> 0 THEN RAISE EXCEPTION 'walletevent perdeu linhas no rollback (%)', n; END IF;
END
$$;
\echo 'ok: rollback devolveu tipos, CHECK, índice e os horários de parede originais'
\endif

\if :{?snapshot}
-- Forma do schema (colunas, constraints, índices, dono) das duas tabelas: o .sh compara a de um banco migrado com a de um banco
-- montado pelo 13-reservation.sql atual (a migração e o init de banco novo têm de terminar no mesmo estado).
SELECT 'col|' || table_name || '|' || column_name || '|' || data_type || '|' || is_nullable || '|' || coalesce(column_default, '')
  FROM information_schema.columns
 WHERE table_schema = 'reservation' AND table_name IN ('eventcap', 'walletevent')
UNION ALL
SELECT 'con|' || conrelid::regclass::text || '|' || conname || '|' || pg_get_constraintdef(oid) || '|' || convalidated
  FROM pg_constraint
 WHERE connamespace = 'reservation'::regnamespace AND conrelid::regclass::text IN ('reservation.eventcap', 'reservation.walletevent')
UNION ALL
SELECT 'idx|' || tablename || '|' || indexname || '|' || indexdef
  FROM pg_indexes
 WHERE schemaname = 'reservation' AND tablename IN ('eventcap', 'walletevent')
UNION ALL
SELECT 'own|' || tablename || '|' || tableowner
  FROM pg_tables
 WHERE schemaname = 'reservation' AND tablename IN ('eventcap', 'walletevent')
ORDER BY 1;
\endif
