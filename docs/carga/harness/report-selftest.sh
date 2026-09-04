#!/bin/bash

# =============================================================================
#  report-selftest.sh · auto-teste de report.sh (issue #11)
# =============================================================================
#
#  report.sh tem lógica não trivial (agregação de percentis/throughput/lag,
#  formatação, decisão de cores/eixos) e não depende de infra externa (não
#  bate em HTTP nem no banco) -- então, diferente de setup.sh/collector.sh/
#  verify.sh (que só fazem sentido contra a stack real), ele pode e deve ser
#  testado com uma fixture sintética pequena e conhecida, no mesmo espírito
#  de docs/testes-manuais/overselling-sob-concorrencia.sh: as asserções ABAIXO
#  são o teste, o script sai com código != 0 se algo bater errado.
#
#  Fixture (valores conhecidos, calculados à mão):
#    k6 summary.json: http_req_duration p(50)=100.0 p(95)=200.0 p(99)=300.0;
#                      iterations rate=12.5
#    verify.csv:       capacity=4 confirmadas=4 pendentes=0 veredito=OK
#    lag.csv:          4 amostras lag_ms: 100, 200, -1 (sentinela), 300
#                       -> amostras=4 (conta o sentinela), max=300.0 (ignora
#                       o sentinela), media=(100+200+300)/3=200.0
#
#  Uso: docs/carga/harness/report-selftest.sh
#  (não precisa de docker/stack no ar -- só do próprio report.sh e de awk)
#
#  Red -> green (documentado, não deixado para o leitor confiar às cegas):
#  rode uma vez com REPRODUZIR_FALHA=1 para ver as asserções de p(95) e de
#  lag_max pegarem um valor deliberadamente errado, depois confira que sem a
#  variável elas passam. Ex.: REPRODUZIR_FALHA=1 docs/carga/harness/report-selftest.sh
# =============================================================================

set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_SH="$DIR/report.sh"
REPRODUZIR_FALHA="${REPRODUZIR_FALHA:-0}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FALHAS=0

ok() { echo "   [ok]    $1"; }
falhar() { echo "   [FALHA] $1"; FALHAS=$((FALHAS + 1)); }

# ---------------------------------------------------------------------------
#  Fixture
# ---------------------------------------------------------------------------
P95_FIXTURE="200.0"
if [ "$REPRODUZIR_FALHA" = "1" ]; then
    # assinatura de falha proposital: p(95) errado no fixture do k6 -> a
    # asserção "latencia_p95,200.0,ms" abaixo tem que pegar isso.
    P95_FIXTURE="999.0"
fi

cat > "$TMP/summary.json" <<JSON
{
    "metrics": {
        "http_req_duration": {
            "avg": 150.0,
            "min": 90.0,
            "med": 140.0,
            "max": 310.0,
            "p(50)": 100.0,
            "p(95)": ${P95_FIXTURE},
            "p(99)": 300.0,
            "count": 40
        },
        "iterations": {
            "count": 40,
            "rate": 12.5
        }
    }
}
JSON

cat > "$TMP/verify.csv" <<CSV
metrica,valor
capacity,4
confirmadas,4
pendentes,0
veredito_corretude,OK
CSV

cat > "$TMP/lag.csv" <<CSV
metrica,timestamp_epoch,valor
lag_ms,1000,100
lag_ms,1001,200
lag_ms,1002,-1
lag_ms,1003,300
CSV

# ---------------------------------------------------------------------------
#  Executa report.sh contra a fixture
# ---------------------------------------------------------------------------
OUT_CSV="$TMP/resultado.csv"
OUT_HTML="$TMP/resultado.html"

echo ""
echo "==> Rodando report.sh contra a fixture sintética (REPRODUZIR_FALHA=$REPRODUZIR_FALHA)"
if ! "$REPORT_SH" \
    --summary "$TMP/summary.json" \
    --verify "$TMP/verify.csv" \
    --lag "$TMP/lag.csv" \
    --n-vus 4 \
    --readpath cas \
    --out-csv "$OUT_CSV" \
    --out-html "$OUT_HTML" >"$TMP/report.log" 2>&1; then
    cat "$TMP/report.log" >&2
    echo "   ERRO: report.sh saiu com falha" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
#  Asserções — cada linha esperada tem que existir literalmente no CSV final
# ---------------------------------------------------------------------------
echo ""
echo "==> Asserções"

assert_linha() {   # $1 = linha exata esperada em $OUT_CSV
    if grep -qxF "$1" "$OUT_CSV"; then
        ok "linha presente: $1"
    else
        falhar "linha ausente/diferente: '$1' (ver $OUT_CSV)"
    fi
}

assert_linha "n_vus,4,vus"
assert_linha "capacity,4,vagas"
assert_linha "confirmadas,4,contagem"
assert_linha "pendentes,0,contagem"
assert_linha "veredito_corretude,OK,texto"
assert_linha "latencia_p50,100.0,ms"
assert_linha "latencia_p95,200.0,ms"
assert_linha "latencia_p99,300.0,ms"
assert_linha "throughput,12.50,reservas/s"
assert_linha "lag_amostras,4,contagem"
assert_linha "lag_leituras_validas,3,contagem"
assert_linha "lag_max,300.0,ms"
assert_linha "lag_medio,200.0,ms"

if [ -s "$OUT_HTML" ] && grep -q "<svg" "$OUT_HTML"; then
    ok "HTML gerado com pelo menos 1 <svg>"
else
    falhar "HTML não gerado ou sem <svg>"
fi
if grep -q "OK" "$OUT_HTML" && grep -q "status-good" "$OUT_HTML"; then
    ok "veredito OK usa a cor de status 'good'"
else
    falhar "veredito OK não usou a cor de status esperada"
fi

echo ""
echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then
    echo "   RESULTADO: OK -- report.sh compôs a fixture corretamente."
    if [ "$REPRODUZIR_FALHA" = "1" ]; then
        echo "   (inesperado: REPRODUZIR_FALHA=1 deveria ter feito a asserção"
        echo "    de latencia_p95 falhar -- ver se a fixture foi mesmo alterada)"
        FALHAS=1
    fi
else
    echo "   RESULTADO: $FALHAS ASSERCAO(OES) FALHARAM."
    if [ "$REPRODUZIR_FALHA" = "1" ]; then
        echo "   (esperado -- REPRODUZIR_FALHA=1 reproduz a assinatura de falha:"
        echo "    p(95) do fixture = 999.0, a asserção 'latencia_p95,200.0,ms'"
        echo "    não bate. Rode sem REPRODUZIR_FALHA para ver o green.)"
    fi
fi
echo "=================================================="
echo ""

if [ "$REPRODUZIR_FALHA" = "1" ]; then
    # aqui a falha É o resultado esperado (prova que a asserção pega o erro).
    exit $(( FALHAS > 0 ? 0 : 1 ))
fi
exit $(( FALHAS > 0 ? 1 : 0 ))
