-- verify-20261029-migration.sql
-- Harness da migração 20261029-stock-schema.sql (issue #47, cenário S9). Roda SEMPRE num banco DESCARTÁVEL (o verify-20261029-migration.sh
-- cria meu_banco_mig47 com os init scripts de ANTES da #47) — NUNCA em meu_banco: o passo "preparar" insere dados.
--
-- Etapas, escolhidas por variável do psql:
--   psql -v preparar=1    -f verify-20261029-migration.sql  -> insere o cenário no schema reservation ANTERIOR (eventcap, walletevent, idempotency_key)
--   psql -v preparar_abort=1 ...                           -> acrescenta o evento 99 (capacity - quant > ativas): a migração tem de abortar
--   psql -v remover_abort=1 ...                            -> tira o evento 99
--   psql -v inalterado=1  ...                              -> confere que uma migração ABORTADA não mudou nada
--   psql -v migrado=1     ...                              -> confere o estado DEPOIS da 1a aplicação (e guarda os holds em verif)
--   psql -v idempotente=1 ...                              -> confere que a 2a aplicação não mudou nenhum hold nem hold_id
--   psql -v restaurado=1  ...                              -> confere o rollback do cabeçalho (dados e schema de antes)
--   psql -v snapshot=1    ...                              -> imprime a FORMA dos schemas reservation e stock (colunas, constraints, índices, donos)
--
-- Cenário (todas as reservas são "antigas": sem hold, sem saga):
--   evento 1: capacity 10, quant 3 -> 10 ativas (7 cas debitaram, 3 naive não)  => 7 holds debitados (os 7 mais antigos), 3 não
--   evento 2: ILIMITADO (-1/-1) com 4 ativas                                   => 4 holds, nenhum debitado
--   evento 3: capacity 6, quant 6, só reservas expired/cancelled               => nenhum hold; hold_id nulo
--   evento 4: capacity 5, quant 0 com 8 ativas (o naive oversselou)            => 5 holds debitados, 3 não
--   evento 5: capacity 4, quant 2 com 2 ativas (todas debitaram)               => 2 debitados
--   evento 6: capacity 8, quant 8, sem reservas
--   evento 99 (só no abort): capacity 5, quant 0 com 3 ativas                  => capacity - quant (5) > ativas (3): aborta

\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';

\if :{?preparar}
\echo '=== preparar: cenario S9 no schema reservation anterior a 20261029 ==='

INSERT INTO reservation.eventcap (event_id, capacity, quant, buy_time_limit) VALUES
    (1, 10, 3, '2030-01-01 10:00:00+00'),
    (2, -1, -1, NULL),
    (3, 6, 6, NULL),
    (4, 5, 0, '2030-06-01 00:00:00+00'),
    (5, 4, 2, NULL),
    (6, 8, 8, NULL);

-- created_at crescente por usuário dentro do evento (o "mais antigo" do backfill)
INSERT INTO reservation.walletevent (user_id, event_id, status, expires_at, created_at, version)
SELECT 100 + g, 1, CASE WHEN g % 3 = 0 THEN 'confirmed' ELSE 'pending' END,
       CASE WHEN g % 3 = 0 THEN NULL ELSE '2030-01-01 10:02:00+00'::timestamptz END,
       timestamp '2029-12-31 10:00:00' + g * interval '1 minute', 1
  FROM generate_series(1, 10) g;
INSERT INTO reservation.walletevent (user_id, event_id, status, expires_at, created_at, version)
SELECT 200 + g, 2, CASE WHEN g % 2 = 0 THEN 'confirmed' ELSE 'pending' END, NULL,
       timestamp '2029-12-31 11:00:00' + g * interval '1 minute', 1
  FROM generate_series(1, 4) g;
INSERT INTO reservation.walletevent (user_id, event_id, status, expires_at, created_at, version) VALUES
    (301, 3, 'cancelled', NULL, '2029-12-31 12:00:00', 2),
    (302, 3, 'expired', NULL, '2029-12-31 12:01:00', 2);
INSERT INTO reservation.walletevent (user_id, event_id, status, expires_at, created_at, version)
SELECT 400 + g, 4, CASE WHEN g % 2 = 0 THEN 'confirmed' ELSE 'pending' END, NULL,
       timestamp '2029-12-31 13:00:00' + g * interval '1 minute', 1
  FROM generate_series(1, 8) g;
INSERT INTO reservation.walletevent (user_id, event_id, status, expires_at, created_at, version) VALUES
    (501, 5, 'confirmed', NULL, '2029-12-31 14:00:00', 1),
    (502, 5, 'pending', '2030-01-01 10:02:00+00', '2029-12-31 14:01:00', 1);

INSERT INTO reservation.idempotency_key (idempotency_key, user_id, event_id, expires_at) VALUES
    ('k-antiga-1', 101, 1, '2030-01-01 10:02:00+00'),
    ('k-antiga-2', 201, 2, '2030-01-01 10:03:00+00'),
    ('k-antiga-3', 401, 4, '2030-01-01 10:04:00+00');

CREATE SCHEMA verif;
CREATE TABLE verif.eventcap_antes AS SELECT * FROM reservation.eventcap;
CREATE TABLE verif.walletevent_antes AS SELECT * FROM reservation.walletevent;
CREATE TABLE verif.idempotency_antes AS SELECT * FROM reservation.idempotency_key;
\echo 'ok: 6 eventcap, 26 walletevent (24 ativas), 3 idempotency_key antigas'
\endif

\if :{?preparar_abort}
INSERT INTO reservation.eventcap (event_id, capacity, quant) VALUES (99, 5, 0);
INSERT INTO reservation.walletevent (user_id, event_id, status, created_at, version) VALUES
    (991, 99, 'pending', now(), 1), (992, 99, 'pending', now(), 1), (993, 99, 'confirmed', now(), 1);
\endif

\if :{?remover_abort}
DELETE FROM reservation.walletevent WHERE event_id = 99;
DELETE FROM reservation.eventcap WHERE event_id = 99;
\endif

\if :{?inalterado}
\echo '=== inalterado: migracao abortada nao mudou nada ==='
DO $$
DECLARE n bigint;
BEGIN
    IF to_regnamespace('stock') IS NOT NULL THEN
        RAISE EXCEPTION 'a migracao abortada deixou o schema stock criado';
    END IF;
    IF to_regclass('reservation.eventcap') IS NULL THEN
        RAISE EXCEPTION 'a migracao abortada moveu o eventcap';
    END IF;
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema = 'reservation' AND table_name = 'walletevent' AND column_name = 'hold_id';
    IF n <> 0 THEN RAISE EXCEPTION 'a migracao abortada criou walletevent.hold_id'; END IF;
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema = 'reservation' AND table_name = 'idempotency_key' AND column_name = 'saga_status';
    IF n <> 0 THEN RAISE EXCEPTION 'a migracao abortada criou idempotency_key.saga_status'; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM reservation.walletevent WHERE event_id <> 99 EXCEPT SELECT * FROM verif.walletevent_antes) x;
    IF n <> 0 THEN RAISE EXCEPTION 'walletevent mudou (% linhas)', n; END IF;
END
$$;
\echo 'ok: sem schema stock, eventcap onde estava, sem colunas novas, walletevent igual'
\endif

\if :{?migrado}
\echo '=== migrado: estado depois da 20261029 (cenario S9) ==='
DO $$
DECLARE
    n bigint;
    r record;
    def text;
BEGIN
    -- o eventcap mudou de schema, com todos os dados e sem tocar no quant
    IF to_regclass('reservation.eventcap') IS NOT NULL OR to_regclass('stock.eventcap') IS NULL THEN
        RAISE EXCEPTION 'eventcap deveria estar em stock e nao em reservation';
    END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM stock.eventcap EXCEPT SELECT * FROM verif.eventcap_antes) x;
    IF n <> 0 THEN RAISE EXCEPTION 'stock.eventcap diferente do original (% linhas)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM verif.eventcap_antes EXCEPT SELECT * FROM stock.eventcap) x;
    IF n <> 0 THEN RAISE EXCEPTION 'stock.eventcap perdeu linhas (%)', n; END IF;

    -- um hold held por reserva ativa (24) e nenhum para as inativas
    SELECT count(*) INTO n FROM stock.hold;
    IF n <> 24 THEN RAISE EXCEPTION 'esperava 24 holds (uma por reserva ativa), achei %', n; END IF;
    SELECT count(*) INTO n FROM stock.hold WHERE status <> 'held';
    IF n <> 0 THEN RAISE EXCEPTION 'todo hold do backfill deveria estar held'; END IF;

    -- decremented por evento: EXATAMENTE capacity - quant nos limitados, 0 no ilimitado
    FOR r IN SELECT e AS ev, d FROM (VALUES (1, 7), (2, 0), (3, 0), (4, 5), (5, 2), (6, 0)) v(e, d) LOOP
        SELECT count(*) INTO n FROM stock.hold WHERE event_id = r.ev AND decremented;
        IF n <> r.d THEN RAISE EXCEPTION 'evento %: esperava % holds decremented, achei %', r.ev, r.d, n; END IF;
    END LOOP;
    SELECT count(*) INTO n FROM stock.hold WHERE event_id = 2;
    IF n <> 4 THEN RAISE EXCEPTION 'evento ilimitado: esperava 4 holds (todos nao debitados), achei %', n; END IF;
    SELECT count(*) INTO n FROM stock.hold WHERE event_id = 1 AND NOT decremented;
    IF n <> 3 THEN RAISE EXCEPTION 'evento 1: esperava 3 holds nao debitados (as 3 naive), achei %', n; END IF;
    SELECT count(*) INTO n FROM stock.hold WHERE event_id = 4 AND NOT decremented;
    IF n <> 3 THEN RAISE EXCEPTION 'evento 4: esperava 3 holds nao debitados (o oversell naive), achei %', n; END IF;

    -- os debitados sao os MAIS ANTIGOS de cada evento (created_at da reserva)
    FOR r IN SELECT DISTINCT h.event_id FROM stock.hold h LOOP
        SELECT count(*) INTO n
          FROM stock.hold hd JOIN reservation.walletevent wd ON wd.hold_id = hd.hold_id
          JOIN stock.hold hn ON hn.event_id = hd.event_id AND NOT hn.decremented
          JOIN reservation.walletevent wn ON wn.hold_id = hn.hold_id
         WHERE hd.event_id = r.event_id AND hd.decremented AND wd.created_at > wn.created_at;
        IF n <> 0 THEN RAISE EXCEPTION 'evento %: um hold debitado e mais novo que um nao debitado', r.event_id; END IF;
    END LOOP;

    -- cada reserva ativa aponta para o hold dela (mesmo evento e usuario); as inativas ficam sem hold_id
    SELECT count(*) INTO n FROM reservation.walletevent w
     WHERE w.status IN ('pending', 'confirmed')
       AND NOT EXISTS (SELECT 1 FROM stock.hold h WHERE h.hold_id = w.hold_id AND h.event_id = w.event_id
                          AND h.user_id = w.user_id AND h.status = 'held');
    IF n <> 0 THEN RAISE EXCEPTION '% reserva(s) ativa(s) sem hold held correspondente', n; END IF;
    SELECT count(*) INTO n FROM reservation.walletevent WHERE status NOT IN ('pending', 'confirmed') AND hold_id IS NOT NULL;
    IF n <> 0 THEN RAISE EXCEPTION '% reserva(s) inativa(s) com hold_id', n; END IF;
    SELECT count(*) INTO n FROM stock.hold h
     WHERE NOT EXISTS (SELECT 1 FROM reservation.walletevent w WHERE w.hold_id = h.hold_id);
    IF n <> 0 THEN RAISE EXCEPTION '% hold(s) sem reserva (vaga presa para sempre)', n; END IF;

    -- A INVARIANTE (S9): quant = capacity - count(held AND decremented) em todo evento limitado
    SELECT count(*) INTO n FROM stock.eventcap c
     WHERE c.capacity <> -1
       AND c.quant <> c.capacity - (SELECT count(*) FROM stock.hold h WHERE h.event_id = c.event_id AND h.status = 'held' AND h.decremented);
    IF n <> 0 THEN RAISE EXCEPTION 'invariante quant = capacity - holds debitados violada em % evento(s)', n; END IF;

    -- o resto da walletevent nao mudou
    SELECT count(*) INTO n FROM (SELECT user_id, event_id, status, expires_at, created_at, updated_at, version FROM reservation.walletevent
                                 EXCEPT SELECT user_id, event_id, status, expires_at, created_at, updated_at, version FROM verif.walletevent_antes) x;
    IF n <> 0 THEN RAISE EXCEPTION 'walletevent mudou alem do hold_id (% linhas)', n; END IF;

    -- idempotency_key: linhas antigas viram reserved, sem hold, com expires_at igual e updated_at preenchido
    SELECT count(*) INTO n FROM reservation.idempotency_key;
    IF n <> 3 THEN RAISE EXCEPTION 'idempotency_key mudou de contagem: %', n; END IF;
    SELECT count(*) INTO n FROM reservation.idempotency_key k JOIN verif.idempotency_antes a USING (user_id, idempotency_key)
     WHERE k.saga_status = 'reserved' AND k.hold_id IS NULL AND k.resolved_at IS NULL AND k.resolved_by IS NULL
       AND k.updated_at IS NOT NULL AND k.expires_at = a.expires_at AND k.event_id = a.event_id AND k.created_at = a.created_at;
    IF n <> 3 THEN RAISE EXCEPTION 'as 3 chaves antigas deveriam ser reserved/sem hold/iguais, achei % certas', n; END IF;

    -- formato novo: expires_at nulo, saga_status sem default, updated_at com default now(), CHECKs e indice parcial
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema = 'reservation' AND table_name = 'idempotency_key' AND column_name = 'expires_at' AND is_nullable = 'YES';
    IF n <> 1 THEN RAISE EXCEPTION 'expires_at deveria aceitar nulo'; END IF;
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema = 'reservation' AND table_name = 'idempotency_key' AND column_name = 'saga_status'
       AND is_nullable = 'NO' AND column_default IS NULL;
    IF n <> 1 THEN RAISE EXCEPTION 'saga_status deveria ser NOT NULL sem DEFAULT (como o init)'; END IF;
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema = 'reservation' AND table_name = 'idempotency_key' AND column_name = 'updated_at'
       AND is_nullable = 'NO' AND column_default LIKE '%now()%';
    IF n <> 1 THEN RAISE EXCEPTION 'updated_at deveria ser NOT NULL DEFAULT now()'; END IF;
    BEGIN
        INSERT INTO reservation.idempotency_key (idempotency_key, user_id, event_id, saga_status) VALUES ('x', 1, 1, 'qualquer');
        RAISE EXCEPTION 'o CHECK de saga_status deveria recusar';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'saga_status invalido recusado pelo CHECK (como esperado)';
    END;
    SELECT indexdef INTO def FROM pg_indexes
     WHERE schemaname = 'reservation' AND indexname = 'idx_idempotency_key_saga_pendente';
    IF def IS NULL OR def NOT LIKE '%(updated_at)%' OR def NOT LIKE '%reserving%' OR def NOT LIKE '%compensating%' THEN
        RAISE EXCEPTION 'indice parcial do reaper inesperado: %', def;
    END IF;

    -- dono admin do que e novo (se a role existe)
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'admin') THEN
        SELECT count(*) INTO n FROM pg_tables WHERE schemaname = 'stock' AND tableowner <> 'admin';
        IF n <> 0 THEN RAISE EXCEPTION 'tabelas do schema stock deveriam ser do admin'; END IF;
        SELECT count(*) INTO n FROM pg_namespace s JOIN pg_roles o ON o.oid = s.nspowner WHERE s.nspname = 'stock' AND o.rolname <> 'admin';
        IF n <> 0 THEN RAISE EXCEPTION 'schema stock deveria ser do admin'; END IF;
    END IF;
END
$$;
-- guarda o estado para a conferencia da 2a aplicacao
CREATE TABLE verif.holds_pos1 AS SELECT * FROM stock.hold;
CREATE TABLE verif.walletevent_pos1 AS SELECT * FROM reservation.walletevent;
SELECT 'invariante: ' || string_agg('evento ' || c.event_id || ' quant=' || c.quant || ' = ' || c.capacity || ' - '
       || (SELECT count(*) FROM stock.hold h WHERE h.event_id = c.event_id AND h.status = 'held' AND h.decremented), '; ' ORDER BY c.event_id)
  FROM stock.eventcap c WHERE c.capacity <> -1;
\echo 'ok: eventcap em stock, 24 holds, debitados = capacity - quant (os mais antigos), invariante verde, idempotency_key reserved'
\endif

\if :{?idempotente}
\echo '=== idempotente: a 2a aplicacao nao mexeu em nada ==='
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM (SELECT * FROM stock.hold EXCEPT SELECT * FROM verif.holds_pos1) x;
    IF n <> 0 THEN RAISE EXCEPTION 'a 2a aplicacao mudou holds (% linhas)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM verif.holds_pos1 EXCEPT SELECT * FROM stock.hold) x;
    IF n <> 0 THEN RAISE EXCEPTION 'a 2a aplicacao perdeu holds (% linhas)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM reservation.walletevent EXCEPT SELECT * FROM verif.walletevent_pos1) x;
    IF n <> 0 THEN RAISE EXCEPTION 'a 2a aplicacao mudou walletevent/hold_id (% linhas)', n; END IF;
END
$$;
\echo 'ok: mesmos 24 holds, mesmos hold_id'
\endif

\if :{?restaurado}
\echo '=== restaurado: rollback do cabecalho da migracao ==='
DO $$
DECLARE n bigint;
BEGIN
    IF to_regnamespace('stock') IS NOT NULL THEN RAISE EXCEPTION 'o rollback deveria dropar o schema stock'; END IF;
    IF to_regclass('reservation.eventcap') IS NULL THEN RAISE EXCEPTION 'o rollback deveria devolver o eventcap a reservation'; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM reservation.eventcap EXCEPT SELECT * FROM verif.eventcap_antes) x;
    IF n <> 0 THEN RAISE EXCEPTION 'eventcap nao voltou aos valores originais (% linhas)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM verif.eventcap_antes EXCEPT SELECT * FROM reservation.eventcap) x;
    IF n <> 0 THEN RAISE EXCEPTION 'eventcap perdeu linhas no rollback (%)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM reservation.walletevent EXCEPT SELECT * FROM verif.walletevent_antes) x;
    IF n <> 0 THEN RAISE EXCEPTION 'walletevent nao voltou ao original (% linhas)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM verif.walletevent_antes EXCEPT SELECT * FROM reservation.walletevent) x;
    IF n <> 0 THEN RAISE EXCEPTION 'walletevent perdeu linhas no rollback (%)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM reservation.idempotency_key EXCEPT SELECT * FROM verif.idempotency_antes) x;
    IF n <> 0 THEN RAISE EXCEPTION 'idempotency_key nao voltou ao original (% linhas)', n; END IF;
    SELECT count(*) INTO n FROM (SELECT * FROM verif.idempotency_antes EXCEPT SELECT * FROM reservation.idempotency_key) x;
    IF n <> 0 THEN RAISE EXCEPTION 'idempotency_key perdeu linhas no rollback (%)', n; END IF;
END
$$;
\echo 'ok: eventcap/walletevent/idempotency_key iguais aos de antes da migracao, sem schema stock'
\endif

\if :{?snapshot}
-- Forma dos schemas reservation e stock (colunas, constraints, indices, donos): o .sh compara (a) o banco migrado com um banco montado pelos
-- init-scripts atuais (13 + 16) e (b) o banco depois do rollback com o de antes da migracao (o schema de 35955ef).
SELECT 'col|' || table_schema || '.' || table_name || '|' || column_name || '|' || data_type || '|' || is_nullable || '|' || coalesce(column_default, '')
  FROM information_schema.columns
 WHERE table_schema IN ('reservation', 'stock')
UNION ALL
SELECT 'con|' || conrelid::regclass::text || '|' || conname || '|' || pg_get_constraintdef(oid) || '|' || convalidated
  FROM pg_constraint
 WHERE connamespace IN ('reservation'::regnamespace, coalesce(to_regnamespace('stock'), 'reservation'::regnamespace))
   AND conrelid::regclass::text ~ '^(reservation|stock)\.'
UNION ALL
SELECT 'idx|' || schemaname || '.' || tablename || '|' || indexname || '|' || indexdef
  FROM pg_indexes
 WHERE schemaname IN ('reservation', 'stock')
UNION ALL
SELECT 'own|' || schemaname || '.' || tablename || '|' || tableowner
  FROM pg_tables
 WHERE schemaname IN ('reservation', 'stock')
ORDER BY 1;
\endif
