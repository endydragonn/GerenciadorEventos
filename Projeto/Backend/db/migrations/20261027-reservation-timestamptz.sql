-- Migração: instantes do schema reservation em timestamptz + índice por event_id + CHECK de capacity (issue #58). Idempotente.
-- Aplicável a quente num banco já existente, SEM recriar o volume, com o gerenciador-backend e o gerenciador-reservation PARADOS
-- (a imagem antiga continua gravando/lendo o mesmo schema; o ALTER TYPE reescreve a tabela e pede ACCESS EXCLUSIVE).
-- Pré-requisito: a 20261025 (schema reservation) já aplicada.
--
-- BACKUP antes (só vale até reabrir o tráfego; nunca dentro do repo):
--   docker exec gerenciador-db pg_dump -h localhost -U admin --clean --if-exists -n reservation meu_banco > <fora do repo>/backup-reservation-pre-58.sql
-- APLICAR (como supabase_admin; roda 2x sem efeito colateral, o 2o run só emite NOTICEs):
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 \
--     < Projeto/Backend/db/migrations/20261027-reservation-timestamptz.sql
-- ROLLBACK (serviços parados): tipos de volta, sem o índice e com o CHECK antigo. O horário de parede volta como era (UTC):
--   ALTER TABLE reservation.eventcap    ALTER COLUMN buy_time_limit TYPE timestamp without time zone USING buy_time_limit AT TIME ZONE 'UTC';
--   ALTER TABLE reservation.walletevent ALTER COLUMN expires_at     TYPE timestamp without time zone USING expires_at     AT TIME ZONE 'UTC';
--   DROP INDEX IF EXISTS reservation.idx_walletevent_event_id;
--   ALTER TABLE reservation.eventcap DROP CONSTRAINT eventcap_capacity_valid;
--   ALTER TABLE reservation.eventcap ADD CONSTRAINT eventcap_capacity_valid CHECK (capacity >= -1);
-- (ou restaurar o pg_dump acima: --clean --if-exists recria as tabelas do schema reservation).
--
-- O que faz:
--   1) reservation.eventcap.buy_time_limit e reservation.walletevent.expires_at: timestamp -> timestamptz. Antes eram horário de
--      parede sem fuso, comparado com CURRENT_TIMESTAMP (que é instante): a comparação dependia do fuso da sessão.
--      A conversão usa USING col AT TIME ZONE 'UTC' EXPLÍCITO (nunca o cast implícito, que usaria o fuso da sessão). O 'UTC' é o
--      fuso em que os valores foram gravados, MEDIDO em 2026-10-26:
--        * Postgres (meu_banco): SHOW timezone = UTC (TimeZone e log_timezone vêm do configuration file);
--        * JVM: Etc/UTC na imagem de teste maven:3.9.7-eclipse-temurin-21-jammy (TimeZone.getDefault() = Etc/UTC;
--          Timestamp.valueOf(LocalDateTime.now()) = hora UTC), no runtime eclipse-temurin:25-jre-jammy e na imagem
--          gerenciador-eventos-reservation (/etc/localtime -> Etc/UTC, sem TZ); nenhum TZ/user.timezone no docker-compose.yml,
--          nos Dockerfiles nem no run-tests.sh. O PgJDBC grava o LocalDateTime como Timestamp no fuso da JVM, então o horário de
--          parede já salvo é UTC.
--      RESSALVA: o host Windows é America/Sao_Paulo (UTC-3). Linhas gravadas por um `mvn spring-boot:run` rodado FORA do Docker
--      teriam o horário de parede em UTC-3, e esta conversão as deslocaria 3 h. Antes de aplicar em um banco que recebeu dados
--      assim, troque o 'UTC' acima pelo fuso real (America/Sao_Paulo). No meu_banco as duas tabelas tinham 0 linhas na medição.
--      O código Java NÃO mudou: LocalDateTime <-> Timestamp (fuso da JVM) já roda contra timestamptz em idempotency_key.expires_at.
--      created_at/updated_at do eventcap e do walletevent seguem timestamp (carimbos de auditoria, fora do escopo).
--      Cada ALTER só age se a coluna ainda for "timestamp without time zone" (guarda por information_schema).
--   2) índice idx_walletevent_event_id em reservation.walletevent (event_id): a PK (user_id, event_id) não serve busca só por event_id.
--   3) eventcap_capacity_valid passa de CHECK (capacity >= -1) para CHECK (capacity = -1 OR capacity >= 1): capacity 0 é recusado
--      (o catalog já exige lot_quantity >= 1 ou -1). Se ainda existir linha com capacity 0 (ou < -1) a migração ABORTA com a
--      contagem, sem converter nada: corrija o dado na mão (ou apague o evento de teste) e rode de novo. A constraint é localizada
--      por conrelid + conname (o nome sozinho pode existir em outra tabela) e a DEFINIÇÃO é comparada, não só a existência.
-- Roda como supabase_admin (dono das tabelas segue admin: ALTER ... TYPE e ADD CONSTRAINT não mudam o dono). lock_timeout de 5 s:
-- se algum serviço ainda estiver de pé a migração falha limpa em vez de travar o banco.
-- Para bancos NOVOS o schema já vem do init-script 13-reservation.sql.

SET client_encoding = 'UTF8';

BEGIN;

SET LOCAL lock_timeout = '5s';

-- 1) timestamp -> timestamptz -----------------------------------------------------------------------------------------
DO $$
DECLARE
    col record;
BEGIN
    FOR col IN
        SELECT * FROM (VALUES ('eventcap', 'buy_time_limit'), ('walletevent', 'expires_at')) AS v(tabela, coluna)
    LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'reservation' AND table_name = col.tabela AND column_name = col.coluna
                      AND data_type = 'timestamp without time zone') THEN
            EXECUTE format('ALTER TABLE reservation.%I ALTER COLUMN %I TYPE timestamptz USING %I AT TIME ZONE ''UTC''',
                           col.tabela, col.coluna, col.coluna);
            RAISE NOTICE 'reservation.%.% convertida para timestamptz (AT TIME ZONE UTC)', col.tabela, col.coluna;
        ELSIF EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = 'reservation' AND table_name = col.tabela AND column_name = col.coluna
                         AND data_type = 'timestamp with time zone') THEN
            RAISE NOTICE 'reservation.%.% já está em timestamptz: nada a fazer', col.tabela, col.coluna;
        ELSE
            RAISE EXCEPTION 'reservation.%.% não existe ou tem tipo inesperado: aplique a 20261025 antes', col.tabela, col.coluna;
        END IF;
    END LOOP;
END
$$;

-- 2) índice por event_id ----------------------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_walletevent_event_id ON reservation.walletevent USING btree (event_id);

-- 3) CHECK de capacity ------------------------------------------------------------------------------------------------
DO $$
DECLARE
    def_atual text;
    def_nova  constant text := 'CHECK (((capacity = ''-1''::integer) OR (capacity >= 1)))'; -- como o PG17 imprime (pg_get_constraintdef)
    invalidas bigint;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO def_atual
      FROM pg_constraint
     WHERE conrelid = 'reservation.eventcap'::regclass AND conname = 'eventcap_capacity_valid';

    IF def_atual = def_nova THEN
        RAISE NOTICE 'reservation.eventcap.eventcap_capacity_valid já é CHECK (capacity = -1 OR capacity >= 1): nada a fazer';
        RETURN;
    END IF;

    SELECT count(*) INTO invalidas FROM reservation.eventcap WHERE NOT (capacity = -1 OR capacity >= 1);
    IF invalidas > 0 THEN
        RAISE EXCEPTION 'reservation.eventcap tem % linha(s) com capacity = 0 ou < -1, que o novo CHECK recusa: corrija ou apague essas linhas (select * from reservation.eventcap where capacity = 0 or capacity < -1) e rode de novo', invalidas;
    END IF;

    IF def_atual IS NOT NULL THEN
        ALTER TABLE reservation.eventcap DROP CONSTRAINT eventcap_capacity_valid;
    END IF;
    ALTER TABLE reservation.eventcap
        ADD CONSTRAINT eventcap_capacity_valid CHECK (capacity = -1 OR capacity >= 1);
    RAISE NOTICE 'reservation.eventcap.eventcap_capacity_valid agora é CHECK (capacity = -1 OR capacity >= 1) (era: %)', def_atual;
END
$$;

COMMIT;
