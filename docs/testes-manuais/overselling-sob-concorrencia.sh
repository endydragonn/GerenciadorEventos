#!/bin/bash

# =============================================================================
#  overselling-sob-concorrencia.sh  ·  issue #9 (Fase 1)
# =============================================================================
#
#  Roteiro de sanity manual: prova, no nivel HTTP e sob concorrencia real, que
#  o caminho de reserva EventCap/CAS (issues #5/#6) NAO permite overselling.
#
#  Cenario (criterio de aceite da issue #9):
#    capacity = 1  ->  duas reservas quase simultaneas de usuarios DISTINTOS
#    ->  exatamente 1 resposta 201 "pending"  e  exatamente 1 resposta 400
#        "Evento lotado"; no banco: eventcap.quant = 0 e exatamente 1 linha
#        walletevent com status = 'pending'.
#
#  O QUE ESTE SCRIPT E: o "teste". As assercoes de resultado esperado sao a
#  fonte de verdade; o script sai com codigo != 0 se qualquer uma falhar
#  (ou seja, se houver overselling ou a assinatura de falha aparecer).
#
#  NAO altera nenhum codigo de producao. So bate na API e le o banco.
#
#  A mesma garantia, no nivel de servico e em processo, ja e coberta
#  automaticamente por:
#    - EventWalletTest.testEventoLotado
#    - EventWalletTest.testRemoveLinkConcorrenteNaoCausaOverselling (CyclicBarrier)
#  (Projeto/Backend/monolito/src/test/java/com/gerenciador/eventos/EventWalletTest.java)
#
#  Pre-requisito: a stack no ar (db + backend).
#    docker compose -f Projeto/docker-compose.yml up -d --build db backend
#
#  Uso:
#    docs/testes-manuais/overselling-sob-concorrencia.sh
#
#  Variaveis de ambiente (todas opcionais):
#    BASE_URL      URL do backend            (default http://localhost:8081)
#    CONTAINER_DB  container do Postgres     (default gerenciador-db)
#    CAPACIDADE    capacidade do lote        (default 1)
#                  -> use CAPACIDADE=2 para REPRODUZIR a assinatura de falha:
#                     com fixture errada as duas reservas passam (2x 201,
#                     0x "Evento lotado", 2 linhas pending) e as assercoes
#                     abaixo falham de proposito -- foi assim que este roteiro
#                     foi validado (red -> green).
#    MANTER_DADOS  se "1", nao limpa os dados criados ao final.
#
#  Compatibilidade: Linux/macOS e Git Bash do Windows (Docker Desktop), no
#  mesmo espirito de Projeto/Backend/run-tests.sh e
#  Projeto/Backend/db/migrations/verify-20260901-migration.sh.
#  Nao ha (nem deve haver) variante .ps1.
# =============================================================================

# -e NAO: uma assercao que falha nao pode matar o script antes do resumo.
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
CAPACIDADE="${CAPACIDADE:-1}"
MANTER_DADOS="${MANTER_DADOS:-0}"

# Sufixo unico por execucao -> roteiro reproduzivel (roda 2x seguidas sem
# colidir): users.user_name / users.email / users.fone e event.event_name sao
# UNIQUE e o backend rejeita duplicados (validateDuplicates / validateDuplicatesOnCreate).
SUFIXO="$(date +%s)-$$"

TMP="$(mktemp -d)"
FALHAS=0
EVENT_ID=""
U1_ID=""
U2_ID=""

# Git Bash (Docker Desktop no Windows): o binario do Docker e nativo e nao
# entende "/tmp/...". A conversao de path do MSYS e desligada SO nos `docker`
# (ver dexec); NAO globalmente -- o curl aqui e nativo e PRECISA da conversao
# para escrever os arquivos de resposta em $TMP.
IS_WIN=0
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) IS_WIN=1 ;;
esac

# Executa `docker ...` com a conversao de path do MSYS desligada no Windows.
dexec() {
    if [ "$IS_WIN" = "1" ]; then
        MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@"
    else
        docker "$@"
    fi
}

echo ""
echo "=================================================="
echo "   NO-OVERSELLING SOB CONCORRENCIA  (issue #9)"
echo "=================================================="
echo "   BASE_URL .......: $BASE_URL"
echo "   CONTAINER_DB ...: $CONTAINER_DB"
echo "   CAPACIDADE .....: $CAPACIDADE"
echo "   SUFIXO .........: $SUFIXO"
echo "=================================================="

# ---------------------------------------------------------------------------
#  Helpers
# ---------------------------------------------------------------------------

# psql dentro do container: conexao local confiavel (sem senha). Usuario e nome
# do banco vem do ambiente do PROPRIO container ($POSTGRES_USER/$POSTGRES_DB) --
# nada de segredo hardcoded aqui. -t -A => valor puro, sem cabecalho/formatacao.
psql_db() {
    dexec exec -i "$CONTAINER_DB" sh -c \
        'psql -v ON_ERROR_STOP=1 -t -A -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' \
        sh "$1"
}

falhar() {
    echo "   [FALHA] $1"
    FALHAS=$((FALHAS + 1))
}

ok() {
    echo "   [ok]    $1"
}

# extrai um id inteiro de um JSON simples (sem jq -- Git Bash nao tem).
# Mesmo idioma de Projeto/Backend/run-tests.sh (grep -oP '..."\K[0-9]+').
extrair_id() {
    grep -oP "\"$1\"\\s*:\\s*\\K[0-9]+" "$2" | head -1
}

limpar() {
    if [ "$MANTER_DADOS" = "1" ]; then
        echo ""
        echo "==> MANTER_DADOS=1 -> dados NAO removidos (sufixo $SUFIXO)"
        rm -rf "$TMP"
        return
    fi
    echo ""
    echo "==> Limpeza (walletevent/eventcap sao do schema reservation, sem FK: nao ha ordem a respeitar entre schemas)"
    # 1. walletevent e eventcap do servico reservation; 2. event; 3. users (mywallet cai por CASCADE).
    psql_db "DELETE FROM reservation.walletevent WHERE event_id IN (SELECT event_id FROM catalog.event WHERE event_name LIKE 'Evento Overselling %${SUFIXO}');" >/dev/null 2>&1 || true
    psql_db "DELETE FROM stock.eventcap WHERE event_id IN (SELECT event_id FROM catalog.event WHERE event_name LIKE 'Evento Overselling %${SUFIXO}');" >/dev/null 2>&1 || true
    psql_db "DELETE FROM catalog.event WHERE event_name LIKE 'Evento Overselling %${SUFIXO}';" >/dev/null 2>&1 || true
    psql_db "DELETE FROM identity.users WHERE email LIKE 'oversell-%-${SUFIXO}@example.com';" >/dev/null 2>&1 || true
    echo "    dados do sufixo $SUFIXO removidos"
    rm -rf "$TMP"
}
trap limpar EXIT

# ---------------------------------------------------------------------------
#  0. Backend no ar?
# ---------------------------------------------------------------------------
echo ""
echo "==> 0. Aguardando o backend responder em $BASE_URL"
# GET /bff/events e publico (/bff/** permitAll) e responde 200. Nao use
# /actuator/health: esta stack devolve 403 nesse path.
# --retry-connrefused cobre a janela em que o container ja subiu mas o Tomcat
# ainda nao aceita conexao.
PROBE=$(curl -s -o /dev/null -w '%{http_code}' \
    --retry-connrefused --retry 30 --retry-delay 2 \
    "$BASE_URL/bff/events?creator_id=1")
if [ "$PROBE" != "200" ]; then
    echo "   ERRO: backend nao respondeu (HTTP $PROBE em /bff/events). Suba a stack:"
    echo "     docker compose -f Projeto/docker-compose.yml up -d --build db backend"
    exit 1
fi
ok "backend respondendo"

if ! dexec ps --format '{{.Names}}' | grep -qx "$CONTAINER_DB"; then
    echo "   ERRO: container $CONTAINER_DB nao esta no ar."
    exit 1
fi

# ---------------------------------------------------------------------------
#  1. Dois usuarios DISTINTOS, cada um com carteira
# ---------------------------------------------------------------------------
# addLink checa existsActive(userId, eventId) ANTES do CAS -> o MESMO usuario
# duas vezes daria "Vinculo ja existente", nao "Evento lotado", e nao provaria
# nada. Precisam ser dois usuarios diferentes.
#
# Corpo minimo aceito por UserService.createUser (validateRequiredFields):
# name, email, fone. Nada mais e obrigatorio.
echo ""
echo "==> 1. Criando 2 usuarios + garantindo carteira de cada"

criar_usuario() {   # $1 = rotulo (u1/u2)
    local rotulo="$1"
    local email="oversell-${rotulo}-${SUFIXO}@example.com"
    # fone tambem precisa ser unico (users.fone e UNIQUE; validateDuplicates
    # rejeita telefone repetido) -> deriva do sufixo, como o helper de teste.
    local fone
    fone="11${rotulo: -1}$(printf '%s' "$SUFIXO" | tr -cd '0-9' | tail -c 9)"
    local code
    code=$(curl -s -o "$TMP/${rotulo}.json" -w '%{http_code}' \
        -X POST "$BASE_URL/bff/users" \
        -H 'Content-Type: application/json' \
        -d "{\"name\":\"Overselling ${rotulo} ${SUFIXO}\",\"email\":\"${email}\",\"fone\":\"${fone}\"}")
    if [ "$code" != "201" ]; then
        # stderr: nao poluir o stdout capturado por $(...). NAO ecoar o corpo:
        # User serializa o campo password (sem @JsonIgnore).
        echo "   ERRO: POST /bff/users ($rotulo) devolveu HTTP $code (esperado 201)." >&2
        return 1
    fi
    extrair_id "user_id" "$TMP/${rotulo}.json"
}

U1_ID=$(criar_usuario "u1")
U2_ID=$(criar_usuario "u2")
case "${U1_ID}/${U2_ID}" in
    */|/*|*[!0-9/]*)
        echo "   ERRO: nao consegui criar/extrair os dois user_id (u1='$U1_ID' u2='$U2_ID')."
        exit 1
        ;;
esac
ok "usuarios criados: u1=$U1_ID  u2=$U2_ID"

# Garante a carteira. O trigger trigger_create_wallet ja cria a linha em
# mywallet no INSERT do usuario, entao esta chamada e redundante -- mas e
# idempotente (ensureExists) e o roteiro da issue pede explicitamente.
for uid in "$U1_ID" "$U2_ID"; do
    code=$(curl -s -o /dev/null -w '%{http_code}' \
        -X POST "$BASE_URL/bff/my-wallets/ensure/$uid")
    [ "$code" = "200" ] || falhar "POST /bff/my-wallets/ensure/$uid devolveu HTTP $code (esperado 200)"
done
ok "carteiras garantidas"

# ---------------------------------------------------------------------------
#  2. Um evento com lot_quantity = CAPACIDADE (default 1)
# ---------------------------------------------------------------------------
# Corpo minimo para EventBffController.createEvent + EventService:
#   creator_id, event_name, is_EAD, event_date (futuro), description,
#   lot_quantity, presenters: []  (null e rejeitado por validateEventBasic:
#   "Lista de apresentadores nao pode ser nula").
echo ""
echo "==> 2. Criando evento (lot_quantity = $CAPACIDADE)"
EVENT_DATE="$(date -u -d '+30 days' '+%Y-%m-%dT%H:%M:%S' 2>/dev/null \
    || date -u -v+30d '+%Y-%m-%dT%H:%M:%S')"
code=$(curl -s -o "$TMP/event.json" -w '%{http_code}' \
    -X POST "$BASE_URL/bff/events" \
    -H 'Content-Type: application/json' \
    -d "{\"creator_id\":${U1_ID},\"event_name\":\"Evento Overselling ${SUFIXO}\",\"is_EAD\":true,\"event_date\":\"${EVENT_DATE}\",\"description\":\"roteiro issue #9\",\"lot_quantity\":${CAPACIDADE},\"presenters\":[]}")
if [ "$code" != "201" ]; then
    echo "   ERRO: POST /bff/events devolveu HTTP $code (esperado 201). Corpo:"
    cat "$TMP/event.json"; echo
    exit 1
fi
EVENT_ID=$(extrair_id "event_id" "$TMP/event.json")
[ -n "$EVENT_ID" ] || { echo "   ERRO: nao consegui extrair event_id."; exit 1; }
ok "evento criado: event_id=$EVENT_ID"

QUANT_INICIAL=$(psql_db "SELECT quant FROM stock.eventcap WHERE event_id = ${EVENT_ID};" | tr -d '[:space:]')
ok "eventcap inicial: quant=$QUANT_INICIAL (esperado $CAPACIDADE)"
[ "$QUANT_INICIAL" = "$CAPACIDADE" ] || falhar "eventcap.quant inicial = $QUANT_INICIAL, esperado $CAPACIDADE"

# ---------------------------------------------------------------------------
#  3. Duas reservas concorrentes (barreira comum + dois curl em background)
# ---------------------------------------------------------------------------
# Rota real: POST /bff/event-wallets  (o texto da issue diz /api/bff/... ->
# OBSOLETO; o nginx do frontend e que remove o prefixo /api). /bff/** e
# permitAll no SecurityConfig -> sem JWT.
# Corpo: {"userId": <id>, "eventId": <id>}.
# (Desde a #57 o userId vem do JWT: userId do corpo diferente do dono do token
#  da 403. Este roteiro ja nao roda desde a #78/#79 -- cria usuario sem JWT.)
echo ""
echo "==> 3. Disparando 2 reservas concorrentes (u1 e u2)"

reservar() {   # $1 = userId ; $2 = tag
    curl -s -o "$TMP/resp_$2.body" -w '%{http_code}' \
        -X POST "$BASE_URL/bff/event-wallets" \
        -H 'Content-Type: application/json' \
        -d "{\"userId\":$1,\"eventId\":${EVENT_ID}}" > "$TMP/resp_$2.code"
}

reservar "$U1_ID" a &
P1=$!
reservar "$U2_ID" b &
P2=$!
wait "$P1"
wait "$P2"

CODE_A=$(cat "$TMP/resp_a.code"); BODY_A=$(cat "$TMP/resp_a.body")
CODE_B=$(cat "$TMP/resp_b.code"); BODY_B=$(cat "$TMP/resp_b.body")

# ---------------------------------------------------------------------------
#  4. Estado observado (impresso ANTES da limpeza -> um run que falha deixa
#     evidencia no stdout mesmo com os dados ja removidos)
# ---------------------------------------------------------------------------
QUANT_FINAL=$(psql_db "SELECT quant FROM stock.eventcap WHERE event_id = ${EVENT_ID};" | tr -d '[:space:]')
PENDING=$(psql_db "SELECT count(*) FROM reservation.walletevent WHERE event_id = ${EVENT_ID} AND status = 'pending';" | tr -d '[:space:]')
ATIVOS=$(psql_db "SELECT count(*) FROM reservation.walletevent WHERE event_id = ${EVENT_ID} AND status IN ('pending','confirmed');" | tr -d '[:space:]')

echo ""
echo "--------------------------------------------------"
echo "   ESTADO OBSERVADO"
echo "--------------------------------------------------"
echo "   resposta u1 .....: HTTP $CODE_A | corpo: $BODY_A"
echo "   resposta u2 .....: HTTP $CODE_B | corpo: $BODY_B"
echo "   eventcap.quant ..: $QUANT_FINAL"
echo "   walletevent pending .......: $PENDING"
echo "   walletevent ativos (p+c) ..: $ATIVOS"
echo "--------------------------------------------------"

# ---------------------------------------------------------------------------
#  5. Assercoes (o "teste" -- fixadas no criterio da issue: capacity = 1)
# ---------------------------------------------------------------------------
echo ""
echo "==> 5. Assercoes"

# 5.1 par de status: exatamente 1x 201 "pending" e exatamente 1x "Evento lotado".
N_201=0
N_LOTADO=0
for pair in "$CODE_A|$BODY_A" "$CODE_B|$BODY_B"; do
    code="${pair%%|*}"; body="${pair#*|}"
    if [ "$code" = "201" ] && printf '%s' "$body" | grep -q '"status":"pending"'; then
        N_201=$((N_201 + 1))
    fi
    if [ "$code" = "400" ] && printf '%s' "$body" | grep -q 'Evento lotado'; then
        N_LOTADO=$((N_LOTADO + 1))
    fi
done
if [ "$N_201" = "1" ]; then ok "exatamente 1 resposta 201 'pending'"
    else falhar "esperava 1 resposta 201 'pending', obtive $N_201"; fi
if [ "$N_LOTADO" = "1" ]; then ok "exatamente 1 resposta 400 'Evento lotado'"
    else falhar "esperava 1 resposta 400 'Evento lotado', obtive $N_LOTADO"; fi

# 5.2 invariante de estado -- e ISTO que prova o no-overselling:
#   - quant nunca fica negativo (CHECK eventcap_quant_bounds; se o CAS estivesse
#     quebrado o CHECK dispararia e o addLink devolveria HTTP 500, nao 400);
#   - exatamente 1 linha pending para o evento.
if [ "$QUANT_FINAL" = "0" ]; then ok "eventcap.quant = 0"
    else falhar "eventcap.quant = $QUANT_FINAL, esperado 0"; fi
case "$QUANT_FINAL" in
    -*) falhar "eventcap.quant NEGATIVO ($QUANT_FINAL) -> OVERSELLING" ;;
esac
if [ "$PENDING" = "1" ]; then ok "exatamente 1 walletevent pending"
    else falhar "walletevent pending = $PENDING, esperado 1 -> OVERSELLING"; fi

# ---------------------------------------------------------------------------
#  6. Resumo
# ---------------------------------------------------------------------------
echo ""
echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then
    echo "   RESULTADO: OK -- sem overselling."
    echo "   (1 reserva ganhou a vaga, 1 recebeu 'Evento lotado';"
    echo "    quant=0 e 1 linha pending -- invariante preservada.)"
else
    echo "   RESULTADO: $FALHAS ASSERCAO(OES) FALHARAM."
    echo ""
    echo "   Assinatura de falha (o que este roteiro pega):"
    echo "     - as DUAS respostas 201 'pending'        -> overselling no HTTP"
    echo "     - walletevent pending > 1               -> overselling no estado"
    echo "     - eventcap.quant negativo               -> CAS furado"
    echo "     - HTTP 500 no lugar de 400 'Evento lotado' -> CHECK"
    echo "       eventcap_quant_bounds disparou (quant=-1 impossivel de gravar)"
fi
echo "=================================================="
echo ""

exit $(( FALHAS > 0 ? 1 : 0 ))
