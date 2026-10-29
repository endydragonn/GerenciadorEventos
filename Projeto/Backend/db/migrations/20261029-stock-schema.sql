-- Migração: schema do serviço stock + log da saga no reservation (issue #47, Fase 7, saga stock + wallet). Idempotente.
-- Aplicável a quente num banco já existente, SEM recriar o volume, com o gerenciador-backend, o gerenciador-reservation, o gerenciador-catalog
-- e o gerenciador-stock PARADOS (o ALTER TABLE ... SET SCHEMA pede ACCESS EXCLUSIVE e a imagem antiga ainda lê/escreve reservation.eventcap).
-- Pré-requisitos: a 20261025 (schema reservation) e a 20261027 (instantes em timestamptz) já aplicadas.
-- Verificada por migrations/verify-20261029-migration.sh (banco descartável com o cenário da S9: capacidade 10 com 7 reservas cas + 3 naive
-- antigas, evento ilimitado com reservas, reservas canceladas; 2 aplicações; abortos; rollback abaixo).
--
-- BACKUP antes (só vale até reabrir o tráfego; nunca dentro do repo):
--   docker exec gerenciador-db pg_dump -h localhost -U admin --clean --if-exists -n reservation meu_banco > <fora do repo>/backup-reservation-pre-47.sql
-- APLICAR (como supabase_admin; roda 2x sem efeito colateral, o 2o run só emite NOTICEs):
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 \
--     < Projeto/Backend/db/migrations/20261029-stock-schema.sql
-- ROLLBACK (tudo parado, inclusive o stock; ANTES, deixe o group `stock` do tópico reservation.wallet sem lag, para as devoluções por
-- cancelamento/expiração pendentes chegarem ao quant, e as sagas reserving/compensating resolvidas pelo reaper). O quant NÃO precisa de ajuste:
-- a migração não o altera, e as devoluções feitas depois dela já o atualizaram. Perde o estado das sagas em voo (as linhas não terminais saem) e
-- o ledger de holds; a alternativa é restaurar o pg_dump acima:
--   DELETE FROM reservation.idempotency_key WHERE saga_status <> 'reserved';
--   DROP TABLE stock.hold;
--   ALTER TABLE stock.eventcap SET SCHEMA reservation;
--   DROP INDEX IF EXISTS reservation.idx_idempotency_key_saga_pendente;
--   ALTER TABLE reservation.walletevent DROP COLUMN hold_id;
--   ALTER TABLE reservation.idempotency_key DROP COLUMN hold_id, DROP COLUMN saga_status, DROP COLUMN updated_at, DROP COLUMN resolved_at, DROP COLUMN resolved_by;
--   ALTER TABLE reservation.idempotency_key ALTER COLUMN expires_at SET NOT NULL;
--   DROP SCHEMA stock;
-- (ou restaurar o pg_dump acima: --clean --if-exists recria as tabelas do schema reservation).
--
-- O que faz:
--   1) cria o schema stock (dono admin) e MOVE para ele reservation.eventcap, com os dados (ALTER TABLE ... SET SCHEMA mantém dados, índices,
--      constraints e dono). ABORTA se existirem os DOIS (reservation.eventcap e stock.eventcap): o estado é ambíguo e escolher um perderia dado.
--      Com nenhum dos dois, aborta pedindo a 20261025. Exige buy_time_limit em timestamptz (20261027).
--   2) cria stock.hold, o ledger de holds (hold_id uuid PK, event_id, user_id, decremented, status held|released, created_at/updated_at
--      timestamptz) e o índice (event_id, status).
--   3) reservation.walletevent ganha hold_id (uuid, nulo): o hold da reserva corrente.
--   4) reservation.idempotency_key vira também o log da saga: hold_id, saga_status (NOT NULL; as linhas existentes ficam 'reserved'; o init não
--      tem DEFAULT, então o DEFAULT provisório é removido), updated_at (NOT NULL DEFAULT now()), resolved_at, resolved_by (inline|reaper) e
--      os CHECKs; expires_at passa a aceitar nulo (é gravado na tx2); índice parcial (updated_at) das sagas não terminais, para o reaper.
--   5) BACKFILL (só na 1a aplicação, a que moveu o eventcap): para cada walletevent pending/confirmed sem hold_id, hold_id = gen_random_uuid() e
--      um stock.hold 'held'. Por evento LIMITADO, EXATAMENTE capacity - quant desses holds (os mais antigos por created_at, desempate por
--      user_id) ficam com decremented = true e o resto com false; evento ilimitado: todos false. Por que essa conta: as reservas antigas do
--      braço naive não se distinguem das do cas. Marcar todas como decremented devolveria vagas nunca tiradas ao cancelá-las (o LEAST(quant + n,
--      capacity) limita o quant, não as ativas). Ex.: capacidade 10, 7 cas + 3 naive => quant 3 e 7 holds debitados; vale a invariante
--      quant = capacity - count(held AND decremented) logo depois da migração. Se capacity - quant for MAIOR que as ativas de algum evento (dado
--      já inconsistente), a migração ABORTA listando os eventos, sem mudar nada (é uma transação só).
--   Reservas antigas inativas (expired/cancelled) não ganham hold: o evento delas passa a poder ser apagado (o RESTRICT agora olha stock.hold).
-- Roda como supabase_admin, por isso os OWNER TO admin explícitos. lock_timeout de 5 s: se algum serviço ainda estiver de pé a migração falha
-- limpa em vez de travar o banco.
-- Para bancos NOVOS o schema já vem dos init-scripts 13-reservation.sql e 16-stock.sql.

SET client_encoding = 'UTF8';

BEGIN;

SET LOCAL lock_timeout = '5s';

CREATE SCHEMA IF NOT EXISTS stock;
ALTER SCHEMA stock OWNER TO admin;

-- 1) eventcap: reservation -> stock --------------------------------------------------------------------------------------------------
DO $$
DECLARE
    em_reservation boolean := to_regclass('reservation.eventcap') IS NOT NULL;
    em_stock       boolean := to_regclass('stock.eventcap') IS NOT NULL;
    inconsistentes text;
BEGIN
    IF em_reservation AND em_stock THEN
        RAISE EXCEPTION 'existem reservation.eventcap E stock.eventcap: estado ambíguo, a migração não escolhe um (o outro perderia dado). Confira qual tem os dados e apague o resto na mão';
    END IF;
    IF NOT em_reservation AND NOT em_stock THEN
        RAISE EXCEPTION 'nem reservation.eventcap nem stock.eventcap existem: aplique a 20261025 antes';
    END IF;

    IF em_reservation THEN
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = 'reservation' AND table_name = 'eventcap' AND column_name = 'buy_time_limit'
                          AND data_type = 'timestamp with time zone') THEN
            RAISE EXCEPTION 'reservation.eventcap.buy_time_limit não é timestamptz: aplique a 20261027 antes';
        END IF;

        -- o backfill debita EXATAMENTE capacity - quant holds: se isso passa das ativas, o dado já era inconsistente
        SELECT string_agg(format('evento %s (capacity %s, quant %s, ativas %s)', c.event_id, c.capacity, c.quant, coalesce(a.n, 0)),
                          '; ' ORDER BY c.event_id)
          INTO inconsistentes
          FROM reservation.eventcap c
          LEFT JOIN (SELECT event_id, count(*) AS n FROM reservation.walletevent
                      WHERE status IN ('pending', 'confirmed') GROUP BY event_id) a USING (event_id)
         WHERE c.capacity <> -1 AND c.capacity - c.quant > coalesce(a.n, 0);
        IF inconsistentes IS NOT NULL THEN
            RAISE EXCEPTION 'capacity - quant maior que as reservas ativas em: %. Corrija o quant (ou as reservas) desses eventos e rode de novo', inconsistentes;
        END IF;

        ALTER TABLE reservation.eventcap SET SCHEMA stock;
        RAISE NOTICE 'reservation.eventcap movida para o schema stock';
    ELSE
        RAISE NOTICE 'stock.eventcap já existe (reservation.eventcap não): nada a mover';
    END IF;
    -- marca (só nesta transação) que ESTA aplicação moveu o eventcap: só ela faz o backfill dos holds
    PERFORM set_config('mig47.moveu', em_reservation::text, true);
END
$$;

-- 2) stock.hold ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock.hold
(
    hold_id uuid NOT NULL,
    event_id integer NOT NULL,
    user_id integer NOT NULL,
    decremented boolean NOT NULL,
    status text COLLATE pg_catalog."default" NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT hold_pkey PRIMARY KEY (hold_id),
    CONSTRAINT hold_status_check CHECK (status IN ('held', 'released'))
)

TABLESPACE pg_default;

ALTER TABLE IF EXISTS stock.hold OWNER TO admin;

CREATE INDEX IF NOT EXISTS idx_hold_event_status
    ON stock.hold USING btree (event_id, status);

COMMENT ON TABLE stock.hold IS
    'Ledger de holds da saga stock + wallet (#47). hold_id = idempotency key do passo; released sem débito = lápide.';

-- 3) walletevent.hold_id ------------------------------------------------------------------------------------------------------------
ALTER TABLE reservation.walletevent ADD COLUMN IF NOT EXISTS hold_id uuid;

-- 4) idempotency_key = log da saga --------------------------------------------------------------------------------------------------
ALTER TABLE reservation.idempotency_key
    ADD COLUMN IF NOT EXISTS hold_id uuid,
    ADD COLUMN IF NOT EXISTS saga_status text NOT NULL DEFAULT 'reserved',
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS resolved_at timestamptz,
    ADD COLUMN IF NOT EXISTS resolved_by text;
-- o init não tem DEFAULT em saga_status: quem insere diz o estado. Só as linhas antigas nasceram 'reserved'.
ALTER TABLE reservation.idempotency_key ALTER COLUMN saga_status DROP DEFAULT;
ALTER TABLE reservation.idempotency_key ALTER COLUMN expires_at DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'reservation.idempotency_key'::regclass AND conname = 'idempotency_key_saga_status_check') THEN
        ALTER TABLE reservation.idempotency_key ADD CONSTRAINT idempotency_key_saga_status_check
            CHECK (saga_status IN ('reserving', 'reserved', 'compensating', 'compensated'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'reservation.idempotency_key'::regclass AND conname = 'idempotency_key_resolved_by_check') THEN
        ALTER TABLE reservation.idempotency_key ADD CONSTRAINT idempotency_key_resolved_by_check
            CHECK (resolved_by IN ('inline', 'reaper'));
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_idempotency_key_saga_pendente ON reservation.idempotency_key (updated_at)
    WHERE saga_status IN ('reserving', 'compensating');

-- 5) backfill dos holds (só na aplicação que moveu o eventcap) ----------------------------------------------------------------------
DO $$
DECLARE
    holds    bigint;
    debitados bigint;
BEGIN
    IF current_setting('mig47.moveu', true) IS DISTINCT FROM 'true' THEN
        RAISE NOTICE 'backfill dos holds já feito (o eventcap não foi movido nesta aplicação): nada a fazer';
        RETURN;
    END IF;

    WITH novos AS (
        SELECT w.user_id, w.event_id, gen_random_uuid() AS hold_id,
               row_number() OVER (PARTITION BY w.event_id ORDER BY w.created_at, w.user_id) AS rn,
               c.capacity, c.quant
          FROM reservation.walletevent w
          LEFT JOIN stock.eventcap c ON c.event_id = w.event_id
         WHERE w.status IN ('pending', 'confirmed') AND w.hold_id IS NULL
    ), marcados AS ( -- data-modifying CTE: executa mesmo sem ser lida
        UPDATE reservation.walletevent w SET hold_id = n.hold_id
          FROM novos n
         WHERE w.user_id = n.user_id AND w.event_id = n.event_id
        RETURNING 1
    ), inseridos AS (
        INSERT INTO stock.hold (hold_id, event_id, user_id, decremented, status)
        SELECT n.hold_id, n.event_id, n.user_id,
               -- limitado: exatamente capacity - quant holds (os mais antigos) debitados; ilimitado ou sem eventcap: nenhum
               n.capacity IS NOT NULL AND n.capacity <> -1 AND n.rn <= n.capacity - n.quant,
               'held'
          FROM novos n
        RETURNING decremented
    )
    SELECT count(*), count(*) FILTER (WHERE decremented) INTO holds, debitados FROM inseridos;
    RAISE NOTICE 'backfill: % hold(s) held criados, % com decremented = true', holds, debitados;
END
$$;

COMMIT;
