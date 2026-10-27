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
#      outbox_reservation, outbox_catalog, outbox_public
#                                     linhas pendentes nos event_outbox (relay atrasado)
#      pg_conexoes, pg_ativas, pg_idle, pg_idle_tx, pg_espera_lock
#                                     lado servidor do pooler: conexoes que o Supavisor (e os testes)
#                                     mantem no meu_banco, por estado (pg_stat_activity)
#    pgss-<rotulo>-<ts>.csv         top 25 do pg_stat_statements por tempo total, desde o reset
#                                   feito no inicio desta coleta
#
#  Uso:  ./coleta-host.sh [rotulo] [duracao_s]      (duracao 0 = ate o Ctrl+C)
#  Env:  INTERVALO_S (1), CONTAINER_DB (gerenciador-db), CONTAINER_RPK (gerenciador-redpanda),
#        PG_ADMIN (supabase_admin: cria a extensao e reseta o pg_stat_statements)
# =============================================================================
set -uo pipefail

INTERVALO_S="${INTERVALO_S:-1}"
CONTAINER_DB="${CONTAINER_DB:-gerenciador-db}"
CONTAINER_RPK="${CONTAINER_RPK:-gerenciador-redpanda}"
PG_ADMIN="${PG_ADMIN:-supabase_admin}"
GRUPOS="reservation reservation-catalog notification"
CABECALHO="epoch_ms,lag_projecao_ms,lag_reservation,lag_reservation_catalog,lag_notification,outbox_reservation,outbox_catalog,outbox_public,pg_conexoes,pg_ativas,pg_idle,pg_idle_tx,pg_espera_lock"

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

psql_db "CREATE EXTENSION IF NOT EXISTS pg_stat_statements; SELECT pg_stat_statements_reset();" >/dev/null \
    || { echo "ERRO: pg_stat_statements indisponivel em $CONTAINER_DB (stack no ar?)" >&2; exit 1; }

SQL_AMOSTRA="SELECT
  COALESCE((SELECT round(MAX(EXTRACT(EPOCH FROM (applied_at - last_occurred_at)) * 1000)) FROM reservation.event_availability
            WHERE applied_at > now() - interval '${INTERVALO_S} seconds'), -1),
  (SELECT count(*) FROM reservation.event_outbox), (SELECT count(*) FROM catalog.event_outbox), (SELECT count(*) FROM public.event_outbox),
  count(*), count(*) FILTER (WHERE state = 'active'), count(*) FILTER (WHERE state = 'idle'),
  count(*) FILTER (WHERE state LIKE 'idle in transaction%'), count(*) FILTER (WHERE wait_event_type = 'Lock')
FROM pg_stat_activity WHERE datname = 'meu_banco' AND pid <> pg_backend_pid();"

finalizar() {
    trap - INT TERM
    psql_db "SELECT round(total_exec_time::numeric, 1), calls, round(mean_exec_time::numeric, 3), rows,
               '\"' || replace(left(regexp_replace(query, '\s+', ' ', 'g'), 300), '\"', '\"\"') || '\"'
             FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 25;" > "$PGSS.tmp" \
        && { echo "total_exec_ms,calls,mean_ms,rows,query"; cat "$PGSS.tmp"; } > "$PGSS"
    rm -f "$PGSS.tmp"
    echo ""
    resumo_csv "$CSV"
    echo "CSV: $CSV"
    echo "pg_stat_statements: $PGSS"
    exit 0
}
trap finalizar INT TERM

echo "$CABECALHO" > "$CSV"
echo "[coleta] $CSV (a cada ${INTERVALO_S}s; $([ "$DURACAO_S" -gt 0 ] && echo "${DURACAO_S}s" || echo 'Ctrl+C encerra'))"
FIM=$(( $(date +%s) + DURACAO_S ))
while [ "$DURACAO_S" -eq 0 ] || [ "$(date +%s)" -lt "$FIM" ]; do
    t0=$(date +%s%3N)
    banco="$(psql_db "$SQL_AMOSTRA" 2>/dev/null | head -1)"
    [ -n "$banco" ] || banco="-1,-1,-1,-1,-1,-1,-1,-1,-1"
    l1="$(dexec exec "$CONTAINER_RPK" rpk group describe reservation 2>/dev/null | lag_do_rpk)"
    l2="$(dexec exec "$CONTAINER_RPK" rpk group describe reservation-catalog 2>/dev/null | lag_do_rpk)"
    l3="$(dexec exec "$CONTAINER_RPK" rpk group describe notification 2>/dev/null | lag_do_rpk)"
    IFS=, read -r proj resto <<< "$banco"
    echo "$t0,$proj,$l1,$l2,$l3,$resto" >> "$CSV"
    resta=$(( INTERVALO_S * 1000 - ($(date +%s%3N) - t0) ))
    [ "$resta" -gt 0 ] && sleep "$(awk -v r="$resta" 'BEGIN{printf "%.3f", r/1000}')"
done
finalizar
