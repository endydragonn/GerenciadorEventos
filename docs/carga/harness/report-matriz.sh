#!/bin/bash

# =============================================================================
#  report-matriz.sh · gráfico da matriz do experimento (issue #11)
# =============================================================================
#
#  Lê o CSV da matriz (Projeto/carga/results/matriz.csv, gerado por
#  `make test-matriz`: uma linha por execução) e gera um HTML autocontido com
#  SVG inline (sem lib externa, no mesmo espírito do report.sh):
#
#    - uma linha de painéis por (N, capacity) (o CSV acumula execuções; capacities
#      diferentes não se misturam):
#        . overselling (y) por lag (x): uma curva por braço (o cas é referência,
#          ver abaixo), marcador = média das repetições, barra grossa = ±1 desvio amostral, linha fina =
#          mín–máx;
#        . latência p95 da reserva (y) por lag (x), mesma anatomia;
#    - a "table view" com os agregados por (braço, lag, N, capacity), incluindo
#      pending+confirmed (métrica secundária) e o lag observado médio.
#
#  Eixo x LINEAR em ms, de 0 ao maior lag do CSV: a posição é proporcional ao lag
#  (a hipótese do experimento é "overselling cresce com o lag"; um eixo ordinal
#  distorceria a proporção). Marcas (e rótulos, se couberem) nos lags presentes.
#
#  Braço cas = linha de REFERÊNCIA horizontal (tracejada), não curva: ele não lê o
#  read model, então não depende do lag (o CSV costuma ter só 1 lag dele). A linha é
#  a média de todas as execuções cas do grupo (N, capacity), de lag 0 ao maior lag;
#  média ± desvio e mín/máx (de todas) no tooltip, e os marcadores com ±desvio e
#  mín–máx ficam em cada lag em que o cas foi de fato medido. Sem cas no grupo, o
#  painel mostra só os demais braços.
#
#  Uso:  report-matriz.sh <matriz.csv> <saida.html>
#  Roda em qualquer bash com awk (o Git Bash do gerador serve; não precisa
#  copiar o CSV para o host). Auto-teste: report-matriz-selftest.sh.
#
#  Cores: braço cas = série 1 (azul), naive = série 2 (laranja), por NOME do
#  braço (a cor segue a entidade); paleta validada pela skill dataviz.
# =============================================================================

set -uo pipefail
export LC_ALL=C   # ponto decimal no printf do awk, independente do locale

if [ $# -ne 2 ]; then
    echo "uso: $0 <matriz.csv> <saida.html>" >&2
    exit 1
fi
CSV="$1"
OUT="$2"
[ -f "$CSV" ] || { echo "ERRO: arquivo não encontrado: $CSV" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

cat > "$TMP" <<'HTML'
<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Matriz do experimento — overselling × lag</title>
<style>
  :root {
    color-scheme: light;
    --surface-1:      #fcfcfb;
    --text-primary:   #0b0b0b;
    --text-secondary: #52514e;
    --text-muted:     #898781;
    --grid:           #e1e0d9;
    --baseline:       #c3c2b7;
    --series-1:       #2a78d6;
    --series-2:       #eb6834;
    --series-3:       #1baf7a;
    --series-4:       #eda100;
  }
  @media (prefers-color-scheme: dark) {
    :root:where(:not([data-theme="light"])) {
      color-scheme: dark;
      --surface-1:      #1a1a19;
      --text-primary:   #ffffff;
      --text-secondary: #c3c2b7;
      --grid:           #2c2c2a;
      --baseline:       #383835;
      --series-1:       #3987e5;
      --series-2:       #d95926;
      --series-3:       #199e70;
      --series-4:       #c98500;
    }
  }
  :root[data-theme="dark"] {
    color-scheme: dark;
    --surface-1:      #1a1a19;
    --text-primary:   #ffffff;
    --text-secondary: #c3c2b7;
    --grid:           #2c2c2a;
    --baseline:       #383835;
    --series-1:       #3987e5;
    --series-2:       #d95926;
    --series-3:       #199e70;
    --series-4:       #c98500;
  }
  body { margin: 0; background: var(--surface-1, #fcfcfb); font-family: system-ui, -apple-system, "Segoe UI", sans-serif; }
  .viz-root { background: var(--surface-1); padding: 24px 16px 40px; max-width: 980px; margin: 0 auto; color: var(--text-primary); }
  h1 { font-size: 18px; margin: 0 0 4px; }
  h2 { font-size: 14px; margin: 28px 0 8px; color: var(--text-secondary); }
  h3 { font-size: 12px; font-weight: 500; margin: 0 0 4px; color: var(--text-secondary); }
  .subtitulo, .nota { color: var(--text-secondary); font-size: 13px; margin: 0 0 8px; }
  .nota { font-size: 12px; color: var(--text-muted); }
  .aviso { color: var(--text-primary); font-size: 13px; margin: 0 0 8px; padding: 6px 10px; border-left: 3px solid var(--series-4); }
  .legenda { display: flex; gap: 16px; margin: 12px 0 4px; font-size: 13px; color: var(--text-secondary); }
  .item { display: inline-flex; align-items: center; gap: 6px; }
  .chave { display: inline-block; width: 16px; height: 3px; border-radius: 2px; }
  .chave-cas { background: repeating-linear-gradient(90deg, var(--series-1) 0 6px, transparent 6px 10px); }
  .chave-naive { background: var(--series-2); }
  .chave-s3 { background: var(--series-3); }
  .chave-s4 { background: var(--series-4); }
  .paineis { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; }
  svg { display: block; overflow: visible; }
  .grid { stroke: var(--grid); stroke-width: 1; }
  .baseline { stroke: var(--baseline); stroke-width: 1; }
  .eixo { fill: var(--text-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
  .rotulo-serie { fill: var(--text-secondary); font-size: 11px; }
  .tabela { overflow-x: auto; }
  table { border-collapse: collapse; width: 100%; font-size: 12px; }
  th, td { text-align: right; padding: 4px 8px; border-bottom: 1px solid var(--grid); font-variant-numeric: tabular-nums; white-space: nowrap; }
  th:first-child, td:first-child { text-align: left; }
  th { color: var(--text-muted); font-weight: 500; }
  footer { margin-top: 28px; font-size: 11px; color: var(--text-muted); }
</style>
</head>
<body>
HTML

awk -v fonte="${CSV##*/}" -v gerado="$(date -u '+%Y-%m-%dT%H:%M:%SZ')" '
BEGIN { FS = "," }

function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
function esc(s) { gsub(/&/, "\\&amp;", s); gsub(/</, "\\&lt;", s); gsub(/>/, "\\&gt;", s); gsub(/"/, "\\&quot;", s); return s }
function fmt(v) { return sprintf("%g", v) }
function max0(v) { return v < 0 ? 0 : v }

# classe CSS do braço: cas/naive têm nome próprio; os demais usam o slot
function armcls(b) { return (b == "cas" || b == "naive") ? b : "s" slot[b] }

# potência de 10 (1, 2 ou 5 x 10^k) para ~4 divisões entre 0 e max
function passo(max,   alvo, p, f) {
    alvo = max / 4
    if (alvo <= 0) return 1
    p = 1
    while (p * 10 <= alvo) p *= 10
    while (p > alvo) p /= 10
    f = alvo / p
    if (f <= 1) return p
    if (f <= 2) return 2 * p
    if (f <= 5) return 5 * p
    return 10 * p
}

# ordena arr[1..n] numericamente (inserção; n pequeno)
function ordenar(arr, n,   i, j, v) {
    for (i = 2; i <= n; i++) {
        v = arr[i]
        for (j = i - 1; j >= 1 && arr[j] > v; j--) arr[j + 1] = arr[j]
        arr[j + 1] = v
    }
}

# ordena os grupos G[1..n] (ids "N|capacity") por N e depois por capacity
function ordenar_grupos(n,   i, j, v) {
    for (i = 2; i <= n; i++) {
        v = G[i]
        for (j = i - 1; j >= 1 && (gN[G[j]] > gN[v] || (gN[G[j]] == gN[v] && gC[G[j]] > gC[v])); j--) G[j + 1] = G[j]
        G[j + 1] = v
    }
}

# x de um lag no eixo LINEAR (de 0 ao maior lag, lmax): proporcional ao lag
function xpos(lag, x0, x1) { return x0 + lag / lmax * (x1 - x0) }

# um painel SVG: n = índice em G[] (grupo = N + capacity), m = "o" (overselling) ou "p" (p95)
function painel(n, m,   W, H, x0, x1, y0, y1, a, l, k, vmax, v, step, top, t, i, xc, px, py, pts, np, mean, sd, mn, mx, lo, hi, nlab, ylast, xlast, ok, b, rotulo, un, tt, ymn, ymx, xlab, c, sm, ss, rmn, rmx, rmean, rsd, ref, yref) {
    W = 420; H = 250; x0 = 52; x1 = W - 56; y0 = 14; y1 = H - 36
    vmax = 0
    for (a = 1; a <= na; a++) for (l = 1; l <= nl; l++) {
        k = A[a] SUBSEP L[l] SUBSEP G[n]
        if (!(k in cnt)) continue
        mx = (m == "o") ? mxo[k] : mxp[k]
        mean = (m == "o") ? mo[k] : mp[k]
        sd = (m == "o") ? sdo[k] : sdp[k]
        v = mx
        if (sd > 0 && mean + sd > v) v = mean + sd
        if (v > vmax) vmax = v
    }
    step = passo(vmax)
    t = int(vmax / step)
    if (t * step < vmax - 1e-9) t++
    if (t < 1) t = 1
    top = step * t
    rotulo = ((m == "o") ? "Overselling por lag" : "Latência p95 por lag") ", N=" gN[G[n]] ", capacity " gC[G[n]]
    un = (m == "o") ? "" : " ms"
    printf "<svg viewBox=\"0 0 %d %d\" width=\"100%%\" role=\"img\" aria-label=\"%s\">\n", W, H, rotulo

    # grade horizontal (hairline) + rótulos do eixo y; o 0 é a linha de base
    for (i = 0; i <= t; i++) {
        v = i * step
        py = y1 - v / top * (y1 - y0)
        printf "<line class=\"%s\" x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\"/>\n", (i == 0 ? "baseline" : "grid"), x0, py, x1, py
        printf "<text class=\"eixo\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>\n", x0 - 8, py + 4, fmt(v)
    }
    # eixo x linear em ms, a partir de 0: marca em cada lag presente; rótulo só se couber (>= 32px do anterior)
    xlab = -1000
    for (l = 1; l <= nl; l++) {
        xc = xpos(L[l], x0, x1)
        printf "<line class=\"baseline\" x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\"/>\n", xc, y1, xc, y1 + 4
        if (xc - xlab < 32) continue
        xlab = xc
        printf "<text class=\"eixo eixo-x\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>\n", xc, y1 + 16, L[l]
    }
    printf "<text class=\"eixo\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">lag (ms) · escala linear</text>\n", (x0 + x1) / 2, H - 4

    # referência do cas: todas as execuções cas do grupo, em qualquer lag (ele não depende do lag)
    ref = 0
    if ("cas" in slot) {
        c = 0; sm = 0; ss = 0
        for (l = 1; l <= nl; l++) {
            k = "cas" SUBSEP L[l] SUBSEP G[n]
            if (!(k in cnt)) continue
            for (i = 1; i <= cnt[k]; i++) {
                v = (m == "o") ? ov[k, i] : pv[k, i]
                sm += v; ss += v * v
                if (!c || v < rmn) rmn = v
                if (!c || v > rmx) rmx = v
                c++
            }
        }
        if (c) {
            ref = 1; rmean = sm / c; rsd = -1
            if (c > 1) { v = (ss - c * rmean * rmean) / (c - 1); rsd = (v > 0) ? sqrt(v) : 0 }
        }
    }
    if (ref) {
        yref = y1 - rmean / top * (y1 - y0)
        tt = sprintf("cas · independe do lag · média %.2f%s", rmean, un)
        if (rsd >= 0) tt = tt sprintf(" ± %.2f", rsd)
        tt = tt sprintf(" (mín %s, máx %s) · %d execuções", fmt(rmn), fmt(rmx), c)
        printf "<g><title>%s</title>\n", tt
        printf "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"transparent\" stroke-width=\"14\" pointer-events=\"stroke\"/>\n", x0, yref, x1, yref
        printf "<line class=\"ref-cas\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"var(--series-%d)\" stroke-width=\"2\" stroke-dasharray=\"6 4\" stroke-linecap=\"round\"/>\n</g>\n", x0, yref, x1, yref, slot["cas"]
    }

    # 1) curvas (médias; o cas é a referência acima), 2) mín–máx e ±desvio, 3) marcadores por cima
    for (a = 1; a <= na; a++) {
        if (A[a] == "cas") continue
        pts = ""; np = 0
        for (l = 1; l <= nl; l++) {
            k = A[a] SUBSEP L[l] SUBSEP G[n]
            if (!(k in cnt)) continue
            mean = (m == "o") ? mo[k] : mp[k]
            xc = xpos(L[l], x0, x1)
            py = y1 - mean / top * (y1 - y0)
            pts = pts sprintf("%.1f,%.1f ", xc, py); np++
        }
        if (np >= 2)
            printf "<polyline class=\"serie serie-%s\" points=\"%s\" fill=\"none\" stroke=\"var(--series-%d)\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n", armcls(A[a]), pts, slot[A[a]]
    }
    for (a = 1; a <= na; a++) {
        for (l = 1; l <= nl; l++) {
            k = A[a] SUBSEP L[l] SUBSEP G[n]
            if (!(k in cnt)) continue
            if (m == "o") { mean = mo[k]; sd = sdo[k]; mn = mno[k]; mx = mxo[k] } else { mean = mp[k]; sd = sdp[k]; mn = mnp[k]; mx = mxp[k] }
            px = xpos(L[l], x0, x1)
            ymn = y1 - mn / top * (y1 - y0); ymx = y1 - mx / top * (y1 - y0)
            printf "<path d=\"M%.1f %.1fV%.1fM%.1f %.1fh6M%.1f %.1fh6\" fill=\"none\" stroke=\"var(--series-%d)\" stroke-width=\"1.5\" stroke-opacity=\".6\" stroke-linecap=\"round\"/>\n", px, ymx, ymn, px - 3, ymx, px - 3, ymn, slot[A[a]]
            if (sd > 0) {
                lo = max0(mean - sd); hi = mean + sd
                printf "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"var(--series-%d)\" stroke-width=\"5\" stroke-opacity=\".35\" stroke-linecap=\"round\"/>\n", px, y1 - hi / top * (y1 - y0), px, y1 - lo / top * (y1 - y0), slot[A[a]]
            }
        }
    }
    for (a = 1; a <= na; a++) {
        for (l = 1; l <= nl; l++) {
            k = A[a] SUBSEP L[l] SUBSEP G[n]
            if (!(k in cnt)) continue
            if (m == "o") { mean = mo[k]; sd = sdo[k]; mn = mno[k]; mx = mxo[k] } else { mean = mp[k]; sd = sdp[k]; mn = mnp[k]; mx = mxp[k] }
            px = xpos(L[l], x0, x1)
            py = y1 - mean / top * (y1 - y0)
            tt = sprintf("%s · lag %s ms · N=%s · capacity %s · %d repetições · média %.2f%s", esc(A[a]), L[l], gN[G[n]], gC[G[n]], cnt[k], mean, un)
            if (sd >= 0) tt = tt sprintf(" ± %.2f", sd)
            tt = tt sprintf(" (mín %s, máx %s)", fmt(mn), fmt(mx))
            printf "<g class=\"pt\"><title>%s</title><circle cx=\"%.1f\" cy=\"%.1f\" r=\"12\" fill=\"transparent\" pointer-events=\"all\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"4.5\" fill=\"var(--series-%d)\" stroke=\"var(--surface-1)\" stroke-width=\"2\"/></g>\n", tt, px, py, px, py, slot[A[a]]
        }
    }

    # rótulo direto no fim de cada curva (do cas: no fim da referência); se dois fins estão a menos de 13px, não rotula (a legenda cobre)
    for (a = 1; a <= na; a++) {
        ylast[a] = ""
        if (A[a] == "cas") { if (ref) { ylast[a] = yref; xlast[a] = x1 }; continue }
        for (l = nl; l >= 1; l--) {
            k = A[a] SUBSEP L[l] SUBSEP G[n]
            if (!(k in cnt)) continue
            mean = (m == "o") ? mo[k] : mp[k]
            ylast[a] = y1 - mean / top * (y1 - y0)
            xlast[a] = xpos(L[l], x0, x1)
            break
        }
    }
    for (a = 1; a <= na; a++) {
        if (ylast[a] == "") continue
        ok = 1
        for (b = 1; b <= na; b++) if (b != a && ylast[b] != "" && (ylast[a] - ylast[b] < 13 && ylast[b] - ylast[a] < 13)) ok = 0
        if (ok) printf "<text class=\"rotulo-serie\" x=\"%.1f\" y=\"%.1f\">%s</text>\n", xlast[a] + 9, ylast[a] + 4, esc(A[a])
    }
    printf "</svg>\n"
}

{ sub(/\r$/, ""); if (NR == 1) sub(/^\357\273\277/, "") }
/^[ \t]*$/ { next }

!cab {
    for (i = 1; i <= NF; i++) col[trim($i)] = i
    split("braco lag_ms N capacity confirmed pending oversell p95", req, " ")
    for (i = 1; i <= 8; i++) if (!(req[i] in col)) { printf "ERRO: coluna obrigatória ausente no CSV: %s\n", req[i] > "/dev/stderr"; erro = 1 }
    if (erro) exit 1
    cab = 1
    next
}

{
    b = trim($col["braco"]); lag = trim($col["lag_ms"]); nn_ = trim($col["N"])
    if (b == "" || lag == "" || nn_ == "") { ignoradas++; next }
    lag = (lag + 0) ""; nn_ = (nn_ + 0) ""
    if (!(b in slot)) {
        slot[b] = (b == "cas") ? 1 : (b == "naive") ? 2 : 2 + (++extra)
        if (slot[b] > 4) { printf "ERRO: mais de 4 braços no CSV (%s)\n", b > "/dev/stderr"; erro = 1; exit 1 }
    }
    lagset[lag] = 1
    # grupo = (N, capacity): o CSV acumula execuções, e capacities diferentes não se misturam na média
    cap = (trim($col["capacity"]) + 0) ""
    gid = nn_ "|" cap
    if (!(gid in gN)) { gN[gid] = nn_ + 0; gC[gid] = cap + 0; ng++; G[ng] = gid }
    k = b SUBSEP lag SUBSEP gid
    i = ++cnt[k]
    o = $col["oversell"] + 0; p = $col["p95"] + 0; at = $col["confirmed"] + $col["pending"]
    ov[k, i] = o; pv[k, i] = p
    so[k] += o; sp[k] += p; sa[k] += at
    if (i == 1 || o < mno[k]) mno[k] = o
    if (i == 1 || o > mxo[k]) mxo[k] = o
    if (i == 1 || p < mnp[k]) mnp[k] = p
    if (i == 1 || p > mxp[k]) mxp[k] = p
    if (("lag_obs_disparo" in col) && trim($col["lag_obs_disparo"]) != "" && $col["lag_obs_disparo"] + 0 >= 0) { sld[k] += $col["lag_obs_disparo"]; nld[k]++ }
    if (("lag_obs_max" in col) && trim($col["lag_obs_max"]) != "" && $col["lag_obs_max"] + 0 >= 0) { slm[k] += $col["lag_obs_max"]; nlm[k]++ }
    if ("commit" in col) { cm = trim($col["commit"]); if (cm != "" && !(cm in commitset)) { commitset[cm] = 1; ncommit++; commits = commits (commits == "" ? "" : ", ") cm } }
    total++
}

END {
    if (erro) exit 1
    if (!total) { print "ERRO: o CSV não tem nenhuma execução (só cabeçalho ou linhas incompletas)" > "/dev/stderr"; exit 1 }
    if (ignoradas) printf "AVISO: %d linha(s) incompleta(s) ignorada(s)\n", ignoradas > "/dev/stderr"

    for (b in slot) bySlot[slot[b]] = b
    for (s = 1; s <= 4; s++) if (s in bySlot) A[++na] = bySlot[s]
    for (x in lagset) L[++nl] = x + 0
    ordenar(L, nl)
    lmax = L[nl]; if (lmax <= 0) lmax = 1   # domínio do eixo x: de 0 ao maior lag
    ordenar_grupos(ng)

    for (k in cnt) {
        c = cnt[k]
        mo[k] = so[k] / c; mp[k] = sp[k] / c; ma[k] = sa[k] / c
        sdo[k] = -1; sdp[k] = -1   # -1 = sem dispersão (n = 1)
        if (c > 1) {
            s1 = 0; s2 = 0
            for (i = 1; i <= c; i++) { d = ov[k, i] - mo[k]; s1 += d * d; e = pv[k, i] - mp[k]; s2 += e * e }
            sdo[k] = sqrt(s1 / (c - 1)); sdp[k] = sqrt(s2 / (c - 1))
        }
    }

    printf "<div class=\"viz-root\">\n<h1>Matriz do experimento — overselling × lag</h1>\n"
    printf "<p class=\"subtitulo\">Fonte: %s · %d execuções · commit: %s · gerado em %s</p>\n", esc(fonte), total, (commits == "" ? "n/d" : esc(commits)), gerado
    if (ncommit > 1) printf "<p class=\"aviso\">AVISO: o CSV mistura mais de um commit (%s); host e gerador devem estar no mesmo commit, e as execuções devem ser comparáveis.</p>\n", esc(commits)
    printf "<div class=\"legenda\">"
    for (a = 1; a <= na; a++) printf "<span class=\"item\"><span class=\"chave chave-%s\"></span>%s%s</span>", armcls(A[a]), esc(A[a]), (A[a] == "cas" ? " · independe do lag" : "")
    printf "</div>\n"
    printf "<p class=\"nota\">Marcador = média das repetições · barra grossa = ±1 desvio amostral · linha fina = mín–máx · eixo x linear em ms, a partir de 0 (a posição é proporcional ao lag; marcas nos lags medidos).%s Overselling = max(0, confirmed − capacity); valores exatos na tabela.</p>\n", ("cas" in slot ? " A linha tracejada do cas é a média de todas as suas execuções do grupo, repetida em todos os lags: o cas não lê o read model, então não depende do lag; os marcadores mostram onde ele foi medido." : "")

    for (n = 1; n <= ng; n++) {
        printf "<h2>N = %s · capacity %s</h2>\n<div class=\"paineis\">\n", gN[G[n]], gC[G[n]]
        printf "<div class=\"painel\"><h3>Overselling · max(0, confirmed − capacity)</h3>\n"; painel(n, "o"); printf "</div>\n"
        printf "<div class=\"painel\"><h3>Latência p95 da reserva (ms)</h3>\n"; painel(n, "p"); printf "</div>\n</div>\n"
    }

    printf "<h2>Tabela</h2>\n<div class=\"tabela\">\n<table>\n<thead><tr><th>braço</th><th>lag_ms</th><th>N</th><th>capacity</th><th>rep.</th><th>oversell média</th><th>desvio</th><th>mín</th><th>máx</th><th>p95 média (ms)</th><th>pending+confirmed média</th><th>lag obs. disparo (ms)</th><th>lag obs. máx (ms)</th></tr></thead>\n<tbody>\n"
    for (n = 1; n <= ng; n++) for (l = 1; l <= nl; l++) for (a = 1; a <= na; a++) {
        k = A[a] SUBSEP L[l] SUBSEP G[n]
        if (!(k in cnt)) continue
        printf "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%d</td><td>%.2f</td><td>%s</td><td>%s</td><td>%s</td><td>%.1f</td><td>%.2f</td><td>%s</td><td>%s</td></tr>\n", esc(A[a]), L[l], gN[G[n]], gC[G[n]], cnt[k], mo[k], (sdo[k] >= 0 ? sprintf("%.2f", sdo[k]) : "–"), fmt(mno[k]), fmt(mxo[k]), mp[k], ma[k], (nld[k] ? sprintf("%.1f", sld[k] / nld[k]) : "–"), (nlm[k] ? sprintf("%.1f", slm[k] / nlm[k]) : "–")
    }
    printf "</tbody>\n</table>\n</div>\n"
    printf "<footer>Gerado por docs/carga/harness/report-matriz.sh a partir de %s. lag obs. = now() − refreshed_at do evento (média das execuções com leitura; “–” = sem leitura). Desvio amostral (n−1); “–” quando há 1 repetição.</footer>\n</div>\n", esc(fonte)
}
' "$CSV" >> "$TMP" || exit 1

printf '</body>\n</html>\n' >> "$TMP"
cp "$TMP" "$OUT"
echo "==> report-matriz.sh: HTML final em $OUT" >&2
