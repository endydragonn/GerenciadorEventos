#!/bin/bash

# =============================================================================
#  smoke-reservation-servico-separado.sh  ·  issue #33 (Fase 7)
# =============================================================================
#
#  Smoke manual da reserva com o servico `reservation` SEPARADO, pelo gateway (/bff/**), pelo salto HTTP
#  gateway -> reservation, pelo pooler e pelo broker. Capacidade 3 e 8 usuarios reservando AO MESMO TEMPO; o braco e o
#  do backend no ar (RESERVATION_READPATH):
#    - comum aos dois: o evento criado no catalog ganha o `eventcap` NO SERVICO (chamada sincrona); as reservas ativas em
#      reservation.walletevent = respostas 201 = SlotReserved no event store DO SERVICO (reservation.event_store), e nada
#      do stream reservation.wallet no public.event_store;
#    - braco cas (default): exatamente 3 x 201 e 5 x 400 "Evento lotado", overselling = 0 EXIGIDO, eventcap.quant = 0;
#    - braco naive (controle; RESERVATION_READPATH=naive no backend): a checagem sao 2 chamadas HTTP (disponibilidade
#      defasada e depois a reserva sem CAS), entao a janela check-then-act passa pela rede e OVERSELLA mesmo com o consumer
#      em dia (medido na #35: 8 x 201 e 7 x 201 em duas rodadas). Exige so 201 + "Evento lotado" = usuarios e
#      eventcap.quant = capacidade (o naive nao toca o eventcap); o overselling e REPORTADO, nao exigido igual a 0.
#  O script sai com codigo != 0 se qualquer assercao falhar.
#
#  Pre-requisito: a stack no ar (db, supabase-pooler, redpanda, reservation, catalog, backend) COM o seed ligado (desde a
#  #35 o POST /bff/seed so existe com SEED_ENABLED=true; o override de carga liga). O evento mora em catalog.event (#35):
#    docker compose -f Projeto/docker-compose.yml -f Projeto/carga/docker-compose.carga.yml up -d --build
#
#  Uso:  docs/testes-manuais/smoke-reservation-servico-separado.sh
#  Variaveis (opcionais): BASE_URL (http://localhost:8081), CONTAINER_DB (gerenciador-db), CAPACIDADE (3),
#    USUARIOS (8), MANTER_DADOS (1 = nao limpa),
#    BRACO (derivada: RESERVATION_READPATH do gerenciador-backend; ausente = cas; pode ser forcada),
#    ESPERADAS_201 (no cas vale CAPACIDADE; no naive vazia = so reporta; definida = exige esse numero de 201).
#  Compatibilidade: Linux/macOS e Git Bash do Windows (Docker Desktop).
# =============================================================================

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
CAPACIDADE="${CAPACIDADE:-3}"
USUARIOS="${USUARIOS:-8}"
MANTER_DADOS="${MANTER_DADOS:-0}"
# braco lido do backend no ar (RESERVATION_READPATH; ausente = cas). naive: sem ESPERADAS_201 so confere a consistencia e
# reporta o overselling (janela check-then-act pela rede; lag da projecao nao medido); cas: exige CAPACIDADE x 201 e overselling 0.
BRACO="${BRACO:-$(docker exec gerenciador-backend printenv RESERVATION_READPATH 2>/dev/null | tr -d '[:space:]')}"
BRACO="${BRACO:-cas}"
ESPERADAS_201="${ESPERADAS_201:-}"
[ "$BRACO" = naive ] || ESPERADAS_201="${ESPERADAS_201:-$CAPACIDADE}"
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
        psql_db "DELETE FROM reservation.walletevent WHERE event_id IN (SELECT event_id FROM catalog.event WHERE event_name = 'Smoke33 $SUFIXO');
                 DELETE FROM stock.hold WHERE event_id IN (SELECT event_id FROM catalog.event WHERE event_name = 'Smoke33 $SUFIXO');
                 DELETE FROM stock.eventcap WHERE event_id IN (SELECT event_id FROM catalog.event WHERE event_name = 'Smoke33 $SUFIXO');
                 DELETE FROM catalog.event WHERE event_name = 'Smoke33 $SUFIXO';
                 DELETE FROM identity.users WHERE email LIKE 'smoke33-$SUFIXO-%@example.com';" >/dev/null 2>&1 || true
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
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/bff/seed" -H 'Content-Type: application/json' -d "{\"users\":$usuarios}")"
if [ "$code" = "404" ]; then
    echo "   [ABORTA] POST /bff/seed = 404: o seed esta desligado (SEED_ENABLED, #35). Suba com o override de carga:"
    echo "            docker compose -f Projeto/docker-compose.yml -f Projeto/carga/docker-compose.carga.yml up -d"
    exit 1
fi
declare -a UID_
for i in $(seq 1 "$USUARIOS"); do
    UID_[$i]="$(psql_db "SELECT user_id FROM identity.users WHERE email = 'smoke33-$SUFIXO-$i@example.com'" | tr -d '[:space:]')"
done
curl -s -X POST "$BASE_URL/bff/seed" -H 'Content-Type: application/json' \
    -d "{\"events\":[{\"creator_id\":${UID_[1]},\"event_name\":\"Smoke33 $SUFIXO\",\"is_EAD\":true,\"event_date\":\"2035-12-01T14:00:00\",\"lot_quantity\":$CAPACIDADE,\"description\":\"smoke\"}]}" >/dev/null
EVENT_ID="$(psql_db "SELECT event_id FROM catalog.event WHERE event_name = 'Smoke33 $SUFIXO'" | tr -d '[:space:]')"
confere "evento criado no catalog" "sim" "$([ -n "$EVENT_ID" ] && echo sim || echo nao)"
confere "stock.eventcap.capacity do evento (criado pelo servico stock, #47)" "$CAPACIDADE" \
    "$(psql_db "SELECT capacity FROM stock.eventcap WHERE event_id = ${EVENT_ID:-0}" | tr -d '[:space:]')"

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
if [ -n "$ESPERADAS_201" ]; then
    confere "respostas 201 (reserva pending)" "$ESPERADAS_201" "$aceitas"
    confere "respostas 400 'Evento lotado'" "$((USUARIOS - ESPERADAS_201))" "$lotadas"
else
    ok "braco naive: $aceitas x 201, $lotadas x 'Evento lotado'"
    confere "201 + 'Evento lotado' = usuarios" "$USUARIOS" "$((aceitas + lotadas))"
fi

echo "==> 4. o banco (schemas reservation e stock) concorda (braco $BRACO)"
ativas="$(psql_db "SELECT count(*) FROM reservation.walletevent WHERE event_id = $EVENT_ID AND status IN ('pending','confirmed')" | tr -d '[:space:]')"
confere "reservas ativas em reservation.walletevent (= respostas 201)" "$aceitas" "$ativas"
oversell=$(( ${ativas:-0} > CAPACIDADE ? ${ativas:-0} - CAPACIDADE : 0 ))
echo "   OVERSELLING (ativas - capacidade) = $oversell"
[ "$BRACO" = naive ] || confere "overselling" 0 "$oversell"
# naive nao toca o eventcap (a disponibilidade vem da projecao): quant fica na capacidade
confere "stock.eventcap.quant" "$([ "$BRACO" = naive ] && echo "$CAPACIDADE" || echo 0)" \
    "$(psql_db "SELECT quant FROM stock.eventcap WHERE event_id = $EVENT_ID" | tr -d '[:space:]')"
# invariantes da saga stock + wallet (#47): o smoke e o e2e sao os unicos scripts que leem os dois schemas
confere "quant = capacidade - holds debitados (stock.hold held AND decremented)" 0 \
    "$(psql_db "SELECT count(*) FROM stock.eventcap ec WHERE ec.event_id = $EVENT_ID AND ec.capacity <> -1 AND ec.quant <> ec.capacity - (SELECT count(*) FROM stock.hold h WHERE h.event_id = ec.event_id AND h.status = 'held' AND h.decremented)" | tr -d '[:space:]')"
confere "nenhum walletevent ativo sem hold held com o mesmo hold_id" 0 \
    "$(psql_db "SELECT count(*) FROM reservation.walletevent w WHERE w.event_id = $EVENT_ID AND w.status IN ('pending','confirmed') AND NOT EXISTS (SELECT 1 FROM stock.hold h WHERE h.hold_id = w.hold_id AND h.status = 'held')" | tr -d '[:space:]')"
confere "nenhum hold held sem walletevent ativo nem saga em curso (vaga presa)" 0 \
    "$(psql_db "SELECT count(*) FROM stock.hold h WHERE h.event_id = $EVENT_ID AND h.status = 'held' AND NOT EXISTS (SELECT 1 FROM reservation.walletevent w WHERE w.hold_id = h.hold_id AND w.status IN ('pending','confirmed')) AND NOT EXISTS (SELECT 1 FROM reservation.idempotency_key k WHERE k.hold_id = h.hold_id AND k.saga_status IN ('reserving','compensating'))" | tr -d '[:space:]')"
confere "nenhuma saga reserving/compensating sobrando" 0 \
    "$(psql_db "SELECT count(*) FROM reservation.idempotency_key WHERE event_id = $EVENT_ID AND saga_status IN ('reserving','compensating')" | tr -d '[:space:]')"
confere "SlotReserved em reservation.event_store (event store DO SERVICO)" "$aceitas" \
    "$(psql_db "SELECT count(*) FROM reservation.event_store WHERE stream_type = 'reservation.wallet' AND event_type = 'SlotReserved' AND partition_key = '$EVENT_ID'" | tr -d '[:space:]')"
confere "o event store do monolito (public) NAO recebeu o stream reservation.wallet" 0 \
    "$(psql_db "SELECT count(*) FROM public.event_store WHERE stream_type = 'reservation.wallet' AND partition_key = '$EVENT_ID'" | tr -d '[:space:]')"

echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then echo "   RESULTADO: SMOKE OK (braco $BRACO, overselling = $oversell)"; else echo "   RESULTADO: $FALHAS ASSERCAO(OES) FALHARAM"; fi
echo "=================================================="
exit "$FALHAS"
