#!/usr/bin/env bash
# Scaler da autoescala de `backend` (gateway), `reservation` (issue #111), `identity`, `catalog`, `admission` e `notification` (issue #112)
# (ADR docs/adr/Architecture-Decision-Autoescala.md, opção A). Uma só função de decisão (decidir_acao) para todos.
# Roda no HOST (Git Bash do Windows ou Linux), NUNCA em container: não monta o docker.sock. Fala com o Docker pela CLI (docker exec no
# `lb`/`redis`, docker compose para --scale) e não lê nem imprime segredo (a senha do Redis é lida DENTRO do container, por REDISCLI_AUTH).
#
# Regra 50/80 (laço de AUTOESCALA_PERIODO s; aritmética completa na ADR):
#   reservation  u = RPM do evento mais quente (adm:hot, publicado pelo admission) / (X x A)        A = réplicas ATIVAS (peso > 0 no lb)
#   gateway      u = req/s da entrada (req_tot do HAProxy, delta entre laços) / (AUTOESCALA_GATEWAY_RPS x A)
#   identity, catalog, admission (#112)  u = req/s da seção do lb do serviço (identity_interno...) / (AUTOESCALA_<SVC>_RPS x A)
#   notification (#112, consumer: sem lb e SEM stand-by, a réplica nova já entra no group e recebe partições)
#                u = lag do group `notification` em identity.notification / AUTOESCALA_NOTIFICATION_LAG (não divide por A: lag é fila, não taxa);
#                sobe uma com u > 0,50 (máx. = partições do tópico, lidas do broker, e AUTOESCALA_NOTIFICATION_MAX); desce uma com lag 0 por 60 s
#   u > 0,50, sem stand-by (S) nem réplica subindo (B) e total < máx.  -> `docker compose up --scale +1`; a nova entra no lb com peso 0
#   u >= 0,80 e S >= 1                                                  -> promove (peso 100); no reservation grava A+1 em adm:replicas
#   RPM / (X x (A-1)) < 0,50 por 60 s e fila vazia                      -> descer uma ativa: `state drain`, espera as sessões (teto 30 s), --scale -1
#   u < 0,30 por 60 s com stand-by                                      -> descarta a stand-by
#   cooldown de 30 s entre ações do mesmo serviço (a promoção, urgente, o ignora); nunca abaixo do mínimo nem derruba a última ativa
# A chave adm:replicas (réplicas ativas do reservation, TTL AUTOESCALA_CHAVE_TTL, renovada a cada laço) é lida pelo script Lua do admission;
# scaler parado => a chave expira e vale ADMISSION_RESERVATION_REPLICAS (lado seguro: entra em fila mais cedo).
#
# Uso:   autoescala/scaler.sh [--uma-vez] [--seco]      (--seco: só lê e loga o que faria, não mexe em nada)
# CSV:   ts,servico,ativas,standby,uso,acao  (um registro por serviço por laço, 6 serviços; no notification `uso` = lag / referência; AUTOESCALA_CSV, default autoescala/saida/replicas-<ts>.csv)
# Selftest da decisão (sem Docker): autoescala/scaler.selftest.sh
#
# Tudo por variável de ambiente (nenhuma é segredo), com default:
#   AUTOESCALA_PROJETO (gerenciador-eventos)  AUTOESCALA_COMPOSE_ARQUIVOS ("docker-compose.yml docker-compose.autoescala.yml", relativos a Projeto/)
#   AUTOESCALA_PERIODO (2)  AUTOESCALA_X_RPM (= ADMISSION_X_RPM, senão 56000)  AUTOESCALA_GATEWAY_RPS (500: PROVISÓRIO, calibrar na Rodada 4)
#   AUTOESCALA_IDENTITY_RPS (100) AUTOESCALA_CATALOG_RPS (500) AUTOESCALA_ADMISSION_RPS (1000) AUTOESCALA_NOTIFICATION_LAG (100): PROVISÓRIOS (Rodada 4)
#   AUTOESCALA_RESERVATION_MIN/_MAX (1/3)  AUTOESCALA_GATEWAY_MIN/_MAX (1/3; o máx. é limitado aos 3 slots do haproxy.cfg)
#   AUTOESCALA_{IDENTITY,CATALOG,ADMISSION,NOTIFICATION}_MIN/_MAX (1/3; o do notification ainda é limitado pelas partições do tópico)
#   AUTOESCALA_SOBE_PCT (50)  AUTOESCALA_PROMOVE_PCT (80)  AUTOESCALA_DESCARTA_PCT (30)  AUTOESCALA_DESCE_PCT (50)
#   AUTOESCALA_DESCE_SEG (60)  AUTOESCALA_DESCARTA_SEG (60)  AUTOESCALA_COOLDOWN_SEG (30)  AUTOESCALA_DRAIN_TETO_SEG (30)
#   AUTOESCALA_CHAVE_TTL (15)  AUTOESCALA_BOOT_TETO_SEG (120: avisa, no log, réplica que não fica saudável)

PROJETO="${AUTOESCALA_PROJETO:-gerenciador-eventos}"
PERIODO="${AUTOESCALA_PERIODO:-2}"
X_RPM="${AUTOESCALA_X_RPM:-${ADMISSION_X_RPM:-56000}}"
GATEWAY_RPS="${AUTOESCALA_GATEWAY_RPS:-500}"
SOBE="$(( ${AUTOESCALA_SOBE_PCT:-50} * 10 ))"            # limiares em milésimos (500 = 0,50)
PROMOVE="$(( ${AUTOESCALA_PROMOVE_PCT:-80} * 10 ))"
DESCARTA="$(( ${AUTOESCALA_DESCARTA_PCT:-30} * 10 ))"
DESCE="$(( ${AUTOESCALA_DESCE_PCT:-50} * 10 ))"
DESCE_SEG="${AUTOESCALA_DESCE_SEG:-60}"
DESCARTA_SEG="${AUTOESCALA_DESCARTA_SEG:-60}"
COOLDOWN_SEG="${AUTOESCALA_COOLDOWN_SEG:-30}"
DRAIN_TETO="${AUTOESCALA_DRAIN_TETO_SEG:-30}"
CHAVE_TTL="${AUTOESCALA_CHAVE_TTL:-15}"
BOOT_TETO="${AUTOESCALA_BOOT_TETO_SEG:-120}"
SLOTS=3                                                   # server-template do haproxy.cfg
SERVICOS_LB="reservation backend identity catalog admission"   # atrás do lb (haproxy.cfg)
SERVICOS="$SERVICOS_LB notification"                              # notification = consumer Kafka, sem lb
TOPICO_NOTIFICATION="identity.notification"; GROUP_NOTIFICATION="notification"
declare -A MIN=( [reservation]="${AUTOESCALA_RESERVATION_MIN:-1}" [backend]="${AUTOESCALA_GATEWAY_MIN:-1}"
    [identity]="${AUTOESCALA_IDENTITY_MIN:-1}" [catalog]="${AUTOESCALA_CATALOG_MIN:-1}" [admission]="${AUTOESCALA_ADMISSION_MIN:-1}" [notification]="${AUTOESCALA_NOTIFICATION_MIN:-1}" )
declare -A MAX=( [reservation]="${AUTOESCALA_RESERVATION_MAX:-3}" [backend]="${AUTOESCALA_GATEWAY_MAX:-3}"
    [identity]="${AUTOESCALA_IDENTITY_MAX:-3}" [catalog]="${AUTOESCALA_CATALOG_MAX:-3}" [admission]="${AUTOESCALA_ADMISSION_MAX:-3}" [notification]="${AUTOESCALA_NOTIFICATION_MAX:-3}" )
# capacidade por réplica (req/s) dos serviços HTTP e referência de lag do notification; PROVISÓRIOS (sem medida, a Rodada 4 calibra)
declare -A CAP=( [backend]="$GATEWAY_RPS" [identity]="${AUTOESCALA_IDENTITY_RPS:-100}" [catalog]="${AUTOESCALA_CATALOG_RPS:-500}"
    [admission]="${AUTOESCALA_ADMISSION_RPS:-1000}" [notification]="${AUTOESCALA_NOTIFICATION_LAG:-100}" )
declare -A LB_BACKEND=( [reservation]=reservation [backend]=gateway [identity]=identity [catalog]=catalog [admission]=admission )   # serviço do compose -> backend do haproxy.cfg
declare -A LB_FRONT=( [backend]=entrada [identity]=identity_interno [catalog]=catalog_interno [admission]=admission_interno )         # serviço -> frontend (req_tot)

# ---------------------------------------------------------------------------------------------------------------------------------
# DECISÃO (funções puras, sem Docker; o selftest as cobre)
# ---------------------------------------------------------------------------------------------------------------------------------

# uso_milli <carga> <capacidade por réplica> <réplicas>  -> carga / (capacidade x réplicas) em milésimos; sem réplica = 999999 ("sem capacidade")
uso_milli() {
    awk -v c="$1" -v cap="$2" -v n="$3" 'BEGIN { if (n <= 0 || cap <= 0) print 999999; else printf "%d", c * 1000 / (cap * n) }'
}

# condicoes <u> <u com A-1> <fila vazia 0|1> <S>  -> "<pode descer 0|1> <pode descartar 0|1>" (as condições que precisam durar DESCE_SEG/DESCARTA_SEG)
condicoes() {
    local u="$1" umenos="$2" vazia="$3" s="$4" desce=0 descarta=0
    (( umenos < DESCE && vazia == 1 )) && desce=1
    (( u < DESCARTA && s >= 1 )) && descarta=1
    echo "$desce $descarta"
}

# decidir_acao <u> <A> <S> <B> <min> <max> <seg que "desce" vale> <seg que "descarta" vale> <cooldown ok 0|1>
#   -> nada | promover | subir_min | subir | descartar | descer
decidir_acao() {
    local u="$1" a="$2" s="$3" b="$4" min="$5" max="$6" seg_desce="$7" seg_descarta="$8" cooldown_ok="$9"
    local total=$(( a + s + b ))
    if (( s >= 1 && u >= PROMOVE )); then echo promover; return; fi            # urgente: ignora o cooldown
    if (( total < min )); then echo subir_min; return; fi                       # abaixo do mínimo (réplica caiu): repõe
    if (( cooldown_ok == 0 )); then echo nada; return; fi
    if (( u > SOBE && s == 0 && b == 0 && total < max )); then echo subir; return; fi
    if (( s >= 1 && seg_descarta >= DESCARTA_SEG )); then echo descartar; return; fi
    # descer só sem stand-by (ela sai primeiro) e nunca a última ativa nem abaixo do mínimo
    if (( s == 0 && b == 0 && a > min && a > 1 && seg_desce >= DESCE_SEG )); then echo descer; return; fi
    echo nada
}

# rastrear <serviço> <desce 0|1> <descarta 0|1> <agora (s)>  -> define SEG_DESCE e SEG_DESCARTA (há quantos segundos a condição vale sem parar)
declare -A DESDE_DESCE=() DESDE_DESCARTA=()
SEG_DESCE=0 SEG_DESCARTA=0
rastrear() {
    local svc="$1" desce="$2" descarta="$3" agora="$4"
    if (( desce == 1 )); then DESDE_DESCE[$svc]="${DESDE_DESCE[$svc]:-$agora}"; else DESDE_DESCE[$svc]=""; fi
    if (( descarta == 1 )); then DESDE_DESCARTA[$svc]="${DESDE_DESCARTA[$svc]:-$agora}"; else DESDE_DESCARTA[$svc]=""; fi
    SEG_DESCE=0; SEG_DESCARTA=0
    [[ -n "${DESDE_DESCE[$svc]}" ]] && SEG_DESCE=$(( agora - DESDE_DESCE[$svc] ))
    [[ -n "${DESDE_DESCARTA[$svc]}" ]] && SEG_DESCARTA=$(( agora - DESDE_DESCARTA[$svc] ))
    return 0
}

# zera_rastreio <serviço>  -> um laço sem leitura confiável (Redis ou lb ilegível) nunca conta como "condição sustentada"
zera_rastreio() { DESDE_DESCE[$1]=""; DESDE_DESCARTA[$1]=""; }

# valida_leitura "<hot> <fila>"  -> imprime a leitura se forem exatamente dois inteiros; senão devolve 1 (nunca vira "carga 0 e fila vazia")
valida_leitura() {
    [[ "$1" =~ ^[0-9]+\ [0-9]+$ ]] || return 1
    echo "$1"
}

# rps_delta <req_tot agora> <req_tot do laço anterior> <ms entre os laços>  -> req/s; 0 sem leitura anterior, contador que voltou (lb reiniciado) ou tempo parado
rps_delta() {
    local tot="$1" ant="$2" ms="$3"
    if [[ "$tot" =~ ^[0-9]+$ && "$ant" =~ ^[0-9]+$ && "$ms" =~ ^[0-9]+$ ]] && (( ms > 0 && tot >= ant )); then
        awk -v d=$(( tot - ant )) -v ms="$ms" 'BEGIN { printf "%d", d * 1000 / ms }'
    else
        echo 0
    fi
}

# max_consumer <máx. por variável> <partições do tópico>  -> o menor: um group usa no máximo UMA réplica por partição
max_consumer() { echo $(( $1 < $2 ? $1 : $2 )); }

# lag_do_group  (stdin = `rpk group describe`)  -> TOTAL-LAG; devolve 1 se não houver um inteiro (group inexistente, erro, "-")
lag_do_group() {
    local v
    v="$(awk '$1 == "TOTAL-LAG" { print $2; exit }')"
    [[ "$v" =~ ^[0-9]+$ ]] || return 1
    echo "$v"
}

# particoes_do_topico <tópico>  (stdin = `rpk topic list`)  -> nº de partições (nome exato); devolve 1 se o tópico não aparece
particoes_do_topico() {
    local v
    v="$(awk -v t="$1" '$1 == t { print $2; exit }')"
    [[ "$v" =~ ^[0-9]+$ ]] || return 1
    echo "$v"
}

# kafka_leitura <tópico>  (stdin = `rpk group describe`, uma linha `---TOPICOS---` e `rpk topic list`)  -> "<lag> <partições>" ou 1
kafka_leitura() {
    local entrada lag part
    entrada="$(cat)"
    [[ "$entrada" == *---TOPICOS---* ]] || return 1
    lag="$(lag_do_group <<<"${entrada%%---TOPICOS---*}")" || return 1
    part="$(particoes_do_topico "$1" <<<"${entrada#*---TOPICOS---}")" || return 1
    echo "$lag $part"
}

# ---------------------------------------------------------------------------------------------------------------------------------
# LEITURA do HAProxy (parsers puros sobre a saída da Runtime API)
# ---------------------------------------------------------------------------------------------------------------------------------

# stat_campo <pxname> <svname> <campo>  (stdin = linhas CSV do `show stat`, com o cabeçalho "# pxname,svname,...")
stat_campo() {
    awk -F, -v px="$1" -v sv="$2" -v campo="$3" '
        /^# / { for (i = 1; i <= NF; i++) { n = $i; sub(/^# /, "", n); col[n] = i } next }
        $1 == px && $2 == sv && (campo in col) { print $(col[campo]); exit }'
}

# slots_do_backend <backend>  (stdin = `show servers state`)  -> "<slot> <ip> <op_state> <admin_state> <peso>" por slot com endereço
slots_do_backend() {
    awk -v be="$1" '$1 !~ /^#/ && NF >= 8 && $2 == be && $5 != "-" && $5 != "0.0.0.0" { print $4, $5, $6, $7, $8 }'
}

# ---------------------------------------------------------------------------------------------------------------------------------
# EXECUÇÃO (Docker). Daqui para baixo nada é coberto pelo selftest: o dry-run da issue exercita.
# ---------------------------------------------------------------------------------------------------------------------------------

SECO=0
UMA_VEZ=0
LB="" REDIS="" REDPANDA="" CSV="" STAT=""
declare -A ULTIMA_ACAO=() PEND_STANDBY=() BOOT_DESDE=()
declare -A E_A=() E_S=() E_B=() E_N=()                 # por serviço: ativas, stand-by, subindo, total
declare -A ID_SVC=() ID_NUM=() ID_SLOT=() ID_PAPEL=()  # por id (12 caracteres) de container; papel = ativa | standby | subindo
declare -A REQ_ANT=() T_ANT=()                        # por serviço HTTP: req_tot do laço anterior e o relógio (ms)

log() { printf '%s [scaler] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
agora() { date +%s; }
agora_ms() { local t; t="$(date +%s%N)"; echo $(( t / 1000000 )); }

compose() {
    local args=() f
    for f in ${AUTOESCALA_COMPOSE_ARQUIVOS:-docker-compose.yml docker-compose.autoescala.yml}; do
        if [[ "$f" == /* || "$f" == [A-Za-z]:* ]]; then args+=(-f "$f"); else args+=(-f "$PROJETO_DIR/$f"); fi   # relativo a Projeto/, ou absoluto
    done
    docker compose -p "$PROJETO" "${args[@]}" "$@"
}

id_do_servico() { # primeiro container rodando de um serviço do compose
    docker ps -q --filter "label=com.docker.compose.project=$PROJETO" --filter "label=com.docker.compose.service=$1" | head -1
}

# lb_cmd <comando>...  -> a resposta de cada comando da Runtime API (127.0.0.1:9999 DENTRO do lb, por /dev/tcp; bash existe na imagem Debian)
lb_cmd() {
    docker exec "$LB" bash -c 'for c in "$@"; do exec 3<>/dev/tcp/127.0.0.1/9999; printf "%s\n" "$c" >&3; cat <&3; exec 3>&-; done' _ "$@"
}

# redis_ciclo <valor de adm:replicas ou vazio>  -> "<RPM mais quente> <esperas pendentes>"; a senha fica no ambiente do container.
# Qualquer resposta que não seja a esperada (PING, SET, GET, TIME, ZCOUNT) sai com 1: leitura ruim nunca vira "carga 0".
redis_ciclo() {
    docker exec "$REDIS" sh -c '
        export REDISCLI_AUTH="$REDIS_PASSWORD"
        num() { case "$1" in ""|*[!0-9]*) return 1;; esac; }
        [ "$(redis-cli PING)" = PONG ] || exit 1
        if [ -n "$1" ]; then [ "$(redis-cli SET adm:replicas "$1" EX "$2")" = OK ] || exit 1; fi
        hot="$(redis-cli GET adm:hot)"
        case "$hot" in *[!0-9]*) exit 1;; esac
        now="$(redis-cli TIME | head -1)"; num "$now" || exit 1; now="${now}000"
        fila=0
        for k in $(redis-cli --scan --pattern "adm:wait:*"); do
            n="$(redis-cli ZCOUNT "$k" "$now" +inf)"; num "$n" || exit 1
            fila=$((fila + n))
        done
        echo "${hot:-0} $fila"' _ "$1" "$CHAVE_TTL"
}

# ler_estado  -> STAT (show stat) e, por container do compose: ID_SVC/ID_NUM/ID_SLOT/ID_PAPEL; por serviço: E_A/E_S/E_B/E_N.
# Container <-> slot do lb pelo IP. ativa = slot UP com peso > 0; standby = UP com peso 0; subindo = o resto (sem slot, ainda DOWN ou em drain).
# O notification (consumer, sem lb) não tem slot: ativa = container healthy; subindo = o resto; nunca stand-by.
# Qualquer falha de leitura (lb mudo, docker ps/inspect com erro ou vazio) devolve 1 SEM tocar nas contagens: o laço não age. Uma lista de
# containers vazia por falha viraria E_N=0 e `subir_min` (`--scale svc=1`), que removeria réplicas sem drain.
ler_estado() {
    local saida estados ids ps_all insp n_ids n_insp id id12 svc num saude ips ip b chave slot op adm peso
    local -A s_nome=() s_op=() s_adm=() s_peso=()
    saida="$(lb_cmd "show stat" "show servers state")" || return 1
    [[ -n "$saida" ]] || return 1
    # UM `docker ps` do project (cada chamada ao docker custa ~0,2 s no Windows); filtra aqui os serviços escalados
    ps_all="$(docker ps --filter "label=com.docker.compose.project=$PROJETO" --format '{{.ID}} {{.Label "com.docker.compose.service"}}')" || return 1
    ids=""
    for svc in $SERVICOS; do ids+="$(awk -v s="$svc" '$2 == s { print $1 }' <<<"$ps_all")"$'\n'; done
    ids="$(grep -v '^$' <<<"$ids")"
    [[ -n "$ids" ]] || return 1
    # shellcheck disable=SC2086
    insp="$(docker inspect -f '{{.Id}} {{index .Config.Labels "com.docker.compose.service"}} {{index .Config.Labels "com.docker.compose.container-number"}} {{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}} {{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' $ids)" || return 1
    n_ids="$(grep -c . <<<"$ids")"; n_insp="$(grep -c . <<<"$insp")"
    [[ "$n_ids" == "$n_insp" ]] || return 1
    STAT="$(grep ',' <<<"$saida")"
    estados="$(grep -v ',' <<<"$saida")"
    for svc in $SERVICOS; do
        E_A[$svc]=0; E_S[$svc]=0; E_B[$svc]=0; E_N[$svc]=0
        b="${LB_BACKEND[$svc]:-}"
        [[ -n "$b" ]] || continue
        while read -r slot ip op adm peso; do
            s_nome[$b|$ip]="$slot"; s_op[$b|$ip]="$op"; s_adm[$b|$ip]="$adm"; s_peso[$b|$ip]="$peso"
        done < <(slots_do_backend "$b" <<<"$estados")
    done
    ID_SVC=() ID_NUM=() ID_SLOT=() ID_PAPEL=()
    while read -r id svc num saude ips; do
        id12="${id:0:12}"
        b="${LB_BACKEND[$svc]:-}"
        ID_SVC[$id12]="$svc"; ID_NUM[$id12]="$num"; ID_SLOT[$id12]=""; ID_PAPEL[$id12]=subindo
        E_N[$svc]=$(( E_N[$svc] + 1 ))
        if [[ -z "$b" ]]; then
            [[ "$saude" == healthy ]] && ID_PAPEL[$id12]=ativa
        else
            for ip in $ips; do
                chave="$b|$ip"
                [[ -n "${s_nome[$chave]:-}" ]] || continue
                ID_SLOT[$id12]="${s_nome[$chave]}"
                if [[ "${s_op[$chave]}" == 2 && "${s_adm[$chave]}" == 0 ]]; then
                    if (( s_peso[$chave] > 0 )); then ID_PAPEL[$id12]=ativa; else ID_PAPEL[$id12]=standby; fi
                fi
            done
        fi
        # réplica que o scaler acabou de criar e ainda não pôs em stand-by (peso 0): pode já estar UP com peso 100 (JVM rápido), mas NÃO é capacidade
        # ativa; contá-la superestimaria A e o adm:replicas (o lado inseguro)
        [[ -n "${PEND_STANDBY[$id12]:-}" ]] && ID_PAPEL[$id12]=subindo
        case "${ID_PAPEL[$id12]}" in
            ativa) E_A[$svc]=$(( E_A[$svc] + 1 )) ;;
            standby) E_S[$svc]=$(( E_S[$svc] + 1 )) ;;
            *) E_B[$svc]=$(( E_B[$svc] + 1 )) ;;
        esac
    done <<<"$insp"
}

ids_do_servico() { # ids do serviço, do mais novo (maior container-number, o que o `--scale` N-1 remove) ao mais velho
    local id
    for id in "${!ID_SVC[@]}"; do
        [[ "${ID_SVC[$id]}" == "$1" ]] && echo "${ID_NUM[$id]} $id"
    done | sort -rn | awk '{print $2}'
}

lb_servidor() { # lb_servidor <svc> <slot> <argumentos de `set server`>...
    local svc="$1" slot="$2"; shift 2
    if (( SECO == 1 )); then log "SECO: set server ${LB_BACKEND[$svc]}/$slot $*"; return 0; fi
    lb_cmd "set server ${LB_BACKEND[$svc]}/$slot $*" | grep -v '^$' >&2 || true
}

escalar() { # escalar <svc> <n>  -> docker compose up --scale (só esse serviço, sem recriar nem subir dependências)
    local svc="$1" n="$2"
    if (( SECO == 1 )); then log "SECO: compose up --scale $svc=$n"; return 0; fi
    compose up -d --no-recreate --no-deps --scale "$svc=$n" "$svc" >/dev/null 2>&1 || { log "ERRO: compose up --scale $svc=$n falhou"; return 1; }
}

grava_chave() { # renova adm:replicas (só o reservation) com o valor dado
    (( SECO == 1 )) && return 0
    redis_ciclo "$1" >/dev/null 2>&1 || log "AVISO: não consegui gravar adm:replicas"
}

acao_subir() { # acao_subir <svc> <standby 0|1>: +1 réplica; a stand-by entra no lb com peso 0 assim que ele a enxerga
    local svc="$1" standby="$2" antes id
    antes=" ${!ID_SVC[*]} "
    escalar "$svc" $(( E_N[$svc] + 1 )) || return 1
    (( SECO == 1 )) && return 0
    for id in $(docker ps -q --filter "label=com.docker.compose.project=$PROJETO" --filter "label=com.docker.compose.service=$svc"); do
        [[ "$antes" == *" $id "* ]] && continue
        (( standby == 1 )) && PEND_STANDBY[$id]="$svc"
        BOOT_DESDE[$id]="$(agora)"
        log "$svc: container novo $id ($( (( standby == 1 )) && echo stand-by || echo ativa ))"
    done
}

aplica_pendentes() { # põe em stand-by (peso 0) a réplica nova assim que o lb a enxerga: o JVM leva alguns segundos (3–7 s no dry-run) para passar no health check
    local id slot
    for id in "${!PEND_STANDBY[@]}"; do
        if [[ -z "${ID_SVC[$id]:-}" ]]; then unset "PEND_STANDBY[$id]"; continue; fi
        slot="${ID_SLOT[$id]}"
        [[ -z "$slot" ]] && continue
        lb_servidor "${PEND_STANDBY[$id]}" "$slot" weight 0
        log "${PEND_STANDBY[$id]}: $id no slot $slot em stand-by (peso 0)"
        unset "PEND_STANDBY[$id]"
    done
}

acao_promover() { # a stand-by mais antiga passa a receber tráfego (peso 100)
    local svc="$1" id
    for id in $(ids_do_servico "$svc" | tac); do
        if [[ "${ID_PAPEL[$id]}" == standby ]]; then
            lb_servidor "$svc" "${ID_SLOT[$id]}" weight 100
            log "$svc: $id (slot ${ID_SLOT[$id]}) promovida a ativa"
            return 0
        fi
    done
    log "$svc: promoção pedida, mas não há stand-by UP"
    return 1
}

acao_remover() { # acao_remover <svc> <descartar|descer>: escoa e derruba o container que o compose removeria (o de maior container-number)
    local svc="$1" tipo="$2" alvo slot fim restantes ativas="${E_A[$1]}" chave_val
    chave_val="${E_A[reservation]}"
    alvo="$(ids_do_servico "$svc" | head -1)"
    [[ -z "$alvo" ]] && return 1
    slot="${ID_SLOT[$alvo]}"
    if [[ "$tipo" == descartar && "${ID_PAPEL[$alvo]}" != standby ]]; then
        log "AVISO $svc: o container que o --scale removeria ($alvo) não é a stand-by; nada feito"; return 1
    fi
    [[ "$tipo" == descer ]] && ativas=$(( ativas - 1 ))
    [[ "$svc" == reservation ]] && chave_val="$ativas"    # no drain do gateway a chave do reservation segue valendo A (TTL 15 s)
    log "$svc: $tipo $alvo (slot ${slot:-?}, ${ID_PAPEL[$alvo]})"
    if [[ -n "$slot" ]]; then
        [[ "$svc" == reservation && "$tipo" == descer ]] && grava_chave "$chave_val"   # a capacidade do admission cai ANTES de a réplica sair
        lb_servidor "$svc" "$slot" state drain
        fim=$(( $(agora) + DRAIN_TETO ))
        restantes=0
        while (( SECO == 0 && $(agora) < fim )); do
            restantes="$(lb_cmd "show stat" | stat_campo "${LB_BACKEND[$svc]}" "$slot" scur)"
            [[ "${restantes:-0}" == 0 ]] && break
            (( chave_val > 0 )) && grava_chave "$chave_val"
            sleep 1
        done
        log "$svc: sessões ainda abertas no slot $slot ao parar: ${restantes:-0}"
    fi
    if ! escalar "$svc" $(( E_N[$svc] - 1 )); then   # o container segue no ar: o slot não pode ficar em drain para sempre
        [[ -n "$slot" ]] && lb_servidor "$svc" "$slot" state ready
        return 1
    fi
    # o slot volta ao estado de fábrica: o próximo container que o DNS puser nele não pode herdar drain nem peso 0
    if [[ -n "$slot" ]]; then lb_servidor "$svc" "$slot" state ready; lb_servidor "$svc" "$slot" weight 100; fi
}

limpa_admin_orfao() { # início: slot em drain/maint de um scaler anterior morto no meio de uma descida volta a "ready"
    local svc slot ip op adm peso
    for svc in $SERVICOS_LB; do
        while read -r slot ip op adm peso; do
            if [[ "$adm" != 0 ]]; then log "$svc: slot $slot com admin_state $adm: volta a ready"; lb_servidor "$svc" "$slot" state ready; fi
        done < <(slots_do_backend "${LB_BACKEND[$svc]}" < <(lb_cmd "show servers state"))
    done
}

linha_csv() { # <servico> <ativas> <standby> <uso em milésimos> <ação>
    printf '%s,%s,%s,%s,%d.%03d,%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$1" "$2" "$3" $(( $4 / 1000 )) $(( $4 % 1000 )) "$5" >>"$CSV"
}

# kafka_ciclo  -> "<lag total do group notification> <partições de identity.notification>" lidos do broker (rpk DENTRO do redpanda, sem segredo).
# Qualquer falha (exec, group inexistente, lag ou partições não numéricos) sai com 1: leitura ruim nunca vira "lag 0".
kafka_ciclo() {
    docker exec "$REDPANDA" bash -c 'rpk group describe "$1" && echo ---TOPICOS--- && rpk topic list' _ "$GROUP_NOTIFICATION" 2>/dev/null \
        | tr -d '\r' | kafka_leitura "$TOPICO_NOTIFICATION"
}

ciclo() {
    local agora_s svc a s b u umenos vazia cond acao cooldown_ok carga cap hot fila saida tot tms chave id leitura_ok maximo
    local lag particoes kafka_ok
    agora_s="$(agora)"
    if ! ler_estado; then   # lb mudo ou docker com erro: não age, e o laço cego não conta como "condição sustentada"
        log "AVISO: não consegui ler o lb/docker (fora do ar?): nenhuma decisão neste laço"
        for svc in $SERVICOS; do zera_rastreio "$svc"; done
        return 1
    fi
    aplica_pendentes
    chave=""; (( SECO == 0 && E_A[reservation] > 0 )) && chave="${E_A[reservation]}"
    leitura_ok=1
    saida="$(redis_ciclo "$chave" 2>/dev/null)" && saida="$(valida_leitura "${saida//$'\r'/}")" || leitura_ok=0
    if (( leitura_ok == 1 )); then read -r hot fila <<<"$saida"; fi
    kafka_ok=1
    saida="$(kafka_ciclo 2>/dev/null)" && saida="$(valida_leitura "$saida")" || kafka_ok=0
    if (( kafka_ok == 1 )); then read -r lag particoes <<<"$saida"; fi
    tms="$(agora_ms)"
    for svc in $SERVICOS; do
        a="${E_A[$svc]}"; s="${E_S[$svc]}"; b="${E_B[$svc]}"; maximo="${MAX[$svc]}"
        case "$svc" in
            reservation)
                if (( leitura_ok == 0 )); then
                    # Redis ilegível (fora, recriado, NOAUTH...): NUNCA vira carga 0 e fila vazia (desceria réplicas sob carga real)
                    log "AVISO: leitura do Redis falhou: o reservation não decide neste laço"
                    zera_rastreio reservation
                    linha_csv reservation "$a" "$s" 0 sem_leitura
                    continue
                fi
                carga="${hot:-0}"; cap="$X_RPM"; vazia=$(( ${fila:-0} == 0 ? 1 : 0 ))
                u="$(uso_milli "$carga" "$cap" "$a")"; umenos="$(uso_milli "$carga" "$cap" $(( a - 1 )))" ;;
            notification)
                if (( kafka_ok == 0 )); then
                    log "AVISO: leitura do lag/partições do Kafka falhou: o notification não decide neste laço"
                    zera_rastreio notification
                    linha_csv notification "$a" "$s" 0 sem_leitura
                    continue
                fi
                # consumer: o lag é fila (backlog), não taxa, então não divide por A; sem stand-by. Descer só com o lag zerado (vazia = lag 0).
                carga="$lag"; cap="${CAP[notification]}"; vazia=$(( lag == 0 ? 1 : 0 ))
                maximo="$(max_consumer "${MAX[notification]}" "$particoes")"
                u="$(uso_milli "$carga" "$cap" 1)"; umenos="$u" ;;
            *)
                # HTTP atrás do lb: req/s da seção do lb (delta de req_tot entre laços; mais estável que o req_rate de 1 s)
                tot="$(stat_campo "${LB_FRONT[$svc]}" FRONTEND req_tot <<<"$STAT")"
                if [[ ! "$tot" =~ ^[0-9]+$ ]]; then
                    # frontend ausente/ilegível no `show stat`: NUNCA vira carga 0 (desceria réplica sob carga real)
                    log "AVISO: req_tot de ${LB_FRONT[$svc]} ilegível: o $svc não decide neste laço"
                    zera_rastreio "$svc"
                    linha_csv "$svc" "$a" "$s" 0 sem_leitura
                    continue
                fi
                carga="$(rps_delta "$tot" "${REQ_ANT[$svc]:-}" $(( tms - ${T_ANT[$svc]:-tms} )))"
                REQ_ANT[$svc]="$tot"; T_ANT[$svc]="$tms"
                cap="${CAP[$svc]}"; vazia=1
                u="$(uso_milli "$carga" "$cap" "$a")"; umenos="$(uso_milli "$carga" "$cap" $(( a - 1 )))" ;;
        esac
        cond="$(condicoes "$u" "$umenos" "$vazia" "$s")"
        rastrear "$svc" "${cond% *}" "${cond#* }" "$agora_s"
        cooldown_ok=1; (( agora_s - ${ULTIMA_ACAO[$svc]:-0} < COOLDOWN_SEG )) && cooldown_ok=0
        acao="$(decidir_acao "$u" "$a" "$s" "$b" "${MIN[$svc]}" "$maximo" "$SEG_DESCE" "$SEG_DESCARTA" "$cooldown_ok")"
        case "$acao" in
            promover)
                if acao_promover "$svc"; then
                    [[ "$svc" == reservation ]] && grava_chave $(( a + 1 ))
                    ULTIMA_ACAO[$svc]="$agora_s"
                fi ;;
            subir_min) acao_subir "$svc" 0 && ULTIMA_ACAO[$svc]="$agora_s" ;;
            subir)     acao_subir "$svc" "$([[ "$svc" == notification ]] && echo 0 || echo 1)" && ULTIMA_ACAO[$svc]="$agora_s" ;;
            descartar|descer)
                acao_remover "$svc" "$acao" && ULTIMA_ACAO[$svc]="$(agora)"
                DESDE_DESCE[$svc]=""; DESDE_DESCARTA[$svc]="" ;;
        esac
        [[ "$acao" != nada ]] && log "$svc: $acao (uso $u/1000, A=$a S=$s B=$b, carga=$carga)"
        linha_csv "$svc" "$a" "$s" "$u" "$acao"
    done
    # réplica que não vira UP no lb: só avisa (não há remédio automático seguro)
    for id in "${!BOOT_DESDE[@]}"; do
        if [[ -z "${ID_SVC[$id]:-}" || "${ID_PAPEL[$id]}" != subindo ]]; then unset "BOOT_DESDE[$id]"; continue; fi
        if (( agora_s - BOOT_DESDE[$id] > BOOT_TETO )); then
            log "AVISO: o container $id não ficou UP no lb há mais de ${BOOT_TETO}s"; BOOT_DESDE[$id]="$agora_s"
        fi
    done
}

principal() {
    local a svc aqui gasto resto t0
    for a in "$@"; do
        case "$a" in --uma-vez) UMA_VEZ=1 ;; --seco) SECO=1 ;; *) echo "uso: $0 [--uma-vez] [--seco]" >&2; exit 2 ;; esac
    done
    export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # Git Bash: não converter /dev/tcp & cia nos argumentos do docker
    aqui="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    PROJETO_DIR="$(cd "$aqui/.." && { pwd -W 2>/dev/null || pwd; })"
    for svc in $SERVICOS; do
        if [[ -n "${LB_BACKEND[$svc]:-}" ]] && (( MAX[$svc] > SLOTS )); then log "AVISO: máx. de $svc (${MAX[$svc]}) > $SLOTS slots do haproxy.cfg: usando $SLOTS"; MAX[$svc]=$SLOTS; fi
        if (( MIN[$svc] > MAX[$svc] )); then log "ERRO: mínimo de $svc maior que o máximo"; exit 2; fi
    done
    LB="$(id_do_servico lb | cut -c1-12)"; REDIS="$(id_do_servico redis | cut -c1-12)"; REDPANDA="$(id_do_servico redpanda | cut -c1-12)"
    [[ -n "$LB" ]] || { log "ERRO: container do lb não encontrado (projeto $PROJETO). Subiu com o override docker-compose.autoescala.yml?"; exit 1; }
    [[ -n "$REDIS" ]] || { log "ERRO: container do redis não encontrado (projeto $PROJETO)"; exit 1; }
    [[ -n "$REDPANDA" ]] || { log "ERRO: container do redpanda não encontrado (projeto $PROJETO)"; exit 1; }
    CSV="${AUTOESCALA_CSV:-$aqui/saida/replicas-$(date '+%Y%m%d-%H%M%S').csv}"
    mkdir -p "$(dirname "$CSV")"
    [[ -s "$CSV" ]] || echo "ts,servico,ativas,standby,uso,acao" >"$CSV"
    log "projeto=$PROJETO periodo=${PERIODO}s X=$X_RPM gateway_rps=$GATEWAY_RPS identity_rps=${CAP[identity]} catalog_rps=${CAP[catalog]} admission_rps=${CAP[admission]} notification_lag=${CAP[notification]} seco=$SECO csv=$CSV"
    for svc in $SERVICOS; do log "  $svc: ${MIN[$svc]}..${MAX[$svc]} réplicas"; done
    (( SECO == 0 )) && limpa_admin_orfao
    trap 'log "encerrado (adm:replicas expira em ${CHAVE_TTL}s e o admission volta ao ADMISSION_RESERVATION_REPLICAS)"' EXIT
    while :; do
        t0="$(agora_ms)"
        ciclo
        (( UMA_VEZ == 1 )) && break
        gasto=$(( $(agora_ms) - t0 ))
        resto="$(awk -v p="$PERIODO" -v g="$gasto" 'BEGIN { r = p - g / 1000; if (r < 0.2) r = 0.2; printf "%.2f", r }')"
        sleep "$resto"
    done
}

# `. scaler.sh` (selftest) só carrega as funções; executado, roda o laço
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then principal "$@"; fi
