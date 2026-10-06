#!/bin/bash

# =============================================================================
#  report-matriz-selftest.sh · auto-teste de report-matriz.sh (issue #11)
# =============================================================================
#
#  report-matriz.sh lê o CSV da matriz do experimento (Projeto/carga/results/
#  matriz.csv, uma linha por execução) e gera o gráfico HTML/SVG. Aqui ele é
#  exercitado com uma fixture sintética pequena, com valores calculados à mão;
#  as asserções são o teste (código de saída != 0 se algo não bater).
#
#  Fixture (escrita com BOM + CRLF, como o PowerShell 5.1 costuma gravar):
#    2 braços (cas, naive) x 2 lags (1000, 5000) x 2 N (50, 100), capacity 20,
#    mais 1 linha naive/1000/N=50 com capacity 40 (outro grupo: o CSV acumula
#    execuções, e capacities diferentes NÃO podem ser misturadas na média).
#    Agregados esperados por (braço, lag, N, capacity) -- desvio = amostral (n-1):
#      naive/1000/50  oversell 10,20,30 -> média 20.00 desvio 10.00 mín 10 máx 30
#      naive/5000/50  oversell 40,50    -> média 45.00 desvio 7.07  mín 40 máx 50
#      naive/1000/100 oversell 70,80    -> média 75.00 desvio 7.07  mín 70 máx 80
#      cas/1000/50    oversell 0,0,0    -> média 0.00  desvio 0.00
#                     lag_obs_disparo 800,300,-1 -> média das válidas 550.0
#                     (o sentinela -1 = "sem leitura" é ignorado)
#      cas/5000/50    1 repetição só    -> desvio "–" (não há dispersão com n=1)
#      naive/1000/50/capacity 40  1 repetição, oversell 5 -> grupo à parte
#
#  Uso: docs/carga/harness/report-matriz-selftest.sh   (só bash/awk; sem stack)
# =============================================================================

set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT="$DIR/report-matriz.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FALHAS=0

ok() { echo "   [ok]    $1"; }
falhar() { echo "   [FALHA] $1"; FALHAS=$((FALHAS + 1)); }

CAB="braco,lag_ms,N,capacity,rep,confirmed,pending,oversell,p50,p95,p99,throughput,lag_obs_disparo,lag_obs_max,commit"
{
    printf '\357\273\277%s\r\n' "$CAB"
    while IFS= read -r linha; do printf '%s\r\n' "$linha"; done <<'CSV'
cas,1000,50,20,1,20,0,0,10.0,40.0,50.0,100.0,800,900,abc1234
cas,1000,50,20,2,20,2,0,10.0,50.0,60.0,100.0,300,420,abc1234
cas,1000,50,20,3,20,0,0,10.0,60.0,70.0,100.0,-1,-1,abc1234
cas,5000,50,20,1,20,0,0,10.0,45.0,55.0,100.0,2000,2100,abc1234
naive,1000,50,20,1,30,0,10,10.0,100.0,110.0,100.0,-1,-1,abc1234
naive,1000,50,20,2,40,0,20,10.0,120.0,130.0,100.0,-1,-1,abc1234
naive,1000,50,20,3,50,0,30,10.0,110.0,120.0,100.0,-1,-1,abc1234
naive,5000,50,20,1,60,0,40,10.0,90.0,100.0,100.0,-1,-1,abc1234
naive,5000,50,20,2,70,0,50,10.0,100.0,110.0,100.0,-1,-1,abc1234
cas,1000,100,20,1,20,0,0,10.0,70.0,80.0,100.0,500,600,abc1234
cas,1000,100,20,2,20,0,0,10.0,80.0,90.0,100.0,500,600,abc1234
naive,1000,100,20,1,90,0,70,10.0,150.0,160.0,100.0,-1,-1,abc1234
naive,1000,100,20,2,100,0,80,10.0,150.0,160.0,100.0,-1,-1,abc1234
naive,1000,50,40,1,45,0,5,10.0,130.0,140.0,100.0,-1,-1,abc1234
CSV
} > "$TMP/matriz.csv"

echo ""
echo "==> Rodando report-matriz.sh contra a fixture sintética"
if ! bash "$REPORT" "$TMP/matriz.csv" "$TMP/matriz.html" >"$TMP/report.log" 2>&1; then
    cat "$TMP/report.log" >&2
    echo "   ERRO: report-matriz.sh saiu com falha" >&2
    exit 1
fi
HTML="$TMP/matriz.html"

echo ""
echo "==> Asserções"

assert_html() {   # $1 = descrição  $2 = trecho literal esperado no HTML
    if grep -qF -- "$2" "$HTML"; then ok "$1"; else falhar "$1 (faltou: $2)"; fi
}

# tabela (a "table view" do gráfico) -- agregados por (braço, lag, N, capacity)
assert_html "naive/1000/50/20: média, desvio, mín, máx, p95 médio, ativas médias (sem misturar a capacity 40)" \
    "<td>naive</td><td>1000</td><td>50</td><td>20</td><td>3</td><td>20.00</td><td>10.00</td><td>10</td><td>30</td><td>110.0</td><td>40.00</td>"
assert_html "naive/5000/50/20: desvio amostral (n-1)" \
    "<td>naive</td><td>5000</td><td>50</td><td>20</td><td>2</td><td>45.00</td><td>7.07</td><td>40</td><td>50</td><td>95.0</td><td>65.00</td>"
assert_html "naive/1000/100/20: segundo N agregado à parte" \
    "<td>naive</td><td>1000</td><td>100</td><td>20</td><td>2</td><td>75.00</td><td>7.07</td><td>70</td><td>80</td><td>150.0</td><td>95.00</td>"
assert_html "cas/1000/50/20: oversell 0, ativas = confirmed+pending, lag observado ignora o sentinela -1" \
    "<td>cas</td><td>1000</td><td>50</td><td>20</td><td>3</td><td>0.00</td><td>0.00</td><td>0</td><td>0</td><td>50.0</td><td>20.67</td><td>550.0</td><td>660.0</td></tr>"
assert_html "cas/5000/50/20: com 1 repetição o desvio é '–'" \
    "<td>cas</td><td>5000</td><td>50</td><td>20</td><td>1</td><td>0.00</td><td>–</td><td>0</td><td>0</td><td>45.0</td><td>20.00</td><td>2000.0</td><td>2100.0</td></tr>"
assert_html "naive sem nenhuma leitura de lag: '–' (não 0)" \
    "<td>110.0</td><td>40.00</td><td>–</td><td>–</td></tr>"
assert_html "naive/1000/50/40: capacity diferente = grupo à parte (1 repetição)" \
    "<td>naive</td><td>1000</td><td>50</td><td>40</td><td>1</td><td>5.00</td><td>–</td><td>5</td><td>5</td><td>130.0</td><td>45.00</td><td>–</td><td>–</td></tr>"

# gráficos: 1 linha de painéis por (N, capacity) -- 50/20, 50/40 e 100/20 --, cada uma com oversell + p95
n_svg=$(grep -c "<svg" "$HTML")
if [ "$n_svg" -eq 6 ]; then ok "6 painéis SVG (3 grupos (N, capacity) x [oversell, p95])"; else falhar "esperava 6 <svg>, achei $n_svg"; fi
assert_html "painel de oversell rotulado"   'aria-label="Overselling por lag, N=50, capacity 20"'
assert_html "painel do grupo capacity 40"   'aria-label="Overselling por lag, N=50, capacity 40"'
assert_html "painel de p95 rotulado"        'aria-label="Latência p95 por lag, N=100, capacity 20"'
assert_html "curva do braço cas (série 1)"   'class="serie serie-cas"'
assert_html "curva do braço naive (série 2)" 'class="serie serie-naive"'
assert_html "cor da série 1 por token"       'stroke="var(--series-1)"'
assert_html "cor da série 2 por token"       'stroke="var(--series-2)"'
assert_html "tooltip nativo por ponto"       '<title>naive · lag 1000 ms · N=50'
assert_html "legenda dos dois braços"        '<span class="chave chave-cas"></span>cas'
assert_html "eixo x com os lags"             '>5000</text>'
assert_html "commit lido do CSV na legenda"  'abc1234'
assert_html "barra de ±desvio (traço grosso)" 'stroke-width="5" stroke-opacity=".35"'
assert_html "linha fina mín–máx (path com tampas)" '<path d="M'
assert_html "tokens de cor no :root (o fundo do body também troca no escuro)" ':root:where(:not([data-theme="light"])) {'

# entradas inválidas falham alto
printf 'braco,lag_ms\r\ncas,1000\r\n' > "$TMP/ruim.csv"
if bash "$REPORT" "$TMP/ruim.csv" "$TMP/ruim.html" >/dev/null 2>&1; then
    falhar "CSV sem as colunas obrigatórias deveria falhar"
else
    ok "CSV sem as colunas obrigatórias falha com código != 0"
fi
if bash "$REPORT" "$TMP/nao-existe.csv" "$TMP/x.html" >/dev/null 2>&1; then
    falhar "CSV inexistente deveria falhar"
else
    ok "CSV inexistente falha com código != 0"
fi

echo ""
echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then
    echo "   RESULTADO: OK -- report-matriz.sh agregou a fixture corretamente."
else
    echo "   RESULTADO: $FALHAS ASSERCAO(OES) FALHARAM."
fi
echo "=================================================="
echo ""
exit $(( FALHAS > 0 ? 1 : 0 ))
