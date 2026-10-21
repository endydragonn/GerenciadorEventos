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
# Roda o REACTOR inteiro (issues #32 e #33, Fase 7): shared + reservation-client + monolito + notification + reservation, um
# `mvn clean test` na raiz do Backend/.
# Os relatórios Surefire de todos os módulos são copiados para Backend/target/surefire-reports (nomes de classe únicos).
#
# Pré-requisito: a stack no ar pra existir a rede, o banco e o broker (Redpanda; os testes do backbone de
# eventos, issue #27, publicam e consomem nele de verdade)
#   docker compose -f Projeto/docker-compose.yml up -d db redpanda
# O cache do Maven fica no volume nomeado "gerenciador-eventos-m2" (persiste entre
# execuções; não é removido por "docker compose down -v").

echo ""
echo "=================================================="
echo "   🧪 EXECUTANDO TESTES NO DOCKER"
echo "=================================================="
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
    echo "❌ Não encontrei $PROJECT_ROOT/.env (credenciais do banco). Abortando."
    echo ""
    exit 1
fi

# Argumentos que dependem do SO. HOME dentro do container aponta pro cache do m2.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        HOST_SRC="$(cd "$SCRIPT_DIR" && pwd -W)"
        HOST_ENV_FILE="$(cd "$PROJECT_ROOT" && pwd -W)/.env"
        export MSYS_NO_PATHCONV=1
        export MSYS2_ARG_CONV_EXCL='*'
        CONTAINER_HOME=/root
        PLATFORM_ARGS=(-v gerenciador-eventos-m2:/root/.m2)
        ;;
    *)
        HOST_SRC="$SCRIPT_DIR"
        HOST_ENV_FILE="$PROJECT_ROOT/.env"
        CONTAINER_HOME=/tmp/mvnhome
        PLATFORM_ARGS=(
            --user "$(id -u):$(id -g)"
            -e HOME=/tmp/mvnhome
            -v gerenciador-eventos-m2:/tmp/mvnhome/.m2
        )
        ;;
esac

# Os testes batem num Postgres e num Redpanda reais: sem o container no ar, falha cedo e explica.
for CONTAINER in gerenciador-db gerenciador-redpanda; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" != "true" ]; then
        echo "❌ O container $CONTAINER não está no ar. Rode:"
        echo "   docker compose -f Projeto/docker-compose.yml up -d db redpanda"
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
# INTERNAL_API_TOKEN: token de serviço das chamadas internas gateway -> reservation (#33). Valor de TESTE explícito, não é segredo; o
# serviço e o cliente não sobem sem a variável (sem default).
#
# SPRING_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092: o broker interno do compose (o default do application.properties,
# localhost:9092, não existe dentro do container de teste).
# EVENTS_CONSUMER_GROUP=gerenciador-eventos-test: os testes têm group próprio, para nunca entrar (e causar
# rebalance) no group do backend vivo (gerenciador-eventos) que consome do mesmo Redpanda.
docker run --rm --network gerenciador-net --env-file "$HOST_ENV_FILE" \
  "${PLATFORM_ARGS[@]}" \
  -e IDEMPOTENCY_RETENTION_SCAN_MS=3600000 \
  -e NOTIFICATION_EVENTS_RETENTION_SCAN_MS=3600000 \
  -e RESERVATION_READPATH=cas \
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
# catalog, testes do monólito). Os groups do serviço vivo NÃO podem reler isso: `reservation` (projeção de disponibilidade) até
# descartaria (o eventId não está no event_store depois do TRUNCATE), mas um backlog desses custaria minutos; `reservation-catalog`
# (reconciliação do eventcap pelo EventCreated) CRIARIA eventcap para ids de evento de teste, que se repetem depois de um
# TRUNCATE ... RESTART IDENTITY e pegariam a capacidade errada. Move os dois para o FIM dos tópicos (a guarda acima garante que
# estão sem membros), rodando SEMPRE. --allow-new-topics cobre o group/tópico que ainda não existe.
for GRUPO_TOPICO in "reservation:reservation.wallet" "reservation-catalog:catalog.event"; do
    GRUPO="${GRUPO_TOPICO%%:*}"
    TOPICO="${GRUPO_TOPICO##*:}"
    if docker exec gerenciador-redpanda rpk group seek "$GRUPO" --to end --topics "$TOPICO" --allow-new-topics >/dev/null 2>&1; then
        echo "✅ Group $GRUPO movido para o fim de $TOPICO (eventos de teste não chegam ao serviço reservation)."
    else
        echo "⚠️  Não consegui mover o group $GRUPO para o fim de $TOPICO. ANTES de subir o reservation, rode:"
        echo "   docker exec gerenciador-redpanda rpk group seek $GRUPO --to end --topics $TOPICO --allow-new-topics"
    fi
done

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
