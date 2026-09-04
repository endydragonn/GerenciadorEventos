# Setup da máquina/VM de geração de carga

Registro do ambiente separado usado para gerar carga contra o sistema (issue #4,
item 3 do `BACKLOG.md`). A geração de carga roda **fora** do host que executa a
stack, para que os números absolutos de latência e throughput sejam válidos — não
só a contagem de corretude (overselling).

> **Estado deste documento:** template. Os campos entre `<...>` são preenchidos por
> quem provisiona a máquina. Enquanto houver `<...>`, o aceite da issue #4 não está
> fechado.

---

## 1. Topologia

```
┌─────────────────────────┐         rede            ┌──────────────────────────┐
│  Gerador de carga        │  <tipo de link>        │  Host do sistema (alvo)  │
│  <host/VM>               │ ─────────────────────► │  <host>                  │
│  ferramenta: <k6/...>    │                        │  backend :8081           │
│                          │                        │  db (Postgres) :5433     │
└─────────────────────────┘                         │  frontend :8088          │
                                                    └──────────────────────────┘
```

| Item | Valor |
|---|---|
| Máquina/VM geradora | `<provedor / modelo / SO>` (ex.: VM local no Hyper-V, droplet, notebook secundário) |
| vCPU / RAM da geradora | `<n vCPU / n GB>` |
| Host do sistema (alvo) | `<hostname ou IP>` |
| Endereço do backend a partir da geradora | `http://<host>:8081` |
| Tipo de link geradora → alvo | `<mesma LAN / Wi-Fi / VPN / internet>` |
| Data da medição registrada abaixo | `<AAAA-MM-DD>` |

**Requisito de rede:** a geradora precisa alcançar `tcp/8081` do alvo. Confirme antes
de medir latência:

```sh
# na geradora
curl -sS -o /dev/null -w "%{http_code}\n" http://<host>:8081/actuator/health
# esperado: 200
```

---

## 2. Ferramenta de carga

**Escolhida:** `<k6 | Gatling | JMeter>` — versão `<versão>`.

> Recomendação do projeto: **k6**. Cenário em JS, saída de resumo em JSON e amostras
> em CSV (`--out csv=...`), métricas `http_req_duration` já com p50/p90/p95/p99 e
> `http_reqs` para throughput — cobre a coleta que a issue #10 pede (corretude,
> latência p50/p95/p99, throughput, lag do read model). Gatling/JMeter servem
> igual; se trocar, ajuste os comandos abaixo e o harness da issue #10.

### Instalação (k6)

```sh
# Debian/Ubuntu
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# macOS
brew install k6

# Windows
winget install k6.k6
```

Confirmação:

```sh
k6 version
# saída: <colar aqui>
```

---

## 3. Latência de rede base (a descontar depois)

Medir **com a stack de pé mas ociosa** (sem carga concorrente), a partir da
geradora. Registrar para poder descontar da latência observada nos experimentos
das issues #11–#13.

### 3.1. RTT ICMP

```sh
# 100 pings, geradora → alvo
ping -c 100 <host>
```

| Métrica | Valor |
|---|---|
| RTT mín / médio / máx / mdev | `<... / ... / ... / ...>` ms |
| Perda de pacotes | `<...>` % |

### 3.2. Latência HTTP ociosa (endpoint barato)

Reflete o caminho real (TCP + HTTP + Spring), não só ICMP. Usar um endpoint que
não toca o banco:

```sh
# 200 amostras sequenciais contra o health check
for i in $(seq 1 200); do
  curl -sS -o /dev/null -w "%{time_total}\n" http://<host>:8081/actuator/health
done | sort -n | awk '
  { a[NR]=$1; s+=$1 }
  END {
    printf "n=%d  média=%.4fs  p50=%.4fs  p95=%.4fs  p99=%.4fs  máx=%.4fs\n", \
      NR, s/NR, a[int(NR*0.50)], a[int(NR*0.95)], a[int(NR*0.99)], a[NR]
  }'
```

| Métrica | Valor |
|---|---|
| Média | `<...>` ms |
| p50 / p95 / p99 | `<... / ... / ...>` ms |
| Máx | `<...>` ms |

### 3.3. (Opcional) Banda e jitter — `iperf3`

Só se o link não for LAN local. `iperf3 -s` no alvo, `iperf3 -c <host>` na geradora.

| Métrica | Valor |
|---|---|
| Throughput | `<...>` Mbit/s |
| Jitter | `<...>` ms |

### 3.4. Baseline consolidada

> **Latência de rede base a descontar dos experimentos:** `<valor>` ms
> (critério: p95 da seção 3.2, medido em `<AAAA-MM-DD>`, link `<tipo>`).
> Se a topologia mudar (rede, host, VM), re-medir e atualizar esta linha.

---

## 4. Preparar o alvo antes de uma rodada

A stack roda no host alvo, não na geradora:

```sh
# no host alvo
docker compose -f Projeto/docker-compose.yml up -d --build db backend
```

Endpoints exercitados pela carga (acesso **direto ao backend**, sem o nginx do
frontend que remove o prefixo `/api/`):

| Ação | Requisição |
|---|---|
| Health | `GET  http://<host>:8081/actuator/health` |
| Login (obter JWT) | `POST http://<host>:8081/api/bff/users/login` |
| Reservar vaga (pending) | `POST http://<host>:8081/api/event-wallets` — corpo `{ "userId": <id>, "eventId": <id> }` |
| Confirmar reserva | `POST http://<host>:8081/api/event-wallets/confirm` |

O cenário concreto de N clientes disputando `capacity` finita, a feature flag
`RESERVATION_READPATH` (`cas` × `naive`) e a coleta em CSV/gráfico são da issue #10
— este documento só registra o ambiente.

---

## 5. Checklist de aceite (issue #4)

- [ ] Máquina/VM geradora provisionada, com acesso de rede ao `tcp/8081` do host — seção 1
- [ ] Ferramenta de carga instalada, versão registrada — seção 2
- [ ] Latência de rede base medida e registrada (seções 3.1–3.2), com a baseline
      consolidada na seção 3.4
- [ ] Este documento sem nenhum `<...>` pendente
