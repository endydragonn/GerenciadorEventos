#!/bin/bash

# =============================================================================
#  verify.sh · harness de carga (issue #11)
# =============================================================================
#
#  Depois da rodada de carga, consulta o banco DIRETO (fonte de verdade,
#  não a resposta HTTP do k6): eventcap.capacity e count(*) confirmed em
#  walletevent para o evento-alvo. Calcula o veredito de corretude.
#
#  Importante (ver README, seção "braço cas vs naive"): no braço cas (default)
#  o veredito esperado é sempre OK -- overselling ali é bug. No braço naive
#  (experimento de controle, RESERVATION_READPATH=naive) overselling é
#  ESPERADO (o lag do read model é a causa) -- OVERSELL nesse braço não é
#  falha do harness nem do backend, é o fenômeno sendo medido.
#
#  Variáveis de ambiente:
#    EVENT_ID       id do evento verificado         (obrigatória)
#    CONTAINER_DB   container do Postgres            (default gerenciador-db)
#    OUT_FILE       CSV de saída (metrica,valor)     (default mktemp)
#
#  Saída (stdout + $OUT_FILE), formato "metrica,valor" (consumido por report.sh):
#    capacity,<int>            (-1 = evento ilimitado)
#    confirmadas,<int>
#    pendentes,<int>
#    veredito_corretude,OK|OVERSELL
# =============================================================================

set -uo pipefail

EVENT_ID="${EVENT_ID:?EVENT_ID é obrigatória}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
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

CAPACITY="$(psql_db "SELECT capacity FROM stock.eventcap WHERE event_id = ${EVENT_ID};" | tr -d '[:space:]')"
CONFIRMADAS="$(psql_db "SELECT count(*) FROM reservation.walletevent WHERE event_id = ${EVENT_ID} AND status = 'confirmed';" | tr -d '[:space:]')"
PENDENTES="$(psql_db "SELECT count(*) FROM reservation.walletevent WHERE event_id = ${EVENT_ID} AND status = 'pending';" | tr -d '[:space:]')"

if [ -z "$CAPACITY" ]; then
    echo "   ERRO: eventcap não encontrado para event_id=${EVENT_ID}" >&2
    exit 1
fi

VEREDITO="OK"
if [ "$CAPACITY" != "-1" ] && [ "$CONFIRMADAS" -gt "$CAPACITY" ]; then
    VEREDITO="OVERSELL"
fi

{
    echo "metrica,valor"
    echo "capacity,${CAPACITY}"
    echo "confirmadas,${CONFIRMADAS}"
    echo "pendentes,${PENDENTES}"
    echo "veredito_corretude,${VEREDITO}"
} | tee "$OUT_FILE" >&2

echo "$OUT_FILE"
