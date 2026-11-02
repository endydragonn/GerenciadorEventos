#!/usr/bin/env python3
"""Amostra do cluster durante o bloco do soak (issue #232): `kubectl get hpa,deploy,pods -o json` em stdin -> duas linhas de CSV por servico/pod.

Uso (o `verificar-soak.sh amostrar` chama a cada 5 s):  kubectl ... get hpa,deploy,pods -o json | python amostra.py --prefixo results/soak-S1 [--epoch N]
Grava (acrescenta; cabecalho se o arquivo e novo):
  <prefixo>-replicas.csv   epoch_s,servico,metrica,alvo,replicas,prontas
      uma linha por Deployment (servico = nome do Deployment). `replicas` = spec.replicas (a decisao do autoescalador), `prontas` = status.readyReplicas.
      `metrica` e `alvo` so existem nos servicos com HPA do KEDA (a metrica do ScaledObject; vazio nos outros, como o stock e o frontend): external -> averageValue
      (quantidade do Kubernetes, ex. 500m = 0,5) ou Resource -> averageUtilization (%). E a fonte do criterio 2 (autoescala acompanha a carga).
  <prefixo>-pods.csv       epoch_s,pod,app,pronto,restarts,terminando
      pronto 0/1, restarts = soma dos restartCount, terminando = 1 se o pod tem deletionTimestamp. Fonte do restartCount do Mailpit e das saidas do notification
      (tolerancia de e-mail duplicado).
Sem dependencias alem do Python >= 3.8.
"""
import argparse
import csv
import json
import os
import sys
import time

SUFIXOS = {"m": 1e-3, "k": 1e3, "K": 1e3, "M": 1e6, "G": 1e9}


def quantidade(q):
    """Quantidade do Kubernetes ("23", "500m", "1500", "2k") -> float. None se nao parseia."""
    if q is None:
        return None
    q = str(q).strip()
    if not q:
        return None
    try:
        if q[-1] in SUFIXOS:
            return float(q[:-1]) * SUFIXOS[q[-1]]
        return float(q)
    except ValueError:
        return None


def metrica_do_hpa(hpa):
    """(metrica_atual, alvo) do 1o item de spec.metrics / status.currentMetrics; (None, None) se ainda sem leitura."""
    specs = (hpa.get("spec") or {}).get("metrics") or []
    atuais = (hpa.get("status") or {}).get("currentMetrics") or []
    if not specs or not atuais:
        return None, None
    s, c = specs[0], atuais[0]
    if s.get("type") == "Resource":
        alvo = ((s.get("resource") or {}).get("target") or {}).get("averageUtilization")
        atual = ((c.get("resource") or {}).get("current") or {}).get("averageUtilization")
        return (None if atual is None else float(atual)), (None if alvo is None else float(alvo))
    if s.get("type") in ("External", "Pods", "Object"):
        chave = s["type"].lower()
        alvo_d = ((s.get(chave) or {}).get("target") or {})
        atual_d = ((c.get(chave) or {}).get("current") or {})
        alvo = quantidade(alvo_d.get("averageValue", alvo_d.get("value")))
        atual = quantidade(atual_d.get("averageValue", atual_d.get("value")))
        return atual, alvo
    return None, None


def amostrar(lista, epoch):
    """-> (linhas_replicas, linhas_pods) de um `kubectl get ... -o json` (kind List)."""
    itens = lista.get("items") or []
    hpas = {}
    for it in itens:
        if it.get("kind") == "HorizontalPodAutoscaler":
            alvo_deploy = ((it.get("spec") or {}).get("scaleTargetRef") or {}).get("name")
            if alvo_deploy:
                hpas[alvo_deploy] = it
    replicas, pods = [], []
    for it in itens:
        meta = it.get("metadata") or {}
        if it.get("kind") == "Deployment":
            nome = meta.get("name", "")
            atual, alvo = metrica_do_hpa(hpas[nome]) if nome in hpas else (None, None)
            replicas.append([epoch, nome, "" if atual is None else repr(atual), "" if alvo is None else repr(alvo),
                             (it.get("spec") or {}).get("replicas", 0), (it.get("status") or {}).get("readyReplicas", 0)])
        elif it.get("kind") == "Pod":
            st = it.get("status") or {}
            pronto = any(c.get("type") == "Ready" and c.get("status") == "True" for c in st.get("conditions") or [])
            restarts = sum(int(c.get("restartCount", 0)) for c in st.get("containerStatuses") or [])
            pods.append([epoch, meta.get("name", ""), (meta.get("labels") or {}).get("app", ""), int(pronto), restarts,
                         int(bool(meta.get("deletionTimestamp")))])
    return replicas, pods


def acrescentar(caminho, cabecalho, linhas):
    novo = not os.path.exists(caminho) or os.path.getsize(caminho) == 0
    with open(caminho, "a", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        if novo:
            w.writerow(cabecalho)
        w.writerows(linhas)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--prefixo", required=True)
    ap.add_argument("--epoch", type=int, default=None, help="so para teste (padrao: agora)")
    a = ap.parse_args(argv)
    try:
        lista = json.load(sys.stdin)
    except ValueError:
        print("amostra: stdin nao e JSON (kubectl falhou?)", file=sys.stderr)
        return 1
    rep, pods = amostrar(lista, a.epoch if a.epoch is not None else int(time.time()))
    acrescentar(a.prefixo + "-replicas.csv", ["epoch_s", "servico", "metrica", "alvo", "replicas", "prontas"], rep)
    acrescentar(a.prefixo + "-pods.csv", ["epoch_s", "pod", "app", "pronto", "restarts", "terminando"], pods)
    return 0


if __name__ == "__main__":
    sys.exit(main())
