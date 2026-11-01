#!/usr/bin/env bash
# Coletor por pod para o K2-repeticao (#228): a cada INTERVALO s grava
#   top-pods-<rotulo>.csv     epoch_ms,pod,container,cpu_m,mem_mi   (kubectl top pods --containers, metrics-server)
#   cgroup-cpu-<rotulo>.csv   epoch_ms,pod,container,usage_usec,nr_periods,nr_throttled,throttled_usec,quota_period   (cpu.stat/cpu.max do cgroup v2 no nó kind)
#   replicas-<rotulo>.csv     epoch_ms,deploy,ready
# Uso: coleta-pods.sh <rotulo> <duracao_s> <dir_saida>   (Ctrl+C/SIGINT encerra)
set -u
ROT="$1"; DUR="$2"; OUT="$3"; INTERVALO="${INTERVALO:-5}"
CTX="${CTX:-kind-gerenciador}"; NS=gerenciador; NO="${NO:-gerenciador-control-plane}"
K=(kubectl --context "$CTX" -n "$NS")
TOP="$OUT/top-pods-$ROT.csv"; CG="$OUT/cgroup-cpu-$ROT.csv"; RP="$OUT/replicas-$ROT.csv"
echo "epoch_ms,pod,container,cpu_m,mem_mi" > "$TOP"
echo "epoch_ms,pod,container,usage_usec,nr_periods,nr_throttled,throttled_usec,cpu_max" > "$CG"
echo "epoch_ms,deploy,ready" > "$RP"
parar=0; trap 'parar=1' INT TERM
fim=$(( $(date +%s) + DUR ))
mapa=""; ultimo_mapa=0
while [ "$parar" = 0 ] && [ "$(date +%s)" -lt "$fim" ]; do
    t0=$(date +%s%3N)
    # mapa id do container -> pod/container, renovado a cada 15 s (as réplicas mudam)
    if [ $(( t0 - ultimo_mapa )) -ge 15000 ]; then
        mapa="$("${K[@]}" get pods -o go-template='{{range .items}}{{$p:=.metadata.name}}{{range .status.containerStatuses}}{{.containerID}} {{$p}} {{.name}}{{"\n"}}{{end}}{{range .status.initContainerStatuses}}{{.containerID}} {{$p}} {{.name}}{{"\n"}}{{end}}{{end}}' 2>/dev/null | sed 's#containerd://##')"
        ultimo_mapa=$t0
    fi
    "${K[@]}" top pods --containers --no-headers 2>/dev/null \
        | awk -v t="$t0" '{gsub(/m$/,"",$3); gsub(/Mi$/,"",$4); print t","$1","$2","$3","$4}' >> "$TOP"
    # cgroup v2 no nó: acha o scope de cada container pelo id e lê cpu.stat e cpu.max
    printf '%s\n' "$mapa" | docker exec -i "$NO" sh -c '
        while read id pod ctr; do
            [ -n "$id" ] || continue
            d=$(ls -d /sys/fs/cgroup/kubelet.slice/kubelet-kubepods.slice/*/*/cri-containerd-"$id".scope /sys/fs/cgroup/kubelet.slice/kubelet-kubepods.slice/*/cri-containerd-"$id".scope 2>/dev/null | head -1)
            [ -n "$d" ] || continue
            u=$(awk "\$1==\"usage_usec\"{print \$2}" "$d/cpu.stat"); np=$(awk "\$1==\"nr_periods\"{print \$2}" "$d/cpu.stat")
            nt=$(awk "\$1==\"nr_throttled\"{print \$2}" "$d/cpu.stat"); tu=$(awk "\$1==\"throttled_usec\"{print \$2}" "$d/cpu.stat")
            mx=$(tr " " "/" < "$d/cpu.max")
            echo "$pod,$ctr,$u,$np,$nt,$tu,$mx"
        done' 2>/dev/null | awk -v t="$t0" '{print t","$0}' >> "$CG"
    "${K[@]}" get deploy -o jsonpath='{range .items[*]}{.metadata.name}{","}{.status.readyReplicas}{"\n"}{end}' 2>/dev/null \
        | awk -v t="$t0" 'NF{print t","$0}' >> "$RP"
    dt=$(( $(date +%s%3N) - t0 )); falta=$(( INTERVALO * 1000 - dt ))
    [ "$falta" -gt 0 ] && sleep "$(awk -v f="$falta" 'BEGIN{printf "%.3f", f/1000}')"
done
echo "[coleta-pods] fim: $TOP $CG $RP"
