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
#    O braço cas é uma linha de referência horizontal (não depende do lag): média de
#    TODAS as suas execuções do grupo, mesmo em lags diferentes:
#      cas/50/20 p95 40,50,60 (lag 1000) + 45 (lag 5000) -> média 48.75 desvio 8.54 mín 40 máx 60
#
#  Segunda fixture (forma da rodada 1b, issue #13): naive em 4 lags (500, 2000, 5000,
#  10000) x N=100, cas só no lag 5000, e um grupo N=200 sem nenhuma linha cas. Prova o eixo x
#  LINEAR (posição proporcional ao lag), a referência do cas cobrindo todo o domínio de lag
#  e o grupo/CSV sem cas (não quebra, não desenha a referência).
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
assert_html "cas = referência horizontal tracejada (não curva)" 'class="ref-cas"'
if grep -qF 'serie-cas' "$HTML"; then falhar "cas não deve ser desenhado como curva por lag (serie-cas)"; else ok "cas não é desenhado como curva por lag"; fi
assert_html "referência do cas: média de todas as execuções do grupo (mesmo em lags diferentes), com desvio e mín/máx" \
    "cas · independe do lag · média 48.75 ms ± 8.54 (mín 40, máx 60) · 4 execuções"
assert_html "legenda do cas diz que independe do lag" '<span class="chave chave-cas"></span>cas · independe do lag'
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

# ---------------------------------------------------------------- 2ª fixture: forma da rodada 1b
echo ""
echo "==> Eixo x linear e referência do cas (fixture da rodada 1b)"
{
    printf '%s\n' "$CAB"
    while IFS= read -r linha; do printf '%s\n' "$linha"; done <<'CSV'
naive,500,100,20,1,22,0,2,10.0,100.0,110.0,100.0,400,400,abc1234
naive,500,100,20,2,24,0,4,10.0,120.0,130.0,100.0,300,300,abc1234
naive,2000,100,20,1,28,0,8,10.0,130.0,140.0,100.0,900,900,abc1234
naive,2000,100,20,2,30,0,10,10.0,150.0,160.0,100.0,1500,1500,abc1234
naive,5000,100,20,1,40,0,20,10.0,160.0,170.0,100.0,2500,2500,abc1234
naive,5000,100,20,2,44,0,24,10.0,180.0,190.0,100.0,4000,4000,abc1234
naive,10000,100,20,1,60,0,40,10.0,200.0,210.0,100.0,5000,5000,abc1234
naive,10000,100,20,2,64,0,44,10.0,220.0,230.0,100.0,9000,9000,abc1234
cas,5000,100,20,1,20,0,0,10.0,60.0,70.0,100.0,2500,2500,abc1234
cas,5000,100,20,2,20,0,0,10.0,80.0,90.0,100.0,4000,4000,abc1234
naive,2000,200,20,1,50,0,30,10.0,300.0,310.0,100.0,900,900,abc1234
naive,10000,200,20,1,110,0,90,10.0,400.0,410.0,100.0,5000,5000,abc1234
CSV
} > "$TMP/matriz-1b.csv"
gerar() {   # $1 = csv  $2 = html de saída (o log vai para $2.log)
    if ! bash "$REPORT" "$1" "$2" >"$2.log" 2>&1; then cat "$2.log" >&2; echo "   ERRO: report-matriz.sh saiu com falha ($1)" >&2; exit 1; fi
}
gerar "$TMP/matriz-1b.csv" "$TMP/1b.html"
H1B="$TMP/1b.html"

painel() {   # $1 = aria-label do painel  $2 = html ; imprime o <svg> inteiro
    awk -v rot="aria-label=\"$1\"" 'index($0, rot) { dentro = 1 } dentro { print } dentro && /<\/svg>/ { exit }' "$2"
}
marcadores() {   # $1 = svg  $2 = braço ; imprime "lag cx cy" por marcador (círculo r=4.5)
    awk -v b="$2" 'index($0, "<g class=\"pt\"><title>" b " ") {
        match($0, /lag [0-9]+ ms/); lag = substr($0, RSTART + 4, RLENGTH - 7)
        match($0, /<circle cx="[0-9.]+" cy="[0-9.]+" r="4.5"/); split(substr($0, RSTART, RLENGTH), p, "\"")
        print lag, p[2], p[4] }' "$1"
}
attr() {   # $1 = linha  $2 = atributo ; imprime o valor (1 por linha de entrada)
    sed -n "s/.* $2=\"\([^\"]*\)\".*/\1/p" <<<"$1"
}
perto() {   # $1 = a  $2 = b  $3 = tolerância relativa ; 0 se |a-b| <= tol*|b|
    awk -v a="$1" -v b="$2" -v t="$3" 'BEGIN { d = a - b; if (d < 0) d = -d; if (b < 0) b = -b; exit !(d <= t * b + 1e-9) }'
}

SVG_O="$TMP/1b-o.svg"; painel "Overselling por lag, N=100, capacity 20" "$H1B" > "$SVG_O"
SVG_P="$TMP/1b-p.svg"; painel "Latência p95 por lag, N=100, capacity 20" "$H1B" > "$SVG_P"
SVG_SEM="$TMP/1b-sem.svg"; painel "Overselling por lag, N=200, capacity 20" "$H1B" > "$SVG_SEM"
[ -s "$SVG_O" ] && [ -s "$SVG_P" ] && [ -s "$SVG_SEM" ] || { echo "   ERRO: painéis da fixture 1b não encontrados" >&2; exit 1; }
n_svg=$(grep -c "<svg" "$H1B")
if [ "$n_svg" -eq 4 ]; then ok "1b: 4 painéis SVG (2 grupos x [oversell, p95])"; else falhar "1b: esperava 4 <svg>, achei $n_svg"; fi

# eixo x linear: posição do marcador do naive = origem + k * lag (a origem é o início da linha de base = lag 0)
ORIGEM=$(attr "$(grep -m1 'class="baseline"' "$SVG_O")" x1)
FIM=$(attr "$(grep -m1 'class="baseline"' "$SVG_O")" x2)
mapfile -t MN < <(marcadores "$SVG_O" naive)
if [ "${#MN[@]}" -eq 4 ]; then ok "1b: 4 marcadores do naive (um por lag)"; else falhar "1b: esperava 4 marcadores naive, achei ${#MN[@]}"; fi
declare -A XN=()
for m in "${MN[@]}"; do set -- $m; XN[$1]=$2; done
if [ -n "${XN[500]:-}" ] && [ -n "${XN[2000]:-}" ] && [ -n "${XN[5000]:-}" ] && [ -n "${XN[10000]:-}" ]; then
    # razão das distâncias: (10000-5000)/(5000-2000) = 5/3 e (2000-500)/(5000-2000) = 1/2 (eixo ordinal daria 1 e 1)
    r1=$(awk -v a="${XN[10000]}" -v b="${XN[5000]}" -v c="${XN[2000]}" 'BEGIN { printf "%.4f", (a - b) / (b - c) }')
    r2=$(awk -v a="${XN[2000]}" -v b="${XN[500]}" -v c="${XN[5000]}" 'BEGIN { printf "%.4f", (a - b) / (c - a) }')
    if perto "$r1" 1.6667 0.01; then ok "1b: (x10000-x5000)/(x5000-x2000) = $r1 (esperado 5/3: eixo linear)"; else falhar "1b: razão (10000-5000)/(5000-2000) = $r1, esperado 1.6667 (eixo linear)"; fi
    if perto "$r2" 0.5 0.01; then ok "1b: (x2000-x500)/(x5000-x2000) = $r2 (esperado 1/2)"; else falhar "1b: razão (2000-500)/(5000-2000) = $r2, esperado 0.5"; fi
    # proporcional a partir da origem (lag 0): (x - origem) / lag constante, e o maior lag fica na ponta da linha de base
    k1=$(awk -v x="${XN[500]}" -v o="$ORIGEM" 'BEGIN { printf "%.6f", (x - o) / 500 }')
    k2=$(awk -v x="${XN[10000]}" -v o="$ORIGEM" 'BEGIN { printf "%.6f", (x - o) / 10000 }')
    if perto "$k1" "$k2" 0.01; then ok "1b: (x - origem)/lag constante (posição proporcional ao lag, origem = lag 0)"; else falhar "1b: (x-origem)/lag = $k1 (500) e $k2 (10000): não proporcional"; fi
    if perto "${XN[10000]}" "$FIM" 0.001; then ok "1b: o maior lag fica na ponta direita da linha de base"; else falhar "1b: x(10000)=${XN[10000]} difere do fim da linha de base ($FIM)"; fi
    # tick do eixo x no mesmo x do marcador
    for lag in 500 2000 5000 10000; do
        tx=$(grep "class=\"eixo eixo-x\"" "$SVG_O" | grep ">$lag</text>" | head -1)
        if [ -n "$tx" ] && perto "$(attr "$tx" x)" "${XN[$lag]}" 0.001; then ok "1b: rótulo do eixo x '$lag' alinhado ao marcador"; else falhar "1b: rótulo do eixo x '$lag' ausente ou fora do x do marcador (${XN[$lag]})"; fi
    done
    # a curva (polyline) usa os mesmos x
    pl=$(grep 'class="serie serie-naive"' "$SVG_O" | head -1)
    esp=""; for lag in 500 2000 5000 10000; do esp="$esp ${XN[$lag]}"; done
    got=$(attr "$pl" points | tr ' ' '\n' | awk -F, 'NF { printf " %s", $1 }')
    ok_pl=1; i=0; for g in $got; do i=$((i + 1)); e=$(echo $esp | cut -d' ' -f$i); perto "$g" "$e" 0.001 || ok_pl=0; done
    if [ "$ok_pl" -eq 1 ] && [ "$i" -eq 4 ]; then ok "1b: a polyline do naive passa pelos mesmos x dos marcadores"; else falhar "1b: polyline do naive em x diferentes dos marcadores (got:$got esperado:$esp)"; fi
else
    falhar "1b: faltam marcadores do naive em algum lag (500/2000/5000/10000)"
fi

# referência do cas: horizontal, cobre todo o domínio de lag (x1 = origem, x2 = maior lag), na altura da média do cas
for painel_svg in "$SVG_O" "$SVG_P"; do
    nome=$([ "$painel_svg" = "$SVG_O" ] && echo oversell || echo p95)
    ref=$(grep 'class="ref-cas"' "$painel_svg" | head -1)
    if [ -z "$ref" ]; then falhar "1b/$nome: sem a linha de referência do cas (ref-cas)"; continue; fi
    rx1=$(attr "$ref" x1); rx2=$(attr "$ref" x2); ry1=$(attr "$ref" y1); ry2=$(attr "$ref" y2)
    if perto "$rx1" "$ORIGEM" 0.001 && perto "$rx2" "$FIM" 0.001; then ok "1b/$nome: a referência do cas cobre todo o domínio de lag (de 0 ao maior lag)"; else falhar "1b/$nome: referência em x $rx1..$rx2, esperado $ORIGEM..$FIM"; fi
    if [ "$ry1" = "$ry2" ]; then ok "1b/$nome: a referência do cas é horizontal"; else falhar "1b/$nome: referência inclinada (y1=$ry1, y2=$ry2)"; fi
    mc=$(marcadores "$painel_svg" cas)
    if [ "$(echo "$mc" | grep -c .)" -eq 1 ] && [ "$(echo "$mc" | cut -d' ' -f1)" = 5000 ]; then
        cy=$(echo "$mc" | cut -d' ' -f3)
        if perto "$ry1" "$cy" 0.001; then ok "1b/$nome: a referência está na altura do único marcador do cas (lag 5000)"; else falhar "1b/$nome: referência em y=$ry1, marcador do cas em y=$cy"; fi
    else
        falhar "1b/$nome: esperava 1 marcador do cas (lag 5000), achei: $mc"
    fi
    if grep -qF 'stroke-dasharray' <<<"$ref"; then ok "1b/$nome: referência tracejada"; else falhar "1b/$nome: referência sem stroke-dasharray"; fi
done
if grep -qF 'cas · independe do lag · média 70.00 ms ± 14.14 (mín 60, máx 80) · 2 execuções' "$SVG_P"; then ok "1b/p95: tooltip da referência com média ± desvio (mín, máx)"; else falhar "1b/p95: tooltip da referência do cas ausente ou errado"; fi
if grep -qF 'cas · independe do lag · média 0.00 ± 0.00 (mín 0, máx 0) · 2 execuções' "$SVG_O"; then ok "1b/oversell: tooltip da referência com média ± desvio (mín, máx)"; else falhar "1b/oversell: tooltip da referência do cas ausente ou errado"; fi

# grupo sem nenhuma linha cas (N=200): só o naive, sem referência e sem quebrar
if grep -qF 'ref-cas' "$SVG_SEM"; then falhar "1b: grupo sem cas não deve desenhar a referência"; else ok "1b: grupo sem cas (N=200) não desenha a referência"; fi
if grep -qF 'class="serie serie-naive"' "$SVG_SEM"; then ok "1b: grupo sem cas ainda desenha a curva do naive"; else falhar "1b: grupo sem cas perdeu a curva do naive"; fi

# CSV só com naive (nenhum cas): não quebra, não desenha referência nem legenda do cas
grep -v '^cas,' "$TMP/matriz-1b.csv" > "$TMP/so-naive.csv"
gerar "$TMP/so-naive.csv" "$TMP/so-naive.html"
if grep -qF 'ref-cas' "$TMP/so-naive.html" || grep -qF 'independe do lag' "$TMP/so-naive.html"; then falhar "CSV sem cas: não deve ter referência nem legenda do cas"; else ok "CSV sem nenhuma linha cas: gráfico só do naive, sem referência"; fi
if grep -qF 'class="serie serie-naive"' "$TMP/so-naive.html"; then ok "CSV sem cas: curva do naive presente"; else falhar "CSV sem cas: faltou a curva do naive"; fi

# CSV só com cas (um lag): a referência ocupa todo o domínio, sem divisão por zero
awk 'NR == 1 || /^cas,/' "$TMP/matriz-1b.csv" > "$TMP/so-cas.csv"
gerar "$TMP/so-cas.csv" "$TMP/so-cas.html"
if grep -qF 'class="ref-cas"' "$TMP/so-cas.html" && ! grep -qiE '\bnan\b|\binf\b' "$TMP/so-cas.html"; then ok "CSV só com cas (1 lag): referência desenhada, sem nan/inf"; else falhar "CSV só com cas: sem referência ou com nan/inf"; fi

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
