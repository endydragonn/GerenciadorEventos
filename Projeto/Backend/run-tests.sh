#!/bin/bash

# Roda a suíte de testes totalmente isolada no Docker (container Maven efêmero +
# Postgres real na rede gerenciador-net).
#
# Funciona em Linux/macOS e no Git Bash do Windows (Docker Desktop):
#  - em Linux/macOS os caminhos do host vão como estão e o container roda com o
#    uid do host, pra não deixar target/ como root;
#  - no Git Bash o binário do Docker é nativo do Windows e não entende "/e/...",
#    então usamos "pwd -W" (-> "E:/...") e desligamos a conversão de path do MSYS.
#
# Roda o REACTOR inteiro (issues #32, #33, #35, #36, #41, #42 e #47, Fase 7): shared + reservation-client + stock-client + catalog-client + identity-client + admission-client +
# monolito + notification + reservation + stock + catalog + identity + admission, um `mvn clean test` na raiz do Backend/.
# Os relatórios Surefire de todos os módulos são copiados para Backend/target/surefire-reports (nomes de classe únicos).
#
# Pré-requisito: a stack no ar pra existir a rede, o banco, o broker (Redpanda; os testes do backbone de
# eventos, issue #27, publicam e consomem nele de verdade), o Redis (os testes do contador do admission, #41, usam o real) e o RabbitMQ
# (os testes da fila de admissão e do worker pool, #42, também)
#   docker compose -f Projeto/docker-compose.yml up -d db redpanda redis rabbitmq
# O compose recusa subir sem REDIS_PASSWORD, RABBITMQ_USER e RABBITMQ_PASSWORD (Projeto/.env, ou exportadas na shell só para o comando).
# Se a variável estiver exportada AQUI também, o container de teste usa essa (-e REDIS_PASSWORD, -e RABBITMQ_USER, -e RABBITMQ_PASSWORD);
# senão usa a do --env-file (Projeto/.env).
# O cache do Maven fica no volume nomeado "gerenciador-eventos-m2" (persiste entre
# execuções; não é removido por "docker compose down -v").

echo ""
echo "=================================================="
echo "   🧪 EXECUTANDO TESTES NO DOCKER"
echo "=================================================="
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Banco dos testes (#47): por padrão o meu_banco de sempre. Um banco do mesmo container criado com os init-scripts da árvore atual
# (ex.: TEST_DB_NAME=meu_banco_s5) deixa o meu_banco intocado, no schema que a stack viva espera. Vale para as conferências de
# schema por psql abaixo E para o container de teste (SPRING_DATASOURCE_URL do .env com meu_banco trocado por este nome; a URL nunca é
# impressa). O resto (broker, Redis, volume da chave do identity) é compartilhado.
TEST_DB_NAME="${TEST_DB_NAME:-meu_banco}"
if ! [[ "$TEST_DB_NAME" =~ ^[a-z][a-z0-9_]*$ ]]; then
    echo "❌ TEST_DB_NAME inválido: '$TEST_DB_NAME' (esperado [a-z][a-z0-9_]*)."
    echo ""
    exit 1
fi
if [ "$TEST_DB_NAME" != "meu_banco" ]; then
    echo "ℹ️  Testes no banco $TEST_DB_NAME (o meu_banco não é tocado)."
fi

# ENV_FILE (opcional): caminho de OUTRO .env, para quem roda de uma worktree sem o .env (ele é gitignored). Padrão: Projeto/.env.
ENV_FILE="${ENV_FILE:-$PROJECT_ROOT/.env}"
if [ ! -f "$ENV_FILE" ]; then
    echo "❌ Não encontrei $ENV_FILE (credenciais do banco). Abortando."
    echo ""
    exit 1
fi

# Argumentos que dependem do SO. HOME dentro do container aponta pro cache do m2.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        HOST_SRC="$(cd "$SCRIPT_DIR" && pwd -W)"
        HOST_ENV_FILE="$(cd "$(dirname "$ENV_FILE")" && pwd -W)/$(basename "$ENV_FILE")"
        export MSYS_NO_PATHCONV=1
        export MSYS2_ARG_CONV_EXCL='*'
        CONTAINER_HOME=/root
        PLATFORM_ARGS=(-v gerenciador-eventos-m2:/root/.m2)
        ;;
    *)
        HOST_SRC="$SCRIPT_DIR"
        HOST_ENV_FILE="$ENV_FILE"
        CONTAINER_HOME=/tmp/mvnhome
        PLATFORM_ARGS=(
            --user "$(id -u):$(id -g)"
            -e HOME=/tmp/mvnhome
            -v gerenciador-eventos-m2:/tmp/mvnhome/.m2
        )
        ;;
esac

# Os testes batem num Postgres, num Redpanda, num Redis e num RabbitMQ reais: sem o container no ar, falha cedo e explica.
for CONTAINER in gerenciador-db gerenciador-redpanda gerenciador-redis gerenciador-rabbitmq; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" != "true" ]; then
        echo "❌ O container $CONTAINER não está no ar. Rode (o compose exige REDIS_PASSWORD, RABBITMQ_USER e RABBITMQ_PASSWORD no Projeto/.env ou exportadas na shell):"
        echo "   docker compose -f Projeto/docker-compose.yml up -d db redpanda redis rabbitmq"
        echo ""
        exit 1
    fi
done

# O relay e o consumer do gerenciador-backend (ligados no compose desde a #28/#29) compartilham o meu_banco com os testes:
# drenariam o event_outbox deles e moveriam a projecao de disponibilidade, quebrando o ReservationReplayTest e a prova de
# overselling do braco naive (que depende do consumer parado). Falha cedo em vez de dar vermelho misterioso.
if [ -n "$(docker ps -q -f name=^gerenciador-backend$)" ]; then
    echo "❌ O container gerenciador-backend está rodando: o relay/consumer dele disputam o banco e o Redpanda dos testes."
    echo "   Pare-o antes: docker stop gerenciador-backend"
    echo ""
    exit 1
fi

# O serviço notification (#32) consome o tópico identity.notification: o relay dos testes do monólito publica lá, e um
# notification vivo mandaria e-mail de verdade (SMTP real) para os endereços de teste. Falha cedo, como no backend.
if [ -n "$(docker ps -q -f name=^gerenciador-notification$)" ]; then
    echo "❌ O container gerenciador-notification está rodando: ele consumiria o tópico identity.notification dos testes e mandaria e-mail de verdade."
    echo "   Pare-o antes: docker stop gerenciador-notification"
    echo ""
    exit 1
fi

# O serviço reservation (#33) tem relay, consumer e reconciliação ligados no compose: dividiria o banco e o Redpanda dos testes
# (drenaria o reservation.event_outbox deles, moveria a projeção de disponibilidade e criaria eventcap a partir dos EventCreated
# de teste). Falha cedo, como no backend e no notification.
if [ -n "$(docker ps -q -f name=^gerenciador-reservation$)" ]; then
    echo "❌ O container gerenciador-reservation está rodando: o relay/consumer dele disputam o banco e o Redpanda dos testes."
    echo "   Pare-o antes: docker stop gerenciador-reservation"
    echo ""
    exit 1
fi

# O serviço stock (#47) tem o consumer de devolução (group stock, reservation.wallet) e a reconciliação (stock-catalog, catalog.event) ligados
# no compose: dividiria o banco e o Redpanda dos testes (devolveria vaga de holds de teste e criaria eventcap para ids de evento de teste).
# Falha cedo, como nos outros serviços.
if [ -n "$(docker ps -q -f name=^gerenciador-stock$)" ]; then
    echo "❌ O container gerenciador-stock está rodando: os consumers dele disputam o banco e o Redpanda dos testes."
    echo "   Pare-o antes: docker stop gerenciador-stock"
    echo ""
    exit 1
fi

# O serviço catalog (#35) tem o relay do catalog.event_outbox ligado no compose: drenaria o outbox dos testes do módulo catalog
# (quebrando as provas de outbox/replay) e dividiria o banco deles. Falha cedo, como nos outros serviços.
if [ -n "$(docker ps -q -f name=^gerenciador-catalog$)" ]; then
    echo "❌ O container gerenciador-catalog está rodando: o relay dele disputa o banco e o Redpanda dos testes."
    echo "   Pare-o antes: docker stop gerenciador-catalog"
    echo ""
    exit 1
fi

# O serviço identity (#36) tem relay do identity.event_outbox ligado no compose (drenaria o outbox dos testes do módulo identity) e divide o
# banco deles. Falha cedo, como nos outros serviços.
if [ -n "$(docker ps -q -f name=^gerenciador-identity$)" ]; then
    echo "❌ O container gerenciador-identity está rodando: o relay dele disputa o banco e o Redpanda dos testes."
    echo "   Pare-o antes: docker stop gerenciador-identity"
    echo ""
    exit 1
fi

# O serviço admission (#41/#42) conta e guarda a fila no MESMO Redis dos testes (chaves adm:*): vivo, ele contaria tentativas de teste e
# apagaria resultados deles (e os ids de evento se repetem depois do TRUNCATE ... RESTART IDENTITY). As filas do RabbitMQ dos testes têm
# prefixo próprio (nunca as admission.* do serviço), então o broker não colide. Falha cedo, como nos outros serviços.
if [ -n "$(docker ps -q -f name=^gerenciador-admission$)" ]; then
    echo "❌ O container gerenciador-admission está rodando: ele dividiria o Redis dos testes (as filas do RabbitMQ dos testes têm prefixo próprio)."
    echo "   Pare-o antes: docker stop gerenciador-admission"
    echo ""
    exit 1
fi

# Réplicas da autoescala (#111, docker-compose.autoescala.yml): sem container_name fixo (<project>-<serviço>-<n>), então as guardas por nome acima
# não as veem. Mesmas razões, achadas pelo label do compose (project padrão gerenciador-eventos; o do e2e, gerenciador-e2e, tem banco e broker
# próprios e não conta).
REPLICAS_VIVAS="$(docker ps --filter "label=com.docker.compose.project=gerenciador-eventos" \
    --filter "label=com.docker.compose.service" --format '{{.Names}} {{.Label "com.docker.compose.service"}}' \
    | awk '$2 ~ /^(backend|notification|reservation|stock|catalog|identity|admission|lb)$/ {print $1}' | tr '\n' ' ')"
if [ -n "$REPLICAS_VIVAS" ]; then
    echo "❌ Há containers de serviço rodando (réplicas e lb da autoescala, sem o nome fixo gerenciador-*): $REPLICAS_VIVAS"
    echo "   Eles disputam o banco, o Redpanda e o Redis dos testes. Pare o scaler (Projeto/autoescala/scaler.sh, Ctrl+C) e os containers: docker stop $REPLICAS_VIVAS"
    echo ""
    exit 1
fi

# Redis dos testes: FLUSHDB no INÍCIO e ao FIM da rodada (verde ou vermelha). O admission está parado (guarda acima), então nada de
# produção vive nesse banco; as chaves do contador usam ids de evento que o RESTART IDENTITY recicla, e uma contagem de uma rodada
# antiga seria lida como carga da seguinte. A senha vem do ambiente do próprio container do Redis (compose), nunca da linha de comando.
limpar_redis() {
    docker exec gerenciador-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli FLUSHDB' 2>&1 | tr -d '\r'
}
if [ "$(limpar_redis)" != "OK" ]; then
    echo "❌ Não consegui dar FLUSHDB no gerenciador-redis (senha? o container foi criado com REDIS_PASSWORD?)."
    echo ""
    exit 1
fi

# Os testes dão TRUNCATE ... RESTART IDENTITY em identity.users do meu_banco (GlobalDbTruncator): o user_id volta a 1. A chave de assinatura
# do JWT do identity vive no volume identity-keys e SOBREVIVE à rodada, então um token emitido antes (localStorage do Flutter, kit de
# carga) passaria a valer como o usuário novo que ganhar o mesmo uid. Apaga a chave no INÍCIO (cobre uma rodada interrompida antes do fim)
# e de novo ao FIM (verde ou vermelha). O identity e o backend estão parados, pelas guardas acima: o próximo boot do identity gera outra
# chave, e o backend, que guarda o JWKS em cache por até 24 h, já está parado (cache vazio quando subir). Sem volume (stack nunca subiu) não
# há o que apagar.
VOLUME_CHAVES="gerenciador-eventos_identity-keys"
apagar_chave_identity() {
    if docker volume inspect "$VOLUME_CHAVES" >/dev/null 2>&1; then
        if docker run --rm -v "$VOLUME_CHAVES":/k maven:3.9.7-eclipse-temurin-21-jammy rm -f /k/jwt-signing-key.json >/dev/null 2>&1; then
            echo "✅ Chave de assinatura do identity apagada do volume $VOLUME_CHAVES (tokens antigos deixam de valer; relogue depois de subir a stack)."
        else
            echo "⚠️  Não consegui apagar a chave do identity. ANTES de subir identity e backend, rode:"
            echo "   docker run --rm -v $VOLUME_CHAVES:/k maven:3.9.7-eclipse-temurin-21-jammy rm -f /k/jwt-signing-key.json"
        fi
    fi
}

# Início da rodada: tokens de antes dela não podem valer depois do TRUNCATE dos testes.
apagar_chave_identity

# Schema do serviço catalog (#35): bancos novos já nascem com ele (supabase-app-init.sql); um volume existente precisa da migração
# 20261026 (move public.event/eventpresenter e o stream catalog.event para o schema catalog, preservando a identity de event_id).
# O script NÃO aplica o 14-catalog.sql: num banco não migrado ele criaria catalog.event vazia ao lado de public.event. Só confere o
# estado e aborta com o caminho da migração. O usuário do app vem do ambiente do próprio container (APP_DB_USER).
ESTADO_CATALOG="$(echo "SELECT to_regclass('public.event') IS NULL, to_regclass('catalog.event') IS NOT NULL;" \
    | docker exec -i -e TEST_DB_NAME="$TEST_DB_NAME" gerenciador-db sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$TEST_DB_NAME" -At' 2>&1)"
if [ "$ESTADO_CATALOG" != "t|t" ]; then
    echo "❌ O banco não está no schema do serviço catalog (public.event ainda existe ou catalog.event não existe; psql: $ESTADO_CATALOG)."
    echo "   Pare o backend e o catalog, faça o backup e aplique a migração (ver o cabeçalho do arquivo):"
    echo "   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 \\"
    echo "     < Projeto/Backend/db/migrations/20261026-catalog-schema.sql"
    echo ""
    exit 1
fi

# Schema do serviço identity (#36): bancos novos já nascem com ele (supabase-app-init.sql); um volume existente precisa da migração
# 20261028 (move public.users/mywallet/pending_verifications e o stream identity.notification para o schema identity, preservando os
# user_id e a identity). O script NÃO aplica o 15-identity.sql: num banco não migrado ele criaria identity.users vazia ao lado de
# public.users (e o serviço identity, que também recusa subir assim, rodaria os testes sobre tabelas que não são as do app). Só confere o
# estado e aborta com o caminho da migração; a saída do psql entra na mensagem (nada de esconder o erro).
ESTADO_IDENTITY="$(echo "SELECT to_regclass('public.users') IS NULL, to_regclass('identity.users') IS NOT NULL;" \
    | docker exec -i -e TEST_DB_NAME="$TEST_DB_NAME" gerenciador-db sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$TEST_DB_NAME" -At' 2>&1)"
if [ "$ESTADO_IDENTITY" != "t|t" ]; then
    echo "❌ O banco não está no schema do serviço identity (public.users ainda existe ou identity.users não existe; psql: $ESTADO_IDENTITY)."
    echo "   Pare o backend e o identity, faça o backup e aplique a migração (ver o cabeçalho do arquivo):"
    echo "   docker exec gerenciador-db pg_dump -h localhost -U admin --clean --if-exists -n public meu_banco > <fora do repo>/meu_banco-public.sql"
    echo "   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco -v ON_ERROR_STOP=1 \\"
    echo "     < Projeto/Backend/db/migrations/20261028-identity-schema.sql"
    echo ""
    exit 1
fi

# Schema do serviço stock (#47): bancos novos já nascem com ele (supabase-app-init.sql, 16-stock.sql); o banco de teste precisa tê-lo
# (a migração de um banco existente fica para a issue de entrega da saga). Sem esta conferência os testes do módulo stock caem com
# "relation stock.eventcap does not exist". Só confere e aborta; não aplica nada.
ESTADO_STOCK="$(echo "SELECT to_regclass('stock.hold') IS NOT NULL;" \
    | docker exec -i -e TEST_DB_NAME="$TEST_DB_NAME" gerenciador-db sh -c 'psql -h localhost -U "$APP_DB_USER" -d "$TEST_DB_NAME" -At' 2>&1)"
if [ "$ESTADO_STOCK" != "t" ]; then
    echo "❌ O banco de teste ($TEST_DB_NAME) não tem o schema do serviço stock (stock.hold não existe; psql: $ESTADO_STOCK)."
    echo "   Use TEST_DB_NAME=<banco criado com Projeto/Backend/db/init-scripts/16-stock.sql> (ver o cabeçalho do arquivo)."
    echo ""
    exit 1
fi

REPORTS_DIR="$SCRIPT_DIR/target/surefire-reports"

# Apaga só os relatórios antigos (não o target/ inteiro): o container copia os XMLs novos por
# cima, então o de uma classe renomeada/movida/removida sobraria e inflaria o total do resumo.
# Se não conseguir apagar (ex.: pasta criada como root numa execução antiga em Linux), aborta
# com mensagem clara — seguir com XML velho faria o resumo mentir sem ninguém perceber.
if ! rm -rf "$REPORTS_DIR"; then
    echo "❌ Não consegui apagar $REPORTS_DIR (provável dono root de uma execução antiga)."
    echo "   Rode: sudo rm -rf \"$REPORTS_DIR\" e execute de novo."
    echo ""
    exit 1
fi

# Cria o diretório de saída ANTES do bind-mount (senão o Docker o cria como root).
mkdir -p "$SCRIPT_DIR/target"

# Rodar tudo dentro do Docker, copiando fonte para /tmp dentro do container
# IDEMPOTENCY_RETENTION_SCAN_MS=3600000: mesma razão para o job IdempotencyKeyRetentionService (#130) —
# um contexto em cache apagando chaves vencidas concorreria com os testes que envelhecem linhas na mão.
#
# NOTIFICATION_EVENTS_RETENTION_SCAN_MS=3600000: idem para o job que apaga notificações vencidas do event_store (#32) — os testes
# dele chamam purgeExpired() na mão, e um contexto em cache apagando por timer concorreria com eles.
#
# RESERVATION_READPATH=cas: o gateway (monólito) decide o braço da reserva por esta flag; pina o default previsível para a suíte
# inteira, mesmo que o Projeto/.env tenha sido setado para "naive" (experimento #11/#12) — senão os testes CAS do gateway
# (ex.: EventWalletGatewayCasTest) quebrariam.
#
# INTERNAL_API_TOKEN: token de serviço das chamadas internas gateway -> reservation (#33) e gateway -> catalog (#35). Valor de
# TESTE explícito, não é segredo; os serviços e os clientes não sobem sem a variável (sem default).
#
# REDIS_HOST=redis: o Redis do compose na rede gerenciador-net (o default do admission, localhost, não existe dentro do container de teste).
# REDIS_PASSWORD: repassada da shell com -e SÓ se estiver exportada (senão vale a do --env-file, Projeto/.env).
#
# RABBITMQ_HOST=rabbitmq: o RabbitMQ do compose na rede gerenciador-net (#42; o default do admission, localhost, não existe dentro do container
# de teste). RABBITMQ_USER e RABBITMQ_PASSWORD: repassadas da shell com -e SÓ se estiverem exportadas (senão valem as do --env-file).
#
# SPRING_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092: o broker interno do compose (o default do application.properties,
# localhost:9092, não existe dentro do container de teste).
# EVENTS_CONSUMER_GROUP=gerenciador-eventos-test: os testes têm group próprio, para nunca entrar (e causar
# rebalance) no group do backend vivo (gerenciador-eventos) que consome do mesmo Redpanda.
REDIS_ARGS=(-e REDIS_HOST=redis -e RABBITMQ_HOST=rabbitmq)
if [ -n "${REDIS_PASSWORD:-}" ]; then
    REDIS_ARGS+=(-e REDIS_PASSWORD)
fi
for VARIAVEL in RABBITMQ_USER RABBITMQ_PASSWORD; do
    if [ -n "${!VARIAVEL:-}" ]; then
        REDIS_ARGS+=(-e "$VARIAVEL")
    fi
done

docker run --rm --network gerenciador-net --env-file "$HOST_ENV_FILE" \
  "${PLATFORM_ARGS[@]}" \
  "${REDIS_ARGS[@]}" \
  -e IDEMPOTENCY_RETENTION_SCAN_MS=3600000 \
  -e NOTIFICATION_EVENTS_RETENTION_SCAN_MS=3600000 \
  -e RESERVATION_READPATH=cas \
  -e "TEST_DB_NAME=$TEST_DB_NAME" \
  -e INTERNAL_API_TOKEN=token-de-teste-nao-e-segredo \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092 \
  -e EVENTS_CONSUMER_GROUP=gerenciador-eventos-test \
  -e "MVN_REPO=$CONTAINER_HOME/.m2/repository" \
  -v "$HOST_SRC":/workspace-src:ro \
  -v "$HOST_SRC/target":/workspace-output \
  maven:3.9.7-eclipse-temurin-21-jammy \
  bash -c '
    # Copiar código fonte para /tmp dentro do container
    mkdir -p /tmp/build "$MVN_REPO"
    cp -r /workspace-src/* /tmp/build/ 2>/dev/null || true
    cd /tmp/build

    # Banco dos testes (#47): troca /meu_banco por /TEST_DB_NAME na URL do .env (substituição de string, só o segmento do caminho; a
    # URL nunca é impressa). ABORTA se a troca não pegar ou se a URL ainda apontar para o meu_banco: cada módulo dá TRUNCATE nos
    # schemas dele, e o meu_banco ficaria exposto. Casamento LITERAL (aspas): sem "?" como glob.
    if [ "$TEST_DB_NAME" != "meu_banco" ]; then
        export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL/\/meu_banco//$TEST_DB_NAME}"
        case "$SPRING_DATASOURCE_URL" in
            */"meu_banco"|*/"meu_banco?"*)
                echo "❌ A URL do banco de teste ainda aponta para o meu_banco. Abortando."; exit 99 ;;
            */"$TEST_DB_NAME"|*/"$TEST_DB_NAME?"*) ;;
            *) echo "❌ A URL do banco de teste não aponta para $TEST_DB_NAME (SPRING_DATASOURCE_URL ausente ou sem /meu_banco). Abortando."; exit 99 ;;
        esac
    fi

    # Executar testes (todo download fica no volume de cache do m2)
    mvn -q -fae -Dmaven.repo.local="$MVN_REPO" clean test
    TEST_CODE=$?

    # Copiar apenas relatórios (de todos os módulos) para o volume de saída
    mkdir -p /workspace-output/surefire-reports
    find . -path "*/target/surefire-reports/TEST-*.xml" -exec cp {} /workspace-output/surefire-reports/ \; 2>/dev/null

    exit $TEST_CODE
  '

TEST_EXIT_CODE=$?

# Os testes publicam em identity.notification (NotificationConsumerTest direto, os do monólito pelo relay) e o group `notification`
# do serviço vivo tem offset atrás ou nem existe (e começa em earliest): sem isto, o próximo `up` do notification mandaria e-mail
# REAL para cada endereço de teste. Move o group para o FIM do tópico, rodando SEMPRE (testes verdes ou não). A guarda acima
# garante que o group está sem membros. Trade-off: um e-mail real que estivesse pendente antes da rodada é pulado (aceitável em
# dev: o backend precisa estar parado para testar). --allow-new-topics cobre o group que ainda não existe.
if docker exec gerenciador-redpanda rpk group seek notification --to end --topics identity.notification --allow-new-topics >/dev/null 2>&1; then
    echo "✅ Group notification movido para o fim de identity.notification (eventos de teste não viram e-mail)."
else
    echo "⚠️  Não consegui mover o group notification para o fim do tópico. ANTES de subir o notification, rode:"
    echo "   docker exec gerenciador-redpanda rpk group seek notification --to end --topics identity.notification --allow-new-topics"
fi

# Os testes também publicam em reservation.wallet (relay do serviço reservation, testes do módulo) e em catalog.event (relay do
# serviço catalog, testes do módulo catalog). Os groups do serviço vivo NÃO podem reler isso: `reservation` (projeção de disponibilidade) até
# descartaria (o eventId não está no event_store depois do TRUNCATE), mas um backlog desses custaria minutos; `reservation-catalog`
# (reconciliação do eventcap pelo EventCreated) CRIARIA eventcap para ids de evento de teste, que se repetem depois de um
# TRUNCATE ... RESTART IDENTITY e pegariam a capacidade errada. Move os dois para o FIM dos tópicos (a guarda acima garante que
# estão sem membros), rodando SEMPRE. --allow-new-topics cobre o group/tópico que ainda não existe. Desde a #47 também os groups do serviço
# stock: `stock` (devolução de vaga; relida do tópico, liberaria holds que os testes já truncaram, sem efeito mas com backlog) e `stock-catalog`
# (reconciliação do eventcap do stock, mesma razão do `reservation-catalog`). O `reservation-catalog` segue até a passada que o remove.
for GRUPO_TOPICO in "reservation:reservation.wallet" "reservation-catalog:catalog.event" "stock:reservation.wallet" "stock-catalog:catalog.event"; do
    GRUPO="${GRUPO_TOPICO%%:*}"
    TOPICO="${GRUPO_TOPICO##*:}"
    if docker exec gerenciador-redpanda rpk group seek "$GRUPO" --to end --topics "$TOPICO" --allow-new-topics >/dev/null 2>&1; then
        echo "✅ Group $GRUPO movido para o fim de $TOPICO (eventos de teste não chegam ao serviço dono do group)."
    else
        echo "⚠️  Não consegui mover o group $GRUPO para o fim de $TOPICO. ANTES de subir o serviço dono do group, rode:"
        echo "   docker exec gerenciador-redpanda rpk group seek $GRUPO --to end --topics $TOPICO --allow-new-topics"
    fi
done

apagar_chave_identity

# FLUSHDB do fim da rodada: as contagens dos testes não ficam para a próxima (nem para o admission, quando subir).
if [ "$(limpar_redis)" = "OK" ]; then
    echo "✅ Redis dos testes zerado (FLUSHDB)."
else
    echo "⚠️  Não consegui dar FLUSHDB no gerenciador-redis ao fim da rodada. ANTES de subir o admission, rode:"
    echo "   docker exec gerenciador-redis sh -c 'REDISCLI_AUTH=\"\$REDIS_PASSWORD\" redis-cli FLUSHDB'"
fi

echo ""
echo "=================================================="
echo "   📊 RESUMO DOS TESTES"
echo "=================================================="

# Extrair informações dos relatórios Surefire
if [ -d "$REPORTS_DIR" ]; then
    echo ""

    TOTAL_TESTS=0
    TOTAL_FAILURES=0
    TOTAL_ERRORS=0
    TOTAL_SKIPPED=0
    TOTAL_TIME=0

    # Coletar estatísticas de cada classe de teste
    for file in "$REPORTS_DIR"/TEST-*.xml; do
        if [ -f "$file" ]; then
            TEST_CLASS=$(basename "$file" .xml | sed 's/TEST-//' | sed 's/com.gerenciador.eventos.//')
            TESTS=$(grep -oP 'tests="\K[0-9]+' "$file" | head -1)
            FAILURES=$(grep -oP 'failures="\K[0-9]+' "$file" | head -1)
            ERRORS=$(grep -oP 'errors="\K[0-9]+' "$file" | head -1)
            SKIPPED=$(grep -oP 'skipped="\K[0-9]+' "$file" | head -1)
            TIME=$(grep -oP 'time="\K[0-9.]+' "$file" | head -1)

            TOTAL_TESTS=$((TOTAL_TESTS + TESTS))
            TOTAL_FAILURES=$((TOTAL_FAILURES + FAILURES))
            TOTAL_ERRORS=$((TOTAL_ERRORS + ERRORS))
            TOTAL_SKIPPED=$((TOTAL_SKIPPED + SKIPPED))
            # Acumular tempo com awk (POSIX; bc pode não existir num Linux enxuto)
            TIME_CLEAN=$(echo "$TIME" | tr ',' '.')
            TOTAL_TIME=$(awk "BEGIN{printf \"%.3f\", ${TOTAL_TIME:-0} + ${TIME_CLEAN:-0}}")

            if [ "$FAILURES" == "0" ] && [ "$ERRORS" == "0" ]; then
                echo "  ✅ $TEST_CLASS: $TESTS testes (${TIME}s)"
            else
                echo "  ❌ $TEST_CLASS: $TESTS testes - $FAILURES falhas, $ERRORS erros (${TIME}s)"
            fi
        fi
    done

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "📊 TOTAL:"
    echo "   • Testes: $TOTAL_TESTS"
    TOTAL_SUCCESS=$((TOTAL_TESTS - TOTAL_FAILURES - TOTAL_ERRORS - TOTAL_SKIPPED))
    echo "   • ✅ Sucesso: $TOTAL_SUCCESS"
    if [ $TOTAL_FAILURES -gt 0 ]; then
        echo "   • ❌ Falhas: $TOTAL_FAILURES"
    fi
    if [ $TOTAL_ERRORS -gt 0 ]; then
        echo "   • ⚠️  Erros: $TOTAL_ERRORS"
    fi
    if [ $TOTAL_SKIPPED -gt 0 ]; then
        echo "   • ⊘ Ignorados: $TOTAL_SKIPPED"
    fi
    echo "   • ⏱️  Tempo: ${TOTAL_TIME}s"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
else
    echo ""
    echo "⚠️  Nenhum relatório de teste encontrado."
fi

echo ""
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "✅ RESULTADO: TODOS OS TESTES PASSARAM!"
else
    echo "❌ RESULTADO: ALGUNS TESTES FALHARAM!"
fi
echo "=================================================="
echo ""

exit $TEST_EXIT_CODE
