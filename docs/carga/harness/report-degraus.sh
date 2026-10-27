#!/bin/bash

# =============================================================================
#  report-degraus.sh · gráfico da escada de carga e decisão do limiar X (issue #14)
# =============================================================================
#
#  Lê o CSV da escada (Projeto/carga/results/degraus-<id>.csv, gerado por
#  `make test-degraus`: uma linha por degrau, UM arquivo por escada) e gera um HTML
#  autocontido com SVG inline (sem lib externa, no mesmo espírito do report-matriz.sh):
#
#    - 3 painéis, todos com o RPM alvo (linear) no eixo x: latência da reserva (p50,
#      p95, p99 e o pior p95 de janela de 30 s), throughput (reservas/s) e taxa de 5xx das reservas.
#      Latência e throughput têm escalas diferentes: painéis separados, nunca eixo duplo;
#    - o veredito do limiar X, a marca do degrau X (ou do limite inferior) nos painéis e
#      a "table view" com todos os degraus e a situação de cada um.
#
#  DEFINIÇÃO DE X (a ÚNICA implementação da regra; o runner só grava as colunas):
#    degradou  = taxa_5xx > 1%  OU  p95_max_janela_30s > 1000 ms           (limites estritos)
#                taxa_5xx = 5xx das RESERVAS / reservas do degrau (o runner a grava); os 5xx da confirmação ficam em
#                erros_5xx_confirma, só informativos: X decide quando a RESERVA iria para a fila
#    saturou   = iteracoes_descartadas > 0  OU  rpm_efetivo < 95% do alvo  (tolerância TOL_PCT)
#    - nenhum degrau saturou antes do 1º que degradou -> X = esse degrau (firme)
#    - o gerador saturou ESTRITAMENTE antes (ou nenhum degradou) -> X é só LIMITE INFERIOR:
#      X > último degrau sem saturação (o antecessor do 1º degrau saturado)
#    - saturou já no 1º degrau -> sem base (nem limite inferior)
#    - degradou e saturou no MESMO degrau -> X firme, com aviso (as iterações descartadas
#      podem vir do próprio host lento: VUs esgotados por latência alta; o 5xx é sinal do host)
#    - nenhum degradou nem saturou -> "X acima do último degrau"
#
#  Colunas lidas por NOME. Obrigatórias: rpm_alvo, rpm_efetivo, reservas, taxa_5xx, p50, p95, p99, p95_max_janela_30s,
#  throughput, iteracoes_descartadas. A coluna `mapeamento` (sequencial | intercalado; issue #113, última do CSV) é
#  OPCIONAL: só aparece no subtítulo. CSV antigo, sem ela, continua valendo e mostra "mapeamento n/d" (não se assume
#  "sequencial": há CSV histórico feito com o mapeamento intercalado por edição local do script).
#
#  Uso:  report-degraus.sh <degraus-<id>.csv> <saida.html>
#  Roda em qualquer bash com awk (o Git Bash do gerador serve). Auto-teste: report-degraus-selftest.sh.
#
#  Cores: séries 1..4 por token, na ordem (a cor segue a entidade); mesma paleta validada do
#  report-matriz.sh (skill dataviz); validate_palette.js nas 4 séries: claro e escuro passam; no claro o
#  contraste das séries 3 e 4 com a superfície fica abaixo de 3:1 (WARN), compensado por legenda, rótulo
#  direto, tooltip e tabela. A marca do X é texto + traço, não só cor.
# =============================================================================

set -uo pipefail
export LC_ALL=C   # ponto decimal no printf do awk, independente do locale

if [ $# -ne 2 ]; then
    echo "uso: $0 <degraus-<id>.csv> <saida.html>" >&2
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
<title>Escada de carga — limiar X</title>
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
  .veredito { color: var(--text-primary); font-size: 15px; font-weight: 600; margin: 16px 0; padding: 8px 12px; border-left: 3px solid var(--text-secondary); }
  .legenda { display: flex; flex-wrap: wrap; gap: 4px 14px; margin: 0 0 4px; font-size: 12px; color: var(--text-secondary); }
  .item { display: inline-flex; align-items: center; gap: 6px; }
  .chave { display: inline-block; width: 16px; height: 3px; border-radius: 2px; }
  .chave-s1 { background: var(--series-1); }
  .chave-s2 { background: var(--series-2); }
  .chave-s3 { background: var(--series-3); }
  .chave-s4 { background: var(--series-4); }
  .chave-ref { background: repeating-linear-gradient(90deg, var(--text-muted) 0 6px, transparent 6px 10px); }
  .paineis { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; }
  svg { display: block; overflow: visible; }
  .grid { stroke: var(--grid); stroke-width: 1; }
  .baseline { stroke: var(--baseline); stroke-width: 1; }
  .eixo { fill: var(--text-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
  .rotulo-serie { fill: var(--text-secondary); font-size: 11px; }
  .rotulo-x { fill: var(--text-primary); font-size: 11px; font-weight: 600; }
  .marca-x { stroke: var(--text-secondary); stroke-width: 1.5; }
  .tabela { overflow-x: auto; }
  table { border-collapse: collapse; width: 100%; font-size: 12px; }
  th, td { text-align: right; padding: 4px 8px; border-bottom: 1px solid var(--grid); font-variant-numeric: tabular-nums; white-space: nowrap; }
  th:first-child, td:first-child { text-align: left; }
  th:last-child, td:last-child { text-align: left; white-space: normal; min-width: 220px; }
  th { color: var(--text-muted); font-weight: 500; white-space: normal; vertical-align: bottom; }
  .degrau-x td, .degrau-limite td { font-weight: 600; }
  footer { margin-top: 28px; font-size: 11px; color: var(--text-muted); }
</style>
</head>
<body>
HTML

awk -v fonte="${CSV##*/}" -v gerado="$(date -u '+%Y-%m-%dT%H:%M:%SZ')" '
BEGIN {
    FS = ","
    LIM_5XX = 0.01      # X: taxa_5xx (5xx das reservas / reservas) > 1%  (fração)
    LIM_P95 = 1000      # X: p95_max_janela_30s > 1 s (ms)
    TOL_PCT = 5         # gerador saturado: rpm_efetivo < (100 - TOL_PCT)% do alvo
}

function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
function esc(s) { gsub(/&/, "\\&amp;", s); gsub(/</, "\\&lt;", s); gsub(/>/, "\\&gt;", s); gsub(/"/, "\\&quot;", s); return s }
function fmt(v) { return sprintf("%g", v) }
function kfmt(v) { return (v % 1000 == 0) ? sprintf("%dk", v / 1000) : sprintf("%d", v) }

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

# regra do X (os dados vêm do CSV, em ordem de RPM alvo: o é o índice original)
function degradou(o) { return (t5[o] > LIM_5XX || (pj[o] >= 0 && pj[o] > LIM_P95)) }
# rpm_efetivo * 100 < alvo * 95: inteiros, sem erro de ponto flutuante na borda de exatamente 95%
function saturou(o) { return (desc[o] > 0 || efe[o] * 100 < alvo[o] * (100 - TOL_PCT)) }

# x de um RPM alvo no eixo LINEAR (do menor ao maior degrau), com folga nas pontas
function xpos(rpm) { return (rmax > rmin) ? xl + (rpm - rmin) / (rmax - rmin) * (xr - xl) : (xl + xr) / 2 }

# situação de um degrau (tabela): por que degradou / saturou
function situacao(o,   s, sat) {
    s = ""
    if (t5[o] > LIM_5XX) s = s (s == "" ? "" : "; ") "5xx das reservas &gt; 1%"
    if (pj[o] >= 0 && pj[o] > LIM_P95) s = s (s == "" ? "" : "; ") "p95 de janela &gt; 1 s"
    sat = ""
    if (desc[o] > 0) sat = "iterações descartadas"
    if (efe[o] * 100 < alvo[o] * (100 - TOL_PCT)) sat = sat (sat == "" ? "" : ", ") "RPM efetivo &lt; " (100 - TOL_PCT) "% do alvo"
    if (sat != "") s = s (s == "" ? "" : "; ") "gerador saturado (" sat ")"
    if (out[o] > 0) s = s (s == "" ? "" : "; ") "outros &gt; 0"
    return (s == "" ? "OK" : s)
}

# (painel(id) e legenda(id) usam o que carrega(id) deixou nas globais)
# dados de um painel em ns séries SN[s] / SV[s,k] (k = posição ordenada; -1 = sem valor),
# referência RV[k] (limiar ou carga oferecida), unidade UN, casas decimais DEC
function carrega(id,   k, o) {
    ns = (id == 1) ? 4 : (id == 2) ? 2 : 1
    UN = (id == 1) ? " ms" : (id == 2) ? " reservas/s" : "%"
    DEC = (id == 3) ? "%.2f" : "%.1f"
    RN = (id == 1) ? "limiar: p95 = 1 s" : (id == 2) ? "carga oferecida (alvo)" : "limiar: 5xx das reservas = 1%"
    RCONST = (id != 2)
    if (id == 1) { SN[1] = "p50"; SN[2] = "p95"; SN[3] = "p99"; SN[4] = "p95 janela" }
    else if (id == 2) { SN[1] = "throughput"; SN[2] = "enviadas" }
    else { SN[1] = "5xx" }
    for (k = 1; k <= n; k++) {
        o = ord[k]
        if (id == 1) { SV[1, k] = p50[o]; SV[2, k] = p95[o]; SV[3, k] = p99[o]; SV[4, k] = (pj[o] >= 0 ? pj[o] : -1); RV[k] = LIM_P95 }
        else if (id == 2) { SV[1, k] = thr[o]; SV[2, k] = efe[o] / 60; RV[k] = alvo[o] / 60 }
        else { SV[1, k] = t5[o] * 100; RV[k] = LIM_5XX * 100 }
    }
}

function legenda(id,   s) {
    printf "<div class=\"legenda\">"
    for (s = 1; s <= ns; s++) printf "<span class=\"item\"><span class=\"chave chave-s%d\"></span>%s</span>", s, esc(LEG[id, s])
    printf "<span class=\"item\"><span class=\"chave chave-ref\"></span>%s</span></div>\n", RN
}

function painel(id,   W, H, x0, x1, y0, y1, s, k, v, vmax, step, top, t, i, py, px, pts, np, tt, xm, anc, ok, b, ylast, xlast, xlab, xc, rotulo) {
    W = 420; H = 250; x0 = 52; x1 = W - 70; y0 = 14; y1 = H - 36
    xl = x0 + 14; xr = x1 - 14
    vmax = 0
    for (s = 1; s <= ns; s++) for (k = 1; k <= n; k++) if (SV[s, k] > vmax) vmax = SV[s, k]
    for (k = 1; k <= n; k++) if (RV[k] > vmax) vmax = RV[k]
    step = passo(vmax)
    t = int(vmax / step)
    if (t * step < vmax - 1e-9) t++
    if (t < 1) t = 1
    top = step * t
    rotulo = (id == 1) ? "Latência × RPM alvo" : (id == 2) ? "Throughput × RPM alvo" : "Taxa de 5xx das reservas × RPM alvo"
    printf "<svg viewBox=\"0 0 %d %d\" width=\"100%%\" role=\"img\" aria-label=\"%s\">\n", W, H, rotulo

    # grade horizontal (hairline sólida) + rótulos do eixo y; o 0 é a linha de base
    for (i = 0; i <= t; i++) {
        v = i * step
        py = y1 - v / top * (y1 - y0)
        printf "<line class=\"%s\" x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\"/>\n", (i == 0 ? "baseline" : "grid"), x0, py, x1, py
        printf "<text class=\"eixo\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>\n", x0 - 8, py + 4, fmt(v)
    }
    # eixo x linear: marca em cada degrau; rótulo só se couber (>= 32px do anterior)
    xlab = -1000
    for (k = 1; k <= n; k++) {
        xc = xpos(alvo[ord[k]])
        printf "<line class=\"baseline\" x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\"/>\n", xc, y1, xc, y1 + 4
        if (xc - xlab < 32) continue
        xlab = xc
        printf "<text class=\"eixo eixo-x\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>\n", xc, y1 + 16, kfmt(alvo[ord[k]])
    }
    printf "<text class=\"eixo\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">RPM alvo (reservas/min) · escala linear</text>\n", (x0 + x1) / 2, H - 4

    # referência tracejada (limiar ou carga oferecida), com tooltip
    pts = ""
    if (RCONST) {
        py = y1 - RV[1] / top * (y1 - y0)
        pts = sprintf("%.1f,%.1f %.1f,%.1f", x0, py, x1, py)
    } else if (n >= 2) {
        for (k = 1; k <= n; k++) pts = pts sprintf("%.1f,%.1f ", xpos(alvo[ord[k]]), y1 - RV[k] / top * (y1 - y0))
    }
    if (pts != "") {
        printf "<g class=\"ref\"><title>%s</title>\n", RN
        printf "<polyline points=\"%s\" fill=\"none\" stroke=\"transparent\" stroke-width=\"14\" pointer-events=\"stroke\"/>\n", pts
        printf "<polyline points=\"%s\" fill=\"none\" stroke=\"var(--text-muted)\" stroke-width=\"1.5\" stroke-dasharray=\"6 4\" stroke-linecap=\"round\"/>\n</g>\n", pts
    }

    # X (ou limite inferior): traço vertical + rótulo em texto (não só cor)
    if (tipo == "firme" || tipo == "limite-inferior") {
        xm = xpos(alvo[ord[xk]])
        printf "<line class=\"marca-x\" x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\"/>\n", xm, y0, xm, y1
        anc = (xm > W / 2) ? "end" : "start"
        printf "<text class=\"rotulo-x\" x=\"%.1f\" y=\"%d\" text-anchor=\"%s\">%s</text>\n", (anc == "end" ? xm - 5 : xm + 5), y0 + 10, anc, (tipo == "firme" ? "X" : "X &gt; " kfmt(alvo[ord[xk]]))
    }

    # séries: linha de 2px (só entre pontos com valor), depois os marcadores com anel da cor da superfície
    for (s = 1; s <= ns; s++) {
        pts = ""; np = 0
        for (k = 1; k <= n; k++) {
            if (SV[s, k] < 0) continue
            pts = pts sprintf("%.1f,%.1f ", xpos(alvo[ord[k]]), y1 - SV[s, k] / top * (y1 - y0)); np++
        }
        if (np >= 2) printf "<polyline class=\"serie\" points=\"%s\" fill=\"none\" stroke=\"var(--series-%d)\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n", pts, s
    }
    for (s = 1; s <= ns; s++) for (k = 1; k <= n; k++) {
        if (SV[s, k] < 0) continue
        px = xpos(alvo[ord[k]]); py = y1 - SV[s, k] / top * (y1 - y0)
        tt = sprintf("%s · %d RPM alvo · " DEC "%s", LEG[id, s], alvo[ord[k]], SV[s, k], UN)
        printf "<g class=\"pt\" data-rpm=\"%d\"><title>%s</title><circle cx=\"%.1f\" cy=\"%.1f\" r=\"12\" fill=\"transparent\" pointer-events=\"all\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"4.5\" fill=\"var(--series-%d)\" stroke=\"var(--surface-1)\" stroke-width=\"2\"/></g>\n", alvo[ord[k]], esc(tt), px, py, px, py, s
    }

    # rótulo direto no fim de cada série; se dois fins estão a menos de 13px, não rotula (a legenda cobre)
    for (s = 1; s <= ns; s++) {
        ylast[s] = ""
        for (k = n; k >= 1; k--) if (SV[s, k] >= 0) { ylast[s] = y1 - SV[s, k] / top * (y1 - y0); xlast[s] = xpos(alvo[ord[k]]); break }
    }
    for (s = 1; s <= ns; s++) {
        if (ylast[s] == "") continue
        ok = 1
        for (b = 1; b <= ns; b++) if (b != s && ylast[b] != "" && (ylast[s] - ylast[b] < 13 && ylast[b] - ylast[s] < 13)) ok = 0
        if (ok) printf "<text class=\"rotulo-serie\" x=\"%.1f\" y=\"%.1f\">%s</text>\n", xlast[s] + 9, ylast[s] + 4, esc(SN[s])
    }
    printf "</svg>\n"
}

{ sub(/\r$/, ""); if (NR == 1) sub(/^\357\273\277/, "") }
/^[ \t]*$/ { next }

!cab {
    for (i = 1; i <= NF; i++) col[trim($i)] = i
    # CSV antigo: taxa_5xx era (5xx da reserva + da confirma) / (reservas + confirmas), diluída pelas confirmações
    if ("requisicoes" in col) { printf "ERRO: CSV no formato antigo (coluna requisicoes): a taxa_5xx incluía as confirmações; agora é 5xx das reservas / reservas. Gere a escada de novo com o rodar-degraus.ps1 atual.\n" > "/dev/stderr"; erro = 1; exit 1 }
    # reservas: o denominador da taxa_5xx (e a marca do formato novo)
    nreq = split("rpm_alvo rpm_efetivo reservas taxa_5xx p50 p95 p99 p95_max_janela_30s throughput iteracoes_descartadas", req, " ")
    for (i = 1; i <= nreq; i++) if (!(req[i] in col)) { printf "ERRO: coluna obrigatória ausente no CSV: %s\n", req[i] > "/dev/stderr"; erro = 1 }
    if (erro) exit 1
    cab = 1
    next
}

{
    r = trim($col["rpm_alvo"])
    if (r == "") { ignoradas++; next }
    if ((r + 0) in visto) { printf "ERRO: rpm_alvo repetido no CSV: %s (um CSV por escada)\n", r > "/dev/stderr"; erro = 1; exit 1 }
    visto[r + 0] = 1
    n++
    alvo[n] = r + 0
    efe[n] = $col["rpm_efetivo"] + 0
    t5[n] = $col["taxa_5xx"] + 0
    p50[n] = $col["p50"] + 0; p95[n] = $col["p95"] + 0; p99[n] = $col["p99"] + 0
    pj[n] = (trim($col["p95_max_janela_30s"]) == "") ? -1 : $col["p95_max_janela_30s"] + 0
    thr[n] = $col["throughput"] + 0
    desc[n] = $col["iteracoes_descartadas"] + 0
    out[n] = (("outros" in col) && trim($col["outros"]) != "") ? $col["outros"] + 0 : 0
    if (("escada" in col) && trim($col["escada"]) != "") { e = trim($col["escada"]); if (!(e in escset)) { escset[e] = 1; nesc++; escadas = escadas (escadas == "" ? "" : ", ") e; escada = e } }
    if (("duracao_s" in col) && !dur) dur = $col["duracao_s"] + 0
    if (("braco" in col) && braco == "") braco = trim($col["braco"])
    if (("max_connections" in col) && maxc == "") maxc = trim($col["max_connections"])
    if (("pool_hikari" in col) && pool == "") pool = trim($col["pool_hikari"])
    if (("mapeamento" in col) && mapeamento == "") mapeamento = trim($col["mapeamento"])
    if (("commit" in col) && trim($col["commit"]) != "") { cm = trim($col["commit"]); if (!(cm in commitset)) { commitset[cm] = 1; ncommit++; commits = commits (commits == "" ? "" : ", ") cm } }
}

END {
    if (erro) exit 1
    if (!n) { print "ERRO: o CSV não tem nenhum degrau (só cabeçalho ou linhas incompletas)" > "/dev/stderr"; exit 1 }
    if (nesc > 1) { printf "ERRO: o CSV mistura mais de uma escada (%s): gere um CSV por escada\n", escadas > "/dev/stderr"; exit 1 }
    if (ignoradas) printf "AVISO: %d linha(s) incompleta(s) ignorada(s)\n", ignoradas > "/dev/stderr"

    # ordem por RPM alvo (inserção; n pequeno): ord[k] = índice original do k-ésimo degrau
    for (k = 1; k <= n; k++) ord[k] = k
    for (k = 2; k <= n; k++) { v = ord[k]; for (j = k - 1; j >= 1 && alvo[ord[j]] > alvo[v]; j--) ord[j + 1] = ord[j]; ord[j + 1] = v }
    rmin = alvo[ord[1]]; rmax = alvo[ord[n]]

    # ---- o limiar X
    fx = 0; fs = 0
    for (k = 1; k <= n; k++) { if (!fx && degradou(ord[k])) fx = k; if (!fs && saturou(ord[k])) fs = k }
    xk = 0
    if (fs && (!fx || fs < fx)) {
        if (fs == 1) tipo = "sem-base"; else { tipo = "limite-inferior"; xk = fs - 1 }
    } else if (fx) { tipo = "firme"; xk = fx }
    else { tipo = "nao-degradou"; xk = n }

    for (k = 1; k <= n; k++) if (out[ord[k]] > 0) noutros++

    printf "<div class=\"viz-root\">\n<h1>Escada de carga — limiar X</h1>\n"
    sub_ = "Fonte: " esc(fonte) " · "
    if (escada != "") sub_ = sub_ "escada " esc(escada) " · "
    sub_ = sub_ n " " (n == 1 ? "degrau" : "degraus") " de " (dur ? dur : "n/d") " s"
    if (braco != "") sub_ = sub_ " · braço " esc(braco)
    if (maxc != "") sub_ = sub_ " · max_connections " esc(maxc)
    if (pool != "") sub_ = sub_ " · pool HikariCP " esc(pool)
    sub_ = sub_ " · commit " (commits == "" ? "n/d" : esc(commits))
    sub_ = sub_ " · mapeamento " (mapeamento == "" ? "n/d" : esc(mapeamento))
    printf "<p class=\"subtitulo\">%s · gerado em %s</p>\n", sub_, gerado
    if (ncommit > 1) printf "<p class=\"aviso\">AVISO: o CSV mistura mais de um commit (%s): host e gerador devem estar no mesmo commit.</p>\n", esc(commits)
    if (noutros) printf "<p class=\"aviso\">AVISO: respostas inesperadas ou falhas de conexão (outros &gt; 0) em %d %s: não entram no critério de X; confira o resumo do k6 desses degraus.</p>\n", noutros, (noutros == 1 ? "degrau" : "degraus")

    # ---- veredito
    if (tipo == "firme") {
        o = ord[fx]
        mot = ""; dm = ""
        if (t5[o] > LIM_5XX) { mot = sprintf("5xx das reservas &gt; 1%% (%.2f%%)", t5[o] * 100); dm = "5xx" }
        if (pj[o] >= 0 && pj[o] > LIM_P95) { mot = mot (mot == "" ? "" : " e ") sprintf("p95 &gt; 1 s numa janela de 30 s (%.1f ms)", pj[o]); dm = dm (dm == "" ? "" : "+") "p95" }
        satx = saturou(o) ? 1 : 0
        printf "<p class=\"veredito\" data-tipo=\"firme\" data-x=\"%d\" data-motivo=\"%s\" data-saturado=\"%d\">X = %d RPM: primeiro degrau com %s.", alvo[o], dm, satx, alvo[o], mot
        if (satx) printf " Atenção: houve iterações descartadas ou RPM efetivo abaixo do alvo no próprio degrau; confira se o gerador contribuiu."
        printf "</p>\n"
    } else if (tipo == "limite-inferior") {
        printf "<p class=\"veredito\" data-tipo=\"limite-inferior\" data-x=\"%d\" data-saturou=\"%d\">X &gt; %d RPM (limite inferior): o gerador saturou em %d RPM (iterações descartadas ou RPM efetivo abaixo do alvo) antes de o host degradar.", alvo[ord[xk]], alvo[ord[fs]], alvo[ord[xk]], alvo[ord[fs]]
        if (fx) printf " O host só degradou em %d RPM, já com o gerador saturado: esses números não separam host de gerador.", alvo[ord[fx]]
        printf "</p>\n"
    } else if (tipo == "nao-degradou") {
        printf "<p class=\"veredito\" data-tipo=\"nao-degradou\" data-x=\"%d\">Nenhum degrau degradou até %d RPM: X &gt; %d RPM (o máximo da escada).</p>\n", rmax, rmax, rmax
    } else {
        printf "<p class=\"veredito\" data-tipo=\"sem-base\" data-saturou=\"%d\">O gerador saturou já no primeiro degrau (%d RPM): sem limite inferior para X.</p>\n", alvo[ord[1]], alvo[ord[1]]
    }

    # ---- painéis
    LEG[1, 1] = "p50"; LEG[1, 2] = "p95 do degrau"; LEG[1, 3] = "p99"; LEG[1, 4] = "p95 da pior janela de 30 s"
    LEG[2, 1] = "throughput (reservas/s sem 5xx)"; LEG[2, 2] = "enviadas (RPM efetivo / 60)"
    LEG[3, 1] = "taxa de 5xx das reservas (%)"
    printf "<div class=\"paineis\">\n"
    printf "<div class=\"painel\"><h3>Latência da reserva (ms)</h3>\n"; carrega(1); legenda(1); painel(1); printf "</div>\n"
    printf "<div class=\"painel\"><h3>Throughput (reservas/s)</h3>\n"; carrega(2); legenda(2); painel(2); printf "</div>\n"
    printf "<div class=\"painel\"><h3>Taxa de 5xx das reservas (%%)</h3>\n"; carrega(3); legenda(3); painel(3); printf "</div>\n"
    printf "</div>\n"
    printf "<p class=\"nota\">Eixo x linear em RPM alvo (reservas por minuto), um marcador por degrau. Tracejado = limiar do critério (latência, 5xx) ou a carga oferecida (throughput). O traço vertical com rótulo marca o degrau X (ou o limite inferior). Valores exatos na tabela.</p>\n"

    # ---- tabela
    printf "<h2>Tabela</h2>\n<div class=\"tabela\">\n<table>\n<thead><tr><th>RPM alvo</th><th>RPM efetivo</th><th>5xx das reservas (%%)</th><th>p50 (ms)</th><th>p95 (ms)</th><th>p99 (ms)</th><th>p95 pior janela 30 s (ms)</th><th>throughput (reservas/s)</th><th>descartadas</th><th>outros</th><th>situação</th></tr></thead>\n<tbody>\n"
    for (k = 1; k <= n; k++) {
        o = ord[k]
        cls = ""; pre = ""
        if ((tipo == "firme") && k == xk) { cls = " class=\"degrau-x\""; pre = "X · " }
        else if ((tipo == "limite-inferior") && k == xk) { cls = " class=\"degrau-limite\""; pre = "limite inferior de X · " }
        printf "<tr%s><td>%d</td><td>%.0f</td><td>%.2f</td><td>%.1f</td><td>%.1f</td><td>%.1f</td><td>%s</td><td>%.1f</td><td>%d</td><td>%d</td><td>%s%s</td></tr>\n", cls, alvo[o], efe[o], t5[o] * 100, p50[o], p95[o], p99[o], (pj[o] >= 0 ? sprintf("%.1f", pj[o]) : "n/d"), thr[o], desc[o], out[o], pre, situacao(o)
    }
    printf "</tbody>\n</table>\n</div>\n"
    printf "<footer>Gerado por docs/carga/harness/report-degraus.sh a partir de %s. X = primeiro degrau com taxa de 5xx das reservas &gt; 1%% (5xx das reservas / reservas do degrau; os 5xx da confirmação não entram) OU p95 &gt; 1 s numa janela de 30 s (limites estritos). Gerador saturado = iterações descartadas &gt; 0 OU RPM efetivo &lt; %d%% do alvo; se isso ocorre antes do degrau X, X é só um limite inferior (o último degrau sem saturação). “n/d” = degrau sem janela de 30 s com amostras.</footer>\n</div>\n", esc(fonte), 100 - TOL_PCT
}
' "$CSV" >> "$TMP" || exit 1

printf '</body>\n</html>\n' >> "$TMP"
cp "$TMP" "$OUT"
echo "==> report-degraus.sh: HTML final em $OUT" >&2
