#!/usr/bin/env bash
# Biblioteca (só `source`) do cliente psql do banco no cluster (issue #228), usada pelo cluster.sh e pelo kit de carga (CARGA_K8S). Funções com o CONTEXTO kubectl
# como 1º argumento: todo kubectl daqui leva --context (nunca o current-context). Namespace fixo `gerenciador`. Segredos: só dentro do pod (ver db-cliente.yaml).
DB_NS=gerenciador
DB_CLIENTE_YAML="${DB_CLIENTE_YAML:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/db-cliente.yaml}"

# kubectl com o contexto e o namespace; sem a conversão de path do Git Bash (o kubectl é nativo do Windows: "/bin/sh" etc. não podem ser reescritos nos argumentos)
_dbk() { local ctx="$1"; shift; MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' kubectl --context "$ctx" -n "$DB_NS" "$@"; }

# db_remoto <contexto>: 0 = db FORA do cluster (o sts/db NAO existe), 1 = db no cluster, 2 = ERRO ao perguntar (nunca vira "remoto": nao existir e errar sao coisas diferentes)
db_remoto() {
    local n
    n="$(_dbk "$1" get sts/db --ignore-not-found -o name 2>/dev/null)" || return 2
    [ -z "$n" ]
}

# db_cliente_garantir <contexto>: cria o pod cliente (idempotente) e espera Ready. O manifest entra por stdin (o bash abre o arquivo: sem path do Git Bash para o kubectl.exe).
db_cliente_garantir() {
    _dbk "$1" apply -f - < "$DB_CLIENTE_YAML" >/dev/null || { echo "ERRO: não consegui criar o pod db-cliente no contexto $1" >&2; return 1; }
    _dbk "$1" wait --for=condition=Ready pod/db-cliente --timeout="${DB_CLIENTE_ESPERA:-180}s" >/dev/null \
        || { echo "ERRO: o pod db-cliente não ficou Ready (kubectl --context $1 -n $DB_NS describe pod db-cliente)" >&2; return 1; }
}

# Script do psql DENTRO do pod: $1 = usuário (`app` = APP_DB_USER com a APP_DB_PASSWORD; outro nome = esse usuário com a POSTGRES_PASSWORD, ex. supabase_admin), $2 = host.
# Aspas simples: o shell do host NUNCA expande as senhas (elas só existem no ambiente do pod).
DB_PSQL_SCRIPT='u="$1"; h="$2"; shift 2
if [ "$u" = app ]; then u="$APP_DB_USER"; PGPASSWORD="$APP_DB_PASSWORD"; else PGPASSWORD="$POSTGRES_PASSWORD"; fi
export PGPASSWORD
exec psql -h "$h" -U "$u" -d "$POSTGRES_DB" "$@"'

# db_psql_preparar <contexto>: decide local x remoto UMA vez por processo (DB_PSQL_ALVO / DB_PSQL_HOST) e, com o db remoto, garante o pod cliente UMA vez. Chame no processo principal
# (nao dentro de $(...), que e um subshell e perderia as variaveis); depois disso o db_psql so faz o `kubectl exec` (1 kubectl por psql).
db_psql_preparar() {
    local r
    db_remoto "$1"; r=$?
    case "$r" in
        0) db_cliente_garantir "$1" || return 1; DB_PSQL_ALVO=pod/db-cliente; DB_PSQL_HOST=db ;;
        1) DB_PSQL_ALVO=sts/db; DB_PSQL_HOST=localhost ;;
        *) echo "ERRO: nao consegui ler o StatefulSet db no contexto $1 (o cluster respondeu com erro, nao com 'nao existe'): nao decido entre db no cluster e db remoto" >&2; return 1 ;;
    esac
}

# db_psql <contexto> <usuario|app> [args do psql...]: stdin repassado; exige db_psql_preparar antes. Db no cluster = exec no sts/db (localhost); db remoto = exec no pod db-cliente (host `db`,
# o Service sem selector -> EndpointSlice do banco remoto).
db_psql() {
    local ctx="$1" usuario="$2"; shift 2
    [ -n "${DB_PSQL_ALVO:-}" ] || { echo "ERRO: db_psql sem db_psql_preparar antes" >&2; return 1; }
    # -c <container> (o nome do alvo: sts/db -> db): sem ele, pod com init container (o db tem o copiar-config) imprime "Defaulted container ..." no stderr
    _dbk "$ctx" exec -i "$DB_PSQL_ALVO" -c "${DB_PSQL_ALVO#*/}" -- sh -c "$DB_PSQL_SCRIPT" sh "$usuario" "$DB_PSQL_HOST" "$@"
}
