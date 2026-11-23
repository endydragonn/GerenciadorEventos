"""Resume os CSVs de carga/consumo/medir-consumo.sh (mediana das repetições) em tabelas Markdown.

    python carga/consumo/analisar-consumo.py <pasta com servicos-rep*.csv e monolito-rep*.csv>  > resumo.md

Convenções:
- "bloco" = infra + serviços com 1 instância cada, medido no passo em que sobe o último serviço Java
  (servicos: passo `backend`; monolito: passo `backend`). O mailpit (captador de e-mail só de teste) e o frontend
  (estático, igual nos dois lados) ficam fora do bloco e de todo total; aparecem só na tabela por serviço.
- memória: `anon` = memória privada (heap, nativa); `mem` = memory.current, que inclui o cache de arquivo (as
  páginas da imagem lidas no boot ficam cobradas no 1º container que as leu, por isso a réplica tem mem ~ anon).
- CPU ociosa em % de 1 núcleo; CPU de boot em CPU-segundos até "pronto".
- Δtotal = soma do project no passo menos a soma no passo anterior (inclui o efeito na infra: conexões no
  pooler/db, partições no broker), sujeita à deriva das JVMs já no ar.
"""
import csv, glob, os, statistics, sys

FORA = {"mailpit", "frontend"}
JAVA = {"identity", "catalog", "stock", "reservation", "admission", "notification", "backend"}


def ler(pasta, fase):
    reps = {}
    for arq in sorted(glob.glob(os.path.join(pasta, f"{fase}-rep*.csv"))):
        with open(arq, newline="") as f:
            for l in csv.DictReader(f):
                reps.setdefault(l["rep"], []).append(l)
    if not reps:
        sys.exit(f"nenhum {fase}-rep*.csv em {pasta}")
    return reps


def num(v):
    return float(v) if v not in ("", None) else None


def por_passo(linhas):
    p = {}
    for l in linhas:
        p.setdefault(int(l["passo"]), []).append(l)
    return p


def total(linhas, campo):
    return sum(num(l[campo]) or 0 for l in linhas if l["servico"] not in FORA and l["servico"] != "VM")


def vm(linhas):
    return next(num(l["mem_mb"]) for l in linhas if l["servico"] == "VM")


def med(vals):
    vals = [v for v in vals if v is not None]
    return statistics.median(vals) if vals else None


def resumo(reps):
    """Por rep: lista de passos com o container novo, totais e Δ; depois a mediana entre reps por rótulo."""
    por_rot = {}
    for rep, linhas in reps.items():
        pp = por_passo(linhas)
        for passo in sorted(pp):
            ls = pp[passo]
            novo = next((l for l in ls if l["novo"] == "1"), None)
            rot = ls[0]["rotulo"]
            d = por_rot.setdefault(rot, {"passo": passo, "servico": novo["servico"] if novo else "", "v": {}})
            v = d["v"]
            tot = {c: total(ls, c) for c in ("anon_mb", "mem_mb", "cpu_ocioso_pct")}
            ant = pp.get(passo - 1)
            for c in ("t_pronto_s", "jvm_start_s", "cpu_boot_s", "cpu_ocioso_pct", "mem_mb", "anon_mb"):
                v.setdefault(c, []).append(num(novo[c]) if novo else None)
            for c, x in tot.items():
                v.setdefault("tot_" + c, []).append(x)
                v.setdefault("d_" + c, []).append(x - total(ant, c) if ant else None)
            v.setdefault("vm", []).append(vm(ls))
            v.setdefault("d_vm", []).append(vm(ls) - vm(ant) if ant else None)
    for d in por_rot.values():
        d["m"] = {c: med(x) for c, x in d["v"].items()}
    return dict(sorted(por_rot.items(), key=lambda kv: kv[1]["passo"])), len(reps)


def f(x, n=0):
    return "–" if x is None else f"{x:,.{n}f}".replace(",", "X").replace(".", ",").replace("X", ".")


def bloco(rots, ate):
    """Somatório do bloco: totais no passo `ate`, CPU de boot e subida sequencial de tudo até lá (fora mailpit/frontend)."""
    m = rots[ate]["m"]
    sub = [d for r, d in rots.items() if d["passo"] <= rots[ate]["passo"] and d["servico"] and d["servico"] not in FORA]
    return {
        "anon": m["tot_anon_mb"], "mem": m["tot_mem_mb"], "cpu": m["tot_cpu_ocioso_pct"], "vm": m["vm"],
        "boot": sum(d["m"]["cpu_boot_s"] or 0 for d in sub), "t": sum(d["m"]["t_pronto_s"] or 0 for d in sub),
        "infra_anon": sum(d["m"]["anon_mb"] or 0 for d in sub if d["servico"] not in JAVA),
        "n": len(sub),
    }


def tabela_servicos(rots, titulo, b):
    print(f"\n### {titulo}\n")
    print("| passo | container novo | pronto (s) | JVM start (s) | CPU boot (CPU-s) | CPU ociosa (% núcleo) | anon (MB) | mem (MB) | Δtotal anon (MB) | % do bloco (anon) |")
    print("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r, d in rots.items():
        if not d["servico"]:
            continue
        m = d["m"]
        print(f"| {d['passo']} | {r} | {f(m['t_pronto_s'],1)} | {f(m['jvm_start_s'],2)} | {f(m['cpu_boot_s'],1)} | "
              f"{f(m['cpu_ocioso_pct'],2)} | {f(m['anon_mb'])} | {f(m['mem_mb'])} | {f(m['d_anon_mb'])} | {f(100*m['anon_mb']/b['anon'],1)}% |")


def tabela_bloco(b, nome):
    print(f"| {nome} | {b['n']} | {f(b['t'],1)} | {f(b['boot'],1)} | {f(b['cpu'],1)} | {f(b['anon'])} | {f(b['infra_anon'])} | {f(b['mem'])} | {f(b['vm'])} |")


def tabela_replicas(rots, b, titulo):
    print(f"\n### {titulo}\n")
    print("| +1 instância | anon próprio (MB) | % do bloco (anon) | Δtotal anon (MB) | % do bloco (Δtotal) | CPU boot (CPU-s) | % do boot do bloco | CPU ociosa (% núcleo) | % da CPU ociosa do bloco | mem própria (MB) | % do bloco (mem) |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r, d in rots.items():
        if not r.endswith("+1"):
            continue
        m = d["m"]
        print(f"| {r} | {f(m['anon_mb'])} | {f(100*m['anon_mb']/b['anon'],1)}% | {f(m['d_anon_mb'])} | {f(100*m['d_anon_mb']/b['anon'],1)}% | "
              f"{f(m['cpu_boot_s'],1)} | {f(100*m['cpu_boot_s']/b['boot'],1)}% | {f(m['cpu_ocioso_pct'],2)} | {f(100*m['cpu_ocioso_pct']/b['cpu'],1)}% | {f(m['mem_mb'])} | {f(100*m['mem_mb']/b['mem'],1)}% |")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    pasta = sys.argv[1]
    sv, ns = resumo(ler(pasta, "servicos"))
    mo, nm = resumo(ler(pasta, "monolito"))
    bs, bm = bloco(sv, "backend"), bloco(mo, "backend")
    print(f"<!-- gerado por carga/consumo/analisar-consumo.py; mediana de {ns} reps (servicos) e {nm} reps (monolito) -->")
    tabela_servicos(sv, "Microsserviços: custo de subir cada container (na ordem das dependências)", bs)
    tabela_servicos(mo, "Monólito pré-#32: custo de subir cada container", bm)
    print("\n### Bloco inicial (1 instância de cada; sem mailpit e frontend)\n")
    print("| bloco | containers | subida sequencial (s) | CPU boot (CPU-s) | CPU ociosa (% núcleo) | anon (MB) | dos quais infra (MB) | mem (MB) | VM usada (MB) |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    tabela_bloco(bs, "microsserviços")
    tabela_bloco(bm, "monólito")
    print(f"\nRazão microsserviços ÷ monólito: anon {f(bs['anon']/bm['anon'],2)}×, CPU de boot {f(bs['boot']/bm['boot'],2)}×, "
          f"CPU ociosa {f(bs['cpu']/bm['cpu'],2)}×, subida sequencial {f(bs['t']/bm['t'],2)}×.")
    tabela_replicas(sv, bs, "Microsserviços: +1 instância de cada serviço, em % do bloco")
    tabela_replicas(mo, bm, "Monólito: +1 instância, em % do bloco")


if __name__ == "__main__":
    main()
