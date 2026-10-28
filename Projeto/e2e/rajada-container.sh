#!/bin/bash
# Rajada concorrente do passo 4b do run-e2e.sh (issue #43). Roda DENTRO do container auxiliar do e2e (imagem Maven: bash, curl, xargs), na
# rede do project temporario, e fala com o gateway por HTTP. Lida pelo run-e2e.sh por `docker exec -i ... env ... bash -s < este arquivo`;
# nada daqui sai do project.
#
# Entrada (variáveis de ambiente): GW (http://backend:8081), EVENT_ID, SUFIXO, N (usuários), PARALELO (processos curl simultâneos na
# rajada). Os N usuários (e-mail rajada-$SUFIXO-<i>@example.com, senha senha123) já existem (SQL do run-e2e.sh).
#
# Saída (linhas que o run-e2e.sh lê): "CODIGO <http> <qtd>" por código da rajada, "ESTADO <status> <qtd>" por estado final (os 201 contam
# como pending; os 400 sem fila, como rejected; os 202 vêm da consulta GET /bff/event-wallets/admission), "SEM_ESTADO_FINAL <qtd>" se alguém
# não chegou a um estado final em 90 s, "RAJADA_MS <ms>" (do primeiro ao último POST) e "TEMPO_TOTAL_S <s>" (do script inteiro).
set -u
: "${GW:?}" "${EVENT_ID:?}" "${SUFIXO:?}" "${N:?}" "${PARALELO:?}"
DIR="$(mktemp -d)"
export GW EVENT_ID SUFIXO DIR

extrair() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
export -f extrair

login() {
    local i="$1" r
    r="$(curl -s -m 30 -X POST "$GW/bff/users/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"rajada-$SUFIXO-$i@example.com\",\"password\":\"senha123\"}")"
    echo "$r" | extrair token >"$DIR/tok.$i"
}
export -f login

disparar() {
    local i="$1" token
    token="$(cat "$DIR/tok.$i")"
    curl -s -m 60 -o "$DIR/corpo.$i" -w '%{http_code}\n' -X POST "$GW/bff/event-wallets" -H 'Content-Type: application/json' \
        -H "Authorization: Bearer $token" -H "Idempotency-Key: rajada-$SUFIXO-$i" -d "{\"eventId\":$EVENT_ID}" >"$DIR/codigo.$i"
}
export -f disparar

consultar() { # espera um estado final (Retry-After: 1) por até 90 s
    local i="$1" token estado fim
    token="$(cat "$DIR/tok.$i")"
    fim=$((SECONDS + 90))
    while [ "$SECONDS" -lt "$fim" ]; do
        estado="$(curl -s -m 10 -G "$GW/bff/event-wallets/admission" --data-urlencode "idempotencyKey=rajada-$SUFIXO-$i" \
            -H "Authorization: Bearer $token" | extrair status)"
        case "$estado" in
            pending|rejected|timed_out) echo "$estado" >"$DIR/estado.$i"; return ;;
        esac
        sleep 1
    done
    echo "sem_estado_final" >"$DIR/estado.$i"
}
export -f consultar

seq 1 "$N" | xargs -P 16 -I{} bash -c 'login {}'
for i in $(seq 1 "$N"); do
    [ -s "$DIR/tok.$i" ] || { echo "ERRO sem token do usuario $i (login)"; exit 1; }
done

T0="$(date +%s%3N)"
seq 1 "$N" | xargs -P "$PARALELO" -I{} bash -c 'disparar {}'
T1="$(date +%s%3N)"
echo "RAJADA_MS $((T1 - T0))"
cat "$DIR"/codigo.* | sort | uniq -c | awk '{print "CODIGO", $2, $1}'

# os 202 consultam o estado; os 201 (sincronos) e os 400/422/429 não passam pela fila
for i in $(seq 1 "$N"); do
    case "$(cat "$DIR/codigo.$i")" in
        202) echo "$i" ;;
        201) echo pending >"$DIR/estado.$i" ;;
        400) echo rejected >"$DIR/estado.$i" ;;
        429) echo rejeitado_429 >"$DIR/estado.$i" ;;
        *) echo "outro" >"$DIR/estado.$i" ;;
    esac
done >"$DIR/enfileirados.txt"
xargs -P 64 -I{} bash -c 'consultar {}' <"$DIR/enfileirados.txt"
echo "TEMPO_TOTAL_S $SECONDS"
cat "$DIR"/estado.* | sort | uniq -c | awk '{print "ESTADO", $2, $1}'
echo "SEM_ESTADO_FINAL $(grep -lx sem_estado_final "$DIR"/estado.* 2>/dev/null | wc -l)"
rm -rf "$DIR"
