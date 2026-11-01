#!/usr/bin/env bash
# =============================================================================
#  coleta-host.sh -- metricas do banco/broker no HOST durante uma rodada (issue #38)
# =============================================================================
#  Roda no HOST (Git Bash), ao lado do telemetria-host.ps1, enquanto o gerador
#  dispara a escada/rpm/leitura. Le so pelo docker exec (psql e rpk dentro dos
#  containers; nenhum segredo do .env). Grava em Projeto/carga/results/:
#
#    coleta-host-<rotulo>-<ts>.csv  1 linha por INTERVALO_S:
#      epoch_ms                       instante (UTC, ms; cruza com os CSVs de telemetria)
#      lag_projecao_ms                write -> projecao: MAX(applied_at - last_occurred_at) de
#                                     reservation.event_availability nas linhas aplicadas no ultimo
#                                     intervalo (-1 = nada aplicado no intervalo)
#      lag_reservation, lag_reservation_catalog, lag_notification
#                                     lag do consumer (TOTAL-LAG do rpk group describe; -1 = grupo ausente)
#      outbox_reservation, outbox_catalog, outbox_identity
#                                     linhas pendentes nos event_outbox (relay atrasado)
#      pg_conexoes, pg_ativas, pg_idle, pg_idle_tx, pg_espera_lock
#                                     lado servidor do pooler: conexoes que o Supavisor (e os testes)
#                                     mantem no meu_banco, por estado (pg_stat_activity)
#      walletevent_ins                inserts acumulados em reservation.walletevent (pg_stat_user_tables.n_tup_ins; -1 = sem leitura)
#    Colunas da Rodada 4 (issue #178), SEMPRE no fim (-1 = sem leitura: container ausente, stack sem admission):
#      rabbit_requests, rabbit_requests_unacked, rabbit_consumidores
#                                     fila admission.requests: prontas, entregues sem ack, consumidores (workers). UMA chamada
#                                     `rabbitmqctl list_queues` por amostra (o CLI Erlang e lento: veja duracao_amostra_ms). Com TIMEOUT_S: se estourar
#                                     (broker reiniciando / em alarme de memoria), as 4 colunas rabbit_* da amostra ficam em -1
#      rabbit_timed_out               mensagens paradas na dead-letter admission.timed_out. O admission a CONSOME (listener da DLX), entao e
#                                     profundidade instantanea (~0), NAO o total de expiradas: o total vem do k6 (taxa de timed_out)
#      walletevent_ins_delta          reservas gravadas desde a amostra anterior (-1 na 1a). SUBSTITUTO da taxa de consumo da fila: sem o plugin
#                                     de management o rabbitmqctl nao da taxa; reservas aceitas/amostra = o que o worker pool (e o sincrono) gravou
#      redis_hot                      RPM do evento mais quente da janela (chave adm:hot, publicada pelo admission); 0 = chave expirada
#      redis_replicas                 adm:replicas (replicas ATIVAS do reservation que o scaler publica; 0 = ausente = scaler parado)
#      redis_dbsize                   chaves no Redis (cresce com adm:res:* sob fila: pressao de memoria)
#      redis_eventos_em_fila, redis_espera_total
#                                     eventos com adm:mode:* (modo fila) e soma de adm:wait:* dos eventos em fila; so nas amostras com SCAN
#                                     (a cada EVENTOS_CADA_S; nas outras -1). SCAN, nunca KEYS: percorre o keyspace inteiro, por isso e espacado.
#                                     A espera e o ZCARD de adm:wait:<ev>, que INCLUI membros ja vencidos (o admission so limpa o ZSET quando decide):
#                                     depois que a carga cai ela superestima a fila real ate o proximo pedido naquele evento
#                                     Redis com TIMEOUT_S estourado: as colunas redis_* da amostra ficam em -1
#      repl_backend, repl_reservation, repl_identity, repl_catalog, repl_admission, repl_notification
#                                     containers EM EXECUCAO de cada servico (1 `docker ps` filtrado por label do compose/amostra; stand-by
#                                     inclusive: ativas x stand-by estao no CSV do scaler). Replicas x tempo. `docker ps` estourou TIMEOUT_S = -1
#      duracao_amostra_ms             quanto a amostra levou (se passar do INTERVALO_S, o intervalo real e esse)
#    Colunas da Rodada 5 (issue #204, saga stock + wallet), DEPOIS de duracao_amostra_ms (as 31 anteriores nao mudam; -1 = sem leitura):
#      lag_stock, lag_stock_catalog   TOTAL-LAG dos groups `stock` (devolucao da vaga, reservation.wallet) e `stock-catalog` (catalog.event), num UNICO
#                                     `rpk group describe stock stock-catalog`; group ausente (ou Dead) = -1
#      saga_reservando, saga_orfas, saga_compensando
#                                     sagas em reservation.idempotency_key: `reserving`; `reserving` com updated_at mais velho que ORPHAN_MS (orfas);
#                                     `compensating`. So contagens do indice parcial idx_idempotency_key_saga_pendente, num 2o `-c` do MESMO psql da
#                                     amostra (nenhum docker exec a mais alem do rpk): a R5 compara com a R4, a coleta nao pode custar mais
#      Regra de custo: todo JOIN entre schemas e todo percentil ficam no FIM da coleta (finalizar), nunca por amostra
#    saga-<rotulo>-<ts>.csv         (no fim) compensacoes por minuto: minuto,compensadas_inline,compensadas_reaper (resolved_at/resolved_by da janela)
#    resumo-saga-<rotulo>-<ts>.txt  (no fim; tambem impresso) tempo ate resolver (resolved_at - created_at das compensated, por inline/reaper: p50/p95/max),
#                                   lag de devolucao (hold.updated_at - occurred_at do Cancelled/Expired, p50/p95/max), estado no fim do bloco
#                                   (devolucao pendente, orfa inversa, sagas reserving/compensating) e a latencia dos passos (logger saga.timing das
#                                   replicas do reservation, `docker logs --since` o inicio da coleta; so existe com LOGGING_LEVEL_SAGA_TIMING=DEBUG).
#                                   Janela: linhas >= o inicio desta coleta. RODE ANTES do limpar-banco.sh: ele trunca as linhas que o resumo le.
#    eventos-<rotulo>-<ts>.csv      formato longo, um registro por evento em cada amostra com SCAN: epoch_ms,evento,soma_buckets,rpm_aprox,modo,espera
#                                   (soma_buckets = contagem dos buckets vivos do Redis; rpm_aprox = soma x 60000 / ADMISSION_WINDOW_MS: aproximado, ate
#                                   ~25% a mais por causa do bucket extra vivo; o exato do mais quente e redis_hot). modo 1 = adm:mode:<evento> existe
#    registro-<rotulo>-<ts>.txt     registro da rodada (so nomes allowlistados, nenhum segredo): data, containers por servico, AUTOESCALA_*/ADMISSION_*
#                                   do ambiente DESTE shell (exporte aqui os mesmos do scaler/up) e as variaveis publicas lidas dos containers
#    pgss-<rotulo>-<ts>.csv         top 25 do pg_stat_statements por tempo total, desde o reset
#                                   feito no inicio desta coleta
#
#    MODO CLUSTER (issue #228): com CARGA_K8S=<contexto kubectl> (ex.: kind-gerenciador) a coleta le o cluster Kubernetes (Projeto/k8s) no lugar do compose, sem Docker; sem a
#    variavel nada disto e carregado. As MESMAS 36 colunas. Todo kubectl leva --context $CARGA_K8S (nunca o current-context), namespace gerenciador. psql = sts/db (db no cluster) ou
#    o pod cliente db-cliente (db remoto; senha lida DENTRO do pod: k8s/lib-db-cliente.sh); rpk/redis-cli/rabbitmqctl por `kubectl exec` (sts/redpanda, deploy/redis, deploy/rabbitmq);
#    repl_* = readyReplicas de cada Deployment; ORPHAN_MS e as variaveis do registro lidos do Deployment reservation; saga.timing por `kubectl logs -l app=reservation --since-time`.
#    Todas as 36 colunas tem equivalente (redis_replicas = a chave adm:replicas, que no cluster o proprio admission grava com o readyReplicas do reservation: ADMISSION_K8S_REPLICAS_ENABLED).
#    Limites: a latencia dos passos (saga.timing) so cobre os pods que ainda existem no fim do bloco (um pod que a autoescala removeu leva os logs junto); o `lb` e o stand-by nao existem.
#
#  Uso:  ./coleta-host.sh [rotulo] [duracao_s]      (duracao 0 = ate o Ctrl+C)
#  Env:  INTERVALO_S (1), CONTAINER_DB (gerenciador-db), CONTAINER_RPK (gerenciador-redpanda),
#        PG_ADMIN (supabase_admin: cria a extensao e reseta o pg_stat_statements),
#        CONTAINER_REDIS (gerenciador-redis), CONTAINER_RABBIT (gerenciador-rabbitmq) -- nomes fixos, nao escalam --,
#        PROJETO (gerenciador-eventos: o project do compose; as replicas dos servicos se acham por label), EVENTOS_CADA_S (5),
#        ADMISSION_WINDOW_MS (200, so para o rpm_aprox), TIMEOUT_S (5: teto de cada coleta lenta -- rabbitmqctl e Redis rodam sob `timeout` DENTRO do
#        container (as imagens alpine tem o `timeout` do busybox) E o `docker exec`/`docker ps` sob `timeout` no host (+2 s) --; estourou = -1 nas colunas, o laco nao trava)
#        ORPHAN_MS (lido UMA vez do RESERVATION_SAGA_ORPHAN_MS do container reservation do project; 30000 se nao achar: o limiar de saga_orfas; exporte
#        para forcar outro, ex. o dry-run com limiar reduzido)
# =============================================================================
set -uo pipefail

INTERVALO_S="${INTERVALO_S:-1}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
CONTAINER_RPK="${CONTAINER_RPK:-gerenciador-redpanda}"
PG_ADMIN="${PG_ADMIN:-supabase_admin}"
CONTAINER_REDIS="${CONTAINER_REDIS:-gerenciador-redis}"
CONTAINER_RABBIT="${CONTAINER_RABBIT:-gerenciador-rabbitmq}"
PROJETO="${PROJETO:-gerenciador-eventos}"
if [ -n "${CARGA_K8S:-}" ]; then # modo cluster (#228): a lib do pod cliente do banco e os alvos do kubectl exec no lugar dos containers
    # shellcheck source=../k8s/lib-db-cliente.sh
    . "${K8S_LIB:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../k8s/lib-db-cliente.sh}"
    CONTAINER_RPK=sts/redpanda; CONTAINER_REDIS=deploy/redis; CONTAINER_RABBIT=deploy/rabbitmq
fi
EVENTOS_CADA_S="${EVENTOS_CADA_S:-5}"
ADMISSION_WINDOW_MS="${ADMISSION_WINDOW_MS:-200}"
TIMEOUT_S="${TIMEOUT_S:-5}"
GRUPOS="reservation reservation-catalog notification"
SERVICOS_REPL="backend reservation identity catalog admission notification"
CABECALHO="epoch_ms,lag_projecao_ms,lag_reservation,lag_reservation_catalog,lag_notification,outbox_reservation,outbox_catalog,outbox_identity,pg_conexoes,pg_ativas,pg_idle,pg_idle_tx,pg_espera_lock,walletevent_ins,rabbit_requests,rabbit_requests_unacked,rabbit_consumidores,rabbit_timed_out,walletevent_ins_delta,redis_hot,redis_replicas,redis_dbsize,redis_eventos_em_fila,redis_espera_total,repl_backend,repl_reservation,repl_identity,repl_catalog,repl_admission,repl_notification,duracao_amostra_ms,lag_stock,lag_stock_catalog,saga_reservando,saga_orfas,saga_compensando"
CABECALHO_EVENTOS="epoch_ms,evento,soma_buckets,rpm_aprox,modo,espera"

# TOTAL-LAG da saida do "rpk group describe" (stdin); -1 se nao houver.
lag_do_rpk() { awk '$1=="TOTAL-LAG"{v=$2} END{print (v=="" ? -1 : v)}'; }

# Lag dos groups do stock num UNICO `rpk group describe g1 g2` (stdin; um bloco "GROUP <nome>" por group). Argumentos = os groups, na ordem desejada;
# saida "lag1 lag2 ..." com -1 para o group ausente, sem TOTAL-LAG ou em STATE Dead (nunca existiu / sem offsets).
lags_do_rpk_multi() {
    awk -v lista="$*" 'BEGIN{n=split(lista, g, " ")}
        $1=="GROUP"{cur=$2}
        $1=="STATE" && $2=="Dead"{dead[cur]=1}
        $1=="TOTAL-LAG"{lag[cur]=$2}
        END{for(i=1;i<=n;i++) printf "%s%s", ((g[i] in lag) && !(g[i] in dead) ? lag[g[i]] : -1), (i<n?" ":"\n")}'
}

# Linhas do logger saga.timing (stdin; "tx1_ms=<n> stock_ms=<n> tx2_ms=<n> total_ms=<n>", so no sucesso) -> "<passo>: n N p50 X p95 Y max Z (ms)" por passo;
# nada se nao houver linha. p50/p95 = posicao ceil(p x n) da lista ordenada (mesma regra do resumo_csv).
percentis_timing() {
    local f k; f="$(mktemp)"; grep 'tx1_ms=' > "$f"
    for k in tx1_ms stock_ms tx2_ms total_ms; do
        grep -o "$k=[0-9]*" "$f" | cut -d= -f2 | sort -n | awk -v k="$k" '{v[NR]=$1}
            END{if(!NR) exit; a=int(0.5*NR+0.999999); b=int(0.95*NR+0.999999); printf "%s: n %d p50 %s p95 %s max %s (ms)\n", k, NR, v[a], v[b], v[NR]}'
    done
    rm -f "$f"
}

# Linhas "nome,n,p50,p95,max" do banco (stdin) -> "<nome>: n N p50 .. p95 .. max .. (ms)" para cada nome da lista ($1, na ordem); sem linha ou n=0 = "sem amostra"
formata_percentis() {
    awk -F, -v lista="$1" 'BEGIN{n=split(lista, nome, " ")}
        $2+0>0{l[$1]=sprintf("n %d p50 %s p95 %s max %s (ms)", $2, $3, $4, $5)}
        END{for(i=1;i<=n;i++) printf "%s: %s\n", nome[i], (nome[i] in l ? l[nome[i]] : "sem amostra")}'
}

# Resumo do CSV da coleta: max e p95 de cada coluna (valores -1 = sem amostra, ignorados).
resumo_csv() {
    awk -F, 'NR==1{for(i=2;i<=NF;i++)nome[i]=$i; n=NF; next}
        {for(i=2;i<=n;i++) if($i!="" && $i>=0){k=++c[i]; v[i,k]=$i+0}}
        END{for(i=2;i<=n;i++){
            if(!c[i]){printf "%-24s sem amostra\n", nome[i]; continue}
            # ordena (insercao; amostras de 1 s por rodada cabem)
            for(a=2;a<=c[i];a++){x=v[i,a]; b=a-1; while(b>0 && v[i,b]>x){v[i,b+1]=v[i,b]; b--} v[i,b+1]=x}
            p=int(0.95*c[i]+0.999999); if(p<1)p=1
            printf "%-24s max %s  p95 %s  (%d amostras)\n", nome[i], v[i,c[i]], v[i,p], c[i]}}' "$1"
}

# `rabbitmqctl -q list_queues name messages_ready messages_unacknowledged consumers` (stdin) -> "ready unacked consumidores timed_out" de admission.requests e
# admission.timed_out (prontas + sem ack). Fila ausente / entrada vazia (container fora) = -1 no que falta.
rabbit_do_list_queues() {
    awk 'BEGIN{r=-1;u=-1;c=-1;t=-1}
        $1=="admission.requests" && $2 ~ /^[0-9]+$/ {r=$2; u=$3; c=$4}
        $1=="admission.timed_out" && $2 ~ /^[0-9]+$/ {t=$2+$3}
        END{print r, u, c, t}'
}

# Saida do script do Redis (linhas "H hot replicas dbsize", "C adm:cnt:<ev>:<bucket> <valor>", "M adm:mode:<ev> <espera>"; stdin) ->
#   linha 1: "hot replicas dbsize eventos_em_fila espera_total" (-1 nos dois ultimos se a amostra nao teve SCAN; tudo -1 sem linha H = Redis fora)
#   demais:  "evento,soma_buckets,rpm_aprox,modo,espera" por evento (so com SCAN). Variavel awk win = ADMISSION_WINDOW_MS.
redis_resumo() {
    awk -v win="$1" '
        $1=="H" && $2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ {h=$2; r=$3; d=$4; temh=1}   # resposta de erro do redis-cli (NOAUTH...) nao vira numero
        $1=="C" && $3 ~ /^[0-9]+$/ { n=split($2, p, ":"); ev=p[3]; soma[ev]+=$3; vis[ev]=1; scan=1 }
        $1=="M" { ev=$2; sub(/^adm:mode:/, "", ev); modo[ev]=1; esp[ev]=($3 ~ /^[0-9]+$/ ? $3 : 0); vis[ev]=1; scan=1; nm++; te+=esp[ev] }
        $1=="S" {scan=1}
        END{
            if(!temh){print "-1 -1 -1 -1 -1"; exit}
            printf "%s %s %s %s %s\n", (h==""?0:h), (r==""?0:r), (d==""?0:d), (scan?nm+0:-1), (scan?te+0:-1)
            if(scan) for(ev in vis) printf "%s,%d,%d,%d,%d\n", ev, soma[ev], soma[ev]*60000/win, (ev in modo)?1:0, esp[ev]
        }'
}

# `docker ps --format '{{.Label "com.docker.compose.service"}} {{.Status}}'` do project (stdin) -> contagem em execucao (Status "Up ...") dos
# SERVICOS_REPL, na ordem; vazio/erro = -1 em todos (nao confundir com "0 replicas")
contar_replicas() {
    awk -v lista="$SERVICOS_REPL" 'BEGIN{n=split(lista, s, " ")}
        NF>=2 && $2=="Up" {c[$1]++; ok=1}
        NF>=1 {viu=1}
        END{for(i=1;i<=n;i++) printf "%s%s", (viu ? c[s[i]]+0 : -1), (i<n?",":"\n")}'
}

# AUTOESCALA_* / ADMISSION_* do ambiente (nomes allowlistados: nao ha segredo neles) para o registro da rodada
env_publico() { env | grep -E '^(AUTOESCALA|ADMISSION)_[A-Z0-9_]+=' | sort; }

[ "${COLETA_LIB:-0}" = "1" ] && return 0 2>/dev/null

ROTULO="${1:-rodada}"
DURACAO_S="${2:-0}"
case "$ROTULO" in ''|*[!A-Za-z0-9_-]*) echo "ERRO: rotulo invalido (letras, digitos, _ e -)" >&2; exit 1 ;; esac
case "$DURACAO_S:$INTERVALO_S" in *[!0-9:]*|:*|*:|*:0) echo "ERRO: duracao/INTERVALO_S devem ser inteiros (intervalo >= 1)" >&2; exit 1 ;; esac

dexec() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@" ;;
        *) docker "$@" ;;
    esac
}
# docker sob `timeout` do HOST (TIMEOUT_S + 2: o `timeout` de dentro do container dispara primeiro quando funciona): o do container mata so o `sh`/`rabbitmqctl`, e um
# `redis-cli` filho bloqueado pode segurar a saida do `docker exec` e o laco travaria; o do host mata o `docker exec` em qualquer caso
dexect() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' timeout "$((TIMEOUT_S + 2))" docker "$@"; }
psql_db() { dexec exec -i "$CONTAINER_DB" psql -h localhost -U "$PG_ADMIN" -d meu_banco -v ON_ERROR_STOP=1 -At -F, -c "$1"; }
# a amostra: as colunas da R4 (-c 1) e as contagens da saga (-c 2) no MESMO exec; com ON_ERROR_STOP o 2o -c que falha (stack sem a saga) nao tira a saida do 1o
psql_amostra() { dexec exec -i "$CONTAINER_DB" psql -h localhost -U "$PG_ADMIN" -d meu_banco -v ON_ERROR_STOP=1 -At -F, -c "$SQL_AMOSTRA" -c "$SQL_SAGA"; }
# "<servico> <Status>" de cada container do project (a coleta de replicas) e as linhas do saga.timing de TODAS as replicas do reservation desde o inicio da coleta
repl_status() { timeout "$TIMEOUT_S" docker ps --filter "label=com.docker.compose.project=$PROJETO" --format '{{.Label "com.docker.compose.service"}} {{.Status}}' 2>/dev/null; }
logs_saga() {
    local c
    for c in $(dexec ps -aq --filter "label=com.docker.compose.project=$PROJETO" --filter "label=com.docker.compose.service=reservation" | tr -d '\r'); do
        MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' timeout 120 docker logs --since "$INICIO_ISO" "$c" 2>&1
    done
}

if [ -n "${CARGA_K8S:-}" ]; then # modo cluster (#228): redefine o que fala com o Docker (a ultima definicao vence); nada daqui roda sem CARGA_K8S
    # exec [-i] <alvo> cmd... -> K8S_ARGV (o kubectl completo, SEMPRE com --context)
    k8s_exec_argv() {
        shift # "exec"
        local i=(); [ "${1:-}" = -i ] && { i=(-i); shift; }
        local alvo="$1"; shift
        K8S_ARGV=(kubectl --context "$CARGA_K8S" -n gerenciador exec ${i[@]+"${i[@]}"} "$alvo" -c "${alvo#*/}" -- "$@") # -c <container> = o nome do alvo (sem "Defaulted container" no stderr)
    }
    # traduz o subconjunto de verbos do docker que a coleta usa: exec e ps (so por label do servico: -q/-aq -> deploy/<svc>; --format {{.Names}} -> os pods)
    dexec() {
        case "${1:-}" in
            exec) k8s_exec_argv "$@"; MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "${K8S_ARGV[@]}" ;;
            ps) local a svc=""
                for a in "$@"; do case "$a" in label=com.docker.compose.service=*) svc="${a##*=}" ;; esac; done
                case "$*" in
                    *'{{.Names}}'*) kubectl --context "$CARGA_K8S" -n gerenciador get pods -l "app=$svc" -o name 2>/dev/null | sed 's#^pod/##' ;;
                    *) echo "deploy/$svc" ;;
                esac ;;
            *) return 1 ;;
        esac
    }
    dexect() { k8s_exec_argv "$@"; MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' timeout "$((TIMEOUT_S + 2))" "${K8S_ARGV[@]}"; }
    psql_db() { db_psql "$CARGA_K8S" "$PG_ADMIN" -v ON_ERROR_STOP=1 -At -F, -c "$1"; }
    psql_amostra() { db_psql "$CARGA_K8S" "$PG_ADMIN" -v ON_ERROR_STOP=1 -At -F, -c "$SQL_AMOSTRA" -c "$SQL_SAGA"; }
    # "<servico> Up" por replica PRONTA (o formato que contar_replicas le); Deployment sem pronta = "<servico> Down"; kubectl fora = saida vazia (-1)
    repl_status() {
        timeout "$TIMEOUT_S" kubectl --context "$CARGA_K8S" -n gerenciador get deploy -o jsonpath='{range .items[*]}{.metadata.name} {.status.readyReplicas}{"\n"}{end}' 2>/dev/null \
            | awk 'NF>=1 { if ($2+0 > 0) for (i = 0; i < $2; i++) print $1 " Up"; else print $1 " Down" }'
    }
    logs_saga() { kubectl --context "$CARGA_K8S" -n gerenciador logs -l app=reservation --since-time "$INICIO_ISO" --tail=-1 --max-log-requests=10 -c reservation 2>&1; }   # -c: sem "Defaulted container" misturado (2>&1) no que o percentis_timing le
    # local x remoto decidido UMA vez e o pod cliente garantido UMA vez, AQUI no processo principal (os psql abaixo rodam em $(...) e herdam as variaveis): depois disso cada psql e 1 kubectl (o exec).
    # Erro ao ler o sts/db aborta a coleta antes do 1o CSV.
    db_psql_preparar "$CARGA_K8S" || exit 1
fi

RESULTS="$(cd "$(dirname "$0")" && pwd)/results"
mkdir -p "$RESULTS"
TS="$(date +%Y%m%d-%H%M%S)"
CSV="$RESULTS/coleta-host-$ROTULO-$TS.csv"
PGSS="$RESULTS/pgss-$ROTULO-$TS.csv"
EVENTOS="$RESULTS/eventos-$ROTULO-$TS.csv"
REGISTRO="$RESULTS/registro-$ROTULO-$TS.txt"
case "$EVENTOS_CADA_S" in ''|*[!0-9]*|0) echo "ERRO: EVENTOS_CADA_S deve ser inteiro >= 1" >&2; exit 1 ;; esac
case "$TIMEOUT_S" in ''|*[!0-9]*|0) echo "ERRO: TIMEOUT_S deve ser inteiro >= 1" >&2; exit 1 ;; esac
TMPD="$(mktemp -d)"

# Inicio da coleta pelo relogio do BANCO (mesmo now() dos created_at/resolved_at/occurred_at e do `docker logs --since`, relogio da VM do Docker, que pode derivar do host no
# WSL2); o ultimo statement do -c devolve o instante. Sem leitura valida, o do host.
INICIO_ISO="$(psql_db "CREATE EXTENSION IF NOT EXISTS pg_stat_statements; SELECT pg_stat_statements_reset(); SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"');" | tr -d '\r' | tail -1)" \
    || { echo "ERRO: pg_stat_statements indisponivel em $CONTAINER_DB (stack no ar?)" >&2; exit 1; }
case "$INICIO_ISO" in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T*Z) ;; *) INICIO_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)" ;; esac

SQL_AMOSTRA="SELECT
  COALESCE((SELECT round(MAX(EXTRACT(EPOCH FROM (applied_at - last_occurred_at)) * 1000)) FROM reservation.event_availability
            WHERE applied_at > now() - interval '${INTERVALO_S} seconds'), -1),
  (SELECT count(*) FROM reservation.event_outbox), (SELECT count(*) FROM catalog.event_outbox), (SELECT count(*) FROM identity.event_outbox),
  count(*), count(*) FILTER (WHERE state = 'active'), count(*) FILTER (WHERE state = 'idle'),
  count(*) FILTER (WHERE state LIKE 'idle in transaction%'), count(*) FILTER (WHERE wait_event_type = 'Lock'),
  COALESCE((SELECT n_tup_ins FROM pg_stat_user_tables WHERE schemaname = 'reservation' AND relname = 'walletevent'), -1)
FROM pg_stat_activity WHERE datname = 'meu_banco' AND pid <> pg_backend_pid();"

# Limiar de "orfa" (saga_orfas): o RESERVATION_SAGA_ORPHAN_MS do container reservation DO PROJECT, lido UMA vez (o dry-run usa um limiar reduzido: um valor
# fixo rotularia errado). ORPHAN_MS no ambiente deste shell vale mais; sem leitura, o default do servico (30000).
descobre_orphan_ms() {
    local c v="${ORPHAN_MS:-}"
    if [ -z "$v" ]; then
        c="$(dexec ps -q --filter "label=com.docker.compose.project=$PROJETO" --filter "label=com.docker.compose.service=reservation" | head -1 | tr -d '\r')"
        [ -n "$c" ] && v="$(dexect exec "$c" printenv RESERVATION_SAGA_ORPHAN_MS 2>/dev/null | tr -d '\r')"
    fi
    case "$v" in ''|*[!0-9]*) v=30000 ;; esac
    ORPHAN_MS="$v"
}
descobre_orphan_ms

# So contagens do indice parcial idx_idempotency_key_saga_pendente (WHERE saga_status IN (reserving, compensating)): custo da amostra = o da R4
SQL_SAGA="SELECT count(*) FILTER (WHERE saga_status = 'reserving'),
  count(*) FILTER (WHERE saga_status = 'reserving' AND updated_at < now() - interval '${ORPHAN_MS} milliseconds'),
  count(*) FILTER (WHERE saga_status = 'compensating')
FROM reservation.idempotency_key WHERE saga_status IN ('reserving', 'compensating');"

# Redis (um docker exec/amostra): H sempre; com $1=1 tambem o SCAN de adm:cnt:* (contadores por evento) e adm:mode:* (modo e espera por evento).
# A senha vem do ambiente DO CONTAINER (REDISCLI_AUTH dentro do sh -c, aspas simples): nada de segredo no host. SCAN, nunca KEYS.
amostra_redis() {
    dexect exec "$CONTAINER_REDIS" timeout "$TIMEOUT_S" sh -c '
        export REDISCLI_AUTH="$REDIS_PASSWORD"
        hot=$(redis-cli GET adm:hot); rep=$(redis-cli GET adm:replicas); db=$(redis-cli DBSIZE)
        printf "H %s %s %s\n" "${hot:-0}" "${rep:-0}" "${db:--1}"
        [ "$1" = 1 ] || exit 0
        echo "S"
        k=/tmp/coleta-k.$$; v=/tmp/coleta-v.$$
        redis-cli --scan --pattern "adm:cnt:*" > $k
        sed "s/^/GET /" $k | redis-cli > $v; paste -d" " $k $v | sed "s/^/C /"
        redis-cli --scan --pattern "adm:mode:*" > $k
        sed "s/^adm:mode:/ZCARD adm:wait:/" $k | redis-cli > $v; paste -d" " $k $v | sed "s/^/M /"
        rm -f $k $v' _ "$1" 2>/dev/null | tr -d '\r'
}

# Registro da rodada: sem segredo (allowlist de nomes); o ambiente do shell vale para o que o operador exportou aqui
escreve_registro() {
    {
        echo "coleta $ROTULO iniciada em $(date -u '+%Y-%m-%dT%H:%M:%SZ') (project $PROJETO)"
        for svc in $SERVICOS_REPL lb; do
            echo "containers $svc: $(dexec ps --filter "label=com.docker.compose.project=$PROJETO" --filter "label=com.docker.compose.service=$svc" --format '{{.Names}}' | tr -d '\r' | tr '\n' ' ')"
        done
        echo "saga: ORPHAN_MS=$ORPHAN_MS (limiar de saga_orfas desta coleta; lido do RESERVATION_SAGA_ORPHAN_MS do reservation ou 30000)"
        echo "ambiente deste shell (AUTOESCALA_*, ADMISSION_*; exporte aqui os do scaler/up):"
        env_publico | sed 's/^/  /'
        for par in "backend RESERVATION_READPATH" "reservation SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE SERVER_TOMCAT_THREADS_MAX RESERVATION_SAGA_ORPHAN_MS RESERVATION_SAGA_REAPER_SCAN_MS" \
                   "stock SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE" \
                   "admission ADMISSION_X_RPM ADMISSION_RESERVATION_REPLICAS ADMISSION_QUEUE_ON ADMISSION_SYNC_BELOW ADMISSION_WORKER_CONCURRENCY ADMISSION_QUEUE_TTL_MS ADMISSION_QUEUE_MAX_LENGTH ADMISSION_MAX_WAITING_PER_USER ADMISSION_WINDOW_MS ADMISSION_BUCKET_MS"; do
            svc="${par%% *}"; vars="${par#* }"
            c="$(dexec ps -q --filter "label=com.docker.compose.project=$PROJETO" --filter "label=com.docker.compose.service=$svc" | head -1 | tr -d '\r')"
            [ -n "$c" ] || continue
            echo "$svc (container $c):"
            for v in $vars; do echo "  $v=$(dexec exec "$c" printenv "$v" 2>/dev/null | tr -d '\r')"; done
        done
    } > "$REGISTRO"
}

# Resumo da saga (issue #204), no FIM da coleta e depois do pgss (as consultas daqui nao entram no pg_stat_statements da rodada): janela >= INICIO_ISO.
# Cada parte falha sozinha (a linha diz "falhou") e nunca derruba o resto do finalizar. Roda ANTES do limpar-banco.sh (ele trunca estas linhas).
resumo_saga() {
    local sg_csv="$RESULTS/saga-$ROTULO-$TS.csv" sg_txt="$RESULTS/resumo-saga-$ROTULO-$TS.txt" o dp oi rs rc c tim
    {
        echo "resumo da saga $ROTULO (janela >= $INICIO_ISO; ORPHAN_MS $ORPHAN_MS)"
        if o="$(psql_db "SELECT to_char(date_trunc('minute', resolved_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD HH24:MI'),
                           count(*) FILTER (WHERE resolved_by = 'inline'), count(*) FILTER (WHERE resolved_by = 'reaper')
                         FROM reservation.idempotency_key WHERE saga_status = 'compensated' AND resolved_at >= '$INICIO_ISO' GROUP BY 1 ORDER BY 1;" 2>/dev/null)"; then
            { echo "minuto,compensadas_inline,compensadas_reaper"; printf '%s\n' "$o" | sed '/^$/d'; } > "$sg_csv"
            echo "compensacoes por minuto: $sg_csv ($(printf '%s\n' "$o" | sed '/^$/d' | wc -l | tr -d ' ') minuto(s) com compensacao; a subcontagem do reabrir de chave compensated apaga o resolved_at: ~0 na R5)"
        else echo "consulta das compensacoes por minuto falhou (stack sem a saga?)"; fi
        if o="$(psql_db "SELECT 'resolver_' || resolved_by, count(*), round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)), round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)), round(max(ms))
                         FROM (SELECT resolved_by, EXTRACT(EPOCH FROM resolved_at - created_at) * 1000 AS ms FROM reservation.idempotency_key
                               WHERE saga_status = 'compensated' AND resolved_at >= '$INICIO_ISO') t GROUP BY resolved_by
                         UNION ALL
                         SELECT 'devolucao', count(*), round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)), round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)), round(max(ms))
                         FROM (SELECT EXTRACT(EPOCH FROM h.updated_at - e.occurred_at) * 1000 AS ms FROM stock.hold h
                               JOIN reservation.event_store e ON e.payload->>'holdId' = h.hold_id::text AND e.event_type IN ('ReservationCancelled', 'ReservationExpired')
                               WHERE h.status = 'released' AND e.occurred_at >= '$INICIO_ISO') t;" 2>/dev/null)"; then
            echo "tempo ate resolver (compensated: resolved_at - created_at; o do reaper e o tempo ate a orfa se resolver):"
            printf '%s\n' "$o" | formata_percentis "resolver_inline resolver_reaper" | sed 's/^/  /'
            echo "lag de devolucao (hold.updated_at - occurred_at do ReservationCancelled/Expired com o mesmo holdId, hold released):"
            printf '%s\n' "$o" | formata_percentis "devolucao" | sed 's/^/  /'
        else echo "consulta dos tempos (resolver / devolucao) falhou (stack sem a saga ou sem o stock?)"; fi
        # devolucao_pendente: hold held com ReservationCancelled/Expired no event_store (a fonte da devolucao), sem JOIN com walletevent (o UPSERT que revive troca o hold_id).
        # Sem filtro de idade: aqui e o retrato do fim do bloco, informativo (a parada com idade > limite e do verificar-rpm). O EXISTS varre o event_store: custa so pos-bloco, nunca por amostra.
        if o="$(psql_db "SELECT (SELECT count(*) FROM stock.hold h WHERE h.status = 'held'
                                  AND EXISTS (SELECT 1 FROM reservation.event_store es WHERE es.event_type IN ('ReservationCancelled', 'ReservationExpired')
                                              AND es.payload->>'holdId' = h.hold_id::text)) AS devolucao_pendente,
                                (SELECT count(*) FROM reservation.walletevent w WHERE w.status IN ('pending', 'confirmed')
                                  AND NOT EXISTS (SELECT 1 FROM stock.hold h WHERE h.hold_id = w.hold_id AND h.status = 'held')) AS orfa_inversa,
                                (SELECT count(*) FROM reservation.idempotency_key WHERE saga_status = 'reserving'),
                                (SELECT count(*) FROM reservation.idempotency_key WHERE saga_status = 'compensating');" 2>/dev/null)"; then
            IFS=, read -r dp oi rs rc <<< "$o"
            echo "fim do bloco: devolucao_pendente ${dp:--1}, orfa_inversa ${oi:--1}, sagas_reserving ${rs:--1}, sagas_compensating ${rc:--1}"
        else echo "consulta do estado no fim do bloco falhou (stack sem a saga ou sem o stock?)"; fi
        # latencia dos passos: logger saga.timing de TODAS as replicas do reservation (o braco C tem varias; -a inclui a que o scaler parou no meio do bloco), desde o inicio da coleta
        tim="$(logs_saga | percentis_timing)"
        if [ -n "$tim" ]; then
            echo "latencia dos passos (logger saga.timing, replicas do reservation no project):"
            printf '%s\n' "$tim" | sed 's/^/  /'
        else
            echo "latencia dos passos: logger saga.timing desligado (LOGGING_LEVEL_SAGA_TIMING=DEBUG so no bloco que mede): latencia dos passos fora"
        fi
    } > "$sg_txt" 2>&1
    cat "$sg_txt"
    echo "resumo da saga: $sg_txt"
}

finalizar() {
    trap - INT TERM
    psql_db "SELECT round(total_exec_time::numeric, 1), calls, round(mean_exec_time::numeric, 3), rows,
               '\"' || replace(left(regexp_replace(query, '\s+', ' ', 'g'), 300), '\"', '\"\"') || '\"'
             FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 25;" > "$PGSS.tmp" \
        && { echo "total_exec_ms,calls,mean_ms,rows,query"; cat "$PGSS.tmp"; } > "$PGSS"
    rm -f "$PGSS.tmp"; rm -rf "$TMPD"
    echo ""
    resumo_csv "$CSV"
    resumo_saga || true
    echo "CSV: $CSV"
    echo "pg_stat_statements: $PGSS"
    echo "eventos (Redis, formato longo): $EVENTOS"
    echo "registro da rodada: $REGISTRO"
    exit 0
}
trap finalizar INT TERM

echo "$CABECALHO" > "$CSV"
echo "$CABECALHO_EVENTOS" > "$EVENTOS"
escreve_registro
ULT_INS=""; ULT_SCAN=0
echo "[coleta] $CSV (a cada ${INTERVALO_S}s; $([ "$DURACAO_S" -gt 0 ] && echo "${DURACAO_S}s" || echo 'Ctrl+C encerra'))"
FIM=$(( $(date +%s) + DURACAO_S ))
while [ "$DURACAO_S" -eq 0 ] || [ "$(date +%s)" -lt "$FIM" ]; do
    t0=$(date +%s%3N)
    # as coletas lentas (rabbitmqctl, Redis, docker ps) rodam em paralelo com o psql/rpk: a amostra dura o maior, nao a soma
    # (quem estoura o TIMEOUT_S sai com codigo != 0: saida descartada = -1; rabbitmqctl e Redis rodam sob `timeout` no container E o `docker exec` sob `timeout` do host)
    ( o="$(dexect exec "$CONTAINER_RABBIT" timeout "$TIMEOUT_S" rabbitmqctl -q list_queues name messages_ready messages_unacknowledged consumers 2>/dev/null)" || o=""
      printf '%s\n' "$o" | tr -d '\r' | rabbit_do_list_queues > "$TMPD/rabbit" ) &
    scan=0; [ $(( t0 / 1000 - ULT_SCAN )) -ge "$EVENTOS_CADA_S" ] && { scan=1; ULT_SCAN=$(( t0 / 1000 )); }
    ( o="$(amostra_redis "$scan")" || o=""; printf '%s\n' "$o" | redis_resumo "$ADMISSION_WINDOW_MS" > "$TMPD/redis" ) &
    ( o="$(repl_status)" || o=""
      printf '%s\n' "$o" | tr -d '\r' | contar_replicas > "$TMPD/repl" ) &
    # lag dos groups do stock: UM rpk (multi-group), em paralelo com o resto como as outras coletas lentas (estourou = -1 -1)
    ( o="$(dexect exec "$CONTAINER_RPK" timeout 3 rpk group describe stock stock-catalog 2>/dev/null)" || o=""
      printf '%s\n' "$o" | tr -d '\r' | lags_do_rpk_multi stock stock-catalog > "$TMPD/lagstock" ) &
    # psql: linha 1 = colunas da R4, linha 2 = contagens da saga (so a saga falha = -1 nela, as antigas intactas)
    banco=""; saga=""; { IFS= read -r banco; IFS= read -r saga; } <<< "$(psql_amostra 2>/dev/null)"
    [ -n "$banco" ] || banco="-1,-1,-1,-1,-1,-1,-1,-1,-1,-1"
    case "$saga" in [0-9]*,[0-9]*,[0-9]*) ;; *) saga="-1,-1,-1" ;; esac
    l1="$(dexec exec "$CONTAINER_RPK" timeout 3 rpk group describe reservation 2>/dev/null | lag_do_rpk)"
    l2="$(dexec exec "$CONTAINER_RPK" timeout 3 rpk group describe reservation-catalog 2>/dev/null | lag_do_rpk)"
    l3="$(dexec exec "$CONTAINER_RPK" timeout 3 rpk group describe notification 2>/dev/null | lag_do_rpk)"
    IFS=, read -r proj resto <<< "$banco"
    wait
    ins="${banco##*,}"; delta=-1
    [ -n "$ULT_INS" ] && [ "$ins" -ge 0 ] 2>/dev/null && [ "$ULT_INS" -ge 0 ] 2>/dev/null && delta=$(( ins - ULT_INS ))
    ULT_INS="$ins"
    read -r rq ru rc rt < "$TMPD/rabbit" 2>/dev/null || { rq=-1; ru=-1; rc=-1; rt=-1; }
    { read -r linha_redis; } < "$TMPD/redis" 2>/dev/null; [ -n "${linha_redis:-}" ] || linha_redis="-1 -1 -1 -1 -1"
    read -r rhot rrep rdb rmod resp <<< "$linha_redis"
    if [ "$scan" = 1 ]; then tail -n +2 "$TMPD/redis" | sed "s/^/$t0,/" >> "$EVENTOS"; fi
    repl="$(cat "$TMPD/repl" 2>/dev/null)"; [ -n "$repl" ] || repl="-1,-1,-1,-1,-1,-1"
    read -r ls1 ls2 < "$TMPD/lagstock" 2>/dev/null || { ls1=-1; ls2=-1; }
    dur=$(( $(date +%s%3N) - t0 ))
    echo "$t0,$proj,$l1,$l2,$l3,$resto,${rq:--1},${ru:--1},${rc:--1},${rt:--1},$delta,${rhot:--1},${rrep:--1},${rdb:--1},${rmod:--1},${resp:--1},$repl,$dur,${ls1:--1},${ls2:--1},$saga" >> "$CSV"
    resta=$(( INTERVALO_S * 1000 - ($(date +%s%3N) - t0) ))
    [ "$resta" -gt 0 ] && sleep "$(awk -v r="$resta" 'BEGIN{printf "%.3f", r/1000}')"
done
finalizar
