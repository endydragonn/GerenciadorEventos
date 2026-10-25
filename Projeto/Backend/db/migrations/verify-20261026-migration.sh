#!/bin/bash

# verify-20261026-migration.sh
# Harness reproduzível da migração 20261026-catalog-schema.sql (issue #35, cutover do serviço catalog) e do init de banco novo.
#
# Monta bancos DESCARTÁVEIS no container gerenciador-db e prova, ponta a ponta:
#   - as guardas: com linhas em catalog.event (e public.event de pé), ou com FK de FORA apontando para public.event (banco sem a
#     20261025), a migração ABORTA sem alterar nada (a FK de fora não é derrubada);
#   - casos 7 e 9 do spec: num banco pré-cutover POPULADO (o de quem rodou a fatia 2: catalog.event VAZIA do 14 ao lado de
#     public.event, sobra de teste no catalog.event_store), a migração roda 2x (idempotente), move dados e streams preservando
#     global_position, deixa só as FKs certas (mywallet_user_id_fkey; eventpresenter_event_id_fkey em cascata; nenhuma
#     cruzando o schema catalog) e o próximo event_id NÃO reaproveita o id apagado (ver verify-20261026-migration.sql);
#   - caso 10: o rollback documentado (DROP SCHEMA catalog CASCADE + restore do pg_dump --clean --if-exists -n public tirado
#     antes) volta public.event com a mesma contagem e sem o schema catalog;
#   - caso 8: o init COMPLETO (supabase-app-init.sql com os init-scripts atuais) num banco vazio termina com
#     "Testes minimos concluidos com sucesso" e to_regclass('public.event') IS NULL.
#
# NADA disto toca em `meu_banco`: tudo acontece em meu_banco_mig e meu_banco_init_check, criados e destruídos aqui.
# Cross-platform como o verify-20260901-migration.sh (Git Bash: "pwd -W" e sem conversão de path do MSYS).
#
# Pré-requisito: docker compose -f Projeto/docker-compose.yml up -d db
# Uso:  Projeto/Backend/db/migrations/verify-20261026-migration.sh      (exit != 0 se qualquer passo falhar)

set -euo pipefail

CONTAINER="gerenciador-db"
MIG_DB="meu_banco_mig"
INIT_DB="meu_banco_init_check"
REMOTE_DIR="/tmp/catalog_mig_check"
# init-scripts atuais (o 03-event e o 05-eventpresenter saíram no cutover; o 14-catalog cria o schema catalog)
INIT_SCRIPTS="01-functions 02-users 06-mywallet 08-pending-verifications 11-event-store 12-notification 13-reservation 14-catalog"

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
echo "   VERIFICACAO DA MIGRACAO 20261026  (issue #35)"
echo "=================================================="

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "ERRO: container $CONTAINER nao esta no ar. Suba o banco primeiro:"
    echo "  docker compose -f Projeto/docker-compose.yml up -d db"
    exit 1
fi

# superusuário pelo socket (trust); o usuário do app (APP_DB_USER, do ambiente do PRÓPRIO container) por localhost (trust).
# Nenhum nome/senha hardcoded aqui.
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

echo ""
echo "==> Copiando scripts para o container em $REMOTE_DIR"
docker exec -i "$CONTAINER" sh -c "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR/init'"
for f in $INIT_SCRIPTS 99-tests; do
    docker cp "$DB_DIR/init-scripts/$f.sql" "$CONTAINER:$REMOTE_DIR/init/$f.sql"
done
docker cp "$DB_DIR/supabase-app-init.sql" "$CONTAINER:$REMOTE_DIR/supabase-app-init.sql"
docker cp "$DB_DIR/migrations/20261026-catalog-schema.sql" "$CONTAINER:$REMOTE_DIR/20261026-catalog-schema.sql"
docker cp "$DB_DIR/migrations/verify-20261026-migration.sql" "$CONTAINER:$REMOTE_DIR/verify.sql"
# o arquivo copiado não pode ter os scripts que saíram
if docker exec "$CONTAINER" grep -q '^\\i .*\(03-event\|05-eventpresenter\)' "$REMOTE_DIR/supabase-app-init.sql"; then
    echo "ERRO: supabase-app-init.sql ainda inclui 03-event/05-eventpresenter"; exit 1
fi

echo ""
echo "==> [A] Banco pre-cutover descartavel: $MIG_DB (init-scripts atuais + estado da fatia 2 com dados)"
drop_dbs # sobra de uma rodada interrompida
como_admin_supabase "-d postgres -c 'CREATE DATABASE $MIG_DB;'"
# dono = o usuário do app, como o meu_banco (supabase-app-init.sql): ele cria os schemas e é dono das tabelas
docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d postgres -c "ALTER DATABASE '"$MIG_DB"' OWNER TO \"$APP_DB_USER\";"'
for f in $INIT_SCRIPTS; do
    echo "    - $f.sql"
    como_app "-d $MIG_DB -f $REMOTE_DIR/init/$f.sql" >/dev/null
done
como_app "-d $MIG_DB -v preparar=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> Guarda: com linha em catalog.event (e public.event de pe) a migracao aborta e nao altera nada"
como_app "-d $MIG_DB -c \"INSERT INTO catalog.event (creator_id, event_name, event_date, description) VALUES (1, 'intrusa', now(), 'x');\""
if SAIDA="$(como_admin_supabase "-d $MIG_DB -f $REMOTE_DIR/20261026-catalog-schema.sql" 2>&1)"; then
    echo "ERRO: a migracao deveria ter abortado com catalog.event nao vazia"; exit 1
fi
echo "$SAIDA" | grep -q "catalog.event tem 1 linha" || { echo "ERRO: mensagem inesperada: $SAIDA"; exit 1; }
como_app "-d $MIG_DB -Atc \"SELECT CASE WHEN to_regclass('public.event') IS NOT NULL THEN 'ok' ELSE 'FALHA' END;\"" | grep -qx ok \
    || { echo "ERRO: a migracao abortada alterou o banco"; exit 1; }
como_app "-d $MIG_DB -c \"DELETE FROM catalog.event WHERE event_name = 'intrusa';\""
echo "    abortou com: $(echo "$SAIDA" | grep -o 'catalog.event tem 1 linha[^:]*' | head -1)"

echo ""
echo "==> Guarda: FK de FORA para public.event (banco sem a 20261025: public.walletevent -> event) aborta, nao e derrubada"
como_app "-d $MIG_DB -c 'CREATE TABLE public.walletevent (user_id integer, event_id integer CONSTRAINT \"WalletEvent_event_id_fkey\" REFERENCES public.event (event_id));'"
if SAIDA="$(como_admin_supabase "-d $MIG_DB -f $REMOTE_DIR/20261026-catalog-schema.sql" 2>&1)"; then
    echo "ERRO: a migracao deveria ter abortado com FK de fora para public.event"; exit 1
fi
echo "$SAIDA" | grep -q "WalletEvent_event_id_fkey.*20261025-reservation-schema.sql" || { echo "ERRO: mensagem inesperada: $SAIDA"; exit 1; }
como_app "-d $MIG_DB -Atc \"SELECT count(*) FROM pg_constraint WHERE conname = 'WalletEvent_event_id_fkey';\"" | grep -qx 1 \
    || { echo "ERRO: a FK de fora sumiu (deveria ficar intacta)"; exit 1; }
como_app "-d $MIG_DB -c 'DROP TABLE public.walletevent;'"
echo "    abortou com: $(echo "$SAIDA" | grep -o 'FK de outra tabela aponta para public.event/eventpresenter: [^ ]* ([^)]*)' | head -1)"

echo ""
echo "==> Backup pre-migracao (pg_dump --clean --if-exists -n public), como no roteiro real"
docker exec -i "$CONTAINER" sh -c 'pg_dump -h localhost -U "$APP_DB_USER" --clean --if-exists -n public '"$MIG_DB"' > '"$REMOTE_DIR/pre.sql"
TAM="$(docker exec "$CONTAINER" sh -c "wc -c < '$REMOTE_DIR/pre.sql'")"
[ "$TAM" -gt 0 ] || { echo "ERRO: dump vazio"; exit 1; }
echo "    dump: $TAM bytes"

echo ""
echo "==> Migracao 20261026, 1a e 2a aplicacao (idempotencia), como supabase_admin"
como_admin_supabase "-d $MIG_DB -f $REMOTE_DIR/20261026-catalog-schema.sql"
como_admin_supabase "-d $MIG_DB -f $REMOTE_DIR/20261026-catalog-schema.sql"

echo ""
echo "==> Casos 7 e 9: conferencia do estado migrado"
como_app "-d $MIG_DB -v conferir=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> Caso 10: rollback (DROP SCHEMA catalog CASCADE + restore do dump) e conferencia"
como_admin_supabase "-d $MIG_DB -c 'DROP SCHEMA catalog CASCADE;'"
como_admin_supabase "-d $MIG_DB -f $REMOTE_DIR/pre.sql" >/dev/null
como_app "-d $MIG_DB -v restaurado=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> [B] Caso 8: init COMPLETO (supabase-app-init.sql + init-scripts atuais) em banco vazio: $INIT_DB"
como_admin_supabase "-d postgres -c 'CREATE DATABASE $INIT_DB;'"
docker exec -i "$CONTAINER" sed -i "s#/app-init/#$REMOTE_DIR/init/#" "$REMOTE_DIR/supabase-app-init.sql"
SAIDA_INIT="$(docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d '"$INIT_DB"' -f '"$REMOTE_DIR/supabase-app-init.sql" 2>&1)"
echo "$SAIDA_INIT" | grep "Testes minimos concluidos com sucesso" || { echo "ERRO: init sem sucesso: $SAIDA_INIT"; exit 1; }
REGCLASS="$(como_admin_supabase "-d $INIT_DB -Atc \"SELECT to_regclass('public.event') IS NULL, to_regclass('catalog.event') IS NOT NULL;\"")"
echo "    to_regclass('public.event') IS NULL, to_regclass('catalog.event') IS NOT NULL -> $REGCLASS"
[ "$REGCLASS" = "t|t" ] || { echo "ERRO: banco novo deveria nascer so com catalog.event"; exit 1; }

echo ""
echo "=================================================="
echo "   RESULTADO: TODOS OS PASSOS PASSARAM"
echo "   - guardas (catalog.event com linhas; FK de fora para public.event) abortam sem alterar nada"
echo "   - migracao idempotente (2x) sobre banco pre-cutover populado; FKs (caso 7); identity (caso 9)"
echo "   - rollback: restore do dump pre-migracao (caso 10)"
echo "   - init completo em banco vazio (caso 8)"
echo "=================================================="
echo ""
