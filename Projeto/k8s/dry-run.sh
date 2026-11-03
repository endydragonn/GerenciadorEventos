#!/bin/bash
# =============================================================================
#  dry-run.sh  -  issue #197 (etapa 4b): dry-run de carga com autoescala, UMA trilha por execução
# =============================================================================
#
#  Local, NÃO é medição: prova que a autoescala sobe e desce de verdade sob carga, nas duas trilhas de balanceamento (b1, c) e no controle (nenhuma).
#  Cria um cluster kind isolado (gerenciador-e2e-dry: overlay `carga` = e2e + SEED_ENABLED, Mailpit, credenciais efêmeras, sem porta no host; nunca o SMTP real),
#  com capacidades baixas para forçar subida e descida em minutos, roda o roteiro abaixo com o gerador de carga do kit (k6, num pod do cluster) e apaga o cluster.
#
#  Uso:  SLIM_TAG=k8s Projeto/k8s/dry-run.sh --trilha b1|c|nenhuma        (sob o lock Docker do projeto; ~35 a 45 min)
#  Saída: Projeto/k8s/saida/dry-run-<trilha>-<ts>/ (gitignored): replicas.csv, fases.csv, no-stats.csv, k6-*.log, resumo.txt. O código de saída é 0 só com TODOS os critérios ok.
#
#  Roteiro (cada marco vai para fases.csv):
#    parado     `up --so redpanda`: nó só com control plane, CoreDNS, kube-proxy, metrics-server, KEDA e o Redpanda; `docker stats` do nó = linha de base "parado"
#    ocioso     stack inteira no ar e parada (nenhuma requisição): linha de base "ocioso"
#    fundo      tráfego leve contínuo até o fim (5 req/s: 1 reserva e 2 leituras): qualquer 5xx numa descida de réplica aparece aqui
#    A rajada   reservas no `cas` num evento só, acima do limiar (20 req/s com X=600: RPM >> 0,5 x X): reservation (RPM) sobe (o stock fica em 1 réplica fixa, #231); mede se a réplica NOVA do reservation
#               recebe conexões (b1: segundos; c: até um TTL; nenhuma: controle, esperado ocioso)
#    B degrau   a mesma carga oscilando (12 s de pico, 8 s de vale): NENHUMA descida do reservation enquanto o degrau dura (#206)
#    descida    só o tráfego de fundo: o reservation volta ao mínimo depois da janela (K8S_DESCE_SEG) e de 1 pod por 60 s
#    C1/C leitura  C1: o backend fixo em 1 réplica sob carga de leitura que o SATURA (CPU perto do limite, p95 alto ou iterações descartadas); C: a mesma carga com a autoescala solta (backend sobe).
#               Nenhum pod sai do Service, nenhum reinício por probe, sem Unhealthy/Killing por probe nos containers da aplicação (#207)
#    D e-mails  rajada de pedidos de e-mail em identity.notification: notification sobe até 3 réplicas (3 partições) e volta; nenhum e-mail duplicado, nenhum perdido
#    E fim      tudo volta ao mínimo; overselling 0 no CAS (SQL); `adm:replicas` acompanhando o `readyReplicas`
#
#  Critérios automáticos (dry-run-analise.py): o tráfego de fundo rodou do começo ao fim (RESUMO e volume >= 90% do esperado); 0 5xx na janela de 60 s depois de cada descida dos serviços que o fundo
#  exercita (backend, admission, reservation, stock, catalog; identity e notification só informados); relógio do nó x host dentro de 5 s; overselling 0 com o evento esgotado; e-mails = o conjunto esperado;
#  CSV com subidas e descidas; o `stock` fica em 1 réplica o dry-run inteiro (#231, no lugar do antigo "stock chega a 2+ réplicas" da #205);
#  adm:replicas == prontas do reservation fora das transições; réplica nova com tráfego; sem descida no degrau B; sem Unhealthy/Killing/reinício na fase C; CPU do nó por fase.
#  Ao sair (inclusive em falha, por trap) reaplica o overlay `e2e` (sem SEED_ENABLED), confere `POST /bff/seed` -> 404 e apaga o cluster (MANTER=1 mantém).
#
#  Variáveis (todas opcionais): SLIM_TAG (obrigatória até a R5 terminar: k8s), K8S_NOME (gerenciador-e2e-dry), MANTER (1), K8S_ESPERA,
#    DRY_X_RPM (600: o X do admission; o alvo do reservation no KEDA é 0,5 x ele; 500..5000), K8S_CPU_ALVO (20: % do request de CPU para os 4 serviços de CPU: backend, identity, catalog, admission),
#    K8S_DESCE_SEG (45: janela de estabilização da descida; o padrão do cluster é 120), AUTOESCALA_NOTIFICATION_LAG (4: lagThreshold 2), K8S_<SVC>_MIN/_MAX (1/3; o stock não tem: fixo em 1),
#    DRY_CAPACIDADE (30: vagas do evento quente), DRY_USUARIOS (60), DRY_RPS_A (20), DRY_DURACAO_A (300 s), DRY_DURACAO_B (180 s), DRY_RPS_C (1500), DRY_DURACAO_C (150 s), DRY_RPS_C1 (3000), DRY_DURACAO_C1 (90 s),
#    DRY_EMAILS (15000), DRY_ESPERA_DESCIDA (600 s: teto de cada espera de descida).
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJETO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJETO_DIR" || exit 1

TRILHA=""
while [ $# -gt 0 ]; do
    case "$1" in --trilha) TRILHA="${2:-}"; shift 2 ;; *) echo "[ABORTA] argumento desconhecido: $1" >&2; exit 1 ;; esac
done
case "$TRILHA" in b1|c|nenhuma) ;; *) echo "[ABORTA] uso: dry-run.sh --trilha b1|c|nenhuma" >&2; exit 1 ;; esac

NOME="${K8S_NOME:-gerenciador-e2e-dry}"
case "$NOME" in gerenciador-e2e*) ;; *) echo "[ABORTA] K8S_NOME='$NOME': o dry-run só roda em cluster gerenciador-e2e* (overlay carga liga o seed)." >&2; exit 1 ;; esac
CTX="kind-$NOME"; NS=gerenciador; NODE="$NOME-control-plane"; MANTER="${MANTER:-0}"
K6_IMG=grafana/k6:2.2.0; K6_POD=dry-k6
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' ;; esac
PY=""; for c in python3 python; do "$c" -c 'import json' >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || { echo "[ABORTA] preciso de python" >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "[ABORTA] preciso de openssl (credenciais efêmeras)" >&2; exit 1; }

# --- parâmetros (capacidades baixas para forçar subida e descida em minutos) ------------------------------------------------------------------------------------
X_RPM="${DRY_X_RPM:-600}"; RPS_A="${DRY_RPS_A:-20}"; DUR_A="${DRY_DURACAO_A:-300}"; DUR_B="${DRY_DURACAO_B:-180}"
RPS_C="${DRY_RPS_C:-1500}"; DUR_C="${DRY_DURACAO_C:-150}"; CAP="${DRY_CAPACIDADE:-30}"; USUARIOS="${DRY_USUARIOS:-60}"; EMAILS="${DRY_EMAILS:-15000}"
ESPERA_DESCIDA="${DRY_ESPERA_DESCIDA:-600}"; DESCE="${K8S_DESCE_SEG:-45}"; FUNDO_RPS=5
export ADMISSION_X_RPM="$X_RPM" K8S_CPU_ALVO="${K8S_CPU_ALVO:-20}" K8S_DESCE_SEG="$DESCE" AUTOESCALA_NOTIFICATION_LAG="${AUTOESCALA_NOTIFICATION_LAG:-4}"
# credenciais efêmeras (a shell vence o Projeto/.env na interpolação do compose): nada do real entra no cluster
REDIS_PASSWORD="$(openssl rand -hex 16)"; RABBITMQ_USER="dry$(openssl rand -hex 4)"; RABBITMQ_PASSWORD="$(openssl rand -hex 16)"
export REDIS_PASSWORD RABBITMQ_USER RABBITMQ_PASSWORD
export K8S_NOME="$NOME" K8S_OVERLAY=carga

TS="$(date +%Y%m%d-%H%M%S)"; SAIDA="$SCRIPT_DIR/saida/dry-run-$TRILHA-$TS"; mkdir -p "$SAIDA"
REP="$SAIDA/replicas.csv"; FASES="$SAIDA/fases.csv"; NOSTATS="$SAIDA/no-stats.csv"; RESUMO="$SAIDA/resumo.txt"
SUF="$(date +%s | tail -c 8)$((RANDOM % 10))" # dígitos: vira parte do fone dos usuários semeados
FALHAS=0
ok() { echo "   [ok]    $*"; }
erro() { echo "   [ERRO]  $*"; FALHAS=$((FALHAS + 1)); }
marca() { echo "$(date +%s),$1,$2" >>"$FASES"; echo "==> $1 $2 ($(date +%H:%M:%S))"; }

cl() { bash "$SCRIPT_DIR/cluster.sh" "$@"; }
k() { kubectl --context "$CTX" -n "$NS" "$@"; }
kp() { k exec -i "$K6_POD" -- "$@"; }
psql_db() { cl psql -At <<<"$1" | tr -d '\r'; }
prontas() { k get deploy "$1" -o jsonpath='{.status.readyReplicas}' 2>/dev/null | tr -d '[:space:]'; }
adm_replicas() { cl redis-cli GET adm:replicas 2>/dev/null | tr -d '[:space:]'; }
esperar_prontas() { # servico valor timeout_s -> 0 se chegou
    local fim=$((SECONDS + $3))
    while [ "$SECONDS" -lt "$fim" ]; do [ "$(prontas "$1")" = "$2" ] && return 0; sleep 3; done
    return 1
}

PID_REG=""; PID_STATS=""; PID_FUNDO=""; PID_TOP=""; PID_A=""
parar_bg() { # os laços e subshells em segundo plano (sem job control o Ctrl+C não os alcança: ficariam órfãos no host)
    local p
    for p in "$PID_REG" "$PID_STATS" "$PID_TOP" "$PID_A"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done
    PID_REG=""; PID_STATS=""; PID_TOP=""; PID_A=""
}
FUNDO_MORTO=0
fundo_vivo() { # o fundo e o que dá sentido ao "0 5xx pós-descida": se morrer, o critério passaria vácuo (o analisador também confere o RESUMO e o volume de requisições)
    [ "$FUNDO_MORTO" = 1 ] && return 0
    if [ -z "$PID_FUNDO" ] || ! kill -0 "$PID_FUNDO" 2>/dev/null; then
        FUNDO_MORTO=1; erro "o tráfego de fundo (k6) morreu antes de '$1': as descidas seguintes não têm requisição para provar 0 5xx (ver k6-fundo.log)"
    fi
}
# diferença (s) entre o relógio do nó (VM do Docker: onde o k6 carimba o ERRO5XX) e o do host (que carimba o CSV); positivo = nó adiantado
offset_relogio() {
    local a n b
    a="$(date +%s)"; n="$(docker exec "$NODE" date +%s 2>/dev/null | tr -d '\r')"; b="$(date +%s)"
    [ -n "$n" ] && echo $((n - (a + b) / 2)) || echo ""
}
finalizar() { # sempre: o overlay volta ao e2e (sem SEED_ENABLED), POST /bff/seed deve dar 404, e o cluster some (salvo MANTER=1)
    local rc=$?
    trap - EXIT INT TERM
    parar_bg
    if kind get clusters 2>/dev/null | tr -d '\r' | grep -qx "$NOME"; then
        echo "==> (saída) reaplicando o overlay e2e (sem SEED_ENABLED) e conferindo POST /bff/seed -> 404"
        kp pkill k6 >/dev/null 2>&1 # qualquer k6 da fase em curso (o kubectl exec local morre no Ctrl+C, o processo remoto não)
        if K8S_OVERLAY=e2e cl up --trilha "$TRILHA" >"$SAIDA/reaplicar-e2e.log" 2>&1; then
            local cod
            cod="$(kp wget -S -O /dev/null --post-data='{}' --header='Content-Type: application/json' http://backend:8081/bff/seed 2>&1 | grep -o 'HTTP/[0-9.]* [0-9]*' | tail -1 | grep -o '[0-9]*$')"
            if [ "$cod" = 404 ]; then ok "POST /bff/seed depois do dry-run: 404 (overlay e2e reaplicado)"; echo "seed_depois=404" >>"$SAIDA/criterios.txt"
            else erro "POST /bff/seed depois do dry-run: '$cod' (esperado 404)"; echo "seed_depois=$cod" >>"$SAIDA/criterios.txt"; fi
        else erro "reaplicar o overlay e2e falhou (ver $SAIDA/reaplicar-e2e.log): o SEED_ENABLED pode ter ficado ligado; o cluster será apagado de qualquer jeito"; fi
        if [ "$MANTER" = 1 ]; then echo "==> MANTER=1: cluster $NOME mantido (apagar: kind delete cluster --name $NOME)"; else cl down >/dev/null 2>&1; fi
    fi
    echo "==> saída em $SAIDA (kind: [$(kind get clusters 2>/dev/null | tr '\n' ' ')])"
    [ "$rc" -ne 0 ] && exit "$rc"
    [ "$FALHAS" -eq 0 ]
    exit $?
}
trap finalizar EXIT
trap 'exit 130' INT TERM

echo "=================================================="
echo "   dry-run #197: trilha=$TRILHA cluster=$NOME X=$X_RPM cpu-alvo=${K8S_CPU_ALVO}% desce=${DESCE}s  saída=$SAIDA"
echo "=================================================="
echo "ts,fase,evento" >"$FASES"; echo "ts,cpu_pct,mem" >"$NOSTATS"
cl guarda --trilha "$TRILHA" || { echo "[ABORTA] guarda de isolamento"; exit 1; }

# --- nó: docker stats a cada ~5 s, em segundo plano, durante tudo --------------------------------------------------------------------------------------------------
( while :; do docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}}' "$NODE" 2>/dev/null | tr -d '\r' | sed "s/^/$(date +%s),/" | sed 's/%//; s/ \/ [^,]*$//' >>"$NOSTATS"; sleep 3; done ) &
PID_STATS=$!

# --- 1. linha de base "parado": o nó só com a infra do kind, KEDA, metrics-server e o Redpanda -------------------------------------------------------------------
marca criar inicio
cl up --trilha "$TRILHA" --so redpanda >"$SAIDA/up-redpanda.log" 2>&1 || { tail -20 "$SAIDA/up-redpanda.log"; echo "[ABORTA] up --so redpanda"; exit 1; }
marca criar fim
RELOGIO_INI="$(offset_relogio)" # só agora o contêiner do nó existe
sleep 30 # o nó assenta (criar o cluster, carregar imagens e os helm installs não entram na linha de base)
marca parado inicio
sleep 60
marca parado fim

# --- 2. stack inteira ------------------------------------------------------------------------------------------------------------------------------------------------
marca up inicio
cl up --trilha "$TRILHA" >"$SAIDA/up.log" 2>&1 || { tail -30 "$SAIDA/up.log"; echo "[ABORTA] up"; exit 1; }
marca up fim
if [ "$TRILHA" = c ]; then # a JVM do backend, COM o perfil Seccomp, leu a JAVA_TOOL_OPTIONS (sem getegid no perfil ela e ignorada em silencio)
    echo "javasec_log=$(k logs deploy/backend -c backend 2>&1 | grep -c '^Picked up JAVA_TOOL_OPTIONS: -Djava.security.properties=')" >>"$SAIDA/criterios.txt"
fi
K8S_NOME="$NOME" bash "$SCRIPT_DIR/registrar-replicas.sh" "$REP" >/dev/null 2>&1 &
PID_REG=$!
cl imagem "$K6_IMG" >/dev/null || { echo "[ABORTA] carregar $K6_IMG no nó"; exit 1; }
k delete pod "$K6_POD" --ignore-not-found --now >/dev/null 2>&1
k run "$K6_POD" --image="$K6_IMG" --labels=sidecar.istio.io/inject=false --image-pull-policy=IfNotPresent --restart=Never \
    --overrides='{"spec":{"automountServiceAccountToken":false,"containers":[{"name":"dry-k6","image":"grafana/k6:2.2.0","command":["sleep","infinity"],"resources":{"limits":{"cpu":"6"}}}]}}' --command -- sleep infinity >/dev/null || { echo "[ABORTA] pod do k6"; exit 1; }
k wait --for=condition=ready "pod/$K6_POD" --timeout=180s >/dev/null || { echo "[ABORTA] pod do k6 não ficou pronto"; exit 1; }
for f in carga.js semear.js; do kp sh -c "cat > /tmp/$f" <"$SCRIPT_DIR/dry-run/$f" || { echo "[ABORTA] copiar $f"; exit 1; }; done
sleep 60
marca ocioso inicio; sleep 60; marca ocioso fim

# O k6 roda no MESMO nó que a stack (16 núcleos do host): sem teto ele rouba CPU do nó e das probes, e o que se mede deixa de ser a autoescala. limits.cpu=6 no pod do k6.
# --- 3. semear (POST /bff/seed, só no overlay carga) + pool de usuários + evento quente -----------------------------------------------------------------------------
marca semear inicio
kp k6 run --quiet -e SUFIXO="$SUF" -e CAPACIDADE="$CAP" /tmp/semear.js >"$SAIDA/k6-semear.log" 2>&1 || { tail -5 "$SAIDA/k6-semear.log"; echo "[ABORTA] seed"; exit 1; }
EVENT_ID="$(psql_db "SELECT event_id FROM catalog.event WHERE event_name = 'Dry quente $SUF'")"
[ -n "$EVENT_ID" ] || { echo "[ABORTA] evento semeado não encontrado"; exit 1; }
psql_db "INSERT INTO identity.users (user_name, email, fone, password, birthdate)
    SELECT 'Dry ' || g, 'dry-$SUF-' || g || '@example.com', 'dr-$SUF-' || g, (SELECT password FROM identity.users WHERE email = 'base-$SUF@example.com'), DATE '1990-01-15'
    FROM generate_series(1, $USUARIOS) AS g" >/dev/null
[ "$(psql_db "SELECT count(*) FROM identity.users WHERE email LIKE 'dry-$SUF-%@example.com'")" = "$USUARIOS" ] || { echo "[ABORTA] usuários do pool"; exit 1; }
# a janela de confirmação não pode devolver vagas no meio do dry-run (o job de expiração é de outro assunto): 1 h
[ "$(psql_db "WITH u AS (UPDATE stock.eventcap SET max_time_in_line = 3600 WHERE event_id = $EVENT_ID RETURNING 1) SELECT count(*) FROM u")" = 1 ] || { echo "[ABORTA] eventcap do evento quente"; exit 1; }
marca semear fim
ok "evento quente $EVENT_ID (capacidade $CAP), $USUARIOS usuários, token por login no k6"

k6run() { # nome modo stages [usuarios]: roda o k6 em primeiro plano, log em $SAIDA/k6-<nome>.log
    kp k6 run --quiet -e MODO="$2" -e EVENT_ID="$EVENT_ID" -e STAGES="$3" -e PREFIXO="dry-$SUF" -e USUARIOS="$USUARIOS" -e MAX_VUS="${4:-400}" /tmp/carga.js >"$SAIDA/k6-$1.log" 2>&1
}

# --- 4. tráfego de fundo (até o fim) --------------------------------------------------------------------------------------------------------------------------------
marca fundo inicio
( k6run fundo fundo "7200s:$FUNDO_RPS" 50 ) &
PID_FUNDO=$!
sleep 20
fundo_vivo "A"

# --- 5. A: rajada no cas acima do limiar ------------------------------------------------------------------------------------------------------------------------------
marca A inicio
( k6run A reserva "${DUR_A}s:$RPS_A" 200 ) &
PID_A=$!
NOVO=""; T_NOVO=""; T_CONN=""; MAX_RES=0
# janela da 1ª conexão no pod novo: b1 balanceia por requisição ("segundos": 15 s), c "em até um TTL" (TTL 15 s do client-lb + cache de DNS 5 s + folga de amostragem: 35 s);
# nenhuma (controle) só informa, 60 s: sob carga o Service L4 também leva conexões NOVAS ao pod novo (a prova do controle ocioso é o passo 7 do e2e)
case "$TRILHA" in b1) JANELA_CONN=15 ;; c) JANELA_CONN=35 ;; *) JANELA_CONN=60 ;; esac
conexoes_8082() { # pod: conexões ESTABLISHED na porta 8082 dentro do netns do pod novo (lido do nó, como o passo 7 do e2e)
    local cid pid
    cid="$(k get pod "$1" -o jsonpath='{.status.containerStatuses[?(@.name=="reservation")].containerID}' | sed 's#.*://##')"
    pid="$(docker exec "$NODE" crictl inspect --output go-template --template '{{.info.pid}}' "$cid" 2>/dev/null | tr -d '\r')"
    [ -n "$pid" ] || { echo 0; return; }
    docker exec "$NODE" sh -c "cat /proc/$pid/net/tcp /proc/$pid/net/tcp6" | tr -d '\r' | awk -v p="$(printf '%04X' 8082)" '$4 == "01" {n = split($2, a, ":"); if (a[n] == p) c++} END {print c + 0}'
}
PRIMEIRO="$(k get pods -l app=reservation -o jsonpath='{.items[0].metadata.name}')"
while kill -0 "$PID_A" 2>/dev/null; do
    r="$(prontas reservation)"; r="${r:-0}"; [ "$r" -gt "$MAX_RES" ] && { MAX_RES=$r; echo "   reservation prontas=$r ($(date +%H:%M:%S))"; }
    if [ -z "$NOVO" ] && [ "$r" -ge 2 ]; then
        NOVO="$(k get pods -l app=reservation --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1:].metadata.name}')"
        [ "$NOVO" = "$PRIMEIRO" ] && NOVO=""
        [ -n "$NOVO" ] && T_NOVO=$SECONDS
    fi
    if [ -n "$NOVO" ] && [ -z "$T_CONN" ] && [ $((SECONDS - T_NOVO)) -le "$JANELA_CONN" ]; then
        [ "$(conexoes_8082 "$NOVO")" -ge 1 ] && T_CONN=$((SECONDS - T_NOVO))
    fi
    sleep 2
done
wait "$PID_A"; PID_A=""
marca A fim
echo "replica_nova=$NOVO pronta_ate_conexao_s=${T_CONN:-nenhuma} janela_s=$JANELA_CONN" >"$SAIDA/replica-nova.txt"
echo "   réplica nova do reservation ($NOVO): primeira conexão na 8082 depois de ${T_CONN:-nenhuma} s (janela $JANELA_CONN s)"

# --- 6. B: degrau oscilante (a mesma carga, 12 s de pico e 8 s de vale) -----------------------------------------------------------------------------------------------
ST=""; n=$((DUR_B / 20)); [ "$n" -ge 1 ] || n=1
for _ in $(seq 1 "$n"); do ST="${ST}12s:$RPS_A,8s:1,"; done
marca B inicio
k6run B reserva "${ST%,}" 200
marca B fim

# --- 7. descida do reservation com só o tráfego de fundo (o stock é fixo em 1 réplica, #231: aqui só se confere que continua em 1) ----------------------------------------------------------------------------------------------------
fundo_vivo "descida"
marca descida inicio
esperar_prontas reservation 1 "$ESPERA_DESCIDA" && ok "reservation voltou a 1" || erro "reservation não voltou a 1 em ${ESPERA_DESCIDA}s (prontas=$(prontas reservation))"
[ "$(prontas stock)" = 1 ] && ok "stock segue em 1 réplica (fora da autoescala)" || erro "stock não está em 1 réplica (prontas=$(prontas stock))"
marca descida fim
fundo_vivo "C1"

# --- 8. C: carga de leitura acima da capacidade de uma réplica do backend (#207) ---------------------------------------------------------------------------------------
EV_ANTES="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# C1: o backend FIXO em 1 réplica (min=max=1 no ScaledObject, como o passo 7 do e2e) e a carga de leitura num nível que a SATURA (evidência: CPU perto do limite de 2 núcleos, p95 alto ou
# iterações descartadas): sem isso "nenhum pod saiu do Service" seria vácuo. C2 (abaixo): a mesma carga com a autoescala solta.
RPS_C1="${DRY_RPS_C1:-3000}"; DUR_C1="${DRY_DURACAO_C1:-90}"
k patch scaledobject backend --type merge -p '{"spec":{"minReplicaCount":1,"maxReplicaCount":1}}' >/dev/null || erro "patch do ScaledObject do backend para 1/1"
esperar_prontas backend 1 240 || erro "backend não ficou em 1 réplica para a fase C1"
marca C1 inicio
# `top --containers`: o consumo do container `backend` sozinho (o `top pod` soma o `istio-proxy` na b1); formato `<ts> <pod> backend <cpu> <mem>`
( while :; do k top pod -l app=backend --containers --no-headers 2>/dev/null | awk -v t="$(date +%s)" '$2 == "backend" {print t, $0}' >>"$SAIDA/top-C1.txt"; sleep 5; done ) &
PID_TOP=$!
k6run C1 leitura "15s:$((RPS_C1 / 5)),10s:$RPS_C1,${DUR_C1}s:$RPS_C1" 3000
kill "$PID_TOP" 2>/dev/null; PID_TOP=""
marca C1 fim
k patch scaledobject backend --type merge -p "{\"spec\":{\"minReplicaCount\":${K8S_BACKEND_MIN:-1},\"maxReplicaCount\":${K8S_BACKEND_MAX:-3}}}" >/dev/null || erro "patch do ScaledObject do backend de volta"
marca C inicio
k6run C leitura "15s:$((RPS_C / 5)),10s:$RPS_C,${DUR_C}s:$RPS_C" 1200
marca C fim
sleep 20
k get events -o json >"$SAIDA/eventos-C.json" 2>/dev/null
k get pods -o json >"$SAIDA/pods-C.json" 2>/dev/null
echo "$EV_ANTES" >"$SAIDA/eventos-C-desde.txt"

# --- 9. D: rajada de e-mails (notification até 3 réplicas e volta) --------------------------------------------------------------------------------------------------------
marca D inicio
produzir() { # fatia (0..2): os pedidos i = fatia (mod 3), um e-mail DISTINTO por pedido
"$PY" -c '
import datetime, json, sys, uuid
n, suf, fatia = int(sys.argv[1]), sys.argv[2], int(sys.argv[3])
agora = datetime.datetime.now(datetime.timezone.utc)
fmt = lambda t: t.strftime("%Y-%m-%dT%H:%M:%S.000Z")
for i in range(1 + fatia, n + 1, 3):
    print("%d %s" % (i, json.dumps({"eventId": str(uuid.uuid4()), "streamType": "identity.notification", "streamId": str(uuid.uuid4()), "version": 1, "globalPosition": 0,
        "eventType": "VerificationCodeRequested", "schemaVersion": 1, "occurredAt": fmt(agora),
        "payload": {"email": "dryrun-%s-%d@example.com" % (suf, i), "userName": "Dry", "code": "%06d" % (i % 1000000), "expirationMinutes": 240,
                    "expiresAt": fmt(agora + datetime.timedelta(hours=4))}, "metadata": {}}, separators=(",", ":"))))
' "$EMAILS" "$SUF" "$1" | cl rpk topic produce identity.notification -f '%k %v
' >"$SAIDA/rpk-produce-$1.log" 2>&1
}
PROD=(); for f in 0 1 2; do produzir "$f" & PROD+=($!); done; wait "${PROD[@]}"
echo "   $(cat "$SAIDA"/rpk-produce-*.log | grep -c 'Produced') mensagens produzidas em identity.notification (3 produtores)"
mailpit_total() { kp wget -qO- 'http://mailpit:8025/api/v1/messages?limit=1' 2>/dev/null | grep -o '"messages_count":[0-9]*' | head -1 | grep -o '[0-9]*$'; }
fundo_vivo "D"
MAX_NOT=0; fim=$((SECONDS + 900))
while [ "$SECONDS" -lt "$fim" ]; do
    r="$(prontas notification)"; r="${r:-0}"; [ "$r" -gt "$MAX_NOT" ] && { MAX_NOT=$r; echo "   notification prontas=$r ($(date +%H:%M:%S))"; }
    t="$(mailpit_total)"; t="${t:-0}"
    [ "$t" -ge "$EMAILS" ] && break
    sleep 5
done
echo "   e-mails no Mailpit: $(mailpit_total) de $EMAILS"
marca D fim
esperar_prontas notification 1 "$ESPERA_DESCIDA" && ok "notification voltou a 1" || erro "notification não voltou a 1 (prontas=$(prontas notification))"
for st in $(seq 0 500 "$EMAILS"); do kp wget -qO- "http://mailpit:8025/api/v1/messages?start=$st&limit=500" >"$SAIDA/mailpit-p$(printf %06d "$st").json" 2>/dev/null; done # a API limita a pagina a 500

# --- 10. E: fim -> tudo no mínimo, overselling 0 -------------------------------------------------------------------------------------------------------------------------
fundo_vivo "E"
marca E inicio
for s in backend identity catalog reservation stock notification admission; do
    esperar_prontas "$s" 1 "$ESPERA_DESCIDA" && ok "$s no mínimo (1)" || erro "$s não voltou ao mínimo (prontas=$(prontas "$s"))"
done
for _ in $(seq 1 10); do [ "$(adm_replicas)" = 1 ] && break; sleep 2; done
echo "adm_replicas_final=$(adm_replicas)" >>"$SAIDA/criterios.txt"
psql_db "SELECT 'evento=' || ec.event_id || ' capacity=' || ec.capacity || ' quant=' || ec.quant || ' ativas=' ||
    (SELECT count(*) FROM reservation.walletevent w WHERE w.event_id = ec.event_id AND w.status IN ('pending', 'confirmed')) FROM stock.eventcap ec WHERE ec.event_id = $EVENT_ID" >"$SAIDA/overselling.txt"
psql_db "SELECT count(*) FROM stock.eventcap ec WHERE ec.capacity <> -1 AND (SELECT count(*) FROM reservation.walletevent w WHERE w.event_id = ec.event_id AND w.status IN ('pending', 'confirmed')) > ec.capacity" >"$SAIDA/overselling-eventos.txt"
psql_db "SELECT count(*) FROM stock.eventcap ec WHERE ec.capacity <> -1 AND ec.quant <> ec.capacity - (SELECT count(*) FROM stock.hold h WHERE h.event_id = ec.event_id AND h.status = 'held' AND h.decremented)" >"$SAIDA/quant-incoerente.txt"
marca E fim
fundo_vivo "o fim"

# --- 11. fim do tráfego de fundo, restos para a análise --------------------------------------------------------------------------------------------------------------------
kp pkill -f fundo >/dev/null 2>&1; kp pkill k6 >/dev/null 2>&1; wait "$PID_FUNDO" 2>/dev/null; PID_FUNDO=""
RELOGIO_FIM="$(offset_relogio)"
k get pods -o json >"$SAIDA/pods-fim.json" 2>/dev/null
echo "suf=$SUF emails_esperados=$EMAILS evento=$EVENT_ID capacidade=$CAP trilha=$TRILHA x=$X_RPM cpu_alvo=$K8S_CPU_ALVO desce=$DESCE fundo_rps=$FUNDO_RPS relogio_ini=$RELOGIO_INI relogio_fim=$RELOGIO_FIM" >"$SAIDA/parametros.txt"
parar_bg
AN="$SCRIPT_DIR/dry-run/dry-run-analise.py"; SD="$SAIDA"; command -v cygpath >/dev/null 2>&1 && { AN="$(cygpath -m "$AN")"; SD="$(cygpath -m "$SAIDA")"; }
PYTHONUTF8=1 "$PY" "$AN" "$SD" | tee "$RESUMO"
rc=${PIPESTATUS[0]}
[ "$rc" -eq 0 ] || FALHAS=$((FALHAS + 1))
