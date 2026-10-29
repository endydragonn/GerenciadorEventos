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
#    eventos-<rotulo>-<ts>.csv      formato longo, um registro por evento em cada amostra com SCAN: epoch_ms,evento,soma_buckets,rpm_aprox,modo,espera
#                                   (soma_buckets = contagem dos buckets vivos do Redis; rpm_aprox = soma x 60000 / ADMISSION_WINDOW_MS: aproximado, ate
#                                   ~25% a mais por causa do bucket extra vivo; o exato do mais quente e redis_hot). modo 1 = adm:mode:<evento> existe
#    registro-<rotulo>-<ts>.txt     registro da rodada (so nomes allowlistados, nenhum segredo): data, containers por servico, AUTOESCALA_*/ADMISSION_*
#                                   do ambiente DESTE shell (exporte aqui os mesmos do scaler/up) e as variaveis publicas lidas dos containers
#    pgss-<rotulo>-<ts>.csv         top 25 do pg_stat_statements por tempo total, desde o reset
#                                   feito no inicio desta coleta
#
#  Uso:  ./coleta-host.sh [rotulo] [duracao_s]      (duracao 0 = ate o Ctrl+C)
#  Env:  INTERVALO_S (1), CONTAINER_DB (gerenciador-db), CONTAINER_RPK (gerenciador-redpanda),
#        PG_ADMIN (supabase_admin: cria a extensao e reseta o pg_stat_statements),
#        CONTAINER_REDIS (gerenciador-redis), CONTAINER_RABBIT (gerenciador-rabbitmq) -- nomes fixos, nao escalam --,
#        PROJETO (gerenciador-eventos: o project do compose; as replicas dos servicos se acham por label), EVENTOS_CADA_S (5),
#        ADMISSION_WINDOW_MS (200, so para o rpm_aprox), TIMEOUT_S (5: teto de cada coleta lenta -- rabbitmqctl e Redis rodam sob `timeout` DENTRO do
#        container (as imagens alpine tem o `timeout` do busybox), o `docker ps` sob `timeout` no host --; estourou = -1 nas colunas, o laco nao trava)
# =============================================================================
set -uo pipefail

INTERVALO_S="${INTERVALO_S:-1}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
CONTAINER_RPK="${CONTAINER_RPK:-gerenciador-redpanda}"
PG_ADMIN="${PG_ADMIN:-supabase_admin}"
CONTAINER_REDIS="${CONTAINER_REDIS:-gerenciador-redis}"
CONTAINER_RABBIT="${CONTAINER_RABBIT:-gerenciador-rabbitmq}"
PROJETO="${PROJETO:-gerenciador-eventos}"
EVENTOS_CADA_S="${EVENTOS_CADA_S:-5}"
ADMISSION_WINDOW_MS="${ADMISSION_WINDOW_MS:-200}"
TIMEOUT_S="${TIMEOUT_S:-5}"
GRUPOS="reservation reservation-catalog notification"
SERVICOS_REPL="backend reservation identity catalog admission notification"
CABECALHO="epoch_ms,lag_projecao_ms,lag_reservation,lag_reservation_catalog,lag_notification,outbox_reservation,outbox_catalog,outbox_identity,pg_conexoes,pg_ativas,pg_idle,pg_idle_tx,pg_espera_lock,walletevent_ins,rabbit_requests,rabbit_requests_unacked,rabbit_consumidores,rabbit_timed_out,walletevent_ins_delta,redis_hot,redis_replicas,redis_dbsize,redis_eventos_em_fila,redis_espera_total,repl_backend,repl_reservation,repl_identity,repl_catalog,repl_admission,repl_notification,duracao_amostra_ms"
CABECALHO_EVENTOS="epoch_ms,evento,soma_buckets,rpm_aprox,modo,espera"

# TOTAL-LAG da saida do "rpk group describe" (stdin); -1 se nao houver.
lag_do_rpk() { awk '$1=="TOTAL-LAG"{v=$2} END{print (v=="" ? -1 : v)}'; }

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
psql_db() { dexec exec -i "$CONTAINER_DB" psql -h localhost -U "$PG_ADMIN" -d meu_banco -v ON_ERROR_STOP=1 -At -F, -c "$1"; }

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

psql_db "CREATE EXTENSION IF NOT EXISTS pg_stat_statements; SELECT pg_stat_statements_reset();" >/dev/null \
    || { echo "ERRO: pg_stat_statements indisponivel em $CONTAINER_DB (stack no ar?)" >&2; exit 1; }

SQL_AMOSTRA="SELECT
  COALESCE((SELECT round(MAX(EXTRACT(EPOCH FROM (applied_at - last_occurred_at)) * 1000)) FROM reservation.event_availability
            WHERE applied_at > now() - interval '${INTERVALO_S} seconds'), -1),
  (SELECT count(*) FROM reservation.event_outbox), (SELECT count(*) FROM catalog.event_outbox), (SELECT count(*) FROM identity.event_outbox),
  count(*), count(*) FILTER (WHERE state = 'active'), count(*) FILTER (WHERE state = 'idle'),
  count(*) FILTER (WHERE state LIKE 'idle in transaction%'), count(*) FILTER (WHERE wait_event_type = 'Lock'),
  COALESCE((SELECT n_tup_ins FROM pg_stat_user_tables WHERE schemaname = 'reservation' AND relname = 'walletevent'), -1)
FROM pg_stat_activity WHERE datname = 'meu_banco' AND pid <> pg_backend_pid();"

# Redis (um docker exec/amostra): H sempre; com $1=1 tambem o SCAN de adm:cnt:* (contadores por evento) e adm:mode:* (modo e espera por evento).
# A senha vem do ambiente DO CONTAINER (REDISCLI_AUTH dentro do sh -c, aspas simples): nada de segredo no host. SCAN, nunca KEYS.
amostra_redis() {
    dexec exec "$CONTAINER_REDIS" timeout "$TIMEOUT_S" sh -c '
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
        echo "ambiente deste shell (AUTOESCALA_*, ADMISSION_*; exporte aqui os do scaler/up):"
        env_publico | sed 's/^/  /'
        for par in "backend RESERVATION_READPATH" "reservation SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE SERVER_TOMCAT_THREADS_MAX" \
                   "admission ADMISSION_X_RPM ADMISSION_RESERVATION_REPLICAS ADMISSION_QUEUE_ON ADMISSION_SYNC_BELOW ADMISSION_WORKER_CONCURRENCY ADMISSION_QUEUE_TTL_MS ADMISSION_QUEUE_MAX_LENGTH ADMISSION_MAX_WAITING_PER_USER ADMISSION_WINDOW_MS ADMISSION_BUCKET_MS"; do
            svc="${par%% *}"; vars="${par#* }"
            c="$(dexec ps -q --filter "label=com.docker.compose.project=$PROJETO" --filter "label=com.docker.compose.service=$svc" | head -1 | tr -d '\r')"
            [ -n "$c" ] || continue
            echo "$svc (container $c):"
            for v in $vars; do echo "  $v=$(dexec exec "$c" printenv "$v" 2>/dev/null | tr -d '\r')"; done
        done
    } > "$REGISTRO"
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
    # (quem estoura o TIMEOUT_S sai com codigo != 0: saida descartada = -1; o host so cobre o `docker ps`, os outros dois rodam sob `timeout` no container)
    ( o="$(dexec exec "$CONTAINER_RABBIT" timeout "$TIMEOUT_S" rabbitmqctl -q list_queues name messages_ready messages_unacknowledged consumers 2>/dev/null)" || o=""
      printf '%s\n' "$o" | tr -d '\r' | rabbit_do_list_queues > "$TMPD/rabbit" ) &
    scan=0; [ $(( t0 / 1000 - ULT_SCAN )) -ge "$EVENTOS_CADA_S" ] && { scan=1; ULT_SCAN=$(( t0 / 1000 )); }
    ( o="$(amostra_redis "$scan")" || o=""; printf '%s\n' "$o" | redis_resumo "$ADMISSION_WINDOW_MS" > "$TMPD/redis" ) &
    ( o="$(timeout "$TIMEOUT_S" docker ps --filter "label=com.docker.compose.project=$PROJETO" --format '{{.Label "com.docker.compose.service"}} {{.Status}}' 2>/dev/null)" || o=""
      printf '%s\n' "$o" | tr -d '\r' | contar_replicas > "$TMPD/repl" ) &
    banco="$(psql_db "$SQL_AMOSTRA" 2>/dev/null | head -1)"
    [ -n "$banco" ] || banco="-1,-1,-1,-1,-1,-1,-1,-1,-1,-1"
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
    dur=$(( $(date +%s%3N) - t0 ))
    echo "$t0,$proj,$l1,$l2,$l3,$resto,${rq:--1},${ru:--1},${rc:--1},${rt:--1},$delta,${rhot:--1},${rrep:--1},${rdb:--1},${rmod:--1},${resp:--1},$repl,$dur" >> "$CSV"
    resta=$(( INTERVALO_S * 1000 - ($(date +%s%3N) - t0) ))
    [ "$resta" -gt 0 ] && sleep "$(awk -v r="$resta" 'BEGIN{printf "%.3f", r/1000}')"
done
finalizar
