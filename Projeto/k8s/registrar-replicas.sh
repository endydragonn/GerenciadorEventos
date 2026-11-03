#!/bin/bash
# =============================================================================
#  registrar-replicas.sh  -  issue #197 (etapa 4b): réplicas dos 7 serviços ao longo do tempo, para o dry-run de carga com autoescala
# =============================================================================
#  A cada 2 s (REGISTRAR_INTERVALO_S) grava no CSV `ts,servico,prontas,desejadas,metrica`, uma linha por serviço Java (os 6 autoescalados mais o `stock`, fixo em 1 réplica desde a #231: sem HPA, `desejadas` vazio), lidas de UM `kubectl get deploy,hpa`:
#    ts        epoch em segundos
#    servico   backend | identity | catalog | reservation | stock | notification | admission
#    prontas   status.readyReplicas do Deployment (0 se ausente)
#    desejadas status.desiredReplicas do HPA que o KEDA cria (keda-hpa-<servico>)
#    metrica   valor atual lido pelo HPA: o `averageValue` do HPA, RPM do evento mais quente ÷ réplicas atuais, POR POD (reservation, via `metrics-api`), o lag (notification, `kafka`) ou a utilização de CPU em % do request
#              do container da aplicação (os outros quatro; o `stock` não tem HPA e fica sem métrica). Texto cru do HPA (o RPM pode vir como quantidade, p.ex. `1200` ou `833500m`).
#  Mais uma linha `adm-replicas,<valor>,,` por ciclo: o `adm:replicas` que o admission grava no Redis (D4); vazio se a chave expirou (REGISTRAR_ADM=0 desliga).
#
#  Uso:  K8S_NOME=gerenciador-e2e-dry Projeto/k8s/registrar-replicas.sh [arquivo.csv]     (roda até receber SIGTERM/Ctrl-C)
#  Padrão do arquivo: Projeto/k8s/saida/replicas-<ts>.csv (pasta gitignored). O contexto é SEMPRE explícito (kind-<K8S_NOME>).
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NOME="${K8S_NOME:-gerenciador}"; CTX="kind-$NOME"; NS=gerenciador
INTERVALO="${REGISTRAR_INTERVALO_S:-2}"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' ;; esac
PY=""; for c in python3 python; do "$c" -c 'import json' >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || { echo "preciso de python" >&2; exit 1; }
ARQ="${1:-$SCRIPT_DIR/saida/replicas-$(date +%Y%m%d-%H%M%S).csv}"
mkdir -p "$(dirname "$ARQ")"
echo "ts,servico,prontas,desejadas,metrica" >"$ARQ"
trap 'exit 0' TERM INT
while :; do
    ini=$SECONDS
    kubectl --context "$CTX" -n "$NS" get deploy,hpa -o json 2>/dev/null | "$PY" -c '
import json, sys, time
SERVICOS = ["backend", "identity", "catalog", "reservation", "stock", "notification", "admission"]
try:
    itens = json.load(sys.stdin)["items"]
except Exception:
    sys.exit(0)
prontas, hpa = {}, {}
for i in itens:
    nome = i["metadata"]["name"]
    if i["kind"] == "Deployment":
        prontas[nome] = i.get("status", {}).get("readyReplicas") or 0
    elif i["kind"] == "HorizontalPodAutoscaler" and nome.startswith("keda-hpa-"):
        st = i.get("status", {})
        m = ""
        for cm in st.get("currentMetrics") or []:
            cur = (cm.get("external") or cm.get("containerResource") or cm.get("resource") or {}).get("current") or {}
            m = str(cur.get("averageUtilization") or cur.get("averageValue") or cur.get("value") or "")
            break
        hpa[nome[len("keda-hpa-"):]] = (st.get("desiredReplicas", ""), m)
ts = int(time.time())
for s in SERVICOS:
    if s in prontas:
        d, m = hpa.get(s, ("", ""))
        print("%d,%s,%s,%s,%s" % (ts, s, prontas[s], d, m))
' >>"$ARQ"
    if [ "${REGISTRAR_ADM:-1}" = 1 ]; then
        v="$(kubectl --context "$CTX" -n "$NS" exec deploy/redis -- sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli GET adm:replicas' 2>/dev/null | tr -d '[:space:]')"
        echo "$(date +%s),adm-replicas,$v,," >>"$ARQ"
    fi
    resto=$((INTERVALO - (SECONDS - ini))); [ "$resto" -gt 0 ] && sleep "$resto"
done
