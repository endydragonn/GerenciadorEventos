#!/usr/bin/env bash
# Raspador do diagnóstico do K2 (#228): a cada INTERVALO s, por pod de backend/reservation/stock/admission, UM kubectl exec no
# istio-proxy (que tem curl) lê o actuator da app (localhost:8090, metrics) e os stats do Envoy (localhost:15000).
#   actuator-<rot>.csv  epoch_ms,pod,metrica,valor
#   envoy-<rot>.csv     epoch_ms,pod,stat,valor
# Uso: raspa-diag.sh <rotulo> <duracao_s> <dir>   (SIGINT encerra)
set -u
ROT="$1"; DUR="$2"; OUT="$3"; INTERVALO="${INTERVALO:-5}"
K=(kubectl --context "${CTX:-kind-gerenciador}" -n gerenciador)
A="$OUT/actuator-$ROT.csv"; E="$OUT/envoy-$ROT.csv"
echo "epoch_ms,pod,metrica,valor" > "$A"; echo "epoch_ms,pod,stat,valor" > "$E"
read -r -d '' REMOTO <<'EOS'
for m in hikaricp.connections.active hikaricp.connections.pending hikaricp.connections.max hikaricp.connections.timeout \
         tomcat.threads.busy tomcat.threads.config.max tomcat.connections.current jvm.threads.live process.cpu.usage; do
  v=$(curl -s -m 2 "localhost:8090/actuator/metrics/$m" | sed -n 's/.*"measurements":\[{"statistic":"[A-Z_]*","value":\([-0-9.Ee+]*\)}.*/\1/p')
  [ -n "$v" ] && echo "A $m $v"
done
curl -s -m 2 "localhost:15000/stats" | grep -E '^server\.concurrency:|^listener\.0\.0\.0\.0_15006\.downstream_cx_active:|^http\.(inbound|outbound)_0\.0\.0\.0_80[0-9][0-9];?\.(downstream_cx_active|downstream_rq_active|downstream_rq_total|downstream_rq_5xx):' \
  | sed 's/;//; s/^/E /; s/: / /'
EOS
parar=0; trap 'parar=1' INT TERM
fim=$(( $(date +%s) + DUR )); pods=""; ultimo=0
while [ "$parar" = 0 ] && [ "$(date +%s)" -lt "$fim" ]; do
    t0=$(date +%s%3N)
    if [ $(( t0 - ultimo )) -ge 15000 ]; then
        pods="$("${K[@]}" get pods -l 'app in (backend,reservation,stock,admission)' --field-selector=status.phase=Running -o name 2>/dev/null | sed 's#pod/##')"
        ultimo=$t0
    fi
    for p in $pods; do
        ( "${K[@]}" exec "$p" -c istio-proxy -- sh -c "$REMOTO" 2>/dev/null \
            | awk -v t="$t0" -v p="$p" -v a="$A" -v e="$E" '$1=="A"{print t","p","$2","$3 >> a} $1=="E"{print t","p","$2","$3 >> e}' ) &
    done
    wait
    dt=$(( $(date +%s%3N) - t0 )); falta=$(( INTERVALO * 1000 - dt ))
    [ "$falta" -gt 0 ] && sleep "$(awk -v f="$falta" 'BEGIN{printf "%.3f", f/1000}')"
done
echo "[raspa-diag] fim: $A $E"
