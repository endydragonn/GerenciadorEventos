#!/usr/bin/env bash
# Selftest do coleta-host.sh (sem Docker): parse do rpk e resumo do CSV.
set -u
COLETA_LIB=1 . "$(dirname "$0")/coleta-host.sh"
falhas=0
confere() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FALHA $1: esperado '$3', veio '$2'"; falhas=$((falhas+1)); fi; }
confere "rpk TOTAL-LAG" "$(printf 'GROUP        reservation\nSTATE        Stable\nTOTAL-LAG    42\n\nTOPIC PARTITION LAG\nreservation.wallet 0 42\n' | lag_do_rpk)" "42"
confere "rpk sem grupo" "$(printf 'erro: group not found\n' | lag_do_rpk)" "-1"
csv="$(mktemp)"
{ echo "epoch_ms,lag_projecao_ms,lag_reservation"; for i in $(seq 1 20); do echo "$i,$((i*10)),-1"; done; } > "$csv"
r="$(resumo_csv "$csv")"; rm -f "$csv"
confere "resumo max/p95" "$(echo "$r" | grep lag_projecao_ms | awk '{print $3, $5}')" "200 190"
confere "resumo sem amostra" "$(echo "$r" | grep -c 'lag_reservation .*sem amostra')" "1"
[ "$falhas" -eq 0 ] && echo "selftest coleta-host: OK" || { echo "selftest coleta-host: $falhas falha(s)"; exit 1; }
