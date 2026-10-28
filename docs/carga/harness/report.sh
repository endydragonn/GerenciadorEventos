#!/bin/bash

# =============================================================================
#  report.sh · harness de carga (issue #11)
# =============================================================================
#
#  Junta os três resultados de uma rodada (resumo do k6, corretude do banco
#  via verify.sh, série de lag do read model via collector.sh) num CSV final
#  + um gráfico HTML autocontido (SVG inline, sem lib externa -- não há
#  Python nem Node.js neste repo, ver Projeto/CLAUDE.md e o brief da #11).
#
#  Uso:
#    report.sh --summary <k6-summary.json> --verify <verify.csv> \
#               --lag <lag.csv> --n-vus <N> --capacity <CAP> \
#               [--readpath cas|naive] [--base-url <url>] \
#               --out-csv <resultado.csv> --out-html <resultado.html>
#
#  Formatos de entrada esperados:
#    --summary : JSON de `k6 run --summary-export=...` (indentado 4 espaços,
#                formato estável do k6 -- ver scenario.js/run.sh), precisa
#                conter os stats p(50)/p(95)/p(99) (summaryTrendStats).
#    --verify  : CSV "metrica,valor" produzido por verify.sh (capacity,
#                confirmadas, pendentes, veredito_corretude).
#    --lag     : CSV "metrica,timestamp_epoch,valor" produzido por
#                collector.sh (uma linha lag_ms por amostra; valor=-1 é o
#                sentinela "read model ainda sem leitura pra este evento").
#
#  Sem jq/python (idioma do projeto): extração via grep -oP/awk, no mesmo
#  espírito de docs/testes-manuais/overselling-sob-concorrencia.sh.
#
#  Auto-teste: report-selftest.sh (fixture sintética + asserções, mesmo rigor
#  do precedente do projeto) valida a lógica de composição deste script.
# =============================================================================

set -uo pipefail

SUMMARY="" VERIFY="" LAG="" N_VUS="" CAPACITY_ARG="" READPATH="cas" BASE_URL=""
OUT_CSV="" OUT_HTML=""

while [ $# -gt 0 ]; do
    case "$1" in
        --summary) SUMMARY="$2"; shift 2 ;;
        --verify) VERIFY="$2"; shift 2 ;;
        --lag) LAG="$2"; shift 2 ;;
        --n-vus) N_VUS="$2"; shift 2 ;;
        --capacity) CAPACITY_ARG="$2"; shift 2 ;;
        --readpath) READPATH="$2"; shift 2 ;;
        --base-url) BASE_URL="$2"; shift 2 ;;
        --out-csv) OUT_CSV="$2"; shift 2 ;;
        --out-html) OUT_HTML="$2"; shift 2 ;;
        *) echo "ERRO: argumento desconhecido: $1" >&2; exit 1 ;;
    esac
done

for req in SUMMARY VERIFY LAG N_VUS OUT_CSV OUT_HTML; do
    if [ -z "${!req}" ]; then
        echo "ERRO: --$(echo "$req" | tr '[:upper:]' '[:lower:]' | tr '_' '-') é obrigatório" >&2
        exit 1
    fi
done
for f in "$SUMMARY" "$VERIFY" "$LAG"; do
    [ -f "$f" ] || { echo "ERRO: arquivo não encontrado: $f" >&2; exit 1; }
done

# ---------------------------------------------------------------------------
#  Extração — k6 summary.json (indentado, uma métrica por bloco "nome": {...})
# ---------------------------------------------------------------------------
# $1 = arquivo  $2 = nome exato da métrica (chave JSON)  $3 = nome do stat
extrair_stat_k6() {
    awk -v metric_key="\"$2\": {" -v stat_key="\"$3\":" '
        index($0, metric_key) > 0 { dentro = 1; next }
        dentro && $0 ~ /^[ \t]*\}/ { dentro = 0 }
        dentro {
            n = index($0, stat_key)
            if (n > 0) {
                resto = substr($0, n + length(stat_key))
                gsub(/^[ \t]*/, "", resto)
                gsub(/,[ \t]*$/, "", resto)
                print resto
                exit
            }
        }
    ' "$1"
}

# ---------------------------------------------------------------------------
#  Extração — verify.csv ("metrica,valor")
# ---------------------------------------------------------------------------
ler_verify() {   # $1 = nome da métrica
    awk -F, -v k="$1" '$1 == k { print $2; exit }' "$VERIFY"
}

P50_RAW=$(extrair_stat_k6 "$SUMMARY" "http_req_duration" "p(50)")
P95_RAW=$(extrair_stat_k6 "$SUMMARY" "http_req_duration" "p(95)")
P99_RAW=$(extrair_stat_k6 "$SUMMARY" "http_req_duration" "p(99)")
THROUGHPUT_RAW=$(extrair_stat_k6 "$SUMMARY" "iterations" "rate")
[ -n "$P50_RAW" ] && [ -n "$P95_RAW" ] && [ -n "$P99_RAW" ] && [ -n "$THROUGHPUT_RAW" ] || {
    echo "ERRO: não consegui extrair p(50)/p(95)/p(99)/throughput de $SUMMARY (rodou com --summary-trend-stats incluindo p(50),p(95),p(99)?)" >&2
    exit 1
}
# Confere que os 4 valores são numéricos antes de seguir -- o parser acima é
# um awk artesanal amarrado ao formato atual do k6-summary.json; se uma
# versão futura do k6 mudar esse formato, é melhor falhar alto aqui do que
# deixar o printf abaixo coagir lixo não-numérico em "0.0" silenciosamente
# (achado do Gemini na review da issue #11/PR #82).
for par in "p(50)=$P50_RAW" "p(95)=$P95_RAW" "p(99)=$P99_RAW" "throughput=$THROUGHPUT_RAW"; do
    valor="${par#*=}"
    case "$valor" in
        ''|*[!0-9.eE+-]*)
            echo "ERRO: valor extraído do k6-summary.json não é numérico ($par) -- formato do JSON mudou?" >&2
            exit 1
            ;;
    esac
done
# normaliza casas decimais (o k6 exporta floats com precisão variável) -- CSV
# final estável e legível, independente da precisão bruta do JSON.
P50=$(awk -v v="$P50_RAW" 'BEGIN{printf "%.1f", v}')
P95=$(awk -v v="$P95_RAW" 'BEGIN{printf "%.1f", v}')
P99=$(awk -v v="$P99_RAW" 'BEGIN{printf "%.1f", v}')
THROUGHPUT=$(awk -v v="$THROUGHPUT_RAW" 'BEGIN{printf "%.2f", v}')

CAPACITY=$(ler_verify "capacity")
CONFIRMADAS=$(ler_verify "confirmadas")
PENDENTES=$(ler_verify "pendentes")
VEREDITO=$(ler_verify "veredito_corretude")
[ -n "$CAPACITY" ] && [ -n "$CONFIRMADAS" ] && [ -n "$VEREDITO" ] || {
    echo "ERRO: não consegui ler capacity/confirmadas/veredito_corretude de $VERIFY" >&2
    exit 1
}
if [ -n "$CAPACITY_ARG" ] && [ "$CAPACITY_ARG" != "$CAPACITY" ]; then
    echo "AVISO: --capacity=$CAPACITY_ARG difere do capacity lido do banco ($CAPACITY) -- usando o do banco (fonte de verdade)." >&2
fi

# ---------------------------------------------------------------------------
#  Extração — lag.csv ("metrica,timestamp_epoch,valor", metrica=lag_ms)
# ---------------------------------------------------------------------------
# ignora o sentinela -1 (read model ainda sem leitura) nas agregações, mas
# preserva a contagem bruta de amostras (inclui os -1, é sinal do fenômeno).
# LAG_VALIDAS < LAG_AMOSTRAS é esperado numa rodada curta (a linha da projeção
# só nasce quando o consumer aplica o 1º evento -- ver README); lag_max/lag_medio = 0.0 com LAG_VALIDAS = 0 significa "sem
# leitura ainda", não "lag zero".
read -r LAG_AMOSTRAS LAG_VALIDAS LAG_MAX LAG_MEDIO < <(awk -F, '
    $1 == "lag_ms" {
        total++
        if ($3 != "-1") { soma += $3; n++; if ($3 > max) { max = $3 } }
    }
    END {
        if (n == 0) { max = 0; media = 0 } else { media = soma / n }
        printf "%d %d %.1f %.1f", total, n, max, media
    }
' "$LAG")

# série (timestamp,lag_ms) só das amostras válidas, pro gráfico de linha —
# normaliza timestamp para segundos relativos ao início da rodada.
LAG_SERIE=$(awk -F, '
    $1 == "lag_ms" && $3 != "-1" { print $2","$3 }
' "$LAG")

# ---------------------------------------------------------------------------
#  CSV final (formato longo: metrica,valor,unidade — 1 linha por métrica do
#  Aceite da issue, fácil de auto-testar)
# ---------------------------------------------------------------------------
mkdir -p "$(dirname "$OUT_CSV")"
{
    echo "metrica,valor,unidade"
    echo "n_vus,${N_VUS},vus"
    echo "capacity,${CAPACITY},vagas"
    echo "confirmadas,${CONFIRMADAS},contagem"
    echo "pendentes,${PENDENTES},contagem"
    echo "veredito_corretude,${VEREDITO},texto"
    echo "latencia_p50,${P50},ms"
    echo "latencia_p95,${P95},ms"
    echo "latencia_p99,${P99},ms"
    echo "throughput,${THROUGHPUT},reservas/s"
    echo "lag_amostras,${LAG_AMOSTRAS},contagem"
    echo "lag_leituras_validas,${LAG_VALIDAS},contagem"
    echo "lag_max,${LAG_MAX},ms"
    echo "lag_medio,${LAG_MEDIO},ms"
    echo "readpath,${READPATH},texto"
    [ -n "$BASE_URL" ] && echo "base_url,${BASE_URL},texto"
} > "$OUT_CSV"
echo "==> report.sh: CSV final em $OUT_CSV" >&2

# ---------------------------------------------------------------------------
#  Gráfico HTML autocontido (SVG inline) — paleta/marcas conforme skill dataviz
#  (references/palette.md e marks-and-anatomy.md): 1 hue por magnitude, marcas
#  finas com pontas arredondadas, grid recessivo, status por cor reservada.
# ---------------------------------------------------------------------------
max_lat=$(awk -v a="$P50" -v b="$P95" -v c="$P99" 'BEGIN{m=a; if(b>m)m=b; if(c>m)m=c; if(m<=0)m=1; print m*1.15}')
BAR_W=90; BAR_GAP=48; CHART_H=220; CHART_BASE=260
labels_csv="p50,p95,p99"
bars_svg=""
x=60
idx=0
for par in "p50:$P50" "p95:$P95" "p99:$P99"; do
    lab="${par%%:*}"; v="${par#*:}"
    h=$(awk -v v="$v" -v m="$max_lat" -v ch="$CHART_H" 'BEGIN{printf "%.1f", (v/m)*ch}')
    y=$(awk -v base="$CHART_BASE" -v h="$h" 'BEGIN{printf "%.1f", base-h}')
    label_y=$(awk -v y="$y" 'BEGIN{ly=y-8; if(ly<14) ly=14; printf "%.1f", ly}')
    bars_svg+="<rect x=\"$x\" y=\"$y\" width=\"$BAR_W\" height=\"$h\" rx=\"4\" ry=\"4\" fill=\"var(--series-1)\"/>"$'\n'
    bars_svg+="<text x=\"$(awk -v x="$x" -v w="$BAR_W" 'BEGIN{printf "%.1f", x+w/2}')\" y=\"$label_y\" text-anchor=\"middle\" class=\"valor-barra\">$(awk -v v="$v" 'BEGIN{printf "%.1f ms", v}')</text>"$'\n'
    bars_svg+="<text x=\"$(awk -v x="$x" -v w="$BAR_W" 'BEGIN{printf "%.1f", x+w/2}')\" y=\"$((CHART_BASE + 22))\" text-anchor=\"middle\" class=\"eixo\">$lab</text>"$'\n'
    x=$((x + BAR_W + BAR_GAP))
    idx=$((idx + 1))
done

lag_line_svg=""
lag_pontos_svg=""
if [ -n "$LAG_SERIE" ]; then
    tmin=$(printf '%s\n' "$LAG_SERIE" | awk -F, 'NR==1{m=$1} $1<m{m=$1} END{print m}')
    tmax=$(printf '%s\n' "$LAG_SERIE" | awk -F, 'NR==1{m=$1} $1>m{m=$1} END{print m}')
    lagmax_serie=$(printf '%s\n' "$LAG_SERIE" | awk -F, 'NR==1{m=$2} $2>m{m=$2} END{print m+0.001}')
    LINE_W=560; LINE_H=180; LINE_X0=60; LINE_Y0=20
    pts=""
    while IFS=, read -r ts val; do
        [ -z "$ts" ] && continue
        px=$(awk -v ts="$ts" -v tmin="$tmin" -v tmax="$tmax" -v w="$LINE_W" -v x0="$LINE_X0" 'BEGIN{d=tmax-tmin; if(d<=0)d=1; printf "%.1f", x0+((ts-tmin)/d)*w}')
        py=$(awk -v val="$val" -v m="$lagmax_serie" -v h="$LINE_H" -v y0="$LINE_Y0" 'BEGIN{printf "%.1f", y0+h-((val/m)*h)}')
        pts+="$px,$py "
        lag_pontos_svg+="<circle cx=\"$px\" cy=\"$py\" r=\"3.5\" fill=\"var(--series-1)\"/>"$'\n'
    done <<< "$LAG_SERIE"
    lag_line_svg="<polyline points=\"${pts}\" fill=\"none\" stroke=\"var(--series-1)\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
fi

veredito_cor="var(--status-good)"
veredito_texto="sem overselling"
if [ "$VEREDITO" != "OK" ]; then
    veredito_cor="var(--status-critical)"
    veredito_texto="overselling detectado"
fi

mkdir -p "$(dirname "$OUT_HTML")"
cat > "$OUT_HTML" <<HTML
<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<title>Harness de carga — issue #11</title>
<style>
  .viz-root {
    color-scheme: light;
    --surface-1:      #fcfcfb;
    --text-primary:   #0b0b0b;
    --text-secondary: #52514e;
    --text-muted:     #898781;
    --grid:           #e1e0d9;
    --baseline:       #c3c2b7;
    --series-1:       #2a78d6;
    --status-good:    #0ca30c;
    --status-critical:#d03b3b;
  }
  @media (prefers-color-scheme: dark) {
    :root:where(:not([data-theme="light"])) .viz-root {
      color-scheme: dark;
      --surface-1:      #1a1a19;
      --text-primary:   #ffffff;
      --text-secondary: #c3c2b7;
      --text-muted:     #898781;
      --grid:           #2c2c2a;
      --baseline:       #383835;
      --series-1:       #3987e5;
      --status-good:    #0ca30c;
      --status-critical:#e66767;
    }
  }
  body { margin: 0; background: var(--surface-1, #fcfcfb); font-family: system-ui, -apple-system, "Segoe UI", sans-serif; }
  .viz-root { padding: 24px 32px 40px; max-width: 760px; margin: 0 auto; color: var(--text-primary); }
  h1 { font-size: 18px; margin: 0 0 4px; }
  .subtitulo { color: var(--text-secondary); font-size: 13px; margin: 0 0 24px; }
  .tiles { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 28px; }
  .tile { flex: 1 1 140px; border: 1px solid var(--grid); border-radius: 8px; padding: 12px 14px; }
  .tile .rotulo { font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: .04em; }
  .tile .valor { font-size: 22px; font-weight: 600; margin-top: 4px; font-variant-numeric: tabular-nums; }
  .tile.veredito .valor { color: ${veredito_cor}; }
  h2 { font-size: 14px; margin: 28px 0 8px; color: var(--text-secondary); }
  svg { display: block; }
  .grid-line { stroke: var(--grid); stroke-width: 1; }
  .baseline { stroke: var(--baseline); stroke-width: 1; }
  .eixo { fill: var(--text-muted); font-size: 11px; }
  .valor-barra { fill: var(--text-secondary); font-size: 11px; font-variant-numeric: tabular-nums; }
  table { border-collapse: collapse; width: 100%; font-size: 12px; margin-top: 8px; }
  th, td { text-align: left; padding: 4px 8px; border-bottom: 1px solid var(--grid); font-variant-numeric: tabular-nums; }
  th { color: var(--text-muted); font-weight: 500; }
  footer { margin-top: 28px; font-size: 11px; color: var(--text-muted); }
</style>
</head>
<body>
<div class="viz-root">
  <h1>Harness de carga — issue #11</h1>
  <p class="subtitulo">N=${N_VUS} VUs disputando capacity=${CAPACITY} · braço readpath=${READPATH} · gerado em $(date -u '+%Y-%m-%dT%H:%M:%SZ')</p>

  <div class="tiles">
    <div class="tile">
      <div class="rotulo">Confirmadas / Capacity</div>
      <div class="valor">${CONFIRMADAS} / ${CAPACITY}</div>
    </div>
    <div class="tile veredito">
      <div class="rotulo">Corretude</div>
      <div class="valor">${VEREDITO}</div>
      <div class="subtitulo" style="margin:2px 0 0">${veredito_texto}</div>
    </div>
    <div class="tile">
      <div class="rotulo">Throughput</div>
      <div class="valor">$(awk -v v="$THROUGHPUT" 'BEGIN{printf "%.2f", v}') /s</div>
    </div>
    <div class="tile">
      <div class="rotulo">Amostras de lag (válidas/total)</div>
      <div class="valor">${LAG_VALIDAS} / ${LAG_AMOSTRAS}</div>
    </div>
  </div>

  <h2>Latência (http_req_duration)</h2>
  <svg viewBox="0 0 480 300" width="100%" role="img" aria-label="Latência p50, p95 e p99 em milissegundos">
    <line class="baseline" x1="40" y1="${CHART_BASE}" x2="440" y2="${CHART_BASE}"/>
    ${bars_svg}
  </svg>

  <h2>Lag do read model ao longo da rodada (event_availability)</h2>
  $( if [ -n "$LAG_SERIE" ]; then
cat <<SVG
  <svg viewBox="0 0 640 210" width="100%" role="img" aria-label="Lag observado do read model, em milissegundos, ao longo da rodada">
    <line class="grid-line" x1="60" y1="20" x2="60" y2="200"/>
    <line class="baseline" x1="60" y1="200" x2="620" y2="200"/>
    ${lag_line_svg}
    ${lag_pontos_svg}
  </svg>
SVG
  else
    echo "  <p class=\"subtitulo\">Sem amostras válidas de lag nesta rodada (o consumer não chegou a aplicar evento no intervalo observado).</p>"
  fi )

  <h2>Resumo</h2>
  <table>
    <thead><tr><th>Métrica</th><th>Valor</th><th>Unidade</th></tr></thead>
    <tbody>
$(awk -F, 'NR>1{printf "      <tr><td>%s</td><td>%s</td><td>%s</td></tr>\n", $1, $2, $3}' "$OUT_CSV")
    </tbody>
  </table>

  <footer>Gerado por docs/carga/harness/report.sh a partir de ${SUMMARY##*/}, ${VERIFY##*/} e ${LAG##*/}. CSV completo: ${OUT_CSV##*/}.</footer>
</div>
</body>
</html>
HTML

echo "==> report.sh: HTML final em $OUT_HTML" >&2
