#!/usr/bin/env bash
# Segue o access log do Envoy (container istio-proxy) de cada pod de backend/reservation/stock/admission durante o diagnóstico:
# um `kubectl logs -f` por pod, num arquivo por pod; pods novos (KEDA) entram a cada 5 s. SIGINT encerra todos.
# Uso: segue-accesslog.sh <dir> <duracao_s>
set -u
OUT="$1"; DUR="$2"; K=(kubectl --context "${CTX:-kind-gerenciador}" -n gerenciador)
mkdir -p "$OUT"; declare -A seguindo; pids=()
parar=0; trap 'parar=1' INT TERM
fim=$(( $(date +%s) + DUR ))
while [ "$parar" = 0 ] && [ "$(date +%s)" -lt "$fim" ]; do
    for p in $("${K[@]}" get pods -l 'app in (backend,reservation,stock,admission)' -o name 2>/dev/null | sed 's#pod/##'); do
        [ -n "${seguindo[$p]:-}" ] && continue
        "${K[@]}" logs -f "$p" -c istio-proxy --since=10s >> "$OUT/accesslog-$p.txt" 2>/dev/null &
        pids+=($!); seguindo[$p]=1
    done
    sleep 5
done
kill "${pids[@]}" 2>/dev/null; wait 2>/dev/null
echo "[segue-accesslog] fim: $(ls "$OUT" | wc -l) arquivos"
