#!/bin/bash

# =============================================================================
#  cluster.sh  -  issue #197 (D1/D2): a stack inteira num cluster kind (um nó), por script
# =============================================================================
#
#  Sobe e derruba um cluster kind com a stack do compose (os 7 serviços Java, o frontend, Postgres + Supavisor, Redpanda, Redis e RabbitMQ), pelos
#  manifests de Projeto/k8s (kustomize embutido no kubectl). Os dados do cluster são DESCARTÁVEIS (somem no `down`); o `meu_banco` do compose não é tocado.
#
#  Subcomandos:
#    up --trilha b1|c|nenhuma [--so <app>]
#                                       cria o cluster (se não existe), carrega as imagens, gera Secrets/ConfigMaps e aplica os manifests; espera tudo
#                                       ficar pronto. `--so redpanda` aplica e espera só o `app=redpanda` (o e2e planta um evento antes do resto).
#                                       --trilha é OBRIGATÓRIO (não existe subida sem trilha por acidente): `b1` = malha de serviços com sidecar (Istio 1.31.1
#                                       instalado por helm: istio/base + istio/istiod), `c` = balanceamento no cliente (módulo Maven client-lb + Services headless),
#                                       `nenhuma` = a base pura (Service L4 do Kubernetes), o controle. Trocar de trilha = `down` + `up --trilha <outra>`.
#                                       Desde a etapa 4 o `up` instala (helm, versões de chart FIXAS, --wait) o metrics-server e o KEDA (todas as trilhas) e aplica os ScaledObjects da
#                                       autoescala (D5): reservation pelo adm:hot do admission, notification pelo lag, os demais por CPU. K8S_<SVC>_MIN/_MAX (default 1/3), K8S_DESCE_SEG (120),
#                                       K8S_CPU_ALVO (50) e AUTOESCALA_NOTIFICATION_LAG (100) viram o patch gerado; o alvo do reservation é 0,5 x o ADMISSION_X_RPM que o admission recebe.
#    guarda --trilha ...                só confere o isolamento do overlay e2e/carga (Secrets e NodePort) com a trilha somada; não cria nada
#    imagem <img...>                    carrega imagens avulsas no nó (o e2e usa para o pod auxiliar)
#    carregar <svc...>                  recarrega as imagens dadas (slim ou frontend) no nó e reinicia os Deployments (ciclo de desenvolvimento)
#    psql | rpk | redis-cli [args]      atalhos de `kubectl exec` (a senha vai pelo ambiente do pod, nunca no argv)
#    status                             nós, pods e serviços
#    down                               `kind delete cluster` (antes de qualquer medição no host: o cluster disputa CPU e RAM)
#
#  Variáveis: K8S_NOME (gerenciador | gerenciador-e2e*), K8S_OVERLAY (base | e2e | completa | carga), K8S_ESPERA (900, segundos de cada espera), SLIM_TAG (a das *.slim; resolvida
#  pelo compose como nos outros scripts: shell > Projeto/.env > latest).
#  Overlays: base (Secrets REAIS, só no cluster `gerenciador`), e2e (Mailpit, credenciais efêmeras, sem NodePort), completa (imagens completas gerenciador-eventos-<svc>:latest no lugar das slim;
#  Secrets REAIS, só em `gerenciador`; SLIM_TAG não vale; exige K8S_PERMITIR_LATEST=1), carga (= e2e + SEED_ENABLED=true no gateway, para o dry-run; Secrets do override do e2e, nunca o SMTP real).
#  SLIM_TAG=latest ABORTA (as :latest da R5 não têm o actuator): use SLIM_TAG=k8s até a R5 terminar; K8S_PERMITIR_LATEST=1 só depois que as :latest forem regeradas.
#  CONTEXTO EXPLÍCITO (E4): todo kubectl daqui passa --context kind-<nome>; nunca depende do current-context (o `kind create` o troca).
#  Segredos: os Secrets saem do `docker compose config` (o ambiente que o compose entrega a cada container) para arquivos temporários (mktemp, chmod 700,
#  apagados no fim) e de lá para o cluster, numa lista fechada de componentes (nunca Studio/Kong/meta). Nada é impresso nem versionado.
#  Pré-requisitos: docker (compose v2), kind e kubectl no PATH (o kind e o helm, este desde a etapa 4 em toda trilha (KEDA e metrics-server), em E:\TCC\claude-tooling\bin na máquina do TCC; o kubectl
#  vem com o Docker Desktop), python, Projeto/.env e Projeto/supabase/.env. Todo helm leva --kube-context kind-<nome> (E4), como o kubectl. Quem roda up/down/carregar segue o lock Docker do projeto (E:/TCC/.lock-docker).
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJETO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJETO_DIR" || exit 1

NOME="${K8S_NOME:-gerenciador}"
OVERLAY="${K8S_OVERLAY:-base}"
ESPERA="${K8S_ESPERA:-900}"
NS=gerenciador
CTX="kind-$NOME"
NODE="$NOME-control-plane"
SERVICOS="backend identity catalog reservation stock notification admission"
# lista FECHADA de componentes com Secret de ambiente (env-<componente>): nunca Studio, Kong nem meta (têm DASHBOARD_PASSWORD/SERVICE_ROLE_KEY)
COMPONENTES="db supabase-pooler redis rabbitmq $SERVICOS"
ISTIO_VERSAO=1.31.1                                    # release stable (2026-09-21), fixa; as 1.31.2/1.31.3 têm menos de duas semanas
ISTIO_REPO=https://blob.istio.io/istio-release/charts  # o istio-release.storage.googleapis.com não publica a série 1.31
KEDA_VERSAO=2.21.0                                     # chart kedacore/keda (appVersion 2.21.0), publicado em 2026-09-23, fixo
KEDA_REPO=https://kedacore.github.io/charts
METRICS_SERVER_VERSAO=3.14.0                           # chart kubernetes-sigs/metrics-server (appVersion 0.9.0), publicado em 2026-08-19, fixo
METRICS_SERVER_REPO=https://kubernetes-sigs.github.io/metrics-server
TRILHA=""
# serviços sob autoescala (KEDA): os 7 Java; o frontend e a infra ficam com 1 réplica fixa
AUTOESCALADOS="backend identity catalog reservation stock notification admission"

# Git Bash: o kubectl/docker/kind são nativos do Windows e a conversão de path do MSYS reescreveria "/bin/sh", "/actuator/..." nos argumentos
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'; WIN=1 ;; *) WIN=0 ;; esac
k() { kubectl --context "$CTX" "$@"; }
h() { helm --kube-context "$CTX" "$@"; }
kn() { kubectl --context "$CTX" -n "$NS" "$@"; }

ok()   { echo "   [ok]    $1"; }
abortar() { echo "   [ABORTA] $1" >&2; exit 1; }

# --- guardas de nome/overlay: Secrets reais NUNCA no cluster do e2e -------------------------------------------------------
case "$NOME" in
    gerenciador|gerenciador-e2e*) ;;
    *) abortar "K8S_NOME='$NOME' inválido: o nome é 'gerenciador' ou começa com 'gerenciador-e2e'." ;;
esac
IMG_COMPLETA=0
case "$OVERLAY" in
    base) case "$NOME" in gerenciador-e2e*) abortar "overlay 'base' leva os Secrets REAIS (Projeto/.env): não vai para o cluster '$NOME' (use K8S_OVERLAY=e2e)." ;; esac
          OVERLAY_DIR="k8s/base"; COMPOSE_E2E=0 ;;
    completa) case "$NOME" in gerenciador-e2e*) abortar "overlay 'completa' leva os Secrets REAIS (Projeto/.env): não vai para o cluster '$NOME' (use K8S_OVERLAY=e2e)." ;; esac
          OVERLAY_DIR="k8s/overlays/completa"; COMPOSE_E2E=0; IMG_COMPLETA=1 ;;
    e2e)  OVERLAY_DIR="k8s/overlays/e2e"; COMPOSE_E2E=1 ;;
    carga) OVERLAY_DIR="k8s/overlays/carga"; COMPOSE_E2E=1 ;; # e2e + SEED_ENABLED: Secrets do override do e2e (Mailpit, credenciais efêmeras), nunca o SMTP real (P2)
    *)    abortar "K8S_OVERLAY='$OVERLAY' inválido (base | e2e | completa | carga)." ;;
esac
KIND_CFG="k8s/kind.yaml"; case "$NOME" in gerenciador-e2e*) KIND_CFG="k8s/kind-e2e.yaml" ;; esac

TMP="$(mktemp -d)"; chmod 700 "$TMP" # os env-files com segredos do .env passam por aqui
trap 'rm -rf "$TMP"' EXIT
TMPW="$TMP"; [ "$WIN" = 1 ] && TMPW="$(cygpath -m "$TMP")"

SLIM_TAG="${SLIM_TAG:-}"
PY=""
for c in python3 python; do "$c" -c 'import json' >/dev/null 2>&1 && { PY="$c"; break; }; done

ferramentas() {
    local f
    for f in docker kind kubectl helm; do command -v "$f" >/dev/null 2>&1 || abortar "preciso de '$f' no PATH (kind e helm ficam em E:\\TCC\\claude-tooling\\bin na máquina do TCC; o helm instala o KEDA e o metrics-server em toda trilha)."; done
    [ -n "$PY" ] || abortar "preciso de python (python3) para filtrar o JSON do 'docker compose config'."
    [ -f .env ] && [ -f supabase/.env ] || abortar "faltam Projeto/.env e/ou Projeto/supabase/.env (ver supabase/README.md)."
}

compose() {
    local extra=(); [ "$COMPOSE_E2E" = 1 ] && extra=(-f e2e/docker-compose.e2e.yml)
    docker compose -f docker-compose.yml ${extra[@]+"${extra[@]}"} "$@"
}

resolver_tag() { # a tag das slim que o compose resolve (shell > .env > latest), como nos outros scripts
    if [ "$IMG_COMPLETA" = 1 ]; then # overlay completa: imagens gerenciador-eventos-<svc>:latest, SLIM_TAG não vale
        # as :latest das imagens completas podem ser as de uma rodada de medição em curso (R5): construir/sobrescrevê-las pede o mesmo cuidado das *.slim:latest
        [ "${K8S_PERMITIR_LATEST:-}" = 1 ] || abortar "overlay 'completa' usa gerenciador-eventos-<serviço>:latest (as imagens completas): só com K8S_PERMITIR_LATEST=1, depois que nenhuma rodada de medição depender delas."
        SLIM_TAG=""; return 0
    fi
    SLIM_TAG="$(compose config --images 2>/dev/null | sed -n 's/^gerenciador-eventos-backend\.slim://p' | tr -d '\r')"
    [ -n "$SLIM_TAG" ] || abortar "não consegui resolver a tag das slim (docker compose config --images não devolveu gerenciador-eventos-backend.slim:<tag>)."
    # As *.slim:latest da R5 não têm o actuator (probes da 8090): com elas os 5 serviços nunca ficam prontos e o `up` só falharia depois de K8S_ESPERA.
    # Até a R5 terminar, SLIM_TAG=k8s; K8S_PERMITIR_LATEST=1 é para depois, quando as :latest forem regeradas com o código desta issue.
    if [ "$SLIM_TAG" = latest ] && [ "${K8S_PERMITIR_LATEST:-}" != 1 ]; then
        abortar "a tag das slim resolvida é 'latest' (imagens da R5, SEM actuator). Até a R5 terminar use SLIM_TAG=k8s (gere com 'SLIM_TAG=k8s Projeto/slim/gerar-imagens-slim.sh'). Depois que as :latest forem regeradas, K8S_PERMITIR_LATEST=1 libera."
    fi
}

validar_trilha() { # $1 = valor de --trilha
    case "$1" in
        nenhuma|b1|c) TRILHA="$1" ;;
        "") abortar "falta --trilha: use --trilha b1 (Istio com sidecar), --trilha c (balanceamento no cliente) ou --trilha nenhuma (a base pura, o controle). Não existe subida sem trilha por acidente." ;;
        *) abortar "trilha '$1' inválida (b1 | c | nenhuma)." ;;
    esac
}

inteiro_entre() { # nome valor min max
    [[ "$2" =~ ^[0-9]+$ ]] && [ "$2" -ge "$3" ] && [ "$2" -le "$4" ] || abortar "$1='$2' inválido (inteiro de $3 a $4)."
}

# Monta, num kustomization TEMPORÁRIO (o kustomize não lê fora da própria pasta: copia base e overlays, só manifests, poucos KB), a seleção que o `up` aplica:
# o overlay (base | e2e | completa | carga) + o componente da trilha (b1 | c) + o PATCH GERADO da autoescala (K8S_<SVC>_MIN/_MAX, K8S_DESCE_SEG, K8S_CPU_ALVO, o alvo do
# reservation e o lagThreshold do notification). Sempre montado (também em --trilha nenhuma): o `guardar` renderiza exatamente o que será aplicado.
montar_selecao() {
    local s S vmin vmax min max desce cpu lag lagalvo alvo resmax=3 patches="" componentes=""
    desce="${K8S_DESCE_SEG:-120}"; inteiro_entre K8S_DESCE_SEG "$desce" 0 3600
    cpu="${K8S_CPU_ALVO:-50}"; inteiro_entre K8S_CPU_ALVO "$cpu" 1 100
    lag="${AUTOESCALA_NOTIFICATION_LAG:-100}"; inteiro_entre AUTOESCALA_NOTIFICATION_LAG "$lag" 2 1000000
    lagalvo=$((lag / 2)) # 0,5 x a referência de lag (regra 50/80 da autoescala)
    # o alvo do reservation sai da MESMA variável que o admission recebe (ADMISSION_X_RPM no ambiente do compose): gerar_env o grava em $TMP/adm-alvo.
    # As violações de isolamento são do `guardar`; aqui só se lê o alvo.
    gerar_env >/dev/null 2>&1 || true
    [ -s "$TMP/adm-alvo" ] || abortar "não consegui ler o ADMISSION_X_RPM do admission no 'docker compose config' (o alvo do reservation no KEDA é 0,5 x ele)."
    alvo="$(tr -d '[:space:]' <"$TMP/adm-alvo")"
    for s in $AUTOESCALADOS; do
        S="$(echo "$s" | tr 'a-z' 'A-Z')"; vmin="K8S_${S}_MIN"; vmax="K8S_${S}_MAX"
        min="${!vmin:-1}"; max="${!vmax:-3}"
        # teto 3: a conta do Supavisor (1492 <= 1500, spec "stock escalável") supõe no máximo 3 réplicas por serviço + 1 em término; acima disso, refazer a conta
        inteiro_entre "$vmin" "$min" 1 3; inteiro_entre "$vmax" "$max" 1 3
        [ "$min" -le "$max" ] || abortar "$vmin ($min) maior que $vmax ($max)."
        [ "$s" = reservation ] && resmax="$max"
        patches+="  - target: {kind: ScaledObject, name: $s}
    patch: |-
      - {op: replace, path: /spec/minReplicaCount, value: $min}
      - {op: replace, path: /spec/maxReplicaCount, value: $max}
      - {op: replace, path: /spec/advanced/horizontalPodAutoscalerConfig/behavior/scaleDown/stabilizationWindowSeconds, value: $desce}
"
        case "$s" in
            reservation)  patches+="      - {op: replace, path: /spec/triggers/0/metadata/targetValue, value: \"$alvo\"}
" ;;
            notification) patches+="      - {op: replace, path: /spec/triggers/0/metadata/lagThreshold, value: \"$lagalvo\"}
" ;;
            *)            patches+="      - {op: replace, path: /spec/triggers/0/metadata/value, value: \"$cpu\"}
" ;;
        esac
    done
    # o admission usa o MESMO maxReplicaCount do reservation como teto da leitura de readyReplicas (uma fonte só)
    patches+="  - target: {kind: Deployment, name: admission}
    patch: |-
      apiVersion: apps/v1
      kind: Deployment
      metadata: {name: admission}
      spec:
        template:
          spec:
            containers:
              - name: admission
                env:
                  - {name: K8S_RESERVATION_MAX, value: \"$resmax\"}
"
    [ "$TRILHA" = nenhuma ] || componentes="components:
  - ../overlays/trilha-$TRILHA
"
    mkdir -p "$TMP/k8s/sel" && cp -r "$SCRIPT_DIR/base" "$SCRIPT_DIR/overlays" "$TMP/k8s/" || abortar "montar o kustomization temporário da seleção falhou."
    cat > "$TMP/k8s/sel/kustomization.yaml" <<EOF
namespace: $NS
resources:
  - ../${OVERLAY_DIR#k8s/}
${componentes}patches:
${patches}
EOF
    OVERLAY_DIR="$TMPW/k8s/sel"
}

render() { # manifests do overlay, com a tag das slim aplicada (a base usa :latest)
    kubectl kustomize "$OVERLAY_DIR" | sed -E "s#(gerenciador-eventos-[a-z]+\.slim):latest#\1:$SLIM_TAG#"
}

# Gera um env-file por componente em $TMP (KEY=VALUE; nunca impresso) e, com o overlay e2e, confere o isolamento (P1): só imprime NOMES de violações.
gerar_env() {
    compose config --format json | "$PY" -c '
import json, os, sys
c = json.load(sys.stdin)
saida, e2e, componentes = sys.argv[1], sys.argv[2] == "1", sys.argv[3].split()
erros = []
# o supabase/.env INTEIRO chega ao `db` e ao `supabase-pooler` pelo env_file do compose (Studio/Kong/meta ficam FORA do cluster, e os segredos deles tambem).
# Lista de PERMITIDAS (fechada): so o que o entrypoint do db (POSTGRES_PASSWORD, JWT_SECRET, hosts/portas, APP_DB_*) e o Supavisor (pooler-upsert.exs, o `command`
# do manifest, SECRET_KEY_BASE, VAULT_ENC_KEY e os literais de supabase/docker-compose.pooler.yml) leem. Chave nova no supabase/.env NAO entra no cluster
# sem alguem acrescentar o nome aqui.
PERMITIDAS = {
    "db": {"POSTGRES_PASSWORD", "JWT_SECRET", "POSTGRES_HOST", "POSTGRES_PORT", "PGPORT", "POSTGRES_DB", "APP_DB_USER", "APP_DB_PASSWORD"},
    "supabase-pooler": {"POSTGRES_PASSWORD", "JWT_SECRET", "SECRET_KEY_BASE", "VAULT_ENC_KEY", "POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB",
                        "PORT", "CLUSTER_POSTGRES", "REGION", "ERL_AFLAGS", "POOLER_TENANT_ID", "POOLER_POOL_MODE", "POOLER_MAX_CLIENT_CONN",
                        "POOLER_DEFAULT_POOL_SIZE", "DB_POOL_SIZE"},
}
for nome in componentes:
    s = c["services"].get(nome)
    if s is None:
        erros.append("falta o servico " + nome); continue
    env = {k: v for k, v in (s.get("environment") or {}).items() if nome not in PERMITIDAS or k in PERMITIDAS[nome]}
    with open(os.path.join(saida, "env-" + nome), "w", newline="\n") as f:
        for k, v in sorted(env.items()):
            if v is None:
                continue
            if "\n" in str(v):
                print("   aviso: env-%s: %s descartada (valor com quebra de linha)" % (nome, k)); continue
            f.write("%s=%s\n" % (k, v))
# alvo do reservation no KEDA (D5): 0,5 x o ADMISSION_X_RPM que o admission RECEBE (a MESMA variável: o dry-run reduz as duas juntas). Não é segredo.
adm_env = c["services"]["admission"].get("environment") or {}
try:
    x_rpm = float(adm_env.get("ADMISSION_X_RPM") or 56000)
except ValueError:
    x_rpm = 0.0
if x_rpm <= 0:
    erros.append("admission: ADMISSION_X_RPM=%s invalido (numero > 0)" % adm_env.get("ADMISSION_X_RPM"))
else:
    alvo = x_rpm * 0.5
    with open(os.path.join(saida, "adm-alvo"), "w", newline="\n") as f:
        f.write(str(int(alvo)) if alvo == int(alvo) else repr(alvo))
if e2e:
    env = c["services"]["notification"].get("environment") or {}
    if env.get("MAIL_HOST") != "mailpit" or env.get("MAIL_PORT") != "1025" or env.get("MAIL_SMTP_AUTH") != "false" \
            or env.get("MAIL_STARTTLS") != "false" or env.get("MAIL_PASSWORD") or not str(env.get("MAIL_USERNAME") or "").endswith("@example.com"):
        erros.append("notification: SMTP nao aponta para o captador mailpit:1025 sem auth/starttls/senha (remetente @example.com)")
    adm = c["services"]["admission"].get("environment") or {}
    if not adm.get("REDIS_PASSWORD") or not adm.get("RABBITMQ_USER") or not adm.get("RABBITMQ_PASSWORD"):
        erros.append("admission: faltam as credenciais efemeras do Redis/RabbitMQ (o e2e as exporta antes)")
    for nome in ("backend",):
        env = c["services"][nome].get("environment") or {}
        if env.get("REDIS_PASSWORD") or env.get("RABBITMQ_USER") or env.get("RABBITMQ_PASSWORD"):
            erros.append(nome + ": recebeu credenciais do Redis/RabbitMQ (so do admission)")
    try:
        x = float(adm.get("ADMISSION_X_RPM") or 0)
    except ValueError:
        x = 0
    if not 500 <= x <= 5000:
        erros.append("admission: ADMISSION_X_RPM=%s fora de 500..5000 (o e2e precisa de X baixo e acima de 500)" % adm.get("ADMISSION_X_RPM"))
for e in erros:
    print("   " + e)
sys.exit(1 if erros else 0)
' "$TMPW" "$COMPOSE_E2E" "$COMPONENTES"
}

guardar() { # isolamento do overlay e2e: Secrets (gerar_env) e NodePort; qualquer falha aborta antes de criar/aplicar qualquer coisa
    local viol
    viol="$(gerar_env 2>&1)" || { echo "$viol"; abortar "o isolamento NÃO pode ser garantido (Secrets): nada foi criado."; }
    if [ "$COMPOSE_E2E" = 1 ]; then
        if kubectl kustomize "$OVERLAY_DIR" | grep -qiE 'nodePort|type: NodePort|type: LoadBalancer'; then
            abortar "o overlay e2e tem NodePort/LoadBalancer (porta no host): nada foi criado."
        fi
        ok "Secrets do overlay e2e: notification -> mailpit:1025 sem auth/senha, credenciais efêmeras no admission, X baixo; manifests sem NodePort"
    fi
}

imagens_do_cluster() { render | sed -n 's/^ *\(- \)\{0,1\}image: *//p' | tr -d '"' | sort -u; }

carregar_no_no() { # imagem: no Docker Desktop (image store containerd) `kind load` falha ("content digest ... not found") com imagem de registro multi-plataforma
    local img="$1" arch
    case "$img" in
        gerenciador-eventos-*) # imagens do projeto (plataforma unica, construidas aqui): o caminho normal do kind
            kind load docker-image --name "$NOME" "$img" >/dev/null 2>"$TMP/load.err" || { cat "$TMP/load.err" >&2; return 1; } ;;
        *) # imagens de registro: so a plataforma do no, importada direto no containerd do no
            case "$(docker exec "$NODE" uname -m | tr -d '[:space:]')" in aarch64|arm64) arch=arm64 ;; *) arch=amd64 ;; esac
            docker save --platform "linux/$arch" "$img" | docker exec -i --privileged "$NODE" ctr --namespace=k8s.io images import --digests --snapshotter=overlayfs - >/dev/null 2>"$TMP/load.err"                 || { cat "$TMP/load.err" >&2; return 1; } ;;
    esac
}

carregar_imagens() { # carrega no nó o que existe no Docker local; imagem de infra ausente o nó baixa do registro (IfNotPresent); as do projeto são obrigatórias
    local img faltando=0 n=0
    for img in "$@"; do
        docker image inspect "$img" >/dev/null 2>&1 && continue
        case "$img" in
            gerenciador-eventos-*) echo "   [ERRO]  falta a imagem local $img (slim: Projeto/slim/gerar-imagens-slim.sh, com SLIM_TAG=$SLIM_TAG se for outra tag; frontend: docker compose -f Projeto/docker-compose.yml build frontend)"; faltando=1 ;;
            *) echo "   (imagem $img ausente no Docker local: o nó a baixa do registro)" ;;
        esac
    done
    [ "$faltando" = 0 ] || abortar "faltam imagens do projeto."
    for img in "$@"; do
        docker image inspect "$img" >/dev/null 2>&1 || continue
        carregar_no_no "$img" || abortar "carregar a imagem $img no nó falhou."
        n=$((n + 1))
    done
    ok "$n imagem(ns) carregada(s) no nó $NODE"
}

instalar_istio() { # trilha b1: istio/base + istio/istiod 1.31.1 por helm (versão fixa, sem istioctl); idempotente (upgrade --install)
    helm_istio istio-base base --create-namespace || abortar "helm istio/base $ISTIO_VERSAO falhou."
    helm_istio istiod istiod -f k8s/istio/istiod-values.yaml || abortar "helm istio/istiod $ISTIO_VERSAO falhou (kubectl --context $CTX -n istio-system get pods)."
    ok "Istio $ISTIO_VERSAO (istio/base + istio/istiod, 1 réplica do istiod) instalado por helm"
}

helm_chart() { # release chart repo versão namespace [args...]: até 3 tentativas (o repositório do chart é uma busca de rede); na falha imprime a saída do helm (charts sem segredo)
    local rel="$1" chart="$2" repo="$3" ver="$4" ns="$5" i; shift 5
    for i in 1 2 3; do
        h upgrade --install "$rel" "$chart" --repo "$repo" --version "$ver" -n "$ns" "$@" --wait --timeout "${ESPERA}s" >"$TMP/helm.out" 2>&1 && return 0
        echo "   (helm $rel, tentativa $i de 3 falhou)" >&2; tail -5 "$TMP/helm.out" >&2; sleep 5
    done
    return 1
}

helm_istio() { # release chart [args...]
    local rel="$1" chart="$2"; shift 2
    helm_chart "$rel" "$chart" "$ISTIO_REPO" "$ISTIO_VERSAO" istio-system "$@"
}

instalar_autoescala() { # metrics-server + KEDA por helm (versões de chart FIXAS), em toda trilha; precisam existir ANTES do apply (CRDs dos ScaledObjects, métricas do HPA)
    helm_chart metrics-server metrics-server "$METRICS_SERVER_REPO" "$METRICS_SERVER_VERSAO" kube-system -f k8s/helm/metrics-server-values.yaml || abortar "helm metrics-server $METRICS_SERVER_VERSAO falhou (kubectl --context $CTX -n kube-system get pods)."
    helm_chart keda keda "$KEDA_REPO" "$KEDA_VERSAO" keda --create-namespace -f k8s/helm/keda-values.yaml || abortar "helm keda $KEDA_VERSAO falhou (kubectl --context $CTX -n keda get pods)."
    local api
    for api in v1beta1.metrics.k8s.io v1beta1.external.metrics.k8s.io; do # o HPA só lê métrica com as APIs agregadas disponíveis
        k wait --for=condition=Available "apiservice/$api" --timeout="${ESPERA}s" >/dev/null || abortar "o APIService $api não ficou disponível (metrics-server/KEDA)."
    done
    ok "metrics-server $METRICS_SERVER_VERSAO e KEDA $KEDA_VERSAO (KEDA_RESTRICT_SECRET_ACCESS=true) instalados por helm"
}

autoescala_segredos() { # o token SÓ de métrica (P3), a ClusterTriggerAuthentication e o Service do Redpanda visível ao namespace do KEDA. Nada disto passa pelo kustomize (o transformador de
    # namespace tiraria o Secret do `keda`). Gerado FORA do .env, nunca impresso. Reaproveita o valor se o Secret já existe neste cluster (um `up` repetido não pode deixar o admission e o
    # KEDA com valores diferentes: 401 e o reservation congelado); cluster novo = token novo.
    local tf="$TMP/metrics-token" tfw="$TMPW/metrics-token"
    if k -n keda get secret admission-metrics >/dev/null 2>&1; then
        k -n keda get secret admission-metrics -o jsonpath='{.data.apiKey}' | base64 -d >"$tf"
    else
        "$PY" -c 'import secrets, sys; sys.stdout.write(secrets.token_hex(32))' >"$tf"
    fi
    [ -s "$tf" ] || abortar "não consegui gerar/ler o token de métrica."
    k -n keda create secret generic admission-metrics --from-file=apiKey="$tfw" --dry-run=client -o yaml | k apply -f - >/dev/null || abortar "secret admission-metrics (keda)."
    kn create secret generic admission-metrics --from-file=token="$tfw" --dry-run=client -o yaml | kn apply -f - >/dev/null || abortar "secret admission-metrics ($NS)."
    k apply -f - >/dev/null <<EOF || abortar "ClusterTriggerAuthentication / Service redpanda do namespace keda."
apiVersion: keda.sh/v1alpha1
kind: ClusterTriggerAuthentication
metadata:
  name: admission-metrics
spec:
  secretTargetRef:
    - parameter: apiKey
      name: admission-metrics
      key: apiKey
---
# o broker anuncia redpanda:9092 (nome curto): o scaler kafka, que roda no namespace keda, só o resolve com este alias para o Service do namespace $NS
apiVersion: v1
kind: Service
metadata:
  name: redpanda
  namespace: keda
spec:
  type: ExternalName
  externalName: redpanda.$NS.svc.cluster.local
EOF
    ok "token de métrica (Secret keda/admission-metrics e $NS/admission-metrics), ClusterTriggerAuthentication e alias redpanda no namespace keda"
}

guardar_trilha() { # logo depois do cluster existir: o namespace leva a trilha do `up`; um `up --trilha X` sobre um cluster de OUTRA trilha aborta (misturaria as duas em silêncio)
    if k get namespace "$NS" >/dev/null 2>&1; then
        local atual
        atual="$(k get namespace "$NS" -o jsonpath="{.metadata.labels['trilha-balanceamento']}")"
        [ -n "$atual" ] || abortar "o namespace $NS do cluster $NOME existe SEM o label trilha-balanceamento (cluster criado fora do cluster.sh ou antes da guarda): faça 'cluster.sh down' e suba de novo com --trilha $TRILHA."
        [ "$atual" = "$TRILHA" ] \
            || abortar "o cluster $NOME já subiu com a trilha '$atual' (kubectl --context $CTX get ns $NS --show-labels): misturar trilhas invalida a comparação. Faça 'cluster.sh down' e suba de novo com --trilha $TRILHA."
    else
        k create namespace "$NS" >/dev/null && k label namespace "$NS" "trilha-balanceamento=$TRILHA" >/dev/null || abortar "criar o namespace $NS com o label da trilha falhou."
    fi
}

criar_cluster() {
    if kind get clusters 2>/dev/null | tr -d '\r' | grep -qx "$NOME"; then ok "cluster $NOME já existe"
    else
        kind create cluster --name "$NOME" --config "$KIND_CFG" --wait 180s >/dev/null 2>"$TMP/kind.err" || { cat "$TMP/kind.err" >&2; abortar "kind create cluster falhou."; }
        ok "cluster $NOME criado (contexto $CTX)"
    fi
    k get nodes >/dev/null 2>&1 || abortar "o contexto $CTX não responde."
}

copiar_seccomp() { # perfis do Slim + k8s/seccomp-extras.txt (syscalls que o runc e o JVM usam e o probe do Slim não viu) -> diretório de seccomp do kubelet
    # (localhostProfile: profiles/<svc>-seccomp.json). O perfil cru do Slim não deixa nem o container iniciar (ver o cabeçalho do seccomp-extras.txt).
    docker exec "$NODE" mkdir -p /var/lib/kubelet/seccomp/profiles || abortar "mkdir no nó falhou."
    local s
    for s in $SERVICOS; do
        "$PY" -c '
import json, sys
perfil, extras, saida = sys.argv[1:4]
d = json.load(open(perfil))
extra = [l.split("#")[0].strip() for l in open(extras)]
permitidas = set(d["syscalls"][0]["names"]) | {e for e in extra if e}
d["syscalls"][0]["names"] = sorted(permitidas)
json.dump(d, open(saida, "w"))
' "slim/perfis/$s-seccomp.json" k8s/seccomp-extras.txt "$TMPW/$s-seccomp.json" || abortar "montar o perfil Seccomp de $s falhou."
        docker cp "$TMPW/$s-seccomp.json" "$NODE:/var/lib/kubelet/seccomp/profiles/$s-seccomp.json" >/dev/null || abortar "docker cp do perfil de $s falhou."
    done
    ok "perfis Seccomp dos 7 serviços (Slim + seccomp-extras.txt) copiados para o nó"
}

configmaps_e_segredos() {
    k create namespace "$NS" --dry-run=client -o yaml | k apply -f - >/dev/null || abortar "namespace $NS."
    # arquivos montados pelo compose, dos MESMOS arquivos do repo (o kustomize não lê fora da própria pasta)
    kn create configmap db-sql --dry-run=client -o yaml \
        --from-file=functions-role.sql=supabase/volumes/db/functions-role.sql --from-file=_supabase.sql=supabase/volumes/db/_supabase.sql \
        --from-file=pooler.sql=supabase/volumes/db/pooler.sql --from-file=roles.sql=supabase/volumes/db/roles.sql \
        --from-file=supabase-app-init.sql=Backend/db/supabase-app-init.sql | kn apply -f - >/dev/null || abortar "configmap db-sql."
    kn create configmap db-app-init --from-file=Backend/db/init-scripts --dry-run=client -o yaml | kn apply -f - >/dev/null || abortar "configmap db-app-init."
    kn create configmap pooler-exs --from-file=pooler-upsert.exs=supabase/volumes/pooler/pooler-upsert.exs --dry-run=client -o yaml | kn apply -f - >/dev/null || abortar "configmap pooler-exs."
    gerar_env || abortar "gerar os env-files falhou."
    local c n
    for c in $COMPONENTES; do
        kn create secret generic "env-$c" --from-env-file="$TMPW/env-$c" --dry-run=client -o yaml | kn apply -f - >/dev/null || abortar "secret env-$c."
        n="$(grep -c '' "$TMP/env-$c")"
        echo "   secret env-$c: $n variáveis"
    done
    ok "ConfigMaps e Secrets aplicados no namespace $NS"
}

esperar() { # [app]: sem argumento espera tudo
    local alvo="${1:-}" t="${ESPERA}s" r
    if [ -n "$alvo" ]; then
        for r in "sts/$alvo" "deploy/$alvo"; do
            kn get "$r" >/dev/null 2>&1 && { kn rollout status "$r" --timeout="$t" || abortar "$r não ficou pronto em $t (kubectl --context $CTX -n $NS describe/logs)."; return 0; }
        done
        abortar "app '$alvo' não encontrado."
    fi
    for r in sts/db sts/redpanda deploy/supabase-pooler deploy/redis deploy/rabbitmq deploy/identity deploy/catalog deploy/stock deploy/reservation deploy/admission deploy/backend deploy/notification deploy/frontend; do
        kn rollout status "$r" --timeout="$t" || { kn get pods; abortar "$r não ficou pronto em $t (kubectl --context $CTX -n $NS describe/logs)."; }
    done
    kn get deploy mailpit >/dev/null 2>&1 && { kn rollout status deploy/mailpit --timeout="$t" || abortar "mailpit não ficou pronto."; }
    kn wait --for=condition=complete job/particoes --timeout="$t" || abortar "o Job particoes não terminou em $t."
    kn wait --for=condition=Ready scaledobject --all --timeout="$t" || { kn get scaledobject,hpa; abortar "os ScaledObjects não ficaram Ready em $t (kubectl --context $CTX -n $NS describe scaledobject; logs do keda-operator no namespace keda)."; }
    ok "tudo pronto (Deployments, StatefulSets, o Job particoes e os ScaledObjects Ready)"
}

cmd_up() {
    local trilha="" so=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --trilha) trilha="${2:-}"; shift 2 ;;
            --so) so="${2:-}"; shift 2 ;;
            *) abortar "argumento desconhecido: $1" ;;
        esac
    done
    validar_trilha "$trilha"
    ferramentas
    montar_selecao
    echo "==> up: cluster $NOME (contexto $CTX), overlay $OVERLAY, trilha $trilha${so:+, só app=$so}"
    guardar
    resolver_tag; ok "tag das slim: ${SLIM_TAG:-n/a (overlay completa)} (a que o compose resolve: shell > Projeto/.env > latest)"
    criar_cluster
    guardar_trilha
    copiar_seccomp
    local imagens=(); mapfile -t imagens < <(imagens_do_cluster | tr -d '\r')
    carregar_imagens "${imagens[@]}"
    instalar_autoescala # também com `--so`: o `kubectl apply -l` mapeia TODOS os recursos do render (sem o CRD dos ScaledObjects falha); o `up` completo seguinte só repete o `helm upgrade --install`
    [ "$TRILHA" = b1 ] && instalar_istio
    configmaps_e_segredos
    autoescala_segredos # também com `--so`: o admission (secretKeyRef não opcional) e o ScaledObject do reservation precisam dos Secrets; idempotente (reaproveita o token)
    # b1: o namespace passa a injetar o sidecar (db, Supavisor, Redpanda, Redis, RabbitMQ, Mailpit e o Job particoes têm inject=false nos manifests da trilha)
    [ "$TRILHA" = b1 ] && { k label namespace "$NS" istio-injection=enabled --overwrite >/dev/null || abortar "label istio-injection no namespace $NS falhou."; ok "namespace $NS com istio-injection=enabled"; }
    if [ -n "$so" ]; then render | kn apply -l "app=$so" -f - || abortar "kubectl apply -l app=$so falhou."
    else render | kn apply -f - || abortar "kubectl apply falhou."; fi
    esperar "$so"
}

cmd_carregar() {
    [ $# -gt 0 ] || abortar "uso: cluster.sh carregar <serviço...> (de: $SERVICOS frontend)"
    ferramentas; resolver_tag
    local s imagens=()
    for s in "$@"; do
        case "$s" in
            frontend) imagens+=("gerenciador-eventos-frontend:latest") ;;
            backend|identity|catalog|reservation|stock|notification|admission)
                if [ "$IMG_COMPLETA" = 1 ]; then imagens+=("gerenciador-eventos-$s:latest"); else imagens+=("gerenciador-eventos-$s.slim:$SLIM_TAG"); fi ;;
            *) abortar "serviço desconhecido: $s" ;;
        esac
    done
    carregar_imagens "${imagens[@]}"
    for s in "$@"; do kn rollout restart "deploy/$s" >/dev/null && ok "deploy/$s reiniciado"; done
    for s in "$@"; do kn rollout status "deploy/$s" --timeout="${ESPERA}s" || abortar "deploy/$s não ficou pronto."; done
}

cmd="${1:-}"; [ $# -gt 0 ] && shift
case "$cmd" in
    up) cmd_up "$@" ;;
    guarda) [ "${1:-}" = --trilha ] || abortar "uso: cluster.sh guarda --trilha b1|c|nenhuma"; validar_trilha "${2:-}"; ferramentas; montar_selecao; guardar ;;
    carregar) cmd_carregar "$@" ;;
    imagem) ferramentas; carregar_imagens "$@" ;; # imagens avulsas (o e2e carrega a do pod auxiliar de curl)
    psql) kn exec -i sts/db -- sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$POSTGRES_DB" "$@"' sh "$@" ;;
    rpk) kn exec -i sts/redpanda -- rpk "$@" ;;
    redis-cli) kn exec -i deploy/redis -- sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli "$@"' sh "$@" ;;
    status) k get nodes; kn get pods,svc,pvc -o wide; kn get scaledobject,hpa ;;
    down) kind get clusters 2>/dev/null | tr -d '\r' | grep -qx "$NOME" && kind delete cluster --name "$NOME" || echo "(cluster $NOME não existe)" ;;
    *) awk 'NR >= 6 && /^# =+$/ {exit} NR >= 6' "$SCRIPT_DIR/cluster.sh"; exit 1 ;;
esac
