#!/bin/bash

# verify-20260901-migration.sh
# Harness reproduzível da issue #6 ("05 · Aplicar a migration idempotente em banco
# populado + 99-tests.sql").
#
# Monta um banco DESCARTÁVEL no container gerenciador-db e prova, ponta a ponta:
#   Aceite 1 -> db/migrations/20260901-eventcap-isolation.sql roda via psql sobre um
#               banco POPULADO no schema ANTIGO, SEM `down -v`, de forma IDEMPOTENTE
#               (2x), preservando dados e convertendo `quant` corretamente
#               (ver verify-20260901-migration.sql).
#   Aceite 2 -> db/init-scripts/99-tests.sql passa (CAS, "lotado", cascata, capacity=-1).
#
# NADA disto toca em `meu_banco`: tudo acontece no banco `eventcap_mig_check`, criado
# e destruído por este script.
#
# Funciona em Linux/macOS e no Git Bash do Windows (Docker Desktop), no mesmo espírito
# de Backend/run-tests.sh (o binário do Docker no Git Bash é nativo do Windows e não
# entende "/e/..."; usamos "pwd -W" e desligamos a conversão de path do MSYS).
#
# Pré-requisito: a stack no ar para existir o container do banco
#   docker compose -f Projeto/docker-compose.yml up -d db
#
# Uso:  Projeto/Backend/db/migrations/verify-20260901-migration.sh
# Exit code != 0 se qualquer passo falhar.

set -euo pipefail

CONTAINER="gerenciador-db"
CHECK_DB="eventcap_mig_check"
REMOTE_DIR="/tmp/eventcap_mig_check"

INIT_SCRIPTS="01-functions 02-users 03-event 04-eventcap 05-eventpresenter 06-mywallet 07-walletevent 08-pending-verifications 09-event-availability 10-idempotency-key"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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
echo "   VERIFICACAO DA MIGRACAO 20260901  (issue #6)"
echo "=================================================="

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "ERRO: container $CONTAINER nao esta no ar. Suba a stack primeiro:"
    echo "  docker compose -f Projeto/docker-compose.yml up -d db"
    exit 1
fi

# psql dentro do container: conexao local confiavel (sem senha). O usuario e os
# nomes vem do ambiente do PROPRIO container (POSTGRES_USER) -- nada hardcoded aqui.
drop_check_db() {
    docker exec -i "$CONTAINER" sh -c \
        'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS '"$CHECK_DB"' WITH (FORCE);"' \
        >/dev/null 2>&1 || true
    docker exec -i "$CONTAINER" rm -rf "$REMOTE_DIR" >/dev/null 2>&1 || true
}
trap drop_check_db EXIT

echo ""
echo "==> Copiando scripts para o container em $REMOTE_DIR"
docker exec -i "$CONTAINER" sh -c "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR/init'"
for f in $INIT_SCRIPTS 99-tests; do
    docker cp "$DB_DIR/init-scripts/$f.sql" "$CONTAINER:$REMOTE_DIR/init/$f.sql"
done
docker cp "$DB_DIR/migrations/20260901-eventcap-isolation.sql" "$CONTAINER:$REMOTE_DIR/20260901-eventcap-isolation.sql"
docker cp "$DB_DIR/migrations/verify-20260901-migration.sql"    "$CONTAINER:$REMOTE_DIR/verify-20260901-migration.sql"

echo ""
echo "==> Recriando banco descartavel: $CHECK_DB"
docker exec -i "$CONTAINER" sh -c \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS '"$CHECK_DB"' WITH (FORCE);" -c "CREATE DATABASE '"$CHECK_DB"';"'

echo ""
echo "==> Aplicando init-scripts 01-10 (schema NOVO) no banco descartavel"
for f in $INIT_SCRIPTS; do
    echo "    - $f.sql"
    docker exec -i "$CONTAINER" sh -c \
        'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d '"$CHECK_DB"' -f '"$REMOTE_DIR/init/$f.sql"
done

echo ""
echo "==> Aceite 2 (canonico): 99-tests.sql sobre schema recem-inicializado"
docker exec -i "$CONTAINER" sh -c \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d '"$CHECK_DB"' -f '"$REMOTE_DIR/init/99-tests.sql"

echo ""
echo "==> Aceite 1: harness da migracao (1a passada -> reshape p/ schema ANTIGO, popula, migra 2x, valida)"
docker exec -i "$CONTAINER" sh -c \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d '"$CHECK_DB"' -f '"$REMOTE_DIR/verify-20260901-migration.sql"

echo ""
echo "==> Idempotencia do proprio harness: 2a passada no mesmo banco"
docker exec -i "$CONTAINER" sh -c \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d '"$CHECK_DB"' -f '"$REMOTE_DIR/verify-20260901-migration.sql"

echo ""
echo "==> Aceite 2 (bonus): 99-tests.sql sobre o schema JA migrado"
docker exec -i "$CONTAINER" sh -c \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d '"$CHECK_DB"' -f '"$REMOTE_DIR/init/99-tests.sql"

echo ""
echo "=================================================="
echo "   RESULTADO: TODOS OS PASSOS PASSARAM"
echo "   - migracao idempotente sobre banco populado (schema antigo -> novo)"
echo "   - dados preservados (contagem + identidade) + conversao de quant OK"
echo "   - 99-tests.sql OK (schema recem-inicializado E schema ja migrado)"
echo "=================================================="
echo ""
