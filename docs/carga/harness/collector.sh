#!/bin/bash

# =============================================================================
#  collector.sh · harness de carga (issue #11)
# =============================================================================
#
#  Amostra o lag do read model defasado (event_availability) direto no banco,
#  num intervalo curto, enquanto a carga (k6) roda. Grava uma SÉRIE de
#  amostras (timestamp_epoch,lag_ms), não um valor único -- é isso que o
#  Aceite da issue pede ("lag observado do read model").
#
#  lag_ms = now() - event_availability.refreshed_at, em milissegundos. O
#  comentário em db/init-scripts/09-event-availability.sql já documenta esse
#  uso: "o harness da #11 mede o lag observado por now() - refreshed_at".
#
#  Pensado para rodar em BACKGROUND (run.sh faz `collector.sh & PID=$!` e
#  depois `kill $PID`); mas funciona standalone (Ctrl+C também encerra e
#  grava a amostra final via trap).
#
#  Variáveis de ambiente:
#    EVENT_ID       id do evento monitorado             (obrigatória)
#    CONTAINER_DB   container do Postgres                (default gerenciador-db)
#    INTERVAL_S     intervalo entre amostras, em segundos (default 1)
#    OUT_FILE       CSV de saída                          (default mktemp)
#
#  Mesmo idioma de docs/testes-manuais/overselling-sob-concorrencia.sh
#  (dexec/psql_db, compatível com Git Bash no Windows).
# =============================================================================

set -uo pipefail

EVENT_ID="${EVENT_ID:?EVENT_ID é obrigatória}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
INTERVAL_S="${INTERVAL_S:-1}"
OUT_FILE="${OUT_FILE:-$(mktemp)}"

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

psql_db() {
    dexec exec -i "$CONTAINER_DB" sh -c \
        'psql -v ON_ERROR_STOP=1 -t -A -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' \
        sh "$1"
}

echo "metrica,timestamp_epoch,valor" > "$OUT_FILE"

PARANDO=0
parar() { PARANDO=1; }
trap parar TERM INT

amostrar() {
    local agora lag
    agora="$(date +%s)"
    lag="$(psql_db "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - refreshed_at)) * 1000, -1) FROM event_availability WHERE event_id = ${EVENT_ID};" 2>/dev/null | tr -d '[:space:]')"
    if [ -z "$lag" ]; then
        # linha ainda não existe (job de refresh não rodou pra este evento) --
        # registra -1 (sentinela "sem leitura ainda"), não quebra a série.
        lag="-1"
    fi
    echo "lag_ms,${agora},${lag}" >> "$OUT_FILE"
}

echo "==> collector.sh: amostrando lag de event_availability (event_id=$EVENT_ID) a cada ${INTERVAL_S}s em $OUT_FILE" >&2

while [ "$PARANDO" -eq 0 ]; do
    amostrar
    sleep "$INTERVAL_S" &
    wait $! 2>/dev/null
done
# amostra final, após o sinal de parada -- captura o estado no fim da rodada.
amostrar
echo "==> collector.sh: parado, $(($(wc -l < "$OUT_FILE") - 1)) amostras em $OUT_FILE" >&2
