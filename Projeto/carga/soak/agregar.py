#!/usr/bin/env python3
"""Agrega o CSV bruto do `k6 --out csv` do soak (issue #232) em `soak-<id>-por-minuto.csv`, lendo POR STREAMING (o bruto passa de 1 GB).

Uso:  python agregar.py <k6.csv|k6.csv.gz> --id S1 [--saida soak-S1-por-minuto.csv]

Uma linha por (minuto, tipo). Colunas:
  minuto, tipo        minuto do bloco (0 = o primeiro) e o tipo da carga (leitura, reserva, login, cadastro, escrita, mailpit)
  taxa                iteracoes (metrica `iterations`) do tipo nesse minuto
  reqs                requisicoes HTTP (linhas de http_req_duration) do tipo nesse minuto
  p50_ms p95_ms p99_ms  percentis de http_req_duration (metodo NEAREST-RANK: o k-esimo menor valor, k = ceil(p x n / 100))
  c2xx c202 c400 c429 c5xx c0 outros   contagem por codigo, MUTUAMENTE EXCLUSIVAS (soma = reqs): c2xx = 200..299 EXCETO 202; c202; c400; c429; c5xx = 500..599;
                      c0 = status 0 (erro de transporte do k6: timeout, conexao recusada/resetada; o criterio 3 o trata como 5xx); outros = o resto (401, 404, 422...)
  descartadas         iteracoes descartadas (dropped_iterations: "Insufficient VUs"; o tipo vem da tag do cenario)
  verificacoes_2xx    requisicoes POST /bff/users/request-verification com 2xx (cada uma deve virar 1 e-mail no Mailpit)
  inicio_s            epoch (s) do comeco do minuto (estimado, ver abaixo): liga o minuto aos pods que sairam (amostras do `amostrar`, soak-ID-pods.csv)
  atrasada            0 nas linhas normais; 1 na linha das amostras que chegaram DEPOIS do fechamento do minuto (ver abaixo): so contagens (reqs, c2xx..c0, taxa, descartadas,
                      verificacoes_2xx), percentis vazios. Os criterios SOMAM todas as linhas do tipo, entao um 503 atrasado reprova o criterio 3 do mesmo jeito
O minuto das requisicoes vem da tag `min` (calculada no k6 pelo relogio do cenario); o de `iterations` e `dropped_iterations` (sem a tag) vem do timestamp
menos o inicio do bloco, estimado como o menor (timestamp - 60 x min) visto: erro de ~1 s na fronteira do minuto, so nessas duas colunas.
Memoria: uma lista de duracoes por (minuto, tipo) aberto; minutos com mais de 2 de atraso para o maior visto sao fechados e descartados da memoria.
Amostra que chega depois do fechamento do minuto NAO entra na linha normal, mas NUNCA some em silencio: vai para uma linha `atrasada=1` do mesmo (minuto, tipo) NO PROPRIO por-minuto
(o que o criterio 3 le) e e impressa por tipo; o programa tambem sai com codigo 3 se uma delas tiver 5xx ou status 0. O k6 grava `min` POR REQUISICAO (carga-soak.js), entao
um fluxo longo (cadastro esperando o e-mail, 202 acompanhado) nao carimba o minuto do comeco nas requisicoes do fim.
Colunas do CSV do k6 lidas pelo NOME (nao pela posicao); tags proprias (tipo, fase, min) vem de `extra_tags` (k=v&k=v).
"""
import argparse
import csv
import gzip
import sys
from array import array
from urllib.parse import parse_qs

COLUNAS = ["minuto", "tipo", "taxa", "reqs", "p50_ms", "p95_ms", "p99_ms", "c2xx", "c202", "c400", "c429", "c5xx", "c0", "outros", "descartadas", "verificacoes_2xx", "inicio_s", "atrasada"]
NOME_VERIFICACAO = "/bff/users/request-verification"
CENARIOS = ("leitura", "reserva", "login", "cadastro", "escrita")
ATRASO_MIN = 2  # minutos de folga antes de fechar um balde


def classe(status):
    if status == 0:
        return "c0"
    if status == 202:
        return "c202"
    if 200 <= status <= 299:
        return "c2xx"
    if status == 400:
        return "c400"
    if status == 429:
        return "c429"
    if 500 <= status <= 599:
        return "c5xx"
    return "outros"


def percentil(ordenados, p):
    """nearest-rank: o ceil(p x n / 100)-esimo menor valor (n > 0)."""
    n = len(ordenados)
    k = -(-p * n // 100)
    return ordenados[max(1, k) - 1]


class Balde:
    __slots__ = ("durs", "taxa", "descartadas", "cont", "verificacoes_2xx")

    def __init__(self):
        self.durs = array("d")
        self.taxa = 0
        self.descartadas = 0
        self.verificacoes_2xx = 0
        self.cont = {"c2xx": 0, "c202": 0, "c400": 0, "c429": 0, "c5xx": 0, "c0": 0, "outros": 0}


def linha(minuto, tipo, b, t0=None, atrasada=0):
    d = sorted(b.durs)
    if d:
        pcts = ["%.3f" % percentil(d, p) for p in (50, 95, 99)]
    else:
        pcts = ["", "", ""]
    c = b.cont
    return [minuto, tipo, b.taxa, sum(c.values())] + pcts + [c["c2xx"], c["c202"], c["c400"], c["c429"], c["c5xx"], c["c0"], c["outros"], b.descartadas, b.verificacoes_2xx,
                                                                 "" if t0 is None else t0 + 60 * minuto, atrasada]


def acumular(b, metrica, status, r, dur=None):
    """Soma uma amostra no balde (`dur` so nas linhas normais: a atrasada nao guarda duracoes, so contagens)."""
    if metrica == "iterations":
        b.taxa += 1
    elif metrica == "dropped_iterations":
        b.descartadas += int(float(r["metric_value"]) or 1)
    else:
        if dur is not None:
            b.durs.append(dur)
        c = classe(status)
        b.cont[c] += 1
        if r.get("name") == NOME_VERIFICACAO and c in ("c2xx", "c202"):
            b.verificacoes_2xx += 1


def abrir(caminho):
    if caminho.endswith(".gz"):
        return gzip.open(caminho, "rt", encoding="utf-8", newline="")
    return open(caminho, "r", encoding="utf-8", newline="")


def agregar(entrada, saida_csv):
    """Le `entrada` (arquivo do k6) e escreve as linhas em `saida_csv` (arquivo aberto para escrita). Devolve (linhas, avisos, atrasadas).
    `atrasadas` = {tipo: {"amostras", "reqs", "c5xx", "c0"}} das amostras que chegaram com o minuto ja fechado (ficam fora das linhas normais, mas entram na linha atrasada=1 do por-minuto)."""
    baldes, ultimo_fechado, maior_min, t0 = {}, -1, -1, None
    n_linhas, avisos, atrasadas, late = 0, [], {}, {}
    w = csv.writer(saida_csv, lineterminator="\n")
    w.writerow(COLUNAS)

    def fechar(ate):
        nonlocal n_linhas, ultimo_fechado
        for chave in sorted(k for k in baldes if k[0] <= ate):
            w.writerow(linha(chave[0], chave[1], baldes.pop(chave), t0))
            n_linhas += 1
        ultimo_fechado = max(ultimo_fechado, ate)

    def atrasada(minuto, tipo, metrica, status, r):
        """Amostra que nao cabe mais (minuto fechado): vai para a linha atrasada=1 do (minuto, tipo) e para o resumo por tipo; nunca some em silencio."""
        acumular(late.setdefault((minuto, tipo), Balde()), metrica, status, r)
        d = atrasadas.setdefault(tipo, {"amostras": 0, "reqs": 0, "c5xx": 0, "c0": 0})
        d["amostras"] += 1
        if metrica == "http_req_duration":
            d["reqs"] += 1
            c = classe(status)
            if c in ("c5xx", "c0"):
                d[c] += 1
        avisos.append("amostra fora de ordem (minuto %d ja fechado): vai para a linha atrasada=1 do por-minuto (so contagens)" % minuto)

    with abrir(entrada) as f:
        for r in csv.DictReader(f):
            metrica = r.get("metric_name")
            if metrica not in ("http_req_duration", "iterations", "dropped_iterations"):
                continue
            tags = parse_qs(r.get("extra_tags") or "", keep_blank_values=True)
            tipo = (tags.get("tipo") or [""])[0]
            if not tipo and r.get("scenario") in CENARIOS:  # seguro: o cenario tem o nome do tipo (iterations/dropped_iterations podem vir sem a tag)
                tipo = r["scenario"]
            if not tipo:  # amostra sem tipo (setup(), teardown()): fora da agregacao
                continue
            try:
                ts = int(float(r["timestamp"]))
            except (KeyError, ValueError):
                continue
            minutos = tags.get("min")
            if minutos and minutos[0].isdigit():
                minuto = int(minutos[0])
                t0 = ts - 60 * minuto if t0 is None else min(t0, ts - 60 * minuto)
            else:  # sem a tag: pelo timestamp (antes da 1a requisicao com `min` ainda nao ha referencia: so pode ser o minuto 0)
                minuto = 0 if t0 is None else max(0, (ts - t0) // 60)
            status = 0
            if metrica == "http_req_duration":
                try:
                    status = int(r.get("status") or 0)
                    dur = float(r["metric_value"])
                except (ValueError, KeyError):
                    continue
            if minuto <= ultimo_fechado:
                atrasada(minuto, tipo, metrica, status, r)
                continue
            b = baldes.setdefault((minuto, tipo), Balde())
            if minuto > maior_min:
                maior_min = minuto
                fechar(maior_min - ATRASO_MIN - 1)
            acumular(b, metrica, status, r, dur if metrica == "http_req_duration" else None)
    fechar(10 ** 9)
    for (minuto, tipo) in sorted(late):  # as atrasadas, depois das normais (so contagens)
        w.writerow(linha(minuto, tipo, late[(minuto, tipo)], t0, atrasada=1))
        n_linhas += 1
    return n_linhas, avisos, atrasadas


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("entrada")
    ap.add_argument("--id", required=True)
    ap.add_argument("--saida", default=None, help="padrao: soak-<id>-por-minuto.csv no diretorio atual")
    a = ap.parse_args(argv)
    saida = a.saida or "soak-%s-por-minuto.csv" % a.id
    with open(saida, "w", encoding="utf-8", newline="") as out:
        n, avisos, atrasadas = agregar(a.entrada, out)
    for av in sorted(set(avisos)):
        print("aviso: " + av, file=sys.stderr)
    print("agregado: %d linhas (minuto x tipo) em %s" % (n, saida))
    perdeu_erro = False
    for tipo, d in sorted(atrasadas.items()):
        print("atrasadas %-8s: %d amostras (%d requisicoes; 5xx %d, status 0 %d) na linha atrasada=1 do por-minuto" % (tipo, d["amostras"], d["reqs"], d["c5xx"], d["c0"]), file=sys.stderr)
        perdeu_erro = perdeu_erro or d["c5xx"] > 0 or d["c0"] > 0
    if perdeu_erro:
        print("ERRO: amostra atrasada com 5xx/status 0 (esta na linha atrasada=1 do por-minuto, entao o criterio 3 a conta e reprova): o bloco nao pode ser aprovado sem investigar. Aumente ATRASO_MIN e agregue de novo para que ela caia na linha do proprio minuto.", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
