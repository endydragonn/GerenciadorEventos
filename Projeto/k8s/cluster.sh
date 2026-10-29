#!/bin/bash

# =============================================================================
#  cluster.sh  -  issue #197 (D1/D2): a stack inteira num cluster kind (um nó), por script
# =============================================================================
#
#  Sobe e derruba um cluster kind com a stack do compose (os 7 serviços Java, o frontend, Postgres + Supavisor, Redpanda, Redis e RabbitMQ), pelos
#  manifests de Projeto/k8s (kustomize embutido no kubectl). Os dados do cluster são DESCARTÁVEIS (somem no `down`); o `meu_banco` do compose não é tocado.
#
#  Subcomandos:
#    up --trilha nenhuma [--so <app>]   cria o cluster (se não existe), carrega as imagens, gera Secrets/ConfigMaps e aplica os manifests; espera tudo
#                                       ficar pronto. `--so redpanda` aplica e espera só o `app=redpanda` (o e2e planta um evento antes do resto).
#                                       As trilhas de balanceamento (b1, c) chegam na etapa 3: aqui só `--trilha nenhuma` (a base pura, o controle).
#    guarda                             só confere o isolamento do overlay e2e (Secrets e NodePort); não cria nada
#    imagem <img...>                    carrega imagens avulsas no nó (o e2e usa para o pod auxiliar)
#    carregar <svc...>                  recarrega as imagens dadas (slim ou frontend) no nó e reinicia os Deployments (ciclo de desenvolvimento)
#    psql | rpk | redis-cli [args]      atalhos de `kubectl exec` (a senha vai pelo ambiente do pod, nunca no argv)
#    status                             nós, pods e serviços
#    down                               `kind delete cluster` (antes de qualquer medição no host: o cluster disputa CPU e RAM)
#
#  Variáveis: K8S_NOME (gerenciador | gerenciador-e2e*), K8S_OVERLAY (base | e2e), K8S_ESPERA (900, segundos de cada espera), SLIM_TAG (a das *.slim; resolvida
#  pelo compose como nos outros scripts: shell > Projeto/.env > latest).
#  SLIM_TAG=latest ABORTA (as :latest da R5 não têm o actuator): use SLIM_TAG=k8s até a R5 terminar; K8S_PERMITIR_LATEST=1 só depois que as :latest forem regeradas.
#  CONTEXTO EXPLÍCITO (E4): todo kubectl daqui passa --context kind-<nome>; nunca depende do current-context (o `kind create` o troca).
#  Segredos: os Secrets saem do `docker compose config` (o ambiente que o compose entrega a cada container) para arquivos temporários (mktemp, chmod 700,
#  apagados no fim) e de lá para o cluster, numa lista fechada de componentes (nunca Studio/Kong/meta). Nada é impresso nem versionado.
#  Pré-requisitos: docker (compose v2), kind e kubectl no PATH (o kind em E:\TCC\claude-tooling\bin na máquina do TCC; o kubectl vem com o Docker
#  Desktop), python, Projeto/.env e Projeto/supabase/.env. Quem roda up/down/carregar segue o lock Docker do projeto (E:/TCC/.lock-docker).
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

# Git Bash: o kubectl/docker/kind são nativos do Windows e a conversão de path do MSYS reescreveria "/bin/sh", "/actuator/..." nos argumentos
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'; WIN=1 ;; *) WIN=0 ;; esac
k() { kubectl --context "$CTX" "$@"; }
kn() { kubectl --context "$CTX" -n "$NS" "$@"; }

ok()   { echo "   [ok]    $1"; }
abortar() { echo "   [ABORTA] $1" >&2; exit 1; }

# --- guardas de nome/overlay: Secrets reais NUNCA no cluster do e2e -------------------------------------------------------
case "$NOME" in
    gerenciador|gerenciador-e2e*) ;;
    *) abortar "K8S_NOME='$NOME' inválido: o nome é 'gerenciador' ou começa com 'gerenciador-e2e'." ;;
esac
case "$OVERLAY" in
    base) case "$NOME" in gerenciador-e2e*) abortar "overlay 'base' leva os Secrets REAIS (Projeto/.env): não vai para o cluster '$NOME' (use K8S_OVERLAY=e2e)." ;; esac
          OVERLAY_DIR="k8s/base"; COMPOSE_E2E=0 ;;
    e2e)  OVERLAY_DIR="k8s/overlays/e2e"; COMPOSE_E2E=1 ;;
    *)    abortar "K8S_OVERLAY='$OVERLAY' inválido (base | e2e)." ;;
esac
KIND_CFG="k8s/kind.yaml"; case "$NOME" in gerenciador-e2e*) KIND_CFG="k8s/kind-e2e.yaml" ;; esac

TMP="$(mktemp -d)"; chmod 700 "$TMP" # os env-files com segredos do .env passam por aqui
trap 'rm -rf "$TMP"' EXIT
TMPW="$TMP"; [ "$WIN" = 1 ] && TMPW="$(cygpath -m "$TMP")"

SLIM_TAG="${SLIM_TAG:-}"
PY=""
for c in python3 python; do "$c" -c 'import json' >/dev/null 2>&1 && { PY="$c"; break; }; done

ferramentas() {
    for f in docker kind kubectl; do command -v "$f" >/dev/null 2>&1 || abortar "preciso de '$f' no PATH (kind e helm ficam em E:\\TCC\\claude-tooling\\bin na máquina do TCC)."; done
    [ -n "$PY" ] || abortar "preciso de python (python3) para filtrar o JSON do 'docker compose config'."
    [ -f .env ] && [ -f supabase/.env ] || abortar "faltam Projeto/.env e/ou Projeto/supabase/.env (ver supabase/README.md)."
}

compose() {
    local extra=(); [ "$COMPOSE_E2E" = 1 ] && extra=(-f e2e/docker-compose.e2e.yml)
    docker compose -f docker-compose.yml ${extra[@]+"${extra[@]}"} "$@"
}

resolver_tag() { # a tag das slim que o compose resolve (shell > .env > latest), como nos outros scripts
    SLIM_TAG="$(compose config --images 2>/dev/null | sed -n 's/^gerenciador-eventos-backend\.slim://p' | tr -d '\r')"
    [ -n "$SLIM_TAG" ] || abortar "não consegui resolver a tag das slim (docker compose config --images não devolveu gerenciador-eventos-backend.slim:<tag>)."
    # As *.slim:latest da R5 não têm o actuator (probes da 8090): com elas os 5 serviços nunca ficam prontos e o `up` só falharia depois de K8S_ESPERA.
    # Até a R5 terminar, SLIM_TAG=k8s; K8S_PERMITIR_LATEST=1 é para depois, quando as :latest forem regeradas com o código desta issue.
    if [ "$SLIM_TAG" = latest ] && [ "${K8S_PERMITIR_LATEST:-}" != 1 ]; then
        abortar "a tag das slim resolvida é 'latest' (imagens da R5, SEM actuator). Até a R5 terminar use SLIM_TAG=k8s (gere com 'SLIM_TAG=k8s Projeto/slim/gerar-imagens-slim.sh'). Depois que as :latest forem regeradas, K8S_PERMITIR_LATEST=1 libera."
    fi
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
    ok "tudo pronto (Deployments, StatefulSets e o Job particoes)"
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
    case "$trilha" in
        nenhuma) ;;
        b1|c) abortar "a trilha '$trilha' chega na etapa 3 (issue #197); por ora só --trilha nenhuma." ;;
        "") abortar "falta --trilha: use 'up --trilha nenhuma' (as trilhas b1 e c chegam na etapa 3; não existe subida sem trilha por acidente)." ;;
        *) abortar "trilha '$trilha' inválida (nenhuma | b1 | c)." ;;
    esac
    ferramentas
    echo "==> up: cluster $NOME (contexto $CTX), overlay $OVERLAY, trilha $trilha${so:+, só app=$so}"
    guardar
    resolver_tag; ok "tag das slim: $SLIM_TAG (a que o compose resolve: shell > Projeto/.env > latest)"
    criar_cluster
    copiar_seccomp
    local imagens=(); mapfile -t imagens < <(imagens_do_cluster | tr -d '\r')
    carregar_imagens "${imagens[@]}"
    configmaps_e_segredos
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
            backend|identity|catalog|reservation|stock|notification|admission) imagens+=("gerenciador-eventos-$s.slim:$SLIM_TAG") ;;
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
    guarda) ferramentas; guardar ;;
    carregar) cmd_carregar "$@" ;;
    imagem) ferramentas; carregar_imagens "$@" ;; # imagens avulsas (o e2e carrega a do pod auxiliar de curl)
    psql) kn exec -i sts/db -- sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$POSTGRES_DB" "$@"' sh "$@" ;;
    rpk) kn exec -i sts/redpanda -- rpk "$@" ;;
    redis-cli) kn exec -i deploy/redis -- sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli "$@"' sh "$@" ;;
    status) k get nodes; kn get pods,svc,pvc -o wide ;;
    down) kind get clusters 2>/dev/null | tr -d '\r' | grep -qx "$NOME" && kind delete cluster --name "$NOME" || echo "(cluster $NOME não existe)" ;;
    *) sed -n '5,23p' "$0"; exit 1 ;;
esac
