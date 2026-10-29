#!/bin/bash

# verify-20261029-migration.sh
# Harness reproduzível da migração 20261029-stock-schema.sql (issue #47, cenário S9 da spec da saga stock + wallet).
#
# Monta bancos DESCARTÁVEIS no container gerenciador-db (nada toca `meu_banco`) e prova, ponta a ponta:
#   - [A] banco "de antes da #47": os init-scripts do commit anterior (COMMIT_ANTERIOR, default 35955ef; lidos por `git show`) populados com o cenário
#     da S9 (capacidade 10 com 7 reservas cas + 3 naive antigas, evento ilimitado com reservas, reservas canceladas, naive que oversellou);
#   - aborta com os DOIS eventcap (reservation.eventcap e stock.eventcap) e nada muda;
#   - aborta quando capacity - quant > reservas ativas (lista o evento) e nada muda;
#   - 1a aplicação: eventcap em stock com os dados, EXATAMENTE capacity - quant holds debitados por evento limitado (os mais antigos), o ilimitado
#     sem débito, a invariante quant = capacity - holds debitados verde logo depois, idempotency_key antigas viram reserved;
#   - 2a aplicação (re-run): só NOTICEs, nenhum hold nem hold_id mudou;
#   - [B] o estado migrado tem a MESMA forma (colunas, constraints, índices, donos de reservation e stock) de um banco novo montado pelos
#     init-scripts atuais (13-reservation.sql + 16-stock.sql);
#   - o rollback do cabeçalho da migração (extraído do próprio arquivo) devolve o schema de ANTES (mesma forma do banco de antes) e os dados;
#   - DROP dos bancos descartáveis no fim (também em caso de falha).
#
# Cross-platform como o verify-20261027-migration.sh (Git Bash: "pwd -W" e sem conversão de path do MSYS).
# Pré-requisito: docker compose -f Projeto/docker-compose.yml up -d db   e   git com o COMMIT_ANTERIOR no histórico.
# Uso:  Projeto/Backend/db/migrations/verify-20261029-migration.sh      (exit != 0 se qualquer passo falhar)

set -euo pipefail

CONTAINER="gerenciador-db"
MIG_DB="meu_banco_mig47"
INIT_DB="meu_banco_mig47_init"
REMOTE_DIR="/tmp/stock_mig_check"
COMMIT_ANTERIOR="${COMMIT_ANTERIOR:-35955ef}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRACAO="$SCRIPT_DIR/20261029-stock-schema.sql"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
INIT_REL="Projeto/Backend/db/init-scripts"
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        DB_DIR="$(cd "$SCRIPT_DIR/.." && pwd -W)"
        export MSYS_NO_PATHCONV=1
        export MSYS2_ARG_CONV_EXCL='*'
        ;;
    *)
        DB_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
        ;;
esac

echo ""
echo "=================================================="
echo "   VERIFICACAO DA MIGRACAO 20261029  (issue #47 / S9)"
echo "   schema de antes = init-scripts de $COMMIT_ANTERIOR"
echo "=================================================="

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "ERRO: container $CONTAINER nao esta no ar. Suba o banco primeiro:"
    echo "  docker compose -f Projeto/docker-compose.yml up -d db"
    exit 1
fi
git -C "$REPO_ROOT" cat-file -e "$COMMIT_ANTERIOR^{commit}" 2>/dev/null || { echo "ERRO: commit $COMMIT_ANTERIOR nao existe neste repositorio"; exit 1; }

# superusuário pelo socket (trust); o usuário do app (APP_DB_USER, do ambiente do PRÓPRIO container) por localhost (trust).
como_admin_supabase() { docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" '"$*"; }
como_app() { docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -h localhost -U "$APP_DB_USER" '"$*"; }

drop_dbs() {
    for db in "$MIG_DB" "$INIT_DB"; do
        como_admin_supabase "-d postgres -c 'DROP DATABASE IF EXISTS $db WITH (FORCE);'" >/dev/null 2>&1 || true
    done
}
limpar() {
    drop_dbs
    docker exec -i "$CONTAINER" rm -rf "$REMOTE_DIR" >/dev/null 2>&1 || true
}
trap limpar EXIT

falhar() { echo "ERRO: $*"; exit 1; }

criar_banco() { # $1 = nome
    como_admin_supabase "-d postgres -c 'CREATE DATABASE $1;'"
    docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d postgres -c "ALTER DATABASE '"$1"' OWNER TO \"$APP_DB_USER\";"'
}

echo ""
echo "==> Copiando scripts para o container em $REMOTE_DIR"
docker exec -i "$CONTAINER" sh -c "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR'"
docker cp "$DB_DIR/migrations/20261029-stock-schema.sql" "$CONTAINER:$REMOTE_DIR/20261029-stock-schema.sql"
docker cp "$DB_DIR/migrations/verify-20261029-migration.sql" "$CONTAINER:$REMOTE_DIR/verify.sql"
MIG_REMOTA="$REMOTE_DIR/20261029-stock-schema.sql"

# o rollback documentado no cabeçalho da migração, tal como está escrito lá (linhas "--   DELETE/DROP/ALTER ..." entre ROLLBACK e "(ou restaurar")
ROLLBACK_SQL="$(sed -n '/^-- ROLLBACK/,/^-- (ou restaurar/p' "$MIGRACAO" | grep -E '^--   (DELETE|ALTER|DROP)' | sed 's/^--   //')"
[ "$(echo "$ROLLBACK_SQL" | grep -c ';')" -ge 8 ] || falhar "rollback do cabecalho incompleto: $ROLLBACK_SQL"

echo ""
echo "==> [A] Banco descartavel $MIG_DB: init-scripts de $COMMIT_ANTERIOR (schema de ANTES da #47)"
drop_dbs # sobra de uma rodada interrompida
criar_banco "$MIG_DB"
for f in $(git -C "$REPO_ROOT" ls-tree --name-only "$COMMIT_ANTERIOR" "$INIT_REL/" | sort); do
    echo "    aplicando $(basename "$f") de $COMMIT_ANTERIOR"
    git -C "$REPO_ROOT" show "$COMMIT_ANTERIOR:$f" | como_app "-d $MIG_DB -f -" >/dev/null
done
[ "$(como_app "-d $MIG_DB -At -c \"select count(*) from pg_namespace where nspname = 'stock'\"")" = "0" ] || falhar "o schema de antes ja tem o schema stock"
como_app "-d $MIG_DB -At -c \"select to_regclass('reservation.eventcap')\"" | grep -qx 'reservation.eventcap' || falhar "o schema de antes deveria ter reservation.eventcap"
como_app "-d $MIG_DB -v preparar=1 -f $REMOTE_DIR/verify.sql"
SNAP_ANTES="$(como_app "-d $MIG_DB -At -v snapshot=1 -f $REMOTE_DIR/verify.sql")"
[ "$(echo "$SNAP_ANTES" | wc -l)" -ge 20 ] || falhar "snapshot do banco de antes vazio/curto"

echo ""
echo "==> Caso 'os dois eventcap': a migracao aborta e NAO altera nada"
como_app "-d $MIG_DB -c 'CREATE SCHEMA stock; CREATE TABLE stock.eventcap (x integer);'"
if SAIDA="$(como_admin_supabase "-d $MIG_DB -f $MIG_REMOTA" 2>&1)"; then
    falhar "a migracao deveria ter abortado com os dois eventcap"
fi
echo "$SAIDA" | grep -q "existem reservation.eventcap E stock.eventcap" || falhar "mensagem inesperada: $SAIDA"
echo "    abortou com: $(echo "$SAIDA" | grep -o 'existem reservation.eventcap E stock.eventcap: estado ambíguo' | head -1)"
como_app "-d $MIG_DB -c 'DROP SCHEMA stock CASCADE;'"
[ "$(como_app "-d $MIG_DB -At -v snapshot=1 -f $REMOTE_DIR/verify.sql")" = "$SNAP_ANTES" ] || falhar "o aborto dos dois eventcap mudou a forma do schema"

echo ""
echo "==> Caso capacity - quant > ativas (evento 99): a migracao aborta listando o evento e NAO altera nada"
como_app "-d $MIG_DB -v preparar_abort=1 -f $REMOTE_DIR/verify.sql"
if SAIDA="$(como_admin_supabase "-d $MIG_DB -f $MIG_REMOTA" 2>&1)"; then
    falhar "a migracao deveria ter abortado com capacity - quant > ativas"
fi
echo "$SAIDA" | grep -q "capacity - quant maior que as reservas ativas em: evento 99 (capacity 5, quant 0, ativas 3)" || falhar "mensagem inesperada: $SAIDA"
echo "    abortou com: $(echo "$SAIDA" | grep -o 'evento 99 (capacity 5, quant 0, ativas 3)' | head -1)"
como_app "-d $MIG_DB -v inalterado=1 -f $REMOTE_DIR/verify.sql"
como_app "-d $MIG_DB -v remover_abort=1 -f $REMOTE_DIR/verify.sql"
[ "$(como_app "-d $MIG_DB -At -v snapshot=1 -f $REMOTE_DIR/verify.sql")" = "$SNAP_ANTES" ] || falhar "o aborto mudou a forma do schema"

echo ""
echo "==> 1a aplicacao da migracao (supabase_admin)"
SAIDA1="$(como_admin_supabase "-d $MIG_DB -f $MIG_REMOTA" 2>&1)" || falhar "1a aplicacao falhou: $SAIDA1"
echo "$SAIDA1" | sed 's/^/    /'
echo "$SAIDA1" | grep -q "reservation.eventcap movida para o schema stock" || falhar "a 1a aplicacao deveria mover o eventcap"
echo "$SAIDA1" | grep -q "backfill: 24 hold(s) held criados, 14 com decremented = true" || falhar "backfill inesperado (esperava 24 holds, 14 debitados = 7 + 0 + 5 + 2)"
echo "==> Conferencia do cenario S9"
como_app "-d $MIG_DB -v migrado=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> 2a aplicacao (re-run idempotente): so NOTICEs, nenhum hold mudou"
SAIDA2="$(como_admin_supabase "-d $MIG_DB -f $MIG_REMOTA" 2>&1)" || falhar "2a aplicacao falhou: $SAIDA2"
echo "$SAIDA2" | sed 's/^/    /'
echo "$SAIDA2" | grep -qiE 'ERROR|movida para|backfill: ' && falhar "a 2a aplicacao nao deveria mover nem fazer backfill"
echo "$SAIDA2" | grep -q "stock.eventcap já existe" || falhar "a 2a aplicacao deveria avisar que o eventcap ja esta em stock"
echo "$SAIDA2" | grep -q "backfill dos holds já feito" || falhar "a 2a aplicacao deveria avisar que o backfill ja foi feito"
como_app "-d $MIG_DB -v idempotente=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> [B] Forma final: migrado == banco novo montado pelos init-scripts atuais (13-reservation.sql + 16-stock.sql) em $INIT_DB"
criar_banco "$INIT_DB"
for f in 13-reservation.sql 16-stock.sql; do
    como_app "-d $INIT_DB -f -" < "$DB_DIR/init-scripts/$f" >/dev/null
done
SNAP_MIG="$(como_app "-d $MIG_DB -At -v snapshot=1 -f $REMOTE_DIR/verify.sql")"
SNAP_INIT="$(como_app "-d $INIT_DB -At -v snapshot=1 -f $REMOTE_DIR/verify.sql")"
[ "$(echo "$SNAP_MIG" | wc -l)" -ge 40 ] || falhar "snapshot do banco migrado vazio/curto: $SNAP_MIG"
if [ "$SNAP_MIG" != "$SNAP_INIT" ]; then
    echo "--- diferencas (< migrado, > init atual)"; diff <(echo "$SNAP_MIG") <(echo "$SNAP_INIT") || true
    falhar "o banco migrado e o banco novo dos init-scripts atuais divergem"
fi
echo "    $(echo "$SNAP_MIG" | wc -l) itens (colunas, constraints, indices, donos de reservation e stock) identicos nos dois"

echo ""
echo "==> Rollback do cabecalho da migracao (extraido do proprio arquivo) e conferencia do schema e dos dados de antes"
echo "$ROLLBACK_SQL" | sed 's/^/    /'
{ echo "BEGIN;"; echo "SET LOCAL lock_timeout = '5s';"; echo "$ROLLBACK_SQL"; echo "COMMIT;"; } | como_admin_supabase "-d $MIG_DB -f -"
como_app "-d $MIG_DB -v restaurado=1 -f $REMOTE_DIR/verify.sql"
SNAP_DEPOIS="$(como_app "-d $MIG_DB -At -v snapshot=1 -f $REMOTE_DIR/verify.sql")"
if [ "$SNAP_DEPOIS" != "$SNAP_ANTES" ]; then
    echo "--- diferencas (< depois do rollback, > antes da migracao)"; diff <(echo "$SNAP_DEPOIS") <(echo "$SNAP_ANTES") || true
    falhar "o schema depois do rollback diverge do schema de $COMMIT_ANTERIOR"
fi
echo "    schema depois do rollback == schema de $COMMIT_ANTERIOR ($(echo "$SNAP_ANTES" | wc -l) itens)"

echo ""
echo "=================================================="
echo "   RESULTADO: TODOS OS PASSOS PASSARAM"
echo "   - aborta com os dois eventcap e com capacity - quant > ativas (lista o evento), sem mudar nada"
echo "   - S9: capacity - quant holds debitados (os mais antigos) por evento limitado, ilimitado sem debito, invariante verde"
echo "   - 2a aplicacao (re-run): so NOTICEs, mesmos holds e hold_id"
echo "   - estado migrado == 13-reservation.sql + 16-stock.sql de banco novo"
echo "   - rollback do cabecalho devolve o schema de $COMMIT_ANTERIOR e os dados"
echo "=================================================="
echo ""
