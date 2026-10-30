#!/bin/bash

# =============================================================================
#  smoke-catalog-servico-separado.sh  -  issue #35 (Fase 7)
# =============================================================================
#
#  Smoke manual do servico `catalog` SEPARADO (container gerenciador-catalog), pela stack real: gateway (/bff/**),
#  salto HTTP gateway -> catalog -> reservation, pooler e broker. Prova que
#    a) um host autenticado cria o evento pelo BFF (POST /bff/events com JWT) e o GET devolve o creator_name
#       composto no gateway;
#    b) a API interna do catalog (/internal/events/**) responde 401 sem X-Internal-Token (ou com um errado) e 200 com o
#       token certo, e a porta 8083 NAO esta publicada no host;
#    c) com o container gerenciador-catalog PARADO: GET /bff/events/{id} -> 503 (nao 404) e a reserva falha com 503,
#       nunca com "Evento nao encontrado"; subindo de novo, o GET volta a 200 e a reserva a 201;
#    d) o banco esta no schema do servico: public.event NAO existe e catalog.event tem o evento (e o eventcap nasceu
#       no reservation);
#    e) o EventCreated foi appendado no event store DO SERVICO (catalog.event_store, nada no public.event_store) e o
#       outbox do catalog escoa (relay ligado).
#  O script sai com codigo != 0 se qualquer assercao falhar. Roda 2x seguidas sem colidir (sufixo por execucao) e
#  sempre religa o catalog na saida (trap), mesmo se abortar no meio.
#
#  Pre-requisito: a stack no ar COM o seed ligado (POST /bff/seed so existe com SEED_ENABLED=true, issue #35; e o
#  unico jeito de criar usuario com senha sem o fluxo de e-mail). O override de carga liga:
#    docker compose -f Projeto/docker-compose.yml -f Projeto/carga/docker-compose.carga.yml up -d --build
#  Host: nenhuma rota promove usuario a host; o script promove o seu usuario de teste por SQL (UPDATE identity.users SET host).
#
#  Uso:  docs/testes-manuais/smoke-catalog-servico-separado.sh
#  Variaveis (opcionais): BASE_URL (http://localhost:8081), CONTAINER_DB (gerenciador-db),
#    CONTAINER_BACKEND (gerenciador-backend), CONTAINER_CATALOG (gerenciador-catalog), CATALOG_HOST (catalog, nome na
#    rede gerenciador-net), CATALOG_PORTA (8083), MANTER_DADOS (1 = nao limpa).
#  Nenhum segredo aqui: o INTERNAL_API_TOKEN e lido DENTRO do container do backend (o valor nunca passa pelo host).
#  Compatibilidade: Linux/macOS e Git Bash do Windows (Docker Desktop).
# =============================================================================

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
CONTAINER_BACKEND="${CONTAINER_BACKEND:-gerenciador-backend}"
CONTAINER_CATALOG="${CONTAINER_CATALOG:-gerenciador-catalog}"
CATALOG_HOST="${CATALOG_HOST:-catalog}"
CATALOG_PORTA="${CATALOG_PORTA:-8083}"
MANTER_DADOS="${MANTER_DADOS:-0}"
SUFIXO="$(date +%s)-$RANDOM"
NOME_EVENTO="Smoke35 $SUFIXO"
NOME_HOST="Smoke35 Host $SUFIXO"
TMP="$(mktemp -d)"
FALHAS=0
EVENT_ID=""
CATALOGO_PARADO=0

# Git Bash: o docker e nativo do Windows e a conversao de path do MSYS reescreveria "/internal/..." e "/dev/tcp/...".
# Desligada SO nos `docker` (o curl precisa dela para gravar em $TMP).
dexec() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@" ;;
        *) docker "$@" ;;
    esac
}

psql_db() {
    # a role do app e a do container (POSTGRES_USER do Projeto/.env); o -h localhost evita o peer auth do socket
    dexec exec -i "$CONTAINER_DB" sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$POSTGRES_DB" -At' <<<"$1"
}

ok()   { echo "   [ok]    $1"; }
erro() { echo "   [ERRO]  $1"; FALHAS=$((FALHAS + 1)); }
confere() { # descricao esperado obtido
    if [ "$2" = "$3" ]; then ok "$1: $3"; else erro "$1: esperado $2, obtido '$3'"; fi
}
abortar() { echo "   [ABORTA] $1"; exit 1; }
extrair_num() { grep -oP "\"$1\"\\s*:\\s*\\K[0-9]+" "$2" | head -1; }

# Status HTTP de um GET na API interna do catalog, feito DE DENTRO do container do backend (a porta nao esta no host
# e a imagem nao tem curl: bash /dev/tcp, como o healthcheck). $1 = caminho; $2 = sem | errado | certo (token).
# O token certo e expandido no container: as aspas simples impedem que o host o veja.
REQ_INTERNA='exec 3<>"/dev/tcp/$1/$2" || exit 1
printf "GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n" "$3" "$1" >&3
case "$4" in
    certo)  printf "X-Internal-Token: %s\r\n" "$INTERNAL_API_TOKEN" >&3 ;;
    errado) printf "X-Internal-Token: token-errado\r\n" >&3 ;;
esac
printf "\r\n" >&3
IFS=" " read -r _ status _ <&3
echo "$status"'
status_interno() {
    dexec exec "$CONTAINER_BACKEND" timeout 10 bash -c "$REQ_INTERNA" bash "$CATALOG_HOST" "$CATALOG_PORTA" "$1" "$2" 2>/dev/null \
        | tr -d '[:space:]'
}

limpar() {
    if [ "$CATALOGO_PARADO" = "1" ]; then
        echo "==> (saida) religando $CONTAINER_CATALOG"
        dexec start "$CONTAINER_CATALOG" >/dev/null 2>&1 || echo "   [ERRO]  nao consegui religar $CONTAINER_CATALOG: docker start $CONTAINER_CATALOG"
    fi
    if [ "$MANTER_DADOS" != "1" ]; then
        # sem FK entre schemas: a ordem nao importa. O stream do evento fica no catalog.event_store (log imutavel; a
        # identity de event_id nunca recicla o id, entao o stream orfao e inofensivo), como no smoke do reservation.
        psql_db "DELETE FROM reservation.walletevent WHERE event_id IN (SELECT event_id FROM catalog.event WHERE event_name = '$NOME_EVENTO');
                 DELETE FROM stock.eventcap WHERE event_id IN (SELECT event_id FROM catalog.event WHERE event_name = '$NOME_EVENTO');
                 DELETE FROM catalog.event WHERE event_name = '$NOME_EVENTO';
                 DELETE FROM identity.users WHERE email LIKE 'smoke35-$SUFIXO-%@example.com';" >/dev/null 2>&1 || true
    fi
    rm -rf "$TMP"
}
trap limpar EXIT

echo "=================================================="
echo "   SMOKE #35: catalog como servico separado"
echo "   BASE_URL=$BASE_URL  CATALOG=$CONTAINER_CATALOG ($CATALOG_HOST:$CATALOG_PORTA)  SUFIXO=$SUFIXO"
echo "=================================================="

echo "==> 0. stack no ar?"
for i in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health")" = "200" ] && break
    sleep 2
done
confere "GET /actuator/health (backend)" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health")"
confere "container $CONTAINER_CATALOG saudavel" healthy "$(dexec inspect -f '{{.State.Health.Status}}' "$CONTAINER_CATALOG" 2>/dev/null)"

echo "==> 1. host e participante (seed), promocao a host por SQL e login"
code="$(curl -s -o "$TMP/seed.json" -w '%{http_code}' -X POST "$BASE_URL/bff/seed" -H 'Content-Type: application/json' \
    -d "{\"users\":[{\"name\":\"$NOME_HOST\",\"email\":\"smoke35-$SUFIXO-host@example.com\",\"password\":\"senha123\",\"fone\":\"s35h-$SUFIXO\",\"birthDate\":\"1990-01-15\"},{\"name\":\"Smoke35 Part $SUFIXO\",\"email\":\"smoke35-$SUFIXO-part@example.com\",\"password\":\"senha123\",\"fone\":\"s35p-$SUFIXO\",\"birthDate\":\"1990-01-15\"}]}")"
if [ "$code" = "404" ]; then
    abortar "POST /bff/seed = 404: o seed esta desligado (SEED_ENABLED). Suba com o override de carga:
            docker compose -f Projeto/docker-compose.yml -f Projeto/carga/docker-compose.carga.yml up -d"
fi
confere "POST /bff/seed (2 usuarios)" 200 "$code"
psql_db "UPDATE identity.users SET host = true WHERE email = 'smoke35-$SUFIXO-host@example.com'" >/dev/null
for papel in host part; do
    curl -s -o "$TMP/login-$papel.json" -X POST "$BASE_URL/bff/users/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"smoke35-$SUFIXO-$papel@example.com\",\"password\":\"senha123\"}"
    grep -o '"token":"[^"]*"' "$TMP/login-$papel.json" | cut -d'"' -f4 >"$TMP/token-$papel"
done
PART_ID="$(extrair_num user_id "$TMP/login-part.json")"
confere "login do host e do participante (tokens)" "sim" "$([ -s "$TMP/token-host" ] && [ -s "$TMP/token-part" ] && [ -n "$PART_ID" ] && echo sim || echo nao)"
[ "$FALHAS" -eq 0 ] || abortar "sem usuarios/tokens nao ha o que provar"

echo "==> a) host cria o evento pelo BFF autenticado (gateway -> catalog -> reservation)"
code="$(curl -s -o "$TMP/evento.json" -w '%{http_code}' -X POST "$BASE_URL/bff/events" \
    -H "Authorization: Bearer $(cat "$TMP/token-host")" -H 'Content-Type: application/json' \
    -d "{\"event_name\":\"$NOME_EVENTO\",\"is_EAD\":true,\"event_date\":\"2035-12-01T14:00:00\",\"description\":\"smoke #35\",\"lot_quantity\":3,\"presenters\":[]}")"
confere "POST /bff/events (host autenticado)" 201 "$code"
EVENT_ID="$(extrair_num event_id "$TMP/evento.json")"
[ -n "$EVENT_ID" ] || abortar "nao consegui extrair o event_id da resposta do POST"
ok "event_id=$EVENT_ID"
code="$(curl -s -o "$TMP/get.json" -w '%{http_code}' "$BASE_URL/bff/events/$EVENT_ID")"
confere "GET /bff/events/$EVENT_ID" 200 "$code"
confere "creator_name composto no gateway" "sim" "$(grep -q "\"creator_name\":\"$NOME_HOST\"" "$TMP/get.json" && echo sim || echo nao)"

echo "==> b) API interna do catalog: so com X-Internal-Token, sem porta no host"
confere "GET /internal/events/$EVENT_ID/exists SEM token" 401 "$(status_interno "/internal/events/$EVENT_ID/exists" sem)"
confere "GET /internal/events/$EVENT_ID/exists com token ERRADO" 401 "$(status_interno "/internal/events/$EVENT_ID/exists" errado)"
confere "GET /internal/events/$EVENT_ID/exists com o token certo" 200 "$(status_interno "/internal/events/$EVENT_ID/exists" certo)"
confere "portas publicadas no host por $CONTAINER_CATALOG (docker port)" "" "$(dexec port "$CONTAINER_CATALOG" 2>/dev/null)"
confere "http://localhost:$CATALOG_PORTA a partir do host (000 = sem conexao)" 000 \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$CATALOG_PORTA/internal/events/$EVENT_ID/exists")"

echo "==> d) o banco esta no schema do servico"
confere "to_regclass('public.event') IS NULL" t "$(psql_db "SELECT to_regclass('public.event') IS NULL" | tr -d '[:space:]')"
confere "evento em catalog.event" 1 "$(psql_db "SELECT count(*) FROM catalog.event WHERE event_id = $EVENT_ID" | tr -d '[:space:]')"
confere "stock.eventcap.capacity do evento (criado pela chamada catalog -> reservation)" 3 \
    "$(psql_db "SELECT capacity FROM stock.eventcap WHERE event_id = $EVENT_ID" | tr -d '[:space:]')"

echo "==> e) event store DO SERVICO e outbox escoando (relay)"
confere "EventCreated em catalog.event_store" 1 \
    "$(psql_db "SELECT count(*) FROM catalog.event_store WHERE stream_type = 'catalog.event' AND stream_id = '$EVENT_ID' AND event_type = 'EventCreated'" | tr -d '[:space:]')"
confere "o public.event_store NAO recebeu o stream catalog.event deste evento" 0 \
    "$(psql_db "SELECT count(*) FROM public.event_store WHERE stream_type = 'catalog.event' AND stream_id = '$EVENT_ID'" | tr -d '[:space:]')"
confere "EVENTS_RELAY_ENABLED no $CONTAINER_CATALOG" true "$(dexec exec "$CONTAINER_CATALOG" printenv EVENTS_RELAY_ENABLED 2>/dev/null | tr -d '[:space:]')"
pendentes=""
for i in $(seq 1 30); do
    pendentes="$(psql_db "SELECT count(*) FROM catalog.event_outbox o JOIN catalog.event_store s USING (global_position)
                         WHERE s.stream_type = 'catalog.event' AND s.stream_id = '$EVENT_ID'" | tr -d '[:space:]')"
    [ "$pendentes" = "0" ] && break
    sleep 2
done
confere "linhas do evento ainda no catalog.event_outbox (ate 60 s)" 0 "$pendentes"

echo "==> c) $CONTAINER_CATALOG PARADO: 503, nunca 'evento nao existe'"
# Nenhuma reserva foi feita neste evento ate aqui: o cache positivo de exists do gateway (30 s) esta vazio para ele.
CATALOGO_PARADO=1
dexec stop "$CONTAINER_CATALOG" >/dev/null
code="$(curl -s -o "$TMP/get-parado.json" -w '%{http_code}' --max-time 30 "$BASE_URL/bff/events/$EVENT_ID")"
confere "GET /bff/events/$EVENT_ID com o catalog parado" 503 "$code"
code="$(curl -s -o "$TMP/reserva-parado.txt" -w '%{http_code}' --max-time 30 -X POST "$BASE_URL/bff/event-wallets" \
    -H "Authorization: Bearer $(cat "$TMP/token-part")" -H 'Content-Type: application/json' \
    -d "{\"userId\":$PART_ID,\"eventId\":$EVENT_ID}")"
confere "POST /bff/event-wallets com o catalog parado" 503 "$code"
confere "corpo da reserva sem 'nao encontrado' (exists falhou, nao disse false)" "sim" \
    "$(grep -qi "encontrado" "$TMP/reserva-parado.txt" && echo nao || echo sim)"

echo "==> c) $CONTAINER_CATALOG de volta: recuperacao"
dexec start "$CONTAINER_CATALOG" >/dev/null
for i in $(seq 1 60); do
    [ "$(dexec inspect -f '{{.State.Health.Status}}' "$CONTAINER_CATALOG" 2>/dev/null)" = "healthy" ] && break
    sleep 2
done
confere "container $CONTAINER_CATALOG saudavel de novo" healthy "$(dexec inspect -f '{{.State.Health.Status}}' "$CONTAINER_CATALOG" 2>/dev/null)"
CATALOGO_PARADO=0
# sem retry no cliente: uma conexao velha do pool pode dar um 503 isolado logo apos a volta
for i in $(seq 1 15); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$BASE_URL/bff/events/$EVENT_ID")"
    [ "$code" = "200" ] && break
    sleep 2
done
confere "GET /bff/events/$EVENT_ID depois da volta" 200 "$code"
code="$(curl -s -o "$TMP/reserva-volta.txt" -w '%{http_code}' --max-time 30 -X POST "$BASE_URL/bff/event-wallets" \
    -H "Authorization: Bearer $(cat "$TMP/token-part")" -H 'Content-Type: application/json' \
    -d "{\"userId\":$PART_ID,\"eventId\":$EVENT_ID}")"
confere "POST /bff/event-wallets depois da volta (reserva pending)" 201 "$code"

echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then echo "   RESULTADO: SMOKE OK (catalog separado)"; else echo "   RESULTADO: $FALHAS ASSERCAO(OES) FALHARAM"; fi
echo "=================================================="
exit "$FALHAS"
