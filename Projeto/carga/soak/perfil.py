#!/usr/bin/env python3
"""Perfil de carga do teste longo (soak, issue #232): 5 tipos de carga independentes, sorteados por SEMENTE.

Uso:  python perfil.py --id S1 --semente 42 --teto 35000 [--duracao 7200] [--saida .]
      grava <saida>/perfil-<id>.json e imprime o resumo (total esperado de cadastros, MP_MAX_MESSAGES minimo).
      python perfil.py --id dryS2 --teto 20000 --fixo dry-s2   (perfil FIXO, sem semente: ver FIXOS)
      python perfil.py --id dryMailpit --semente 257867 --teto 50000 --duracao 2400 --so cadastro   (so um tipo: ver abaixo)

Cada tipo tem a fatia dele do TETO do bloco (RPM de iteracoes; soma dos tetos = teto do bloco) e um perfil proprio de fases:
  - fase de 3 a 10 min (180 a 600 s), com rampa de 30 a 60 s no inicio dela (da taxa da fase anterior ate a desta);
  - cada fase e pico (70% a 100% do teto do tipo) ou vale (10% do teto do tipo), 50% para cada;
  - a ultima fase absorve o resto (o bloco acaba na duracao exata; nenhuma fase fica abaixo de 180 s).
O arquivo e DETERMINISTICO: mesma semente/teto/duracao = mesmos bytes (so rng.random(), inteiros derivados aqui, chaves ordenadas, LF, sem
timestamps). Ele e lido pelo carga-soak.js (stages por tipo e fases para a tag `fase`), pelo verificar-soak (total de cadastros) e fica no repo.
Perfil FIXO (`--fixo NOME`, FIXOS): a mesma tabela de fases para os 5 tipos, sem sorteio e sem semente (nao existe "outra semente"); so o teto muda a taxa. Existe para o dry-run do S2, que
precisa de subidas bruscas de vale para pico na reserva (a fila so liga na subida, antes de o reservation ganhar replica) e nao pode depender de a semente tirar uma. A gravacao e a mesma.
Um tipo so (`--so TIPO`): o perfil traz SO esse tipo (chave `so` no JSON; os outros nao rodam no k6 nem entram no por-minuto). O tipo sai IGUAL ao do perfil completo da mesma semente/teto/duracao
(cada tipo tem o seu rng e a sua fatia do teto), ex.: o dry longo do Mailpit (40 min) roda so o cadastro no ritmo do S2 (semente 257867, teto 50000). Sem `--so` o JSON e o de sempre, byte a byte.
Python >= 3.8, so biblioteca padrao.
"""
import argparse
import json
import os
import random
import sys
from fractions import Fraction
from math import ceil

# fatia de cada tipo no teto do bloco, em % inteiros (leitura recebe o resto da divisao inteira)
FATIAS = {"leitura": 60, "reserva": 30, "login": 7, "cadastro": 2, "escrita": 1}
TIPOS = ("leitura", "reserva", "login", "cadastro", "escrita")
FASE_MIN_S, FASE_MAX_S = 180, 600
RAMPA_MIN_S, RAMPA_MAX_S = 30, 60
PICO_MIN, PICO_MAX = Fraction(70, 100), Fraction(1)  # fracao do teto do tipo
VALE = Fraction(10, 100)
# perfis fixos: nome -> (duracao_s, rampa_s, ((fim_s da fase, pico?), ...)). Pico = 100% do teto do tipo, vale = 10%; a rampa de rampa_s abre cada fase (a 1a sobe de 0 ao vale).
# dry-s2: 10 min; vale 60 s, pico 120 s, vale 120 s, pico 120 s, vale 60 s, pico 120 s = 3 subidas vale -> pico em 30 s (60 s, 300 s e 480 s). So a 1a e garantida com o reservation em
# 1 replica; as outras dependem de a replica ter voltado a 1 (descida do KEDA: janela de 120 s + 1 pod a cada 60 s), e valem como chances extras.
FIXOS = {
    "dry-s2": (600, 30, ((60, False), (180, True), (300, False), (420, True), (480, False), (600, True))),
}


def tetos_por_tipo(teto):
    """Teto de cada tipo: fatias inteiras, o resto da divisao vai para a leitura (a soma e EXATAMENTE o teto do bloco)."""
    t = {k: teto * v // 100 for k, v in FATIAS.items()}
    t["leitura"] += teto - sum(t.values())
    return t


def _u(rng):
    """Sorteio em [0, 1) como Fraction exata (so rng.random(), estavel entre versoes do Python): 2**-53 de resolucao."""
    return Fraction(int(rng.random() * (1 << 53)), 1 << 53)


def fases_do_tipo(rng, teto_tipo, duracao_s):
    """Lista de fases {inicio_s, fim_s, rampa_s, pico, rpm}. Cada fase consome SEMPRE 4 sorteios (duracao, rampa, pico?, nivel)."""
    fases, t = [], 0
    while t < duracao_s:
        u_dur, u_rampa, u_pico, u_nivel = _u(rng), _u(rng), _u(rng), _u(rng)
        d = FASE_MIN_S + int(u_dur * (FASE_MAX_S - FASE_MIN_S + 1))
        r = RAMPA_MIN_S + int(u_rampa * (RAMPA_MAX_S - RAMPA_MIN_S + 1))
        resto = duracao_s - t
        if resto - d < FASE_MIN_S:  # o que sobraria seria curto demais: esta fase leva tudo, ou deixa 180 s para a ultima
            d = resto if resto <= FASE_MAX_S else resto - FASE_MIN_S
        pico = u_pico < Fraction(1, 2)
        frac = PICO_MIN + u_nivel * (PICO_MAX - PICO_MIN) if pico else VALE
        rpm = max(1, int(frac * teto_tipo + Fraction(1, 2)))  # arredonda para o inteiro mais proximo (k6: alvo inteiro)
        fases.append({"inicio_s": t, "fim_s": t + d, "rampa_s": min(r, d), "pico": pico, "rpm": rpm})
        t += d
    return fases


def fases_fixas(nome, teto_tipo):
    """Fases do perfil fixo `nome` para um tipo de teto `teto_tipo` (mesmo formato do fases_do_tipo; nenhum sorteio)."""
    _, rampa, tabela = FIXOS[nome]
    fases, t = [], 0
    for fim, pico in tabela:
        frac = Fraction(1) if pico else VALE
        fases.append({"inicio_s": t, "fim_s": fim, "rampa_s": min(rampa, fim - t), "pico": pico, "rpm": max(1, int(frac * teto_tipo + Fraction(1, 2)))})
        t = fim
    return fases


def stages_do_k6(fases):
    """stages do ramping-arrival-rate (timeUnit 1m): rampa ate a taxa da fase e o patamar ate o fim dela."""
    st = []
    for f in fases:
        st.append({"target": f["rpm"], "duration": "%ds" % f["rampa_s"]})
        plano = f["fim_s"] - f["inicio_s"] - f["rampa_s"]
        if plano > 0:
            st.append({"target": f["rpm"], "duration": "%ds" % plano})
    return st


def nos_da_taxa(fases):
    """Vertices (t_s, rpm) da taxa linear por partes, a partir de 0 RPM."""
    nos, prev = [(0, 0)], 0
    for f in fases:
        nos.append((f["inicio_s"] + f["rampa_s"], f["rpm"]))
        if f["fim_s"] > f["inicio_s"] + f["rampa_s"]:
            nos.append((f["fim_s"], f["rpm"]))
        prev = f["rpm"]
    return nos


def iteracoes(nos, a, b):
    """Integral da taxa (RPM) entre a e b segundos, em iteracoes (= area / 60), exata (Fraction)."""
    area = Fraction(0)
    for (t0, r0), (t1, r1) in zip(nos, nos[1:]):
        lo, hi = max(a, t0), min(b, t1)
        if hi <= lo:
            continue
        ra = r0 + Fraction(r1 - r0, t1 - t0) * (lo - t0)
        rb = r0 + Fraction(r1 - r0, t1 - t0) * (hi - t0)
        area += (ra + rb) * (hi - lo) / 2
    return area / 60


def _fase_em(fases, t):
    for f in fases:
        if f["inicio_s"] <= t < f["fim_s"]:
            return f
    return fases[-1]


def gerar_perfil(id_, semente, teto, duracao_s, so=None):
    return _montar(id_, semente, teto, duracao_s,
                   lambda i, tipo, teto_tipo: fases_do_tipo(random.Random(semente * 1009 + i), teto_tipo, duracao_s), so)  # semente inteira por tipo: tipos independentes


def gerar_perfil_fixo(id_, nome, teto):
    p = _montar(id_, 0, teto, FIXOS[nome][0], lambda i, tipo, teto_tipo: fases_fixas(nome, teto_tipo))
    p["fixo"] = nome
    return p


def _montar(id_, semente, teto, duracao_s, fases_do, so=None):
    if teto < 100:
        raise ValueError("teto do bloco < 100 RPM: as fatias de 1% zeram")
    if duracao_s < 60:
        raise ValueError("duracao < 60 s")
    tetos = tetos_por_tipo(teto)
    tipos, nos = {}, {}
    for i, tipo in enumerate(TIPOS):
        if so and tipo != so:  # o indice i segue contando: o tipo escolhido sai igual ao do perfil completo
            continue
        fases = fases_do(i, tipo, tetos[tipo])
        tipos[tipo] = {"teto_rpm": tetos[tipo], "fases": fases, "stages": stages_do_k6(fases)}
        nos[tipo] = nos_da_taxa(fases)
    por_minuto = []
    for m in range((duracao_s + 59) // 60):
        a, b = 60 * m, min(60 * m + 60, duracao_s)
        lin = {"minuto": m}
        for tipo in tipos:
            # taxa media do minuto em RPM (= iteracoes do minuto, num minuto cheio); pico = fase ativa no meio do minuto
            lin[tipo] = {"rpm": round(float(iteracoes(nos[tipo], a, b) * 60 / (b - a)), 3),
                         "pico": _fase_em(tipos[tipo]["fases"], Fraction(a + b, 2))["pico"]}
        por_minuto.append(lin)
    p = {
        "id": id_, "semente": semente, "teto_rpm": teto, "duracao_s": duracao_s, "fatias_pct": FATIAS,
        "tipos": tipos, "por_minuto": por_minuto,
        # 1 e-mail por cadastro (request-verification): e o que o verificar-soak compara com o MP_MAX_MESSAGES do render
        "total_cadastros_esperado": ceil(iteracoes(nos["cadastro"], 0, duracao_s)) if "cadastro" in nos else 0,
    }
    if so:
        p["so"] = so
    return p


def serializar(perfil):
    return (json.dumps(perfil, sort_keys=True, indent=1, ensure_ascii=True) + "\n").encode("utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--id", required=True, help="id do bloco (S1, S2, dry-run...): letras, digitos, hifen")
    ap.add_argument("--semente", type=int, help="obrigatoria, exceto com --fixo (o perfil fixo nao tem semente)")
    ap.add_argument("--teto", type=int, required=True, help="teto do bloco em RPM (soma dos picos coincidentes)")
    ap.add_argument("--duracao", type=int, default=None, help="segundos (padrao 7200 = 2 h; com --fixo e a do perfil)")
    ap.add_argument("--fixo", choices=sorted(FIXOS), help="perfil fixo e deterministico (sem semente): dry-s2 = 10 min com 3 subidas vale -> pico")
    ap.add_argument("--so", choices=TIPOS, help="gera SO esse tipo (o dry do Mailpit: --so cadastro); o tipo sai igual ao do perfil completo; nao vale com --fixo")
    ap.add_argument("--saida", default=".", help="diretorio de saida (perfil-<id>.json)")
    a = ap.parse_args(argv)
    if not a.id or any(not (c.isalnum() or c == "-") for c in a.id):
        ap.error("--id so aceita letras, digitos e hifen")
    if a.fixo and a.so:
        ap.error("--so nao vale com --fixo (o perfil fixo e sempre o mesmo)")
    if a.fixo:
        if a.semente is not None or a.duracao not in (None, FIXOS[a.fixo][0]):
            ap.error("--fixo nao aceita --semente nem outra --duracao (o perfil fixo e sempre o mesmo; nao troque de semente)")
        p = gerar_perfil_fixo(a.id, a.fixo, a.teto)
    else:
        if a.semente is None:
            ap.error("--semente e obrigatoria (ou use --fixo)")
        p = gerar_perfil(a.id, a.semente, a.teto, 7200 if a.duracao is None else a.duracao, a.so)
    os.makedirs(a.saida, exist_ok=True)
    caminho = os.path.join(a.saida, "perfil-%s.json" % a.id)
    with open(caminho, "wb") as f:  # bytes: LF em qualquer SO
        f.write(serializar(p))
    n = p["total_cadastros_esperado"]
    origem = "perfil FIXO %s" % a.fixo if a.fixo else "semente %d" % a.semente
    if a.so:
        origem += ", so %s" % a.so
    print("perfil %s: %s, teto %d RPM, %d s; cadastros esperados %d (MP_MAX_MESSAGES minimo %d = 2x); %s" % (a.id, origem, a.teto, p["duracao_s"], n, 2 * n, caminho))
    return 0


if __name__ == "__main__":
    sys.exit(main())
