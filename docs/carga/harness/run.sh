#!/bin/bash

# =============================================================================
#  run.sh · harness de carga (issue #11) — orquestrador
# =============================================================================
#
#  Roda uma rodada completa do harness contra o BASE_URL informado:
#    0. probe "backend no ar"      (GET /bff/events, não /actuator/health)
#    1. setup.sh                   host + N attendees + evento com capacity
#    2. collector.sh em background amostra o lag de event_availability
#    3. k6 (container grafana/k6)  N VUs disparando a reserva concorrente
#    4. para o collector
#    5. verify.sh                  corretude lida direto do banco
#    6. report.sh                  CSV final + gráfico HTML
#
#  PENDÊNCIA CONHECIDA (documentada, não fingida como feita — ver README):
#  esta rodada é disparada da MESMA máquina que roda a stack (BASE_URL default
#  aponta pra rede Docker local). A execução "de verdade" da issue #11
#  (disparada da máquina/VM separada da issue #4, com desconto da baseline de
#  latência de rede) depende da #4, que ainda está aberta -- só o parâmetro
#  BASE_URL muda quando a VM existir, nada de código.
#
#  Variáveis de ambiente (todas opcionais):
#    BASE_URL_PROBE   URL do backend visto DESTA máquina (probe HTTP inicial)
#                      (default http://localhost:8081)
#    BASE_URL_K6      URL do backend visto de DENTRO do container do k6
#                      (default http://backend:8081 -- nome do serviço na rede
#                      gerenciador-net; aponte para a VM da #4 quando existir)
#    NETWORK          rede Docker do k6                  (default gerenciador-net)
#    CONTAINER_DB     container do Postgres               (default gerenciador-db)
#    K6_IMAGE         imagem do k6                         (default grafana/k6)
#    N                nº de attendees / VUs concorrentes  (default 10)
#    CAPACITY         capacity do evento                  (default 4)
#    READPATH         só informativo no relatório final -- NÃO troca o braço
#                      em runtime (é @Value, lido só no startup do backend).
#                      Pra rodar o braço naive, suba o backend com
#                      RESERVATION_READPATH=naive ANTES de chamar este script.
#                      (default cas)
#    COLLECTOR_INTERVAL_S  intervalo de amostragem do lag, em segundos (default 1)
#    COLLECTOR_COOLDOWN_S  quanto esperar após o k6 terminar antes de parar o
#                      collector -- cobre 1 ciclo do job de refresh do read
#                      model (default 6, > READMODEL_AVAILABILITY_REFRESH_MS
#                      padrão de 5000ms) para não ficar só com o sentinela -1
#                      numa rodada curta
#    OUT_DIR          diretório de saída da rodada
#                      (default docs/carga/harness/resultados/<sufixo>)
#    MANTER_DADOS     se "1", não limpa host/attendees/evento ao final
#
#  Pré-requisito: stack local no ar.
#    docker compose -f Projeto/docker-compose.yml up -d --build db backend
#
#  Uso:
#    docs/carga/harness/run.sh
#    N=30 CAPACITY=10 docs/carga/harness/run.sh
# =============================================================================

set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BASE_URL_PROBE="${BASE_URL_PROBE:-http://localhost:8081}"
BASE_URL_K6="${BASE_URL_K6:-http://backend:8081}"
NETWORK="${NETWORK:-gerenciador-net}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
K6_IMAGE="${K6_IMAGE:-grafana/k6}"
N="${N:-10}"
CAPACITY="${CAPACITY:-4}"
READPATH="${READPATH:-cas}"
COLLECTOR_INTERVAL_S="${COLLECTOR_INTERVAL_S:-1}"
MANTER_DADOS="${MANTER_DADOS:-0}"

SUFIXO_RODADA="$(date +%s)-$$"
OUT_DIR="${OUT_DIR:-$DIR/resultados/$SUFIXO_RODADA}"

IS_WIN=0
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) IS_WIN=1 ;;
esac

dexec() {
    if [ "$IS_WIN" = "1" ]; then
        MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@"
    else
        docker "$@"
    fi
}

# caminho de host no formato que o Docker Desktop no Windows espera em `-v`
# (drive letter, não /c/...); no Linux/macOS o caminho já serve como está.
caminho_docker() {
    if [ "$IS_WIN" = "1" ] && command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        echo "$1"
    fi
}

echo ""
echo "=================================================="
echo "   HARNESS DE CARGA (issue #11)"
echo "=================================================="
echo "   BASE_URL_PROBE ......: $BASE_URL_PROBE"
echo "   BASE_URL_K6 .........: $BASE_URL_K6"
echo "   N (VUs/attendees) ...: $N"
echo "   CAPACITY ............: $CAPACITY"
echo "   READPATH (informativo): $READPATH"
echo "   OUT_DIR .............: $OUT_DIR"
echo "=================================================="

mkdir -p "$OUT_DIR"
HOST_ID=""
EVENT_ID=""
SUFIXO_FIXTURE=""
COLLECTOR_PID=""

limpar() {
    if [ -n "$COLLECTOR_PID" ] && kill -0 "$COLLECTOR_PID" 2>/dev/null; then
        kill "$COLLECTOR_PID" 2>/dev/null
        wait "$COLLECTOR_PID" 2>/dev/null
    fi
    if [ "$MANTER_DADOS" = "1" ] || [ -z "$SUFIXO_FIXTURE" ]; then
        return
    fi
    echo ""
    echo "==> Limpeza da fixture (sufixo $SUFIXO_FIXTURE)"
    dexec exec -i "$CONTAINER_DB" sh -c \
        'psql -v ON_ERROR_STOP=1 -t -A -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' \
        sh "DELETE FROM walletevent WHERE event_id IN (SELECT event_id FROM event WHERE event_name LIKE 'Evento Carga %${SUFIXO_FIXTURE}'); DELETE FROM event WHERE event_name LIKE 'Evento Carga %${SUFIXO_FIXTURE}'; DELETE FROM users WHERE email LIKE 'carga-%-${SUFIXO_FIXTURE}@example.com';" \
        >/dev/null 2>&1 || true
    echo "    dados do sufixo $SUFIXO_FIXTURE removidos"
}
trap limpar EXIT

# ---------------------------------------------------------------------------
#  0. Backend no ar?
# ---------------------------------------------------------------------------
echo ""
echo "==> 0. Aguardando o backend responder em $BASE_URL_PROBE"
PROBE=$(curl -s -o /dev/null -w '%{http_code}' \
    --retry-connrefused --retry 30 --retry-delay 2 \
    "$BASE_URL_PROBE/bff/events?creator_id=1")
if [ "$PROBE" != "200" ]; then
    echo "   ERRO: backend não respondeu (HTTP $PROBE em /bff/events). Suba a stack:" >&2
    echo "     docker compose -f Projeto/docker-compose.yml up -d --build db backend" >&2
    exit 1
fi
echo "   backend respondendo"
if ! dexec ps --format '{{.Names}}' | grep -qx "$CONTAINER_DB"; then
    echo "   ERRO: container $CONTAINER_DB não está no ar." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
#  1. setup.sh — fixture (host + N attendees + evento com capacity)
# ---------------------------------------------------------------------------
echo ""
echo "==> 1. setup.sh (N=$N, CAPACITY=$CAPACITY)"
TOKENS_FILE="$OUT_DIR/tokens.json"
SETUP_OUT="$(BASE_URL="$BASE_URL_PROBE" N="$N" CAPACITY="$CAPACITY" TOKENS_FILE="$TOKENS_FILE" \
    "$DIR/setup.sh")" || { echo "   ERRO: setup.sh falhou" >&2; exit 1; }

EVENT_ID="$(printf '%s\n' "$SETUP_OUT" | grep -oP '^EVENT_ID=\K.*')"
SUFIXO_FIXTURE="$(printf '%s\n' "$SETUP_OUT" | grep -oP '^SUFIXO=\K.*')"
HOST_ID="$(printf '%s\n' "$SETUP_OUT" | grep -oP '^HOST_ID=\K.*')"
[ -n "$EVENT_ID" ] && [ -n "$SUFIXO_FIXTURE" ] || { echo "   ERRO: não consegui ler EVENT_ID/SUFIXO da saída de setup.sh" >&2; exit 1; }
echo "   fixture pronta: event_id=$EVENT_ID host_id=$HOST_ID sufixo=$SUFIXO_FIXTURE"

# ---------------------------------------------------------------------------
#  2. collector.sh em background — amostra o lag durante a rodada
# ---------------------------------------------------------------------------
echo ""
echo "==> 2. collector.sh em background (intervalo ${COLLECTOR_INTERVAL_S}s)"
LAG_FILE="$OUT_DIR/lag.csv"
EVENT_ID="$EVENT_ID" CONTAINER_DB="$CONTAINER_DB" INTERVAL_S="$COLLECTOR_INTERVAL_S" OUT_FILE="$LAG_FILE" \
    "$DIR/collector.sh" &
COLLECTOR_PID=$!
sleep 1   # garante ao menos 1 amostra antes da carga começar

# ---------------------------------------------------------------------------
#  3. k6 — N VUs, 1 iteração cada, todas disparando juntas
# ---------------------------------------------------------------------------
echo ""
echo "==> 3. k6 ($K6_IMAGE) — VUS=$N contra $BASE_URL_K6"
SUMMARY_FILE="$OUT_DIR/k6-summary.json"
METRICS_FILE="$OUT_DIR/k6-metrics.csv"
HARNESS_DOCKER_PATH="$(caminho_docker "$DIR")"
OUT_DIR_DOCKER_PATH="$(caminho_docker "$OUT_DIR")"

dexec run --rm --network "$NETWORK" \
    -v "${HARNESS_DOCKER_PATH}:/scripts:ro" \
    -v "${OUT_DIR_DOCKER_PATH}:/out" \
    -e BASE_URL="$BASE_URL_K6" \
    -e EVENT_ID="$EVENT_ID" \
    -e TOKENS_FILE="/out/tokens.json" \
    -e VUS="$N" \
    "$K6_IMAGE" run \
    --summary-export=/out/k6-summary.json \
    --out csv=/out/k6-metrics.csv \
    /scripts/scenario.js
K6_EXIT=$?
if [ "$K6_EXIT" -ne 0 ]; then
    echo "   ERRO: k6 saiu com código $K6_EXIT" >&2
    exit 1
fi

# Um cenário pequeno (N/CAPACITY baixos, como um smoke run) termina em bem
# menos de 1s -- rápido demais pro job ReadModelRefreshService (default
# READMODEL_AVAILABILITY_REFRESH_MS=5000ms) ter rodado sequer 1x pro evento
# desta rodada. Sem esperar, o collector só registraria o sentinela -1 ("sem
# leitura ainda") e o gráfico de lag ficaria vazio. Dá margem pra pelo menos
# 1 ciclo de refresh acontecer antes de parar de amostrar.
echo ""
echo "==> 3.1 aguardando ${COLLECTOR_COOLDOWN_S:-6}s (cobre 1 ciclo do job de refresh do read model, default 5000ms) antes de parar o collector"
sleep "${COLLECTOR_COOLDOWN_S:-6}"

# ---------------------------------------------------------------------------
#  4. Para o collector
# ---------------------------------------------------------------------------
echo ""
echo "==> 4. Parando collector.sh"
kill "$COLLECTOR_PID" 2>/dev/null
wait "$COLLECTOR_PID" 2>/dev/null
COLLECTOR_PID=""

# ---------------------------------------------------------------------------
#  5. verify.sh — corretude lida direto do banco
# ---------------------------------------------------------------------------
echo ""
echo "==> 5. verify.sh"
VERIFY_FILE="$OUT_DIR/verify.csv"
EVENT_ID="$EVENT_ID" CONTAINER_DB="$CONTAINER_DB" OUT_FILE="$VERIFY_FILE" "$DIR/verify.sh" >/dev/null

# ---------------------------------------------------------------------------
#  6. report.sh — CSV final + gráfico
# ---------------------------------------------------------------------------
echo ""
echo "==> 6. report.sh"
RESULTADO_CSV="$OUT_DIR/resultado.csv"
RESULTADO_HTML="$OUT_DIR/resultado.html"
"$DIR/report.sh" \
    --summary "$SUMMARY_FILE" \
    --verify "$VERIFY_FILE" \
    --lag "$LAG_FILE" \
    --n-vus "$N" \
    --capacity "$CAPACITY" \
    --readpath "$READPATH" \
    --base-url "$BASE_URL_K6" \
    --out-csv "$RESULTADO_CSV" \
    --out-html "$RESULTADO_HTML"

echo ""
echo "=================================================="
echo "   RODADA CONCLUÍDA"
echo "=================================================="
echo "   tokens .......: $TOKENS_FILE"
echo "   lag ..........: $LAG_FILE"
echo "   verify .......: $VERIFY_FILE"
echo "   k6 summary ...: $SUMMARY_FILE"
echo "   k6 metrics ...: $METRICS_FILE"
echo "   CSV final ....: $RESULTADO_CSV"
echo "   gráfico HTML .: $RESULTADO_HTML"
echo "=================================================="
cat "$RESULTADO_CSV"
echo ""
