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
#  Modo do kit de carga (Projeto/carga, k6 no gerador; ver Projeto/carga/lib-seed.ps1),
#  todas opcionais -- sem elas o comportamento é o do harness (1 evento, tokens):
#    EVENTS             nº de eventos criados, cada um com CAPACITY vagas   (default 1)
#    EVENT_NAME_PREFIX  se definido, os eventos se chamam "<prefixo>-E<k>"  (default:
#                       "Evento Carga <SUFIXO>", usado pelo run.sh na limpeza)
#    SEM_TOKENS=1       não loga os attendees nem escreve TOKENS_FILE (o k6 faz o login)
#    USERS_FILE         se definido, grava [{"email","password"}] dos attendees
#    EVENT_IDS_FILE     se definido, grava [<event_id>, ...] de todos os eventos
#  Saída extra no stdout quando definidos: USERS_FILE=<caminho>, EVENT_IDS_FILE=<caminho>.
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
EVENTS="${EVENTS:-1}"
EVENT_NAME_PREFIX="${EVENT_NAME_PREFIX:-}"
SEM_TOKENS="${SEM_TOKENS:-0}"
USERS_FILE="${USERS_FILE:-}"
EVENT_IDS_FILE="${EVENT_IDS_FILE:-}"

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

case "$N$EVENTS" in *[!0-9]*|'') erro "N e EVENTS precisam ser inteiros" ;; esac
[ "$N" -ge 1 ] && [ "$EVENTS" -ge 1 ] || erro "N e EVENTS precisam ser >= 1"
case "$EVENT_NAME_PREFIX" in *[!A-Za-z0-9-]*) erro "EVENT_NAME_PREFIX só aceita letras, números e hífen" ;; esac

echo "==> setup.sh: BASE_URL=$BASE_URL N=$N EVENTS=$EVENTS CAPACITY=$CAPACITY SUFIXO=$SUFIXO" >&2

# ---------------------------------------------------------------------------
#  1. Semear host + N attendees (POST /bff/seed, só "users")
# ---------------------------------------------------------------------------
EMAIL_HOST="carga-host-${SUFIXO}@example.com"
FONE_HOST="119$(printf '%s' "$SUFIXO" | tr -cd '0-9' | tail -c 8)"

USERS_JSON="{\"name\":\"Host Carga ${SUFIXO}\",\"email\":\"${EMAIL_HOST}\",\"password\":\"${SENHA_PADRAO}\",\"fone\":\"${FONE_HOST}\"}"
declare -a EMAILS_ATTENDEE=()
for i in $(seq 1 "$N"); do
    email="carga-att${i}-${SUFIXO}@example.com"
    fone="11$(printf '%03d' "$i")$(printf '%s' "$SUFIXO" | tr -cd '0-9' | tail -c 6)"
    USERS_JSON="${USERS_JSON},{\"name\":\"Attendee ${i} Carga ${SUFIXO}\",\"email\":\"${email}\",\"password\":\"${SENHA_PADRAO}\",\"fone\":\"${fone}\"}"
    EMAILS_ATTENDEE+=("$email")
done

# Corpo via arquivo: com centenas de usuários o JSON passa do limite de linha de comando do
# Windows (~32 KB) se for em -d "...".
printf '{"users":[%s]}' "$USERS_JSON" > "$TMP/seed_users_body.json"
code=$(curl -s -o "$TMP/seed_users.json" -w '%{http_code}' \
    -X POST "$BASE_URL/bff/seed" \
    -H 'Content-Type: application/json' \
    --data-binary "@$TMP/seed_users_body.json")
[ "$code" = "200" ] || erro "POST /bff/seed (users) devolveu HTTP $code (esperado 200). Corpo: $(cat "$TMP/seed_users.json")"
criados=$(extrair_num "usersCreated" "$TMP/seed_users.json")
[ "${criados:-0}" -eq $((N + 1)) ] || echo "   AVISO: usersCreated=${criados:-?} (esperado $((N + 1))); veja warnings em: $(cat "$TMP/seed_users.json")" >&2
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

if [ "$SEM_TOKENS" != 1 ]; then
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
# $TOKENS_FILE guarda JWTs válidos (mesmo que de usuários de teste descartáveis)
# -- restringe a leitura ao dono antes de qualquer outra coisa tocar o arquivo,
# pra não ficar 0644 em runners compartilhados (achado do Gemini na review da
# issue #11/PR #82). chmod pode não ter efeito em todo filesystem do Windows,
# mas é inofensivo lá e é o que protege de verdade em Linux/macOS/CI.
chmod 600 "$TOKENS_FILE" 2>/dev/null || true
echo "   $N attendees logados, tokens em $TOKENS_FILE" >&2
fi

if [ -n "$USERS_FILE" ]; then
    {
        printf '['
        for i in $(seq 1 "$N"); do
            [ "$i" -gt 1 ] && printf ','
            printf '{"email":"%s","password":"%s"}' "${EMAILS_ATTENDEE[$((i - 1))]}" "$SENHA_PADRAO"
        done
        printf ']\n'
    } > "$USERS_FILE"
    chmod 600 "$USERS_FILE" 2>/dev/null || true   # credenciais de teste: só o dono lê
fi

# ---------------------------------------------------------------------------
#  3. Semear os eventos (creator_id = host) com capacity fixa
# ---------------------------------------------------------------------------
# Sem EVENT_NAME_PREFIX e com 1 evento: nome legado "Evento Carga <SUFIXO>" (o run.sh limpa
# por ele). Caso contrário: "<prefixo>-E<k>", k = 1..EVENTS.
declare -a EVENT_NAMES=()
if [ -z "$EVENT_NAME_PREFIX" ] && [ "$EVENTS" = 1 ]; then
    EVENT_NAMES+=("Evento Carga ${SUFIXO}")
else
    for k in $(seq 1 "$EVENTS"); do
        EVENT_NAMES+=("${EVENT_NAME_PREFIX:-Evento Carga ${SUFIXO}}-E${k}")
    done
fi
EVENT_DATE="$(date -u -d '+30 days' '+%Y-%m-%dT%H:%M:%S' 2>/dev/null \
    || date -u -v+30d '+%Y-%m-%dT%H:%M:%S')"

EVENTS_JSON=""
for nome in "${EVENT_NAMES[@]}"; do
    [ -n "$EVENTS_JSON" ] && EVENTS_JSON+=","
    EVENTS_JSON+="{\"creator_id\":${HOST_ID},\"event_name\":\"${nome}\",\"is_EAD\":true,\"event_date\":\"${EVENT_DATE}\",\"description\":\"harness de carga - issue #11\",\"lot_quantity\":${CAPACITY},\"quantity\":${CAPACITY}}"
done

printf '{"events":[%s]}' "$EVENTS_JSON" > "$TMP/seed_events_body.json"
code=$(curl -s -o "$TMP/seed_event.json" -w '%{http_code}' \
    -X POST "$BASE_URL/bff/seed" \
    -H 'Content-Type: application/json' \
    --data-binary "@$TMP/seed_events_body.json")
[ "$code" = "200" ] || erro "POST /bff/seed (events) devolveu HTTP $code (esperado 200). Corpo: $(cat "$TMP/seed_event.json")"
echo "   seed de ${#EVENT_NAMES[@]} evento(s) ok (capacity=$CAPACITY)" >&2

# ---------------------------------------------------------------------------
#  4. Ler os event_id de volta (GET /bff/events?creator_id=<host>, rota pública)
# ---------------------------------------------------------------------------
code=$(curl -s -o "$TMP/events_host.json" -w '%{http_code}' \
    "$BASE_URL/bff/events?creator_id=${HOST_ID}")
[ "$code" = "200" ] || erro "GET /bff/events?creator_id=$HOST_ID devolveu HTTP $code (esperado 200)"

# A resposta é um array JSON compacto (Jackson sem indentação, ver
# application.properties -- não há spring.jackson.serialization.indent-output).
# Nenhum campo de Event é objeto aninhado (presenters é array de strings, não
# de objetos), então cada evento do array não contém "{"/"}" internos -- dá
# pra isolar o objeto do evento certo com [^{}]* entre chaves, sem jq.
declare -a EVENT_IDS=()
for nome in "${EVENT_NAMES[@]}"; do
    OBJ=$(grep -oP "\{[^{}]*\"event_name\":\"${nome}\"[^{}]*\}" "$TMP/events_host.json" | head -1)
    [ -n "$OBJ" ] || erro "não achei um evento com event_name='${nome}' em GET /bff/events?creator_id=$HOST_ID"
    id=$(printf '%s' "$OBJ" | grep -oP '"event_id"\s*:\s*\K[0-9]+' | head -1)
    [ -n "$id" ] || erro "não consegui extrair event_id do evento '${nome}'"
    EVENT_IDS+=("$id")
done
EVENT_ID="${EVENT_IDS[0]}"

if [ -n "$EVENT_IDS_FILE" ]; then
    (IFS=,; printf '[%s]\n' "${EVENT_IDS[*]}") > "$EVENT_IDS_FILE"
fi

echo "   evento(s) encontrado(s): ${#EVENT_IDS[@]} (primeiro event_id=$EVENT_ID)" >&2
echo "" >&2

echo "EVENT_ID=$EVENT_ID"
echo "SUFIXO=$SUFIXO"
echo "HOST_ID=$HOST_ID"
echo "TOKENS_FILE=$TOKENS_FILE"
[ -n "$USERS_FILE" ] && echo "USERS_FILE=$USERS_FILE"
[ -n "$EVENT_IDS_FILE" ] && echo "EVENT_IDS_FILE=$EVENT_IDS_FILE"
true
