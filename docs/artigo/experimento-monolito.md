# Experimento de reserva concorrente no monólito: reserva por CAS × caminho ingênuo

> **Status: rascunho de seção do artigo (issue #15).** Os resultados das rodadas 1a e 1b e da escada de
> carga (issues #12, #13 e #14) ainda não foram preenchidos: aparecem como `[PREENCHER — …]`, com a
> issue (`#12`, `#13`, `#14`) ou "usuário" (dado que só o usuário tem). Todos se localizam por `grep PREENCHER`.
> Revisão com o orientador pendente. Caminhos de arquivo são relativos à raiz do repositório (nos comandos
> `make`, relativos a `Projeto/carga`); os termos seguem o glossário de `CONTEXT.md`.
>
> **Escopo dos números.** Todos os números desta seção são de **monólito + HikariCP** (pool e
> `max_connections` informados no Setup). Eles **não** são o ponto 1 definitivo da série de 3 pontos (`CONTEXT.md`,
> "Ponto da série"): o ponto 1 é refeito depois do pooler (Supavisor, Fase 4 do `BACKLOG.md`), e só essa
> repetição vira a linha de base "monólito CRUD" do artigo.

## Hipótese

O experimento compara dois **braços**, isto é, duas formas de checar a capacidade (`capacity`) de um evento
numa reserva (`walletevent`):

- **Reserva por CAS** (caminho `cas`, o padrão): dentro de uma única transação, um `UPDATE` condicional
  atômico em `eventcap` (`quant = quant - 1 ... AND quant >= 1`; zero linhas afetadas significa "lotado")
  e, só então, a gravação da reserva como `pending`. O design proposto.
- **Caminho ingênuo** (`naive`): lê o read model defasado `event_availability` e depois insere a reserva,
  sem CAS e sem tocar `eventcap`. O read model é recalculado por um job periódico (`ReadModelRefreshService`)
  cujo intervalo é o **lag** (`READMODEL_AVAILABILITY_REFRESH_MS`). O braço de controle.

A fundamentação está em `docs/adr/Architecture-Decision-Stock-Isolation.md`: ler a quantidade e só depois
decidir a escrita abre uma condição de corrida, e a correção é transformar "checar e reservar" numa única
operação atômica na fonte de verdade; checar a capacidade por uma projeção eventualmente consistente
reintroduz o overselling. O experimento é o diferencial do trabalho (`docs/adr/Architecture-Decision-Microservices-Split.md`):
roda primeiro contra o monólito, com um read model sintético (tabela desnormalizada, refresh por timer e
caminho de leitura atrás de feature flag), e é repetido a cada marco de arquitetura, formando a série de
3 pontos. Esta seção cobre a Fase 2, o monólito CRUD com read model sintético.

Hipóteses testáveis:

- **H1 (CAS).** O braço CAS tem overselling zero em toda **disputa**, para todo N testado, nas duas
  métricas (primária e secundária), com tolerância 0. Uma única execução do braço CAS com
  `confirmed > capacity` ou `pending + confirmed > capacity` refuta H1 e seria um defeito do CAS, não ruído
  de medição.
- **H2 (ingênuo).** O caminho ingênuo oversella, e o overselling cresce com o lag, pois um refresh mais
  espaçado deixa a leitura de vagas mais desatualizada por mais tempo (`Projeto/Backend/CLAUDE.md`:
  "Oversella proporcional ao lag do read model"; `Projeto/carga/README.md`: "overselling proporcional ao
  lag"). Por construção, cada cliente tenta uma vez, então o overselling de uma disputa é limitado por
  N − capacidade (80 para N = 100; 180 para N = 200): um patamar nesse teto para lags grandes é compatível com
  H2. H2 é enfraquecida se o overselling for zero, ou se não crescer com o lag antes de atingir o teto. O
  resultado é reportado como medido, sem repetir rodadas nem descartar lags para "achar" a tendência
  (`Projeto/carga/README.md`, rodada 1b).
- **Limiar X.** Não é uma hipótese sobre um valor, e sim uma medição: o primeiro degrau de carga em que o
  fluxo síncrono degrada. Pode não existir dentro da escada ("X acima do último degrau") ou valer só como
  limite inferior, se o gerador saturar antes do host.

## Setup

**Topologia.** Duas máquinas na mesma rede (`docs/carga/setup.md`): o **host** (alvo do teste: stack Docker
com backend em `:8081` e Postgres em `:5433`) e o **gerador** (roda o k6). O k6 nunca roda no host, pois
competiria por CPU e rede com o backend e invalidaria as latências. O link do gerador é de 1 Gbps; do lado do
host só está registrado "Ethernet, mesma rede do gerador". A matriz também consulta o Postgres do host pelo
gerador, por `psql` remoto na `:5433`, porta aberta apenas para o IP do gerador (`Projeto/carga/README.md`).

| Item | Host (alvo) | Gerador |
|---|---|---|
| CPU / RAM / disco | **[PREENCHER — usuário]** | AMD Ryzen 5 7600 (6 núcleos); RAM e disco: **[PREENCHER — usuário]** |
| SO | **[PREENCHER — usuário]** | **[PREENCHER — usuário]** |
| Rede | Ethernet, mesma rede do gerador | link de 1 Gbps; meio físico (cabo ou Wi-Fi): **[PREENCHER — usuário]** |
| Ferramentas | Docker Desktop, versão: **[PREENCHER — usuário]** | k6 v2.2.0 (go1.26.5, windows/amd64); `psql`, versão: **[PREENCHER — usuário]** |

Os campos `[PREENCHER — usuário]` deste Setup são n/d ou não constam nos registros do repositório e não
foram reconstruídos depois. Fontes: `docs/carga/setup.md` (seção 5) e `Projeto/carga/results/baseline-20261004-122632.txt`.

**Versões e configuração do backend e do banco.**

| Componente | Valor |
|---|---|
| PostgreSQL | 16 (imagem `postgres:16-alpine`, `Projeto/Backend/db/Dockerfile`); versão menor **[PREENCHER — usuário]** |
| Backend | Spring Boot 3.5.7, Java 21 na compilação e `temurin:25-jre` na execução (`Projeto/Backend/CLAUDE.md`) |
| HikariCP | versão **[PREENCHER — usuário]** (gerenciada pelo Spring Boot; não registrada no repositório); `maximum-pool-size` padrão 1000 e `minimum-idle` 5 (o pool cresce sob demanda) |
| `max_connections` do Postgres | **[PREENCHER — issue #14]** (colunas `max_connections` e `pool_hikari` de `Projeto/carga/results/degraus-<id>.csv`); o `matriz.csv` não grava esses campos, então o valor das rodadas 1a e 1b também deve ser registrado aqui: **[PREENCHER — issues #12 e #13]** |
| Braço e lag | `RESERVATION_READPATH` (`cas` \| `naive`) e `READMODEL_AVAILABILITY_REFRESH_MS`, lidos no startup do backend (`Projeto/carga/docker-compose.carga.yml`); log de SQL desligado no host de carga para não distorcer a latência |
| Commit testado | coluna `commit` dos CSVs (checkout do gerador); host e gerador no mesmo commit: **[PREENCHER — issues #12, #13 e #14]** |

**Latência base HTTP (não comparável; não subtraída).** O `setup-gerador.ps1` mediu, em 2026-10-04, 30
requisições `GET /actuator/health` cronometradas com `Stopwatch` em torno de `Invoke-WebRequest` (PowerShell):
p50 14,9 / p95 19,0 / máx 21,1 ms, numa única medição (`docs/carga/setup.md`, seção 5). Esse valor vem de
outro cliente que não o k6 e de um endpoint que consulta o Postgres; portanto não é a latência de rede (RTT) e
não é comparável ao tempo de requisição do k6 (excede, por exemplo, a latência de reserva medida pelo k6 nas
rodadas preliminares). Por isso **não é subtraído**: as latências do k6 são reportadas como medidas, com o RTT
embutido e não isolado. O ICMP não respondeu (100 pacotes sem retorno), então não há ping nem perda de
pacotes. Nova medição para as rodadas #12–#14, se houver: **[PREENCHER — usuário]**.

## Workload

### Disputa (rodadas 1a e 1b, matriz)

A unidade de execução do experimento de corretude é a **disputa**: N clientes concorrentes, cada um com
usuário próprio, tentam reservar o mesmo evento de `capacity` 20. Cada cliente faz uma tentativa
(`POST /bff/event-wallets`) e toda reserva aceita (201) é confirmada na mesma iteração
(`POST /bff/event-wallets/confirm`, `PCT_CONFIRMA=100`). No k6, é um cenário `shared-iterations` com um VU
por cliente; o login dos usuários (bcrypt) acontece no `setup()`, antes da rajada e fora da medição
(`Projeto/carga/carga.js`).

Cada chamada do runner (`Projeto/carga/rodar-matriz.ps1`) faz, nesta ordem:

1. um aquecimento descartado, uma vez por chamada e antes do seed do primeiro N (rajada pequena, evento
   próprio, sem linha no CSV);
2. por N, o seed: N usuários e um evento novo por repetição, todos com a mesma capacidade;
3. por repetição (um evento novo cada), espera o read model ter a linha do evento e depois um tempo aleatório
   em `[0, lag)`, de modo que a rajada caia numa fase pseudoaleatória do job de refresh, sem se sincronizar
   com ele;
4. dispara a rajada e, enquanto ela roda, amostra o lag no Postgres do host;
5. lê `confirmed` e `pending` do evento no Postgres e grava uma linha em `Projeto/carga/results/matriz.csv`.

| Rodada | Braço | N | Lag (ms) | Repetições | Linhas |
|---|---|---|---|---|---|
| 1a (#12) | `cas` | {100, 200} | 5000 (só rotula a coluna: o CAS não lê o read model) | 5 | 10 |
| 1b (#13) | `naive` | {100, 200} | {500, 2000, 5000, 10000} | 5 | 40 |

O total é de 50 linhas no mesmo CSV. O lag e o braço são lidos no startup do backend e não mudam em
runtime, então a rodada 1b reinicia a stack do host uma vez por lag (`make host-rpm`), e o valor passado ao
gerador apenas rotula as colunas `braco` e `lag_ms`: ele precisa bater com o que o host roda, o que se confere
com `docker exec gerenciador-backend printenv RESERVATION_READPATH READMODEL_AVAILABILITY_REFRESH_MS`.

Uma repetição só vira linha do CSV se for **válida**: k6 com código 0, resumo com `disparo_ms` (alguma
iteração rodou), nenhum usuário sem login, nenhuma resposta inesperada ou 5xx e toda reserva aceita
confirmada. Repetição inválida é descartada, contada e
listada no fim, e o runner sai com código 2 (matriz incompleta, refazer). Já o overselling no braço CAS não
invalida nada: é um resultado, entra no CSV e o runner sai com código 3.

### Escada de carga (limiar X, #14)

Uma única execução do k6, com um cenário `constant-arrival-rate` por degrau (seed e login uma vez só), que
estende o teste de taxa fixa (`Projeto/carga/README.md`, "Limiar X preliminar", e
`Projeto/carga/degraus/carga-degraus.js`):

- degraus a partir de 12k RPM (`DEG_INICIO`), de 4k em 4k (`DEG_PASSO`), até `DEG_MAX` (padrão 40k; no máximo
  40 degraus), 60 s por degrau (`DEG_DURACAO`, no mínimo 60 e múltiplo de 30);
- braço CAS (`READPATH=cas`) nos comandos do protocolo, 200 usuários, `MAX_VUS` 2000 (todos pré-alocados) e
  `PCT_CONFIRMA=100`;
- cada reserva é um par (usuário, evento) único, em sequência (usuário `i % U`, evento `floor(i / U)`), com
  capacidade por evento igual à metade dos usuários (padrão de `Projeto/carga/degraus/rodar-degraus.ps1`;
  100 com 200 usuários);
- só `POST` de reserva e de confirmação, sem leituras;
- no padrão (12k a 40k): 8 degraus, 208.008 pares, 1.093 eventos e cerca de 11,6 min de k6, com 31 s ociosos
  entre degraus;
- a escada vai até o máximo, mesmo depois de o host degradar.

## Métricas

As definições seguem `CONTEXT.md`. A tabela diz como cada uma é coletada.

| Métrica | Definição | Coleta |
|---|---|---|
| **Overselling primário** | Evento com `confirmed` maior que `capacity`. Coluna `oversell` = `max(0, confirmed − capacity)`, a magnitude. | `psql` remoto no Postgres do host (fonte de verdade), depois da rajada. |
| **Overselling secundário** | `pending + confirmed` maior que `capacity`. Derivável das colunas `pending` e `confirmed`. | Mesma leitura. |
| **Tolerância no braço CAS** | 0 nas duas métricas: igual a `capacity` passa; maior falha (código 3 do runner). | Verificada pelo runner a cada execução. |
| **Lag** | Intervalo de refresh do read model (`READMODEL_AVAILABILITY_REFRESH_MS`); coluna `lag_ms`. É a variável independente do braço ingênuo. O job usa `fixedDelay`, então o período efetivo é o lag mais a duração de cada refresh. | Configurado no host; rotulado no CSV. |
| **Lag observado** | `lag_obs_disparo` (última amostra antes do disparo) e `lag_obs_max` (maior valor entre essa amostra e as da rajada); `-1` = sem leitura. | `now() − refreshed_at` do evento (relógio do Postgres), amostrado a cada ~250 ms pelo gerador por `psql` remoto; o instante da amostra é o do relógio do gerador, o mesmo do k6. |
| **Latência da reserva** | p50, p95 e p99 de `POST /bff/event-wallets`, em ms. | k6 no gerador; reportada como medida, sem subtrair a latência base HTTP (ver Setup: não é comparável). |
| **Vazão** | Matriz: tentativas por segundo na janela da rajada (1ª iteração iniciada até a última terminada), sem os logins do `setup()`. Escada: reservas por segundo respondidas sem 5xx. | k6 (resumo da execução). |
| **Taxa de 5xx** | `erros_5xx / reservas` (só os 5xx da reserva, sobre as reservas do degrau), como fração. Os 5xx da confirmação ficam em `erros_5xx_confirma`, informativos, fora do X. Respostas inesperadas (4xx fora de "lotado", timeout, conexão recusada) vão para `outros` e não entram em X. | k6, por degrau. |
| **p95 por janela de 30 s** | `p95_max_janela_30s`: maior p95 da reserva entre as janelas de 30 s do degrau. | k6: cada reserva é marcada pela janela do instante de **envio**; janelas fixas alinhadas ao início do degrau. |
| **Limiar X** | Primeiro degrau da escada com 5xx acima de 1% das reservas ou p95 acima de 1 s por 30 s. | Regra aplicada só pelo report (`docs/carga/harness/report-degraus.sh`), sobre o CSV. |

Detalhes de coleta que afetam a leitura:

- Os dois limites do X são estritos (`taxa_5xx` > 1%; `p95_max_janela_30s` > 1000 ms).
- A taxa de 5xx do X é só da reserva (5xx das reservas / reservas do degrau): o X marca o ponto em que a reserva
  passaria a ir para a fila, e os 5xx da confirmação não entram, mas ficam registrados em `erros_5xx_confirma`.
- O gerador é considerado **saturado** num degrau se `iteracoes_descartadas > 0` ou se `rpm_efetivo` ficar abaixo
  de 95% do alvo. Se ele saturou estritamente antes do primeiro degrau que degradou, X vira **limite inferior**
  (X maior que o último degrau sem saturação). Se degradou e saturou no mesmo degrau, X é firme, com aviso.
- O overselling é medido no banco, não pela contagem de respostas do cliente, que é só indicativa.
- O lag observado tem a resolução do intervalo de amostragem e a rajada é curta, então `lag_obs_max` costuma
  igualar `lag_obs_disparo`.
- Agregação (`docs/carga/harness/report-matriz.sh`): por (N, capacidade), média, ±1 desvio amostral e mín–máx
  das repetições em cada lag, eixo x linear em ms; o braço CAS, que não depende do lag, entra como linha de
  referência (média de todas as suas execuções).
- **Ponto da série**: medição do experimento em um marco da arquitetura (monólito CRUD, monólito ES,
  distribuído). Os números aqui são "monólito + HikariCP", não o ponto 1 definitivo.

## Resultados

Mapa das fontes: a rodada 1a (#12) e a rodada 1b (#13) alimentam o mesmo `Projeto/carga/results/matriz.csv`
(linhas `braco=cas` e `braco=naive`), e o gráfico sai de `make report-matriz`
(`Projeto/carga/results/matriz.html`, regenerável, fora do git). A escada (#14) gera
`Projeto/carga/results/degraus-<id>.csv` e `Projeto/carga/results/summary-degraus-<id>.json`; o gráfico sai de
`make report-degraus`. Resultado como medido: lags e execuções não são descartados nem repetidos para
melhorar a curva (só as repetições inválidas são refeitas, pelas regras do runner).

### Overselling × lag (duas curvas: CAS e ingênuo)

> **[PREENCHER — issue #12]** Braço CAS (rodada 1a). Fonte: linhas `braco=cas` de
> `Projeto/carga/results/matriz.csv` (10 linhas: N ∈ {100, 200} × 5 repetições) e gráfico de
> `make report-matriz`. Preencher: `confirmed`, `pending` e `oversell` por execução; confirmar `oversell = 0`
> nas 10 linhas e a ausência de `[FALHA CAS]` (código de saída 3); p95 da reserva por N; número de repetições
> inválidas descartadas. Se algum `oversell > 0`, reportar como achado (defeito do CAS), não como ruído.

> **[PREENCHER — issue #13]** Braço ingênuo (rodada 1b). Fonte: linhas `braco=naive` do mesmo
> `Projeto/carga/results/matriz.csv` (40 linhas: 4 lags × 2 valores de N × 5 repetições) e gráfico de
> `make report-matriz`. Preencher a tabela abaixo, a figura (overselling × lag, eixo x linear, braço CAS como
> linha de referência) e um comentário sobre a forma observada (crescente, patamar no teto N − capacidade,
> queda, ruído), sem repetir rodadas nem descartar lags.

| Lag (ms) | N | `oversell` média ± desvio | `oversell` mín–máx | `lag_obs_disparo` médio (ms) | p95 da reserva (ms) |
|---|---|---|---|---|---|
| 500 | 100 | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] |
| 500 | 200 | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] |
| 2000 | 100 | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] |
| 2000 | 200 | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] |
| 5000 | 100 | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] |
| 5000 | 200 | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] |
| 10000 | 100 | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] |
| 10000 | 200 | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] | [PREENCHER — issue #13] |

### Limiar X (escada de carga)

> **[PREENCHER — issue #14]** Escada de carga. Fonte: `Projeto/carga/results/degraus-<id>.csv` (uma linha
> por degrau), `Projeto/carga/results/summary-degraus-<id>.json` e o gráfico de
> `make report-degraus CSV=results\degraus-<id>.csv` (comando rodado em `Projeto/carga`).
> Preencher: o **X** (primeiro degrau com `taxa_5xx` > 1%, de 5xx das reservas, ou `p95_max_janela_30s` > 1000 ms), dizendo
> explicitamente se o report o marcou como **limite inferior** (gerador saturado antes), como "X acima do
> último degrau" ou como "saturou já no 1º degrau"; `max_connections` e `pool_hikari` do CSV; a tabela por
> degrau (`rpm_alvo`, `rpm_efetivo`, `taxa_5xx`, `erros_5xx_confirma`, `p95`, `p95_max_janela_30s`, `throughput`,
> `iteracoes_descartadas`); as curvas de latência e vazão × carga oferecida e de taxa de 5xx × RPM alvo; e a
> causa dos 5xx no log do backend (`too many clients` indicaria o teto de conexões: ver "Ameaças à validade").

### Resultados preliminares (fora do protocolo)

Antes do protocolo acima, rodadas de 2026-10-04 (`Projeto/carga/results/ANALISE.md`) mediram os dois braços
com **um único lag (5 s) e uma ou duas repetições por configuração** (`BACKLOG.md`, seção "Estado";
`ANALISE.md`, seção 6: "uma rodada por configuração"). Servem como motivação e como expectativa, **não**
como resultado do protocolo: não entram nas curvas nem no X. A tabela traz só overselling, medido no banco
como `(pending + confirmed) − capacidade` (`make verificar-*`), isto é, a métrica secundária; com 100% de
confirmação ela coincide com a primária. As rodadas pós-pool rodaram com `max_connections=1000`
(`docs/carga/setup.md`, seção 6), enquanto os comandos do protocolo usam `MAX_CONN=300`
(`Projeto/carga/README.md`): mais um motivo para não compará-las com a matriz e a escada.

| Teste preliminar | Braço | Overselling (banco) |
|---|---|---|
| Rodadas pós-pool (disputa, taxa fixa e aleatório; com e sem confirmação) | `cas` | 0 (`eventos_com_overselling = 0`) |
| Disputa de 50 / 100 / 200 clientes, capacidade 20, com 100% e 60% de confirmação (N = 50 está fora do protocolo, que usa 100 e 200) | `naive` | 30 / 80 / 180 (igual nas duas confirmações) |
| Taxa fixa de 10k RPM por 90 s, 79 eventos | `naive` | 7.347 reservas a mais em 75 eventos (100% de confirmação); 6.985 em 75 eventos (60%) |
| Aleatório, 45 min, 1464 eventos | `naive` | 0 |

Leitura (interpretação pelo código e pelo padrão de carga, **não medida diretamente**): na disputa preliminar
do braço ingênuo todos os clientes foram aceitos (0 "lotado"), porque a rajada cabe dentro de um intervalo de
refresh e o read model mostra 20 vagas durante toda ela; no aleatório, as tentativas de um mesmo evento chegam
mais espaçadas que o refresh e o evento já aparece cheio na tentativa seguinte. Isso sugere que o overselling
do braço ingênuo depende da concentração de tentativas por evento dentro do lag, e não da taxa total
(hipótese a testar, não resultado).

Para o X, os dados preliminares de monólito + HikariCP ficam abaixo ou no início da escada: 10k RPM por 90 s
no braço CAS, com 0 respostas 5xx e p95 da reserva de 4,2 ms (`ANALISE.md`, seção 3), e o aleatório de 45 min
(1k–12k RPM, com leituras), também sem 5xx. Não são comparáveis aos degraus (carga, duração e mistura
diferentes). Os números anteriores ao pool de conexões (por exemplo, 5xx de cerca de 31% a 10k RPM) estão
invalidados (issue #96) e não são usados.

## Ameaças à validade

As sete primeiras são as ameaças obrigatórias do experimento; a sétima está **aberta**.

1. **LAN única.** Host e gerador estão na mesma rede (host em Ethernet; gerador com link de 1 Gbps, meio
   físico não registrado). Não há variação de topologia nem das condições de rede. O RTT não foi isolado: o
   ICMP não respondeu e a "latência base" HTTP (p50 de 14,9 ms) veio de outro cliente (PowerShell) e de um
   endpoint que consulta o Postgres, então não é comparável ao k6 e não é subtraída; as latências do k6 vão
   como medidas, com o RTT embutido. Latências absolutas e X valem para esta topologia; o efeito de uma rede
   mais lenta ou com jitter sobre a duração da rajada, e portanto sobre o overselling do braço ingênuo, não
   foi avaliado.
2. **Um evento por disputa.** Cada disputa concentra os N clientes num único evento: uma linha de `eventcap`
   e uma do read model. É o caso de maior concentração. Nas rodadas preliminares (ver "Resultados
   preliminares") o braço ingênuo teve overselling na disputa concentrada e nenhum no teste aleatório, com
   as tentativas espalhadas por muitos eventos; o resultado da matriz vale para a concentração máxima e não
   diz quanto se oversella com a demanda espalhada. Na matriz também não há disputas simultâneas entre
   eventos.
3. **Determinismo do job de refresh.** O overselling do braço ingênuo depende do ponto do ciclo do job em
   que a rajada chega e de haver ou não um refresh no meio dela. A mitigação é a espera aleatória em
   `[0, lag)`, as 5 repetições e a coleta de `lag_obs_*`. Resíduos: 5 repetições são uma amostra pequena das
   fases; a rajada é curta (cerca de 0,1-0,3 s segundo `Projeto/carga/lib-matriz.ps1`; "menos de ~3 s" nas
   rodadas preliminares, `ANALISE.md`), e na disputa preliminar todos os clientes foram aceitos, então para
   lags grandes o overselling pode atingir um patamar em N − capacidade que esconde a sensibilidade ao lag;
   o job usa `fixedDelay`, então o período efetivo é o lag mais a duração do refresh, um único
   `INSERT ... SELECT` que recalcula todas as linhas de `eventcap` e tende a crescer com os eventos acumulados
   no banco, de modo que a espera uniforme em `[0, lag)` cobre só parte do ciclo; e sob saturação o job pode
   atrasar (observado antes do pool, em `docs/carga/setup.md`). Em ambos os casos o lag efetivo difere do
   configurado, e o `lag_obs_*` registra o efeito.
4. **bcrypt no seed.** O seed e o login usam bcrypt (custo alto): semear centenas de usuários leva dezenas de
   segundos e os logins do `setup()` custam cerca de 10 ms por cliente, o que domina o tempo de cada
   execução e deixa a rajada ocupar uma fração mínima dele. A consequência de validade é que esse custo leva a
   usar poucos usuários (200 na escada) para gerar milhares de reservas, com os pares (usuário, evento) em
   sequência (`Projeto/carga/README.md`, "Como funciona"), o que concentra as tentativas de cada evento (ver
   a ameaça 10). O `setup()` só termina depois de todos os logins (`http.batch`), então a rajada não compete
   com o bcrypt; resta, como possibilidade não medida, algum efeito do estado do host logo depois de N
   logins (coleta de lixo, JIT). A mitigação é medir só a janela da rajada (`disparo_ms` e `fim_ms`), sem os
   logins.
5. **Pool HikariCP de 1000.** O tamanho padrão do pool não foi explorado: nas rodadas preliminares, com 100 VUs
   e latências de poucos ms, o pool nunca chegou perto de 1000 (`ANALISE.md`, seção 6), e o padrão só é seguro
   com `max_connections` alto. Um pool menor mudaria onde as requisições esperam (fila no pool em vez de conexão no Postgres) e,
   com isso, a latência e o X (não testado). Os resultados são de "monólito + HikariCP (1000)".
6. **Sem `pg_stat_statements`.** A extensão não é coletada nesta fase (a coleta está prevista para o harness
   definitivo da Fase 8, e a imagem do Supabase a traz: `docs/adr/Architecture-Decision-Supabase-Self-Host.md`).
   Sem ela não há tempo por instrução SQL (o `UPDATE` do CAS, o UPSERT em `walletevent`, o `INSERT ... SELECT`
   do refresh), e as causas de latência e do X permanecem hipóteses. Os CSVs da matriz e da escada também não
   trazem série de CPU, memória nem uso do pool durante a carga, e o `sockstat` pós-pool não cobre as rodadas.
7. **p95 bimodal (~460 ms) sem causa medida (ameaça aberta).** Nas rodadas preliminares pós-pool
   (medição fora do protocolo), o backend apresentou uma latência de **leitura** bimodal: p50 de ~2 ms e p95 de
   ~460–475 ms, nos braços `cas` e `naive`, que não vem do pool nem da confirmação (`ANALISE.md`, seção 6).
   A suspeita, não medida por endpoint, é que `GET /bff/events/search` varra os eventos em memória. A matriz
   e a escada enviam só `POST` (reserva e confirmação), então esse comportamento não deveria aparecer nelas,
   mas a causa não foi isolada e não há evidência de que o caminho de escrita sob a escada esteja livre dele.
   Segue aberta até a latência ser medida por endpoint.

Ameaças adicionais, sustentadas pelos documentos do kit:

8. **Pool maior que `max_connections`.** O pool cresce sob demanda a partir de 5 conexões; se crescer além de
   `max_connections`, o Postgres pode recusar conexões (`too many clients`) e o backend responde HTTP 500
   (`Projeto/carga/README.md`, "Por que `MAX_CONN` importa"). Os comandos do protocolo usam `MAX_CONN=300` com
   o pool padrão de 1000 (o valor efetivo é o registrado no Setup), então 5xx na escada podem ser artefato de
   configuração, o que pode antecipar o X. Mitigação parcial: `max_connections` e `pool_hikari` vão para o
   CSV, e a causa dos 5xx deve ser conferida no log. Na matriz, repetições com 5xx são descartadas, não
   gravadas.
9. **Saturação do gerador.** Se o k6 não sustenta a taxa (iterações descartadas ou `rpm_efetivo` abaixo de
   95% do alvo), o degrau mede o gerador, e X passa a limite inferior; a tolerância de 5% é fixa. Os 2000 VUs
   pré-alocados custam memória no gerador (estimativa de 1–2 MB por VU).
10. **Concentração por evento crescente com o RPM na escada.** Os pares (usuário, evento) são consumidos em
    sequência (usuário `i % U`, evento `floor(i / U)`, com U = 200 usuários;
    `Projeto/carga/degraus/carga-degraus.js`), então cada evento recebe até 200 tentativas, uma após a outra
    (capacidade 100); o padrão é o mesmo descrito em `ANALISE.md`, seção 5.3, para o teste de taxa fixa. A
    carga por evento é fixa: só a duração dessa rajada por evento encurta com o RPM (200 · 60 / RPM segundos,
    conta derivada da regra de pares: cerca de 1 s a 12k RPM e 0,3 s a 40k RPM). Logo, nos degraus altos
    sobem juntas a carga total e a concentração temporal por evento (contenção na linha de `eventcap`), e o X
    confunde os dois efeitos.
11. **Degraus contíguos e timeouts fora do X.** Entre degraus só há 31 s ociosos, sem resfriamento, e a
    degradação de um degrau pode contaminar o seguinte. Um host que trava gera timeouts, contados em `outros`
    e não como 5xx; o report avisa, mas a regra do X não captura essa forma de degradação.
12. **Ambiente não caracterizado.** CPU, RAM, disco, SO e a versão do Docker Desktop do host (e RAM, disco,
    SO e o meio físico do gerador) não constam nos registros (placeholders no Setup), o que limita a
    reprodução e a leitura do X em termos de recurso. O plano de energia
    "Alto desempenho" e o host na tomada são recomendados, mas não há registro de que tenham sido aplicados.
13. **Rótulo versus configuração real.** `braco` e `lag_ms` são rotulados pelo operador no gerador, e o
    `commit` do CSV é o do checkout do gerador: o host precisa estar no mesmo commit e na mesma configuração,
    o que é conferido manualmente (`printenv`). O `lag_obs_*` permite detectar uma discrepância de lag, mas
    não de braço, de commit nem de pool.

## Limitações

- **Não é o ponto 1 definitivo da série.** Os números são de "monólito + HikariCP", sem o Supavisor. O ponto 1
  é refeito na Fase 4 (rodadas 1a e 1b repetidas com o pooler, atualizando este documento), e os pontos 2
  (monólito com CQRS + ES) e 3 (distribuído) vêm depois. Nenhuma comparação entre pontos é possível com estes
  números.
- **Lag sintético.** O read model é recalculado por timer (lag fixo e configurável); não é o atraso de uma
  projeção real, que varia com o consumidor. A transferência do resultado para a projeção ES é o objeto dos
  pontos 2 e 3.
- **X preliminar e em RPM agregado.** O X desta fase vem de uma única execução da escada e é expresso em
  reservas por minuto do total, somando todos os eventos. Não se lê como RPM por evento: pela regra de pares,
  a carga por evento é fixa (até 200 tentativas, capacidade 100) e só a duração da rajada por evento encurta
  com o RPM (ameaça 10). O limiar da arquitetura de admissão é definido por evento (`RPM(evento) ≥ X` em
  `docs/adr/Architecture-Decision-Async-Checkout-Queue.md`) e será definido na Fase 8; esta medição não o
  converte.
- **Escopo do workload.** Só escrita (reserva e confirmação) com `PCT_CONFIRMA=100`; capacidade fixa em 20 e
  N ∈ {100, 200} na disputa; um único evento por disputa. A escada não tem leituras, e o teste aleatório
  (com leituras) ficou fora do protocolo.
- **Vazão do kit anterior não usada.** Os números de "Vazão" e `reservas_por_s` do teste 1 anterior à matriz
  (`docs/carga/setup.md`, seção 6, tabela da disputa; coluna `reservas_por_s` de
  `Projeto/carga/results/resultados.csv`) não são reportados aqui. Há uma hipótese, **não confirmada**, de que meçam a taxa de login do
  `setup()` (bcrypt) e não a de reserva: o `duracao_s` desse teste inclui o `setup()` (`Projeto/carga/README.md`;
  `Projeto/carga/lib-matriz.ps1`). Não se afirma que estejam errados; a vazão desta seção vem só do
  `throughput` da matriz (janela da rajada) e do `throughput` da escada.
- **Amostra e execução.** 5 repetições por (lag, N) e uma execução da escada; a execução é feita pelo
  usuário no gerador, e o resultado depende do estado do host naquele dia.
- **Observabilidade.** Sem `pg_stat_statements`, sem série de recursos do host e sem medição de latência por
  endpoint (ver as ameaças 6 e 7).
