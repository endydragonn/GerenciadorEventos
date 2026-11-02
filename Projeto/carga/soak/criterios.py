#!/usr/bin/env python3
"""Criterios de aprovacao do soak (issue #232) e pre-checks, em funcoes puras + CLI chamada pelo verificar-soak.sh. So biblioteca padrao.

Subcomandos (saida 0 = ok, 1 = reprovou; imprime o motivo, nunca segredo):
  pre     --lista <deploy,scaledobject.json> --total <cadastros esperados> [--bloco S1|S2 | --x-esperado N] [--x-vivo N] [--admission-antigo 0|1] [--chaves-secret arq]
                                                                                 pre-bloco no cluster VIVO (kubectl get deploy,scaledobject -o json)
  crit3   --por-minuto <soak-ID-por-minuto.csv> [--pods <soak-ID-pods.csv>]      0 respostas 5xx E 0 status 0 (erro de transporte) fora de tipo=mailpit; se reprovar, lista os
                                                                                 minutos com erro ao lado dos pods que sairam neles
  fila    --por-minuto <soak-ID-por-minuto.csv> --bloco S1|S2                    S2: 202 obrigatorio na reserva (429 so registrado); S1: so registra
  crit2   --replicas <soak-ID-replicas.csv> [--max 3] [--bloco S1|S2]            a autoescala acompanha a carga (e o stock fica em 1); no S1 o reservation fica fora
  emails  --paginas <pag.json...> --esperados N --sufixo S --pods <pods.csv>     e-mail perdido / duplicado / From
  restarts --pods <soak-ID-pods.csv> --app mailpit                               restartCount do Mailpit no bloco = 0
"""
import argparse
import csv
import json
import os
import re
import sys
from datetime import datetime, timezone

SEIS = [("MAIL_HOST", "mailpit"), ("MAIL_PORT", "1025"), ("MAIL_SMTP_AUTH", "false"), ("MAIL_STARTTLS", "false"),
        ("MAIL_USERNAME", "soak@example.com"), ("MAIL_PASSWORD", "")]
# Gatilho do soak por bloco (decisao C da spec): ADMISSION_X_RPM do ambiente do `cluster.sh up`; o alvo do KEDA do reservation e 0,5 x X da MESMA variavel.
X_DO_BLOCO = {"S1": 56000, "S2": 4000}
# Status 0 do k6 (erro de transporte: timeout, conexao recusada ou resetada) conta como 5xx no criterio 3 (decisao do usuario). Trocar para False = so registrar.
C0_REPROVA = True
SPRING_MAIL_ENVS = ("SPRING_APPLICATION_JSON", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")  # onde um spring.mail.* poderia vencer o MAIL_HOST
HIKARI_STOCK = "245"  # #231: pool do stock = pool real do Supavisor, com 1 replica fixa (Recreate)
DOMINIO = "@example.com"


# ----------------------------------------------------------------------------- pre-bloco (cluster vivo)
def env_literal(deploy, container):
    """nome -> ('valor', 'literal'|'valueFrom') do container; entradas repetidas viram a ULTIMA (como o kubelet). Entrada sem `value` = '' (a API omite o vazio)."""
    for c in (((deploy.get("spec") or {}).get("template") or {}).get("spec") or {}).get("containers") or []:
        if c.get("name") == container:
            out = {}
            for e in c.get("env") or []:
                out[e["name"]] = (None, "valueFrom") if "valueFrom" in e else (e.get("value", ""), "literal")
            return out
    return None


def alvo_keda(x_rpm):
    """targetValue do ScaledObject do reservation para o X dado: 0,5 x X (como string inteira, o que o cluster.sh grava)."""
    a = x_rpm * 0.5
    return str(int(a)) if a == int(a) else repr(a)


def spring_mail_no_env(env):
    """Erros de um env {nome: (valor, 'literal'|'valueFrom')} do notification que reabririam o SMTP real por fora do MAIL_HOST."""
    erros = []
    for nome in sorted(env):
        if re.match(r"(?i)^SPRING_MAIL", nome) or re.match(r"(?i)^spring\.mail", nome):
            erros.append("notification: %s no env (o relaxed binding venceria o MAIL_HOST e reabriria o SMTP real)" % nome)
        elif nome in SPRING_MAIL_ENVS:
            valor, tipo = env[nome]
            if tipo == "valueFrom":
                erros.append("notification: %s vem de valueFrom (nao da para provar que nao leva spring.mail)" % nome)
            elif re.search(r"(?i)spring[._]mail", valor or ""):
                erros.append("notification: %s contem spring.mail (venceria o MAIL_HOST e reabriria o SMTP real)" % nome)
    return erros


def pre_checks(lista, total_cadastros, x_esperado=None, x_vivo=None, admission_antigo=False, chaves_secret=()):
    """Erros do pre-bloco. `lista` = kind List de `kubectl get deploy,scaledobject -o json`.
    x_esperado = ADMISSION_X_RPM do bloco (X_DO_BLOCO): o targetValue do ScaledObject do reservation tem de ser 0,5 x ele. x_vivo = o ADMISSION_X_RPM que o Secret env-admission traz
    agora (None = nao conferido) e admission_antigo = algum pod do admission subiu ANTES do Secret ser aplicado (o envFrom nao recarrega: o X vivo seria o do bloco anterior).
    chaves_secret = nomes (nunca valores) das chaves do Secret env-notification."""
    erros, itens = [], lista.get("items") or []
    deploys = {i["metadata"]["name"]: i for i in itens if i.get("kind") == "Deployment"}
    sos = [i for i in itens if i.get("kind") == "ScaledObject"]
    # 1. nenhum e-mail real: o notification VIVO. A API omite `value` vazio: ausente do env != vazio (a senha REAL do Secret venceria por envFrom)
    nt = deploys.get("notification")
    env = env_literal(nt, "notification") if nt else None
    if env is None:
        erros.append("o Deployment notification nao existe no cluster")
    else:
        for var, esperado in SEIS:
            if var not in env:
                erros.append("notification: %s ausente do env (valeria o Secret, com o SMTP real)" % var)
            elif env[var][1] == "valueFrom":
                erros.append("notification: %s vem de valueFrom (esperado o literal)" % var)
            elif env[var][0] != esperado:
                erros.append("notification: %s com valor errado (esperado %r)%s" % (var, esperado, " -- cluster base/rodada, nao soak?" if var == "MAIL_HOST" else ""))
        erros += spring_mail_no_env(env)
    for k in chaves_secret:
        if re.match(r"(?i)^spring[._]mail", k) or k in SPRING_MAIL_ENVS:
            erros.append("Secret env-notification traz a chave %s (envFrom: venceria o MAIL_HOST e reabriria o SMTP real)" % k)
    # 1b. o gatilho do bloco: X vivo do admission e alvo do KEDA do reservation (decisao C)
    if x_esperado is not None:
        if x_vivo is None:
            erros.append("ADMISSION_X_RPM vivo do admission nao lido (Secret env-admission)")
        elif float(x_vivo) != float(x_esperado):
            erros.append("admission: ADMISSION_X_RPM=%s no cluster, o bloco quer %s (suba o cluster com ADMISSION_X_RPM=%s no ambiente)" % (x_vivo, x_esperado, x_esperado))
        if admission_antigo:
            erros.append("admission: ha pod que subiu ANTES do Secret env-admission ser aplicado (o envFrom nao recarrega): kubectl rollout restart deploy/admission")
        alvos = [(so["metadata"]["name"], (((so.get("spec") or {}).get("triggers") or [{}])[0].get("metadata") or {}).get("targetValue")) for so in sos
                 if so["metadata"]["name"] == "reservation"]
        if not alvos:
            erros.append("o ScaledObject do reservation nao existe no cluster")
        elif alvos[0][1] is None or float(alvos[0][1]) != float(alvo_keda(x_esperado)):
            erros.append("reservation: targetValue do KEDA=%s, esperado %s (0,5 x X=%s)" % (alvos[0][1], alvo_keda(x_esperado), x_esperado))
    # 2. pre-condicao #231: sem ScaledObject do stock e Hikari 245
    for so in sos:
        if so["metadata"]["name"] == "stock" or ((so.get("spec") or {}).get("scaleTargetRef") or {}).get("name") == "stock":
            erros.append("existe ScaledObject do stock (a #231 nao foi aplicada: o stock fica em 1 replica fixa)")
    st = deploys.get("stock")
    senv = env_literal(st, "stock") if st else None
    if senv is None:
        erros.append("o Deployment stock nao existe no cluster")
    elif senv.get("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", (None, ""))[0] != HIKARI_STOCK:
        erros.append("stock: Hikari %r (esperado %s da #231)" % (senv.get("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", ("ausente",))[0], HIKARI_STOCK))
    # 3. todo Deployment autoescalado em 1 replica pronta (o limpar-banco religa as anotadas, nao 1)
    for so in sos:
        alvo = ((so.get("spec") or {}).get("scaleTargetRef") or {}).get("name")
        d = deploys.get(alvo)
        if d is None:
            erros.append("ScaledObject %s aponta para o Deployment %r, que nao existe" % (so["metadata"]["name"], alvo))
            continue
        rep, prontas = (d.get("spec") or {}).get("replicas"), (d.get("status") or {}).get("readyReplicas", 0)
        if rep != 1 or prontas != 1:
            erros.append("%s: replicas=%s prontas=%s (esperado 1 e 1 no inicio do bloco)" % (alvo, rep, prontas))
    # 4. Mailpit dimensionado: MP_MAX_MESSAGES >= 2x o total esperado (cobre o total > MP_MAX_MESSAGES)
    mp = deploys.get("mailpit")
    menv = env_literal(mp, "mailpit") if mp else None
    if menv is None:
        erros.append("o Deployment mailpit nao existe no cluster")
    else:
        try:
            mp_max = int(menv.get("MP_MAX_MESSAGES", ("",))[0])
        except ValueError:
            mp_max = 0
        if mp_max <= 0:
            erros.append("mailpit: MP_MAX_MESSAGES ausente ou 0 (o padrao guarda so 500 mensagens)")
        elif 2 * total_cadastros > mp_max:
            erros.append("o total esperado de cadastros (%d, x2 = %d) passa do MP_MAX_MESSAGES do Mailpit (%d)" % (total_cadastros, 2 * total_cadastros, mp_max))
    return erros


# ----------------------------------------------------------------------------- criterios por bloco
def ler_csv(caminho):
    with open(caminho, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def criterio3(por_minuto, c0_reprova=None):
    """5xx em todos os tipos, EXCETO tipo=mailpit (o gerador falando com o captador). Com C0_REPROVA o status 0 (erro de transporte do k6) conta como 5xx: e o que "troca de replica
    sem erro" quer provar (um pod que sai com keep-alive aberto no L4 do gateway da status 0, nao 5xx). 202/429/400 nao contam.
    -> {"reprova": n, "c5xx": n, "c0": n, "por_tipo": {tipo: {"c5xx", "c0"}}, "minutos": [{"minuto", "tipo", "c5xx", "c0", "inicio_s"}]} (minutos so os com erro, em ordem)"""
    c0_reprova = C0_REPROVA if c0_reprova is None else c0_reprova
    por_tipo, minutos = {}, []
    for l in por_minuto:
        if l["tipo"] == "mailpit":
            continue
        e5, e0 = int(l["c5xx"] or 0), int(l.get("c0") or 0)
        d = por_tipo.setdefault(l["tipo"], {"c5xx": 0, "c0": 0})
        d["c5xx"] += e5
        d["c0"] += e0
        if e5 or e0:
            minutos.append({"minuto": int(l["minuto"]), "tipo": l["tipo"], "c5xx": e5, "c0": e0, "inicio_s": float(l["inicio_s"]) if l.get("inicio_s") else None, "atrasada": l.get("atrasada") == "1"})
    t5, t0 = sum(d["c5xx"] for d in por_tipo.values()), sum(d["c0"] for d in por_tipo.values())
    minutos.sort(key=lambda m: (m["minuto"], m["tipo"]))
    return {"reprova": t5 + (t0 if c0_reprova else 0), "c5xx": t5, "c0": t0, "por_tipo": por_tipo, "minutos": minutos}


def utc(epoch):
    return datetime.fromtimestamp(epoch, tz=timezone.utc).strftime("%H:%M:%SZ")


def saidas_de_pods(pods):
    """Pods que sairam, pelas amostras do `amostrar` (a cada ~5 s): [(epoch, app, pod, motivo)] com motivo 'terminando' (1a amostra com deletionTimestamp), 'reiniciou' (restartCount
    subiu) ou 'sumiu' (estava na amostra anterior e nao esta mais; a hora e a da 1a amostra sem ele). O pod que SO aparece (subiu) nao e saida."""
    por_epoch = {}
    for l in pods:
        por_epoch.setdefault(float(l["epoch_s"]), {})[l["pod"]] = l
    out, terminando, restarts, anterior = [], set(), {}, {}
    for t in sorted(por_epoch):
        atual = por_epoch[t]
        for pod, l in atual.items():
            if l["terminando"] == "1" and pod not in terminando:
                terminando.add(pod)
                out.append((t, l["app"], pod, "terminando"))
            r = int(l["restarts"])
            if pod in restarts and r > restarts[pod]:
                out.append((t, l["app"], pod, "reiniciou"))
            restarts[pod] = r
        for pod, l in anterior.items():
            if pod not in atual:
                out.append((t, l["app"], pod, "sumiu"))
        anterior = atual
    return sorted(out)


def relatorio_erros(c3, pods, folga_s=5):
    """Linhas de texto: cada minuto com 5xx/status 0, por tipo, e os pods que sairam nele (janela do minuto +- folga_s, a granularidade do `amostrar`): horario, servico (app) e nome do pod.
    Serve para ligar (ou nao) a causa ao L4 do gateway."""
    saidas = saidas_de_pods(pods) if pods is not None else None
    linhas = []
    for m in c3["minutos"]:
        cab = "minuto %d%s %-8s 5xx %d, status 0 %d" % (m["minuto"], "" if m["inicio_s"] is None else " (%s)" % utc(m["inicio_s"]), m["tipo"], m["c5xx"], m["c0"])
        if m.get("atrasada"):
            cab += " [amostras que chegaram depois do fechamento do minuto]"
        linhas.append("   [ERRO] " + cab)
        if saidas is None:
            linhas.append("          sem soak-ID-pods.csv: nao da para ligar a pods que sairam")
        elif m["inicio_s"] is None:
            linhas.append("          por-minuto sem inicio_s: nao da para ligar a pods que sairam")
        else:
            dentro = [x for x in saidas if m["inicio_s"] - folga_s <= x[0] < m["inicio_s"] + 60 + folga_s]
            if not dentro:
                linhas.append("          nenhum pod saiu neste minuto (+-%ds, supondo os relogios do gerador e do host sincronizados): a causa provavelmente NAO e troca de replica" % folga_s)
            for t, app, pod, motivo in dentro:
                linhas.append("          pod saiu as %s: servico %s, pod %s (%s)" % (utc(t), app, pod, motivo))
    return linhas


def criterio_fila(por_minuto, bloco):
    """Reserva no S2: 202 obrigatorio (a fila ligou); 429 so registrado (so sai por carga: o limite de mensagens em espera por usuario depende da latencia da fila). S1 (X=56k): so registra.
    -> (ok, c202, c429)"""
    c202 = sum(int(l["c202"]) for l in por_minuto if l["tipo"] == "reserva")
    c429 = sum(int(l["c429"]) for l in por_minuto if l["tipo"] == "reserva")
    return (bloco != "S2" or c202 > 0), c202, c429


def criterio2(linhas, max_replicas=3, tol=0.10, subida_s=60, descida_s=300, fora=()):
    """A autoescala acompanha a carga, por servico com metrica+alvo (HPA do KEDA). `linhas` = soak-ID-replicas.csv.
    Subida: a metrica por pod passa de alvo x (1+tol) (a tolerancia do HPA) com replicas < max -> em ate `subida_s` o servico ganha replica (spec.replicas).
    Descida: a carga TOTAL (replicas x metrica) cabe em 1 pod no alvo -> em ate `descida_s` o servico volta a 1. So avalia o episodio que durou o prazo
    (um que acabou antes nao e violacao nem sucesso). O stock (sem HPA) deve ficar em 1 o bloco inteiro.
    -> {servico: {"subidas": n, "descidas": n, "violacoes": [txt]}}."""
    por_srv = {}
    for l in linhas:
        por_srv.setdefault(l["servico"], []).append(l)
    res = {}
    for srv, ls in sorted(por_srv.items()):
        if srv in fora:  # ex.: o reservation no S1 (X=56k: o alvo do KEDA, 28k, nunca e alcancado de proposito)
            continue
        ls.sort(key=lambda x: float(x["epoch_s"]))
        amostras = [(float(x["epoch_s"]), int(x["replicas"]), float(x["metrica"]) if x["metrica"] else None, float(x["alvo"]) if x["alvo"] else None) for x in ls]
        r = {"subidas": 0, "descidas": 0, "violacoes": []}
        if srv == "stock":
            if max(a[1] for a in amostras) > 1:
                r["violacoes"].append("stock chegou a %d replicas (esperado 1 fixa)" % max(a[1] for a in amostras))
            res[srv] = r
            continue
        if not any(a[3] for a in amostras):
            continue  # sem HPA (frontend, infra): fora do criterio
        com_metrica = [a for a in amostras if a[2] is not None and a[3]]
        n, i = len(com_metrica), 0
        while i < n:  # subida
            t, rep, m, alvo = com_metrica[i]
            if m > alvo * (1 + tol) and rep < max_replicas:
                j, subiu = i, False
                while j < n and com_metrica[j][2] > com_metrica[j][3] * (1 + tol):
                    if com_metrica[j][0] - t <= subida_s and com_metrica[j][1] > rep:
                        subiu = True
                    j += 1
                duracao = com_metrica[j - 1][0] - t
                if subiu:
                    r["subidas"] += 1
                elif duracao >= subida_s:
                    r["subidas"] += 1
                    r["violacoes"].append("metrica %.4g > alvo %.4g desde t=%d (replicas %d) e nenhuma replica nova em %ds" % (m, alvo, t, rep, subida_s))
                i = j
            else:
                i += 1
        i = 0
        while i < n:  # descida
            t, rep, m, alvo = com_metrica[i]
            if rep > 1 and rep * m <= alvo:
                j = i
                while j < n and com_metrica[j][1] * com_metrica[j][2] <= com_metrica[j][3]:
                    j += 1
                duracao = com_metrica[j - 1][0] - t
                if duracao >= descida_s:
                    r["descidas"] += 1
                    if not any(a[1] == 1 for a in com_metrica[i:j] if a[0] - t <= descida_s):
                        r["violacoes"].append("carga total %.4g <= alvo %.4g desde t=%d (replicas %d) e ainda sem voltar a 1 em %ds" % (rep * m, alvo, t, rep, descida_s))
                i = j
            else:
                i += 1
        res[srv] = r
    return res


def parse_quando(txt):
    """RFC3339 do Mailpit ('2026-11-01T12:00:00.123456789Z' ou com offset) -> epoch float (fracao truncada a 6 casas: o fromisoformat <3.11 so aceita 3 ou 6)."""
    t = re.sub(r"\.(\d{1,9})", lambda m: "." + (m.group(1) + "000000")[:6], txt.replace("Z", "+00:00"))
    return datetime.fromisoformat(t).astimezone(timezone.utc).timestamp()


def mensagens_das_paginas(paginas):
    out = []
    for pg in paginas:
        for m in pg.get("messages") or []:
            to = ((m.get("To") or [{}])[0].get("Address") or "").lower()
            out.append({"id": m.get("ID", ""), "to": to, "de": ((m.get("From") or {}).get("Address") or "").lower(), "quando": parse_quando(m["Created"])})
    return out


def saidas_do_notification(pods):
    """Instantes em que um pod do notification comecou a sair (deletionTimestamp) ou reiniciou: ate a 1a amostra com terminando=1 / restarts maior."""
    saidas, vistos, restarts = [], set(), {}
    for l in sorted((x for x in pods if x["app"] == "notification"), key=lambda x: float(x["epoch_s"])):
        pod, t = l["pod"], float(l["epoch_s"])
        if l["terminando"] == "1" and pod not in vistos:
            vistos.add(pod)
            saidas.append(t)
        r = int(l["restarts"])
        if pod in restarts and r > restarts[pod]:
            saidas.append(t)
        restarts[pod] = r
    return saidas


def criterio_emails(mensagens, esperados, sufixo, saidas, tol_s=60):
    """-> dict: perdidos, duplicadas_nao_toleradas (lista), duplicadas_toleradas, extras, from_ruins (lista), unicos, total.
    Perdido = cadastro (request-verification 2xx) sem nenhum e-mail; sempre reprova. Duplicado so e tolerado a +-tol_s de um pod do notification saindo."""
    prefixo = ("soak-%s-" % sufixo).lower()  # o Mailpit devolve o endereco como foi enviado; compara em minusculas
    do_bloco = [m for m in mensagens if m["to"].startswith(prefixo) and m["to"].endswith(DOMINIO)]
    por_end = {}
    for m in do_bloco:
        por_end.setdefault(m["to"], []).append(m["quando"])
    dup_ok, dup_ruim = 0, []
    for end, ts in por_end.items():
        for t in sorted(ts)[1:]:
            if any(abs(t - s) <= tol_s for s in saidas):
                dup_ok += 1
            else:
                dup_ruim.append(end)
    return {"total": len(do_bloco), "unicos": len(por_end), "esperados": esperados, "perdidos": max(0, esperados - len(por_end)),
            "extras": max(0, len(por_end) - esperados), "duplicadas_toleradas": dup_ok, "duplicadas_nao_toleradas": dup_ruim,
            "from_ruins": [m["de"] for m in mensagens if not m["de"].endswith(DOMINIO)]}


# ----------------------------------------------------------------------------- CLI
def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pre"); p.add_argument("--lista", required=True); p.add_argument("--total", type=int, required=True)
    p.add_argument("--bloco", choices=sorted(X_DO_BLOCO)); p.add_argument("--x-esperado", type=float); p.add_argument("--x-vivo", type=float)
    p.add_argument("--admission-antigo", choices=("0", "1"), default="0"); p.add_argument("--chaves-secret", default=None)
    p = sub.add_parser("crit3"); p.add_argument("--por-minuto", required=True); p.add_argument("--pods", default=None)
    p = sub.add_parser("fila"); p.add_argument("--por-minuto", required=True); p.add_argument("--bloco", required=True, choices=sorted(X_DO_BLOCO))
    p = sub.add_parser("crit2"); p.add_argument("--replicas", required=True); p.add_argument("--max", type=int, default=3); p.add_argument("--bloco", choices=sorted(X_DO_BLOCO))
    p = sub.add_parser("emails"); p.add_argument("--paginas", nargs="+", required=True); p.add_argument("--esperados", type=int, required=True)
    p.add_argument("--sufixo", required=True); p.add_argument("--pods", required=True)
    p = sub.add_parser("restarts"); p.add_argument("--pods", required=True); p.add_argument("--app", default="mailpit")
    a = ap.parse_args(argv)

    if a.cmd == "pre":
        x = a.x_esperado if a.x_esperado is not None else (X_DO_BLOCO[a.bloco] if a.bloco else None)
        chaves = []
        if a.chaves_secret:
            with open(a.chaves_secret, encoding="utf-8") as f:
                chaves = [l.strip() for l in f if l.strip()]
        with open(a.lista, encoding="utf-8") as f:
            erros = pre_checks(json.load(f), a.total, x_esperado=x, x_vivo=a.x_vivo, admission_antigo=a.admission_antigo == "1", chaves_secret=chaves)
        for e in erros:
            print("   [PRE] " + e)
        return 1 if erros else 0
    if a.cmd == "crit3":
        c3 = criterio3(ler_csv(a.por_minuto))
        print("criterio 3 (troca de replica sem erro): %d respostas 5xx e %d com status 0 (erro de transporte%s) fora de tipo=mailpit %s"
              % (c3["c5xx"], c3["c0"], "" if C0_REPROVA else ", so registrado", json.dumps(c3["por_tipo"], sort_keys=True)))
        if c3["reprova"]:
            pods = ler_csv(a.pods) if a.pods and os.path.exists(a.pods) else None
            for l in relatorio_erros(c3, pods):
                print(l)
        return 1 if c3["reprova"] else 0
    if a.cmd == "fila":
        ok, c202, c429 = criterio_fila(ler_csv(a.por_minuto), a.bloco)
        print("fila (%s): reserva com %d respostas 202 e %d respostas 429%s" % (a.bloco, c202, c429, "" if a.bloco == "S2" else " (S1: X=56k, a fila nao deve ligar; so registrado)"))
        if a.bloco == "S2" and not ok:
            print("   [VIOLACAO] 0 respostas 202 no S2: a fila nao ligou (ADMISSION_X_RPM=4000 aplicado? R do reservation? eventos quentes?)")
        elif a.bloco == "S2" and not c429:
            print("   [registro] 0 respostas 429: o limite de mensagens em espera por usuario nao foi atingido por carga (so registrado; nao reprova)")
        return 0 if ok else 1
    if a.cmd == "crit2":
        fora = ("reservation",) if a.bloco == "S1" else ()
        res = criterio2(ler_csv(a.replicas), max_replicas=a.max, fora=fora)
        if fora:
            print("criterio 2 reservation: FORA do criterio no S1 (X=56k: alvo do KEDA 28k RPM, nunca alcancado de proposito; o reservation fica em 1 replica)")
        ruim = False
        for srv, r in res.items():
            print("criterio 2 %-12s subidas avaliadas %d, descidas avaliadas %d, violacoes %d" % (srv, r["subidas"], r["descidas"], len(r["violacoes"])))
            for v in r["violacoes"]:
                print("   [VIOLACAO] %s: %s" % (srv, v))
                ruim = True
        if not any(r["subidas"] or r["descidas"] for s, r in res.items() if s != "stock"):
            print("   [aviso] nenhum episodio de subida/descida avaliado: a autoescala nao foi exercitada neste bloco (criterio vacuo)")
        return 1 if ruim else 0
    if a.cmd == "emails":
        paginas = []
        for caminho in a.paginas:
            with open(caminho, encoding="utf-8") as f:
                paginas.append(json.load(f))
        pods = ler_csv(a.pods)
        r = criterio_emails(mensagens_das_paginas(paginas), a.esperados, a.sufixo, saidas_do_notification(pods))
        print("mailpit: %d mensagens do bloco (%d enderecos) para %d cadastros esperados; perdidas %d, duplicadas toleradas %d, duplicadas nao toleradas %d, extras %d, From fora de %s: %d"
              % (r["total"], r["unicos"], r["esperados"], r["perdidos"], r["duplicadas_toleradas"], len(r["duplicadas_nao_toleradas"]), r["extras"], DOMINIO, len(r["from_ruins"])))
        if r["extras"]:
            print("   [aviso] %d enderecos a mais que os 2xx (um request-verification com 5xx/timeout pode ter enviado o e-mail)" % r["extras"])
        return 1 if (r["perdidos"] or r["duplicadas_nao_toleradas"] or r["from_ruins"]) else 0
    if a.cmd == "restarts":
        ls = [l for l in ler_csv(a.pods) if l["app"] == a.app]
        rs = [int(l["restarts"]) for l in ls]
        crescimento = (max(rs) - min(rs)) if rs else 0
        trocas = max(0, len({l["pod"] for l in ls}) - 1)  # pod recriado = banco temporario do Mailpit perdido, mesmo sem restartCount
        print("restartCount do %s no bloco: %d reinicio(s), %d pod(s) trocado(s) (%d amostras)" % (a.app, crescimento, trocas, len(rs)))
        return 1 if (crescimento or trocas) else 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
