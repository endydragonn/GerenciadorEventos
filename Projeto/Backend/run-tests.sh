#!/bin/bash

# Script para rodar testes totalmente isolado no Docker
echo ""
echo "=================================================="
echo "   🧪 EXECUTANDO TESTES NO DOCKER"
echo "=================================================="
echo ""

# Rodar tudo dentro do Docker, copiando fonte para /tmp dentro do container
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
docker run --rm --network gerenciador-net --env-file "$PROJECT_ROOT/.env" \
  -v "$SCRIPT_DIR":/workspace-src:ro \
  -v "$SCRIPT_DIR/target":/workspace-output \
  maven:3.9.7-eclipse-temurin-21-jammy \
  bash -c '
    # Copiar código fonte para /tmp dentro do container
    mkdir -p /tmp/build
    cp -r /workspace-src/* /tmp/build/ 2>/dev/null || true
    cd /tmp/build
    
    # Executar testes (todo download fica dentro do container)
    mvn -q clean test
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
if [ -d "target/surefire-reports" ]; then
    echo ""
    
    TOTAL_TESTS=0
    TOTAL_FAILURES=0
    TOTAL_ERRORS=0
    TOTAL_SKIPPED=0
    TOTAL_TIME=0
    
    # Coletar estatísticas de cada classe de teste
    for file in target/surefire-reports/TEST-*.xml; do
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
            # Acumular tempo usando bc para suportar decimais
            TIME_CLEAN=$(echo "$TIME" | tr ',' '.')
            TOTAL_TIME=$(echo "$TOTAL_TIME + $TIME_CLEAN" | bc -l)
            
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
