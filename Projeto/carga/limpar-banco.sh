#!/usr/bin/env bash
# =============================================================================
#  limpar-banco.sh -- limpa o HOST entre blocos de carga (versionado na issue #178; antes vivia fora do repo)
# =============================================================================
#  Roda no HOST (Git Bash ou Linux), com a stack no ar (base OU braco C com a autoescala). Uso:  Projeto/carga/limpar-banco.sh   (make limpar-banco)
#  Env: PROJETO (gerenciador-eventos: project do compose), BASE_URL (http://localhost:8081: entrada do gateway, a porta do host ou o lb),
#       ESPERA_HEALTHY_S (180), ESPERA_SCALER_S (20), SONDA_TETO_S (150), SONDA_LIGADA (1; 0 pula o login de sonda).
#
#  O QUE FAZ, nesta ordem:
#   0. Confere ANTES de parar qualquer coisa: o SCALER da autoescala esta PARADO (ver abaixo) e a rotacao da chave do JWT e possivel: se o volume
#      ${PROJETO}_identity-keys existe, a chave SERA apagada (haja ou nao replica do identity em execucao: um identity que caiu e volta depois subiria
#      com a chave antiga) e a imagem para apagar o arquivo ja tem de estar local; se o volume NAO existe e ha container do identity no project (parado
#      inclusive), RECUSA (project errado?).
#   1. Anota os containers EM EXECUCAO de cada servico por LABEL do compose (com.docker.compose.project + .service): com a autoescala as
#      replicas se chamam <project>-<servico>-<n>, nao gerenciador-<servico>. Stand-by inclusive. db, redpanda, redis e rabbitmq seguem com nome fixo.
#   2. Para backend, admission, reservation, catalog, stock (#47), identity (TODAS as replicas) e o notification, se no ar.
#   3. Apaga a chave de assinatura do JWT do volume ${PROJETO}_identity-keys (o nome do volume deriva do project; um container descartavel o monta;
#      so quando o volume existe, ver o passo 0).
#      O TRUNCATE ... RESTART IDENTITY recicla o user_id e o JWT e validado sem estado (claim uid): um token de antes valeria como o usuario novo de mesmo
#      uid. Se nao conseguir apagar, ABORTA sem religar nada (religar com a chave antiga deixaria os tokens antigos valendo).
#   4. Move os consumer groups reservation / stock (reservation.wallet) / stock-catalog (catalog.event) / notification para o fim dos topicos (rpk group
#      seek), trunca public, reservation, stock (#47), catalog e identity numa UNICA instrucao, da FLUSHDB no Redis e faz purge das filas admission.* do RabbitMQ (FLUSHDB sem OK ou purge que falha
#      ABORTA: o bloco seguinte herdaria modo/contagem/espera dos ids reciclados; o que o script parou e religado).
#   5. Sobe de volta, so o que o PROPRIO script parou (docker start nos mesmos containers: preserva o ambiente da rodada, o --scale e o -f do up; e
#      diferente de reaproveitar um container antigo de outra rodada), esperando todas as replicas healthy em cada etapa: TODAS as replicas do identity,
#      depois o stock (#47: o reservation o espera healthy), depois reservation e catalog, depois admission, depois TODAS as replicas do backend (cache de JWKS vazio), e por fim o notification.
#   6. Janela do JWKS no boot: o gateway so tenta o JWKS de novo a cada 30 s. Com o lb no ar (braco C) espera os slots do identity (e do backend) UP no
#      lb; depois faz um LOGIN DE SONDA (usuario criado por POST /bff/seed, que o override de carga liga) e chama um endpoint autenticado do gateway
#      varias vezes (round-robin pelas replicas) ate nenhuma dar 401: isso prova o JWKS em cache em TODAS as replicas do gateway. A sonda deixa 1
#      usuario no banco (o kit semeia os dele com e-mails unicos); SONDA_LIGADA=0 pula. Sem /bff/seed (404) so avisa.
#
#  MODO CLUSTER (issue #228): com CARGA_K8S=<contexto kubectl> (ex.: kind-gerenciador) o mesmo roteiro roda contra o cluster Kubernetes (Projeto/k8s), sem Docker. Sem a variavel
#  nada disto e carregado. Todo kubectl leva --context $CARGA_K8S (nunca o current-context), namespace gerenciador. Equivalencias: parar/religar = pausar o ScaledObject do KEDA
#  (autoscaling.keda.sh/paused-replicas=0, senao o KEDA sobe o pod de volta) + scale 0 / voltar as replicas de antes, esperando prontas; a chave do JWT = um pod descartavel que monta o PVC
#  identity-keys e apaga o arquivo (identity e backend parados antes); psql = sts/db (db no cluster) ou pod cliente db-cliente (db remoto, senha lida DENTRO do pod: k8s/lib-db-cliente.sh);
#  rpk/redis-cli/rabbitmqctl por `kubectl exec`; o lb nao existe (a entrada e o NodePort da BASE_URL). O scaler.sh nao existe no cluster (a autoescala e o KEDA, que o roteiro pausa).
#
#  O SCALER TEM DE ESTAR PARADO. Com ele rodando, uma replica parada fica abaixo do minimo e ele faz `compose up --scale` no meio do TRUNCATE. Parar o
#  scaler = Ctrl+C no terminal dele. O script detecta pelo Redis: um scaler (sem --seco) renova a chave adm:replicas (TTL 15 s) a cada laco; se ela
#  existe, espera ESPERA_SCALER_S (> TTL) e, se continuar existindo, RECUSA rodar. Suba o scaler de novo DEPOIS (ele recalcula do estado atual).
#  Nao mexe no schema notification nem no topico identity.notification (evitar e-mail real): o seek do group notification so pula o que ja estava no topico.
# =============================================================================
set -uo pipefail
unset MSYS_NO_PATHCONV   # herdado (Git Bash) quebra o curl.exe da sonda: o corpo vai para outro caminho e o mktemp fica vazio ("login sem token" com HTTP 200); o docker tem a conversao desligada no `dk`

PROJETO="${PROJETO:-gerenciador-eventos}"
BASE_URL="${BASE_URL:-http://localhost:8081}"
ESPERA_HEALTHY_S="${ESPERA_HEALTHY_S:-180}"
ESPERA_SCALER_S="${ESPERA_SCALER_S:-20}"
SONDA_TETO_S="${SONDA_TETO_S:-150}"
SONDA_LIGADA="${SONDA_LIGADA:-1}"
# modo cluster (#228): a lib do pod cliente do banco (so carregada com CARGA_K8S; K8S_LIB muda o caminho, o selftest copia o script)
# shellcheck source=../k8s/lib-db-cliente.sh
[ -z "${CARGA_K8S:-}" ] || . "${K8S_LIB:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../k8s/lib-db-cliente.sh}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
CONTAINER_RPK="${CONTAINER_RPK:-gerenciador-redpanda}"
CONTAINER_REDIS="${CONTAINER_REDIS:-gerenciador-redis}"
CONTAINER_RABBIT="${CONTAINER_RABBIT:-gerenciador-rabbitmq}"
VOLUME_CHAVES="${PROJETO}_identity-keys"
ARQUIVO_CHAVE="jwt-signing-key.json"
# ordem de subida; a de parada e a inversa (backend primeiro). admission depende do reservation; o backend, de identity/catalog/reservation; o reservation, do stock (#47)
SERVICOS="identity stock reservation catalog admission backend"

dk() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@"; }
log() { printf '%s [limpar] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }

declare -A IDS=()        # servico -> ids (12 caracteres) dos containers que ESTE script parou
NOTIF_IDS=""
PARADOS=""               # ids parados por este script (para religar se abortar)
NAO_RELIGAR=0
TEM_VOLUME=0             # o volume da chave do JWT existe (passo 0): a chave sera apagada
IMG_CHAVE=

# --- puras (o selftest as cobre) ---------------------------------------------------------------------------------------------------------------

# contar_up <pxname>  (stdin = `show stat` do HAProxy, CSV com o cabecalho "# pxname,svname,...") -> nº de slots UP do backend (FRONTEND/BACKEND ignorados)
contar_up() {
    awk -F, -v px="$1" '
        /^# / { for (i = 1; i <= NF; i++) { n = $i; sub(/^# /, "", n); col[n] = i } next }
        $1 == px && $2 != "BACKEND" && $2 != "FRONTEND" && ("status" in col) && $(col["status"]) == "UP" { c++ }
        END { print c + 0 }'
}

# token_do_login  (stdin = corpo do POST /bff/users/login) -> o JWT, ou vazio
token_do_login() { grep -o '"token" *: *"[^"]*"' | head -1 | sed 's/.*: *"\(.*\)"/\1/'; }

# ids_do_servico <servico> [-a]  -> ids dos containers EM EXECUCAO do servico no project (por label); com -a, tambem os parados
ids_do_servico() {
    dk ps -q ${2:-} --filter "label=com.docker.compose.project=$PROJETO" --filter "label=com.docker.compose.service=$1" | tr -d '\r' | cut -c1-12
}

saude() { dk inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$1" 2>/dev/null | tr -d '\r'; }

# esperar_healthy <ids...>  -> 0 se TODOS healthy dentro de ESPERA_HEALTHY_S (todos os servicos do compose tem healthcheck; sem ele nunca fica healthy)
esperar_healthy() {
    local id fim
    for id in "$@"; do
        fim=$(( $(date +%s) + ESPERA_HEALTHY_S ))
        while :; do
            case "$(saude "$id")" in healthy) break ;; esac
            if [ "$(date +%s)" -ge "$fim" ]; then log "ERRO: $id nao ficou healthy em ${ESPERA_HEALTHY_S}s"; return 1; fi
            sleep 3
        done
    done
}

# --- Docker ---------------------------------------------------------------------------------------------------------------------------------------

redis_sh() { dk exec "$CONTAINER_REDIS" sh -c "$1" 2>/dev/null | tr -d '\r'; }   # REDISCLI_AUTH dentro do sh -c, aspas simples: nenhum segredo no host

# 0. O scaler tem de estar parado (ver o cabecalho). Sem Redis nao ha admission nem scaler a detectar.
exigir_scaler_parado() {
    local existe fim
    dk inspect "$CONTAINER_REDIS" >/dev/null 2>&1 || { log "AVISO: sem Redis ($CONTAINER_REDIS): nao da para detectar o scaler"; return 0; }
    fim=$(( $(date +%s) + ESPERA_SCALER_S ))
    while :; do
        existe="$(redis_sh 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli EXISTS adm:replicas')"
        [ "$existe" = 0 ] && return 0
        if [ "$existe" != 1 ]; then log "ERRO: nao consegui ler o Redis para saber se o scaler esta parado (resposta '$existe')"; return 1; fi
        if [ "$(date +%s)" -ge "$fim" ]; then
            log "ERRO: o SCALER da autoescala esta rodando (adm:replicas renovada). Pare-o (Ctrl+C) e rode de novo; com ele no ar uma replica parada faz ele subir outra no meio do TRUNCATE."
            return 1
        fi
        sleep 2
    done
}

# imagem do container descartavel que apaga a chave: a do proprio redis do project (ja esta local e tem rm)
imagem_para_apagar() {
    local img
    img="$(dk inspect -f '{{.Config.Image}}' "$CONTAINER_REDIS" 2>/dev/null | tr -d '\r')"
    echo "${img:-redis:7.4.12-alpine}"
}

# apaga a chave do JWT do volume do project, com um container descartavel
apagar_chave() {
    dk run --rm -v "$VOLUME_CHAVES:/k" --entrypoint sh "$IMG_CHAVE" -c "rm -f /k/$ARQUIVO_CHAVE && test ! -e /k/$ARQUIVO_CHAVE"
}

parar_ids() { # docker stop; guarda em PARADOS o que parou
    [ $# -gt 0 ] || return 0
    dk stop "$@" >/dev/null || return 1
    PARADOS="$PARADOS $*"
}

# religar <com_sonda 0|1>: sobe o que ESTE script parou, na ordem, esperando healthy em cada etapa
religar() {
    local svc ids
    for svc in identity stock "reservation catalog" admission backend; do
        ids=""
        for s in $svc; do ids="$ids ${IDS[$s]:-}"; done
        ids="$(echo $ids)"
        [ -n "$ids" ] || continue
        # shellcheck disable=SC2086
        dk start $ids >/dev/null || { log "ERRO: docker start $svc falhou"; return 1; }
        # shellcheck disable=SC2086
        esperar_healthy $ids || return 1
        log "no ar e healthy: $svc ($ids)"
    done
    if [ -n "$NOTIF_IDS" ]; then
        # shellcheck disable=SC2086
        dk start $NOTIF_IDS >/dev/null && log "notification religado ($NOTIF_IDS)"
    fi
}

lb_stat() { # `show stat` do lb por /dev/tcp (a Runtime API so escuta em 127.0.0.1:9999 DENTRO do container; bash existe na imagem Debian)
    dk exec "$1" bash -c 'exec 3<>/dev/tcp/127.0.0.1/9999; printf "show stat\n" >&3; cat <&3' 2>/dev/null | tr -d '\r'
}

# esperar_slots_up <lb> <backend do haproxy> <n>  -> espera >= n slots UP
esperar_slots_up() {
    local lb="$1" be="$2" n="$3" fim up
    fim=$(( $(date +%s) + 90 ))
    while :; do
        up="$(lb_stat "$lb" | contar_up "$be")"
        [ "${up:-0}" -ge "$n" ] && { log "lb: $up slot(s) UP em $be (esperado >= $n)"; return 0; }
        [ "$(date +%s)" -ge "$fim" ] && { log "ERRO: lb com $up slot(s) UP em $be (esperado >= $n) apos 90s"; return 1; }
        sleep 2
    done
}

http_codigo() { curl -s -o "$2" -w '%{http_code}' "${@:3}" "$1"; }   # http_codigo <url> <arquivo de saida> [args do curl]

# Sonda do JWKS: usuario de sonda -> login -> 3 x replicas do gateway chamadas autenticadas SEGUIDAS com 200/404 (401, 000 e 5xx zeram a contagem).
sonda_jwks() {
    local n_gw="$1" tmp senha email fone code token ok=0 fim n i
    tmp="$(mktemp)"; senha="Sonda#$RANDOM$RANDOM"; email="sonda-$(date +%s)-$RANDOM@example.com"; fone="119$(printf '%08d' $(( (RANDOM * 32768 + RANDOM) % 100000000 )))"
    code="$(http_codigo "$BASE_URL/bff/seed" "$tmp" -X POST -H 'Content-Type: application/json' -d "{\"users\":[{\"name\":\"Sonda\",\"email\":\"$email\",\"password\":\"$senha\",\"fone\":\"$fone\"}]}")"
    if [ "$code" = 404 ]; then log "AVISO: /bff/seed desligado (404): sem login de sonda; o JWKS do gateway NAO foi provado. Faca um login e uma chamada autenticada antes de aplicar carga."; rm -f "$tmp"; return 0; fi
    [ "$code" = 200 ] || { log "ERRO: seed da sonda devolveu HTTP $code"; rm -f "$tmp"; return 1; }
    fim=$(( $(date +%s) + SONDA_TETO_S ))
    while :; do
        code="$(http_codigo "$BASE_URL/bff/users/login" "$tmp" -X POST -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$senha\"}")"
        [ "$code" = 200 ] && break
        [ "$(date +%s)" -ge "$fim" ] && { log "ERRO: login da sonda devolveu HTTP $code (identity fora do lb?)"; rm -f "$tmp"; return 1; }
        sleep 2
    done
    token="$(token_do_login < "$tmp")"
    [ -n "$token" ] || { log "ERRO: login da sonda sem token"; rm -f "$tmp"; return 1; }
    n=$(( 3 * n_gw )); ok=0
    while :; do
        # endpoint autenticado e barato: chave inexistente = 404 com token valido, 401 com JWKS ainda sem a chave
        code="$(http_codigo "$BASE_URL/bff/event-wallets/admission?idempotencyKey=sonda" "$tmp" -H "Authorization: Bearer $token")"
        # so 200/404 provam o JWKS (token valido). 401 = JWKS sem a chave; 000 (sem conexao) e 5xx = gateway/lb ainda subindo: nao provam nada
        case "$code" in 200|404) ok=$(( ok + 1 )) ;; *) ok=0 ;; esac
        [ "$ok" -ge "$n" ] && { log "sonda: $ok chamadas autenticadas seguidas sem 401 (JWKS em cache em todas as $n_gw replica(s) do gateway)"; rm -f "$tmp"; return 0; }
        [ "$(date +%s)" -ge "$fim" ] && { log "ERRO: o gateway ainda responde 401 com token valido apos ${SONDA_TETO_S}s (JWKS sem a chave nova?). O JWKS so e tentado de novo a cada 30 s: reinicie o backend."; rm -f "$tmp"; return 1; }
        case "$code" in 200|404) ;; *) sleep 2 ;; esac
    done
}

# --- o fluxo --------------------------------------------------------------------------------------------------------------------------------------

# aborto depois de parar: religa o que parou (mesma ordem e esperas), a menos que a chave do JWT nao tenha sido apagada (NAO_RELIGAR)
religar_se_abortou() {
    local rc=$?
    [ "$rc" = 0 ] && return
    if [ "$NAO_RELIGAR" = 1 ]; then
        log "ERRO (rc=$rc): nao consegui apagar a chave do JWT; backend, identity e os demais ficam PARADOS (religar com a chave antiga deixaria os tokens antigos valendo). Resolva e rode de novo."
        return
    fi
    [ -n "$PARADOS" ] || return
    set +e
    log "ERRO (rc=$rc): limpeza abortada; religando o que o script parou"
    religar
    log "health do backend: $(curl -s "$BASE_URL/actuator/health")"
}

principal() {
    local svc ids lb n_id n_gw q gt g tp seek_ok i filas
    # 0. guardas, antes de parar qualquer coisa
    exigir_scaler_parado || return 1
    for svc in $SERVICOS; do IDS[$svc]="$(ids_do_servico "$svc" | tr '\n' ' ')"; IDS[$svc]="${IDS[$svc]% }"; done
    NOTIF_IDS="$(ids_do_servico notification | tr '\n' ' ')"; NOTIF_IDS="${NOTIF_IDS% }"
    lb="$(ids_do_servico lb | head -1)"
    for svc in $SERVICOS; do log "$svc: ${IDS[$svc]:-nenhum container no ar}"; done
    [ -n "$NOTIF_IDS" ] && log "notification: $NOTIF_IDS"
    [ -n "$lb" ] && log "lb (braco C): $lb"
    # a rotacao da chave decide pelo VOLUME, nao pela replica em execucao (um identity parado/morto voltaria depois com a chave antiga)
    if dk volume inspect "$VOLUME_CHAVES" >/dev/null 2>&1; then
        TEM_VOLUME=1
        IMG_CHAVE="$(imagem_para_apagar)"
        dk image inspect "$IMG_CHAVE" >/dev/null 2>&1 || { log "ERRO: imagem $IMG_CHAVE (para apagar a chave do JWT) nao esta local; docker pull $IMG_CHAVE e rode de novo"; return 1; }
    elif [ -n "$(ids_do_servico identity -a)" ]; then
        log "ERRO: ha container do identity no project $PROJETO mas o volume $VOLUME_CHAVES nao existe (project errado? PROJETO=$PROJETO)"; return 1
    else
        log "AVISO: sem identity e sem volume $VOLUME_CHAVES: nada a rotacionar"
    fi
    trap religar_se_abortou EXIT

    # 2. parar (inversa da subida: backend primeiro), TODAS as replicas
    for svc in backend admission catalog reservation stock identity; do
        # shellcheck disable=SC2086
        parar_ids ${IDS[$svc]:-} || { log "ERRO: docker stop $svc falhou"; return 1; }
    done
    # shellcheck disable=SC2086
    [ -n "$NOTIF_IDS" ] && { parar_ids $NOTIF_IDS || return 1; }

    # 3. chave do JWT (ANTES do TRUNCATE, com identity e backend parados)
    if [ "$TEM_VOLUME" = 1 ]; then
        NAO_RELIGAR=1
        apagar_chave || { log "ERRO: nao consegui apagar $ARQUIVO_CHAVE de $VOLUME_CHAVES"; return 1; }
        NAO_RELIGAR=0
        log "chave de assinatura do identity apagada de $VOLUME_CHAVES (tokens antigos agora dao 401)"
    fi

    # 4a. consumer groups para o fim dos topicos. O seek falha se um consumer parado ainda nao saiu do group (sessao nao expirou): repete; se ainda
    # falhar, reservation / stock / stock-catalog caem no reset antigo (apaga o topico e o group), senao o group releria eventos de ids reciclados. O topico
    # identity.notification NUNCA e apagado: la a falha so avisa.
    for gt in reservation:reservation.wallet stock:reservation.wallet stock-catalog:catalog.event notification:identity.notification; do
        g="${gt%%:*}"; tp="${gt#*:}"; seek_ok=0
        for i in 1 2 3 4 5 6; do
            dk exec "$CONTAINER_RPK" rpk group seek "$g" --to end --topics "$tp" --allow-new-topics >/dev/null 2>&1 && { seek_ok=1; break; }
            sleep 5
        done
        if [ "$seek_ok" = 0 ]; then
            if [ "$g" = notification ]; then
                log "AVISO: rpk group seek notification falhou; o topico identity.notification NAO e apagado (confira: rpk group describe notification)"
            else
                log "AVISO: rpk group seek $g falhou; apagando o topico $tp e o group $g (reset antigo)"
                dk exec "$CONTAINER_RPK" rpk topic delete "$tp" >/dev/null 2>&1 || true
                dk exec "$CONTAINER_RPK" rpk group delete "$g" >/dev/null 2>&1 || true
            fi
        fi
    done
    # 4b. TRUNCATE numa UNICA instrucao (catalog.event e stock.eventcap andam juntos: ids de evento reciclados nunca herdam eventcap/reservas antigos;
    # o event store/outbox/snapshot/checkpoint de cada schema vao junto)
    dk exec "$CONTAINER_DB" sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U admin -d meu_banco -v ON_ERROR_STOP=1 -q -c "
DO \$\$ DECLARE t text; BEGIN
  SELECT string_agg(format('\''%I.%I'\'', schemaname, tablename), '\'', '\'') INTO t FROM pg_tables WHERE schemaname IN ('\''public'\'', '\''reservation'\'', '\''stock'\'', '\''catalog'\'', '\''identity'\'');
  EXECUTE '\''TRUNCATE '\'' || t || '\'' RESTART IDENTITY CASCADE'\'';
END \$\$;" -c "VACUUM ANALYZE;" 2>/dev/null' || { log "ERRO: TRUNCATE falhou"; return 1; }
    log "banco truncado (public, reservation, stock, catalog, identity)"
    # 4c. admission (#41/#42): estado transitorio zerado com o admission PARADO (os ids de evento e de usuario reciclados nao herdam contagem, modo, espera nem resultado)
    # (compara a SAIDA do FLUSHDB: o codigo de saida do redis-cli nao e confiavel em resposta de erro, ex.: NOAUTH)
    if dk inspect "$CONTAINER_REDIS" >/dev/null 2>&1; then
        [ "$(redis_sh 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli FLUSHDB')" = OK ] || { log "ERRO: FLUSHDB do Redis nao respondeu OK (REDIS_PASSWORD no container? Redis fora?)"; return 1; }
        log "redis: FLUSHDB ok"
    fi
    if dk inspect "$CONTAINER_RABBIT" >/dev/null 2>&1; then
        filas="$(dk exec "$CONTAINER_RABBIT" rabbitmqctl -q list_queues name 2>/dev/null)" || { log "ERRO: rabbitmqctl list_queues falhou"; return 1; }
        for q in $(printf '%s\n' "$filas" | tr -d '\r' | grep '^admission\.' || true); do
            dk exec "$CONTAINER_RABBIT" rabbitmqctl -q purge_queue "$q" >/dev/null || { log "ERRO: purge_queue $q falhou"; return 1; }
            log "rabbitmq: purge $q"
        done
    fi
    for tp in catalog.event.DLT reservation.wallet.DLT t27.e2e t27.e2e.DLT t27.relay t146.e2e t146.e2e.DLT; do
        dk exec "$CONTAINER_RPK" rpk topic delete "$tp" >/dev/null 2>&1 || true
    done
    dk exec "$CONTAINER_RPK" rpk group delete gerenciador-eventos >/dev/null 2>&1 || true

    # 5. religar so o que este script parou (docker start: preserva o env da rodada; nao e reaproveitar container antigo)
    religar || return 1

    # 6. braco C: lb. Janela do JWKS no boot (o gateway so tenta de novo a cada 30 s)
    n_id="$(echo ${IDS[identity]:-} | wc -w)"; n_gw="$(echo ${IDS[backend]:-} | wc -w)"
    if [ -n "$lb" ]; then
        [ "$n_id" -gt 0 ] && { esperar_slots_up "$lb" identity "$n_id" || return 1; }
        [ "$n_gw" -gt 0 ] && { esperar_slots_up "$lb" gateway "$n_gw" || return 1; }
    fi
    if [ "$SONDA_LIGADA" = 1 ] && [ "$n_gw" -gt 0 ]; then sonda_jwks "$n_gw" || return 1; fi

    log "health do backend: $(curl -s "$BASE_URL/actuator/health")"
    # estado final (contagens do banco)
    dk exec "$CONTAINER_DB" sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U admin -d meu_banco -Atc "select '\''event(catalog)='\''||count(*) from catalog.event; select '\''walletevent='\''||count(*) from reservation.walletevent; select '\''eventcap(stock)='\''||count(*) from stock.eventcap; select '\''event_store(reservation)='\''||count(*) from reservation.event_store; select '\''users(identity)='\''||count(*) from identity.users"' 2>/dev/null || true
    dk exec "$CONTAINER_RPK" rpk topic list 2>/dev/null || true
    return 0
}

# --- modo cluster (#228) ------------------------------------------------------------------------------------------------------------------------

# deploys parados pela limpeza, na ordem de PARADA (inversa da subida); o notification por ultimo, como no compose
SERVICOS_PARADA_K8S="backend admission catalog reservation stock identity notification"
declare -A REPL_K8S=()   # deploy -> replicas de antes (so dos que ESTE script parou)

kc() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' kubectl --context "$CARGA_K8S" -n gerenciador "$@"; }   # sem a conversao de path do Git Bash (kubectl nativo do Windows)
kexec() { local alvo="$1"; shift; kc exec "$alvo" -c "${alvo#*/}" -- "$@"; }   # kexec <sts/x|deploy/x> cmd...   (stdin nao e repassado: sem -i; -c <container> = o nome do alvo, para o kubectl nao escrever "Defaulted container" no stderr)

# "<prontas> <desejadas>" do Deployment
k8s_prontas() { kc get "deploy/$1" -o jsonpath='{.status.readyReplicas} {.spec.replicas}' 2>/dev/null | tr -d '\r'; }

# esperar_prontos_k8s <deploy>: espera readyReplicas == spec.replicas >= 1 (o KEDA pode mexer em spec.replicas: compara a cada volta)
esperar_prontos_k8s() {
    local fim pr de
    fim=$(( $(date +%s) + ESPERA_HEALTHY_S ))
    while :; do
        read -r pr de <<< "$(k8s_prontas "$1")"
        [ "${pr:-0}" -ge 1 ] && [ "${pr:-0}" = "${de:-x}" ] && return 0
        if [ "$(date +%s)" -ge "$fim" ]; then log "ERRO: deploy/$1 nao ficou pronto em ${ESPERA_HEALTHY_S}s (prontas '${pr:-0}' de '${de:-?}')"; return 1; fi
        sleep 3
    done
}

# esperar_sem_pods_k8s <deploy>: espera nao haver mais pod do app (o scale 0 so termina quando os pods saem; o preStop leva ate 20 s)
esperar_sem_pods_k8s() {
    local fim; fim=$(( $(date +%s) + ESPERA_HEALTHY_S ))
    while [ -n "$(kc get pods -l "app=$1" -o name 2>/dev/null | tr -d '\r')" ]; do
        if [ "$(date +%s)" -ge "$fim" ]; then log "ERRO: os pods de $1 nao sairam em ${ESPERA_HEALTHY_S}s"; return 1; fi
        sleep 2
    done
}

ANOT_ANTES=carga.gerenciador/replicas-antes   # anotacao do Deployment com as replicas de antes: a recuperacao (e a recusa de uma nova limpeza) as le dali
RELIGAR_ESTADO=                               # "" = nao tentou religar | falhou | ok

parar_k8s() { # <deploy>: anota as replicas de antes, pausa o KEDA em 0 e zera
    local d="$1" n
    n="$(kc get "deploy/$d" -o jsonpath='{.spec.replicas}' 2>/dev/null | tr -d '\r')"
    [ "${n:-0}" -ge 1 ] || { log "$d: ja em 0 replicas (nao foi este script que parou)"; return 0; }
    REPL_K8S[$d]="$n"   # ANTES de qualquer mudanca: se o annotate der certo e o scale falhar, o trap ainda religa este
    kc annotate "deploy/$d" "$ANOT_ANTES=$n" --overwrite >/dev/null || return 1
    kc annotate "scaledobject/$d" autoscaling.keda.sh/paused-replicas=0 --overwrite >/dev/null 2>&1 || log "AVISO: sem ScaledObject $d (o KEDA nao esta pausado para ele)"
    kc scale "deploy/$d" --replicas=0 >/dev/null || return 1
    PARADOS="$PARADOS $d"
    esperar_sem_pods_k8s "$d"
}

# recuperacao_k8s [svc=n ...]: os comandos EXATOS para voltar o cluster ao estado de antes (tirar a pausa do KEDA, devolver as replicas, apagar a anotacao)
recuperacao_k8s() {
    local p c="kubectl --context $CARGA_K8S -n gerenciador"
    log "RECUPERACAO (copie e rode, com os servicos que a limpeza parou):"
    log "  $c annotate scaledobject --all autoscaling.keda.sh/paused-replicas-"
    for p in "$@"; do log "  $c scale deploy/${p%%=*} --replicas=${p#*=}"; done
    log "  $c annotate deploy --all $ANOT_ANTES-"
}

# pares svc=n dos Deployments que ESTE script parou
pares_parados_k8s() { local d; for d in "${!REPL_K8S[@]}"; do echo "$d=${REPL_K8S[$d]}"; done; }

religar_k8s() { # 1) tira a pausa e faz o scale de TODOS os que o script parou; 2) so depois espera, na ordem (identity, stock, reservation+catalog, admission, backend, notification)
    local etapa d
    RELIGAR_ESTADO=falhou
    for etapa in identity stock "reservation catalog" admission backend notification; do
        for d in $etapa; do
            [ -n "${REPL_K8S[$d]:-}" ] || continue
            kc annotate "scaledobject/$d" autoscaling.keda.sh/paused-replicas- >/dev/null 2>&1 || true
            kc scale "deploy/$d" --replicas="${REPL_K8S[$d]}" >/dev/null || log "ERRO: scale $d falhou"
        done
    done
    for etapa in identity stock "reservation catalog" admission backend notification; do
        for d in $etapa; do
            [ -n "${REPL_K8S[$d]:-}" ] || continue
            esperar_prontos_k8s "$d" || return 1
            log "no ar e pronto: $d"
        done
    done
    for d in "${!REPL_K8S[@]}"; do kc annotate "deploy/$d" "$ANOT_ANTES-" >/dev/null 2>&1 || true; done
    RELIGAR_ESTADO=ok
}

# apaga a chave do JWT do PVC identity-keys com um pod descartavel (identity e backend ja parados; o PVC e RWO)
apagar_chave_k8s() {
    local fase fim
    kc delete pod/limpar-chave --ignore-not-found >/dev/null 2>&1
    kc apply -f - >/dev/null <<EOF || return 1
apiVersion: v1
kind: Pod
metadata:
  name: limpar-chave
  labels: {app: limpar-chave, sidecar.istio.io/inject: "false"}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  enableServiceLinks: false
  securityContext: {seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: limpar-chave
      image: busybox:1.37.0
      imagePullPolicy: IfNotPresent
      command: ["sh", "-c", "rm -f /k/$ARQUIVO_CHAVE && test ! -e /k/$ARQUIVO_CHAVE"]
      volumeMounts:
        - {name: chaves, mountPath: /k}
  volumes:
    - name: chaves
      persistentVolumeClaim: {claimName: identity-keys}
EOF
    fim=$(( $(date +%s) + ESPERA_HEALTHY_S ))
    while :; do
        fase="$(kc get pod/limpar-chave -o jsonpath='{.status.phase}' 2>/dev/null | tr -d '\r')"
        case "$fase" in Succeeded|Failed) break ;; esac
        [ "$(date +%s)" -ge "$fim" ] && { fase=timeout; break; }
        sleep 2
    done
    kc delete pod/limpar-chave --wait=false >/dev/null 2>&1
    [ "$fase" = Succeeded ]
}

religar_k8s_se_abortou() {
    local rc=$?
    [ "$rc" = 0 ] && return
    [ "$RELIGAR_ESTADO" = ok ] && { log "ERRO (rc=$rc): a limpeza falhou DEPOIS de religar tudo (nada a recuperar)"; return; }
    if [ "$NAO_RELIGAR" = 1 ]; then
        log "ERRO (rc=$rc): nao consegui apagar a chave do JWT; os servicos ficam PARADOS e o KEDA PAUSADO (religar com a chave antiga deixaria os tokens antigos valendo). Resolva e rode de novo; para voltar ao estado de antes sem apagar a chave:"
        # shellcheck disable=SC2046
        recuperacao_k8s $(pares_parados_k8s)
        return
    fi
    [ "${#REPL_K8S[@]}" -gt 0 ] || return   # nada foi anotado nem parado
    set +e
    if [ "$RELIGAR_ESTADO" = "" ]; then
        log "ERRO (rc=$rc): limpeza abortada; religando o que o script parou"
        religar_k8s
    else
        log "ERRO (rc=$rc): religar nao terminou"
    fi
    [ "$RELIGAR_ESTADO" = ok ] && return
    # shellcheck disable=SC2046
    recuperacao_k8s $(pares_parados_k8s)
}

# o mesmo TRUNCATE do compose (uma unica instrucao: catalog.event e stock.eventcap andam juntos)
SQL_TRUNCATE_K8S="DO \$\$ DECLARE t text; BEGIN
  SELECT string_agg(format('%I.%I', schemaname, tablename), ', ') INTO t FROM pg_tables WHERE schemaname IN ('public', 'reservation', 'stock', 'catalog', 'identity');
  EXECUTE 'TRUNCATE ' || t || ' RESTART IDENTITY CASCADE';
END \$\$;"

principal_k8s() {
    local d n q gt g tp seek_ok i filas n_gw saida paus ants pend
    # 0. guardas, antes de parar qualquer coisa: o contexto responde, o PVC da chave existe
    kc get deploy/identity -o name >/dev/null 2>&1 || { log "ERRO: o contexto $CARGA_K8S nao responde ou nao tem deploy/identity no namespace gerenciador (CARGA_K8S errado?)"; return 1; }
    kc get pvc/identity-keys -o name >/dev/null 2>&1 || { log "ERRO: o cluster nao tem o PVC identity-keys: nao ha como apagar a chave do JWT"; return 1; }
    # uma limpeza anterior que abortou deixa o KEDA pausado em 0 e/ou a anotacao das replicas: rodar de novo "daria certo" com o cluster parado. Recusa e diz como recuperar.
    paus="$(kc get scaledobject -o jsonpath='{range .items[*]}{.metadata.name} {.metadata.annotations.autoscaling\.keda\.sh/paused-replicas}{"\n"}{end}' | tr -d '\r')" \
        || { log "ERRO: nao consegui ler os ScaledObjects (KEDA instalado? contexto $CARGA_K8S)"; return 1; }
    ants="$(kc get deploy -o jsonpath='{range .items[*]}{.metadata.name} {.metadata.annotations.carga\.gerenciador/replicas-antes}{"\n"}{end}' | tr -d '\r')" \
        || { log "ERRO: nao consegui ler os Deployments"; return 1; }
    pend=(); while read -r d n; do [ -n "${n:-}" ] && pend+=("$d=$n"); done <<< "$ants"
    if [ -n "$(printf '%s\n' "$paus" | awk 'NF >= 2')" ] || [ "${#pend[@]}" -gt 0 ]; then
        log "ERRO: uma limpeza anterior nao terminou (ScaledObject pausado: $(printf '%s\n' "$paus" | awk 'NF >= 2 {printf "%s ", $1}')| replicas anotadas: ${pend[*]:-nenhuma}). Nada foi parado agora. Recupere o cluster:"
        recuperacao_k8s ${pend[@]+"${pend[@]}"}
        return 1
    fi
    db_psql_preparar "$CARGA_K8S" || return 1   # local x remoto decidido UMA vez e o pod cliente garantido UMA vez (db_psql so faz o exec)
    log "db $([ "$DB_PSQL_ALVO" = sts/db ] && echo 'no cluster (sts/db)' || echo 'FORA do cluster (sem sts/db): psql pelo pod cliente db-cliente')"
    trap religar_k8s_se_abortou EXIT

    # 2. parar (inversa da subida), com o KEDA pausado
    for d in $SERVICOS_PARADA_K8S; do parar_k8s "$d" || { log "ERRO: parar $d falhou"; return 1; }; done

    # 3. chave do JWT (ANTES do TRUNCATE, com identity e backend parados)
    NAO_RELIGAR=1
    apagar_chave_k8s || { log "ERRO: nao consegui apagar $ARQUIVO_CHAVE do PVC identity-keys"; return 1; }
    NAO_RELIGAR=0
    log "chave de assinatura do identity apagada do PVC identity-keys (tokens antigos agora dao 401)"

    # 4a. consumer groups para o fim dos topicos (mesma regra do compose)
    for gt in reservation:reservation.wallet stock:reservation.wallet stock-catalog:catalog.event notification:identity.notification; do
        g="${gt%%:*}"; tp="${gt#*:}"; seek_ok=0
        for i in 1 2 3 4 5 6; do
            kexec sts/redpanda rpk group seek "$g" --to end --topics "$tp" --allow-new-topics >/dev/null 2>&1 && { seek_ok=1; break; }
            sleep 5
        done
        if [ "$seek_ok" = 0 ]; then
            if [ "$g" = notification ]; then
                log "AVISO: rpk group seek notification falhou; o topico identity.notification NAO e apagado (confira: rpk group describe notification)"
            else
                log "AVISO: rpk group seek $g falhou; apagando o topico $tp e o group $g (reset antigo)"
                kexec sts/redpanda rpk topic delete "$tp" >/dev/null 2>&1 || true
                kexec sts/redpanda rpk group delete "$g" >/dev/null 2>&1 || true
            fi
        fi
    done
    # 4b. TRUNCATE (banco do cluster ou o remoto, pelo db_psql)
    db_psql "$CARGA_K8S" app -v ON_ERROR_STOP=1 -q -c "$SQL_TRUNCATE_K8S" -c "VACUUM ANALYZE;" >/dev/null 2>&1 || { log "ERRO: TRUNCATE falhou"; return 1; }
    log "banco truncado (public, reservation, stock, catalog, identity)"
    # 4c. Redis e filas do admission, com o admission PARADO
    saida="$(kexec deploy/redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli FLUSHDB' 2>/dev/null | tr -d '\r')"
    [ "$saida" = OK ] || { log "ERRO: FLUSHDB do Redis nao respondeu OK (REDIS_PASSWORD no pod? Redis fora?)"; return 1; }
    log "redis: FLUSHDB ok"
    filas="$(kexec deploy/rabbitmq rabbitmqctl -q list_queues name 2>/dev/null)" || { log "ERRO: rabbitmqctl list_queues falhou"; return 1; }
    for q in $(printf '%s\n' "$filas" | tr -d '\r' | grep '^admission\.' || true); do
        kexec deploy/rabbitmq rabbitmqctl -q purge_queue "$q" >/dev/null || { log "ERRO: purge_queue $q falhou"; return 1; }
        log "rabbitmq: purge $q"
    done
    for tp in catalog.event.DLT reservation.wallet.DLT t27.e2e t27.e2e.DLT t27.relay t146.e2e t146.e2e.DLT; do
        kexec sts/redpanda rpk topic delete "$tp" >/dev/null 2>&1 || true
    done
    kexec sts/redpanda rpk group delete gerenciador-eventos >/dev/null 2>&1 || true

    # 5. religar so o que este script parou
    religar_k8s || return 1

    # 6. sonda do JWKS: login de sonda + chamadas autenticadas pela entrada do gateway (o NodePort da BASE_URL) ate nenhuma dar 401.
    # Sem replica do backend para provar (estava em 0 antes) a sonda NAO roda: isso e falha, nunca um sucesso silencioso.
    n_gw="${REPL_K8S[backend]:-0}"
    if [ "$SONDA_LIGADA" = 1 ]; then
        [ "$n_gw" -gt 0 ] || { log "ERRO: o backend estava em 0 replicas antes da limpeza: o JWKS do gateway NAO foi provado (suba o backend e rode a sonda, ou SONDA_LIGADA=0 se for de proposito)"; return 1; }
        sonda_jwks "$n_gw" || return 1
    fi
    log "backend: $(k8s_prontas backend) (prontas desejadas)"
    db_psql "$CARGA_K8S" app -Atc "select 'event(catalog)='||count(*) from catalog.event; select 'walletevent='||count(*) from reservation.walletevent; select 'eventcap(stock)='||count(*) from stock.eventcap; select 'event_store(reservation)='||count(*) from reservation.event_store; select 'users(identity)='||count(*) from identity.users" 2>/dev/null || true
    kexec sts/redpanda rpk topic list 2>/dev/null || true
    return 0
}

[ "${LIMPAR_LIB:-0}" = "1" ] && return 0 2>/dev/null
if [ -n "${CARGA_K8S:-}" ]; then principal_k8s; else principal; fi
exit $?
