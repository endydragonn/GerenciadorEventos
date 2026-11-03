import sys, glob, re, os, datetime as dt
pasta, ini, fim = sys.argv[1:4]
t0 = dt.datetime.fromisoformat(ini.replace('Z','+00:00')); t1 = dt.datetime.fromisoformat(fim.replace('Z','+00:00'))
R = re.compile(r'^\[([^\]]+)\] "POST /bff/event-wallets HTTP[^"]*" (\d+) \S+ \S+ \S+ "[^"]*" \d+ \d+ (\d+) .* inbound\|8081\|')
d = {}
for f in glob.glob(os.path.join(pasta, 'accesslog-backend-*')):
    op = __import__('gzip').open if f.endswith('.gz') else open
    vistos = set()
    with op(f, 'rt', encoding='utf-8', errors='replace') as h:
        for ln in h:
            m = R.match(ln)
            if not m or ln in vistos: continue
            vistos.add(ln)
            ts = dt.datetime.fromisoformat(m[1].replace('Z','+00:00'))
            if t0 <= ts <= t1: d.setdefault(m[2], []).append(int(m[3]))
tot = sum(len(v) for v in d.values())
for c in sorted(d):
    s = sorted(d[c]); p = lambda q: s[min(len(s)-1, int(round(q*(len(s)-1))))]
    print(f'{c}: n={len(s)} ({100*len(s)/tot:.1f}%) p50={p(.5)} p95={p(.95)} p99={p(.99)}')
