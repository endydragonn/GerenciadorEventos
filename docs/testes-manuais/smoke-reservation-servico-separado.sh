#!/bin/bash

# =============================================================================
#  smoke-reservation-servico-separado.sh  ·  issue #33 (Fase 7)
# =============================================================================
#
#  Smoke manual da reserva com o servico `reservation` SEPARADO: prova, pelo gateway (/bff/**), pelo salto HTTP
#  gateway -> reservation, pelo pooler e pelo broker, que
#    - o evento criado no catalog ganha o `eventcap` NO SERVICO (chamada sincrona) ;
#    - com capacity = 3 e 8 usuarios reservando AO MESMO TEMPO (braco cas) saem exatamente
#      3 x 201 e 5 x 400 "Evento lotado" (overselling = 0);
#    - o banco, lido no schema NOVO (`reservation.*`), concorda: 3 reservas ativas, eventcap.quant = 0,
#      3 eventos SlotReserved no event store DO SERVICO (reservation.event_store).
#  O script sai com codigo != 0 se qualquer assercao falhar.
#
#  Para o braco naive: suba o backend com RESERVATION_READPATH=naive (a checagem vira 2 chamadas HTTP; com o consumer em dia
#  o resultado tambem fecha em 3, com ele atrasado oversella: e o braco de controle) e passe ESPERADAS_201=... .
#
#  Pre-requisito: a stack no ar (db, supabase-pooler, redpanda, reservation, backend):
#    docker compose -f Projeto/docker-compose.yml up -d --build
#
#  Uso:  docs/testes-manuais/smoke-reservation-servico-separado.sh
#  Variaveis (opcionais): BASE_URL (http://localhost:8081), CONTAINER_DB (gerenciador-db), CAPACIDADE (3),
#    USUARIOS (8), MANTER_DADOS (1 = nao limpa).
#  Compatibilidade: Linux/macOS e Git Bash do Windows (Docker Desktop).
# =============================================================================

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
CAPACIDADE="${CAPACIDADE:-3}"
USUARIOS="${USUARIOS:-8}"
MANTER_DADOS="${MANTER_DADOS:-0}"
SUFIXO="$(date +%s)-$RANDOM"
TMP="$(mktemp -d)"
FALHAS=0

psql_db() {
    # a role do app e a do container (POSTGRES_USER do Projeto/.env); o -h localhost evita o peer auth do socket
    docker exec -i "$CONTAINER_DB" sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$POSTGRES_DB" -At' <<<"$1"
}

ok()   { echo "   [ok]    $1"; }
erro() { echo "   [ERRO]  $1"; FALHAS=$((FALHAS + 1)); }
confere() { # descricao esperado obtido
    if [ "$2" = "$3" ]; then ok "$1: $3"; else erro "$1: esperado $2, obtido '$3'"; fi
}

limpar() {
    if [ "$MANTER_DADOS" != "1" ]; then
        psql_db "DELETE FROM reservation.walletevent WHERE event_id IN (SELECT event_id FROM event WHERE event_name = 'Smoke33 $SUFIXO');
                 DELETE FROM reservation.eventcap WHERE event_id IN (SELECT event_id FROM event WHERE event_name = 'Smoke33 $SUFIXO');
                 DELETE FROM event WHERE event_name = 'Smoke33 $SUFIXO';
                 DELETE FROM users WHERE email LIKE 'smoke33-$SUFIXO-%@example.com';" >/dev/null 2>&1 || true
    fi
    rm -rf "$TMP"
}
trap limpar EXIT

echo "=================================================="
echo "   SMOKE #33: reserva com o servico separado"
echo "   BASE_URL=$BASE_URL  CAPACIDADE=$CAPACIDADE  USUARIOS=$USUARIOS  SUFIXO=$SUFIXO"
echo "=================================================="

echo "==> 0. backend no ar?"
for i in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health")" = "200" ] && break
    sleep 2
done
confere "GET /actuator/health" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health")"

echo "==> 1. usuarios e evento (o evento chama o reservation para criar o eventcap)"
usuarios='['
for i in $(seq 1 "$USUARIOS"); do
    usuarios="$usuarios{\"name\":\"Smoke33 $SUFIXO $i\",\"email\":\"smoke33-$SUFIXO-$i@example.com\",\"password\":\"senha123\",\"fone\":\"s33-$SUFIXO-$i\",\"birthDate\":\"1990-01-15\"},"
done
usuarios="${usuarios%,}]"
curl -s -X POST "$BASE_URL/bff/seed" -H 'Content-Type: application/json' -d "{\"users\":$usuarios}" >/dev/null
declare -a UID_
for i in $(seq 1 "$USUARIOS"); do
    UID_[$i]="$(psql_db "SELECT user_id FROM users WHERE email = 'smoke33-$SUFIXO-$i@example.com'" | tr -d '[:space:]')"
done
curl -s -X POST "$BASE_URL/bff/seed" -H 'Content-Type: application/json' \
    -d "{\"events\":[{\"creator_id\":${UID_[1]},\"event_name\":\"Smoke33 $SUFIXO\",\"is_EAD\":true,\"event_date\":\"2035-12-01T14:00:00\",\"lot_quantity\":$CAPACIDADE,\"description\":\"smoke\"}]}" >/dev/null
EVENT_ID="$(psql_db "SELECT event_id FROM event WHERE event_name = 'Smoke33 $SUFIXO'" | tr -d '[:space:]')"
confere "evento criado no catalog" "sim" "$([ -n "$EVENT_ID" ] && echo sim || echo nao)"
confere "reservation.eventcap.capacity do evento (criado pelo servico)" "$CAPACIDADE" \
    "$(psql_db "SELECT capacity FROM reservation.eventcap WHERE event_id = ${EVENT_ID:-0}" | tr -d '[:space:]')"

echo "==> 2. login de cada usuario"
for i in $(seq 1 "$USUARIOS"); do
    curl -s -X POST "$BASE_URL/bff/users/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"smoke33-$SUFIXO-$i@example.com\",\"password\":\"senha123\"}" | grep -o '"token":"[^"]*"' | cut -d'"' -f4 >"$TMP/token-$i"
done

echo "==> 3. $USUARIOS reservas SIMULTANEAS no evento de capacidade $CAPACIDADE (gateway -> reservation)"
for i in $(seq 1 "$USUARIOS"); do
    (
        curl -s -o "$TMP/corpo-$i" -w '%{http_code}' -X POST "$BASE_URL/bff/event-wallets" \
            -H "Authorization: Bearer $(cat "$TMP/token-$i")" -H 'Content-Type: application/json' \
            -d "{\"userId\":${UID_[$i]},\"eventId\":$EVENT_ID}" >"$TMP/status-$i"
    ) &
done
wait
aceitas=0; lotadas=0
for i in $(seq 1 "$USUARIOS"); do
    st="$(cat "$TMP/status-$i")"
    if [ "$st" = "201" ]; then aceitas=$((aceitas + 1)); elif [ "$st" = "400" ] && grep -qi "lotado" "$TMP/corpo-$i"; then lotadas=$((lotadas + 1)); fi
done
confere "respostas 201 (reserva pending)" "$CAPACIDADE" "$aceitas"
confere "respostas 400 'Evento lotado'" "$((USUARIOS - CAPACIDADE))" "$lotadas"

echo "==> 4. o banco (schema reservation) concorda"
confere "reservas ativas em reservation.walletevent" "$CAPACIDADE" \
    "$(psql_db "SELECT count(*) FROM reservation.walletevent WHERE event_id = $EVENT_ID AND status IN ('pending','confirmed')" | tr -d '[:space:]')"
confere "reservation.eventcap.quant" 0 "$(psql_db "SELECT quant FROM reservation.eventcap WHERE event_id = $EVENT_ID" | tr -d '[:space:]')"
confere "SlotReserved em reservation.event_store (event store DO SERVICO)" "$CAPACIDADE" \
    "$(psql_db "SELECT count(*) FROM reservation.event_store WHERE stream_type = 'reservation.wallet' AND event_type = 'SlotReserved' AND partition_key = '$EVENT_ID'" | tr -d '[:space:]')"
confere "o event store do monolito (public) NAO recebeu o stream reservation.wallet" 0 \
    "$(psql_db "SELECT count(*) FROM public.event_store WHERE stream_type = 'reservation.wallet' AND partition_key = '$EVENT_ID'" | tr -d '[:space:]')"

echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then echo "   RESULTADO: SMOKE OK (overselling = 0)"; else echo "   RESULTADO: $FALHAS ASSERCAO(OES) FALHARAM"; fi
echo "=================================================="
exit "$FALHAS"
