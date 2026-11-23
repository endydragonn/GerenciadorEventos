#!/usr/bin/env bash
# Mede o custo de SUBIR cada container, um por vez, num project isolado e com volumes novos:
#   tempo até pronto, CPU-segundos gastos no boot, e depois de assentar, RAM e CPU em repouso de TODOS os containers do project
#   (o delta da infra a cada passo sai da mesma tabela).
#
#   carga/consumo/medir-consumo.sh servicos <rep> <saida>
#       stack atual (imagens slim do compose): infra uma a uma -> 8 serviços na ordem das dependências -> +1 réplica de 6 serviços
#   MONO_DIR=<worktree em 2980675>/Projeto carga/consumo/medir-consumo.sh monolito <rep> <saida>
#       (MONO_DIR em formato Windows, C:/...: com MSYS_NO_PATHCONV o /c/... não é convertido)
#       monólito pré-#32 (imagem já construída com o mesmo project): infra uma a uma -> backend -> 2ª instância do backend
#
# Rodar de Projeto/. Saída: <saida>/<fase>-rep<rep>.csv, formato longo (uma linha por container por passo; servico=VM é a
# memória usada pela VM do Docker). Derruba o project (down -v) no início e no fim. Nunca toca a stack real
# (project, rede e volumes próprios; nenhum container_name; nenhuma porta no host).
#
# Leitura: CPU e memória pelos contadores do cgroup v2 de cada container (cpu.stat usage_usec, memory.current, anon de
# memory.stat), lidos por um container amostrador (alpine) com /sys/fs/cgroup montado só-leitura. Pronto = linha
# "Started X in N seconds" do Spring nos serviços Java (as duas variantes têm), healthy nos demais (running + 5 s sem healthcheck).
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
FASE=${1:?fase: servicos|monolito} REP=${2:?rep} SAIDA=${3:?pasta de saída}
ASSENTAR=${ASSENTAR:-60} JANELA=${JANELA:-30} AMOSTRA=${AMOSTRA:-2} ESPERA=${ESPERA:-300}
AQUI=$(cd "$(dirname "$0")" && { pwd -W 2>/dev/null || pwd; }) # -W: caminho Windows no Git Bash (MSYS_NO_PATHCONV não converte)
mkdir -p "$SAIDA"
CSV="$SAIDA/$FASE-rep$REP.csv"
AMOSTRADOR=consumo-amostrador
JAVA=" identity catalog stock reservation admission notification backend "
aleatorio() { head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

case $FASE in
  servicos)
    PROJ=gerenciador-consumo
    ARQS=(-f docker-compose.yml -f "$AQUI/docker-compose.consumo.yml")
    INFRA=(db supabase-pooler redpanda redis rabbitmq mailpit)
    SERVICOS=(identity catalog stock reservation admission notification backend frontend)
    REPLICAS=(identity catalog reservation admission notification backend) # stock é 1 réplica fixa (#231)
    # credenciais efêmeras (nunca as do Projeto/.env); a shell vence o .env na interpolação
    export INTERNAL_API_TOKEN=consumo-token-de-teste REDIS_PASSWORD=$(aleatorio) RABBITMQ_USER=consumo RABBITMQ_PASSWORD=$(aleatorio)
    export SLIM_TAG=${SLIM_TAG:-consumo} # lote próprio das slim, sem tocar o :latest
    ;;
  monolito)
    : "${MONO_DIR:?MONO_DIR = pasta Projeto do worktree em 2980675}"
    PROJ=gerenciador-consumo-mono
    ARQS=(-f "$MONO_DIR/docker-compose.yml" -f "$AQUI/docker-compose.consumo-mono.yml")
    INFRA=(db supabase-pooler redpanda)
    SERVICOS=(backend)
    REPLICAS=(backend)
    ;;
  *) echo "fase inválida: $FASE" >&2; exit 2 ;;
esac
dc() { docker compose -p "$PROJ" "${ARQS[@]}" "$@"; }
agora() { date +%s.%N; }
log() { echo "[$(date +%T)] $*"; }

derrubar() {
  docker ps -aq --filter "label=com.docker.compose.project=$PROJ" | xargs -r docker rm -f >/dev/null
  dc down -v --remove-orphans >/dev/null 2>&1 || true
}
encerrar() { derrubar; docker rm -f "$AMOSTRADOR" >/dev/null 2>&1 || true; }
trap encerrar EXIT

declare -A CG NOME
CIDS=()

cgroup_de() { # caminho do cgroup do container (o mais curto que contém o id)
  docker exec "$AMOSTRADOR" sh -c "find /cg -maxdepth 6 -type d -name '*$1*' 2>/dev/null | awk '{print length, \$0}' | sort -n | head -1 | cut -d' ' -f2-"
}
acompanhar() { # $1 cid, $2 servico
  local d; d=$(cgroup_de "$1"); [ -n "$d" ] || { echo "cgroup do container $1 não encontrado" >&2; exit 1; }
  CG[$1]=$d; NOME[$1]=$2; CIDS+=("$1")
}
ler_todos() { # uma linha "cid usage_usec memory_current anon" por container acompanhado + "VM 0 usada 0"
  local args="" c
  for c in "${CIDS[@]}"; do args+="$c=${CG[$c]} "; done
  docker exec "$AMOSTRADOR" sh -c 'for p in '"$args"'; do c=${p%%=*}; d=${p#*=}; [ -f $d/cpu.stat ] || continue
    u=$(awk "/^usage_usec/{print \$2}" $d/cpu.stat); m=$(cat $d/memory.current); a=$(awk "/^anon /{print \$2}" $d/memory.stat)
    echo "$c $u $m $a"; done
    awk "/^MemTotal/{t=\$2} /^MemAvailable/{a=\$2} END{print \"VM\", 0, (t-a)*1024, 0}" /proc/meminfo'
}
cpu_usec() { docker exec "$AMOSTRADOR" sh -c "awk '/^usage_usec/{print \$2}' ${CG[$1]}/cpu.stat"; }

PASSO=0
registrar() { # $1 rótulo; $2 cid novo (vazio no passo 0); $3 t_pronto_s; $4 jvm_start_s; $5 cpu_boot_s
  local rot=$1 novo=${2:-} tp=${3:-} jvm=${4:-} boot=${5:-} tmp ini fim t_ini t_fim i
  log "  assentando ${ASSENTAR}s e medindo ${JANELA}s ($rot)"
  sleep "$ASSENTAR"
  tmp=$(mktemp)
  t_ini=$(agora); ini=$(ler_todos)
  for ((i = 0; i < JANELA / AMOSTRA; i++)); do ler_todos >>"$tmp"; sleep "$AMOSTRA"; done
  t_fim=$(agora); fim=$(ler_todos)
  local nomes="" c; for c in "${CIDS[@]}"; do nomes+="$c=${NOME[$c]} "; done
  awk -v rep="$REP" -v passo="$PASSO" -v rot="$rot" -v novo="$novo" -v tp="$tp" -v jvm="$jvm" -v boot="$boot" \
      -v dt="$(awk -v a="$t_ini" -v b="$t_fim" 'BEGIN{print b-a}')" -v nomes="$nomes" -v ini="$ini" -v fim="$fim" '
    function mediana(s,   v, n) { n = split(s, v, " "); asort(v); return n % 2 ? v[(n + 1) / 2] : (v[n / 2] + v[n / 2 + 1]) / 2 }
    BEGIN {
      n = split(nomes, p, " "); for (i = 1; i <= n; i++) { split(p[i], q, "="); svc[q[1]] = q[2] }; svc["VM"] = "VM"
      n = split(ini, l, "\n"); for (i = 1; i <= n; i++) { split(l[i], f, " "); u0[f[1]] = f[2] }
      n = split(fim, l, "\n"); for (i = 1; i <= n; i++) { split(l[i], f, " "); u1[f[1]] = f[2] }
    }
    { mem[$1] = mem[$1] " " $3; anon[$1] = anon[$1] " " $4 }
    END {
      for (c in mem) {
        e = (c == novo)
        cpu = (c == "VM" || !(c in u0) || !(c in u1)) ? "" : sprintf("%.2f", (u1[c] - u0[c]) / (dt * 1e6) * 100)
        printf "%s,%s,%s,%s,%s,%d,%s,%s,%s,%s,%.1f,%.1f\n", rep, passo, rot, svc[c], substr(c, 1, 12), e,
          e ? tp : "", e ? jvm : "", e ? boot : "", cpu, mediana(mem[c]) / 1048576, mediana(anon[c]) / 1048576
      }
    }' "$tmp" | sort -t, -k4,4 >>"$CSV"
  rm -f "$tmp"
  PASSO=$((PASSO + 1))
}

esperar_pronto() { # $1 cid, $2 servico, $3 t0 -> ecoa "t_pronto,jvm_start,cpu_boot"
  local cid=$1 svc=$2 t0=$3 java=0 tp="" jvm="" boot="" st h linha lim
  [[ $JAVA == *" $svc "* ]] && java=1
  lim=$(awk -v a="$t0" -v e="$ESPERA" 'BEGIN{printf "%.3f", a+e}') # printf: o print do awk usa %.6g e arredonda o epoch a 10^4 s
  while :; do
    read -r st h < <(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid")
    [[ $st == exited || $st == dead ]] && { echo "$svc saiu ($st) antes de ficar pronto" >&2; docker logs --tail 40 "$cid" >&2; exit 1; }
    if [ -z "$tp" ]; then
      if ((java)); then
        linha=$(docker logs "$cid" 2>&1 | grep -m1 -oE 'Started [A-Za-z0-9]+ in [0-9.]+ seconds' || true)
        [ -n "$linha" ] && { tp=$(awk -v a="$t0" -v b="$(agora)" 'BEGIN{printf "%.1f", b-a}'); boot=$(cpu_usec "$cid"); jvm=$(awk '{print $(NF-1)}' <<<"$linha"); }
      elif [[ $h == healthy || ($h == none && $st == running) ]]; then
        [[ $h == none ]] && sleep 5
        tp=$(awk -v a="$t0" -v b="$(agora)" 'BEGIN{printf "%.1f", b-a}'); boot=$(cpu_usec "$cid")
      fi
    fi
    [[ -n $tp && ($h == healthy || $h == none) ]] && break
    awk -v l="$lim" -v n="$(agora)" 'BEGIN{exit !(n > l)}' && { echo "$svc não ficou pronto em ${ESPERA}s ($st/$h)" >&2
      docker inspect -f '{{json .State.Health}}' "$cid" >"$SAIDA/falha-$FASE-rep$REP-$svc.health.json" 2>&1 || true
      docker logs "$cid" >"$SAIDA/falha-$FASE-rep$REP-$svc.log" 2>&1 || true; exit 1; }
    sleep 1
  done
  echo "$tp,$jvm,$(awk -v u="$boot" 'BEGIN{printf "%.1f", u/1e6}')" # vírgula: jvm vazio (infra) não pode sumir no word splitting
}

subir() { # $1 servico, $2 "servico"|"replica"
  local svc=$1 modo=$2 t0 cid r rot
  t0=$(agora)
  if [ "$modo" = servico ]; then
    dc up -d --no-deps --pull never "$svc" >/dev/null
    cid=$(dc ps -q --no-trunc "$svc"); rot=$svc
  else
    cid=$(dc run -d --no-deps --pull never --name "$PROJ-$svc-2" "$svc" | tail -1)
    cid=$(docker inspect -f '{{.Id}}' "$cid"); rot="$svc+1"
  fi
  acompanhar "$cid" "$svc"
  r=$(esperar_pronto "$cid" "$svc" "$t0")
  log "  $rot pronto: t_pronto/jvm/cpu_boot = $r"
  registrar "$rot" "$cid" "${r%%,*}" "$(cut -d, -f2 <<<"$r")" "${r##*,}"
}

# --- execução ---
log "fase=$FASE rep=$REP project=$PROJ saída=$CSV"
[ -e "$CSV" ] && { echo "$CSV já existe; apague ou use outro rep" >&2; exit 1; }
derrubar
docker rm -f "$AMOSTRADOR" >/dev/null 2>&1 || true
docker run -d --name "$AMOSTRADOR" --cgroupns host -v /sys/fs/cgroup:/cg:ro alpine:3.20 sleep infinity >/dev/null
echo "rep,passo,rotulo,servico,container,novo,t_pronto_s,jvm_start_s,cpu_boot_s,cpu_ocioso_pct,mem_mb,anon_mb" >"$CSV"
log "passo 0: vazio (só a VM e o amostrador)"
registrar vazio
for s in "${INFRA[@]}"; do log "infra: $s"; subir "$s" servico; done
if [ "$FASE" = servicos ]; then # one-shots: só preparam os groups, não são medidos
  # o `rpk group seek` do notification-seek falha com UNKNOWN_TOPIC_OR_PARTITION se o tópico não existe (aqui o identity ainda não subiu para criá-lo; o stock-seek já cria o dele)
  docker exec "$(dc ps -q redpanda)" rpk topic create identity.notification -p 1 -r 1 >/dev/null 2>&1 || true
  dc up -d --no-deps --pull never stock-seek notification-seek >/dev/null
  for s in stock-seek notification-seek; do [ "$(docker wait "$(dc ps -aq "$s")")" = 0 ] || { echo "$s falhou" >&2; exit 1; }; done
fi
for s in "${SERVICOS[@]}"; do log "serviço: $s"; subir "$s" servico; done
for s in "${REPLICAS[@]}"; do log "réplica: $s"; subir "$s" replica; done
log "fim: $CSV"
