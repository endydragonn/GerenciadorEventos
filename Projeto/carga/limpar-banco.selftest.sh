#!/usr/bin/env bash
# Selftest do limpar-banco.sh (issue #178), SEM Docker: um `docker`/`curl`/`sleep` falsos (funcoes do shell) registram as chamadas e o script roda
# inteiro contra um "braco C" simulado (2 replicas de identity e de backend, lb). Confere a ORDEM (parar tudo antes de apagar a chave, chave antes do
# TRUNCATE, identity antes de reservation/catalog, admission, backend por ultimo), que acha as replicas por label, o nome do volume derivado do
# project, a recusa com o scaler no ar (nada e parado), a rotacao da chave decidida pelo VOLUME (identity fora do ar tambem apaga), FLUSHDB/purge que
# falham (abortam e religam) e a sonda do JWKS (so 200/404 provam; 401, 000 e 5xx zeram).
set -u
LIMPAR_LIB=1 . "$(dirname "$0")/limpar-banco.sh"
falhas=0
confere() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FALHA $1: esperado '$3', veio '$2'"; falhas=$((falhas+1)); fi; }

# --- puras
# MSYS_NO_PATHCONV=1 herdado (Git Bash) quebra o curl.exe da sonda: ele grava o corpo noutro lugar e o mktemp fica vazio ("login sem token" com HTTP 200)
confere "carregar o script desliga MSYS_NO_PATHCONV herdado" "$(MSYS_NO_PATHCONV=1 LIMPAR_LIB=1 bash -c '. ./limpar-banco.sh; echo "${MSYS_NO_PATHCONV-desligado}"')" "desligado"
csv_lb='# pxname,svname,qcur,qmax,scur,smax,slim,stot,bin,bout,dreq,dresp,ereq,econ,eresp,wretr,wredis,status,weight
identity,FRONTEND,0,0,0,0,1,0,0,0,0,0,0,,,,,OPEN,
identity,id1,0,0,0,0,,0,0,0,,0,,0,0,0,0,UP,100
identity,id2,0,0,0,0,,0,0,0,,0,,0,0,0,0,UP,0
identity,id3,0,0,0,0,,0,0,0,,0,,0,0,0,0,DOWN,100
identity,BACKEND,0,0,0,0,1,0,0,0,0,0,,0,0,0,0,UP,100
gateway,gw1,0,0,0,0,,0,0,0,,0,,0,0,0,0,UP,100
gateway,gw2,0,0,0,0,,0,0,0,,0,,0,0,0,0,DOWN,100'
confere "contar_up: so slots UP do backend pedido (stand-by de peso 0 conta; DOWN/FRONTEND/BACKEND nao)" "$(echo "$csv_lb" | contar_up identity)" "2"
confere "contar_up: outro backend" "$(echo "$csv_lb" | contar_up gateway)" "1"
confere "contar_up: backend ausente = 0" "$(echo "$csv_lb" | contar_up nada)" "0"
confere "token_do_login" "$(printf '{"user":{"user_id":3},"token":"abc.def.ghi"}' | token_do_login)" "abc.def.ghi"
confere "token_do_login: sem token = vazio" "$(printf '{"erro":"x"}' | token_do_login)" ""

# --- o fluxo, com docker/curl/sleep falsos
csv_ok="$(echo "$csv_lb" | awk -F, 'BEGIN{OFS=","} $2=="gw2"{$18="UP"} {print}')"
LOG="$(mktemp)"
FALHA_CHAVE=0
sleep() { :; }
# containers por servico (ids de 12 caracteres): braco C, 2 replicas de identity e backend
declare -A FAKE_IDS=( [identity]="aaaaaaaaaa01 aaaaaaaaaa02" [stock]="gggggggggg01" [reservation]="bbbbbbbbbb01" [catalog]="cccccccccc01" [admission]="dddddddddd01"
    [backend]="eeeeeeeeee01 eeeeeeeeee02" [notification]="ffffffffff01" [lb]="1111111111lb" )
declare -A FAKE_TODOS=( [identity]="aaaaaaaaaa01 aaaaaaaaaa02" )   # `ps -a`: inclui os parados
SCALER_EXISTE=0
SEED_CODIGO=200
VOLUME_EXISTE=1; FLUSH_SAIDA=OK; PURGE_FALHA=0
docker() {
    echo "docker $*" >> "$LOG"
    case "$1" in
        ps)
            local svc
            for a in "$@"; do case "$a" in label=com.docker.compose.service=*) svc="${a#*=*=}" ;; esac; done
            case "$*" in *"label=com.docker.compose.project=$PROJETO"*) ;; *) return 0 ;; esac
            case "$*" in
                *" -a "*) for i in ${FAKE_TODOS[$svc]:-${FAKE_IDS[$svc]:-}}; do echo "$i"; done ;;
                *) for i in ${FAKE_IDS[$svc]:-}; do echo "$i"; done ;;
            esac ;;
        inspect)
            case "$*" in
                *Config.Image*) echo "redis:7.4.12-alpine" ;;
                *Health*) echo "healthy" ;;
            esac ;;
        volume) [ "$VOLUME_EXISTE" = 1 ] ;;
        run) case "$*" in *"identity-keys:/k"*) [ "$FALHA_CHAVE" = 1 ] && return 1 ;; esac; return 0 ;;
        stop|start) return 0 ;;
        exec)
            case "$*" in
                *"EXISTS adm:replicas"*) echo "$SCALER_EXISTE" ;;
                *FLUSHDB*) printf '%s\n' "$FLUSH_SAIDA" ;;
                *purge_queue*) [ "$PURGE_FALHA" != 1 ] ;;
                *"list_queues name"*) printf 'admission.requests\nadmission.timed_out\nother\n' ;;
                *"show stat"*) echo "$csv_ok" ;;
                *) return 0 ;;
            esac ;;
        *) return 0 ;;
    esac
}
curl() {
    echo "curl $*" >> "$LOG"
    local out="" url="" w=0 a
    for a in "$@"; do
        [ "$w" = 1 ] && { out="$a"; w=0; continue; }
        [ "$a" = -o ] && { w=1; continue; }
        url="$a"
    done
    case "$url" in
        */bff/seed) [ -n "$out" ] && echo '{"usersCreated":1}' > "$out"; printf '%s' "$SEED_CODIGO" ;;
        */bff/users/login) [ -n "$out" ] && echo '{"user":{"user_id":1},"token":"tok.en.x"}' > "$out"; printf '200' ;;
        */bff/event-wallets/admission*) [ -n "$out" ] && : > "$out"
            c="$(head -1 "$LOG.seq" 2>/dev/null)"   # estado em arquivo: o curl roda em subshell ($(...))
            if [ -n "$c" ]; then tail -n +2 "$LOG.seq" > "$LOG.seq.t"; mv "$LOG.seq.t" "$LOG.seq"; printf '%s' "$c"; else printf '404'; fi ;;
        */actuator/health) echo '{"status":"UP"}' ;;
        *) printf '200' ;;
    esac
}
export -f docker curl sleep 2>/dev/null || true
ESPERA_SCALER_S=0; ESPERA_HEALTHY_S=1; SONDA_TETO_S=10
PROJETO=gerenciador-eventos
linha() { grep -n "$1" "$LOG" | head -1 | cut -d: -f1; }

seq_adm() { printf '%s\n' "$@" > "$LOG.seq"; }
: > "$LOG"; seq_adm 401 401    # as 2 primeiras chamadas autenticadas dao 401 (JWKS ainda sem a chave), depois 404
( principal ) >/dev/null 2>&1; rc=$?
confere "fluxo completo: codigo 0" "$rc" "0"
confere "achou as replicas por label (project + servico), nao por nome fixo" "$(grep -c 'label=com.docker.compose.service=identity' "$LOG" | awk '{print ($1>=1)?"sim":"nao"}')" "sim"
confere "para TODAS as replicas do identity e do backend (stand-by inclusive)" "$(grep -c 'docker stop aaaaaaaaaa01 aaaaaaaaaa02' "$LOG")$(grep -c 'docker stop eeeeeeeeee01 eeeeeeeeee02' "$LOG")" "11"
p_back="$(linha 'docker stop eeeeeeeeee01')"; p_id="$(linha 'docker stop aaaaaaaaaa01')"; p_key="$(linha 'docker run --rm -v gerenciador-eventos_identity-keys')"
p_trunc="$(linha 'TRUNCATE')"; p_flush="$(linha 'FLUSHDB')"; p_purge="$(linha 'purge_queue admission.requests')"
s_id="$(linha 'docker start aaaaaaaaaa01 aaaaaaaaaa02')"; s_rc="$(linha 'docker start bbbbbbbbbb01 cccccccccc01')"; s_ad="$(linha 'docker start dddddddddd01')"; s_gw="$(linha 'docker start eeeeeeeeee01 eeeeeeeeee02')"
s_nt="$(linha 'docker start ffffffffff01')"; p_stock="$(linha 'docker stop gggggggggg01')"; p_res="$(linha 'docker stop bbbbbbbbbb01')"; s_st="$(linha 'docker start gggggggggg01')"
confere "ordem de parada: backend antes do identity" "$([ "$p_back" -lt "$p_id" ] && echo sim || echo nao)" "sim"
confere "a chave so e apagada DEPOIS de parar o identity e o backend" "$([ "$p_id" -lt "$p_key" ] && echo sim || echo nao)" "sim"
confere "a chave e apagada ANTES do TRUNCATE" "$([ "$p_key" -lt "$p_trunc" ] && echo sim || echo nao)" "sim"
confere "volume derivado do project (${PROJETO}_identity-keys), nao fixo" "$(grep -c 'identity-keys:/k' "$LOG" | awk '{print ($1==1)?"sim":"nao"}')" "sim"
confere "FLUSHDB e purge das filas admission.* com os servicos parados (depois do TRUNCATE, antes de religar)" "$([ "$p_trunc" -lt "$p_flush" ] && [ "$p_flush" -lt "$s_id" ] && [ "$p_purge" -lt "$s_id" ] && echo sim || echo nao)" "sim"
confere "so purga filas admission.*" "$(grep -c 'purge_queue other' "$LOG")" "0"
confere "religa: TODAS as replicas do identity, depois reservation+catalog, depois admission, depois TODAS as do backend" "$([ "$s_id" -lt "$s_rc" ] && [ "$s_rc" -lt "$s_ad" ] && [ "$s_ad" -lt "$s_gw" ] && echo sim || echo nao)" "sim"
confere "stock (#47): parado depois do reservation e antes do identity, religado depois do identity e ANTES do reservation+catalog" "$([ "$p_res" -lt "$p_stock" ] && [ "$p_stock" -lt "$p_id" ] && [ "$s_id" -lt "$s_st" ] && [ "$s_st" -lt "$s_rc" ] && echo sim || echo nao)" "sim"
confere "o TRUNCATE inclui o schema stock" "$(grep -c "'stock'" "$LOG")" "1"
confere "move os groups stock e stock-catalog (e nao mais reservation-catalog) para o fim dos topicos" "$(grep -c 'rpk group seek stock --to end --topics reservation.wallet' "$LOG")$(grep -c 'rpk group seek stock-catalog --to end --topics catalog.event' "$LOG")$(grep -c 'seek reservation-catalog' "$LOG")" "110"
confere "notification volta por ultimo (estava no ar)" "$([ "$s_gw" -lt "$s_nt" ] && echo sim || echo nao)" "sim"
confere "religa com docker start nos MESMOS containers (nunca recria: nao ha 'compose up')" "$(grep -c 'compose up' "$LOG")" "0"
confere "braco C: consulta os slots do identity e do gateway no lb" "$(grep -c 'show stat' "$LOG" | awk '{print ($1>=2)?"sim":"nao"}')" "sim"
confere "sonda: seed + login + chamadas autenticadas ate nao dar 401 (JWKS)" "$(grep -c 'event-wallets/admission' "$LOG" | awk '{print ($1>=6)?"sim":"nao"}')" "sim"

# scaler no ar: recusa antes de parar QUALQUER coisa
: > "$LOG"; SCALER_EXISTE=1
( principal ) >/dev/null 2>&1; rc=$?
confere "scaler no ar: recusa (codigo != 0)" "$([ "$rc" -ne 0 ] && echo sim || echo nao)" "sim"
confere "scaler no ar: nenhum container foi parado nem religado" "$(grep -c 'docker stop\|docker start' "$LOG")" "0"
SCALER_EXISTE=0

# sem /bff/seed (404): so avisa, nao falha
: > "$LOG"; SEED_CODIGO=404
( principal ) >/dev/null 2>&1; rc=$?
confere "seed desligado (404): limpeza termina com 0 (a sonda so avisa)" "$rc" "0"
SEED_CODIGO=200

# falha ao apagar a chave: NADA e religado
: > "$LOG"; FALHA_CHAVE=1
( principal ) >/dev/null 2>&1; rc=$?
confere "chave nao apagada: aborta (codigo != 0)" "$([ "$rc" -ne 0 ] && echo sim || echo nao)" "sim"
confere "chave nao apagada: NAO religa identity nem backend (os tokens antigos valeriam)" "$(grep -c 'docker start' "$LOG")" "0"
confere "chave nao apagada: o TRUNCATE nem chegou a rodar" "$(grep -c 'TRUNCATE' "$LOG")" "0"

FALHA_CHAVE=0
# sonda: 000 (sem conexao) e 5xx nao provam o JWKS; so 200/404 SEGUIDOS (3 x 2 replicas do gateway = 6) contam
: > "$LOG"; seq_adm 000 000 000 503 404 404 404 502 404 404 404 404 404 404
( principal ) >/dev/null 2>&1; rc=$?
confere "sonda: 000/5xx zeram a contagem (a sequencia 404 x3, 502, 404 x6 so fecha na ultima rajada de 6)" "$(grep -c 'event-wallets/admission' "$LOG")" "14"
confere "sonda: com a rajada final de 6 sem 401/000/5xx a limpeza termina com 0" "$rc" "0"
: > "$LOG"; seq_adm $(yes 000 | head -300)
SONDA_TETO_S=1
( principal ) >/dev/null 2>&1; rc=$?
confere "sonda: gateway fora (000 sem parar) NAO conta como provado: erro apos o teto" "$([ "$rc" -ne 0 ] && echo sim || echo nao)" "sim"
SONDA_TETO_S=10; : > "$LOG.seq"

# chave decidida pelo VOLUME: identity SEM replica em execucao (caiu / parado a mao), volume existente -> a chave e apagada
: > "$LOG"; VOLUME_EXISTE=1; FAKE_IDS[identity]=""
( principal ) >/dev/null 2>&1; rc=$?
confere "identity fora do ar + volume existente: a chave e apagada (nao volta com a chave antiga)" "$(grep -c 'docker run --rm -v gerenciador-eventos_identity-keys' "$LOG")" "1"
p_key="$(linha 'docker run --rm -v gerenciador-eventos_identity-keys')"; p_trunc="$(linha 'TRUNCATE')"
confere "identity fora do ar: a chave e apagada ANTES do TRUNCATE" "$([ -n "$p_key" ] && [ "$p_key" -lt "$p_trunc" ] && echo sim || echo nao)" "sim"
confere "identity fora do ar: limpeza termina com 0 e nao religa o identity (nao o parou)" "$rc$(grep -c 'docker start aaaaaaaaaa' "$LOG")" "00"
# sem volume e com container do identity no project (parado inclusive): recusa antes de parar qualquer coisa
: > "$LOG"; VOLUME_EXISTE=0
( principal ) >/dev/null 2>&1; rc=$?
confere "sem volume + container do identity (ps -a): recusa sem parar nada" "$([ "$rc" -ne 0 ] && echo sim || echo nao)$(grep -c 'docker stop' "$LOG")" "sim0"
# sem volume e sem nenhum identity: nada a rotacionar, segue
: > "$LOG"; FAKE_TODOS[identity]=""
( principal ) >/dev/null 2>&1; rc=$?
confere "sem volume e sem identity: segue sem apagar chave" "$rc$(grep -c 'docker run' "$LOG")" "00"
FAKE_TODOS[identity]="aaaaaaaaaa01 aaaaaaaaaa02"; FAKE_IDS[identity]="aaaaaaaaaa01 aaaaaaaaaa02"; VOLUME_EXISTE=1

# FLUSHDB sem OK (NOAUTH etc.): aborta, nao fica em silencio, e RELIGA o que parou (a chave ja foi apagada)
: > "$LOG"; FLUSH_SAIDA="NOAUTH Authentication required."
( principal ) >/dev/null 2>&1; rc=$?
confere "FLUSHDB sem OK: aborta (codigo != 0)" "$([ "$rc" -ne 0 ] && echo sim || echo nao)" "sim"
confere "FLUSHDB sem OK: religa o que parou" "$(grep -c 'docker start' "$LOG" | awk '{print ($1>=1)?"sim":"nao"}')" "sim"
FLUSH_SAIDA=OK
# purge_queue que falha: aborta e religa
: > "$LOG"; PURGE_FALHA=1
( principal ) >/dev/null 2>&1; rc=$?
confere "purge_queue falhou: aborta (codigo != 0)" "$([ "$rc" -ne 0 ] && echo sim || echo nao)" "sim"
confere "purge_queue falhou: religa o que parou" "$(grep -c 'docker start' "$LOG" | awk '{print ($1>=1)?"sim":"nao"}')" "sim"
PURGE_FALHA=0

# --- MODO CLUSTER (#228): CARGA_K8S=<contexto>; um `kubectl` FUNCAO registra as chamadas (nenhum Docker). Confere: --context em todo kubectl, nenhuma chamada ao docker, a senha
# fora dos argumentos, a escolha local x remoto do psql (decidida UMA vez), a ordem (parar tudo -> chave -> TRUNCATE -> FLUSHDB -> religar), o KEDA pausado e liberado, a falha da chave
# (nada religa), a recuperacao (aborto no meio, pausa deixada por uma limpeza anterior, sonda pulada).
K8S_LIB="$(cd "$(dirname "$0")" && pwd)/../k8s/lib-db-cliente.sh"
. "$K8S_LIB"
LOGK="$(mktemp)"
FAKE_DB_REMOTO=0; FAKE_DB_ERRO=0; FAKE_FASE_CHAVE=Succeeded; FAKE_FLUSH=OK; FAKE_PAUSADOS=""; FAKE_ANTES=""; FAKE_SCALE_FALHA=""; FAKE_NAO_PRONTO=""
declare -A FAKE_REPL=( [backend]=2 [admission]=1 [catalog]=1 [reservation]=1 [stock]=1 [identity]=1 [notification]=1 )
kubectl() {
    echo "kubectl $(printf '%s ' "$@" | tr '\n' ' ')" >> "$LOGK"
    local d todos="$*"
    case "$todos" in
        *"get sts/db"*) [ "$FAKE_DB_ERRO" = 1 ] && return 1; [ "$FAKE_DB_REMOTO" = 1 ] && return 0; echo "statefulset.apps/db" ;;
        *"get deploy/identity -o name"*) echo "deployment.apps/identity" ;;
        *"get pvc/identity-keys"*) echo "persistentvolumeclaim/identity-keys" ;;
        *"get scaledobject -o jsonpath"*) printf '%b' "$FAKE_PAUSADOS" ;;
        *"get deploy -o jsonpath"*) printf '%b' "$FAKE_ANTES" ;;
        *"get pod/limpar-chave"*) echo "$FAKE_FASE_CHAVE" ;;
        *"get deploy/"*"{.status.readyReplicas}"*) d="${todos#*get deploy/}"; d="${d%% *}"; if [ "$d" = "$FAKE_NAO_PRONTO" ]; then echo "0 ${FAKE_REPL[$d]}"; else echo "${FAKE_REPL[$d]} ${FAKE_REPL[$d]}"; fi ;;
        *"get deploy/"*"{.spec.replicas}"*) d="${todos#*get deploy/}"; d="${d%% *}"; echo "${FAKE_REPL[$d]}" ;;
        *"get pods -l app="*) ;;
        *"apply -f -"*) cat > /dev/null ;;
        *"scale deploy/${FAKE_SCALE_FALHA:-nenhum} --replicas=0"*) return 1 ;;
        *FLUSHDB*) echo "$FAKE_FLUSH" ;;
        *"list_queues name"*) printf 'admission.requests\nadmission.timed_out\nother\n' ;;
    esac
    return 0
}
CARGA_K8S=kind-gerenciador
SEGREDOS_K8S=(APP_DB_PASSWORD=CANARIO-APP POSTGRES_PASSWORD=CANARIO-PG REDIS_PASSWORD=CANARIO-REDIS)
SAIDAK="$(mktemp)"
roda_k8s() { : > "$LOGK"; : > "$LOG"; : > "$LOG.seq"; ( export "${SEGREDOS_K8S[@]}"; unset DB_PSQL_ALVO DB_PSQL_HOST; principal_k8s ) > "$SAIDAK" 2>&1; }
plinha() { grep -n -- "$1" "$LOGK" | head -1 | cut -d: -f1; }
ultima() { grep -n -- "$1" "$LOGK" | tail -1 | cut -d: -f1; }

roda_k8s; rc=$?
confere "k8s: fluxo completo com o db no cluster: codigo 0" "$rc" "0"
confere "k8s: nenhuma chamada ao docker (nem o curl da sonda foi para outro lugar)" "$(grep -c '^docker ' "$LOG")" "0"
confere "k8s: todo kubectl leva --context kind-gerenciador -n gerenciador (e o log nao esta vazio)" "$([ -s "$LOGK" ] && grep -vc '^kubectl --context kind-gerenciador -n gerenciador ' "$LOGK")" "0"
confere "k8s: a senha do ambiente do shell nunca vira argumento" "$(grep -c 'CANARIO' "$LOGK")" "0"
confere "k8s: db no cluster: o TRUNCATE vai ao sts/db (localhost), sem pod cliente" "$(grep -c 'exec -i sts/db.*TRUNCATE' "$LOGK")$(grep -c 'db-cliente' "$LOGK")" "10"
confere "k8s: local x remoto decidido UMA vez (1 get sts/db em toda a limpeza, que faz 2 psql)" "$(grep -c 'get sts/db' "$LOGK")$(grep -c 'exec -i sts/db' "$LOGK")" "12"
confere "k8s: guarda as replicas de antes numa anotacao de cada Deployment (backend=2), ANTES de pausar e zerar" "$(grep -c 'annotate deploy/[a-z]* carga.gerenciador/replicas-antes=' "$LOGK")|$(grep -c 'annotate deploy/backend carga.gerenciador/replicas-antes=2' "$LOGK")|$([ "$(plinha 'annotate deploy/backend carga.gerenciador/replicas-antes=2')" -lt "$(plinha 'annotate scaledobject/backend autoscaling.keda.sh/paused-replicas=0')" ] && [ "$(plinha 'annotate scaledobject/backend autoscaling.keda.sh/paused-replicas=0')" -lt "$(plinha 'scale deploy/backend --replicas=0')" ] && echo sim || echo nao)" "7|1|sim"
confere "k8s: pausa o KEDA e zera cada um dos 7 deploys; depois libera, religa e apaga a anotacao das replicas" "$(grep -c 'annotate scaledobject/[a-z]* autoscaling.keda.sh/paused-replicas=0' "$LOGK")$(grep -c 'scale deploy/[a-z]* --replicas=0' "$LOGK")$(grep -c 'annotate scaledobject/[a-z]* autoscaling.keda.sh/paused-replicas- ' "$LOGK")$(grep -c 'annotate deploy/[a-z]* carga.gerenciador/replicas-antes- ' "$LOGK")" "7777"
confere "k8s: religa com as replicas de ANTES (backend 2)" "$(grep -c 'scale deploy/backend --replicas=2' "$LOGK")$(grep -c 'scale deploy/identity --replicas=1' "$LOGK")" "11"
k_back="$(plinha 'scale deploy/backend --replicas=0')"; k_id="$(plinha 'scale deploy/identity --replicas=0')"; k_nt="$(plinha 'scale deploy/notification --replicas=0')"
k_key="$(plinha 'apply -f -')"; k_trunc="$(plinha 'TRUNCATE')"; k_flush="$(plinha 'FLUSHDB')"; k_purge="$(plinha 'purge_queue admission.requests')"
r_id="$(plinha 'scale deploy/identity --replicas=1')"; r_st="$(plinha 'scale deploy/stock --replicas=1')"; r_rs="$(plinha 'scale deploy/reservation --replicas=1')"; r_ad="$(plinha 'scale deploy/admission --replicas=1')"
r_gw="$(plinha 'scale deploy/backend --replicas=2')"; r_nt="$(plinha 'scale deploy/notification --replicas=1')"
confere "k8s: parada: backend antes do identity; todos antes de apagar a chave" "$([ "$k_back" -lt "$k_id" ] && [ "$k_nt" -lt "$k_key" ] && [ "$k_id" -lt "$k_key" ] && echo sim || echo nao)" "sim"
confere "k8s: a chave (pod descartavel no PVC identity-keys) e apagada ANTES do TRUNCATE" "$([ "$k_key" -lt "$k_trunc" ] && echo sim || echo nao)" "sim"
confere "k8s: FLUSHDB e purge das filas admission.* depois do TRUNCATE e antes de religar" "$([ "$k_trunc" -lt "$k_flush" ] && [ "$k_flush" -lt "$r_id" ] && [ "$k_purge" -lt "$r_id" ] && echo sim || echo nao)" "sim"
confere "k8s: so purga filas admission.*" "$(grep -c 'purge_queue other' "$LOGK")" "0"
confere "k8s: religa: identity, stock, reservation, admission, backend, notification" "$([ "$r_id" -lt "$r_st" ] && [ "$r_st" -lt "$r_rs" ] && [ "$r_rs" -lt "$r_ad" ] && [ "$r_ad" -lt "$r_gw" ] && [ "$r_gw" -lt "$r_nt" ] && echo sim || echo nao)" "sim"
confere "k8s: religar tira a pausa e faz o scale de TODOS antes de esperar o primeiro (uma espera estourada nao deixa os seguintes pausados)" "$([ "$(ultima 'scale deploy/[a-z]* --replicas=[1-9]')" -lt "$(plinha '{.status.readyReplicas}')" ] && echo sim || echo nao)" "sim"
confere "k8s: sonda do JWKS pela entrada do gateway (curl, nao kubectl)" "$(grep -c 'event-wallets/admission' "$LOG" | awk '{print ($1>=6)?"sim":"nao"}')" "sim"
confere "k8s: move os groups stock e stock-catalog para o fim (rpk por kubectl exec no sts/redpanda)" "$(grep -c 'exec sts/redpanda -- rpk group seek stock --to end --topics reservation.wallet' "$LOGK")$(grep -c 'exec sts/redpanda -- rpk group seek stock-catalog --to end --topics catalog.event' "$LOGK")" "11"

# um ScaledObject ja pausado (limpeza anterior abortada): recusa ANTES de parar qualquer coisa e diz como recuperar
FAKE_PAUSADOS='backend 0\nidentity 0\n'; FAKE_ANTES='backend 2\nidentity 1\n'; roda_k8s; rc=$?
confere "k8s: ScaledObject ja pausado: recusa (codigo != 0)" "$([ "$rc" -ne 0 ] && echo sim || echo nao)" "sim"
confere "k8s: ScaledObject ja pausado: nada foi parado, anotado nem apagado" "$(grep -c 'scale deploy\|annotate\|apply -f -\|TRUNCATE' "$LOGK")" "0"
confere "k8s: a recusa imprime os comandos exatos de recuperacao (tirar a pausa, scale com as replicas anotadas)" "$(grep -c 'kubectl --context kind-gerenciador -n gerenciador annotate scaledobject --all autoscaling.keda.sh/paused-replicas-$' "$SAIDAK")$(grep -c 'kubectl --context kind-gerenciador -n gerenciador scale deploy/backend --replicas=2$' "$SAIDAK")$(grep -c 'kubectl --context kind-gerenciador -n gerenciador scale deploy/identity --replicas=1$' "$SAIDAK")$(grep -c 'annotate deploy --all carga.gerenciador/replicas-antes-$' "$SAIDAK")" "1111"
# so a anotacao de replicas (sem pausa): tambem e uma limpeza que nao terminou
FAKE_PAUSADOS=""; FAKE_ANTES='stock 1\n'; roda_k8s; rc=$?
confere "k8s: anotacao de replicas deixada por uma limpeza anterior: recusa sem parar nada" "$([ "$rc" -ne 0 ] && echo sim || echo nao)$(grep -c 'scale deploy' "$LOGK")" "sim0"
FAKE_ANTES=""

FAKE_DB_REMOTO=1; roda_k8s; rc=$?
confere "k8s: db remoto: codigo 0" "$rc" "0"
confere "k8s: db remoto: o TRUNCATE vai ao pod db-cliente (aplicado e Ready UMA vez), nunca ao sts/db" "$(grep -c 'exec -i pod/db-cliente.*TRUNCATE' "$LOGK")$(grep -c 'exec -i sts/db' "$LOGK")$(grep -c 'wait --for=condition=Ready pod/db-cliente' "$LOGK")$(grep -c 'apply -f - $' "$LOGK" | awk '{print ($1>=1)?"sim":"nao"}')" "101sim"
confere "k8s: db remoto: 1 get sts/db so, mesmo com 2 psql" "$(grep -c 'get sts/db' "$LOGK")" "1"
confere "k8s: db remoto: a senha so e lida dentro do pod (PGPASSWORD=\"\$APP_DB_PASSWORD\" no sh -c) e nao vaza" "$(grep -c 'PGPASSWORD="\$APP_DB_PASSWORD"' "$LOGK" | awk '{print ($1>=1)?"sim":"nao"}')$(grep -c 'CANARIO' "$LOGK")" "sim0"
confere "k8s: db remoto: todo kubectl leva --context" "$(grep -vc '^kubectl --context kind-gerenciador ' "$LOGK")" "0"
FAKE_DB_REMOTO=0

# erro ao perguntar pelo sts/db (nao e "nao existe"): aborta antes de parar qualquer coisa; nunca cai para "remoto"
FAKE_DB_ERRO=1; roda_k8s; rc=$?
confere "k8s: erro no get sts/db: aborta sem parar nada e sem criar o pod cliente" "$([ "$rc" -ne 0 ] && echo sim || echo nao)$(grep -c 'scale deploy\|db-cliente\|annotate' "$LOGK")" "sim0"
FAKE_DB_ERRO=0

FAKE_FASE_CHAVE=Failed; roda_k8s; rc=$?
confere "k8s: chave nao apagada: aborta (codigo != 0)" "$([ "$rc" -ne 0 ] && echo sim || echo nao)" "sim"
confere "k8s: chave nao apagada: NAO religa nada (os tokens antigos valeriam) e o TRUNCATE nao roda" "$(grep -c 'scale deploy/[a-z]* --replicas=[1-9]' "$LOGK")$(grep -c 'TRUNCATE' "$LOGK")" "00"
confere "k8s: chave nao apagada: a mensagem imprime os comandos de recuperacao com as replicas de antes" "$(grep -c 'scale deploy/backend --replicas=2$' "$SAIDAK")$(grep -c 'annotate scaledobject --all autoscaling.keda.sh/paused-replicas-$' "$SAIDAK")" "11"
FAKE_FASE_CHAVE=Succeeded

FAKE_FLUSH="NOAUTH Authentication required."; roda_k8s; rc=$?
confere "k8s: FLUSHDB sem OK: aborta e RELIGA o que parou" "$([ "$rc" -ne 0 ] && echo sim || echo nao)$(grep -c 'scale deploy/[a-z]* --replicas=[1-9]' "$LOGK")" "sim7"
FAKE_FLUSH=OK

# o scale a 0 falha DEPOIS do annotate: o trap tem de religar tambem esse (a pausa ja estava posta)
FAKE_SCALE_FALHA=reservation; roda_k8s; rc=$?
confere "k8s: scale a 0 falhou depois do annotate: aborta e religa os parados, inclusive o que falhou (pausa tirada e scale de volta)" "$([ "$rc" -ne 0 ] && echo sim || echo nao)|$(grep -c 'scale deploy/reservation --replicas=1' "$LOGK")|$(grep -c 'annotate scaledobject/reservation autoscaling.keda.sh/paused-replicas- ' "$LOGK")|$(grep -c 'scale deploy/identity' "$LOGK")" "sim|1|1|0"
FAKE_SCALE_FALHA=""

# uma espera estoura (identity nao fica pronto): todos ja foram religados, o aborto imprime a recuperacao e as anotacoes ficam
FAKE_NAO_PRONTO=identity; ESPERA_HEALTHY_S=1; roda_k8s; rc=$?; ESPERA_HEALTHY_S=1
confere "k8s: espera estourada: aborta, mas tirou a pausa e fez o scale dos 7 (nenhum fica pausado)" "$([ "$rc" -ne 0 ] && echo sim || echo nao)|$(grep -c 'annotate scaledobject/[a-z]* autoscaling.keda.sh/paused-replicas- ' "$LOGK")|$(grep -c 'scale deploy/[a-z]* --replicas=[1-9]' "$LOGK")" "sim|7|7"
confere "k8s: espera estourada: as anotacoes de replicas ficam e a recuperacao e impressa" "$(grep -c 'annotate deploy/[a-z]* carga.gerenciador/replicas-antes- ' "$LOGK")|$(grep -c 'scale deploy/backend --replicas=2$' "$SAIDAK")" "0|1"
FAKE_NAO_PRONTO=""

# backend em 0 antes: a sonda do JWKS NAO foi feita e isso e falha, nunca um return 0 silencioso
FAKE_REPL[backend]=0; roda_k8s; rc=$?
confere "k8s: backend ja em 0 replicas (sonda pulada): saida != 0 com a mensagem" "$([ "$rc" -ne 0 ] && echo sim || echo nao)$(grep -c 'JWKS' "$SAIDAK" | awk '{print ($1>=1)?"sim":"nao"}')" "simsim"
FAKE_REPL[backend]=2
unset CARGA_K8S
rm -f "$LOGK" "$SAIDAK"


rm -f "$LOG" "$LOG.seq"
[ "$falhas" -eq 0 ] && echo "selftest limpar-banco: OK" || { echo "selftest limpar-banco: $falhas falha(s)"; exit 1; }
