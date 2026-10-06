#!/bin/bash

# =============================================================================
#  report-degraus-selftest.sh · auto-teste de report-degraus.sh (issue #14)
# =============================================================================
#
#  report-degraus.sh lê o CSV da escada de carga (Projeto/carga/results/
#  degraus-<id>.csv, uma linha por degrau) e decide o limiar X; aqui ele roda contra
#  fixtures sintéticas pequenas, uma por regra. As asserções são o teste (código de
#  saída != 0 se algo não bater).
#
#  Regras (constantes no report: 5xx 1%, p95 1000 ms, tolerância do RPM efetivo 5%):
#    X = primeiro degrau com taxa_5xx > 1% OU p95_max_janela_30s > 1000 ms
#        (os limites são ESTRITOS: exatamente 1% e exatamente 1000 ms NÃO disparam)
#    gerador saturado = iteracoes_descartadas > 0 OU rpm_efetivo < 95% do alvo
#        (exatamente 95% NÃO é saturação)
#    saturação ESTRITAMENTE antes do degrau X  -> X é só LIMITE INFERIOR (= último degrau sem saturação)
#    saturação no próprio degrau X             -> X firme, mas com o aviso de que o gerador pode ter contribuído
#    saturação no 1º degrau                    -> sem base (nem limite inferior)
#    nenhum degrau degradou                    -> X acima do último degrau
#
#  Fixtures (A..G: 4-5 degraus de 12000 em passos de 4000; as linhas saudáveis têm
#  rpm_efetivo = alvo, 0 descartadas, 0% de 5xx e p95 de janela de 25 ms):
#    A  5xx > 1% no degrau 20000                              -> X firme (5xx)
#    B  p95 da janela 1250 ms com p95 do degrau 600 ms        -> X firme (p95; prova que a janela decide);
#       16000 com janela = exatamente 1000 ms não é X
#    C  descartadas no 20000 e 5xx no 24000                   -> limite inferior 16000
#    D  nenhum degradou (um degrau com exatamente 1% e 1000 ms) -> X acima do último
#    E  5xx e descartadas no MESMO degrau 20000               -> X firme com aviso de saturação
#    F  16000 com rpm_efetivo = exatamente 95% (não satura); 20000 com 18999 (satura), 24000 com janela de 2 s
#                                                             -> limite inferior 16000 (saturou em 20000)
#    G  saturação no 1º degrau                                -> sem base
#    H  degraus 12000, 16000, 20000, 32000                    -> eixo x LINEAR (posição proporcional ao RPM)
#
#  Uso: docs/carga/harness/report-degraus-selftest.sh   (só bash/awk; sem stack)
# =============================================================================

set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT="$DIR/report-degraus.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FALHAS=0

ok() { echo "   [ok]    $1"; }
falhar() { echo "   [FALHA] $1"; FALHAS=$((FALHAS + 1)); }

CAB="escada,rpm_alvo,rpm_efetivo,duracao_s,requisicoes,erros_5xx,taxa_5xx,outros,p50,p95,p99,p95_max_janela_30s,throughput,iteracoes_descartadas,braco,max_connections,pool_hikari,commit"

# linha: L <escada> <alvo> <efetivo> <taxa_5xx> <outros> <p95> <janela> <descartadas>
L() {
    printf '%s,%s,%s,60,1000,0,%s,%s,10.0,%s,30.0,%s,%s.0,%s,cas,300,1000,abc1234\n' "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$(($3 / 60))" "$8"
}
# degrau saudável: S <escada> <alvo>
S() { L "$1" "$2" "$2" 0.000000 0 20.0 25.0 0; }

fixture() {   # $1 = nome ; lê as linhas do stdin
    { echo "$CAB"; cat; } > "$TMP/$1.csv"
}

gerar() {   # $1 = nome (gera $TMP/$1.html)
    if ! bash "$REPORT" "$TMP/$1.csv" "$TMP/$1.html" >"$TMP/$1.log" 2>&1; then
        cat "$TMP/$1.log" >&2
        echo "   ERRO: report-degraus.sh saiu com falha ($1)" >&2
        exit 1
    fi
}

assert_html() {   # $1 = html  $2 = descrição  $3 = trecho literal esperado
    if grep -qF -- "$3" "$TMP/$1.html"; then ok "$2"; else falhar "$2 (faltou: $3)"; fi
}
refute_html() {   # $1 = html  $2 = descrição  $3 = trecho que NÃO pode existir
    if grep -qF -- "$3" "$TMP/$1.html"; then falhar "$2 (apareceu: $3)"; else ok "$2"; fi
}
conta() { grep -oF -- "$2" "$TMP/$1.html" | wc -l | tr -d ' '; }

echo ""
echo "==> Gerando os relatórios das fixtures"

# A: X por 5xx
{ S Deg-1 12000; S Deg-1 16000; L Deg-1 20000 20000 0.030000 0 20.0 25.0 0; L Deg-1 24000 24000 0.200000 0 20.0 25.0 0; } | fixture A
gerar A
# B: X por p95 em janela (p95 do degrau < 1 s; só a janela passa de 1 s); 16000 = exatamente 1000 ms
{ S Deg-1 12000; L Deg-1 16000 16000 0 0 400.0 1000.0 0; L Deg-1 20000 20000 0 0 600.0 1250.5 0; L Deg-1 24000 24000 0.2 0 2000.0 3000.0 0; } | fixture B
gerar B
# C: saturação do gerador antes de X
{ S Deg-1 12000; S Deg-1 16000; L Deg-1 20000 19800 0 0 20.0 25.0 57; L Deg-1 24000 24000 0.050000 0 20.0 25.0 0; } | fixture C
gerar C
# D: nenhum degrau degradado (um degrau exatamente nos limites: 1% e 1000 ms)
{ S Deg-1 12000; S Deg-1 16000; L Deg-1 20000 20000 0.010000 0 900.0 1000.0 0; S Deg-1 24000; S Deg-1 28000; } | fixture D
gerar D
# E: 5xx e descartadas no mesmo degrau
{ S Deg-1 12000; S Deg-1 16000; L Deg-1 20000 20000 0.050000 0 20.0 25.0 100; } | fixture E
gerar E
# F: RPM efetivo (sem descartadas): exatamente 95% não satura; 18999 de 20000 satura
{ S Deg-1 12000; L Deg-1 16000 15200 0 0 20.0 25.0 0; L Deg-1 20000 18999 0 0 20.0 25.0 0; L Deg-1 24000 24000 0 0 1500.0 2000.0 0; } | fixture F
gerar F
# G: saturação já no 1º degrau
{ L Deg-1 12000 12000 0 0 20.0 25.0 10; L Deg-1 16000 16000 0.050000 0 20.0 25.0 0; } | fixture G
gerar G
# H: degraus não uniformes (eixo x linear)
{ S Deg-1 12000; S Deg-1 16000; S Deg-1 20000; S Deg-1 32000; } | fixture H
gerar H
# I: respostas fora do critério (outros) -> aviso, mas não decidem X
{ S Deg-1 12000; L Deg-1 16000 16000 0 7 20.0 25.0 0; } | fixture I
gerar I
# J: o CSV chega ao report fora de ordem, com BOM e CRLF (como o PowerShell 5.1 costuma gravar)
{ printf '\357\273\277%s\r\n' "$CAB"; { L Deg-1 24000 24000 0.200000 0 20.0 25.0 0; S Deg-1 12000; L Deg-1 20000 20000 0.030000 0 20.0 25.0 0; S Deg-1 16000; } | while IFS= read -r l; do printf '%s\r\n' "$l"; done; } > "$TMP/J.csv"
gerar J

echo ""
echo "==> A: X por 5xx"
assert_html A "veredito firme no degrau 20000, motivo 5xx" '<p class="veredito" data-tipo="firme" data-x="20000" data-motivo="5xx" data-saturado="0">'
assert_html A "texto do veredito" 'X = 20000 RPM: primeiro degrau com 5xx &gt; 1% (3.00%)'
assert_html A "linha do degrau X marcada na tabela" '<tr class="degrau-x"><td>20000</td>'
refute_html A "só o primeiro degrau degradado é o X (o 24000 não é marcado)" '<tr class="degrau-x"><td>24000</td>'
assert_html A "situação na tabela" '5xx &gt; 1%'
assert_html A "marca vertical do X nos 3 painéis (latência, throughput, 5xx)" 'class="marca-x"'
if [ "$(conta A 'class="marca-x"')" = 3 ]; then ok "uma marca por painel"; else falhar "esperava 3 marcas do X, achei $(conta A 'class="marca-x"')"; fi
assert_html A "rótulo do X no gráfico (texto, não só cor)" '>X</text>'
if [ "$(conta A '<svg')" = 3 ]; then ok "3 painéis SVG"; else falhar "esperava 3 <svg>, achei $(conta A '<svg')"; fi
assert_html A "painel de latência" 'aria-label="Latência × RPM alvo"'
assert_html A "painel de throughput" 'aria-label="Throughput × RPM alvo"'
assert_html A "painel de 5xx" 'aria-label="Taxa de 5xx × RPM alvo"'
assert_html A "subtítulo: escada, degraus, host" 'escada Deg-1 · 4 degraus de 60 s · braço cas · max_connections 300 · pool HikariCP 1000 · commit abc1234'
assert_html A "tokens de cor no :root, claro e escuro" ':root:where(:not([data-theme="light"])) {'
assert_html A "tema escuro explícito" ':root[data-theme="dark"] {'

echo ""
echo "==> B: X por p95 em janela de 30 s"
assert_html B "X = 20000 pelo p95 de janela (p95 do degrau, 600 ms, estava abaixo de 1 s)" '<p class="veredito" data-tipo="firme" data-x="20000" data-motivo="p95" data-saturado="0">'
assert_html B "texto do veredito cita a janela" 'primeiro degrau com p95 &gt; 1 s numa janela de 30 s (1250.5 ms)'
refute_html B "janela exatamente em 1000 ms NÃO é X (limite estrito)" '<tr class="degrau-x"><td>16000</td>'

echo ""
echo "==> C: saturação do gerador antes de X (limite inferior)"
assert_html C "limite inferior = último degrau sem saturação (16000), saturou em 20000" '<p class="veredito" data-tipo="limite-inferior" data-x="16000" data-saturou="20000">'
assert_html C "texto do veredito" 'X &gt; 16000 RPM (limite inferior): o gerador saturou em 20000 RPM'
assert_html C "o degrau que degradou depois é citado, já com o gerador saturado" 'O host só degradou em 24000 RPM'
assert_html C "linha do limite inferior marcada na tabela" '<tr class="degrau-limite"><td>16000</td>'
refute_html C "nenhuma linha de X firme" 'class="degrau-x"'
assert_html C "rótulo do limite inferior no gráfico" '>X &gt; 16k</text>'
assert_html C "situação do degrau saturado" 'gerador saturado'

echo ""
echo "==> D: nenhum degrau degradado"
assert_html D "veredito: X acima do último degrau" '<p class="veredito" data-tipo="nao-degradou" data-x="28000">'
assert_html D "texto do veredito" 'Nenhum degrau degradou até 28000 RPM: X &gt; 28000 RPM'
refute_html D "exatamente 1% e exatamente 1000 ms não degradam (limites estritos)" 'class="degrau-x"'
refute_html D "sem marca do X" 'class="marca-x"'

echo ""
echo "==> E: 5xx e descartadas no mesmo degrau"
assert_html E "X firme em 20000, marcado como saturado" '<p class="veredito" data-tipo="firme" data-x="20000" data-motivo="5xx" data-saturado="1">'
assert_html E "aviso no texto" 'iterações descartadas ou RPM efetivo abaixo do alvo no próprio degrau'

echo ""
echo "==> F: saturação pelo RPM efetivo (tolerância de 5%)"
assert_html F "16000 com exatamente 95% do alvo NÃO satura; 18999 de 20000 satura" '<p class="veredito" data-tipo="limite-inferior" data-x="16000" data-saturou="20000">'
assert_html F "a situação cita o RPM efetivo abaixo do alvo" 'RPM efetivo &lt; 95% do alvo'

echo ""
echo "==> G: saturação no 1º degrau"
assert_html G "sem base: nem limite inferior" '<p class="veredito" data-tipo="sem-base" data-saturou="12000">'
assert_html G "texto do veredito" 'O gerador saturou já no primeiro degrau (12000 RPM): sem limite inferior para X'
refute_html G "sem marca do X" 'class="marca-x"'

echo ""
echo "==> I: respostas fora do critério (outros)"
assert_html I "aviso de outros > 0 (não decide X)" 'class="aviso"'
assert_html I "texto do aviso" 'respostas inesperadas ou falhas de conexão (outros &gt; 0) em 1 degrau'
assert_html I "e o veredito segue o critério: nenhum degrau degradou" 'data-tipo="nao-degradou"'

echo ""
echo "==> J: CSV fora de ordem, com BOM e CRLF"
assert_html J "ordena por rpm_alvo (no arquivo o 24000 vem antes): o 1º degrau degradado é o 20000" '<p class="veredito" data-tipo="firme" data-x="20000" data-motivo="5xx" data-saturado="0">'
assert_html J "subtítulo conta os 4 degraus" '4 degraus de 60 s'

echo ""
echo "==> H: eixo x linear (posição proporcional ao RPM alvo)"
painel() {   # imprime o 1º <svg> (latência) do html $1
    awk 'index($0, "<svg") { dentro = 1 } dentro { print } dentro && /<\/svg>/ { exit }' "$TMP/$1.html"
}
cx_de() {   # $1 = svg  $2 = rpm ; cx do marcador do p50 nesse degrau
    grep -F "<g class=\"pt\" data-rpm=\"$2\"><title>p50 " "$1" | head -1 | sed -n 's/.*<circle cx="\([0-9.]*\)" cy="[0-9.]*" r="4.5".*/\1/p'
}
painel H > "$TMP/H-lat.svg"
X12=$(cx_de "$TMP/H-lat.svg" 12000); X16=$(cx_de "$TMP/H-lat.svg" 16000); X20=$(cx_de "$TMP/H-lat.svg" 20000); X32=$(cx_de "$TMP/H-lat.svg" 32000)
if [ -n "$X12" ] && [ -n "$X16" ] && [ -n "$X20" ] && [ -n "$X32" ]; then
    r1=$(awk -v a="$X12" -v b="$X16" -v c="$X32" 'BEGIN { printf "%.3f", (b - a) / (c - a) }')
    r2=$(awk -v a="$X12" -v b="$X20" -v c="$X32" 'BEGIN { printf "%.3f", (b - a) / (c - a) }')
    if [ "$r1" = "0.200" ]; then ok "(x16k - x12k) / (x32k - x12k) = $r1 (esperado 4k/20k = 0.2: eixo linear)"; else falhar "razão 16k = $r1, esperado 0.200"; fi
    if [ "$r2" = "0.400" ]; then ok "(x20k - x12k) / (x32k - x12k) = $r2 (esperado 8k/20k = 0.4)"; else falhar "razão 20k = $r2, esperado 0.400"; fi
else
    falhar "marcadores do p50 não encontrados no painel de latência (12k='$X12' 16k='$X16' 20k='$X20' 32k='$X32')"
fi
assert_html H "rótulos do eixo x em milhares" '>32k</text>'

echo ""
echo "==> Entradas inválidas falham alto"
roda() {   # $1 = nome  ; imprime o código de saída
    bash "$REPORT" "$TMP/$1.csv" "$TMP/$1.html" >"$TMP/$1.log" 2>&1; echo $?
}
{ S Deg-1 12000; S Deg-2 16000; } | fixture mista
if [ "$(roda mista)" != 0 ] && grep -q 'mais de uma escada' "$TMP/mista.log"; then ok "CSV com 2 escadas (primeiro degrau de cada uma) recusa: mais de uma escada"; else falhar "CSV com 2 escadas deveria falhar"; fi
{ S Deg-1 12000; S Deg-1 12000; } | fixture dup
if [ "$(roda dup)" != 0 ] && grep -q 'repetido' "$TMP/dup.log"; then ok "rpm_alvo repetido recusa"; else falhar "rpm_alvo repetido deveria falhar"; fi
printf 'rpm_alvo,taxa_5xx\n12000,0\n' > "$TMP/semcol.csv"
if [ "$(roda semcol)" != 0 ] && grep -q 'coluna obrigatória' "$TMP/semcol.log"; then ok "CSV sem as colunas obrigatórias recusa"; else falhar "CSV sem colunas obrigatórias deveria falhar"; fi
echo "$CAB" > "$TMP/vazio.csv"
if [ "$(roda vazio)" != 0 ]; then ok "CSV só com o cabeçalho recusa"; else falhar "CSV vazio deveria falhar"; fi
if bash "$REPORT" "$TMP/nao-existe.csv" "$TMP/x.html" >/dev/null 2>&1; then falhar "CSV inexistente deveria falhar"; else ok "CSV inexistente falha com código != 0"; fi
if bash "$REPORT" >/dev/null 2>&1; then falhar "sem argumentos deveria falhar"; else ok "sem argumentos falha com código != 0"; fi

echo ""
echo "=================================================="
if [ "$FALHAS" -eq 0 ]; then
    echo "   RESULTADO: OK -- report-degraus.sh decidiu X corretamente em todas as fixtures."
else
    echo "   RESULTADO: $FALHAS ASSERCAO(OES) FALHARAM."
fi
echo "=================================================="
echo ""
exit $(( FALHAS > 0 ? 1 : 0 ))
