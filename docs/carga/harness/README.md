# Harness de carga — issue #11

Cenário de N clientes concorrentes disputando uma `capacity` finita, coleta de
corretude (`confirmed` vs `capacity`), latência p50/p95/p99, throughput
(reservas/s) e lag observado do read model. Saída em CSV + gráfico HTML.

Complementa `docs/testes-manuais/overselling-sob-concorrencia.sh` (que prova
não-overselling com 2 usuários e capacity=1, no nível HTTP): este harness
generaliza para N clientes e agrega métricas de desempenho, pensado para
alimentar o experimento CAS vs. naive da Fase 2 (issues #12/#13).

## Escopo desta rodada (issue #11) — o que falta pra rodar "de verdade"

A issue #11 depende da #4 ("Preparar a máquina/VM separada para geração de
carga"), que **ainda está aberta** (PR #76 em draft, sem VM real
provisionada). O usuário decidiu seguir com a #11 mesmo assim: o harness
abaixo está **completo e funcional contra a stack local**, parametrizado por
`BASE_URL_K6` para já poder apontar para a VM da #4 sem mudar código quando
ela existir.

**O que falta, explicitamente, quando a #4 fechar:**

- Disparar a rodada **de fato de outra máquina/VM** (hoje: `BASE_URL_K6`
  aponta pra rede Docker local, `http://backend:8081`).
- **Descontar a baseline de latência de rede** entre a VM de carga e o
  backend — previsto na seção 3.4 de `docs/carga/setup.md`, que só existe na
  branch `Issue-4`/PR #76 (não foi criado nem copiado aqui de propósito: viraria
  duplicação divergente entre branches). Sem VM real, não há baseline de rede
  pra medir.

Nada além disso muda: os scripts abaixo já são o harness real da issue #11,
não uma simulação — a smoke run documentada mais abaixo rodou contra a stack
local de verdade (`docker compose up db backend`), gerando CSV e gráfico
reais.

## Arquivos

| Arquivo | Papel |
|---|---|
| `run.sh` | Orquestrador: probe → setup → collector (bg) → k6 → verify → report. É o que você roda. |
| `setup.sh` | Cria a fixture: host + N attendees + evento com `capacity` fixa (via `POST /bff/seed`), loga cada attendee e escreve os tokens. |
| `scenario.js` | Script k6: N VUs, 1 iteração cada, disparo simultâneo (`per-vu-iterations`). Cada VU faz `addLink` e, se aceito, `confirm`. |
| `collector.sh` | Amostra `event_availability.refreshed_at` (lag do read model) via `psql`, em background, durante a rodada. |
| `verify.sh` | Depois da carga, lê `eventcap`/`walletevent` **direto do banco** (fonte de verdade) e calcula o veredito de corretude. |
| `report.sh` | Junta k6 + verify + lag num CSV final + gráfico HTML autocontido (SVG inline, sem lib externa). |
| `report-selftest.sh` | Auto-teste de `report.sh` com fixture sintética (sem precisar de stack no ar) — ver seção própria abaixo. |
| `resultados/` | Saída de cada rodada (`run.sh` cria `resultados/<sufixo>/`); **não versionado** (`.gitignore` local), mesma regra do `CLAUDE.md` da raiz para relatórios gerados. |

## Pré-requisitos

```bash
docker compose -f Projeto/docker-compose.yml up -d --build db backend
```

Só isso — `run.sh` roda o k6 via container efêmero (`grafana/k6`, baixado na
primeira execução), no mesmo espírito do `Projeto/Backend/run-tests.sh` para
o Maven. Não precisa instalar k6 nem nada localmente.

## Rodar um smoke run local

```bash
docs/carga/harness/run.sh
# ou parametrizado:
N=30 CAPACITY=10 docs/carga/harness/run.sh
```

Ao final, imprime o caminho de cada artefato gerado em
`resultados/<sufixo>/`:

- `tokens.json` — tokens JWT dos attendees (entrada do k6).
- `lag.csv` — série de amostras `(timestamp_epoch, lag_ms)` do read model.
- `verify.csv` — `capacity`, `confirmadas`, `pendentes`, `veredito_corretude`.
- `k6-summary.json` / `k6-metrics.csv` — saída bruta do k6.
- `resultado.csv` — **CSV final** (formato longo `metrica,valor,unidade`), junta tudo.
- `resultado.html` — **gráfico** (barras de latência p50/p95/p99 + linha de
  lag ao longo da rodada + tabela-resumo), abra num navegador.

Por padrão a fixture (usuários + evento) é removida ao final
(`MANTER_DADOS=1` para preservar e inspecionar manualmente).

### Variáveis de ambiente principais

Ver o cabeçalho de `run.sh` para a lista completa comentada. As mais usadas:

| Variável | Default | O que é |
|---|---|---|
| `N` | `10` | nº de attendees / VUs concorrentes disputando a capacity |
| `CAPACITY` | `4` | capacity do evento-fixture (`eventcap.capacity`) |
| `BASE_URL_K6` | `http://backend:8081` | URL do backend visto de **dentro** do container do k6 — trocar pela VM da #4 quando existir |
| `BASE_URL_PROBE` | `http://localhost:8081` | URL usada pelo probe inicial e por `setup.sh`, rodando **nesta máquina** |
| `READPATH` | `cas` | só rotula o relatório final — **não troca o braço em runtime** (ver abaixo) |
| `COLLECTOR_INTERVAL_S` | `1` | intervalo (s) entre amostras de lag do `collector.sh` — ver limitação abaixo em rodadas com N alto |

## Braço `cas` (default) vs. `naive` (experimento)

`RESERVATION_READPATH` é um `@Value` do Spring (`Service/EventWalletService`),
lido **só no startup** do backend — o harness não troca isso em tempo de
execução. Pra rodar o braço "ingênuo" (issues #12/#13, fora do escopo desta
issue), suba o backend com a env var setada **antes** de chamar `run.sh`:

```bash
# Projeto/.env (ou export antes do docker compose):
RESERVATION_READPATH=naive
READMODEL_AVAILABILITY_REFRESH_MS=5000   # default; aumente para mais lag/overselling

docker compose -f Projeto/docker-compose.yml up -d --build backend
READPATH=naive docs/carga/harness/run.sh
```

No braço `naive`, `verify.sh` pode reportar `veredito_corretude=OVERSELL`
(`confirmadas > capacity`) — isso **não é um bug do harness nem do backend**:
é o fenômeno de controle do experimento (overselling proporcional ao lag do
read model, ver `Projeto/Backend/CLAUDE.md`). No braço `cas`, overselling
seria um bug real.

## Interpretação: "confirmed vs capacity" (suposição desta issue)

A reserva é em duas fases (`addLink` → `pending`, `confirm` → `confirmed`).
O Aceite da issue pede corretude como `confirmed` vs `capacity`, mas não
detalha o fluxo — decisão tomada aqui: cada VU do `scenario.js`, se `addLink`
retornar `201 pending`, encadeia imediatamente um `confirm`. A corretude final
é sempre lida do banco (`verify.sh`: `count(*) walletevent WHERE status =
'confirmed'` vs `eventcap.capacity`), nunca só pela resposta HTTP observada
pelo k6 — o k6 registra sucesso/lotado/erro em contadores customizados
(`reservas_confirmadas`, `reservas_lotadas`, `reservas_erro`) só para
diagnóstico, não como fonte de verdade.

## Limitação metodológica: overhead do polling de lag em N alto

`collector.sh` amostra o lag chamando `docker exec ... psql` a cada
`COLLECTOR_INTERVAL_S` segundos (default `1`), em background, durante toda a
rodada — cada amostra abre um processo `docker exec` + uma conexão nova ao
Postgres. Em rodadas pequenas (dezenas de VUs, o smoke run local desta issue)
o custo é desprezível frente à carga do k6. Em rodadas de N alto (issues
#12/#13, contra o mesmo host de banco), esse polling compete por CPU/IO com o
próprio banco sob teste e pode introduzir ruído na latência/throughput
observados — achado da review automática do Gemini na PR #82. Mitigação: para
N grande, aumente `COLLECTOR_INTERVAL_S` (ex.: `2`–`5`s) e leve isso em conta
na seção de "ameaças à validade" do rascunho do artigo (issue #14) — o lag do
read model muda na casca de segundos (job padrão a cada 5000ms), então uma
amostragem mais espaçada ainda captura a série real sem perder resolução
relevante.

## Lag do read model: por que pode aparecer `-1` nas primeiras amostras

`collector.sh` grava `-1` quando `event_availability` ainda não tem linha
para o evento (o job `ReadModelRefreshService` roda a cada
`READMODEL_AVAILABILITY_REFRESH_MS`, default 5000ms, e só recalcula depois de
haver reservas). Numa rodada pequena (poucos VUs), o k6 termina em bem menos
de 1 segundo — mais rápido que 1 ciclo do job. Por isso `run.sh` espera
`COLLECTOR_COOLDOWN_S` (default 6s, > 5000ms) **depois** do k6 terminar antes
de parar o collector, garantindo pelo menos 1 leitura real na maioria das
rodadas. `resultado.csv` distingue `lag_amostras` (total, inclui os `-1`) de
`lag_leituras_validas` (só as reais) — `lag_max`/`lag_medio` são calculados só
sobre as válidas (`0.0` com `lag_leituras_validas=0` significa "sem leitura
ainda", não "lag zero").

## Auto-teste de `report.sh` (`report-selftest.sh`)

`report.sh` é o único script desta pasta com lógica não trivial que **não**
depende de infra externa (não bate em HTTP nem no banco — só agrega os
arquivos que os outros scripts produzem). Por isso, ao contrário de
`setup.sh`/`collector.sh`/`verify.sh`/`run.sh` (que só fazem sentido contra a
stack real), ele é testável com fixture sintética, no mesmo espírito de
`docs/testes-manuais/overselling-sob-concorrencia.sh`: as asserções são o
teste, saem com código != 0 se algo bater errado.

```bash
docs/carga/harness/report-selftest.sh              # green esperado
REPRODUZIR_FALHA=1 docs/carga/harness/report-selftest.sh   # reproduz a
                                                             # assinatura de
                                                             # falha (p(95)
                                                             # errado no
                                                             # fixture) de
                                                             # propósito
```

Não precisa de Docker/stack no ar — só de `bash`/`awk`.

## Por que HTML+SVG inline e não uma lib de gráfico

Não há Python nem Node.js neste repo (só Java/Maven no backend e Flutter no
frontend) — nada de matplotlib/plotly/Chart.js. `report.sh` gera um HTML
autocontido com SVG desenhado à mão (barras de latência, linha de lag),
seguindo a skill `dataviz` do projeto (paleta validada para contraste/CVD,
marcas finas, grid recessivo, cor reservada para o veredito de corretude —
verde = OK, vermelho = OVERSELL). Abra o arquivo `resultado.html` direto no
navegador.
