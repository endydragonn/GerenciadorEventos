#!/bin/bash

# =============================================================================
#  setup.sh · harness de carga (issue #11)
# =============================================================================
#
#  Cria a fixture de uma rodada do harness: 1 host + N attendees (via
#  POST /bff/seed, dados de teste) + 1 evento com capacity fixa (também via
#  seed, que chama EventService.createEvent direto -- sem o gate de host de
#  POST /bff/events, ver Projeto/Backend/CLAUDE.md "Rotas e segurança").
#
#  POST /bff/seed não devolve os IDs criados (só contagens), então:
#    1. semeia host + attendees;
#    2. loga cada um em /bff/users/login (resposta traz user_id + token);
#    3. semeia o evento usando o user_id do host como creator_id;
#    4. lê o event_id de volta via GET /bff/events?creator_id=<host> (rota
#       pública) -- não precisa de acesso ao banco aqui.
#
#  Saída:
#    - $TOKENS_FILE: JSON com os tokens dos attendees, formato consumido pelo
#      scenario.js:  [{"userId":1,"token":"..."}, ...]
#    - stdout, últimas linhas (formato "CHAVE=valor", uma por linha) para o
#      chamador (run.sh) capturar com grep/cut:
#        EVENT_ID=<id>
#        SUFIXO=<sufixo>
#        HOST_ID=<id>
#        TOKENS_FILE=<caminho>
#
#  Variáveis de ambiente (todas opcionais):
#    BASE_URL      URL do backend                    (default http://localhost:8081)
#    N             nº de attendees / VUs da rodada    (default 10)
#    CAPACITY      capacity do evento (eventcap)      (default 4)
#    SUFIXO        sufixo único da rodada              (default timestamp+pid)
#    TOKENS_FILE   caminho de saída do JSON de tokens (default mktemp)
#
#  Mesmo idioma de docs/testes-manuais/overselling-sob-concorrencia.sh:
#  Git Bash + Linux/macOS, sem jq (extração via grep -oP).
# =============================================================================

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
N="${N:-10}"
CAPACITY="${CAPACITY:-4}"
SUFIXO="${SUFIXO:-$(date +%s)-$$}"
TOKENS_FILE="${TOKENS_FILE:-$(mktemp)}"

SENHA_PADRAO="CargaTeste#${SUFIXO}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

erro() {
    echo "   ERRO: $1" >&2
    exit 1
}

# extrai um valor string de um JSON simples de uma linha só (sem jq).
extrair_str() {
    grep -oP "\"$1\"\\s*:\\s*\"\\K[^\"]*" "$2" | head -1
}

extrair_num() {
    grep -oP "\"$1\"\\s*:\\s*\\K[0-9]+" "$2" | head -1
}

echo "==> setup.sh: BASE_URL=$BASE_URL N=$N CAPACITY=$CAPACITY SUFIXO=$SUFIXO" >&2

# ---------------------------------------------------------------------------
#  1. Semear host + N attendees (POST /bff/seed, só "users")
# ---------------------------------------------------------------------------
EMAIL_HOST="carga-host-${SUFIXO}@example.com"
FONE_HOST="119$(printf '%s' "$SUFIXO" | tr -cd '0-9' | tail -c 8)"

USERS_JSON="{\"name\":\"Host Carga ${SUFIXO}\",\"email\":\"${EMAIL_HOST}\",\"password\":\"${SENHA_PADRAO}\",\"fone\":\"${FONE_HOST}\"}"
declare -a EMAILS_ATTENDEE=()
for i in $(seq 1 "$N"); do
    email="carga-att${i}-${SUFIXO}@example.com"
    fone="118${i}$(printf '%s' "$SUFIXO" | tr -cd '0-9' | tail -c 7)"
    USERS_JSON="${USERS_JSON},{\"name\":\"Attendee ${i} Carga ${SUFIXO}\",\"email\":\"${email}\",\"password\":\"${SENHA_PADRAO}\",\"fone\":\"${fone}\"}"
    EMAILS_ATTENDEE+=("$email")
done

code=$(curl -s -o "$TMP/seed_users.json" -w '%{http_code}' \
    -X POST "$BASE_URL/bff/seed" \
    -H 'Content-Type: application/json' \
    -d "{\"users\":[${USERS_JSON}]}")
[ "$code" = "200" ] || erro "POST /bff/seed (users) devolveu HTTP $code (esperado 200). Corpo: $(cat "$TMP/seed_users.json")"
echo "   seed de usuários ok (host + $N attendees)" >&2

# ---------------------------------------------------------------------------
#  2. Login de cada um (host + attendees) -> user_id + token
# ---------------------------------------------------------------------------
login() {   # $1 = email ; $2 = arquivo de saída
    local code
    code=$(curl -s -o "$2" -w '%{http_code}' \
        -X POST "$BASE_URL/bff/users/login" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$1\",\"password\":\"${SENHA_PADRAO}\"}")
    [ "$code" = "200" ] || erro "POST /bff/users/login ($1) devolveu HTTP $code (esperado 200)"
}

login "$EMAIL_HOST" "$TMP/login_host.json"
HOST_ID=$(extrair_num "user_id" "$TMP/login_host.json")
[ -n "$HOST_ID" ] || erro "não consegui extrair user_id do host"
echo "   host logado: user_id=$HOST_ID" >&2

TOKENS_JSON="["
first=1
for i in $(seq 1 "$N"); do
    email="${EMAILS_ATTENDEE[$((i - 1))]}"
    login "$email" "$TMP/login_att${i}.json"
    uid=$(extrair_num "user_id" "$TMP/login_att${i}.json")
    tok=$(extrair_str "token" "$TMP/login_att${i}.json")
    [ -n "$uid" ] && [ -n "$tok" ] || erro "não consegui extrair user_id/token do attendee $i ($email)"
    if [ "$first" = 1 ]; then first=0; else TOKENS_JSON+=","; fi
    TOKENS_JSON+="{\"userId\":${uid},\"token\":\"${tok}\"}"
done
TOKENS_JSON+="]"
printf '%s\n' "$TOKENS_JSON" > "$TOKENS_FILE"
echo "   $N attendees logados, tokens em $TOKENS_FILE" >&2

# ---------------------------------------------------------------------------
#  3. Semear o evento (creator_id = host) com capacity fixa
# ---------------------------------------------------------------------------
EVENT_NAME="Evento Carga ${SUFIXO}"
EVENT_DATE="$(date -u -d '+30 days' '+%Y-%m-%dT%H:%M:%S' 2>/dev/null \
    || date -u -v+30d '+%Y-%m-%dT%H:%M:%S')"
EVENT_JSON="{\"creator_id\":${HOST_ID},\"event_name\":\"${EVENT_NAME}\",\"is_EAD\":true,\"event_date\":\"${EVENT_DATE}\",\"description\":\"harness de carga - issue #11\",\"lot_quantity\":${CAPACITY},\"quantity\":${CAPACITY}}"

code=$(curl -s -o "$TMP/seed_event.json" -w '%{http_code}' \
    -X POST "$BASE_URL/bff/seed" \
    -H 'Content-Type: application/json' \
    -d "{\"events\":[${EVENT_JSON}]}")
[ "$code" = "200" ] || erro "POST /bff/seed (events) devolveu HTTP $code (esperado 200). Corpo: $(cat "$TMP/seed_event.json")"
echo "   seed de evento ok (capacity=$CAPACITY)" >&2

# ---------------------------------------------------------------------------
#  4. Ler event_id de volta (GET /bff/events?creator_id=<host>, rota pública)
# ---------------------------------------------------------------------------
code=$(curl -s -o "$TMP/events_host.json" -w '%{http_code}' \
    "$BASE_URL/bff/events?creator_id=${HOST_ID}")
[ "$code" = "200" ] || erro "GET /bff/events?creator_id=$HOST_ID devolveu HTTP $code (esperado 200)"

# A resposta é um array JSON compacto (Jackson sem indentação, ver
# application.properties -- não há spring.jackson.serialization.indent-output).
# Nenhum campo de Event é objeto aninhado (presenters é array de strings, não
# de objetos), então cada evento do array não contém "{"/"}" internos -- dá
# pra isolar o objeto do evento certo com [^{}]* entre chaves, sem jq.
OBJ=$(grep -oP "\{[^{}]*\"event_name\":\"${EVENT_NAME}\"[^{}]*\}" "$TMP/events_host.json" | head -1)
[ -n "$OBJ" ] || erro "não achei um evento com event_name='${EVENT_NAME}' em GET /bff/events?creator_id=$HOST_ID"
EVENT_ID=$(printf '%s' "$OBJ" | grep -oP '"event_id"\s*:\s*\K[0-9]+' | head -1)
[ -n "$EVENT_ID" ] || erro "não consegui extrair event_id do objeto do evento"

echo "   evento encontrado: event_id=$EVENT_ID" >&2
echo "" >&2

echo "EVENT_ID=$EVENT_ID"
echo "SUFIXO=$SUFIXO"
echo "HOST_ID=$HOST_ID"
echo "TOKENS_FILE=$TOKENS_FILE"
