#!/bin/bash

# =============================================================================
#  run-e2e.sh  -  issue #37 (Fase 7): suite de integracao da stack inteira
# =============================================================================
#
#  Sobe a stack COMPLETA (gateway/backend, identity, catalog, reservation, notification, frontend, supabase-*, redpanda) num
#  project docker compose TEMPORARIO e isolado da stack real (gerenciador-eventos), com um captador de e-mail (Mailpit) no
#  lugar do Gmail, e prova o fluxo ponta a ponta pelo gateway:
#    cadastro em duas fases (o codigo vem do E-MAIL no captador) -> criar evento -> reservar -> confirmar -> e-mail
#    + replay (event sourcing) de catalog.event e reservation.wallet contra o banco do project: sombra == projecao viva.
#  Sem porta no host: as chamadas HTTP saem de um container auxiliar na rede do project. No fim (verde ou nao) faz
#  `down -v` SO do project temporario, salvo MANTER=1.
#
#  ISOLAMENTO (conferido por `config` ANTES do `up`; se falhar, aborta sem subir nada): o override
#  e2e/docker-compose.e2e.yml remove container_name e portas de todos os servicos e tira o nome fixo gerenciador-net da rede
#  (senao `db`/`redpanda`/`supabase-pooler` resolveriam para os containers REAIS e o project escreveria no meu_banco de verdade).
#  O project precisa se chamar gerenciador-e2e*; gerenciador-eventos (o real) e recusado, e o `down -v` tambem confere.
#  E-MAIL: o script aborta se o notification nao estiver apontado para o captador. Nenhum e-mail real sai.
#  Segredos: `config --format json` carrega os segredos do Projeto/.env; so passa por um filtro que imprime nomes de
#  container/porta/rede/volume. O Projeto/.env e o supabase/.env precisam existir (nada deles e impresso).
#
#  Pre-requisitos: docker (compose v2), python (so para filtrar o JSON do `config`), Projeto/.env e Projeto/supabase/.env.
#  A stack real pode estar no ar (portas e nomes nao colidem), mas o project sobe ~15 containers: pare a real se faltar RAM
#  (`docker compose -p gerenciador-eventos stop`, nunca `down`).
#
#  Uso:  Projeto/e2e/run-e2e.sh
#  Variaveis (opcionais): E2E_PROJECT (gerenciador-e2e), MANTER (1 = nao derruba o project), ESPERA_UP (900, segundos do --wait).
#  Compatibilidade: Linux/macOS e Git Bash do Windows (Docker Desktop).
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJETO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJETO_DIR" || exit 1

PROJETO="${E2E_PROJECT:-gerenciador-e2e}"
MANTER="${MANTER:-0}"
ESPERA_UP="${ESPERA_UP:-900}"
REDE="${PROJETO}_default"
CLI="${PROJETO}-cli"
SUFIXO="$(date +%s)-$RANDOM"
EMAIL="e2e-$SUFIXO@example.com"
FONE="11$(printf '%09d' $(( (RANDOM * 32768 + RANDOM) % 1000000000 )))"
IMAGEM_MVN="maven:3.9.7-eclipse-temurin-21-jammy"
FALHAS=0
TMP="$(mktemp -d)"

# Git Bash: o docker e nativo do Windows e a conversao de path do MSYS reescreveria "/dev/tcp/..." e "/var/...".
dexec() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@" ;;
        *) docker "$@" ;;
    esac
}
dc() { dexec compose -p "$PROJETO" -f docker-compose.yml -f e2e/docker-compose.e2e.yml "$@"; }
cli() { dexec exec -i "$CLI" "$@"; }
psql_db() { # a role do app e a do container; -h localhost evita o peer auth do socket
    dc exec -T db sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$POSTGRES_DB" -At' <<<"$1" | tr -d '\r'
}

ok()   { echo "   [ok]    $1"; }
erro() { echo "   [ERRO]  $1"; FALHAS=$((FALHAS + 1)); }
confere() { # descricao esperado obtido
    if [ "$2" = "$3" ]; then ok "$1: $3"; else erro "$1: esperado '$2', obtido '$3'"; fi
}
abortar() { echo "   [ABORTA] $1"; exit 1; }

# --- guarda do project: NUNCA o real ---------------------------------------------------------------------------------------
case "$PROJETO" in
    gerenciador-eventos) abortar "o project '$PROJETO' e o da stack REAL: esta suite so roda num project temporario (gerenciador-e2e*)." ;;
    gerenciador-e2e*) ;;
    *) abortar "E2E_PROJECT='$PROJETO' invalido: o nome precisa comecar com gerenciador-e2e." ;;
esac

ISOLADO=0 # so vira 1 depois que o `config` passou na validacao de isolamento (passo 0)
derrubar() { # down -v SO do project temporario e SO com o isolamento validado (config rejeitado => nao derruba nada)
    case "$PROJETO" in gerenciador-e2e*) [ "$ISOLADO" = "1" ] && dc down -v --remove-orphans >/dev/null 2>&1 ;; esac
}
limpar() {
    dexec rm -f "$CLI" >/dev/null 2>&1
    if [ "$MANTER" = "1" ]; then
        echo "==> MANTER=1: project '$PROJETO' mantido. Para apagar: docker compose -p $PROJETO -f docker-compose.yml -f e2e/docker-compose.e2e.yml down -v"
    else
        echo "==> (saida) down -v do project temporario '$PROJETO'"
        derrubar
        [ "$ISOLADO" = "1" ] || echo "   (isolamento nao validado: nada foi derrubado)"
    fi
    rm -rf "$TMP"
}
trap limpar EXIT

PY=""
for c in python3 python; do
    if "$c" -c 'import json' >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -n "$PY" ] || abortar "preciso de python (python3) para filtrar o JSON do 'docker compose config'."
[ -f .env ] && [ -f supabase/.env ] || abortar "faltam Projeto/.env e/ou Projeto/supabase/.env (ver supabase/README.md)."

# Le o JSON do `config` (com segredos) e imprime SO violacoes de isolamento/e-mail (nomes, nunca valores).
VALIDADOR='
import json, sys
c = json.load(sys.stdin)
proj = sys.argv[1]
erros = []
for nome, s in sorted(c["services"].items()):
    if s.get("container_name"):
        erros.append("servico %s: container_name fixo (%s)" % (nome, s["container_name"]))
    if s.get("ports"):
        erros.append("servico %s: porta publicada no host" % nome)
    if s.get("network_mode"):
        erros.append("servico %s: network_mode %s" % (nome, s["network_mode"]))
    for v in (s.get("volumes") or []):
        if v.get("type") == "bind" and not v.get("read_only"):
            erros.append("servico %s: bind mount gravavel do host (%s)" % (nome, v.get("source")))
    for rede in (s.get("networks") or {"default": None}):
        if rede != "default":
            erros.append("servico %s: rede %s fora do default" % (nome, rede))
for chave, rede in sorted(c.get("networks", {}).items()):
    nome = str(rede.get("name"))
    if rede.get("external") or nome == "gerenciador-net" or not nome.startswith(proj + "_"):
        erros.append("rede %s: nome efetivo %s (esperado %s_*)" % (chave, nome, proj))
for chave, vol in sorted(c.get("volumes", {}).items()):
    nome = str(vol.get("name"))
    if vol.get("external") or not nome.startswith(proj + "_"):
        erros.append("volume %s: nome efetivo %s (esperado %s_*)" % (chave, nome, proj))
env = (c["services"].get("notification") or {}).get("environment") or {}
if env.get("MAIL_HOST") != "mailpit" or env.get("MAIL_PORT") != "1025" or env.get("MAIL_SMTP_AUTH") != "false" \
        or env.get("MAIL_STARTTLS") != "false" or env.get("MAIL_PASSWORD")         or not str(env.get("MAIL_USERNAME") or "").endswith("@example.com"):
    erros.append("notification: SMTP nao aponta para o captador mailpit:1025 sem auth/starttls/senha (remetente @example.com)")
if "mailpit" not in c["services"]:
    erros.append("falta o servico mailpit")
for e in erros:
    print("   " + e)
sys.exit(1 if erros else 0)
'

echo "=================================================="
echo "   E2E #37: stack inteira em project temporario"
echo "   PROJETO=$PROJETO  REDE=$REDE  SUFIXO=$SUFIXO"
echo "=================================================="

echo "==> 0. isolamento (config) ANTES do up"
VIOLACOES="$(dc config --format json 2>/dev/null | "$PY" -c "$VALIDADOR" "$PROJETO" 2>&1)"
if [ $? -ne 0 ] || [ -n "$VIOLACOES" ]; then
    echo "$VIOLACOES"
    abortar "o isolamento NAO pode ser verificado/garantido: nada foi subido."
fi
ok "sem container_name, sem porta no host, rede e volumes com o prefixo do project, notification -> mailpit:1025"
ISOLADO=1

echo "==> 1. project limpo; 1a) so o redpanda; 1b) um pedido de e-mail PENDENTE no topico; 1c) up -d --build --wait (pode levar varios minutos no 1o build)"
derrubar
# O down -v deixa o topico vazio, entao "0 e-mails depois do 1o up" passaria igual SEM o seek. Para provar o seek: com o group
# notification ainda inexistente, o topico ja tem um VerificationCodeRequested valido (de um endereco @example.com, com validade
# longa para o consumer nao descarta-lo por vencido): sem o seek o group novo (earliest) o leria e mandaria o e-mail ao captador.
dc up -d --wait --wait-timeout "$ESPERA_UP" redpanda || abortar "o redpanda do project nao ficou healthy"
PENDENTE_EMAIL="pendente-$SUFIXO@example.com"
ENVELOPE="$("$PY" -c '
import datetime, json, sys, uuid
agora = datetime.datetime.now(datetime.timezone.utc)
fmt = lambda t: t.strftime("%Y-%m-%dT%H:%M:%S.000Z")
print(json.dumps({"eventId": str(uuid.uuid4()), "streamType": "identity.notification", "streamId": str(uuid.uuid4()),
    "version": 1, "globalPosition": 0, "eventType": "VerificationCodeRequested", "schemaVersion": 1, "occurredAt": fmt(agora),
    "payload": {"email": sys.argv[1], "userName": "Pendente", "code": "654321", "expirationMinutes": 240,
                "expiresAt": fmt(agora + datetime.timedelta(hours=4))}, "metadata": {}}, separators=(",", ":")))
' "$PENDENTE_EMAIL")"
dc exec -T redpanda rpk topic create identity.notification -p 1 -r 1 >/dev/null 2>&1 # produce em topico inexistente falha (UNKNOWN_TOPIC_OR_PARTITION)
PRODUZIDO="$(dc exec -T redpanda rpk topic produce identity.notification -k pendente <<<"$ENVELOPE" 2>&1 | tr -d '\r')"
confere "pedido de e-mail pendente produzido em identity.notification (offset 0)" sim "$(grep -q 'offset 0' <<<"$PRODUZIDO" && echo sim || echo nao)"
dc up -d --build --wait --wait-timeout "$ESPERA_UP" || { dc ps -a; abortar "up --wait falhou (algum servico nao ficou healthy). Logs: docker compose -p $PROJETO ... logs <servico>"; }
SAUDE="$(dc ps -a --format '{{.Service}} {{.State}} {{or .Health "-"}} {{.ExitCode}}' | tr -d '\r')"
while read -r servico estado saude codigo; do
    [ -n "$servico" ] || continue
    case "$servico" in
        notification-seek) confere "$servico (one-shot) terminou com 0" "exited 0" "$estado $codigo" ;;
        *) confere "$servico running/healthy" "running healthy" "$estado $saude" ;;
    esac
done <<<"$SAUDE"
[ "$FALHAS" -eq 0 ] || abortar "stack nao esta toda healthy."

echo "==> 2. guarda de e-mail: o notification so fala com o captador"
confere "MAIL_HOST efetivo do container notification" mailpit "$(dc exec -T notification printenv MAIL_HOST | tr -d '[:space:]')"
[ "$FALHAS" -eq 0 ] || abortar "o notification NAO esta apontado para o captador: nenhum e-mail pode sair."
dexec rm -f "$CLI" >/dev/null 2>&1
dexec run -d --rm --name "$CLI" --network "$REDE" --entrypoint sleep "$IMAGEM_MVN" infinity >/dev/null || abortar "nao consegui subir o container auxiliar de curl"
extrair_token() { grep -o '"token":"[^"]*"' <<<"$1" | head -1 | cut -d'"' -f4; }
extrair_num() { grep -oE "\"$1\" ?: ?[0-9]+" <<<"$2" | head -1 | grep -oE '[0-9]+$'; }
http() { # metodo url [corpo-json] [token] -> HTTP_CODE, HTTP_BODY
    local args=(-s -m 30 -X "$1" -w '\n%{http_code}' "$2") out
    [ -n "${3:-}" ] && args+=(-H 'Content-Type: application/json' -d "$3")
    [ -n "${4:-}" ] && args+=(-H "Authorization: Bearer $4")
    out="$(cli curl "${args[@]}" | tr -d '\r')"
    HTTP_CODE="${out##*$'\n'}"
    HTTP_BODY="${out%$'\n'*}"
}
GW="http://backend:8081"
MAILPIT="http://mailpit:8025"
mails_para() { # quantidade de e-mails para $1 no captador
    cli curl -s -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:$1" | grep -oE '"messages_count": ?[0-9]+' | grep -oE '[0-9]+$'
}
echo "   group notification depois do 1o up:"
dc exec -T redpanda rpk group describe notification 2>&1 | tr -d '\r' | sed 's/^/     /'
# group novo: o seek o pos DEPOIS do pedido pendente (offset 1, log-end 1, lag 0) e o consumer nao o releu
confere "group notification depois do 1o up: CURRENT-OFFSET LOG-END-OFFSET LAG" "1 1 0" \
    "$(dc exec -T redpanda rpk group describe notification 2>/dev/null | tr -d '\r' | awk '$1 == "identity.notification" {print $3, $5, $6}')"
sleep 5 # folga para um (hipotetico) envio do pedido pendente chegar ao captador
confere "e-mails no captador depois do 1o up (o pedido pendente NAO virou e-mail: o seek moveu o group)" 0 \
    "$(cli curl -s "$MAILPIT/api/v1/messages" | grep -oE '"total": ?[0-9]+' | head -1 | grep -oE '[0-9]+$')"
confere "e-mails para o endereco do pedido pendente" 0 "$(mails_para "$PENDENTE_EMAIL")"
[ "$FALHAS" -eq 0 ] || abortar "o 1o up mandou e-mail ou o captador nao responde."

echo "==> 3. cadastro em duas fases (o codigo vem do e-mail no captador)"
http POST "$GW/bff/users/request-verification" \
    "{\"name\":\"E2E $SUFIXO\",\"email\":\"$EMAIL\",\"password\":\"senha123\",\"fone\":\"$FONE\",\"birthdate\":\"1990-01-15\"}"
confere "POST /bff/users/request-verification" 201 "$HTTP_CODE"
CODIGO=""
for i in $(seq 1 60); do
    if [ "$(mails_para "$EMAIL")" = "1" ]; then
        ID_MAIL="$(cli curl -s -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:$EMAIL" | grep -o '"ID":"[^"]*"' | head -1 | cut -d'"' -f4)"
        CODIGO="$(cli curl -s "$MAILPIT/api/v1/message/$ID_MAIL" | sed -n 's/.*Verificação: \([0-9]\{6\}\).*/\1/p')"
        break
    fi
    sleep 2
done
confere "e-mail de verificacao chegou ao captador e traz um codigo de 6 digitos" sim "$([[ "$CODIGO" =~ ^[0-9]{6}$ ]] && echo sim || echo nao)"
[ "$FALHAS" -eq 0 ] || abortar "sem codigo de verificacao nao ha cadastro (o evento chegou ao notification?)"
http POST "$GW/bff/users/verify-email" "{\"email\":\"$EMAIL\",\"verificationCode\":\"$CODIGO\"}"
confere "POST /bff/users/verify-email com o codigo lido do e-mail" 201 "$HTTP_CODE"
# nenhuma rota promove usuario a host: por SQL ANTES do login (isHost e lido do banco na emissao do token)
psql_db "UPDATE identity.users SET host = true WHERE email = '$EMAIL'" >/dev/null
http POST "$GW/bff/users/login" "{\"email\":\"$EMAIL\",\"password\":\"senha123\"}"
confere "POST /bff/users/login" 200 "$HTTP_CODE"
TOKEN="$(extrair_token "$HTTP_BODY")"
USER_ID="$(extrair_num user_id "$HTTP_BODY")"
confere "token e user_id do login" sim "$([ -n "$TOKEN" ] && [ -n "$USER_ID" ] && echo sim || echo nao)"
[ "$FALHAS" -eq 0 ] || abortar "sem token nao ha fluxo"

echo "==> 4. criar evento -> reservar -> confirmar (pelo gateway, braco cas)"
http POST "$GW/bff/events" \
    "{\"event_name\":\"E2E $SUFIXO\",\"is_EAD\":true,\"event_date\":\"2035-12-01T14:00:00\",\"description\":\"e2e #37\",\"lot_quantity\":5,\"presenters\":[]}" "$TOKEN"
confere "POST /bff/events (host autenticado)" 201 "$HTTP_CODE"
EVENT_ID="$(extrair_num event_id "$HTTP_BODY")"
[ -n "$EVENT_ID" ] || abortar "sem event_id na resposta"
http POST "$GW/bff/event-wallets" "{\"userId\":$USER_ID,\"eventId\":$EVENT_ID}" "$TOKEN"
confere "POST /bff/event-wallets (reserva)" 201 "$HTTP_CODE"
confere "reserva em reservation.walletevent" pending \
    "$(psql_db "SELECT status FROM reservation.walletevent WHERE user_id = $USER_ID AND event_id = $EVENT_ID")"
http POST "$GW/bff/event-wallets/confirm" "{\"userId\":$USER_ID,\"eventId\":$EVENT_ID}" "$TOKEN"
confere "POST /bff/event-wallets/confirm" 200 "$HTTP_CODE"
confere "reserva em reservation.walletevent depois do confirm" confirmed \
    "$(psql_db "SELECT status FROM reservation.walletevent WHERE user_id = $USER_ID AND event_id = $EVENT_ID")"
confere "e-mails para o usuario no captador (so o de verificacao existe)" 1 "$(mails_para "$EMAIL")"
[ "$FALHAS" -eq 0 ] || abortar "o fluxo falhou antes do replay"

echo "==> 5. esperar o backbone escoar (outbox vazio e lag 0 dos consumers) antes do replay"
PENDENTES=""
for i in $(seq 1 45); do
    PENDENTES="$(psql_db "SELECT (SELECT count(*) FROM reservation.event_outbox) + (SELECT count(*) FROM catalog.event_outbox) + (SELECT count(*) FROM identity.event_outbox)")"
    [ "$PENDENTES" = "0" ] && break
    sleep 2
done
confere "linhas pendentes nos 3 outbox (ate 90 s)" 0 "$PENDENTES"
lag() { dc exec -T redpanda rpk group describe "$1" 2>/dev/null | tr -d '\r' | awk '$1 == "TOTAL-LAG" {print $2}'; }
for grupo in reservation reservation-catalog notification; do
    LAG=""
    for i in $(seq 1 45); do
        LAG="$(lag "$grupo")"
        [ "$LAG" = "0" ] && break
        sleep 2
    done
    confere "TOTAL-LAG do group $grupo (ate 90 s)" 0 "$LAG"
done
[ "$FALHAS" -eq 0 ] || abortar "o backbone nao escoou: o replay compararia com projecao defasada"

echo "==> 5b. 2o up: o group notification JA existe, entao o one-shot notification-seek NAO move offset"
SEEK2="$(dc run --rm --no-deps notification-seek 2>&1 | tr -d '\r')"
confere "notification-seek com o group existente" sim "$(grep -q 'offsets mantidos' <<<"$SEEK2" && echo sim || echo nao)"

echo "==> 6. replay (event sourcing): catalog.event e reservation.wallet reconstruidos do log == projecoes vivas"
# Container Maven efemero na rede DO PROJECT (db = o db temporario), SEM relay/consumer/expiracao e sem truncar nada: as classes
# *ReplayE2eTest so leem o log e criam/derrubam o schema-sombra; ficam puladas no run-tests.sh (precisam de E2E_REPLAY=1).
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        HOST_SRC="$(cd "$PROJETO_DIR/Backend" && pwd -W)"
        HOST_ENV_FILE="$(cd "$PROJETO_DIR" && pwd -W)/.env"
        CONTAINER_HOME=/root
        PLATFORM_ARGS=(-v gerenciador-eventos-m2:/root/.m2)
        ;;
    *)
        HOST_SRC="$PROJETO_DIR/Backend"
        HOST_ENV_FILE="$PROJETO_DIR/.env"
        CONTAINER_HOME=/tmp/mvnhome
        PLATFORM_ARGS=(--user "$(id -u):$(id -g)" -e HOME=/tmp/mvnhome -v gerenciador-eventos-m2:/tmp/mvnhome/.m2)
        ;;
esac
dexec run --rm --network "$REDE" --env-file "$HOST_ENV_FILE" "${PLATFORM_ARGS[@]}" \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/meu_banco \
    -e SPRING_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092 \
    -e INTERNAL_API_TOKEN=token-de-teste-nao-e-segredo \
    -e EVENTS_RELAY_ENABLED=false -e EVENTS_CONSUMER_ENABLED=false \
    -e RESERVATION_CATALOG_RECONCILIATION_ENABLED=false -e RESERVATION_EXPIRY_SCAN_MS=3600000 \
    -e E2E_REPLAY=1 -e "MVN_REPO=$CONTAINER_HOME/.m2/repository" \
    -v "$HOST_SRC":/workspace-src:ro \
    "$IMAGEM_MVN" \
    bash -c '
        mkdir -p /tmp/build "$MVN_REPO"
        cp -r /workspace-src/* /tmp/build/ 2>/dev/null || true
        cd /tmp/build
        mvn -B -Dmaven.repo.local="$MVN_REPO" -pl catalog,reservation -am test \
            -Dtest="CatalogReplayE2eTest,ReservationReplayE2eTest" -Dsurefire.failIfNoSpecifiedTests=false
    ' 2>&1 | tr -d '\r' >"$TMP/replay.log"
REPLAY_CODE="${PIPESTATUS[0]}"
grep -E "Tests run:.*(CatalogReplayE2eTest|ReservationReplayE2eTest)|BUILD|ERROR\]" "$TMP/replay.log" | head -20 | sed 's/^/     /'
confere "mvn test das classes de replay (codigo de saida)" 0 "$REPLAY_CODE"
for classe in CatalogReplayE2eTest ReservationReplayE2eTest; do
    confere "$classe rodou 1 teste, 0 falhas, 0 pulados (replay sem divergencia)" sim \
        "$(grep -q "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0.* -- in .*$classe" "$TMP/replay.log" && echo sim || echo nao)"
done

echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then echo "   RESULTADO: E2E OK (stack inteira, fluxo, e-mail no captador, replay sem divergencia)"; else echo "   RESULTADO: $FALHAS ASSERCAO(OES) FALHARAM"; fi
echo "=================================================="
[ "$FALHAS" -eq 0 ]
