"""Análise do access log do Envoy do diagnóstico do K2 (#228).

Uso: python -I analisa-accesslog.py <dir_accesslog> <inicio_iso> <fim_iso>
Lê accesslog-<pod>.txt (formato padrão do Istio), filtra a janela e imprime:
  1. p50/p95/p99 de duração e upstream_service_time por salto (lado do proxy, direção, destino, rota);
  2. 5xx e response_flags por salto;
  3. requisições de entrada do POST /bff/event-wallets por pod do gateway, a cada 10 s.
"""
import collections
import datetime as dt
import glob
import os
import re
import sys

LINHA = re.compile(
    r'^\[(?P<ts>[^\]]+)\] "(?P<met>\S+) (?P<path>\S+) [^"]*" (?P<code>\d+) (?P<flags>\S+) \S+ \S+ "[^"]*" '
    r'\d+ \d+ (?P<dur>\d+) (?P<ust>\S+) "[^"]*" "[^"]*" "[^"]*" "[^"]*" "(?P<uhost>[^"]*)" (?P<cluster>\S+) ')


def rota(met, path):
    p = path.split('?')[0]
    p = re.sub(r'/\d+', '/{id}', p)
    return f'{met} {p}'


def pct(vals, q):
    if not vals:
        return None
    s = sorted(vals)
    return s[min(len(s) - 1, int(round(q * (len(s) - 1))))]


def main():
    pasta, ini, fim = sys.argv[1], sys.argv[2], sys.argv[3]
    t0 = dt.datetime.fromisoformat(ini.replace('Z', '+00:00'))
    t1 = dt.datetime.fromisoformat(fim.replace('Z', '+00:00'))
    dur = collections.defaultdict(list)
    ust = collections.defaultdict(list)
    cods = collections.defaultdict(collections.Counter)
    por_pod = collections.defaultdict(collections.Counter)
    for f in sorted(glob.glob(os.path.join(pasta, 'accesslog-*.txt'))):
        pod = os.path.basename(f)[len('accesslog-'):-4]
        svc = pod.split('-')[0]
        vistos = set()
        with open(f, encoding='utf-8', errors='replace') as h:
            for ln in h:
                m = LINHA.match(ln)
                if not m:
                    continue
                if ln in vistos:   # o kubectl logs -f relido com --since pode repetir linhas
                    continue
                vistos.add(ln)
                ts = dt.datetime.fromisoformat(m['ts'].replace('Z', '+00:00'))
                if not (t0 <= ts <= t1):
                    continue
                cl = m['cluster']
                direcao = 'in' if cl.startswith('inbound') else 'out'
                destino = svc if direcao == 'in' else (cl.split('|')[3].split('.')[0] if cl.count('|') >= 3 else cl)
                r = rota(m['met'], m['path'])
                chave = (svc, direcao, destino, r)
                dur[chave].append(int(m['dur']))
                if m['ust'] != '-':
                    ust[chave].append(int(m['ust']))
                cods[chave][(m['code'], m['flags'])] += 1
                if svc == 'backend' and direcao == 'in' and r == 'POST /bff/event-wallets':
                    por_pod[int(ts.timestamp()) // 10 * 10][pod] += 1
    print('# 1. Tempo por salto (ms): proxy, direção, destino, rota, n, duração p50/p95/p99, upstream_service_time p50/p95')
    for k in sorted(dur, key=lambda k: -len(dur[k])):
        if len(dur[k]) < 20:
            continue
        d, u = dur[k], ust.get(k, [])
        print(f'{k[0]:12} {k[1]:3} {k[2]:12} {k[3]:42} n={len(d):6} dur p50={pct(d,.5)} p95={pct(d,.95)} p99={pct(d,.99)}'
              f'  ust p50={pct(u,.5)} p95={pct(u,.95)}')
    print('\n# 2. Códigos >= 500 e response_flags diferentes de "-" por salto')
    for k in sorted(cods):
        ruins = {cf: n for cf, n in cods[k].items() if cf[0] >= '500' or cf[1] != '-'}
        if ruins:
            print(f'{k[0]:12} {k[1]:3} {k[2]:12} {k[3]:42} ' + ', '.join(f'{c} {fl}: {n}' for (c, fl), n in sorted(ruins.items())))
    print('\n# 3. POST /bff/event-wallets de entrada por pod do gateway, a cada 10 s')
    for t in sorted(por_pod):
        hora = dt.datetime.fromtimestamp(t, dt.timezone.utc).strftime('%H:%M:%S')
        print(hora, ' '.join(f'{p[-5:]}={n}' for p, n in sorted(por_pod[t].items())))


if __name__ == '__main__':
    main()
