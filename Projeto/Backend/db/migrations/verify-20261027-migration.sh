#!/bin/bash

# verify-20261027-migration.sh
# Harness reproduzível da migração 20261027-reservation-timestamptz.sql (issue #58; harness da issue #59).
#
# Monta bancos DESCARTÁVEIS no container gerenciador-db (nada toca `meu_banco`) e prova, ponta a ponta:
#   - caso capacity = 0: a migração ABORTA com a contagem e NADA muda (tipos, CHECK, índice e linhas iguais: ela é uma transação);
#   - 1ª aplicação sobre o estado ANTERIOR COM linhas (eventcap/walletevent com horários de parede conhecidos, NULL, microssegundos e
#     um horário que não existe em America/New_York), em sessão NÃO-UTC (America/Sao_Paulo): linha a linha, novo = antigo AT TIME ZONE
#     'UTC'; tipos, CHECK (definição e regra), índice e dono admin finais;
#   - 2ª aplicação (re-run idempotente), em outra sessão não-UTC (America/New_York): só NOTICEs "nada a fazer", valores inalterados;
#   - o estado migrado tem a MESMA forma (colunas, constraints, índices, donos) de um banco novo montado pelo 13-reservation.sql;
#   - o rollback do cabeçalho da migração (extraído do próprio arquivo) devolve tipos, CHECK, índice e os valores originais.
#
# Cross-platform como o verify-20261026-migration.sh (Git Bash: "pwd -W" e sem conversão de path do MSYS).
# Pré-requisito: docker compose -f Projeto/docker-compose.yml up -d db
# Uso:  Projeto/Backend/db/migrations/verify-20261027-migration.sh      (exit != 0 se qualquer passo falhar)

set -euo pipefail

CONTAINER="gerenciador-db"
MIG_DB="meu_banco_mig27"
INIT_DB="meu_banco_mig27_init"
REMOTE_DIR="/tmp/reservation_ts_mig_check"
TZ_1="America/Sao_Paulo"
TZ_2="America/New_York"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRACAO="$SCRIPT_DIR/20261027-reservation-timestamptz.sql"
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
echo "   VERIFICACAO DA MIGRACAO 20261027  (issue #58 / #59)"
echo "=================================================="

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "ERRO: container $CONTAINER nao esta no ar. Suba o banco primeiro:"
    echo "  docker compose -f Projeto/docker-compose.yml up -d db"
    exit 1
fi

# superusuário pelo socket (trust); o usuário do app (APP_DB_USER, do ambiente do PRÓPRIO container) por localhost (trust).
# Nenhum nome/senha hardcoded aqui. O 1º argumento do "_tz" é o fuso da SESSÃO (PGOPTIONS), o resto vai ao psql.
como_admin_supabase() { docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" '"$*"; }
como_admin_supabase_tz() {
    local tz="$1"; shift
    docker exec -i -e "PGOPTIONS=-c timezone=$tz" "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" '"$*"
}
como_app() { docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -h localhost -U "$APP_DB_USER" '"$*"; }
como_app_tz() {
    local tz="$1"; shift
    docker exec -i -e "PGOPTIONS=-c timezone=$tz" "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -h localhost -U "$APP_DB_USER" '"$*"
}

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

echo ""
echo "==> Copiando scripts para o container em $REMOTE_DIR"
docker exec -i "$CONTAINER" sh -c "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR'"
docker cp "$DB_DIR/migrations/20261027-reservation-timestamptz.sql" "$CONTAINER:$REMOTE_DIR/20261027-reservation-timestamptz.sql"
docker cp "$DB_DIR/migrations/verify-20261027-migration.sql" "$CONTAINER:$REMOTE_DIR/verify.sql"
docker cp "$DB_DIR/init-scripts/13-reservation.sql" "$CONTAINER:$REMOTE_DIR/13-reservation.sql"
MIG_REMOTA="$REMOTE_DIR/20261027-reservation-timestamptz.sql"

# o rollback documentado no cabeçalho da migração, tal como está escrito lá (linhas "--   ALTER/DROP ..." entre ROLLBACK e "(ou restaurar")
ROLLBACK_SQL="$(sed -n '/^-- ROLLBACK/,/^-- (ou restaurar/p' "$MIGRACAO" | grep -E '^--   (ALTER|DROP)' | sed 's/^--   //')"
[ "$(echo "$ROLLBACK_SQL" | grep -c ';')" -ge 5 ] || falhar "rollback do cabecalho incompleto: $ROLLBACK_SQL"

echo ""
echo "==> [A] Banco descartavel $MIG_DB: schema reservation ANTERIOR a 20261027, com dados"
drop_dbs # sobra de uma rodada interrompida
como_admin_supabase "-d postgres -c 'CREATE DATABASE $MIG_DB;'"
docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d postgres -c "ALTER DATABASE '"$MIG_DB"' OWNER TO \"$APP_DB_USER\";"'
como_app "-d $MIG_DB -v preparar=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> Caso capacity = 0: a migracao aborta com a contagem e NAO altera nada (sessao em $TZ_1)"
como_app "-d $MIG_DB -c \"INSERT INTO reservation.eventcap (event_id, capacity, quant) VALUES (99, 0, 0);\""
if SAIDA="$(como_admin_supabase_tz "$TZ_1" "-d $MIG_DB -f $MIG_REMOTA" 2>&1)"; then
    falhar "a migracao deveria ter abortado com capacity = 0"
fi
echo "$SAIDA" | grep -q "ERROR:  reservation.eventcap tem 1 linha(s) com capacity = 0 ou < -1" || falhar "mensagem inesperada: $SAIDA"
echo "    abortou com: $(echo "$SAIDA" | grep -o 'reservation.eventcap tem 1 linha(s) com capacity = 0 ou < -1' | head -1)"
como_app "-d $MIG_DB -c \"DELETE FROM reservation.eventcap WHERE event_id = 99;\""
como_app "-d $MIG_DB -v inalterado=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> 1a aplicacao da migracao (supabase_admin, sessao em $TZ_1)"
SAIDA1="$(como_admin_supabase_tz "$TZ_1" "-d $MIG_DB -f $MIG_REMOTA" 2>&1)" || falhar "1a aplicacao falhou: $SAIDA1"
echo "$SAIDA1" | sed 's/^/    /'
[ "$(echo "$SAIDA1" | grep -c 'convertida para timestamptz')" -eq 2 ] || falhar "a 1a aplicacao deveria converter as 2 colunas"
echo "$SAIDA1" | grep -q "agora é CHECK" || falhar "a 1a aplicacao deveria trocar o CHECK"
echo "==> Conferencia (sessao em $TZ_1): instantes linha a linha, tipos, CHECK, indice, dono"
como_app_tz "$TZ_1" "-d $MIG_DB -v migrado=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> 2a aplicacao (re-run idempotente, sessao em $TZ_2): so NOTICEs, valores inalterados"
SAIDA2="$(como_admin_supabase_tz "$TZ_2" "-d $MIG_DB -f $MIG_REMOTA" 2>&1)" || falhar "2a aplicacao falhou: $SAIDA2"
echo "$SAIDA2" | sed 's/^/    /'
echo "$SAIDA2" | grep -qiE 'ERROR|convertida para|agora é CHECK' && falhar "a 2a aplicacao nao deveria converter nem trocar nada"
[ "$(echo "$SAIDA2" | grep -c 'nada a fazer')" -eq 3 ] || falhar "a 2a aplicacao deveria emitir 3 NOTICEs 'nada a fazer' (2 colunas + CHECK)"
echo "$SAIDA2" | grep -q "idx_walletevent_event_id" || falhar "a 2a aplicacao deveria avisar que o indice ja existe"
como_app_tz "$TZ_2" "-d $MIG_DB -v migrado=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> [B] Forma final: migrado == banco novo montado pelo 13-reservation.sql ($INIT_DB)"
como_admin_supabase "-d postgres -c 'CREATE DATABASE $INIT_DB;'"
docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d postgres -c "ALTER DATABASE '"$INIT_DB"' OWNER TO \"$APP_DB_USER\";"'
como_app "-d $INIT_DB -f $REMOTE_DIR/13-reservation.sql" >/dev/null
SNAP_MIG="$(como_app "-d $MIG_DB -At -v snapshot=1 -f $REMOTE_DIR/verify.sql")"
SNAP_INIT="$(como_app "-d $INIT_DB -At -v snapshot=1 -f $REMOTE_DIR/verify.sql")"
[ -n "$SNAP_MIG" ] && [ "$(echo "$SNAP_MIG" | wc -l)" -ge 20 ] || falhar "snapshot do banco migrado vazio/curto: $SNAP_MIG"
if [ "$SNAP_MIG" != "$SNAP_INIT" ]; then
    echo "--- migrado"; echo "$SNAP_MIG"; echo "--- init (13-reservation.sql)"; echo "$SNAP_INIT"
    falhar "o banco migrado e o banco novo do 13-reservation.sql divergem"
fi
echo "    $(echo "$SNAP_MIG" | wc -l) itens (colunas, constraints, indices, donos) identicos nos dois"

echo ""
echo "==> Rollback do cabecalho da migracao (extraido do proprio arquivo) e conferencia dos valores originais"
echo "$ROLLBACK_SQL" | sed 's/^/    /'
echo "$ROLLBACK_SQL" | como_admin_supabase_tz "$TZ_1" "-d $MIG_DB -f -"
como_app "-d $MIG_DB -v restaurado=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "=================================================="
echo "   RESULTADO: TODOS OS PASSOS PASSARAM"
echo "   - capacity = 0: aborta com a contagem e nada muda"
echo "   - 1a aplicacao em sessao nao-UTC: novo = antigo AT TIME ZONE 'UTC' linha a linha; tipos, CHECK, indice, dono"
echo "   - 2a aplicacao (re-run): so NOTICEs, valores inalterados"
echo "   - estado migrado == 13-reservation.sql de banco novo"
echo "   - rollback do cabecalho devolve tipos, CHECK, indice e valores originais"
echo "=================================================="
echo ""
