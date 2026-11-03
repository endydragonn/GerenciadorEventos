#!/bin/bash

# =============================================================================
#  run-e2e.sh  -  issue #37 (Fase 7): suite de integracao da stack inteira
# =============================================================================
#
#  Sobe a stack COMPLETA (gateway/backend, identity, catalog, reservation, stock, admission, redis, rabbitmq, notification, frontend, supabase-*, redpanda) num
#  project docker compose TEMPORARIO e isolado da stack real (gerenciador-eventos), com um captador de e-mail (Mailpit) no
#  lugar do Gmail, e prova o fluxo ponta a ponta pelo gateway:
#    cadastro em duas fases (o codigo vem do E-MAIL no captador) -> criar evento -> reservar -> confirmar -> e-mail
#    + saga stock + wallet (#47): (a) stock fora (500 generico, saga compensating, o reaper compensa quando ele volta, a mesma chave reserva) e
#    (b) queda do reservation no meio de uma rajada (invariante e overselling 0 depois da janela do reaper; ORPHAN_MS/SCAN_MS curtos no project)
#    + replay (event sourcing) de catalog.event e reservation.wallet contra o banco do project: sombra == projecao viva.
#  Sem porta no host: as chamadas HTTP saem de um container auxiliar na rede do project. No fim (verde ou nao) faz
#  `down -v` SO do project temporario, salvo MANTER=1.
#
#  ISOLAMENTO (conferido por `config` ANTES do `up`; se falhar, aborta sem subir nada): o override
#  e2e/docker-compose.e2e.yml remove container_name e portas de todos os servicos e tira o nome fixo gerenciador-net da rede
#  (senao `db`/`redpanda`/`supabase-pooler` resolveriam para os containers REAIS e o project escreveria no meu_banco de verdade).
#  O project precisa se chamar gerenciador-e2e*; gerenciador-eventos (o real) e recusado, e o `down -v` tambem confere.
#  E-MAIL: o script aborta se o notification nao estiver apontado para o captador. Nenhum e-mail real sai.
#  REDIS (#41): o compose exige REDIS_PASSWORD; o script exporta uma senha EFEMERA (openssl rand -hex 16) so para este project, entao
#  o Redis e o admission do e2e nunca usam a do Projeto/.env. O validador confere que redis e admission existem e estao isolados.
#  RABBITMQ (#42): idem, usuario e senha EFEMEROS (openssl rand -hex 16) exportados so para este project; o validador confere que o rabbitmq
#  existe, esta isolado (sem container_name nem porta no host) e que o admission aponta para ele; o backend nao recebe as credenciais.
#  Segredos: `config --format json` carrega os segredos do Projeto/.env; so passa por um filtro que imprime nomes de
#  container/porta/rede/volume. O Projeto/.env e o supabase/.env precisam existir (nada deles e impresso).
#
#  Pre-requisitos: docker (compose v2), python (so para filtrar o JSON do `config`), Projeto/.env e Projeto/supabase/.env.
#  A stack real pode estar no ar (portas e nomes nao colidem), mas o project sobe ~17 containers: pare a real se faltar RAM
#  (`docker compose -p gerenciador-eventos stop`, nunca `down`).
#
#  Uso:  Projeto/e2e/run-e2e.sh
#  Variaveis (opcionais): E2E_PROJECT (gerenciador-e2e), MANTER (1 = nao derruba o project), ESPERA_UP (900, segundos do --wait).
#  IMAGENS DOS SERVICOS (#197, D7): por padrao os 7 servicos Java (com o admission, #178) rodam as imagens ENXUTAS do Slim, que o docker-compose.yml ja
#  referencia (`gerenciador-eventos-<servico>.slim:${SLIM_TAG:-latest}`, sem build). Elas precisam existir (Projeto/slim/gerar-imagens-slim.sh): o script
#  confere antes do `up` e aborta se faltar alguma; depois do `up` confere que cada container roda o ID dessa imagem. SLIM_TAG (default latest; vale da
#  shell ou do Projeto/.env, como qualquer variavel do compose) escolhe outra tag, p.ex. para testar um lote novo sem tocar o `:latest`. O script NAO a define: pergunta
#  ao proprio compose (`config --images`) qual tag ele usa, mostra no log e confere essa.
#  E2E_COMPLETA=1 roda com as imagens COMPLETAS (docker-compose.completa.yml: `up --build` constroi os 7 pelo Dockerfile) e depois do `up` confere que
#  cada container roda o ID da imagem gorda construida no project (`<project>-<servico>:latest`).
#  E2E_K8S=1 (#197, D2): em vez do project compose, cria um cluster kind PROPRIO (`gerenciador-e2e`, sem porta no host: k8s/kind-e2e.yaml) e roda os MESMOS
#  passos contra a stack inteira nele (overlay k8s/overlays/e2e: Mailpit, Secrets do override e2e com credenciais efemeras; nada do SMTP real). Exige
#  E2E_K8S_TRILHA=nenhuma|c|b1 (a trilha de balanceamento, obrigatoria; b1 instala o Istio por helm e tambem exige o helm no PATH), kind e kubectl no PATH e as
#  *.slim:$SLIM_TAG. Todo kubectl/helm leva --context kind-<cluster>. O passo 7 (so no cluster) prova o balanceamento: uma replica NOVA do catalog recebe
#  conexao do gateway nas trilhas b1 e c e NAO recebe na nenhuma (o controle: o Service L4 mantem as conexoes do pool nos pods velhos).
#  Os helpers que fazem `docker exec`/`docker compose exec` passam por `sexec`; stock fora = `kubectl scale`, pausa do stock = SIGSTOP/SIGCONT no PID a partir do
#  no (crictl), queda do reservation = SIGKILL no PID pelo no (como o `docker stop -t 0`; o `kubectl delete pod --force` desmonta a rede do pod e nao deixa orfas). O isolamento (Secrets e NodePort) e conferido por `cluster.sh guarda`
#  ANTES de qualquer apply. No fim `kind delete cluster` (MANTER=1 mantem).
#  AUTOESCALA (#197, etapa 4): o cluster tem KEDA + metrics-server e os 6 ScaledObjects (o stock, #231, e 1 replica fixa sem ScaledObject). Para o roteiro ter UMA replica por servico (varios passos assumem um pod so), o e2e sobe com
#  K8S_<SVC>_MIN=MAX=1 (K8S_DESCE_SEG=5) e K8S_RESERVATION_MAX=2 (o teto que o admission aceita ler), e logo depois do `up` limita o ScaledObject do reservation a 1. Os passos que precisam de
#  2 replicas (7: catalog; 8: reservation) mexem no min/max do ScaledObject, nunca em `kubectl scale` (o HPA o desfaria); "stock fora" e um `kubectl scale` direto (o stock nao tem HPA que o revivesse). O passo 8 confere
#  ScaledObjects/HPAs Ready com metrica lida, `GET /internal/metrics/hot` (token de metrica: 200; sem token, token errado e token de metrica em outra rota: 401) e `adm:replicas` = readyReplicas do reservation (D4).
#  Sem carga pesada: o dry-run de carga (etapa 4b) e quem prova a subida e a descida sob carga.
#  Compatibilidade: Linux/macOS e Git Bash do Windows (Docker Desktop).
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJETO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJETO_DIR" || exit 1

PROJETO="${E2E_PROJECT:-gerenciador-e2e}"
MANTER="${MANTER:-0}"
ESPERA_UP="${ESPERA_UP:-900}"
REDE="${PROJETO}_default"
CLI="${PROJETO}-cli"
SUFIXO="$(date +%s)-$RANDOM"
# Rajada do modo fila (#43, passo 4b): usuarios concorrentes no evento da rajada (E2E_RAJADA_USUARIOS, default 20; com um valor alto o mesmo
# passo e o dry-run acima do limiar), capacidade do evento (default N/4, sempre menor que N) e o X do admission no project (override e2e).
RAJADA_USUARIOS="${E2E_RAJADA_USUARIOS:-20}"
RAJADA_CAPACIDADE="${E2E_RAJADA_CAPACIDADE:-$(( RAJADA_USUARIOS / 4 > 0 ? RAJADA_USUARIOS / 4 : 1 ))}"
ADMISSION_X_RPM="${E2E_ADMISSION_X_RPM:-600}"
export ADMISSION_X_RPM
# Janela do reaper da saga (#47) no project: orfa depois de ORPHAN_MS, varredura a cada SCAN_MS (o override e2e le estas duas variaveis).
SAGA_ORPHAN_MS="${E2E_SAGA_ORPHAN_MS:-5000}"
SAGA_SCAN_MS="${E2E_SAGA_SCAN_MS:-1000}"
export SAGA_ORPHAN_MS SAGA_SCAN_MS
SAGA_JANELA_S=$(( (SAGA_ORPHAN_MS + SAGA_SCAN_MS) / 1000 + 1 ))
EMAIL="e2e-$SUFIXO@example.com"
FONE="11$(printf '%09d' $(( (RANDOM * 32768 + RANDOM) % 1000000000 )))"
IMAGEM_MVN="maven:3.9.7-eclipse-temurin-21-jammy"
FALHAS=0
TMP="$(mktemp -d)"

# Git Bash: o docker e nativo do Windows e a conversao de path do MSYS reescreveria "/dev/tcp/..." e "/var/...".
dexec() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@" ;;
        *) docker "$@" ;;
    esac
}
# Padrao: as imagens enxutas (*.slim:$SLIM_TAG) do docker-compose.yml; E2E_COMPLETA=1 troca por build das completas (issue #197, D7)
# (SLIM_TAG nao e definida aqui: o compose a resolve e o passo 0 a le dele)
COMPLETA_ARGS=(); [ "${E2E_COMPLETA:-0}" = "1" ] && COMPLETA_ARGS=(-f docker-compose.completa.yml)
dc() { dexec compose -p "$PROJETO" -f docker-compose.yml -f e2e/docker-compose.e2e.yml ${COMPLETA_ARGS[@]+"${COMPLETA_ARGS[@]}"} "$@"; }
# --- E2E_K8S=1 (#197): o mesmo roteiro contra um cluster kind proprio; `sexec`/`cli` abstraem docker (compose) e kubectl -----------------------------
K8S="${E2E_K8S:-0}"
CTX="kind-$PROJETO"
K8S_NS=gerenciador
TRILHA_K8S="${E2E_K8S_TRILHA:-}"
kc() { # kubectl do cluster do e2e: o contexto e SEMPRE explicito (o `kind create` troca o current-context)
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' kubectl --context "$CTX" -n "$K8S_NS" "$@" ;;
        *) kubectl --context "$CTX" -n "$K8S_NS" "$@" ;;
    esac
}
cluster() { K8S_NOME="$PROJETO" K8S_OVERLAY=e2e bash "$SCRIPT_DIR/../k8s/cluster.sh" "$@"; }
alvo_k8s() { case "$1" in db|redpanda) echo "sts/$1" ;; *) echo "deploy/$1" ;; esac; }
sexec() { # servico comando...: dentro do container (compose) ou do pod (cluster) do servico; stdin repassado
    if [ "$K8S" = "1" ]; then local alvo; alvo="$(alvo_k8s "$1")"; shift; kc exec -i "$alvo" -- "$@"; else dc exec -T "$@"; fi
}
cli() { if [ "$K8S" = "1" ]; then kc exec -i "$CLI" -- "$@"; else dexec exec -i "$CLI" "$@"; fi; }
psql_db() { # a role do app e a do container; -h localhost evita o peer auth do socket
    sexec db sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$POSTGRES_DB" -At' <<<"$1" | tr -d '\r'
}

ok()   { echo "   [ok]    $1"; }
erro() { echo "   [ERRO]  $1"; FALHAS=$((FALHAS + 1)); }
confere() { # descricao esperado obtido
    if [ "$2" = "$3" ]; then ok "$1: $3"; else erro "$1: esperado '$2', obtido '$3'"; fi
}
abortar() { echo "   [ABORTA] $1"; exit 1; }

# --- guarda do project: NUNCA o real ---------------------------------------------------------------------------------------
case "$PROJETO" in
    gerenciador-eventos) abortar "o project '$PROJETO' e o da stack REAL: esta suite so roda num project temporario (gerenciador-e2e*)." ;;
    gerenciador-e2e*) ;;
    *) abortar "E2E_PROJECT='$PROJETO' invalido: o nome precisa comecar com gerenciador-e2e." ;;
esac

# Credencial efemera do Redis do project temporario (#41): a shell tem precedencia sobre o Projeto/.env na interpolacao do compose.
command -v openssl >/dev/null 2>&1 || abortar "preciso de openssl (rand -hex) para gerar a senha efemera do Redis do project."
REDIS_PASSWORD="$(openssl rand -hex 16)"
export REDIS_PASSWORD
# Credenciais efemeras do RabbitMQ do project temporario (#42): mesmo motivo (a shell vence o Projeto/.env na interpolacao do compose).
RABBITMQ_USER="e2e$(openssl rand -hex 4)"
RABBITMQ_PASSWORD="$(openssl rand -hex 16)"
export RABBITMQ_USER RABBITMQ_PASSWORD

ISOLADO=0 # so vira 1 depois que o `config` passou na validacao de isolamento (passo 0)
derrubar() { # down -v SO do project temporario e SO com o isolamento validado (config rejeitado => nao derruba nada)
    case "$PROJETO" in
        gerenciador-e2e*)
            if [ "$ISOLADO" = "1" ]; then
                if [ "$K8S" = "1" ]; then cluster down >/dev/null 2>&1; else dc down -v --remove-orphans >/dev/null 2>&1; fi
            fi ;;
    esac
}
limpar() {
    dexec rm -f "$CLI" >/dev/null 2>&1
    if [ "$MANTER" = "1" ]; then
        if [ "$K8S" = "1" ]; then echo "==> MANTER=1: cluster '$PROJETO' mantido. Para apagar: kind delete cluster --name $PROJETO"
        else echo "==> MANTER=1: project '$PROJETO' mantido. Para apagar: docker compose -p $PROJETO -f docker-compose.yml -f e2e/docker-compose.e2e.yml down -v"; fi
    else
        if [ "$K8S" = "1" ]; then echo "==> (saida) kind delete cluster do cluster temporario '$PROJETO'"; else echo "==> (saida) down -v do project temporario '$PROJETO'"; fi
        derrubar
        [ "$ISOLADO" = "1" ] || echo "   (isolamento nao validado: nada foi derrubado)"
    fi
    rm -rf "$TMP"
}
trap limpar EXIT

PY=""
for c in python3 python; do
    if "$c" -c 'import json' >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -n "$PY" ] || abortar "preciso de python (python3) para filtrar o JSON do 'docker compose config'."
[ -f .env ] && [ -f supabase/.env ] || abortar "faltam Projeto/.env e/ou Projeto/supabase/.env (ver supabase/README.md)."

# Le o JSON do `config` (com segredos) e imprime SO violacoes de isolamento/e-mail (nomes, nunca valores).
VALIDADOR='
import json, sys
c = json.load(sys.stdin)
proj = sys.argv[1]
erros = []
for nome, s in sorted(c["services"].items()):
    if s.get("container_name"):
        erros.append("servico %s: container_name fixo (%s)" % (nome, s["container_name"]))
    if s.get("ports"):
        erros.append("servico %s: porta publicada no host" % nome)
    if s.get("network_mode"):
        erros.append("servico %s: network_mode %s" % (nome, s["network_mode"]))
    for v in (s.get("volumes") or []):
        if v.get("type") == "bind" and not v.get("read_only"):
            erros.append("servico %s: bind mount gravavel do host (%s)" % (nome, v.get("source")))
    for rede in (s.get("networks") or {"default": None}):
        if rede != "default":
            erros.append("servico %s: rede %s fora do default" % (nome, rede))
for chave, rede in sorted(c.get("networks", {}).items()):
    nome = str(rede.get("name"))
    if rede.get("external") or nome == "gerenciador-net" or not nome.startswith(proj + "_"):
        erros.append("rede %s: nome efetivo %s (esperado %s_*)" % (chave, nome, proj))
for chave, vol in sorted(c.get("volumes", {}).items()):
    nome = str(vol.get("name"))
    if vol.get("external") or not nome.startswith(proj + "_"):
        erros.append("volume %s: nome efetivo %s (esperado %s_*)" % (chave, nome, proj))
env = (c["services"].get("notification") or {}).get("environment") or {}
if env.get("MAIL_HOST") != "mailpit" or env.get("MAIL_PORT") != "1025" or env.get("MAIL_SMTP_AUTH") != "false" \
        or env.get("MAIL_STARTTLS") != "false" or env.get("MAIL_PASSWORD")         or not str(env.get("MAIL_USERNAME") or "").endswith("@example.com"):
    erros.append("notification: SMTP nao aponta para o captador mailpit:1025 sem auth/starttls/senha (remetente @example.com)")
for obrigatorio in ("mailpit", "redis", "rabbitmq", "admission", "stock", "stock-seek"):
    if obrigatorio not in c["services"]:
        erros.append("falta o servico " + obrigatorio)
adm_env = (c["services"].get("admission") or {}).get("environment") or {}
if adm_env.get("REDIS_HOST") != "redis" or not adm_env.get("REDIS_PASSWORD"):
    erros.append("admission: REDIS_HOST nao aponta para o redis do project ou falta a senha efemera")
if (c["services"].get("backend") or {}).get("environment", {}).get("REDIS_PASSWORD"):
    erros.append("backend: recebeu REDIS_PASSWORD (o Redis e so do admission)")
if adm_env.get("RABBITMQ_HOST") != "rabbitmq" or not adm_env.get("RABBITMQ_USER") or not adm_env.get("RABBITMQ_PASSWORD"):
    erros.append("admission: RABBITMQ_HOST nao aponta para o rabbitmq do project ou faltam as credenciais efemeras")
rabbit_env = (c["services"].get("rabbitmq") or {}).get("environment") or {}
if rabbit_env.get("RABBITMQ_DEFAULT_USER") != adm_env.get("RABBITMQ_USER") or rabbit_env.get("RABBITMQ_DEFAULT_PASS") != adm_env.get("RABBITMQ_PASSWORD"):
    erros.append("rabbitmq: as credenciais do broker nao sao as do admission")
bk_env = (c["services"].get("backend") or {}).get("environment", {})
if bk_env.get("RABBITMQ_USER") or bk_env.get("RABBITMQ_PASSWORD"):
    erros.append("backend: recebeu as credenciais do RabbitMQ (a fila e so do admission)")
res_env = (c["services"].get("reservation") or {}).get("environment") or {}
if res_env.get("STOCK_SERVICE_URL") != "http://stock:8086":
    erros.append("reservation: STOCK_SERVICE_URL nao aponta para o stock do project")
try:
    orfa = float(res_env.get("RESERVATION_SAGA_ORPHAN_MS") or 0)
except ValueError:
    orfa = 0
if not 1000 <= orfa <= 10000:
    erros.append("reservation: RESERVATION_SAGA_ORPHAN_MS=%s fora de 1000..10000 (os passos da saga esperam a janela do reaper)" % res_env.get("RESERVATION_SAGA_ORPHAN_MS"))
if (c["services"].get("stock") or {}).get("environment", {}).get("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE") != "250":
    erros.append("stock: Hikari nao e o literal 250")
try:
    x = float(adm_env.get("ADMISSION_X_RPM") or 0)
except ValueError:
    x = 0
if not 500 <= x <= 5000:
    # o passo 4 faz UMA reserva e ela tem de seguir sincrona (uma tentativa isolada le ate ~397 RPM; 80% de X > 397 pede X >= 500);
    # a rajada do passo 4b tem de passar de 80% de X (X baixo, para a suite nao depender de carga real)
    erros.append("admission: ADMISSION_X_RPM=%s fora de 500..5000 (o e2e precisa de X baixo e acima de 500)" % adm_env.get("ADMISSION_X_RPM"))
for e in erros:
    print("   " + e)
sys.exit(1 if erros else 0)
'

echo "=================================================="
echo "   E2E #37: stack inteira em project temporario"
echo "   PROJETO=$PROJETO  REDE=$REDE  SUFIXO=$SUFIXO"
echo "=================================================="

if [ "$K8S" = "1" ]; then
    echo "==> 0. isolamento (Secrets e NodePort) ANTES de criar o cluster"
    case "$TRILHA_K8S" in nenhuma|b1|c) ;; *) abortar "E2E_K8S=1 exige E2E_K8S_TRILHA=nenhuma|c|b1 (a trilha de balanceamento; obtido: '$TRILHA_K8S')." ;; esac
    [ "${E2E_COMPLETA:-0}" = "1" ] && abortar "E2E_K8S=1 roda so as *.slim (E2E_COMPLETA=1 vale so para o compose)."
    for f in kind kubectl $([ "$TRILHA_K8S" = b1 ] && echo helm); do command -v "$f" >/dev/null 2>&1 || abortar "preciso de '$f' no PATH (kind em E:\\TCC\\claude-tooling\\bin)."; done
    # autoescala (#197, etapa 4): uma replica por servico no roteiro (min=max=1); o reservation sobe com teto 2 (o admission so grava adm:replicas dentro de [1, max]) e e limitado a 1 depois do up
    for _s in BACKEND IDENTITY CATALOG NOTIFICATION ADMISSION; do export "K8S_${_s}_MIN=1" "K8S_${_s}_MAX=1"; done
    export K8S_RESERVATION_MIN=1 K8S_RESERVATION_MAX=2 K8S_DESCE_SEG=5
    cluster guarda --trilha "$TRILHA_K8S" || abortar "o isolamento NAO pode ser verificado/garantido: nada foi criado."
    ok "Secrets do overlay e2e conferidos (mailpit, credenciais efemeras, X baixo) e manifests sem NodePort"
else
echo "==> 0. isolamento (config) ANTES do up"
VIOLACOES="$(dc config --format json 2>/dev/null | "$PY" -c "$VALIDADOR" "$PROJETO" 2>&1)"
if [ $? -ne 0 ] || [ -n "$VIOLACOES" ]; then
    echo "$VIOLACOES"
    abortar "o isolamento NAO pode ser verificado/garantido: nada foi subido."
fi
ok "sem container_name, sem porta no host, rede e volumes com o prefixo do project, notification -> mailpit:1025, redis, rabbitmq, admission, stock e stock-seek isolados"
fi
ISOLADO=1
SERVICOS_JAVA="backend identity catalog reservation stock notification admission"
if [ "${E2E_COMPLETA:-0}" = "1" ]; then
    ok "E2E_COMPLETA=1: os 7 servicos serao construidos pelo Dockerfile (imagem completa) neste project"
else
    SLIM_TAG="$(dc config --images 2>/dev/null | sed -n 's/^gerenciador-eventos-backend\.slim://p' | tr -d '\r')"
    [ -n "$SLIM_TAG" ] || abortar "nao consegui resolver a tag das slim (config --images nao devolveu gerenciador-eventos-backend.slim:<tag>)."
    ok "tag das slim: $SLIM_TAG (a que o compose resolve: shell > Projeto/.env > latest)"
    for svc in $SERVICOS_JAVA; do
        dexec image inspect "gerenciador-eventos-$svc.slim:$SLIM_TAG" >/dev/null 2>&1 || abortar "falta a imagem gerenciador-eventos-$svc.slim:$SLIM_TAG (rode Projeto/slim/gerar-imagens-slim.sh, com SLIM_TAG=$SLIM_TAG se for outra tag; ou E2E_COMPLETA=1 para construir as completas)."
    done
    ok "imagens *.slim:$SLIM_TAG presentes; os 7 servicos vao rodar com elas"
fi

echo "==> 1. project limpo; 1a) so o redpanda; 1b) um pedido de e-mail PENDENTE no topico; 1c) up -d --build --wait (pode levar varios minutos no 1o build)"
derrubar
# O down -v deixa o topico vazio, entao "0 e-mails depois do 1o up" passaria igual SEM o seek. Para provar o seek: com o group
# notification ainda inexistente, o topico ja tem um VerificationCodeRequested valido (de um endereco @example.com, com validade
# longa para o consumer nao descarta-lo por vencido): sem o seek o group novo (earliest) o leria e mandaria o e-mail ao captador.
if [ "$K8S" = "1" ]; then
    # 1a no cluster: cria o cluster, carrega as imagens, gera Secrets/ConfigMaps e aplica SO o redpanda (app=redpanda); o resto vem no 1c
    K8S_ESPERA="$ESPERA_UP" cluster up --trilha "$TRILHA_K8S" --so redpanda || abortar "o redpanda do cluster nao ficou pronto"
else
dc up -d --wait --wait-timeout "$ESPERA_UP" redpanda || abortar "o redpanda do project nao ficou healthy"
fi
PENDENTE_EMAIL="pendente-$SUFIXO@example.com"
ENVELOPE="$("$PY" -c '
import datetime, json, sys, uuid
agora = datetime.datetime.now(datetime.timezone.utc)
fmt = lambda t: t.strftime("%Y-%m-%dT%H:%M:%S.000Z")
print(json.dumps({"eventId": str(uuid.uuid4()), "streamType": "identity.notification", "streamId": str(uuid.uuid4()),
    "version": 1, "globalPosition": 0, "eventType": "VerificationCodeRequested", "schemaVersion": 1, "occurredAt": fmt(agora),
    "payload": {"email": sys.argv[1], "userName": "Pendente", "code": "654321", "expirationMinutes": 240,
                "expiresAt": fmt(agora + datetime.timedelta(hours=4))}, "metadata": {}}, separators=(",", ":")))
' "$PENDENTE_EMAIL")"
sexec redpanda rpk topic create identity.notification -p 1 -r 1 >/dev/null 2>&1 # produce em topico inexistente falha (UNKNOWN_TOPIC_OR_PARTITION)
PRODUZIDO="$(sexec redpanda rpk topic produce identity.notification -k pendente <<<"$ENVELOPE" 2>&1 | tr -d '\r')"
confere "pedido de e-mail pendente produzido em identity.notification (offset 0)" sim "$(grep -q 'offset 0' <<<"$PRODUZIDO" && echo sim || echo nao)"
if [ "$K8S" = "1" ]; then
    # 1c no cluster: aplica o resto (o `up` espera Deployments, StatefulSets e o Job `particoes`); depois confere cada workload e o Job
    K8S_ESPERA="$ESPERA_UP" cluster up --trilha "$TRILHA_K8S" || { kc get pods; abortar "o cluster nao ficou todo pronto. Logs: kubectl --context $CTX -n $K8S_NS logs <pod> [-c <init>]"; }
    for alvo in sts/db sts/redpanda deploy/supabase-pooler deploy/redis deploy/rabbitmq deploy/mailpit deploy/frontend $(for s in backend identity catalog reservation stock notification admission; do echo deploy/$s; done); do
        confere "$alvo pronto (prontas/desejadas)" "1/1" "$(kc get "$alvo" -o jsonpath='{.status.readyReplicas}/{.spec.replicas}')"
    done
    confere "Job particoes concluido (identity.notification e reservation.wallet com 3 particoes)" 1 "$(kc get job particoes -o jsonpath='{.status.succeeded}')"
    for sec in db supabase-pooler; do # o supabase/.env inteiro chega a esses dois no compose; no cluster os segredos de Studio/Kong/meta nao entram (so nomes de chaves, nunca valores)
        confere "secret env-$sec sem chaves de Studio/Kong/meta (DASHBOARD_*, SERVICE_ROLE_KEY, ANON_KEY, SUPABASE_*_KEY, PG_META_CRYPTO_KEY)" "" \
            "$(kc get secret "env-$sec" -o json | "$PY" -c 'import json, sys; p = {"DASHBOARD_USERNAME", "DASHBOARD_PASSWORD", "SERVICE_ROLE_KEY", "ANON_KEY", "SUPABASE_PUBLISHABLE_KEY", "SUPABASE_SECRET_KEY", "PG_META_CRYPTO_KEY", "STUDIO_DEFAULT_ORGANIZATION", "STUDIO_DEFAULT_PROJECT"}; print(",".join(sorted(k for k in json.load(sys.stdin)["data"] if k in p)))' | tr -d '[:space:]')"
    done
    kc patch scaledobject reservation --type merge -p '{"spec":{"maxReplicaCount":1}}' >/dev/null || abortar "nao consegui limitar o ScaledObject do reservation a 1 replica"
    confere "namespace com o label da trilha do up (guarda contra misturar trilhas)" "$TRILHA_K8S" \
        "$(kubectl --context "$CTX" get namespace "$K8S_NS" -o jsonpath="{.metadata.labels['trilha-balanceamento']}")"
    confere "ScaledObjects Ready (6: reservation, notification, backend, identity, catalog, admission; o stock e fixo, sem ScaledObject)" 6 \
        "$(kc get scaledobject -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' | grep -c True)"
    confere "nenhum container reiniciou por probe/falha (restartCount total)" 0 \
        "$(kc get pods -o jsonpath='{range .items[*]}{range .status.containerStatuses[*]}{.restartCount}{"\n"}{end}{end}' | awk '{s += $1} END {print s + 0}')"
    if [ "$TRILHA_K8S" = "c" ]; then
        # Trilha C: o java.security extra (cache de DNS de 5 s). A prova mais forte e o log da JVM do backend COM o perfil Seccomp: "Picked up JAVA_TOOL_OPTIONS" so sai se o HotSpot LEU a variavel.
        # (Na 4a ele nao saia e a causa estava mal atribuida: faltava `getegid` nos 7 perfis, o HotSpot nao confirma uid==euid/gid==egid, trata o processo como privilegiado e IGNORA a variavel
        # em silencio; o `getegid` esta no seccomp-extras.txt desde a 4b.) Alem do log, duas provas independentes do perfil: (1) a variavel esta no ambiente do PROCESSO da JVM do backend
        # (/proc/<pid>/environ, lido do no); (2) a JVM da MESMA imagem, com a MESMA variavel e o MESMO ConfigMap, enxerga networkaddress.cache.ttl=5 (um pod efemero, SEM o perfil Seccomp: com ele
        # o `-XshowSettings` nao imprime, pela mesma razao medida na 4a; roda `java -XshowSettings:security:properties`; sem a variavel o ttl nem aparece).
        confere "trilha c: o log do backend (COM o perfil Seccomp) traz 'Picked up JAVA_TOOL_OPTIONS' (a JVM leu o java.security extra)" 1 \
        "$(kc logs deploy/backend -c backend 2>&1 | grep -c '^Picked up JAVA_TOOL_OPTIONS: -Djava.security.properties=')"
        BK_CID="$(kc get pod -l app=backend -o jsonpath='{.items[0].status.containerStatuses[?(@.name=="backend")].containerID}' | sed 's#.*://##')"
        BK_PID="$(dexec exec "$PROJETO-control-plane" crictl inspect --output go-template --template '{{.info.pid}}' "$BK_CID" | tr -d '\r')"
        confere "trilha c: JAVA_TOOL_OPTIONS (java.security extra) no ambiente do processo da JVM do backend" 1 \
            "$(dexec exec "$PROJETO-control-plane" sh -c "tr '\\0' '\\n' </proc/$BK_PID/environ" | tr -d '\r' | grep -c '^JAVA_TOOL_OPTIONS=-Djava.security.properties=/etc/java-security/java.security$')"
        JSEC_POD="$CLI-jsec"
        kc delete pod "$JSEC_POD" --ignore-not-found --now >/dev/null 2>&1
        kc apply -f - >/dev/null <<EOF || abortar "nao consegui criar o pod da prova do java.security"
apiVersion: v1
kind: Pod
metadata: {name: $JSEC_POD, labels: {app: e2e-jsec, sidecar.istio.io/inject: "false"}}
spec:
  restartPolicy: Never
  enableServiceLinks: false
  automountServiceAccountToken: false
  volumes:
    - {name: js, configMap: {name: java-security}}
  containers:
    - name: java
      image: gerenciador-eventos-backend.slim:$SLIM_TAG
      imagePullPolicy: Never
      command: [java, "-XshowSettings:security:properties", "-version"]
      env:
        - {name: JAVA_TOOL_OPTIONS, value: "-Djava.security.properties=/etc/java-security/java.security"}
      volumeMounts:
        - {name: js, mountPath: /etc/java-security, readOnly: true}
EOF
        for i in $(seq 1 60); do [ "$(kc get pod "$JSEC_POD" -o jsonpath='{.status.phase}')" = "Succeeded" ] && break; sleep 2; done
        JSEC_OUT="$(kc logs "$JSEC_POD" 2>&1 | tr -d '\r')"
        confere "trilha c: a JVM com o java.security extra enxerga networkaddress.cache.ttl=5 e negative.ttl=1" "1 1" \
            "$(grep -c 'networkaddress.cache.ttl=5$' <<<"$JSEC_OUT") $(grep -c 'networkaddress.cache.negative.ttl=1$' <<<"$JSEC_OUT")"
        kc delete pod "$JSEC_POD" --now >/dev/null 2>&1
    fi
    if [ "$TRILHA_K8S" = "b1" ]; then
        # B1 (#197): sidecar so nos 7 servicos e no frontend; nenhum na infra, no Mailpit e no Job; proxy sem limit de CPU; sidecar NATIVO (initContainer); sem reinicio do proxy
        COM_PROXY="$(kc get pods -o jsonpath='{range .items[*]}{.metadata.labels.app}{" "}{range .spec.initContainers[*]}{.name}{","}{end}{range .spec.containers[*]}{.name}{","}{end}{"\n"}{end}' \
            | awk '$2 ~ /istio-proxy/ {print $1}' | sort | tr '\n' ' ' | sed 's/ $//')"
        confere "istio-proxy presente nos 7 servicos e no frontend (e so neles)" "admission backend catalog frontend identity notification reservation stock" "$COM_PROXY"
        confere "istio-proxy ausente em db, supabase-pooler, redpanda, redis, rabbitmq, mailpit e no Job particoes" "" \
            "$(kc get pods -o jsonpath='{range .items[*]}{.metadata.labels.app}{.metadata.labels.job-name}{" "}{range .spec.initContainers[*]}{.name}{","}{end}{range .spec.containers[*]}{.name}{","}{end}{"\n"}{end}' \
                | awk '$2 ~ /istio-proxy/ && $1 ~ /^(db|supabase-pooler|redpanda|redis|rabbitmq|mailpit|particoes)$/ {print $1}' | tr '\n' ' ' | sed 's/ $//')"
        confere "sidecar nativo: istio-proxy em initContainerStatuses do backend" istio-proxy \
            "$(kc get pod -l app=backend -o jsonpath='{.items[0].status.initContainerStatuses[?(@.name=="istio-proxy")].name}')"
        PROXY_RES="$(kc get pod -l app=backend -o jsonpath='{.items[0].spec.initContainers[?(@.name=="istio-proxy")].resources}')"
        confere "istio-proxy do backend: request de CPU 100m e SEM limit de CPU (limit so de memoria: 1Gi, o gateway recebe as conexoes de leitura)" "sim 1Gi" \
            "$(grep -q '"requests":{"cpu":"100m"' <<<"$PROXY_RES" && ! grep -oE '"limits":\{[^}]*\}' <<<"$PROXY_RES" | grep -q cpu && echo sim || echo nao) $(grep -oE '"limits":\{[^}]*\}' <<<"$PROXY_RES" | grep -oE '[0-9]+[GM]i')"
        confere "istio-proxy dos demais (identity): limit de memoria 256Mi" 256Mi \
            "$(kc get pod -l app=identity -o jsonpath='{.items[0].spec.initContainers[?(@.name=="istio-proxy")].resources.limits.memory}')"
        confere "b1: mesh config do istio com defaultHttpRetryPolicy.attempts: 0 (a malha nao repete POST)" sim \
            "$([ "$(kubectl --context "$CTX" -n istio-system get configmap istio -o jsonpath='{.data.mesh}' | grep -Ec 'attempts: *0')" -ge 1 ] && echo sim || echo nao)"
        confere "b1: EXIT_ON_ZERO_ACTIVE_CONNECTIONS no istio-proxy do backend (termino sem cortar requisicao em voo)" sim \
            "$([ "$(kc get pod -l app=backend -o jsonpath='{.items[0].spec.initContainers[?(@.name=="istio-proxy")].env[*].name}' | tr ' ' '\n' | grep -cx EXIT_ON_ZERO_ACTIVE_CONNECTIONS)" -ge 1 ] && echo sim || echo nao)"
        confere "istio-proxy nao reiniciou (restartCount total dos sidecars)" 0 \
            "$(kc get pods -o jsonpath='{range .items[*]}{range .status.initContainerStatuses[?(@.name=="istio-proxy")]}{.restartCount}{"\n"}{end}{end}' | awk '{s += $1} END {print s + 0}')"
    fi
else
dc up -d --build --wait --wait-timeout "$ESPERA_UP" || { dc ps -a; abortar "up --wait falhou (algum servico nao ficou healthy). Logs: docker compose -p $PROJETO ... logs <servico>"; }
SAUDE="$(dc ps -a --format '{{.Service}} {{.State}} {{or .Health "-"}} {{.ExitCode}}' | tr -d '\r')"
while read -r servico estado saude codigo; do
    [ -n "$servico" ] || continue
    case "$servico" in
        notification-seek|stock-seek) confere "$servico (one-shot) terminou com 0" "exited 0" "$estado $codigo" ;;
        *) confere "$servico running/healthy" "running healthy" "$estado $saude" ;;
    esac
done <<<"$SAUDE"
fi
[ "$FALHAS" -eq 0 ] || abortar "stack nao esta toda healthy."
# a imagem tem que ser a esperada: a *.slim:$SLIM_TAG (padrao) ou, com E2E_COMPLETA=1, a gorda construida neste project (nao uma gorda com nome slim)
imagem_esperada() { if [ "${E2E_COMPLETA:-0}" = "1" ]; then echo "$PROJETO-$1:latest"; else echo "gerenciador-eventos-$1.slim:$SLIM_TAG"; fi; }
for svc in $SERVICOS_JAVA; do
    if [ "$K8S" = "1" ]; then
        # O ID do Docker (store containerd) nao e o do no: o `kind load` reexporta a imagem. Prova em dois passos: (1) a camada de cima da imagem da tag no no
        # == a da imagem local; (2) o digest do imageID do pod esta nos repoDigests dessa imagem do no (o pod roda ESSA imagem).
        IMG_ESP="$(imagem_esperada "$svc")"; NO_IMG="$PROJETO-control-plane"
        CAMADA_LOCAL="$(dexec image inspect -f '{{range .RootFS.Layers}}{{.}}{{"\n"}}{{end}}' "$IMG_ESP" | tr -d '\r' | grep . | tail -1)"
        CAMADA_NO="$(dexec exec "$NO_IMG" crictl inspecti --output go-template --template '{{range .info.imageSpec.rootfs.diff_ids}}{{.}}{{"\n"}}{{end}}' "$IMG_ESP" | tr -d '\r' | grep . | tail -1)"
        confere "$svc: camada superior de $IMG_ESP no no == a da imagem local (nao vazia)" sim \
            "$([ -n "$CAMADA_LOCAL" ] && [ "$CAMADA_LOCAL" = "$CAMADA_NO" ] && echo sim || echo nao)"
        POD_DIGEST="$(kc get pod -l "app=$svc" -o jsonpath="{.items[0].status.containerStatuses[?(@.name==\"$svc\")].imageID}" | sed 's/.*@//')"
        confere "$svc: o pod roda a imagem $IMG_ESP do no (digest do imageID nos repoDigests, nao vazio)" sim \
            "$([ -n "$POD_DIGEST" ] && dexec exec "$NO_IMG" crictl inspecti --output go-template --template '{{range .status.repoDigests}}{{.}}{{"\n"}}{{end}}' "$IMG_ESP" | tr -d '\r' | grep -qF "$POD_DIGEST" && echo sim || echo nao)"
    else
    confere "$svc roda a imagem $(imagem_esperada "$svc")" "$(dexec image inspect -f '{{.Id}}' "$(imagem_esperada "$svc")")" \
        "$(dexec inspect -f '{{.Image}}' "$(dc ps -q "$svc" | head -1)")"
    fi
done
[ "$FALHAS" -eq 0 ] || abortar "algum servico NAO esta rodando a imagem esperada."

echo "==> 2. guarda de e-mail: o notification so fala com o captador"
confere "MAIL_HOST efetivo do container notification" mailpit "$(sexec notification printenv MAIL_HOST | tr -d '[:space:]')"
[ "$FALHAS" -eq 0 ] || abortar "o notification NAO esta apontado para o captador: nenhum e-mail pode sair."
if [ "$K8S" = "1" ]; then
    # pod auxiliar de curl no namespace (sem sidecar), com a imagem Maven (curl, bash, xargs, mvn do replay) carregada no no
    cluster imagem "$IMAGEM_MVN" || abortar "carregar a imagem auxiliar no no falhou"
    kc delete pod "$CLI" --ignore-not-found --now >/dev/null 2>&1
    kc run "$CLI" --image="$IMAGEM_MVN" --labels=sidecar.istio.io/inject=false --image-pull-policy=IfNotPresent --restart=Never --overrides='{"spec":{"automountServiceAccountToken":false}}' --command -- sleep infinity >/dev/null || abortar "nao consegui subir o pod auxiliar de curl"
    kc wait --for=condition=ready "pod/$CLI" --timeout=180s >/dev/null || abortar "o pod auxiliar de curl nao ficou pronto"
else
dexec rm -f "$CLI" >/dev/null 2>&1
dexec run -d --rm --name "$CLI" --network "$REDE" --entrypoint sleep "$IMAGEM_MVN" infinity >/dev/null || abortar "nao consegui subir o container auxiliar de curl"
fi
extrair_token() { grep -o '"token":"[^"]*"' <<<"$1" | head -1 | cut -d'"' -f4; }
extrair_num() { grep -oE "\"$1\" ?: ?[0-9]+" <<<"$2" | head -1 | grep -oE '[0-9]+$'; }
http() { # metodo url [corpo-json] [token] [header-extra] -> HTTP_CODE, HTTP_BODY
    local args=(-s -m 30 -X "$1" -w '\n%{http_code}' "$2") out
    [ -n "${3:-}" ] && args+=(-H 'Content-Type: application/json' -d "$3")
    [ -n "${4:-}" ] && args+=(-H "Authorization: Bearer $4")
    [ -n "${5:-}" ] && args+=(-H "$5")
    out="$(cli curl "${args[@]}" | tr -d '\r')"
    HTTP_CODE="${out##*$'\n'}"
    HTTP_BODY="${out%$'\n'*}"
}
GW="http://backend:8081"
MAILPIT="http://mailpit:8025"
mails_para() { # quantidade de e-mails para $1 no captador
    cli curl -s -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:$1" | grep -oE '"messages_count": ?[0-9]+' | grep -oE '[0-9]+$'
}
echo "   group notification depois do 1o up:"
sexec redpanda rpk group describe notification 2>&1 | tr -d '\r' | sed 's/^/     /'
# group novo: o seek o pos DEPOIS do pedido pendente (offset 1, log-end 1, lag 0) e o consumer nao o releu
GRUPO_NOTIF="$(sexec redpanda rpk group describe notification 2>/dev/null | tr -d '\r' | awk '$1 == "identity.notification" {print $3, $5, $6}')"
if [ "$K8S" = "1" ]; then
    # no cluster o topico tem 3 particoes (Job `particoes`): o pedido pendente esta na 0 (offset 1, log-end 1, lag 0) e as 3 tem lag 0
    confere "group notification depois do 1o up, particao 0: CURRENT-OFFSET LOG-END-OFFSET LAG" "1 1 0" "$(head -1 <<<"$GRUPO_NOTIF")"
    confere "group notification depois do 1o up: 3 particoes, lag total 0" "3 0" "$(awk '{n++; l += $3} END {print n + 0, l + 0}' <<<"$GRUPO_NOTIF")"
else
confere "group notification depois do 1o up: CURRENT-OFFSET LOG-END-OFFSET LAG" "1 1 0" "$GRUPO_NOTIF"
fi
sleep 5 # folga para um (hipotetico) envio do pedido pendente chegar ao captador
confere "e-mails no captador depois do 1o up (o pedido pendente NAO virou e-mail: o seek moveu o group)" 0 \
    "$(cli curl -s "$MAILPIT/api/v1/messages" | grep -oE '"total": ?[0-9]+' | head -1 | grep -oE '[0-9]+$')"
confere "e-mails para o endereco do pedido pendente" 0 "$(mails_para "$PENDENTE_EMAIL")"
[ "$FALHAS" -eq 0 ] || abortar "o 1o up mandou e-mail ou o captador nao responde."
if [ "$K8S" = "1" ]; then
    # probes (#207): porta de management 8090 (outro conector e outro pool) so com `health`, sem detalhes e sem token. A porta 8090 nao entra no Service: o e2e fala direto
    # com o IP do pod. (Que a porta da API dos 5 internos nao serve o actuator e de construcao, `management.server.port` separada; nao ha checagem: o InternalTokenFilter
    # responde 401 a tudo sem o token real do .env, que o e2e nao le, entao ela seria vacuosa.)
    for par in backend identity catalog reservation stock admission; do
        ip="$(kc get pod -l "app=$par" -o jsonpath='{.items[0].status.podIP}')"
        rd="$(cli curl -s -m 10 -w '\n%{http_code}' "http://$ip:8090/actuator/health/readiness" | tr -d '\r')"
        confere "$par: GET :8090/actuator/health/readiness sem token (codigo, status UP)" "200 sim" "${rd##*$'\n'} $(grep -q '"status":"UP"' <<<"$rd" && echo sim || echo nao)"
        confere "$par: GET :8090/actuator/health/liveness" 200 "$(cli curl -s -m 10 -o /dev/null -w '%{http_code}' "http://$ip:8090/actuator/health/liveness")"
        info="$(cli curl -s -m 10 -o /dev/null -w '%{http_code}' "http://$ip:8090/actuator/info")" # 404 (nao exposto) ou 401 (o gateway barra tudo que nao e health)
        confere "$par: GET :8090/actuator/info nao expoe dados (404 ou 401)" sim "$([ "$info" = 404 ] || [ "$info" = 401 ] && echo sim || echo nao)"
    done
    [ "$FALHAS" -eq 0 ] || abortar "as probes/management do cluster nao estao como a spec pede."
fi

echo "==> 3. cadastro em duas fases (o codigo vem do e-mail no captador)"
http POST "$GW/bff/users/request-verification" \
    "{\"name\":\"E2E $SUFIXO\",\"email\":\"$EMAIL\",\"password\":\"senha123\",\"fone\":\"$FONE\",\"birthdate\":\"1990-01-15\"}"
confere "POST /bff/users/request-verification" 201 "$HTTP_CODE"
CODIGO=""
for i in $(seq 1 60); do
    if [ "$(mails_para "$EMAIL")" = "1" ]; then
        ID_MAIL="$(cli curl -s -G "$MAILPIT/api/v1/search" --data-urlencode "query=to:$EMAIL" | grep -o '"ID":"[^"]*"' | head -1 | cut -d'"' -f4)"
        CODIGO="$(cli curl -s "$MAILPIT/api/v1/message/$ID_MAIL" | sed -n 's/.*Verificação: \([0-9]\{6\}\).*/\1/p')"
        break
    fi
    sleep 2
done
confere "e-mail de verificacao chegou ao captador e traz um codigo de 6 digitos" sim "$([[ "$CODIGO" =~ ^[0-9]{6}$ ]] && echo sim || echo nao)"
[ "$FALHAS" -eq 0 ] || abortar "sem codigo de verificacao nao ha cadastro (o evento chegou ao notification?)"
http POST "$GW/bff/users/verify-email" "{\"email\":\"$EMAIL\",\"verificationCode\":\"$CODIGO\"}"
confere "POST /bff/users/verify-email com o codigo lido do e-mail" 201 "$HTTP_CODE"
# nenhuma rota promove usuario a host: por SQL ANTES do login (isHost e lido do banco na emissao do token)
psql_db "UPDATE identity.users SET host = true WHERE email = '$EMAIL'" >/dev/null
http POST "$GW/bff/users/login" "{\"email\":\"$EMAIL\",\"password\":\"senha123\"}"
confere "POST /bff/users/login" 200 "$HTTP_CODE"
TOKEN="$(extrair_token "$HTTP_BODY")"
USER_ID="$(extrair_num user_id "$HTTP_BODY")"
confere "token e user_id do login" sim "$([ -n "$TOKEN" ] && [ -n "$USER_ID" ] && echo sim || echo nao)"
[ "$FALHAS" -eq 0 ] || abortar "sem token nao ha fluxo"

echo "==> 4. criar evento -> reservar -> confirmar (pelo gateway, braco cas)"
http POST "$GW/bff/events" \
    "{\"event_name\":\"E2E $SUFIXO\",\"is_EAD\":true,\"event_date\":\"2035-12-01T14:00:00\",\"description\":\"e2e #37\",\"lot_quantity\":5,\"presenters\":[]}" "$TOKEN"
confere "POST /bff/events (host autenticado)" 201 "$HTTP_CODE"
EVENT_ID="$(extrair_num event_id "$HTTP_BODY")"
[ -n "$EVENT_ID" ] || abortar "sem event_id na resposta"
http POST "$GW/bff/event-wallets" "{\"userId\":$USER_ID,\"eventId\":$EVENT_ID}" "$TOKEN"
confere "POST /bff/event-wallets (reserva)" 201 "$HTTP_CODE"
confere "reserva em reservation.walletevent" pending \
    "$(psql_db "SELECT status FROM reservation.walletevent WHERE user_id = $USER_ID AND event_id = $EVENT_ID")"
http POST "$GW/bff/event-wallets/confirm" "{\"userId\":$USER_ID,\"eventId\":$EVENT_ID}" "$TOKEN"
confere "POST /bff/event-wallets/confirm" 200 "$HTTP_CODE"
confere "reserva em reservation.walletevent depois do confirm" confirmed \
    "$(psql_db "SELECT status FROM reservation.walletevent WHERE user_id = $USER_ID AND event_id = $EVENT_ID")"
confere "e-mails para o usuario no captador (so o de verificacao existe)" 1 "$(mails_para "$EMAIL")"
# admission (#41): o gateway contou a reserva no Redis (o script Lua do contador rodou). Prova o caminho gateway -> admission -> Redis na
# stack real; as chaves do contador vivem 250 ms, entao a prova e o contador de comandos do Redis do project (fresco: so o admission o usa).
EVALS="$(sexec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli INFO commandstats' | tr -d '\r' \
    | awk -F'[=,]' '/^cmdstat_eval(sha)?:/ {s += $2} END {print s + 0}')"
confere "scripts Lua do contador executados no Redis (o admission contou a reserva)" sim "$([ "${EVALS:-0}" -ge 1 ] && echo sim || echo nao)"
[ "$FALHAS" -eq 0 ] || abortar "o fluxo falhou antes do rajada/replay"

echo "==> 4b. modo fila (#43): rajada concorrente de $RAJADA_USUARIOS usuarios num evento de capacidade $RAJADA_CAPACIDADE (X do admission = $ADMISSION_X_RPM RPM)"
# precisa de pelo menos 2 usuarios (so a partir de 2 tentativas na janela o RPM passa de 80% de X) e de capacidade MENOR que os usuarios (senao
# nenhum fica "rejected" e "todas as vagas foram dadas" perde o sentido)
[ "$RAJADA_USUARIOS" -ge 2 ] || abortar "E2E_RAJADA_USUARIOS=$RAJADA_USUARIOS: a rajada precisa de pelo menos 2 usuarios."
[ "$RAJADA_CAPACIDADE" -ge 1 ] && [ "$RAJADA_CAPACIDADE" -lt "$RAJADA_USUARIOS" ] \
    || abortar "E2E_RAJADA_CAPACIDADE=$RAJADA_CAPACIDADE: precisa estar entre 1 e $((RAJADA_USUARIOS - 1)) (menor que os usuarios)."
# O passo 4 (UMA reserva, 201) ficou abaixo do gatilho (80% de X = $((ADMISSION_X_RPM * 8 / 10)) RPM; uma tentativa isolada le <= 397 RPM). Aqui, N usuarios
# reservam o MESMO evento ao mesmo tempo: o RPM do evento passa de 80% de X, o admission liga o modo fila e o gateway responde 202 a
# parte deles; o worker pool faz o CAS no reservation REAL. Os usuarios sao criados por SQL (copiando o hash BCrypt do usuario do passo 3:
# nenhum e-mail, nenhum cadastro em duas fases) e logam pelo gateway; a rajada roda dentro do container auxiliar (rajada-container.sh).
http POST "$GW/bff/events" \
    "{\"event_name\":\"E2E rajada $SUFIXO\",\"is_EAD\":true,\"event_date\":\"2035-12-01T14:00:00\",\"description\":\"e2e #43\",\"lot_quantity\":$RAJADA_CAPACIDADE,\"presenters\":[]}" "$TOKEN"
confere "POST /bff/events (evento da rajada, capacidade $RAJADA_CAPACIDADE)" 201 "$HTTP_CODE"
RAJADA_EVENT_ID="$(extrair_num event_id "$HTTP_BODY")"
[ -n "$RAJADA_EVENT_ID" ] || abortar "sem event_id do evento da rajada"
# A janela de confirmacao (eventcap.max_time_in_line, 120 s por padrao) nao pode vencer durante uma rajada grande (E2E_RAJADA_USUARIOS alto):
# o job de expiracao devolveria vagas e "reservas ativas = capacidade" falharia sem haver overselling. Neste project descartavel, 1 h.
confere "stock.eventcap da rajada com max_time_in_line = 3600" 1 \
    "$(psql_db "WITH u AS (UPDATE stock.eventcap SET max_time_in_line = 3600 WHERE event_id = $RAJADA_EVENT_ID RETURNING 1) SELECT count(*) FROM u")"
psql_db "INSERT INTO identity.users (user_name, email, fone, password, birthdate)
    SELECT 'Rajada $SUFIXO ' || g, 'rajada-$SUFIXO-' || g || '@example.com', 'rj-$SUFIXO-' || g, (SELECT password FROM identity.users WHERE email = '$EMAIL'), DATE '1990-01-15'
    FROM generate_series(1, $RAJADA_USUARIOS) AS g" >/dev/null
confere "usuarios da rajada criados por SQL" "$RAJADA_USUARIOS" \
    "$(psql_db "SELECT count(*) FROM identity.users WHERE email LIKE 'rajada-$SUFIXO-%@example.com'")"
[ "$FALHAS" -eq 0 ] || abortar "sem os usuarios da rajada"
# o script roda em segundo plano; a profundidade da fila admission.requests e amostrada (rabbitmqctl) enquanto ele corre, e depois
amostra_fila() { sexec rabbitmq rabbitmqctl list_queues -q name messages 2>/dev/null | tr -d '\r' | awk '$1 == "admission.requests" {print $2}'; }
PARALELO=$(( RAJADA_USUARIOS < 400 ? RAJADA_USUARIOS : 400 ))
cli env GW="$GW" EVENT_ID="$RAJADA_EVENT_ID" SUFIXO="$SUFIXO" N="$RAJADA_USUARIOS" PARALELO="$PARALELO" bash -s \
    <"$SCRIPT_DIR/rajada-container.sh" >"$TMP/rajada.out" 2>&1 &
RAJADA_PID=$!
AMOSTRAS=""
while kill -0 "$RAJADA_PID" 2>/dev/null; do
    AMOSTRAS="$AMOSTRAS $(amostra_fila)"
    sleep 1
done
wait "$RAJADA_PID"
RAJADA_CODE=$?
sed 's/^/     /' "$TMP/rajada.out"
confere "rajada-container.sh terminou (codigo de saida)" 0 "$RAJADA_CODE"
[ "$FALHAS" -eq 0 ] || abortar "a rajada nao rodou"
# conta as respostas por codigo e os estados finais (impressos pelo script)
n_codigo() { awk -v c="$1" '$1 == "CODIGO" && $2 == c {s += $3} END {print s + 0}' "$TMP/rajada.out"; }
n_estado() { awk -v e="$1" '$1 == "ESTADO" && $2 == e {s += $3} END {print s + 0}' "$TMP/rajada.out"; }
C201="$(n_codigo 201)"; C202="$(n_codigo 202)"; C429="$(n_codigo 429)"; C400="$(n_codigo 400)"
echo "   respostas da rajada: 201=$C201 202=$C202 400=$C400 429=$C429 (de $RAJADA_USUARIOS)"
confere "a rajada produziu pelo menos um 202 (o gatilho ligou o modo fila)" sim "$([ "$C202" -ge 1 ] && echo sim || echo nao)"
confere "toda resposta da rajada e 201, 202, 400 (lotado) ou 429" "$RAJADA_USUARIOS" "$((C201 + C202 + C400 + C429))"
confere "nenhum usuario ficou sem estado final em 90 s" 0 "$(awk '$1 == "SEM_ESTADO_FINAL" {print $2}' "$TMP/rajada.out")"
confere "estados finais: pending + rejected + timed_out + rejeitado_429 = usuarios" "$RAJADA_USUARIOS" \
    "$(( $(n_estado pending) + $(n_estado rejected) + $(n_estado timed_out) + $(n_estado rejeitado_429) ))"
echo "   estados finais: pending=$(n_estado pending) rejected=$(n_estado rejected) timed_out=$(n_estado timed_out)"
# a fila drenou: profundidade 0 (amostras durante a rajada, impressas; depois ate 60 s ate zerar)
echo "   profundidade de admission.requests durante a rajada (1 amostra/s):${AMOSTRAS:- (rajada curta demais para amostrar)}"
PROFUNDIDADE=""
for i in $(seq 1 30); do
    PROFUNDIDADE="$(amostra_fila)"
    echo "   admission.requests apos a rajada (amostra $i): ${PROFUNDIDADE:-?}"
    [ "$PROFUNDIDADE" = "0" ] && break
    sleep 2
done
confere "fila admission.requests drenada (profundidade 0)" 0 "$PROFUNDIDADE"
# overselling 0 contra o reservation e o stock REAIS: reservas ativas <= capacidade e eventcap.quant = capacidade - ativas
ATIVAS="$(psql_db "SELECT count(*) FROM reservation.walletevent WHERE event_id = $RAJADA_EVENT_ID AND status IN ('pending', 'confirmed')")"
QUANT="$(psql_db "SELECT quant FROM stock.eventcap WHERE event_id = $RAJADA_EVENT_ID")"
echo "   reservation/stock: capacidade=$RAJADA_CAPACIDADE reservas ativas=$ATIVAS stock.eventcap.quant=$QUANT"
confere "overselling 0: reservas ativas <= capacidade" sim "$([ "$ATIVAS" -le "$RAJADA_CAPACIDADE" ] && echo sim || echo nao)"
confere "stock.eventcap.quant coerente (capacidade - reservas ativas)" "$((RAJADA_CAPACIDADE - ATIVAS))" "$QUANT"
confere "todas as vagas foram dadas (N > capacidade): reservas ativas = capacidade" "$RAJADA_CAPACIDADE" "$ATIVAS"
confere "estados pending = reservas ativas no reservation" "$ATIVAS" "$(n_estado pending)"
# consulta de estado: so o dono (404 igual para o de outro usuario), 400 sem a chave, 401 sem token
http GET "$GW/bff/event-wallets/admission?idempotencyKey=rajada-$SUFIXO-1" "" "$TOKEN"
confere "GET /bff/event-wallets/admission com a chave de OUTRO usuario" 404 "$HTTP_CODE"
http GET "$GW/bff/event-wallets/admission" "" "$TOKEN"
confere "GET /bff/event-wallets/admission sem a chave" 400 "$HTTP_CODE"
http GET "$GW/bff/event-wallets/admission?idempotencyKey=rajada-$SUFIXO-1"
confere "GET /bff/event-wallets/admission sem token" 401 "$HTTP_CODE"
[ "$FALHAS" -eq 0 ] || abortar "a rajada do modo fila falhou antes do replay"

# ---- saga stock + wallet (#47): invariante em SQL (o unico trecho do e2e que le os dois schemas, como o helper dos testes) -------------
viol_invariantes() { # modo (parcial|final) event_id... -> imprime as violacoes (vazio = ok)
    local modo="$1" ids; shift
    ids="$(IFS=,; echo "$*")"
    local saga=""
    [ "$modo" = "final" ] && saga="UNION ALL SELECT 'saga sobrando ' || idempotency_key || ' ' || saga_status FROM reservation.idempotency_key WHERE event_id IN ($ids) AND saga_status IN ('reserving', 'compensating')"
    psql_db "
SELECT 'quant ' || ec.event_id || ': quant=' || ec.quant || ' capacity=' || ec.capacity FROM stock.eventcap ec
  WHERE ec.event_id IN ($ids) AND ec.capacity <> -1 AND ec.quant <> ec.capacity - (SELECT count(*) FROM stock.hold h WHERE h.event_id = ec.event_id AND h.status = 'held' AND h.decremented)
UNION ALL SELECT 'overselling ' || ec.event_id FROM stock.eventcap ec
  WHERE ec.event_id IN ($ids) AND ec.capacity <> -1 AND (SELECT count(*) FROM reservation.walletevent w WHERE w.event_id = ec.event_id AND w.status IN ('pending', 'confirmed')) > ec.capacity
UNION ALL SELECT 'wallet ativo sem hold held ' || w.user_id || ':' || w.event_id FROM reservation.walletevent w
  WHERE w.event_id IN ($ids) AND w.status IN ('pending', 'confirmed') AND NOT EXISTS (SELECT 1 FROM stock.hold h WHERE h.hold_id = w.hold_id AND h.status = 'held')
UNION ALL SELECT 'hold held preso ' || h.hold_id FROM stock.hold h
  WHERE h.event_id IN ($ids) AND h.status = 'held'
    AND NOT EXISTS (SELECT 1 FROM reservation.walletevent w WHERE w.hold_id = h.hold_id AND w.status IN ('pending', 'confirmed'))
    AND NOT EXISTS (SELECT 1 FROM reservation.idempotency_key k WHERE k.hold_id = h.hold_id AND k.saga_status IN ('reserving', 'compensating'))
    AND NOT EXISTS (SELECT 1 FROM reservation.event_store e WHERE e.stream_type = 'reservation.wallet'
        AND e.event_type IN ('ReservationCancelled', 'ReservationExpired') AND e.payload ->> 'holdId' = h.hold_id::text)
$saga" | tr '\n' ';'
}
esperar_invariantes() { # modo timeout_s event_id... -> VIOL (vazio = ok) e INV_S (segundos ate valer)
    local modo="$1" limite="$2" inicio=$SECONDS; shift 2
    while :; do
        VIOL="$(viol_invariantes "$modo" "$@")"
        [ -z "$VIOL" ] && break
        [ $((SECONDS - inicio)) -ge "$limite" ] && break
        sleep 1
    done
    INV_S=$((SECONDS - inicio))
}
esperar_saudavel() { # container_id [timeout_s]
    local fim=$((SECONDS + ${2:-120}))
    while [ "$SECONDS" -lt "$fim" ]; do
        [ "$(dexec inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null | tr -d '\r')" = "healthy" ] && return 0
        sleep 2
    done
    return 1
}
# --- faltas dos passos 4c/4d: compose (docker stop/pause/start) ou cluster (scale, SIGSTOP/SIGCONT a partir do no, SIGKILL no PID pelo no) ------------------
NO_K8S="$PROJETO-control-plane"
esperar_pod_novo() { # app pod_antigo timeout_s (cluster): ate existir um pod Ready de nome diferente do antigo
    local fim=$((SECONDS + $3)) novo
    while [ "$SECONDS" -lt "$fim" ]; do
        novo="$(kc get pods -l "app=$1" -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[?(@.name=="'"$1"'")].ready}{"\n"}{end}' 2>/dev/null | awk -v a="$2" '$1 != a && $2 == "true" {print $1}')"
        [ -n "$novo" ] && return 0
        sleep 2
    done
    return 1
}
esperar_reinicio() { # app pod restartCount_antes timeout_s (cluster): ate o container do pod reiniciar e ficar Ready
    local fim=$((SECONDS + $4)) r
    while [ "$SECONDS" -lt "$fim" ]; do
        r="$(kc get pod "$2" -o jsonpath='{.status.containerStatuses[?(@.name=="'"$1"'")].restartCount} {.status.containerStatuses[?(@.name=="'"$1"'")].ready}' 2>/dev/null)"
        [ "${r%% *}" -gt "$3" ] 2>/dev/null && [ "${r##* }" = "true" ] && return 0
        sleep 2
    done
    return 1
}
pid_no() { # app: PID do processo do container NO NO (de dentro o PID 1 ignora SIGSTOP/SIGKILL de fora do namespace)
    dexec exec "$NO_K8S" sh -c "crictl inspect --output go-template --template '{{.info.pid}}' \$(crictl ps --name '^$1\$' --state running -q | head -1)" | tr -d '\r'
}
stock_fora() {
    if [ "$K8S" = "1" ]; then
        # o stock nao tem ScaledObject (#231, 1 replica fixa): o scale a 0 nao e desfeito por nenhum HPA
        kc scale deploy/stock --replicas=0 >/dev/null || abortar "kubectl scale do stock para 0 falhou"
        for i in $(seq 1 60); do [ -z "$(kc get pods -l app=stock -o name)" ] && break; sleep 2; done
    else
        STOCK_CID="$(dc ps -q stock | head -1)"
        [ -n "$STOCK_CID" ] || abortar "sem container do stock"
        dexec stop "$STOCK_CID" >/dev/null || abortar "docker stop do stock falhou"
    fi
}
stock_volta() {
    if [ "$K8S" = "1" ]; then
        kc scale deploy/stock --replicas=1 >/dev/null || abortar "kubectl scale do stock para 1 falhou"
        kc rollout status deploy/stock --timeout=180s >/dev/null || abortar "o stock nao voltou a ficar pronto"
    else
        dexec start "$STOCK_CID" >/dev/null || abortar "docker start do stock falhou"
        esperar_saudavel "$STOCK_CID" 120 || abortar "o stock nao voltou a ficar healthy"
    fi
}
stock_pausar() {
    if [ "$K8S" = "1" ]; then
        STOCK_PID="$(pid_no stock)"
        [ -n "$STOCK_PID" ] || abortar "sem PID do stock no no (crictl)"
        dexec exec "$NO_K8S" kill -STOP "$STOCK_PID" || abortar "SIGSTOP do stock falhou"
    else
        STOCK_CID="$(dc ps -q stock | head -1)"
        [ -n "$STOCK_CID" ] || abortar "sem container do stock"
        dexec pause "$STOCK_CID" >/dev/null || abortar "docker pause do stock falhou"
    fi
}
stock_retomar() { # $1 = 1 no caminho de erro (so retoma, sem esperar nem abortar)
    if [ "$K8S" = "1" ]; then
        dexec exec "$NO_K8S" kill -CONT "$STOCK_PID" >/dev/null 2>&1 || { [ "${1:-0}" = "1" ] || abortar "SIGCONT do stock falhou"; }
        [ "${1:-0}" = "1" ] || kc rollout status deploy/stock --timeout=120s >/dev/null || abortar "o stock nao voltou a ficar pronto depois do SIGCONT"
    else
        dexec unpause "$STOCK_CID" >/dev/null 2>&1 || { [ "${1:-0}" = "1" ] || abortar "docker unpause do stock falhou"; }
        [ "${1:-0}" = "1" ] || esperar_saudavel "$STOCK_CID" 60 || abortar "o stock nao voltou a ficar healthy depois do unpause"
    fi
}
reservation_queda() {
    if [ "$K8S" = "1" ]; then
        RES_POD="$(kc get pods -l app=reservation -o jsonpath='{.items[0].metadata.name}')"
        [ -n "$RES_POD" ] || abortar "sem pod do reservation"
        RES_RESTARTS="$(kc get pod "$RES_POD" -o jsonpath='{.status.containerStatuses[?(@.name=="reservation")].restartCount}')"
        # SIGKILL no PID do processo, a partir do no (como o `docker stop -t 0`): o kubelet reinicia o container no MESMO pod/rede, e o kernel fecha os sockets
        # do processo morto com os pedidos ja enviados ao stock pausado. (`kubectl delete pod --force` desmonta tambem a rede do pod: os pedidos pendentes no
        # stock pausado eram descartados e a queda nao deixava nenhuma saga orfa para compensar.)
        RES_PID="$(pid_no reservation)"
        [ -n "$RES_PID" ] || { stock_retomar 1; abortar "sem PID do reservation no no (crictl)"; }
        dexec exec "$NO_K8S" kill -9 "$RES_PID" || { stock_retomar 1; abortar "SIGKILL do reservation falhou"; }
    else
        RES_CID="$(dc ps -q reservation | head -1)"
        [ -n "$RES_CID" ] || abortar "sem container do reservation"
        dexec stop -t 0 "$RES_CID" >/dev/null || { stock_retomar 1; abortar "docker stop -t 0 do reservation falhou"; }
    fi
}
reservation_volta() {
    if [ "$K8S" = "1" ]; then
        esperar_reinicio reservation "$RES_POD" "$RES_RESTARTS" 300 || abortar "o reservation nao voltou a ficar pronto"
    else
        dexec start "$RES_CID" >/dev/null || abortar "docker start do reservation falhou"
        esperar_saudavel "$RES_CID" 180 || abortar "o reservation nao voltou a ficar healthy"
    fi
}

echo "==> 4c. saga (a): stock FORA -> 500 generico, saga compensating, quant intacto; o stock volta, o reaper compensa (janela ${SAGA_JANELA_S}s), a mesma chave reserva"
http POST "$GW/bff/events" \
    "{\"event_name\":\"E2E saga a $SUFIXO\",\"is_EAD\":true,\"event_date\":\"2035-12-01T14:00:00\",\"description\":\"e2e #47a\",\"lot_quantity\":5,\"presenters\":[]}" "$TOKEN"
confere "POST /bff/events (evento da saga a, capacidade 5)" 201 "$HTTP_CODE"
SAGA_A_EVENT="$(extrair_num event_id "$HTTP_BODY")"
[ -n "$SAGA_A_EVENT" ] || abortar "sem event_id do evento da saga (a)"
psql_db "UPDATE stock.eventcap SET max_time_in_line = 3600 WHERE event_id = $SAGA_A_EVENT" >/dev/null
CHAVE_A="saga-a-$SUFIXO"
stock_fora
http POST "$GW/bff/event-wallets" "{\"userId\":$USER_ID,\"eventId\":$SAGA_A_EVENT}" "$TOKEN" "Idempotency-Key: $CHAVE_A"
confere "reserva com o stock fora (500 generico no /bff, como com o reservation fora)" 500 "$HTTP_CODE"
confere "saga da chave fica compensating (o release tambem falhou)" compensating \
    "$(psql_db "SELECT saga_status FROM reservation.idempotency_key WHERE user_id = $USER_ID AND idempotency_key = '$CHAVE_A'")"
confere "quant intacto com o stock fora" 5 "$(psql_db "SELECT quant FROM stock.eventcap WHERE event_id = $SAGA_A_EVENT")"
confere "nenhum walletevent do evento" 0 "$(psql_db "SELECT count(*) FROM reservation.walletevent WHERE event_id = $SAGA_A_EVENT")"
confere "invariantes (parcial) com o stock fora" "" "$(viol_invariantes parcial "$SAGA_A_EVENT")"
stock_volta
INICIO_A=$SECONDS
SAGA_A_ST=""
while [ $((SECONDS - INICIO_A)) -le $((SAGA_JANELA_S + 20)) ]; do
    SAGA_A_ST="$(psql_db "SELECT saga_status FROM reservation.idempotency_key WHERE user_id = $USER_ID AND idempotency_key = '$CHAVE_A'")"
    [ "$SAGA_A_ST" = "compensated" ] && break
    sleep 1
done
echo "   saga (a): $SAGA_A_ST ${SAGA_A_ST:+apos $((SECONDS - INICIO_A)) s com o stock de volta} (janela do reaper: ORPHAN_MS=$SAGA_ORPHAN_MS + SCAN_MS=$SAGA_SCAN_MS)"
confere "o reaper deixou a saga compensated depois de ORPHAN_MS + SCAN_MS" compensated "$SAGA_A_ST"
confere "resolvida pelo reaper" reaper "$(psql_db "SELECT resolved_by FROM reservation.idempotency_key WHERE user_id = $USER_ID AND idempotency_key = '$CHAVE_A'")"
confere "hold da saga liberado (lapide)" released \
    "$(psql_db "SELECT h.status FROM stock.hold h JOIN reservation.idempotency_key k ON k.hold_id = h.hold_id WHERE k.user_id = $USER_ID AND k.idempotency_key = '$CHAVE_A'")"
confere "quant intacto depois da compensacao" 5 "$(psql_db "SELECT quant FROM stock.eventcap WHERE event_id = $SAGA_A_EVENT")"
esperar_invariantes final 20 "$SAGA_A_EVENT"
confere "invariantes (final) depois da compensacao" "" "$VIOL"
http POST "$GW/bff/event-wallets" "{\"userId\":$USER_ID,\"eventId\":$SAGA_A_EVENT}" "$TOKEN" "Idempotency-Key: $CHAVE_A"
confere "a mesma chave reserva depois (falha nao consome a chave)" 201 "$HTTP_CODE"
confere "quant depois da reserva" 4 "$(psql_db "SELECT quant FROM stock.eventcap WHERE event_id = $SAGA_A_EVENT")"
esperar_invariantes final 20 "$SAGA_A_EVENT"
confere "invariantes (final) depois da reserva" "" "$VIOL"
# devolucao por evento (D4): cancelar a reserva so appenda ReservationCancelled com o holdId; quem devolve a vaga e o consumer do stock
http DELETE "$GW/bff/event-wallets?userId=$USER_ID&eventId=$SAGA_A_EVENT" "" "$TOKEN"
confere "DELETE /bff/event-wallets (cancelar)" 200 "$HTTP_CODE"
QUANT_A=""
for i in $(seq 1 30); do
    QUANT_A="$(psql_db "SELECT quant FROM stock.eventcap WHERE event_id = $SAGA_A_EVENT")"
    [ "$QUANT_A" = "5" ] && break
    sleep 1
done
confere "o consumer do stock devolveu a vaga do cancelamento (quant volta a 5)" 5 "$QUANT_A"
confere "hold da reserva cancelada liberado" released     "$(psql_db "SELECT h.status FROM stock.hold h JOIN reservation.walletevent w ON w.hold_id = h.hold_id WHERE w.user_id = $USER_ID AND w.event_id = $SAGA_A_EVENT")"
esperar_invariantes final 20 "$SAGA_A_EVENT"
confere "invariantes (final) depois do cancelamento" "" "$VIOL"
[ "$FALHAS" -eq 0 ] || abortar "o passo (a) da saga falhou"

echo "==> 4d. saga (b): queda do reservation (docker stop -t 0) no meio de uma rajada de 20 usuarios; invariante e overselling 0 depois da janela do reaper"
SB_USUARIOS=20
SB_CAPACIDADE=8
SB_SUFIXO="b$SUFIXO"
http POST "$GW/bff/events" \
    "{\"event_name\":\"E2E saga b $SUFIXO\",\"is_EAD\":true,\"event_date\":\"2035-12-01T14:00:00\",\"description\":\"e2e #47b\",\"lot_quantity\":$SB_CAPACIDADE,\"presenters\":[]}" "$TOKEN"
confere "POST /bff/events (evento da saga b, capacidade $SB_CAPACIDADE)" 201 "$HTTP_CODE"
SAGA_B_EVENT="$(extrair_num event_id "$HTTP_BODY")"
[ -n "$SAGA_B_EVENT" ] || abortar "sem event_id do evento da saga (b)"
psql_db "UPDATE stock.eventcap SET max_time_in_line = 3600 WHERE event_id = $SAGA_B_EVENT" >/dev/null
psql_db "INSERT INTO identity.users (user_name, email, fone, password, birthdate)
    SELECT 'Rajada $SB_SUFIXO ' || g, 'rajada-$SB_SUFIXO-' || g || '@example.com', 'rj-$SB_SUFIXO-' || g, (SELECT password FROM identity.users WHERE email = '$EMAIL'), DATE '1990-01-15'
    FROM generate_series(1, $SB_USUARIOS) AS g" >/dev/null
confere "usuarios da rajada (b) criados por SQL" "$SB_USUARIOS" "$(psql_db "SELECT count(*) FROM identity.users WHERE email LIKE 'rajada-$SB_SUFIXO-%@example.com'")"
[ "$FALHAS" -eq 0 ] || abortar "sem os usuarios da rajada (b)"
# A rajada de 20 acaba em ~100 ms, menos que o `docker stop`: sem ajuda a queda pegaria so o fim. Por isso o stock fica PAUSADO (docker pause) ate o
# reservation cair: as sagas ja gravaram a tx1 (reserving) e esperam o hold, sem resposta. Quando o stock volta, ele processa os pedidos de hold que
# ficaram na fila do socket, com o chamador morto: holds `held` sem tx2 (as orfas que o reaper tem de liberar). A queda e o `docker stop -t 0` pedido.
stock_pausar
cli env GW="$GW" EVENT_ID="$SAGA_B_EVENT" SUFIXO="$SB_SUFIXO" N="$SB_USUARIOS" PARALELO="$SB_USUARIOS" bash -s \
    <"$SCRIPT_DIR/rajada-container.sh" >"$TMP/rajada-b.out" 2>&1 &
RAJADA_B_PID=$!
for i in $(seq 1 120); do
    grep -q DISPARANDO "$TMP/rajada-b.out" 2>/dev/null && break
    sleep 0.5
done
grep -q DISPARANDO "$TMP/rajada-b.out" || abortar "a rajada (b) nao chegou a disparar"
sleep 1.5 # as chamadas ao stock pausado ja estao penduradas
echo "   sagas em voo (reserving) antes da queda: $(psql_db "SELECT count(*) FROM reservation.idempotency_key WHERE event_id = $SAGA_B_EVENT AND saga_status = 'reserving'")"
reservation_queda
echo "   reservation derrubado no meio da rajada (SIGKILL)"
stock_retomar
sleep 2 # o stock processa os holds pendentes com o chamador morto
echo "   holds held sem tx2 depois da queda (orfas): $(psql_db "SELECT count(*) FROM stock.hold h WHERE h.event_id = $SAGA_B_EVENT AND h.status = 'held' AND NOT EXISTS (SELECT 1 FROM reservation.walletevent w WHERE w.hold_id = h.hold_id)")"
reservation_volta
echo "   reservation de volta; aguardando o fim da rajada e a janela do reaper (ORPHAN_MS=$SAGA_ORPHAN_MS, SCAN_MS=$SAGA_SCAN_MS)"
wait "$RAJADA_B_PID"
grep -E "CODIGO|ESTADO|SEM_ESTADO_FINAL|RAJADA_MS" "$TMP/rajada-b.out" | sed 's/^/     /'
esperar_invariantes final $((SAGA_JANELA_S + 60)) "$SAGA_B_EVENT"
echo "   invariantes (final) valeram apos ${INV_S} s (limite $((SAGA_JANELA_S + 60)) s)"
confere "invariantes (final): quant = capacidade - holds debitados, sem wallet sem hold, sem hold preso, sem saga sobrando" "" "$VIOL"
ATIVAS_B="$(psql_db "SELECT count(*) FROM reservation.walletevent WHERE event_id = $SAGA_B_EVENT AND status IN ('pending', 'confirmed')")"
QUANT_B="$(psql_db "SELECT quant FROM stock.eventcap WHERE event_id = $SAGA_B_EVENT")"
echo "   saga (b): capacidade=$SB_CAPACIDADE reservas ativas=$ATIVAS_B stock.eventcap.quant=$QUANT_B; sagas: $(psql_db "SELECT coalesce(string_agg(saga_status || '/' || coalesce(resolved_by, '-') || '=' || n, ' '), 'nenhuma') FROM (SELECT saga_status, resolved_by, count(*) AS n FROM reservation.idempotency_key WHERE event_id = $SAGA_B_EVENT GROUP BY 1, 2) t")"
COMPENSADOS_B="$(psql_db "SELECT count(*) FROM stock.hold WHERE event_id = $SAGA_B_EVENT AND status = 'released'")"
echo "   holds liberados no evento (compensacoes inline, do reaper e lapides): $COMPENSADOS_B"
confere "a queda deixou sagas para compensar (pelo menos um hold liberado)" sim "$([ "${COMPENSADOS_B:-0}" -ge 1 ] && echo sim || echo nao)"
confere "overselling 0: reservas ativas <= capacidade" sim "$([ "$ATIVAS_B" -le "$SB_CAPACIDADE" ] && echo sim || echo nao)"
confere "stock.eventcap.quant = capacidade - reservas ativas" "$((SB_CAPACIDADE - ATIVAS_B))" "$QUANT_B"
[ "$FALHAS" -eq 0 ] || abortar "o passo (b) da saga falhou"

echo "==> 5. esperar o backbone escoar (outbox vazio e lag 0 dos consumers) antes do replay"
PENDENTES=""
for i in $(seq 1 45); do
    PENDENTES="$(psql_db "SELECT (SELECT count(*) FROM reservation.event_outbox) + (SELECT count(*) FROM catalog.event_outbox) + (SELECT count(*) FROM identity.event_outbox)")"
    [ "$PENDENTES" = "0" ] && break
    sleep 2
done
confere "linhas pendentes nos 3 outbox (ate 90 s)" 0 "$PENDENTES"
lag() { sexec redpanda rpk group describe "$1" 2>/dev/null | tr -d '\r' | awk '$1 == "TOTAL-LAG" {print $2}'; }
for grupo in reservation stock stock-catalog notification; do
    LAG=""
    for i in $(seq 1 45); do
        LAG="$(lag "$grupo")"
        [ "$LAG" = "0" ] && break
        sleep 2
    done
    confere "TOTAL-LAG do group $grupo (ate 90 s)" 0 "$LAG"
done
[ "$FALHAS" -eq 0 ] || abortar "o backbone nao escoou: o replay compararia com projecao defasada"

echo "==> 5b. 2o up: o group notification JA existe, entao o one-shot notification-seek NAO move offset"
if [ "$K8S" = "1" ]; then
    # no cluster o seek e um initContainer do proprio pod do notification: reiniciar o pod e o "2o up"; o log do initContainer diz que o group ja existia
    NOTIF_POD="$(kc get pods -l app=notification -o jsonpath='{.items[0].metadata.name}')"
    kc delete pod "$NOTIF_POD" --wait=true >/dev/null 2>&1 || abortar "kubectl delete do pod do notification falhou"
    esperar_pod_novo notification "$NOTIF_POD" 300 || abortar "o notification nao voltou a ficar pronto"
    SEEK2="$(kc logs -l app=notification -c seek 2>&1 | tr -d '\r')"
else
SEEK2="$(dc run --rm --no-deps notification-seek 2>&1 | tr -d '\r')"
fi
confere "notification-seek com o group existente" sim "$(grep -q 'offsets mantidos' <<<"$SEEK2" && echo sim || echo nao)"

echo "==> 6. replay (event sourcing): catalog.event e reservation.wallet reconstruidos do log == projecoes vivas"
# Container Maven efemero na rede DO PROJECT (db = o db temporario), SEM relay/consumer/expiracao e sem truncar nada: as classes
# *ReplayE2eTest so leem o log e criam/derrubam o schema-sombra; ficam puladas no run-tests.sh (precisam de E2E_REPLAY=1).
if [ "$K8S" = "1" ]; then
    # No cluster o db so existe na rede do cluster: o Maven roda num POD efemero (mesma imagem auxiliar), com o codigo copiado por tar e o usuario/senha
    # do app lidos do Secret env-db (APP_DB_USER/APP_DB_PASSWORD). As dependencias do Maven vem do repositorio central a cada rodada (sem cache entre rodadas).
    REPLAY_POD="$CLI-replay"
    kc delete pod "$REPLAY_POD" --ignore-not-found >/dev/null 2>&1
    kc apply -f - >/dev/null <<EOF || abortar "nao consegui criar o pod do replay"
apiVersion: v1
kind: Pod
metadata: {name: $REPLAY_POD, labels: {app: e2e-replay, sidecar.istio.io/inject: "false"}}
spec:
  restartPolicy: Never
  enableServiceLinks: false
  automountServiceAccountToken: false
  containers:
    - name: mvn
      image: $IMAGEM_MVN
      imagePullPolicy: IfNotPresent
      command: [sleep, infinity]
      env:
        - {name: SPRING_DATASOURCE_URL, value: "jdbc:postgresql://db:5432/meu_banco"}
        - {name: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE, value: "10"}
        - {name: SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE, value: "1"}
        - name: SPRING_DATASOURCE_USERNAME
          valueFrom: {secretKeyRef: {name: env-db, key: APP_DB_USER}}
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom: {secretKeyRef: {name: env-db, key: APP_DB_PASSWORD}}
        - {name: SPRING_KAFKA_BOOTSTRAP_SERVERS, value: "redpanda:9092"}
        - {name: INTERNAL_API_TOKEN, value: "token-de-teste-nao-e-segredo"}
        - {name: EVENTS_RELAY_ENABLED, value: "false"}
        - {name: EVENTS_CONSUMER_ENABLED, value: "false"}
        - {name: STOCK_CATALOG_RECONCILIATION_ENABLED, value: "false"}
        - {name: STOCK_RELEASE_CONSUMER_ENABLED, value: "false"}
        - {name: RESERVATION_SAGA_REAPER_ENABLED, value: "false"}
        - {name: RESERVATION_EXPIRY_SCAN_MS, value: "3600000"}
        - {name: E2E_REPLAY, value: "1"}
EOF
    kc wait --for=condition=ready "pod/$REPLAY_POD" --timeout=180s >/dev/null || abortar "o pod do replay nao ficou pronto"
    tar -C "$PROJETO_DIR/Backend" --exclude=target -cf - . | kc exec -i "$REPLAY_POD" -- sh -c 'mkdir -p /build && tar -xf - -C /build' || abortar "nao consegui copiar o codigo para o pod do replay"
    kc exec "$REPLAY_POD" -- bash -c '
        cd /build
        mvn -B -pl catalog,reservation -am test             -Dtest="CatalogReplayE2eTest,ReservationReplayE2eTest" -Dsurefire.failIfNoSpecifiedTests=false
    ' 2>&1 | tr -d '\015' >"$TMP/replay.log"
    REPLAY_CODE="${PIPESTATUS[0]}"
    kc delete pod "$REPLAY_POD" --now >/dev/null 2>&1
else
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        HOST_SRC="$(cd "$PROJETO_DIR/Backend" && pwd -W)"
        HOST_ENV_FILE="$(cd "$PROJETO_DIR" && pwd -W)/.env"
        CONTAINER_HOME=/root
        PLATFORM_ARGS=(-v gerenciador-eventos-m2:/root/.m2)
        ;;
    *)
        HOST_SRC="$PROJETO_DIR/Backend"
        HOST_ENV_FILE="$PROJETO_DIR/.env"
        CONTAINER_HOME=/tmp/mvnhome
        PLATFORM_ARGS=(--user "$(id -u):$(id -g)" -e HOME=/tmp/mvnhome -v gerenciador-eventos-m2:/tmp/mvnhome/.m2)
        ;;
esac
dexec run --rm --network "$REDE" --env-file "$HOST_ENV_FILE" "${PLATFORM_ARGS[@]}" \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/meu_banco \
    -e SPRING_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092 \
    -e INTERNAL_API_TOKEN=token-de-teste-nao-e-segredo \
    -e EVENTS_RELAY_ENABLED=false -e EVENTS_CONSUMER_ENABLED=false \
    -e STOCK_CATALOG_RECONCILIATION_ENABLED=false -e STOCK_RELEASE_CONSUMER_ENABLED=false \
    -e RESERVATION_SAGA_REAPER_ENABLED=false -e RESERVATION_EXPIRY_SCAN_MS=3600000 \
    -e E2E_REPLAY=1 -e "MVN_REPO=$CONTAINER_HOME/.m2/repository" \
    -v "$HOST_SRC":/workspace-src:ro \
    "$IMAGEM_MVN" \
    bash -c '
        mkdir -p /tmp/build "$MVN_REPO"
        cp -r /workspace-src/* /tmp/build/ 2>/dev/null || true
        cd /tmp/build
        mvn -B -Dmaven.repo.local="$MVN_REPO" -pl catalog,reservation -am test \
            -Dtest="CatalogReplayE2eTest,ReservationReplayE2eTest" -Dsurefire.failIfNoSpecifiedTests=false
    ' 2>&1 | tr -d '\r' >"$TMP/replay.log"
REPLAY_CODE="${PIPESTATUS[0]}"
fi
grep -E "Tests run:.*(CatalogReplayE2eTest|ReservationReplayE2eTest)|BUILD|ERROR\]" "$TMP/replay.log" | head -20 | sed 's/^/     /'
confere "mvn test das classes de replay (codigo de saida)" 0 "$REPLAY_CODE"
for classe in CatalogReplayE2eTest ReservationReplayE2eTest; do
    confere "$classe rodou 1 teste, 0 falhas, 0 pulados (replay sem divergencia)" sim \
        "$(grep -q "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0.* -- in .*$classe" "$TMP/replay.log" && echo sim || echo nao)"
done

if [ "$K8S" = "1" ]; then
echo "==> 7. balanceamento (trilha $TRILHA_K8S, #197): uma replica NOVA do catalog recebe conexao do gateway?"
# O gateway chama o catalog por UMA conexao em uso (GETs sequenciais abaixo). Com o catalog em 2 replicas: no Service L4 (nenhuma) a conexao do pool continua no
# pod velho e o novo fica sem nada; na trilha c o TTL de 15 s do client-lb refaz a conexao e o DNS round-robin a leva tambem ao pod novo; na b1 o Envoy balanceia
# por requisicao. A medida e o numero de conexoes ESTABLISHED na porta 8083 DENTRO do pod novo (netns do container, lido do no), amostrado a cada 2 s.
CAT_VELHO="$(kc get pods -l app=catalog -o jsonpath='{.items[0].metadata.name}')"
GET_URL="$GW/bff/events/$EVENT_ID"
conexoes_8083() { # pod: conexoes ESTABLISHED na porta 8083 do pod (tcp e tcp6 do netns do container, vistos do no)
    local cid pid
    cid="$(kc get pod "$1" -o jsonpath='{.status.containerStatuses[?(@.name=="catalog")].containerID}' | sed 's#.*://##')"
    pid="$(dexec exec "$NO_K8S" crictl inspect --output go-template --template '{{.info.pid}}' "$cid" | tr -d '\r')"
    [ -n "$pid" ] || { echo 0; return; }
    dexec exec "$NO_K8S" sh -c "cat /proc/$pid/net/tcp /proc/$pid/net/tcp6" | tr -d '\r' \
        | awk -v p="$(printf '%04X' 8083)" '$4 == "01" {n = split($2, a, ":"); if (a[n] == p) c++} END {print c + 0}'
}
confere "GET $GET_URL (o evento do passo 4)" 200 "$(cli curl -s -m 10 -o /dev/null -w '%{http_code}' "$GET_URL")"
# GETs SEQUENCIAIS no gateway (uma conexao do gateway ao catalog em uso) do inicio ao fim do passo, num pod auxiliar sem sidecar; param pelo arquivo /tmp/parar (teto de 600 s).
# Comecam ANTES do scale: a conexao quente nao pode ociosar durante a subida da replica (o pool a evictaria e a proxima abriria uma conexao nova, que o kube-proxy poderia
# levar ao pod novo e estragar o controle).
cli bash -c 'rm -f /tmp/parar; fim=$((SECONDS + 600)); n=0; while [ ! -f /tmp/parar ] && [ "$SECONDS" -lt "$fim" ]; do curl -s -m 5 -o /dev/null "$1"; n=$((n + 1)); done; echo "$n"' bash "$GET_URL" >"$TMP/trafego.out" 2>&1 &
TRAFEGO_PID=$!
sleep 30 # aquecimento: as conexoes ociosas do e2e sao evictadas (evict-idle 20 s) e sobra a que esta em uso
# 2 replicas pelo ScaledObject (o HPA desfaria um `kubectl scale`): min=max=2; no fim volta a 1/1
kc patch scaledobject catalog --type merge -p '{"spec":{"minReplicaCount":2,"maxReplicaCount":2}}' >/dev/null || { cli touch /tmp/parar; abortar "patch do ScaledObject do catalog para 2 falhou"; }
for i in $(seq 1 120); do [ "$(kc get deploy catalog -o jsonpath='{.status.readyReplicas}')" = "2" ] && break; sleep 2; done
confere "catalog com 2 replicas prontas" 2 "$(kc get deploy catalog -o jsonpath='{.status.readyReplicas}')"
[ "$(kc get deploy catalog -o jsonpath='{.status.readyReplicas}')" = "2" ] || { cli touch /tmp/parar; abortar "o catalog nao subiu a 2 replicas"; }
CAT_NOVO="$(kc get pods -l app=catalog -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | tr -d '\r' | grep -vx "$CAT_VELHO" | head -1)"
[ -n "$CAT_NOVO" ] || { cli touch /tmp/parar; abortar "nao achei o pod novo do catalog"; }
# janela de medida: no controle (nenhuma) 45 s fixos, esperando 0; nas trilhas b1 e c ate 120 s, parando na 1a conexao no pod novo (na c o novo so entra quando o TTL de
# 15 s refaz a conexao e o DNS sorteia o pod novo: cada conexao nova tem ~50% de chance, entao a janela e folgada)
JANELA=45; [ "$TRILHA_K8S" = "nenhuma" ] || JANELA=120
MAX_NOVO=0
FIM_JANELA=$((SECONDS + JANELA))
while [ "$SECONDS" -lt "$FIM_JANELA" ]; do
    n="$(conexoes_8083 "$CAT_NOVO")"; [ "${n:-0}" -gt "$MAX_NOVO" ] && MAX_NOVO="$n"
    [ "$TRILHA_K8S" != "nenhuma" ] && [ "$MAX_NOVO" -ge 1 ] && break
    sleep 2
done
cli touch /tmp/parar
wait "$TRAFEGO_PID"
confere "GETs sequenciais durante o passo (requisicoes >= 10)" sim "$([ "$(tr -d '[:space:]' <"$TMP/trafego.out")" -ge 10 ] 2>/dev/null && echo sim || echo nao)"
echo "   conexoes ESTABLISHED do gateway no pod novo do catalog ($CAT_NOVO), maximo amostrado: $MAX_NOVO"
if [ "$TRILHA_K8S" = "nenhuma" ]; then
    confere "controle (Service L4): o pod novo NAO recebeu conexao do gateway em 45 s (o pool manteve a conexao no pod velho)" 0 "$MAX_NOVO"
else
    confere "trilha $TRILHA_K8S: o pod novo RECEBEU conexao do gateway (>= 1)" sim "$([ "$MAX_NOVO" -ge 1 ] && echo sim || echo nao)"
fi
kc patch scaledobject catalog --type merge -p '{"spec":{"minReplicaCount":1,"maxReplicaCount":1}}' >/dev/null 2>&1

echo "==> 8. autoescala (#197, etapa 4): HPAs com metrica lida, metrica do admission e adm:replicas (D4)"
hpas_com_metrica() { kc get hpa -o jsonpath='{range .items[*]}{.status.currentMetrics[0].type}{"\n"}{end}' | grep -c .; }
for i in $(seq 1 60); do [ "$(hpas_com_metrica)" = "6" ] && break; sleep 3; done
confere "HPAs criados pelo KEDA (keda-hpa-<servico>, 6: o stock nao tem)" 6 "$(kc get hpa -o name | grep -c 'keda-hpa-')"
confere "HPAs com a metrica lida (currentMetrics presente: metrics-server e external metrics funcionando)" 6 "$(hpas_com_metrica)"
confere "kubectl top pods (metrics-server responde)" sim "$([ "$(kc top pods --no-headers 2>/dev/null | grep -c .)" -ge 1 ] && echo sim || echo nao)"
# o token SO de metrica (Secret admission-metrics do namespace): vai no argv de um curl efemero (nao e o INTERNAL_API_TOKEN do .env)
TOKEN_METRICA="$(kc get secret admission-metrics -o jsonpath='{.data.token}' | base64 -d)"
HOT_URL="http://admission:8085/internal/metrics/hot"
confere "GET /internal/metrics/hot com o token de metrica" 200 "$(cli curl -s -m 10 -o /dev/null -w '%{http_code}' -H "X-Internal-Token: $TOKEN_METRICA" "$HOT_URL")"
confere "corpo do /internal/metrics/hot traz o rpm inteiro" sim "$([ "$(cli curl -s -m 10 -H "X-Internal-Token: $TOKEN_METRICA" "$HOT_URL" | grep -Ec '"rpm":[0-9]+')" -ge 1 ] && echo sim || echo nao)"
confere "GET /internal/metrics/hot sem token" 401 "$(cli curl -s -m 10 -o /dev/null -w '%{http_code}' "$HOT_URL")"
confere "GET /internal/metrics/hot com token errado" 401 "$(cli curl -s -m 10 -o /dev/null -w '%{http_code}' -H 'X-Internal-Token: errado' "$HOT_URL")"
confere "o token de metrica NAO abre outra rota do admission" 401 \
    "$(cli curl -s -m 10 -o /dev/null -w '%{http_code}' -H "X-Internal-Token: $TOKEN_METRICA" "http://admission:8085/internal/admissions/events/1/rpm")"
# D4: com o reservation em 2 replicas, o admission le readyReplicas pela API do Kubernetes e grava adm:replicas = 2 (o caminho real: ServiceAccount, CA do cluster, RBAC)
adm_replicas() { sexec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli GET adm:replicas' | tr -d '[:space:]'; }
kc patch scaledobject reservation --type merge -p '{"spec":{"minReplicaCount":2,"maxReplicaCount":2}}' >/dev/null || abortar "patch do ScaledObject do reservation para 2 falhou"
for i in $(seq 1 120); do [ "$(kc get deploy reservation -o jsonpath='{.status.readyReplicas}')" = "2" ] && break; sleep 3; done
confere "reservation com 2 replicas prontas (readyReplicas)" 2 "$(kc get deploy reservation -o jsonpath='{.status.readyReplicas}')"
for i in $(seq 1 30); do [ "$(adm_replicas)" = "2" ] && break; sleep 2; done
confere "adm:replicas (gravada pelo admission pela API do Kubernetes) == readyReplicas do reservation" 2 "$(adm_replicas)"
kc patch scaledobject reservation --type merge -p '{"spec":{"minReplicaCount":1,"maxReplicaCount":1}}' >/dev/null 2>&1
for i in $(seq 1 120); do [ "$(kc get deploy reservation -o jsonpath='{.status.readyReplicas}')" = "1" ] && break; sleep 3; done
for i in $(seq 1 30); do [ "$(adm_replicas)" = "1" ] && break; sleep 2; done
confere "reservation de volta a 1 replica e adm:replicas acompanha" "1 1" "$(kc get deploy reservation -o jsonpath='{.status.readyReplicas}') $(adm_replicas)"
fi

echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then echo "   RESULTADO: E2E OK (stack inteira, fluxo, e-mail no captador, replay sem divergencia)"; else echo "   RESULTADO: $FALHAS ASSERCAO(OES) FALHARAM"; fi
echo "=================================================="
[ "$FALHAS" -eq 0 ]
