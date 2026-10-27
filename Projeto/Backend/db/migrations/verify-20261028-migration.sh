#!/bin/bash

# verify-20261028-migration.sh
# Harness reproduzível da migração 20261028-identity-schema.sql (issue #36, cutover do serviço identity) e do init de banco novo.
#
# Monta bancos DESCARTÁVEIS no container gerenciador-db e prova, ponta a ponta:
#   - [A] banco pré-cutover POPULADO SEM o 15-identity.sql (nunca rodou a fatia 2): a migração roda 2x (idempotente), move users/
#     mywallet/pending_verifications preservando os ids e a identity (o id apagado não volta), recria funções e triggers em identity,
#     move os streams identity.notification preservando global_position (outbox, checkpoint, snapshot) e deixa o resto em public
#     (ver verify-20261028-migration.sql: casos 9 do spec); depois o rollback documentado (DROP SCHEMA identity CASCADE + restore do
#     pg_dump --clean --if-exists -n public tirado antes) devolve public.users com a mesma contagem;
#   - [B] o mesmo banco COM o 15-identity.sql já aplicado (as 7 tabelas identity.* vazias, como em quem rodou a fatia 2): migra e o
#     15 reaplicado depois não quebra nada (I3 do revisor da fatia 2);
#   - [C] as guardas (caso 14): linha em QUALQUER das 7 tabelas identity.* (users, event_store, event_outbox, event_snapshot,
#     projection_checkpoint) ou FK de fora para public.users ABORTA sem alterar nada;
#   - [D] o init COMPLETO (supabase-app-init.sql com os init-scripts atuais) num banco vazio termina com "Testes minimos concluidos
#     com sucesso", sem public.users, e o trigger cria a carteira em identity.mywallet.
#
# NADA disto toca em `meu_banco`: tudo acontece nos bancos meu_banco_mig28*, criados e destruídos aqui. O estado pré-cutover vem do
# fixture legacy-fixtures/public-identity-pre-20261028.sql (o 01/02/06/08 saíram do repo). Cross-platform como o verify-20261026.
#
# Pré-requisito: docker compose -f Projeto/docker-compose.yml up -d db
# Uso:  Projeto/Backend/db/migrations/verify-20261028-migration.sh      (exit != 0 se qualquer passo falhar)

set -euo pipefail

CONTAINER="gerenciador-db"
DB_A="meu_banco_mig28a"
DB_B="meu_banco_mig28b"
DB_C="meu_banco_mig28c"
DB_INIT="meu_banco_mig28_init"
REMOTE_DIR="/tmp/identity_mig_check"
LEGACY_FIXTURE="public-identity-pre-20261028"
# init-scripts do estado pré-cutover do identity (o 15 é aplicado à parte, só nos bancos [B] e [C])
INIT_SCRIPTS="11-event-store 12-notification 13-reservation 14-catalog"

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
echo "   VERIFICACAO DA MIGRACAO 20261028  (issue #36)"
echo "=================================================="

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "ERRO: container $CONTAINER nao esta no ar. Suba o banco primeiro:"
    echo "  docker compose -f Projeto/docker-compose.yml up -d db"
    exit 1
fi

# superusuário pelo socket (trust); o usuário do app (APP_DB_USER, do ambiente do PRÓPRIO container) por localhost (trust).
# Nenhum nome/senha hardcoded aqui. Sem -f/-c o psql lê o SQL do stdin.
como_admin_supabase() { docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" '"$*"; }
como_app() { docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -h localhost -U "$APP_DB_USER" '"$*"; }

drop_dbs() {
    for db in "$DB_A" "$DB_B" "$DB_C" "$DB_INIT"; do
        como_admin_supabase "-d postgres -c 'DROP DATABASE IF EXISTS $db WITH (FORCE);'" >/dev/null 2>&1 || true
    done
}
limpar() {
    drop_dbs
    docker exec -i "$CONTAINER" rm -rf "$REMOTE_DIR" >/dev/null 2>&1 || true
}
trap limpar EXIT

falhar() { echo "ERRO: $*"; exit 1; }

# monta um banco pré-cutover: dono = o usuário do app (como o meu_banco), fixture legado + init-scripts (+ 15 se $2 = com15)
montar() {
    local db="$1" com15="$2"
    como_admin_supabase "-d postgres -c 'CREATE DATABASE $db;'"
    docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d postgres -c "ALTER DATABASE '"$db"' OWNER TO \"$APP_DB_USER\";"'
    como_app "-d $db -f $REMOTE_DIR/init/$LEGACY_FIXTURE.sql" >/dev/null
    for f in $INIT_SCRIPTS; do
        como_app "-d $db -f $REMOTE_DIR/init/$f.sql" >/dev/null
    done
    if [ "$com15" = "com15" ]; then
        como_app "-d $db -f $REMOTE_DIR/init/15-identity.sql" >/dev/null
    fi
}

migrar() { como_admin_supabase "-d $1 -f $REMOTE_DIR/20261028-identity-schema.sql"; }

# a migração tem de ABORTAR (exit != 0) com a mensagem esperada e deixar public.users intacta ($3 = quantos usuários)
exigir_aborto() {
    local db="$1" trecho="$2" saida
    if saida="$(migrar "$db" 2>&1)"; then
        falhar "a migracao deveria ter abortado ($trecho)"
    fi
    echo "$saida" | grep -q "$trecho" || falhar "mensagem inesperada (esperado '$trecho'): $saida"
    [ "$(echo "SELECT count(*) FROM public.users;" | como_app "-d $db -At")" = "3" ] \
        || falhar "a migracao abortada alterou public.users"
    [ "$(echo "SELECT to_regclass('public.mywallet') IS NOT NULL AND to_regclass('public.pending_verifications') IS NOT NULL;" \
        | como_app "-d $db -At")" = "t" ] || falhar "a migracao abortada moveu tabelas"
    echo "    abortou com: $trecho"
}

echo ""
echo "==> Copiando scripts para o container em $REMOTE_DIR"
docker exec -i "$CONTAINER" sh -c "rm -rf '$REMOTE_DIR' && mkdir -p '$REMOTE_DIR/init'"
for f in $INIT_SCRIPTS 15-identity 99-tests; do
    docker cp "$DB_DIR/init-scripts/$f.sql" "$CONTAINER:$REMOTE_DIR/init/$f.sql"
done
docker cp "$DB_DIR/migrations/legacy-fixtures/$LEGACY_FIXTURE.sql" "$CONTAINER:$REMOTE_DIR/init/$LEGACY_FIXTURE.sql"
docker cp "$DB_DIR/supabase-app-init.sql" "$CONTAINER:$REMOTE_DIR/supabase-app-init.sql"
docker cp "$DB_DIR/migrations/20261028-identity-schema.sql" "$CONTAINER:$REMOTE_DIR/20261028-identity-schema.sql"
docker cp "$DB_DIR/migrations/verify-20261028-migration.sql" "$CONTAINER:$REMOTE_DIR/verify.sql"
# o arquivo copiado não pode ter os scripts que saíram
if docker exec "$CONTAINER" grep -q '^\\i .*\(01-functions\|02-users\|06-mywallet\|08-pending-verifications\)' "$REMOTE_DIR/supabase-app-init.sql"; then
    falhar "supabase-app-init.sql ainda inclui 01-functions/02-users/06-mywallet/08-pending-verifications"
fi
drop_dbs # sobra de uma rodada interrompida

echo ""
echo "==> [A] Banco pre-cutover SEM o 15 (nunca rodou a fatia 2): $DB_A"
montar "$DB_A" sem15
como_app "-d $DB_A -v preparar=1 -f $REMOTE_DIR/verify.sql"

echo "    backup pre-migracao (pg_dump --clean --if-exists -n public), como no roteiro real"
docker exec -i "$CONTAINER" sh -c 'pg_dump -h localhost -U "$APP_DB_USER" --clean --if-exists -n public '"$DB_A"' > '"$REMOTE_DIR/pre.sql"
TAM="$(docker exec "$CONTAINER" sh -c "wc -c < '$REMOTE_DIR/pre.sql'")"
[ "$TAM" -gt 0 ] || falhar "dump vazio"
echo "    dump: $TAM bytes"

echo "    migracao 20261028, 1a e 2a aplicacao (idempotencia), como supabase_admin"
migrar "$DB_A"
migrar "$DB_A"
echo "    conferencia do estado migrado (caso 9)"
como_app "-d $DB_A -v conferir=1 -f $REMOTE_DIR/verify.sql"

echo "    rollback (DROP SCHEMA identity CASCADE + restore do dump) e conferencia"
como_admin_supabase "-d $DB_A -c 'DROP SCHEMA identity CASCADE;'"
como_admin_supabase "-d $DB_A -f $REMOTE_DIR/pre.sql" >/dev/null
como_app "-d $DB_A -v restaurado=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> [B] Banco pre-cutover COM o 15 ja aplicado (7 tabelas identity.* vazias, como em quem rodou a fatia 2): $DB_B"
montar "$DB_B" com15
como_app "-d $DB_B -v preparar=1 -f $REMOTE_DIR/verify.sql"
[ "$(echo "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'identity';" | como_app "-d $DB_B -At")" = "7" ] \
    || falhar "o banco [B] deveria ter as 7 tabelas identity.* do 15"
migrar "$DB_B"
migrar "$DB_B"
como_app "-d $DB_B -v conferir=1 -f $REMOTE_DIR/verify.sql"
echo "    15-identity.sql reaplicado depois da migracao (idempotente, nao quebra nem recria public.users)"
como_app "-d $DB_B -f $REMOTE_DIR/init/15-identity.sql" >/dev/null
[ "$(echo "SELECT to_regclass('public.users') IS NULL AND (SELECT count(*) FROM identity.users) = 3;" | como_app "-d $DB_B -At")" = "t" ] \
    || falhar "o 15 reaplicado mexeu no estado migrado"

echo ""
echo "==> [C] Guardas (caso 14): linha em qualquer das 7 tabelas identity.* ou FK de fora ABORTA sem alterar nada: $DB_C"
montar "$DB_C" com15
como_app "-d $DB_C -v preparar=1 -f $REMOTE_DIR/verify.sql"
echo "INSERT INTO identity.users (user_name, email, fone, password, birthdate) VALUES ('intrusa', 'intrusa@test.com', '5588', 'x', '2000-01-01');" \
    | como_app "-d $DB_C"
exigir_aborto "$DB_C" "identity.users (1 linha"
echo "DELETE FROM identity.users;" | como_app "-d $DB_C"
EVENTO="(gen_random_uuid(), 'teste.intrusa', 's', 1, 'T', '{}', 's')"
echo "INSERT INTO identity.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key) VALUES $EVENTO;" \
    | como_app "-d $DB_C"
exigir_aborto "$DB_C" "identity.event_store (1 linha"
echo "INSERT INTO identity.event_outbox (global_position) SELECT global_position FROM identity.event_store;" | como_app "-d $DB_C"
exigir_aborto "$DB_C" "identity.event_outbox (1 linha"
echo "TRUNCATE identity.event_store, identity.event_outbox;" | como_app "-d $DB_C"
echo "INSERT INTO identity.event_snapshot (stream_type, stream_id, version, state) VALUES ('t', 's', 1, '{}');" | como_app "-d $DB_C"
exigir_aborto "$DB_C" "identity.event_snapshot (1 linha"
echo "TRUNCATE identity.event_snapshot;" | como_app "-d $DB_C"
echo "INSERT INTO identity.projection_checkpoint (projecao, stream_type, stream_id, version) VALUES ('p', 't', 's', 1);" | como_app "-d $DB_C"
exigir_aborto "$DB_C" "identity.projection_checkpoint (1 linha"
echo "TRUNCATE identity.projection_checkpoint;" | como_app "-d $DB_C"
echo "    FK de FORA para public.users (banco sem a 20261025/20261026) aborta, nao e derrubada"
echo "CREATE TABLE public.legado_fk (u integer CONSTRAINT legado_fk_u_fkey REFERENCES public.users (user_id));" | como_app "-d $DB_C"
exigir_aborto "$DB_C" "legado_fk_u_fkey.*20261025-reservation-schema.sql"
[ "$(echo "SELECT count(*) FROM pg_constraint WHERE conname = 'legado_fk_u_fkey';" | como_app "-d $DB_C -At")" = "1" ] \
    || falhar "a FK de fora sumiu (deveria ficar intacta)"
echo "DROP TABLE public.legado_fk;" | como_app "-d $DB_C"
echo "    limpo o estado inesperado, a migracao passa (as guardas nao deixaram sujeira)"
migrar "$DB_C"
como_app "-d $DB_C -v conferir=1 -f $REMOTE_DIR/verify.sql"

echo ""
echo "==> [D] Init COMPLETO (supabase-app-init.sql + init-scripts atuais) em banco vazio: $DB_INIT"
como_admin_supabase "-d postgres -c 'CREATE DATABASE $DB_INIT;'"
docker exec -i "$CONTAINER" sed -i "s#/app-init/#$REMOTE_DIR/init/#" "$REMOTE_DIR/supabase-app-init.sql"
SAIDA_INIT="$(docker exec -i "$CONTAINER" sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d '"$DB_INIT"' -f '"$REMOTE_DIR/supabase-app-init.sql" 2>&1)"
echo "$SAIDA_INIT" | grep "Testes minimos concluidos com sucesso" || falhar "init sem sucesso: $SAIDA_INIT"
ESTADO="$(echo "SELECT to_regclass('public.users') IS NULL, to_regclass('identity.users') IS NOT NULL, to_regclass('identity.mywallet') IS NOT NULL;" \
    | como_admin_supabase "-d $DB_INIT -At")"
echo "    to_regclass('public.users') IS NULL, identity.users e identity.mywallet existem -> $ESTADO"
[ "$ESTADO" = "t|t|t" ] || falhar "banco novo deveria nascer so com identity.users/mywallet"
[ "$(echo "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace ns ON ns.oid = t.relnamespace JOIN pg_class r ON r.oid = c.confrelid JOIN pg_namespace rn ON rn.oid = r.relnamespace WHERE c.contype = 'f' AND (ns.nspname = 'identity') <> (rn.nspname = 'identity');" \
    | como_admin_supabase "-d $DB_INIT -At")" = "0" ] || falhar "FK cruzando o schema identity no init"

echo ""
echo "=================================================="
echo "   RESULTADO: TODOS OS PASSOS PASSARAM"
echo "   - [A] sem o 15: migracao idempotente (2x), ids/identity/triggers/streams (caso 9) e rollback do dump"
echo "   - [B] com o 15 vazio (I3): migra; o 15 reaplicado depois e inofensivo"
echo "   - [C] guardas (caso 14): linha em users/event_store/event_outbox/event_snapshot/projection_checkpoint e FK de fora abortam"
echo "   - [D] init completo em banco vazio, sem public.users"
echo "=================================================="
echo ""
