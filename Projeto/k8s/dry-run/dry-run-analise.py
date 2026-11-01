#!/usr/bin/env python3
"""Analise do dry-run de carga com autoescala (issue #197, etapa 4b). Uso: dry-run-analise.py <pasta saida do dry-run.sh>

Le o que o dry-run.sh grava (replicas.csv, fases.csv, no-stats.csv, k6-*.log, eventos/pods do kubectl, mensagens do Mailpit, SQL) e imprime os criterios do roteiro com [ok] / [ERRO] e os
numeros reais. Codigo de saida 0 so com todos ok. So stdlib.
"""
import csv
import datetime
import glob
import json
import os
import re
import sys

d = sys.argv[1]
SERVICOS = ["backend", "identity", "catalog", "reservation", "stock", "notification", "admission"]
falhas = 0


def ok(m):
    print("   [ok]    " + m)


def erro(m):
    global falhas
    falhas += 1
    print("   [ERRO]  " + m)


def criterio(cond, m):
    (ok if cond else erro)(m)


def nao_avaliavel(m, motivo="nao avaliavel com estes dados: valera a partir da proxima execucao do dry-run.sh"):
    print("   [n/a]   " + m + " (" + motivo + ")")


def ler(arq):
    p = os.path.join(d, arq)
    return open(p, encoding="utf-8").read().strip() if os.path.exists(p) else ""


def kv(arq):
    r = {}
    for linha in ler(arq).split():
        if "=" in linha:
            a, b = linha.split("=", 1)
            r[a] = b
    return r


par = kv("parametros.txt")
trilha = par.get("trilha", "?")
cri = kv("criterios.txt")

# ---- fases -------------------------------------------------------------------------------------------------------------------------------------------------------
fases = {}
for r in csv.DictReader(open(os.path.join(d, "fases.csv"), encoding="utf-8")):
    fases[(r["fase"], r["evento"])] = int(r["ts"])


def janela(f):
    return fases.get((f, "inicio")), fases.get((f, "fim"))


# ---- replicas ----------------------------------------------------------------------------------------------------------------------------------------------------
serie = {s: [] for s in SERVICOS}  # (ts, prontas, desejadas, metrica)
adm = []  # (ts, valor|None)
for r in csv.DictReader(open(os.path.join(d, "replicas.csv"), encoding="utf-8")):
    try:
        ts = int(r["ts"])
    except ValueError:
        continue
    if r["servico"] == "adm-replicas":
        adm.append((ts, int(r["prontas"]) if r["prontas"].isdigit() else None))
    elif r["servico"] in serie:
        serie[r["servico"]].append((ts, int(r["prontas"] or 0), r["desejadas"], r["metrica"]))

print("=== trilha %s: X=%s cpu-alvo=%s%% janela de descida=%ss ===" % (trilha, par.get("x"), par.get("cpu_alvo"), par.get("desce")))
print("--- subidas e descidas (replicas.csv: %d amostras de reservation, a cada ~2 s) ---" % len(serie["reservation"]))
descidas = []  # (servico, ts)
subidas = {}
maximo = {}
for s in SERVICOS:
    sub = desc = 0
    ant = None
    mx = 0
    for ts, p, _, _ in serie[s]:
        mx = max(mx, p)
        if ant is not None and p > ant:
            sub += 1
        if ant is not None and p < ant:
            # TODA queda de `prontas` conta (descida planejada, OOM, falha de readiness): uma queda sem descida planejada aparece depois como 5xx, restart ou "backend saiu do Service"
            desc += 1
            descidas.append((s, ts))
        ant = p
    subidas[s] = (sub, desc)
    maximo[s] = mx
    print("   %-12s maximo de prontas=%d subidas=%d descidas=%d" % (s, mx, sub, desc))
criterio(maximo["reservation"] >= 2 and subidas["reservation"][1] >= 1, "reservation sobe (max %d) e desce (%d descidas) com a carga no cas (RPM do evento mais quente)" % (maximo["reservation"], subidas["reservation"][1]))
criterio(maximo["reservation"] >= 3, "reservation chegou ao teto de 3 (max %d)" % maximo["reservation"])
# #231: o stock e UMA replica fixa (fora da autoescala, Hikari 245, Recreate): nunca sobe (o CAS na linha quente serializa as reservas) e nunca cai (queda de `prontas` = reinicio/OOM)
criterio(maximo["stock"] == 1 and subidas["stock"][0] == 0 and subidas["stock"][1] == 0, "stock fica em 1 replica o dry-run inteiro, fora da autoescala (max %d, %d subidas, %d descidas)" % (maximo["stock"], subidas["stock"][0], subidas["stock"][1]))
criterio(maximo["notification"] >= 3 and subidas["notification"][1] >= 1, "notification sobe ate 3 (max %d) e volta (%d descidas) pela rajada de e-mails" % (maximo["notification"], subidas["notification"][1]))
criterio(maximo["backend"] >= 2 and subidas["backend"][1] >= 1, "backend sobe (max %d) sob a carga de leitura e volta (%d descidas)" % (maximo["backend"], subidas["backend"][1]))

# ---- 5xx ---------------------------------------------------------------------------------------------------------------------------------------------------------
erros5xx = []  # (ts, status, nome, arquivo)
resumos = {}
for arq in sorted(glob.glob(os.path.join(d, "k6-*.log"))):
    nome = os.path.basename(arq)[3:-4]
    for linha in open(arq, encoding="utf-8", errors="replace"):
        m = re.search(r"ERRO5XX (\d+) (\d+) (\w+)", linha)
        if m:
            erros5xx.append((int(m.group(1)), int(m.group(2)), m.group(3), nome))
        m = re.search(r"RESUMO (\{.*\})", linha)
        if m:
            try:
                resumos[nome] = json.loads(m.group(1).replace('\\"', '"'))
            except ValueError:
                pass
print("--- k6 por fase (respostas 2xx/4xx/5xx; 5xx inclui falha de conexao) ---")
for n in ("fundo", "A", "B", "C1", "C"):
    r = resumos.get(n)
    if r:
        print("   %-6s req=%s 2xx=%s (202=%s) 4xx=%s 5xx=%s p95=%sms descartadas=%s" % (n, r["req"], r["r2xx"], r["r202"], r["r4xx"], r["r5xx"], r["p95_ms"], r["descartadas"]))
    else:
        erro("k6 da fase %s sem RESUMO (o k6 terminou? o RESUMO sai no fim, inclusive no pkill do fundo)" % n)
print("   total de linhas ERRO5XX nos logs: %d (fundo=%d)" % (len(erros5xx), sum(1 for e in erros5xx if e[3] == "fundo")))

# o "0 5xx depois da descida" so vale se o fundo ESTEVE gerando requisicoes durante todas as descidas: sem isso ele passaria vacuo
fundo_rps = float(par.get("fundo_rps", 5))
f0 = fases.get(("fundo", "inicio"))
f1 = fases.get(("E", "fim"))
rf = resumos.get("fundo")
if rf and f0 and f1:
    minimo_req = 0.9 * fundo_rps * (f1 - f0)
    criterio(rf["req"] >= minimo_req, "o trafego de fundo rodou do inicio ao fim: %d requisicoes >= %.0f (90%% de %g req/s x %d s, do inicio do fundo ao fim da fase E)" % (rf["req"], minimo_req, fundo_rps, f1 - f0))
    fundo_ok = rf["req"] >= minimo_req
else:
    erro("sem RESUMO do fundo ou sem as marcas fundo/E em fases.csv: nao da para provar que houve requisicao durante as descidas")
    fundo_ok = False

# so os servicos no caminho do fundo (leitura e reserva: gateway -> catalog -> reservation/stock, admission) podem produzir 5xx de descida que o k6 veja;
# identity (o gateway valida o JWT sem estado; so o cache do creator_name o chama, ~1 a 3x por janela) e notification (consumer Kafka, sem HTTP) ficam so informados
NO_CAMINHO = ("backend", "admission", "reservation", "stock", "catalog")
tot = 0
n_cam = 0
for s_, ts in descidas:
    n = sum(1 for e in erros5xx if ts <= e[0] <= ts + 60)
    if s_ in NO_CAMINHO:
        tot += n
        n_cam += 1
    print("   descida de %-12s em %s: %d 5xx na janela de 60 s%s" % (s_, datetime.datetime.fromtimestamp(ts).strftime("%H:%M:%S"), n, "" if s_ in NO_CAMINHO else "  (informativo: o fundo nao exercita este servico por HTTP)"))
criterio(fundo_ok and n_cam >= 1 and tot == 0, "0 5xx nas janelas de 60 s depois de cada descida dos servicos no caminho do fundo (%d descidas de backend/admission/reservation/stock/catalog, %d 5xx; %d descidas de identity/notification so informadas)" % (n_cam, tot, len(descidas) - n_cam))

# o ERRO5XX leva o relogio do no (a VM do Docker, onde o k6 roda) e as janelas/CSV levam o do host: um desvio maior que a folga da janela esconderia 5xx de descida
desvios = []
for k_ in ("relogio_ini", "relogio_fim"):
    try:
        desvios.append(int(par[k_]))
    except (KeyError, ValueError):
        pass
if len(desvios) == 2:
    criterio(max(abs(x_) for x_ in desvios) <= 5, "relogio do no x host (s, no inicio e no fim): %d e %d; o limite e 5 s" % tuple(desvios))
elif "relogio_ini" in par or "relogio_fim" in par:
    nao_avaliavel("desvio de relogio no x host (relogio_ini='%s' relogio_fim='%s')" % (par.get("relogio_ini"), par.get("relogio_fim")), "medicao ausente ou vazia nesta rodada: o docker exec no no falhou; verificar")
else:
    nao_avaliavel("desvio de relogio no x host (parametros.txt sem relogio_ini/relogio_fim)")

# ---- degrau B (#206) ---------------------------------------------------------------------------------------------------------------------------------------------
b0, b1 = janela("B")
rb = [(ts, p, des, met) for ts, p, des, met in serie["reservation"] if b0 and b0 <= ts <= b1]
if rb:
    inicio = rb[0][1]
    minimo = min(p for _, p, _, _ in rb)
    desc_b = sum(1 for a, b in zip(rb, rb[1:]) if b[1] < a[1])
    mets = []
    for _, _, _, met in rb:
        v = re.match(r"^([0-9.]+)(m?)$", met or "")
        if v:
            mets.append(float(v.group(1)) / (1000 if v.group(2) else 1))
    des_b = [x[2] for x in rb]
    print("--- degrau B: reservation prontas no inicio=%d minimo=%d; metrica do HPA oscilou de %.0f a %.0f; desejadas variou entre %s ---" % (
        inicio, minimo, min(mets) if mets else -1, max(mets) if mets else -1, sorted(set(des_b))))
    criterio(inicio >= 2 and desc_b == 0 and minimo >= inicio, "nenhuma descida do reservation durante o degrau oscilante (#206): prontas %d -> minimo %d, %d descidas" % (inicio, minimo, desc_b))
    # sem a oscilacao o criterio acima seria vacuo. A metrica gravada e o averageValue do HPA (trigger AverageValue: RPM total / replicas atuais, POR POD) e o alvo por pod e 0,5 x X;
    # sem a janela de estabilizacao o HPA desce de `inicio` para `inicio-1` quando ceil(inicio x media / alvo) <= inicio-1, isto e, media x inicio <= (inicio-1) x 0,5 x X
    x = float(par.get("x", 0))
    limiar_b = (inicio - 1) * 0.5 * x
    vale = min(mets) * inicio if mets else -1
    criterio(bool(mets) and x > 0 and vale <= limiar_b, "o degrau oscilou de verdade: metrica minima do HPA %.0f por pod (x %d prontas = %.0f no total) %s %.0f ((prontas-1) x 0,5 x X); so assim, sem a janela de estabilizacao, o HPA teria descido uma replica%s" % (
        min(mets) if mets else -1, inicio, vale, "<=" if vale <= limiar_b else ">", limiar_b, "" if vale <= limiar_b else " (nao desceria: o vale nao foi fundo o bastante, a #206 nao fica provada nesta rodada)"))
else:
    erro("fase B sem amostras de replicas")

# ---- reservation x adm:replicas (D4) -----------------------------------------------------------------------------------------------------------------------------
res = serie["reservation"]
mudancas = [res[i][0] for i in range(1, len(res)) if res[i][1] != res[i - 1][1]]
fora = dentro = 0
bons = 0
j = 0
for ts, v in adm:
    while j + 1 < len(res) and res[j + 1][0] <= ts:
        j += 1
    if not res or res[j][0] > ts:
        continue
    if any(m - 4 <= ts <= m + 8 for m in mudancas):  # 2 s do job do admission + 2 s do registro + latencia do kubectl
        dentro += 1
        continue
    if v == res[j][1]:
        bons += 1
    else:
        fora += 1
print("--- adm:replicas x readyReplicas do reservation: %d amostras iguais, %d diferentes fora das transicoes (-4 a +8 s), %d dentro de transicoes ---" % (bons, fora, dentro))
criterio(bons > 0 and fora == 0, "adm:replicas acompanha o readyReplicas do reservation (fora das transicoes: %d diferencas)" % fora)
criterio(cri.get("adm_replicas_final") == "1", "adm:replicas volta a 1 no fim (obtido '%s')" % cri.get("adm_replicas_final"))

# ---- replica nova recebendo trafego ------------------------------------------------------------------------------------------------------------------------------
rn = kv("replica-nova.txt")
conn = rn.get("pronta_ate_conexao_s", "nenhuma")
novo = rn.get("replica_nova", "")
if trilha == "nenhuma":
    print("--- controle (Service L4): replica nova do reservation %s; primeira conexao na 8082: %s s (janela %s s) ---" % (novo or "(nenhuma)", conn, rn.get("janela_s")))
    print("   (informativo, NAO e criterio: o aceite pede o controle ocioso, mas sob esta carga o L4 tambem leva conexoes NOVAS ao pod novo; a prova do controle ocioso e o passo 7 do e2e, uma conexao so)")
else:
    criterio(bool(novo) and conn != "nenhuma", "trilha %s: a replica nova do reservation (%s) recebeu conexao na 8082 em %s s (janela %s s)" % (trilha, novo or "(nenhuma subiu)", conn, rn.get("janela_s")))

# ---- fase C (#207) -----------------------------------------------------------------------------------------------------------------------------------------------
c0 = janela("C1")[0]
c1 = janela("C")[1]
# C1: a carga SATUROU a unica replica? (sem isso o resto da fase C seria vacuo)
# top-C1.txt: `<ts> <pod> backend <cpu> <mem>` (kubectl top pod --containers, so o container backend; na b1 o `top pod` somaria o istio-proxy). Formato antigo (so pod): `<ts> <pod> <cpu> <mem>`.
cpu_max = None
cpu_pod_legado = 0
for linha in ler("top-C1.txt").splitlines():
    m = re.match(r"\d+\s+\S+\s+backend\s+(\d+)m\b", linha)
    if m:
        cpu_max = max(cpu_max or 0, int(m.group(1)))
        continue
    m = re.match(r"\d+\s+\S+\s+(\d+)m\b", linha)
    if m:
        cpu_pod_legado = max(cpu_pod_legado, int(m.group(1)))
r1 = resumos.get("C1") or {}
desc1 = r1.get("descartadas") or 0
p95_1 = r1.get("p95_ms") or 0
# p95 >= 300 ms: na fase C (a mesma leitura com a autoescala solta, sem saturar) o p95 ficou entre 6 e 103 ms, e na C1 (1 replica fixa) o MENOR p95 saturado foi 564 ms; 300 ms fica com ~3x de folga
# dos dois lados (100 ms, o limiar anterior, era alcancavel por ruido num no compartilhado). Quem discrimina e o p95: a fase C teve 86 a 1016 iteracoes descartadas sem saturar, entao
# "descartadas > 0" sozinho pesa pouco. Alem do p95 exige CPU do container >= 1500m (de 2000m) OU iteracoes descartadas (o k6 nao manteve a taxa oferecida).
if cpu_max is not None:
    print("--- C1 (backend fixo em 1 replica): CPU maxima do CONTAINER backend=%dm de 2000m, p95=%sms, descartadas=%s, 5xx=%s ---" % (cpu_max, r1.get("p95_ms"), desc1, r1.get("r5xx")))
else:
    print("--- C1 (backend fixo em 1 replica): CPU maxima do POD (formato legado)=%dm de 2000m, p95=%sms, descartadas=%s, 5xx=%s ---" % (cpu_pod_legado, r1.get("p95_ms"), desc1, r1.get("r5xx")))
    nao_avaliavel("CPU do container backend isolado (o top legado e do pod inteiro e, na b1, soma o istio-proxy): so as iteracoes descartadas entram no criterio de saturacao")
saturou = (((cpu_max or 0) >= 1500) or desc1 > 0) and p95_1 >= 300
criterio(saturou, "a carga de leitura SATUROU a unica replica do backend (p95 >= 300 ms E (CPU do container >= 1500m OU iteracoes descartadas)): sem isso a prova da #207 seria vacua")
rc = [(ts, p) for ts, p, _, _ in serie["backend"] if c0 and c0 <= ts <= c1 + 20]
if rc:
    queda = sum(1 for a, b in zip(rc, rc[1:]) if b[1] < a[1])
    print("--- fase C: backend prontas inicio=%d minimo=%d maximo=%d ---" % (rc[0][1], min(p for _, p in rc), max(p for _, p in rc)))
    criterio(queda == 0 and min(p for _, p in rc) >= 1, "nenhum pod do backend saiu do Service durante a carga de leitura (readyReplicas nunca caiu: %d quedas)" % queda)
ev = []
try:
    ev = json.load(open(os.path.join(d, "eventos-C.json"), encoding="utf-8"))["items"]
except (OSError, ValueError, KeyError):
    erro("eventos-C.json ausente")
desde = ler("eventos-C-desde.txt")
ruins = []
parados = {}  # pod -> instante do 1o Killing: um pod que a descida esta parando falha a readiness ("connection refused") depois do Killing; isso nao e probe de pod em servico
for e in ev:
    if e.get("reason") == "Killing":
        n = (e.get("involvedObject") or {}).get("name")
        t = e.get("lastTimestamp") or e.get("eventTime") or e.get("firstTimestamp") or ""
        if n and (n not in parados or t < parados[n]):
            parados[n] = t
for e in ev:
    campo = (e.get("involvedObject") or {}).get("fieldPath", "")
    ts = e.get("lastTimestamp") or e.get("eventTime") or e.get("firstTimestamp") or ""
    m = re.match(r"spec\.containers\{(\w[\w-]*)\}", campo)
    if not m or m.group(1) not in SERVICOS:
        if e.get("reason") != "Killing":
            continue
    msg = e.get("message", "")
    if e.get("reason") == "Unhealthy" and (e.get("involvedObject") or {}).get("name") in parados and ts >= parados[e["involvedObject"]["name"]]:
        continue
    # "Killing" tambem e a parada normal de um pod que a descida tirou ("Stopping container"): so conta o que a probe causou ("failed liveness probe, will be restarted")
    if ts >= desde and ((e.get("reason") == "Killing" and "probe" in msg) or (e.get("reason") == "Unhealthy" and re.match(r"(Readiness|Liveness) probe failed", msg))):
        ruins.append("%s %s %s %s" % (ts, e["involvedObject"].get("name"), e.get("reason"), msg[:80]))
reinicio_max = {}  # (uid do pod, container) -> maior restartCount visto em pods-C.json e pods-fim.json (um pod desescalado depois da C some do pods-fim)
for arq in ("pods-C.json", "pods-fim.json"):
    try:
        lista = json.load(open(os.path.join(d, arq), encoding="utf-8"))["items"]
    except (OSError, ValueError, KeyError):
        erro("%s ausente: o restartCount nao cobre toda a fase" % arq)
        continue
    for p in lista:
        uid = p.get("metadata", {}).get("uid")
        for cs in (p.get("status", {}).get("containerStatuses") or []) + (p.get("status", {}).get("initContainerStatuses") or []):
            k_ = (uid, cs.get("name"))
            reinicio_max[k_] = max(reinicio_max.get(k_, 0), cs.get("restartCount", 0))
reinicios = sum(reinicio_max.values())
for r in ruins:
    print("   evento: " + r)
criterio(not ruins, "sem Unhealthy de readiness/liveness nem Killing nos containers da aplicacao desde o inicio da fase C (%d eventos; a startupProbe gera Unhealthy no boot e nao conta)" % len(ruins))
criterio(reinicios == 0 and bool(reinicio_max), "restartCount (maximo por pod e container entre o fim da fase C e o fim do dry-run; %d containers vistos): %d" % (len(reinicio_max), reinicios))

# ---- java.security extra na trilha c ---------------------------------------------------------------------------------------------------------------------------
if trilha == "c":
    criterio(cri.get("javasec_log") == "1", "trilha c: a JVM do backend, COM o perfil Seccomp, leu a JAVA_TOOL_OPTIONS ('Picked up' no log: %s linha)" % cri.get("javasec_log"))

# ---- e-mails -----------------------------------------------------------------------------------------------------------------------------------------------------
esperados = int(par.get("emails_esperados", 0))
dest = []
try:
    for arq in sorted(glob.glob(os.path.join(d, "mailpit-p*.json"))):  # paginas de 500 da API do Mailpit
        dest += [((m.get("To") or [{}])[0]).get("Address", "") for m in json.load(open(arq, encoding="utf-8")).get("messages", [])]
except (OSError, ValueError):
    erro("paginas do Mailpit ausentes/invalidas")
unicos = set(dest)
suf = par.get("suf", "")
esperado_set = set("dryrun-%s-%d@example.com" % (suf, i) for i in range(1, esperados + 1))
faltam = esperado_set - unicos
extras = unicos - esperado_set
print("--- e-mails no Mailpit: %d mensagens, %d destinatarios distintos (esperados %d: dryrun-%s-1..%d); faltam %d, de fora do conjunto %d ---" % (len(dest), len(unicos), esperados, suf, esperados, len(faltam), len(extras)))
criterio(len(dest) == esperados and not faltam and not extras and esperados > 0, "um e-mail por pedido, nenhum duplicado, perdido nem estranho (mensagens=%d, esperados=%d, faltam=%d, de fora=%d)" % (len(dest), esperados, len(faltam), len(extras)))

# ---- overselling -------------------------------------------------------------------------------------------------------------------------------------------------
o = ler("overselling.txt")
m = re.search(r"capacity=(\d+) quant=(-?\d+) ativas=(\d+)", o)
if m:
    cap, quant, at = map(int, m.groups())
    criterio(at == cap and quant == cap - at, "overselling 0 com o evento ESGOTADO (a disputa aconteceu): capacidade=%d reservas ativas=%d eventcap.quant=%d" % (cap, at, quant))
else:
    erro("overselling.txt sem o evento quente: '%s'" % o)
criterio(ler("overselling-eventos.txt") == "0", "nenhum evento com reservas ativas > capacidade (SQL em todos os eventos: %s)" % ler("overselling-eventos.txt"))
criterio(ler("quant-incoerente.txt") == "0", "eventcap.quant == capacidade - holds debitados em todos os eventos (incoerentes: %s)" % ler("quant-incoerente.txt"))

# ---- CPU/RAM do no por fase ----------------------------------------------------------------------------------------------------------------------------------------
print("--- docker stats do no do kind por fase (CPU em % de um nucleo: 100 = 1 nucleo; RAM em uso) ---")
amostras = []
for r in csv.reader(open(os.path.join(d, "no-stats.csv"), encoding="utf-8")):
    try:
        mem = r[2]
        n = float(re.match(r"[0-9.]+", mem).group(0)) * (1024 if "GiB" in mem else 1 if "MiB" in mem else 0.001)
        amostras.append((int(r[0]), float(r[1]), n))
    except (ValueError, IndexError, AttributeError):
        continue
for f in ("parado", "ocioso", "A", "B", "descida", "C1", "C", "D", "E"):
    a, b = janela(f)
    xs = [(c, mm) for ts, c, mm in amostras if a and b and a <= ts <= b]
    if xs:
        cs = [c for c, _ in xs]
        print("   %-8s CPU media=%6.0f%% maxima=%6.0f%%   RAM media=%6.0f MiB maxima=%6.0f MiB   (%d amostras)" % (f, sum(cs) / len(cs), max(cs), sum(m for _, m in xs) / len(xs), max(m for _, m in xs), len(xs)))
    else:
        print("   %-8s sem amostras" % f)

print("=== %s ===" % ("DRY-RUN OK (todos os criterios automaticos)" if falhas == 0 else "%d CRITERIO(S) FALHARAM" % falhas))
sys.exit(1 if falhas else 0)
