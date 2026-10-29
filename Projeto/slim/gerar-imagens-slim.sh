#!/bin/bash

# =============================================================================
#  gerar-imagens-slim.sh  -  issue #177 (Batch 2, T4): imagens enxutas dos servicos com o Slim
# =============================================================================
#
#  Gera, por `slim build --target <imagem>`, a versao enxuta de backend, identity, catalog, reservation, notification e admission
#  (o `admission` entrou na #178). As imagens originais (Dockerfiles intocados) continuam o padrao do
#  compose; as `*.slim` entram so pelo override Projeto/docker-compose.slim.yml (ver slim/README.md).
#
#  COMO: sobe, num project docker compose ISOLADO (gerenciador-e2e-slim*, mesmo override de isolamento do run-e2e: sem
#  container_name, sem porta no host, rede e volumes proprios, e-mail num captador Mailpit), as dependencias reais
#  (db, redpanda, supabase-pooler, mailpit, redis, rabbitmq) e os servicos gordos. Para cada alvo: para o servico gordo de mesmo nome, roda o Slim
#  (container dslim/slim, versao fixa) com o container-alvo na rede do project e um probe que exercita os endpoints reais
#  (--http-probe-cmd-file: /internal/** com X-Internal-Token, /bff/**, actuator, JWKS) e o caminho Kafka (os consumers/relays do
#  alvo sobem junto; o notification, sem HTTP, processa um VerificationCodeRequested produzido no topico antes do start).
#  NUNCA contra a stack real (gerenciador-eventos): o probe escreve no banco e o notification manda e-mail.
#
#  Segredos: os valores de ambiente do alvo vem do `docker compose config` (que carrega o Projeto/.env) e vao a um arquivo
#  temporario montado no container do Slim (--env-file); nada deles e impresso e a saida do Slim passa por um filtro que
#  mascara qualquer valor. Os perfis Seccomp/AppArmor (sem segredo) vao para slim/perfis/; o resto dos artefatos do Slim
#  (creport, Dockerfile.reversed) NAO e versionado.
#
#  Uso:  Projeto/slim/gerar-imagens-slim.sh [servico ...]      (padrao: identity reservation catalog backend notification admission)
#  Variaveis (opcionais): SLIM_PROJECT (gerenciador-e2e-slim), MANTER (1 = nao derruba o project no fim), ESPERA_UP (900).
#  Idempotente: cada execucao regenera as imagens `*.slim` (mesmas tags) a partir do codigo atual e derruba o project.
#  Pre-requisitos: docker (compose v2), python, Projeto/.env e Projeto/supabase/.env. Nada e instalado no host.
#  Compatibilidade: Linux/macOS e Git Bash do Windows (Docker Desktop).
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJETO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJETO_DIR" || exit 1

PROJETO="${SLIM_PROJECT:-gerenciador-e2e-slim}"
MANTER="${MANTER:-0}"
ESPERA_UP="${ESPERA_UP:-900}"
REDE="${PROJETO}_default"
SLIM_IMG="dslim/slim:1.40.11"
IMAGEM_CURL="maven:3.9.7-eclipse-temurin-21-jammy" # tem curl; a mesma do run-e2e
CLI="${PROJETO}-cli"
JAVA_HOME_IMG="/opt/java/openjdk"
PERFIS_DIR="$SCRIPT_DIR/perfis"
TMP="$(mktemp -d)"; chmod 700 "$TMP" # o env-file com segredos do .env passa por aqui
mkdir -p "$PERFIS_DIR"
FALHAS=0
TODOS=(identity reservation catalog backend notification admission)
SERVICOS=("$@"); [ ${#SERVICOS[@]} -gt 0 ] || SERVICOS=("${TODOS[@]}")

dexec() { # Git Bash: o docker e nativo do Windows e a conversao de path do MSYS reescreveria "/var/..." e "/work"
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@" ;;
        *) docker "$@" ;;
    esac
}
dc() { dexec compose -p "$PROJETO" -f docker-compose.yml -f e2e/docker-compose.e2e.yml "$@"; }
cli() { dexec exec -i "$CLI" "$@"; }
winpath() { case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) (cd "$1" && pwd -W) ;; *) (cd "$1" && pwd) ;; esac; }

ok()   { echo "   [ok]    $1"; }
erro() { echo "   [ERRO]  $1"; FALHAS=$((FALHAS + 1)); }
abortar() { echo "   [ABORTA] $1"; exit 1; }

case "$PROJETO" in
    gerenciador-eventos) abortar "o project '$PROJETO' e o da stack REAL: o probe escreve no banco e o notification manda e-mail." ;;
    gerenciador-e2e-slim*) ;;
    *) abortar "SLIM_PROJECT='$PROJETO' invalido: o nome precisa comecar com gerenciador-e2e-slim (o gerenciador-e2e puro e do run-e2e)." ;;
esac
ISOLADO=0
limpar() {
    dexec rm -f "$CLI" >/dev/null 2>&1
    if [ "$MANTER" = "1" ]; then echo "==> MANTER=1: project '$PROJETO' mantido."
    elif [ "$ISOLADO" = "1" ]; then echo "==> (saida) down -v do project temporario '$PROJETO'"; dc down -v --remove-orphans >/dev/null 2>&1
    fi
    rm -rf "$TMP"
}
trap limpar EXIT

PY=""
for c in python3 python; do "$c" -c 'import json' >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || abortar "preciso de python (python3)."
[ -f .env ] && [ -f supabase/.env ] || abortar "faltam Projeto/.env e/ou Projeto/supabase/.env (ver supabase/README.md)."

# --- isolamento (config) ANTES de subir qualquer coisa: so imprime violacoes (nomes), nunca valores --------------------------
VALIDADOR='
import json, sys
c = json.load(sys.stdin); proj = sys.argv[1]; erros = []
for nome, s in sorted(c["services"].items()):
    if s.get("container_name"): erros.append("servico %s: container_name fixo" % nome)
    if s.get("ports"): erros.append("servico %s: porta publicada no host" % nome)
    if s.get("network_mode"): erros.append("servico %s: network_mode" % nome)
    for rede in (s.get("networks") or {"default": None}):
        if rede != "default": erros.append("servico %s: rede %s fora do default" % (nome, rede))
    for v in (s.get("volumes") or []):
        if v.get("type") == "bind" and not v.get("read_only"): erros.append("servico %s: bind mount gravavel do host" % nome)
for chave, rede in sorted(c.get("networks", {}).items()):
    if rede.get("external") or not str(rede.get("name")).startswith(proj + "_"): erros.append("rede %s: nome efetivo %s" % (chave, rede.get("name")))
for chave, vol in sorted(c.get("volumes", {}).items()):
    if vol.get("external") or not str(vol.get("name")).startswith(proj + "_"): erros.append("volume %s: nome efetivo %s" % (chave, vol.get("name")))
env = (c["services"].get("notification") or {}).get("environment") or {}
if env.get("MAIL_HOST") != "mailpit" or env.get("MAIL_PORT") != "1025" or env.get("MAIL_SMTP_AUTH") != "false"         or env.get("MAIL_STARTTLS") != "false" or env.get("MAIL_PASSWORD") or not str(env.get("MAIL_USERNAME") or "").endswith("@example.com"):
    erros.append("notification: SMTP nao aponta para o captador mailpit:1025 sem auth/starttls/senha (remetente @example.com)")
if "mailpit" not in c["services"]:
    erros.append("falta o servico mailpit")
for e in erros: print("   " + e)
sys.exit(1 if erros else 0)
'
echo "=================================================="
echo "   SLIM #177: imagens enxutas  (PROJETO=$PROJETO, alvos: ${SERVICOS[*]})"
echo "=================================================="
echo "==> 0. isolamento (config) ANTES do up"
VIOLACOES="$(dc config --format json 2>/dev/null | "$PY" -c "$VALIDADOR" "$PROJETO" 2>&1)"
if [ $? -ne 0 ] || [ -n "$VIOLACOES" ]; then echo "$VIOLACOES"; abortar "isolamento nao garantido: nada foi subido."; fi
ok "sem container_name, sem porta no host, rede/volumes com o prefixo do project, notification -> mailpit"
ISOLADO=1

# --- por servico: porta HTTP (vazio = sem HTTP) e nomes das imagens -----------------------------------------------------------
porta_de() { case "$1" in backend) echo 8081 ;; identity) echo 8084 ;; catalog) echo 8083 ;; reservation) echo 8082 ;; admission) echo 8085 ;; *) echo "" ;; esac; }
fat_de() { echo "${PROJETO}-$1:latest"; }                  # imagem gorda construida pelo compose neste project
slim_de() { echo "gerenciador-eventos-$1.slim:latest"; }   # a que o override docker-compose.slim.yml usa
for s in "${SERVICOS[@]}"; do
    case "$s" in backend|identity|catalog|reservation|notification|admission) ;; *) abortar "servico desconhecido: $s" ;; esac
done

echo "==> 1. project limpo; imagens gordas (build deste codigo); dependencias reais + servicos gordos no ar"
dc down -v --remove-orphans >/dev/null 2>&1
COMPOSE_PARALLEL_LIMIT=2 dc build identity reservation catalog backend notification admission >"$TMP/build.log" 2>&1 || { tail -15 "$TMP/build.log"; abortar "build das imagens gordas falhou"; }
# so o que o probe precisa (com as dependencias do compose: db, supabase-pooler, redpanda, mailpit, notification-seek, redis, rabbitmq); sem frontend/studio/kong/meta
dc up -d --wait --wait-timeout "$ESPERA_UP" identity reservation catalog backend notification admission || { dc ps -a; abortar "up --wait falhou (algum servico nao ficou healthy)"; }
dexec rm -f "$CLI" >/dev/null 2>&1
dexec run -d --rm --name "$CLI" --network "$REDE" --entrypoint sleep "$IMAGEM_CURL" infinity >/dev/null || abortar "container auxiliar de curl"

# env de um servico (do config, que carrega o .env), em arquivo temporario; NUNCA impresso
env_arquivo() { # servico arquivo-destino: KEY=VALUE por linha
    dc config --format json | "$PY" -c '
import json, sys
env = json.load(sys.stdin)["services"][sys.argv[1]].get("environment") or {}
with open(sys.argv[2], "w", newline="\n") as f:
    for k, v in sorted(env.items()):
        if v is not None and "\n" not in str(v): f.write("%s=%s\n" % (k, v))
' "$1" "$2"
}
valor() { sed -n "s/^$2=//p" "$TMP/env-$1" | head -1; }
http() { # metodo url [corpo] -> HTTP_CODE HTTP_BODY (curl no container auxiliar; cabecalho do token interno sempre)
    local args=(-s -m 30 -X "$1" -w '\n%{http_code}' -H "X-Internal-Token: $TOKEN_INT" "$2") out
    [ -n "${3:-}" ] && args+=(-H 'Content-Type: application/json' -d "$3")
    out="$(cli curl "${args[@]}" | tr -d '\r')"
    HTTP_CODE="${out##*$'\n'}"; HTTP_BODY="${out%$'\n'*}"
}
# mascara qualquer valor de ambiente (>= 6 caracteres) na saida do Slim antes de mostrar
mascarar() { "$PY" -c '
import sys
vals = {l.rstrip("\n").split("=", 1)[1] for l in open(sys.argv[1]) if "=" in l}
vals = sorted((v for v in vals if len(v) >= 6), key=len, reverse=True)
for linha in sys.stdin:
    for v in vals: linha = linha.replace(v, "***")
    sys.stdout.write(linha)
' "$1"; }

env_arquivo identity "$TMP/env-identity"; TOKEN_INT="$(valor identity INTERNAL_API_TOKEN)"
[ -n "$TOKEN_INT" ] || abortar "sem INTERNAL_API_TOKEN no Projeto/.env (ver CLAUDE.md, 'INTERNAL_API_TOKEN')."

SUFIXO="$(date +%s)-$RANDOM"
EMAIL_PROBE="slim-$SUFIXO@example.com"
FONE_PROBE="11$(printf '%09d' $(( (RANDOM * 32768 + RANDOM) % 1000000000 )))"

# Probe: lista de chamadas (metodo, recurso, cabecalhos, corpo) em JSON por servico. Segredos so entram no arquivo temporario.
gerar_probe() { # servico token-jwt (so backend)
    "$PY" -c '
import json, sys
svc, tok_int, email, fone, jwt = sys.argv[1:6]
H = ["X-Internal-Token:" + tok_int, "Content-Type:application/json"]
def cmd(m, r, body=None, headers=None):
    c = {"protocol": "http", "method": m, "resource": r, "headers": headers if headers is not None else H}
    if body is not None: c["body"] = json.dumps(body)
    return c
user = {"name": "Slim Probe", "email": email, "password": "senha123", "fone": fone, "birthdate": "1990-01-15"}
if svc == "identity":
    email, fone = "idp-" + email, "12" + fone[2:]  # o usuario do backend (email e fone puros) ja existe no identity gordo
    user["email"], user["fone"] = email, fone
    cmds = [
        cmd("GET", "/.well-known/jwks.json", headers=[]),
        cmd("POST", "/internal/users", user),
        cmd("POST", "/internal/users/login", {"email": email, "password": "senha123"}),
        cmd("POST", "/internal/users/login", {"email": email, "password": "errada"}),
        cmd("GET", "/internal/users/by-email?email=" + email),
        cmd("GET", "/internal/users/1"), cmd("GET", "/internal/users/1/exists"),
        cmd("POST", "/internal/users/names", [1, 2]),
        cmd("GET", "/internal/wallets/1"), cmd("PUT", "/internal/wallets/1"), cmd("GET", "/internal/wallets/1/exists"),
        cmd("POST", "/internal/users/validate", user),
        cmd("POST", "/internal/registrations", {"email": "reg-" + email, "fone": fone, "userName": "Slim Reg", "password": "senha123", "birthDate": "1990-01-15"}),
        cmd("POST", "/internal/registrations/resend", {"email": "reg-" + email}),
        cmd("POST", "/internal/registrations/verify", {"email": "reg-" + email, "verificationCode": "000000"}),
        cmd("POST", "/internal/recaptcha/verify", {"token": "probe", "action": "login"}),
        cmd("GET", "/internal/users/1", headers=[]),
    ]
elif svc == "catalog":
    ev = {"event_name": "Slim " + email, "is_EAD": True, "event_date": "2035-12-01T14:00:00", "description": "slim probe", "lot_quantity": 5, "presenters": ["Ana"], "creator_id": 1}
    cmds = [
        cmd("POST", "/internal/events/validate", ev),
        cmd("POST", "/internal/events", ev),
        cmd("GET", "/internal/events/search?term=Slim"), cmd("GET", "/internal/events?creatorId=1"),
        cmd("GET", "/internal/events/1"), cmd("GET", "/internal/events/1/exists"), cmd("GET", "/internal/events/1/creator"),
        cmd("GET", "/internal/events/1/image"),
        cmd("PUT", "/internal/events/1", ev),
        cmd("GET", "/internal/events/1", headers=[]),
    ]
elif svc == "reservation":
    cap = {"capacity": 5, "buyTimeLimit": "2035-12-01T14:00:00", "maxTimeInLine": 600}
    cmds = [
        cmd("PUT", "/internal/eventcaps/900001", cap), cmd("GET", "/internal/eventcaps/900001"),
        cmd("POST", "/internal/eventcaps/batch", [900001]),
        cmd("PATCH", "/internal/eventcaps/900001/policy", cap),
        cmd("GET", "/internal/events/900001/availability"),
        cmd("POST", "/internal/reservations?path=cas", {"userId": 900001, "eventId": 900001}, H + ["Idempotency-Key:slim-1"]),
        cmd("GET", "/internal/reservations/replay?userId=900001&eventId=900001&idempotencyKey=slim-1"),
        cmd("GET", "/internal/reservations/active?userId=900001&eventId=900001"),
        cmd("POST", "/internal/reservations/confirm", {"userId": 900001, "eventId": 900001}),
        cmd("GET", "/internal/reservations/user/900001"),
        cmd("PUT", "/internal/eventcaps/900002", cap),
        cmd("POST", "/internal/reservations?path=naive", {"userId": 900002, "eventId": 900002}),
        cmd("DELETE", "/internal/reservations?userId=900002&eventId=900002"),
        cmd("DELETE", "/internal/eventcaps/900002"),
        cmd("GET", "/internal/eventcaps/900001", headers=[]),
    ]
elif svc == "admission":
    # X baixo (ADMISSION_X_RPM=60, imposto no env-file do alvo): as 1as tentativas ficam sync, as seguintes passam de 80% de X e o evento entra em modo
    # fila (script Lua do contador + enfileirar.lua + publicacao no RabbitMQ). O worker pool (@RabbitListener) consome e chama o reservation gordo (cas):
    # o script confere depois que existem reservas do evento 910001 (so o worker as cria: o POST /internal/admissions nunca reserva).
    ev = 910001
    pedido = lambda k: {"eventId": ev, "userId": ev + k, "idempotencyKey": "slim-adm-%d" % k, "path": "cas"}
    cmds = [cmd("POST", "/internal/admissions", pedido(k)) for k in range(1, 61)]
    cmds += [cmd("GET", "/internal/admissions/events/%d/rpm" % ev)]
    cmds += [cmd("GET", "/internal/admissions/result?userId=%d&idempotencyKey=slim-adm-%d" % (ev + k, k)) for k in range(1, 61)]
    cmds += [
        cmd("POST", "/internal/admissions", pedido(1)),                                   # repeticao da chave: devolve o estado, nao enfileira de novo
        cmd("POST", "/internal/admissions", dict(pedido(2), eventId=ev + 1)),             # chave de outro evento: 422
        cmd("GET", "/internal/admissions/result?userId=1&idempotencyKey=inexistente"),    # 404
        cmd("POST", "/internal/admissions", pedido(3), headers=[]),                       # sem token: 401
    ]
elif svc == "backend":
    B = ["Content-Type:application/json"]
    A = B + ["Authorization:Bearer " + jwt]
    ev = {"event_name": "Slim BFF " + email, "is_EAD": True, "event_date": "2035-12-01T14:00:00", "description": "slim probe", "lot_quantity": 5, "presenters": []}
    cmds = [
        cmd("GET", "/actuator/health", headers=[]),
        cmd("POST", "/bff/users/login", {"email": email, "password": "senha123"}, B),
        cmd("POST", "/bff/users/login", {"email": email, "password": "errada"}, B),
        cmd("GET", "/bff/users/me", headers=A),
        cmd("GET", "/bff/users/me", headers=["Authorization:Bearer invalido"]),
        cmd("POST", "/bff/events", ev, A),
        cmd("GET", "/bff/events/search?term=Slim", headers=A),
        cmd("GET", "/bff/events/1", headers=A),
        cmd("GET", "/bff/event-wallets/user/1", headers=A),
        cmd("POST", "/bff/event-wallets", {"userId": 1, "eventId": 1}, A + ["Idempotency-Key:slim-bff-1"]),
        cmd("POST", "/bff/event-wallets/confirm", {"userId": 1, "eventId": 1}, A),
        cmd("GET", "/bff/my-wallets/1", headers=A),
        cmd("POST", "/bff/users/request-verification", {"name": "Slim Reg", "email": "bff-" + email, "password": "senha123", "fone": fone, "birthdate": "1990-01-15"}, B),
        cmd("GET", "/bff/events?creator_id=1", headers=A),
    ]
else:
    cmds = []
print(json.dumps({"commands": cmds}))
' "$1" "$TOKEN_INT" "$EMAIL_PROBE" "$FONE_PROBE" "${2:-}" > "$TMP/probe-$1.json"
}

# backend precisa de um token JWT real: cria o usuario de probe no identity GORDO e faz login
JWT_PROBE=""
preparar_jwt() {
    http POST "http://identity:8084/internal/users" "{\"name\":\"Slim Probe\",\"email\":\"$EMAIL_PROBE\",\"password\":\"senha123\",\"fone\":\"$FONE_PROBE\",\"birthdate\":\"1990-01-15\"}"
    [ "$HTTP_CODE" = "201" ] || { echo "   (POST /internal/users no identity gordo: HTTP $HTTP_CODE)"; return 1; }
    dc exec -T db sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$POSTGRES_DB" -At' <<<"UPDATE identity.users SET host = true WHERE email = '$EMAIL_PROBE'" >/dev/null # host: o POST /bff/events exige
    http POST "http://identity:8084/internal/users/login" "{\"email\":\"$EMAIL_PROBE\",\"password\":\"senha123\"}"
    JWT_PROBE="$(grep -o '"token":"[^"]*"' <<<"$HTTP_BODY" | head -1 | cut -d'"' -f4)"
    [ -n "$JWT_PROBE" ]
}

psql_probe() { dc exec -T db sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$POSTGRES_DB" -At' <<<"$1" | tr -d '\r'; }

# notification (sem HTTP): o "probe" e o consumer processar uma mensagem; produz um pedido de e-mail antes do start
produzir_pedido_email() {
    local envelope
    envelope="$("$PY" -c '
import datetime, json, sys, uuid
agora = datetime.datetime.now(datetime.timezone.utc)
fmt = lambda t: t.strftime("%Y-%m-%dT%H:%M:%S.000Z")
print(json.dumps({"eventId": str(uuid.uuid4()), "streamType": "identity.notification", "streamId": str(uuid.uuid4()),
    "version": 1, "globalPosition": 0, "eventType": "VerificationCodeRequested", "schemaVersion": 1, "occurredAt": fmt(agora),
    "payload": {"email": sys.argv[1], "userName": "Slim", "code": "654321", "expirationMinutes": 240,
                "expiresAt": fmt(agora + datetime.timedelta(hours=4))}, "metadata": {}}, separators=(",", ":")))
' "$1")"
    dc exec -T redpanda rpk topic produce identity.notification -k slim <<<"$envelope" 2>&1 | grep -q offset
}

# includes (motivos em slim/README.md, "Includes e motivo")
INCLUDES_COMUM=(
    --include-shell
    --include-exe bash --include-exe head --include-exe grep --include-exe awk
    --include-path "$JAVA_HOME_IMG/lib"
    --include-path "$JAVA_HOME_IMG/conf"
    --include-path /tmp
)

slimar() { # servico
    local s="$1" img slim porta log="$TMP/slim-$1.log" extra=() probe
    img="$(fat_de "$s")"; slim="$(slim_de "$s")"; porta="$(porta_de "$s")"
    echo "==> slim build: $s  ($img -> $slim)"
    env_arquivo "$s" "$TMP/env-$s"
    if [ "$s" = admission ]; then # X baixo no alvo (o env-file vence o do compose): o probe liga o modo fila com poucas chamadas. O eventcap do evento do probe nasce no reservation gordo.
        grep -q '^ADMISSION_X_RPM=' "$TMP/env-$s" && sed -i 's/^ADMISSION_X_RPM=.*/ADMISSION_X_RPM=60/' "$TMP/env-$s" || echo 'ADMISSION_X_RPM=60' >> "$TMP/env-$s"
        http PUT "http://reservation:8082/internal/eventcaps/910001" '{"capacity":500,"buyTimeLimit":"2035-12-01T14:00:00","maxTimeInLine":600}'
        [ "$HTTP_CODE" = 200 ] || [ "$HTTP_CODE" = 201 ] || erro "admission: nao consegui criar o eventcap do probe no reservation gordo (HTTP $HTTP_CODE)"
    fi
    dc stop "$s" >/dev/null 2>&1                                  # o consumer group de 1 particao tem que cair no container-alvo
    case "$s" in
        identity) extra+=(--include-path /var/lib/identity --exclude-pattern '/var/lib/identity/*') ;; # o diretorio fica (o compose monta o volume ali); a chave RSA gerada no boot do alvo NAO pode ir na imagem
        backend|notification) extra+=(--include-exe printenv) ;; # backend: os smokes leem RESERVATION_READPATH; notification: o run-e2e le MAIL_HOST
    esac
    if [ -n "$porta" ]; then
        gerar_probe "$s" "$JWT_PROBE"
        probe=(--http-probe-cmd-file "/work/probe-$s.json" --http-probe-retry-count 8 --http-probe-exit-on-failure=false --continue-after probe)
    else
        produzir_pedido_email "pedido-$SUFIXO@example.com" || erro "notification: nao consegui produzir o pedido de e-mail no topico"
        probe=(--http-probe=false --continue-after 120)
    fi
    local cmd=(run --rm --network "$REDE" -v /var/run/docker.sock:/var/run/docker.sock -v "$(winpath "$TMP"):/work"
        "$SLIM_IMG" --check-version=false --crt-api-version 1.44 --report off --archive-state off
        build --target "$img" --tag "$slim" --network "$REDE" --env-file "/work/env-$s"
        --copy-meta-artifacts "/work/meta-$s" --show-clogs "${probe[@]}" "${INCLUDES_COMUM[@]}" "${extra[@]}")
    mkdir -p "$TMP/meta-$s"
    dexec "${cmd[@]}" 2>&1 | tr -d '\r' | mascarar "$TMP/env-$s" > "$log"
    grep -E "http.probe.call|results status|results image.name|state=exited|level=(error|fatal)" "$log" | cut -c1-260 | sed 's/^/     /'
    if [ "$s" = admission ]; then # o worker da imagem-alvo tem que ter consumido a fila e reservado no reservation gordo (so ele cria reservas desse evento)
        local n_res
        n_res="$(psql_probe "SELECT count(*) FROM reservation.walletevent WHERE event_id = 910001")"
        if [ "${n_res:-0}" -ge 1 ] 2>/dev/null; then ok "admission: o worker da imagem-alvo consumiu a fila e criou $n_res reserva(s) (AMQP + Lua exercitados)"
        else erro "admission: nenhuma reserva do evento 910001: o listener AMQP/worker da imagem-alvo nao rodou (obtido '${n_res:-}')"; fi
    fi
    if [ -z "$porta" ]; then # notification: o consumer da imagem-alvo tem que ter processado o pedido (e-mail no captador)
        local n
        n="$(cli curl -s -G "http://mailpit:8025/api/v1/search" --data-urlencode "query=to:pedido-$SUFIXO@example.com" | grep -oE '"messages_count": ?[0-9]+' | grep -oE '[0-9]+$')"
        if [ "$n" = "1" ]; then ok "notification: o consumer da imagem-alvo processou a mensagem (1 e-mail no captador)"; else erro "notification: o pedido de e-mail nao virou e-mail no captador (obtido '$n')"; fi
    fi
    if dexec image inspect "$slim" >/dev/null 2>&1; then ok "$s: imagem $slim gerada"; else erro "$s: o Slim nao gerou $slim"; tail -15 "$log" | cut -c1-240 | sed 's/^/     /'; fi
    # perfis de seguranca (sem segredo) ao lado do override; o resto dos artefatos nao e versionado
    for f in "$TMP/meta-$s"/*seccomp*.json "$TMP/meta-$s"/*apparmor*; do
        [ -f "$f" ] || continue
        case "$f" in *seccomp*) cp "$f" "$PERFIS_DIR/$s-seccomp.json" ;; *) cp "$f" "$PERFIS_DIR/$s-apparmor-profile" ;; esac
    done
    dc up -d --wait --wait-timeout "$ESPERA_UP" "$s" >/dev/null 2>&1 || echo "   (aviso: o $s gordo nao voltou a ficar healthy)"
}

echo "==> 2. slim build por servico"
if printf '%s\n' "${SERVICOS[@]}" | grep -qx backend; then
    if preparar_jwt; then ok "usuario de probe criado e token JWT obtido do identity gordo"; else erro "sem token JWT de probe: o probe autenticado do backend fica degradado"; fi
fi
for s in "${SERVICOS[@]}"; do slimar "$s"; done

echo "==> 3. as imagens enxutas tem os binarios dos healthchecks do compose?"
for s in "${SERVICOS[@]}"; do
    dexec image inspect "$(slim_de "$s")" >/dev/null 2>&1 || continue
    if dexec run --rm --entrypoint bash "$(slim_de "$s")" -c 'command -v bash head grep awk >/dev/null'; then ok "$s: bash head grep awk presentes"; else erro "$s: falta binario de healthcheck"; fi
    case "$s" in backend|notification)
        if dexec run --rm --entrypoint bash "$(slim_de "$s")" -c 'command -v printenv >/dev/null'; then ok "$s: printenv presente"; else erro "$s: falta printenv"; fi ;;
    esac
done

if printf '%s
' "${SERVICOS[@]}" | grep -qx identity && dexec image inspect "$(slim_de identity)" >/dev/null 2>&1; then
    # guarda: a chave privada do JWT (gerada no boot do alvo) nao pode ter entrado na imagem; o diretorio existe e vazio
    CHAVE_LS="$(dexec run --rm --entrypoint bash "$(slim_de identity)" -c 'test -d /var/lib/identity && ls -A /var/lib/identity' 2>&1)"
    if [ $? -eq 0 ] && [ -z "$CHAVE_LS" ]; then ok "identity: /var/lib/identity existe e esta VAZIO (sem chave do JWT na imagem): ls -A => ''"
    else erro "identity: /var/lib/identity ausente ou NAO vazio na imagem slim (ls -A => '$CHAVE_LS'): chave do JWT embutida? NAO use esta imagem"; fi
fi

echo "==> 4. antes / depois (docker image ls)"
printf '   %-14s %-42s %-12s %-42s %s\n' servico "antes (gordo)" tamanho "depois (slim)" tamanho
for s in "${SERVICOS[@]}"; do
    printf '   %-14s %-42s %-12s %-42s %s\n' "$s" "$(fat_de "$s")" "$(dexec image ls --format '{{.Size}}' "$(fat_de "$s")" | head -1)" \
        "$(slim_de "$s")" "$(dexec image ls --format '{{.Size}}' "$(slim_de "$s")" | head -1)"
done
echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then echo "   RESULTADO: SLIM OK (${SERVICOS[*]})"; else echo "   RESULTADO: $FALHAS ERRO(S)"; fi
echo "=================================================="
[ "$FALHAS" -eq 0 ]
