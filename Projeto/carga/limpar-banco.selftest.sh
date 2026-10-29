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
declare -A FAKE_IDS=( [identity]="aaaaaaaaaa01 aaaaaaaaaa02" [reservation]="bbbbbbbbbb01" [catalog]="cccccccccc01" [admission]="dddddddddd01"
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
s_nt="$(linha 'docker start ffffffffff01')"
confere "ordem de parada: backend antes do identity" "$([ "$p_back" -lt "$p_id" ] && echo sim || echo nao)" "sim"
confere "a chave so e apagada DEPOIS de parar o identity e o backend" "$([ "$p_id" -lt "$p_key" ] && echo sim || echo nao)" "sim"
confere "a chave e apagada ANTES do TRUNCATE" "$([ "$p_key" -lt "$p_trunc" ] && echo sim || echo nao)" "sim"
confere "volume derivado do project (${PROJETO}_identity-keys), nao fixo" "$(grep -c 'identity-keys:/k' "$LOG" | awk '{print ($1==1)?"sim":"nao"}')" "sim"
confere "FLUSHDB e purge das filas admission.* com os servicos parados (depois do TRUNCATE, antes de religar)" "$([ "$p_trunc" -lt "$p_flush" ] && [ "$p_flush" -lt "$s_id" ] && [ "$p_purge" -lt "$s_id" ] && echo sim || echo nao)" "sim"
confere "so purga filas admission.*" "$(grep -c 'purge_queue other' "$LOG")" "0"
confere "religa: TODAS as replicas do identity, depois reservation+catalog, depois admission, depois TODAS as do backend" "$([ "$s_id" -lt "$s_rc" ] && [ "$s_rc" -lt "$s_ad" ] && [ "$s_ad" -lt "$s_gw" ] && echo sim || echo nao)" "sim"
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

rm -f "$LOG" "$LOG.seq"
[ "$falhas" -eq 0 ] && echo "selftest limpar-banco: OK" || { echo "selftest limpar-banco: $falhas falha(s)"; exit 1; }
