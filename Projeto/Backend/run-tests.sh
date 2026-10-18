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
# Estica o intervalo do job ReadModelRefreshService (@Scheduled) para 1h durante a suíte.
# Esse job roda em TODOS os contextos Spring em cache (só fechados no fim do JVM) contra o
# mesmo Postgres, então um refresh disparado pelo contexto de um teste contaminaria o
# read model event_availability que outro teste observa. Os testes chamam refresh() na mão.
# (Mesma ideia do @TestPropertySource de ReservationExpiryTest, aqui para toda a suíte.)
#
# IDEMPOTENCY_RETENTION_SCAN_MS=3600000: mesma razão para o job IdempotencyKeyRetentionService (#130) —
# um contexto em cache apagando chaves vencidas concorreria com os testes que envelhecem linhas na mão.
#
# RESERVATION_READPATH=cas: pina o caminho de reserva no default previsível para a suíte
# inteira, mesmo que o Projeto/.env tenha sido setado para "naive" (experimento #11/#12) —
# senão os testes CAS (ex.: EventWalletTest.testEventoLotado) quebrariam.
#
# SPRING_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092: o broker interno do compose (o default do application.properties,
# localhost:9092, não existe dentro do container de teste).
# EVENTS_CONSUMER_GROUP=gerenciador-eventos-test: os testes têm group próprio, para nunca entrar (e causar
# rebalance) no group do backend vivo (gerenciador-eventos) que consome do mesmo Redpanda.
docker run --rm --network gerenciador-net --env-file "$HOST_ENV_FILE" \
  "${PLATFORM_ARGS[@]}" \
  -e READMODEL_AVAILABILITY_REFRESH_MS=3600000 \
  -e IDEMPOTENCY_RETENTION_SCAN_MS=3600000 \
  -e RESERVATION_READPATH=cas \
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
    mvn -q -Dmaven.repo.local="$MVN_REPO" clean test
    TEST_CODE=$?

    # Copiar apenas relatórios para o volume de saída
    mkdir -p /workspace-output/surefire-reports
    cp -r target/surefire-reports/* /workspace-output/surefire-reports/ 2>/dev/null || true

    exit $TEST_CODE
  '

TEST_EXIT_CODE=$?

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
