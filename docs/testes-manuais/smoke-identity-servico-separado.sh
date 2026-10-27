#!/bin/bash

# =============================================================================
#  smoke-identity-servico-separado.sh  -  issue #36 (Fase 7)
# =============================================================================
#
#  Smoke manual do servico `identity` SEPARADO (container gerenciador-identity), pela stack real: gateway (/bff/**),
#  salto HTTP gateway -> identity, JWKS, pooler e broker. Prova que
#    a) o cadastro em duas fases funciona (request-verification -> codigo lido do BANCO -> verify-email; codigo errado = 400
#       e conta tentativa); a carteira nasce no mesmo INSERT (identity.mywallet) e o `UserRegistered` entra no identity.event_store;
#    b) o token do cadastro e RS256, com `kid` presente no JWKS do identity e as claims `uid`/`isHost`/`iss`/`sub`;
#    c) login (200 / 401) e GET /bff/users/me pelo uid do token; bearer lixo em rota publica segue anonimo (200);
#    d) a API interna do identity (/internal/**) responde 401 sem X-Internal-Token (ou com um errado) e 200 com o token
#       certo; so a URI exata /.well-known/jwks.json dispensa o token; a porta 8084 NAO esta publicada no host;
#    e) reserva no braco `cas` com tokens emitidos pelo identity: capacidade 1 e 2 reservas simultaneas = 1 x 201 +
#       1 x "Evento lotado", overselling 0 (no braco naive so reporta);
#    f) com o container gerenciador-identity PARADO: login, cadastro e /bff/users/me -> 503 (nunca 401/500), mas a reserva
#       com um token valido segue 201 (o gateway valida pelo JWKS em cache, sem chamar o identity);
#    g) ao voltar, o login volta a 200;
#    h) `docker restart gerenciador-identity`: a chave de assinatura sobrevive (mesmo `kid` no JWKS) e o token antigo
#       continua valido;
#    i) o banco esta no schema do servico: public.users NAO existe e o outbox do identity escoa (relay ligado).
#  O script sai com codigo != 0 se qualquer assercao falhar. Roda 2x seguidas sem colidir (sufixo por execucao) e sempre
#  religa o identity (e o notification) na saida (trap), mesmo se abortar no meio.
#
#  E-MAIL: o cadastro pede um e-mail de verdade ao servico `notification` (SMTP real). Para nao mandar e-mail a cada
#  rodada, o script PARA o gerenciador-notification durante a execucao (PARAR_NOTIFICATION=1, default) e, na saida,
#  move o group `notification` para o fim do tópico identity.notification (como o run-tests.sh) antes de religa-lo. Trade-off: um
#  e-mail legitimo pendente antes do smoke e pulado. Se o seek falhar o notification NAO e religado e o comando aparece na saida.
#
#  Pre-requisito: a stack no ar COM o seed ligado (POST /bff/seed so existe com SEED_ENABLED=true, issue #35; e o jeito de
#  criar host e participante com senha sem o fluxo de e-mail). O override de carga liga:
#    docker compose -f Projeto/docker-compose.yml -f Projeto/carga/docker-compose.carga.yml up -d --build
#  Host: nenhuma rota promove usuario a host; o script promove o seu usuario de teste por SQL (UPDATE identity.users) ANTES
#  do login, porque `isHost` e lido do banco na EMISSAO do token.
#
#  Uso:  docs/testes-manuais/smoke-identity-servico-separado.sh
#  Variaveis (opcionais): BASE_URL (http://localhost:8081), CONTAINER_DB (gerenciador-db),
#    CONTAINER_BACKEND (gerenciador-backend), CONTAINER_IDENTITY (gerenciador-identity), IDENTITY_HOST (identity, nome na
#    rede gerenciador-net), IDENTITY_PORTA (8084), CONTAINER_NOTIFICATION (gerenciador-notification), CONTAINER_REDPANDA
#    (gerenciador-redpanda), PARAR_NOTIFICATION (1), MANTER_DADOS (1 = nao limpa).
#  Nenhum segredo aqui: o INTERNAL_API_TOKEN e lido DENTRO do container do backend (o valor nunca passa pelo host).
#  Compatibilidade: Linux/macOS e Git Bash do Windows (Docker Desktop).
# =============================================================================

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
CONTAINER_BACKEND="${CONTAINER_BACKEND:-gerenciador-backend}"
CONTAINER_IDENTITY="${CONTAINER_IDENTITY:-gerenciador-identity}"
CONTAINER_NOTIFICATION="${CONTAINER_NOTIFICATION:-gerenciador-notification}"
CONTAINER_REDPANDA="${CONTAINER_REDPANDA:-gerenciador-redpanda}"
IDENTITY_HOST="${IDENTITY_HOST:-identity}"
IDENTITY_PORTA="${IDENTITY_PORTA:-8084}"
PARAR_NOTIFICATION="${PARAR_NOTIFICATION:-1}"
MANTER_DADOS="${MANTER_DADOS:-0}"
BRACO="${BRACO:-$(docker exec "$CONTAINER_BACKEND" printenv RESERVATION_READPATH 2>/dev/null | tr -d '[:space:]')}"
BRACO="${BRACO:-cas}"
SUFIXO="$(date +%s)-$RANDOM"
EMAIL_NOVO="smoke36-$SUFIXO-novo@example.com"
EMAIL_HOST="smoke36-$SUFIXO-host@example.com"
EMAIL_PART="smoke36-$SUFIXO-part@example.com"
FONE_NOVO="11$(printf '%09d' $(( (RANDOM * 32768 + RANDOM) % 1000000000 )))"
NOME_EVENTO_A="Smoke36 $SUFIXO A"
NOME_EVENTO_B="Smoke36 $SUFIXO B"
TMP="$(mktemp -d)"
FALHAS=0
IDENTITY_PARADO=0
NOTIFICATION_PARADO=0

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
extrair_token() { grep -o '"token":"[^"]*"' "$1" | head -1 | cut -d'"' -f4; }

# Decodifica uma parte do JWT (1 = header, 2 = payload): base64url -> base64 (com padding) -> texto.
jwt_parte() {
    local p
    p="$(printf '%s' "$1" | cut -d. -f"$2" | tr '_-' '/+')"
    while [ $(( ${#p} % 4 )) -ne 0 ]; do p="${p}="; done
    printf '%s' "$p" | base64 -d 2>/dev/null
}

# Resposta HTTP CRUA (linha de status, cabecalhos e corpo) de um GET na API interna do identity, feito DE DENTRO do
# container do backend (a porta nao esta no host e a imagem nao tem curl: bash /dev/tcp, como o healthcheck).
# $1 = caminho; $2 = sem | errado | certo (token). O token certo e expandido no container: as aspas simples impedem que o
# host o veja.
REQ_INTERNA='exec 3<>"/dev/tcp/$1/$2" || exit 1
printf "GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n" "$3" "$1" >&3
case "$4" in
    certo)  printf "X-Internal-Token: %s\r\n" "$INTERNAL_API_TOKEN" >&3 ;;
    errado) printf "X-Internal-Token: token-errado\r\n" >&3 ;;
esac
printf "\r\n" >&3
cat <&3'
resposta_interna() {
    dexec exec "$CONTAINER_BACKEND" timeout 10 bash -c "$REQ_INTERNA" bash "$IDENTITY_HOST" "$IDENTITY_PORTA" "$1" "$2" 2>/dev/null
}
status_interno() {
    resposta_interna "$1" "$2" | head -1 | awk '{print $2}' | tr -d '[:space:]'
}

# espera o container ficar healthy (ate ~120 s)
esperar_saudavel() {
    for i in $(seq 1 60); do
        [ "$(dexec inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null)" = "healthy" ] && return 0
        sleep 2
    done
    return 1
}

limpar() {
    if [ "$IDENTITY_PARADO" = "1" ]; then
        echo "==> (saida) religando $CONTAINER_IDENTITY"
        dexec start "$CONTAINER_IDENTITY" >/dev/null 2>&1 || echo "   [ERRO]  nao consegui religar $CONTAINER_IDENTITY: docker start $CONTAINER_IDENTITY"
    fi
    if [ "$MANTER_DADOS" != "1" ]; then
        # sem FK entre schemas: a ordem nao importa. O mywallet cai por CASCADE com o usuario; os streams identity.user /
        # identity.notification e catalog.event ficam no event store (log imutavel; a identity de user_id/event_id nunca
        # recicla o id, entao o stream orfao e inofensivo).
        psql_db "DELETE FROM reservation.walletevent WHERE event_id IN (SELECT event_id FROM catalog.event WHERE event_name LIKE 'Smoke36 $SUFIXO %');
                 DELETE FROM reservation.eventcap WHERE event_id IN (SELECT event_id FROM catalog.event WHERE event_name LIKE 'Smoke36 $SUFIXO %');
                 DELETE FROM catalog.event WHERE event_name LIKE 'Smoke36 $SUFIXO %';
                 DELETE FROM identity.pending_verifications WHERE email LIKE 'smoke36-$SUFIXO-%@example.com';
                 DELETE FROM identity.users WHERE email LIKE 'smoke36-$SUFIXO-%@example.com';" >/dev/null 2>&1 || true
    fi
    if [ "$NOTIFICATION_PARADO" = "1" ]; then
        # os pedidos de e-mail deste smoke estao no topico: pula-os ANTES de religar (so da seek com o group sem membro vivo)
        seek_ok=0
        for i in 1 2 3 4 5 6; do
            if dexec exec "$CONTAINER_REDPANDA" rpk group seek notification --to end --topics identity.notification --allow-new-topics >/dev/null 2>&1; then
                seek_ok=1; break
            fi
            sleep 5
        done
        if [ "$seek_ok" = 1 ]; then
            echo "==> (saida) group notification no fim de identity.notification; religando $CONTAINER_NOTIFICATION"
            dexec start "$CONTAINER_NOTIFICATION" >/dev/null 2>&1 || echo "   [ERRO]  nao consegui religar $CONTAINER_NOTIFICATION"
        else
            echo "   [ERRO]  seek do group notification falhou; $CONTAINER_NOTIFICATION NAO foi religado (mandaria e-mail real do smoke). Rode:"
            echo "           docker exec $CONTAINER_REDPANDA rpk group seek notification --to end --topics identity.notification --allow-new-topics"
            echo "           docker start $CONTAINER_NOTIFICATION"
        fi
    fi
    rm -rf "$TMP"
}
trap limpar EXIT

echo "=================================================="
echo "   SMOKE #36: identity como servico separado"
echo "   BASE_URL=$BASE_URL  IDENTITY=$CONTAINER_IDENTITY ($IDENTITY_HOST:$IDENTITY_PORTA)  BRACO=$BRACO  SUFIXO=$SUFIXO"
echo "=================================================="

echo "==> 0. stack no ar?"
for i in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health")" = "200" ] && break
    sleep 2
done
confere "GET /actuator/health (backend)" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health")"
confere "container $CONTAINER_IDENTITY saudavel" healthy "$(dexec inspect -f '{{.State.Health.Status}}' "$CONTAINER_IDENTITY" 2>/dev/null)"
[ "$FALHAS" -eq 0 ] || abortar "stack fora do ar: nao ha o que provar"
if [ "$PARAR_NOTIFICATION" = "1" ] && [ "$(dexec inspect -f '{{.State.Running}}' "$CONTAINER_NOTIFICATION" 2>/dev/null)" = "true" ]; then
    NOTIFICATION_PARADO=1
    dexec stop "$CONTAINER_NOTIFICATION" >/dev/null
    ok "$CONTAINER_NOTIFICATION parado durante o smoke (nenhum e-mail real; religado na saida)"
fi

echo "==> a) cadastro em duas fases (o codigo vem do BANCO, o e-mail nao e lido)"
code="$(curl -s -o "$TMP/req.json" -w '%{http_code}' -X POST "$BASE_URL/bff/users/request-verification" -H 'Content-Type: application/json' \
    -d "{\"name\":\"Smoke36 Novo $SUFIXO\",\"email\":\"$EMAIL_NOVO\",\"password\":\"senha123\",\"fone\":\"$FONE_NOVO\",\"birthdate\":\"1990-01-15\"}")"
confere "POST /bff/users/request-verification" 201 "$code"
CODIGO="$(psql_db "SELECT verification_code FROM identity.pending_verifications WHERE email = '$EMAIL_NOVO'" | tr -d '[:space:]')"
confere "codigo de 6 digitos em identity.pending_verifications" sim "$([[ "$CODIGO" =~ ^[0-9]{6}$ ]] && echo sim || echo nao)"
confere "VerificationCodeRequested no identity.event_store (stream identity.notification)" sim \
    "$([ "$(psql_db "SELECT count(*) FROM identity.event_store WHERE stream_type = 'identity.notification' AND event_type = 'VerificationCodeRequested' AND payload::text LIKE '%$EMAIL_NOVO%'" | tr -d '[:space:]')" -ge 1 ] && echo sim || echo nao)"
[ "$FALHAS" -eq 0 ] || abortar "sem codigo de verificacao nao ha cadastro"
ERRADO="111111"; [ "$CODIGO" = "$ERRADO" ] && ERRADO="222222"
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/bff/users/verify-email" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL_NOVO\",\"verificationCode\":\"$ERRADO\"}")"
confere "POST /bff/users/verify-email com codigo ERRADO" 400 "$code"
confere "identity.pending_verifications.attempts depois do erro" 1 \
    "$(psql_db "SELECT attempts FROM identity.pending_verifications WHERE email = '$EMAIL_NOVO'" | tr -d '[:space:]')"
code="$(curl -s -o "$TMP/verify.json" -w '%{http_code}' -X POST "$BASE_URL/bff/users/verify-email" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL_NOVO\",\"verificationCode\":\"$CODIGO\"}")"
confere "POST /bff/users/verify-email com o codigo certo" 201 "$code"
TOKEN_NOVO="$(extrair_token "$TMP/verify.json")"
UID_NOVO="$(psql_db "SELECT user_id FROM identity.users WHERE email = '$EMAIL_NOVO'" | tr -d '[:space:]')"
confere "usuario criado em identity.users e token devolvido" sim "$([ -n "$UID_NOVO" ] && [ -n "$TOKEN_NOVO" ] && echo sim || echo nao)"
[ "$FALHAS" -eq 0 ] || abortar "sem usuario/token nao ha o que provar"
confere "carteira criada no mesmo INSERT (identity.mywallet)" 1 "$(psql_db "SELECT count(*) FROM identity.mywallet WHERE user_id = $UID_NOVO" | tr -d '[:space:]')"
confere "UserRegistered no identity.event_store (stream identity.user)" 1 \
    "$(psql_db "SELECT count(*) FROM identity.event_store WHERE stream_type = 'identity.user' AND stream_id = '$UID_NOVO' AND event_type = 'UserRegistered'" | tr -d '[:space:]')"

echo "==> b) token RS256: header (alg, kid no JWKS) e claims (uid, isHost, iss, sub)"
HEADER="$(jwt_parte "$TOKEN_NOVO" 1)"
PAYLOAD="$(jwt_parte "$TOKEN_NOVO" 2)"
KID="$(printf '%s' "$HEADER" | grep -oP '"kid"\s*:\s*"\K[^"]+' | head -1)"
confere "header alg" sim "$(printf '%s' "$HEADER" | grep -qP '"alg"\s*:\s*"RS256"' && echo sim || echo nao)"
confere "header kid presente" sim "$([ -n "$KID" ] && echo sim || echo nao)"
confere "claim uid = user_id do banco" "$UID_NOVO" "$(printf '%s' "$PAYLOAD" | grep -oP '"uid"\s*:\s*\K[0-9]+' | head -1)"
confere "claim isHost (usuario novo nao e host)" sim "$(printf '%s' "$PAYLOAD" | grep -qP '"isHost"\s*:\s*false' && echo sim || echo nao)"
confere "claim iss" sim "$(printf '%s' "$PAYLOAD" | grep -qP '"iss"\s*:\s*"gerenciador-identity"' && echo sim || echo nao)"
confere "claim sub = e-mail" sim "$(printf '%s' "$PAYLOAD" | grep -qF "\"sub\":\"$EMAIL_NOVO\"" && echo sim || echo nao)"
JWKS_ANTES="$(resposta_interna "/.well-known/jwks.json" sem)"
confere "kid do token esta no JWKS do identity" sim "$(printf '%s' "$JWKS_ANTES" | grep -qP "\"kid\"\\s*:\\s*\"$KID\"" && echo sim || echo nao)"
confere "o JWKS so tem a parte publica (sem o expoente privado 'd')" sim "$(printf '%s' "$JWKS_ANTES" | grep -qP '"d"\s*:' && echo nao || echo sim)"

echo "==> c) login, /me e bearer lixo"
code="$(curl -s -o "$TMP/login.json" -w '%{http_code}' -X POST "$BASE_URL/bff/users/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL_NOVO\",\"password\":\"senha123\"}")"
confere "POST /bff/users/login" 200 "$code"
TOKEN_LOGIN="$(extrair_token "$TMP/login.json")"
confere "token do login com o mesmo uid" "$UID_NOVO" "$(jwt_parte "$TOKEN_LOGIN" 2 | grep -oP '"uid"\s*:\s*\K[0-9]+' | head -1)"
confere "POST /bff/users/login com senha errada" 401 \
    "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/bff/users/login" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL_NOVO\",\"password\":\"errada123\"}")"
code="$(curl -s -o "$TMP/me.json" -w '%{http_code}' "$BASE_URL/bff/users/me" -H "Authorization: Bearer $TOKEN_LOGIN")"
confere "GET /bff/users/me com o token" 200 "$code"
confere "/me devolve o usuario do uid" sim "$(grep -qF "\"$EMAIL_NOVO\"" "$TMP/me.json" && echo sim || echo nao)"
confere "GET /bff/users/me sem token" 401 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/bff/users/me")"
confere "GET /bff/events/search?term= com bearer lixo (rota publica segue anonima)" 200 \
    "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/bff/events/search?term=smoke36" -H 'Authorization: Bearer isto.nao.e-jwt')"
confere "GET /bff/users/me com bearer lixo (rota protegida)" 401 \
    "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/bff/users/me" -H 'Authorization: Bearer isto.nao.e-jwt')"

echo "==> d) API interna do identity: so com X-Internal-Token, sem porta no host"
confere "GET /internal/users/$UID_NOVO/exists SEM token" 401 "$(status_interno "/internal/users/$UID_NOVO/exists" sem)"
confere "GET /internal/users/$UID_NOVO/exists com token ERRADO" 401 "$(status_interno "/internal/users/$UID_NOVO/exists" errado)"
confere "GET /internal/users/$UID_NOVO/exists com o token certo" 200 "$(status_interno "/internal/users/$UID_NOVO/exists" certo)"
confere "GET /.well-known/jwks.json SEM token (isento so por esta URI)" 200 "$(status_interno "/.well-known/jwks.json" sem)"
confere "GET /.well-known/jwks.json;x SEM token" 401 "$(status_interno "/.well-known/jwks.json;x" sem)"
confere "portas publicadas no host por $CONTAINER_IDENTITY (docker port)" "" "$(dexec port "$CONTAINER_IDENTITY" 2>/dev/null)"
confere "http://localhost:$IDENTITY_PORTA a partir do host (000 = sem conexao)" 000 \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$IDENTITY_PORTA/.well-known/jwks.json")"

echo "==> e) reserva no braco $BRACO com tokens do identity (host por SQL, participante pelo seed)"
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/bff/seed" -H 'Content-Type: application/json' \
    -d "{\"users\":[{\"name\":\"Smoke36 Host $SUFIXO\",\"email\":\"$EMAIL_HOST\",\"password\":\"senha123\",\"fone\":\"s36h-$SUFIXO\",\"birthDate\":\"1990-01-15\"},{\"name\":\"Smoke36 Part $SUFIXO\",\"email\":\"$EMAIL_PART\",\"password\":\"senha123\",\"fone\":\"s36p-$SUFIXO\",\"birthDate\":\"1990-01-15\"}]}")"
if [ "$code" = "404" ]; then
    abortar "POST /bff/seed = 404: o seed esta desligado (SEED_ENABLED). Suba com o override de carga:
            docker compose -f Projeto/docker-compose.yml -f Projeto/carga/docker-compose.carga.yml up -d"
fi
confere "POST /bff/seed (host e participante)" 200 "$code"
psql_db "UPDATE identity.users SET host = true WHERE email = '$EMAIL_HOST'" >/dev/null
for papel in host part; do
    curl -s -o "$TMP/login-$papel.json" -X POST "$BASE_URL/bff/users/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"smoke36-$SUFIXO-$papel@example.com\",\"password\":\"senha123\"}"
    extrair_token "$TMP/login-$papel.json" >"$TMP/token-$papel"
done
UID_PART="$(extrair_num user_id "$TMP/login-part.json")"
confere "login do host e do participante (tokens)" sim "$([ -s "$TMP/token-host" ] && [ -s "$TMP/token-part" ] && [ -n "$UID_PART" ] && echo sim || echo nao)"
[ "$FALHAS" -eq 0 ] || abortar "sem tokens nao ha o que provar"
confere "claim isHost do host (lido do banco na emissao)" sim "$(jwt_parte "$(cat "$TMP/token-host")" 2 | grep -qP '"isHost"\s*:\s*true' && echo sim || echo nao)"
for nome_cap in "A:1:$NOME_EVENTO_A" "B:5:$NOME_EVENTO_B"; do
    letra="${nome_cap%%:*}"; resto="${nome_cap#*:}"; cap="${resto%%:*}"; nome="${resto#*:}"
    code="$(curl -s -o "$TMP/evento-$letra.json" -w '%{http_code}' -X POST "$BASE_URL/bff/events" \
        -H "Authorization: Bearer $(cat "$TMP/token-host")" -H 'Content-Type: application/json' \
        -d "{\"event_name\":\"$nome\",\"is_EAD\":true,\"event_date\":\"2035-12-01T14:00:00\",\"description\":\"smoke #36\",\"lot_quantity\":$cap,\"presenters\":[]}")"
    confere "POST /bff/events $letra (capacidade $cap, host autenticado pelo token do identity)" 201 "$code"
    eval "EVENT_$letra=\"$(extrair_num event_id "$TMP/evento-$letra.json")\""
done
[ -n "$EVENT_A" ] && [ -n "$EVENT_B" ] || abortar "nao consegui extrair os event_id"
reservar() { # $1 = arquivo do token, $2 = userId, $3 = eventId, $4 = prefixo do arquivo de saida
    curl -s -o "$TMP/$4-corpo" -w '%{http_code}' --max-time 30 -X POST "$BASE_URL/bff/event-wallets" \
        -H "Authorization: Bearer $(cat "$1")" -H 'Content-Type: application/json' \
        -d "{\"userId\":$2,\"eventId\":$3}" >"$TMP/$4-status"
}
printf '%s' "$TOKEN_LOGIN" >"$TMP/token-novo"
reservar "$TMP/token-novo" "$UID_NOVO" "$EVENT_A" r1 &
reservar "$TMP/token-part" "$UID_PART" "$EVENT_A" r2 &
wait
aceitas=0; lotadas=0
for r in r1 r2; do
    st="$(cat "$TMP/$r-status")"
    if [ "$st" = "201" ]; then aceitas=$((aceitas + 1)); elif [ "$st" = "400" ] && grep -qi "lotado" "$TMP/$r-corpo"; then lotadas=$((lotadas + 1)); fi
done
ativas="$(psql_db "SELECT count(*) FROM reservation.walletevent WHERE event_id = $EVENT_A AND status IN ('pending','confirmed')" | tr -d '[:space:]')"
oversell=$(( ${ativas:-0} > 1 ? ${ativas:-0} - 1 : 0 ))
echo "   OVERSELLING (ativas - capacidade) = $oversell  (201=$aceitas, lotado=$lotadas)"
if [ "$BRACO" = naive ]; then
    ok "braco naive (controle): overselling so reportado"
else
    confere "respostas 201 (capacidade 1)" 1 "$aceitas"
    confere "respostas 400 'Evento lotado'" 1 "$lotadas"
    confere "overselling" 0 "$oversell"
fi
confere "reservas ativas em reservation.walletevent (= respostas 201)" "$aceitas" "$ativas"

echo "==> f) $CONTAINER_IDENTITY PARADO: 503 no que depende dele, reserva com token valido segue (JWKS em cache)"
IDENTITY_PARADO=1
dexec stop "$CONTAINER_IDENTITY" >/dev/null
confere "POST /bff/users/login com o identity parado" 503 \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X POST "$BASE_URL/bff/users/login" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL_NOVO\",\"password\":\"senha123\"}")"
confere "POST /bff/users/request-verification com o identity parado" 503 \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X POST "$BASE_URL/bff/users/request-verification" -H 'Content-Type: application/json' \
        -d "{\"name\":\"Smoke36 Fora $SUFIXO\",\"email\":\"smoke36-$SUFIXO-fora@example.com\",\"password\":\"senha123\",\"fone\":\"11999990000\",\"birthdate\":\"1990-01-15\"}")"
confere "GET /bff/users/me com o identity parado (token valido)" 503 \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$BASE_URL/bff/users/me" -H "Authorization: Bearer $TOKEN_LOGIN")"
confere "GET /bff/events/search?term= com o identity parado (nao depende dele)" 200 "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$BASE_URL/bff/events/search?term=smoke36")"
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X POST "$BASE_URL/bff/event-wallets" \
    -H "Authorization: Bearer $TOKEN_LOGIN" -H 'Content-Type: application/json' -d "{\"userId\":$UID_NOVO,\"eventId\":$EVENT_B}")"
confere "POST /bff/event-wallets com o identity parado (token valido pelo JWKS em cache)" 201 "$code"
confere "GET /bff/users/me sem token e sem identity continua 401 (nunca 503)" 401 \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$BASE_URL/bff/users/me")"

echo "==> g) $CONTAINER_IDENTITY de volta: recuperacao"
dexec start "$CONTAINER_IDENTITY" >/dev/null
esperar_saudavel "$CONTAINER_IDENTITY"
IDENTITY_PARADO=0
confere "container $CONTAINER_IDENTITY saudavel de novo" healthy "$(dexec inspect -f '{{.State.Health.Status}}' "$CONTAINER_IDENTITY" 2>/dev/null)"
# sem retry no cliente: uma conexao velha do pool pode dar um 503 isolado logo apos a volta
for i in $(seq 1 15); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X POST "$BASE_URL/bff/users/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"$EMAIL_NOVO\",\"password\":\"senha123\"}")"
    [ "$code" = "200" ] && break
    sleep 2
done
confere "POST /bff/users/login depois da volta" 200 "$code"

echo "==> h) docker restart $CONTAINER_IDENTITY: a chave de assinatura sobrevive (mesmo kid), token antigo valido"
dexec restart "$CONTAINER_IDENTITY" >/dev/null
esperar_saudavel "$CONTAINER_IDENTITY"
confere "container $CONTAINER_IDENTITY saudavel depois do restart" healthy "$(dexec inspect -f '{{.State.Health.Status}}' "$CONTAINER_IDENTITY" 2>/dev/null)"
JWKS_DEPOIS="$(resposta_interna "/.well-known/jwks.json" sem)"
confere "o kid do token antigo continua no JWKS (chave persistida no volume)" sim "$(printf '%s' "$JWKS_DEPOIS" | grep -qP "\"kid\"\\s*:\\s*\"$KID\"" && echo sim || echo nao)"
for i in $(seq 1 15); do
    code="$(curl -s -o "$TMP/me-depois.json" -w '%{http_code}' --max-time 30 "$BASE_URL/bff/users/me" -H "Authorization: Bearer $TOKEN_NOVO")"
    [ "$code" = "200" ] && break
    sleep 2
done
confere "GET /bff/users/me com o token ANTIGO (emitido antes do restart)" 200 "$code"

echo "==> i) o banco esta no schema do servico e o outbox escoa (relay)"
confere "to_regclass('public.users') IS NULL" t "$(psql_db "SELECT to_regclass('public.users') IS NULL" | tr -d '[:space:]')"
confere "EVENTS_RELAY_ENABLED no $CONTAINER_IDENTITY" true "$(dexec exec "$CONTAINER_IDENTITY" printenv EVENTS_RELAY_ENABLED 2>/dev/null | tr -d '[:space:]')"
pendentes=""
for i in $(seq 1 30); do
    pendentes="$(psql_db "SELECT count(*) FROM identity.event_outbox o JOIN identity.event_store s USING (global_position)
                         WHERE s.stream_type = 'identity.user' AND s.stream_id = '$UID_NOVO'" | tr -d '[:space:]')"
    [ "$pendentes" = "0" ] && break
    sleep 2
done
confere "linhas do usuario ainda no identity.event_outbox (ate 60 s)" 0 "$pendentes"

echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then echo "   RESULTADO: SMOKE OK (identity separado)"; else echo "   RESULTADO: $FALHAS ASSERCAO(OES) FALHARAM"; fi
echo "=================================================="
exit "$FALHAS"
