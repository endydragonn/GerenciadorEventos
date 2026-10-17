# Experimento de reserva concorrente no monólito: reserva por CAS × caminho ingênuo

> **Status: rascunho de seção do artigo (issue #15).** Os resultados das rodadas 1a e 1b e da escada de
> carga (issues #12, #13 e #14) foram preenchidos com as rodadas de 2026-10-16. O **ponto 1 da série** (monólito
> CRUD + pooler) foi medido depois, com as rodadas 1a e 1b repetidas com o Supavisor (issue #22).
> Revisão com o orientador pendente. Caminhos de arquivo são relativos à raiz do repositório (nos comandos
> `make`, relativos a `Projeto/carga`); os termos seguem o glossário de `CONTEXT.md`.
>
> **Escopo dos números.** Há dois conjuntos, que **não se misturam**:
>
> - **Ponto 1 (pós-pooler, "monólito CRUD + pooler").** As rodadas 1a e 1b repetidas em 2026-10-16 com o
>   Supabase Postgres 17 e o Supavisor (issue #22). É a linha de base oficial da série de 3 pontos (`CONTEXT.md`,
>   "Ponto da série"): subseção "Ponto 1 (pós-pooler)" dos Resultados. Só a matriz foi refeita.
> - **Pré-pooler (referência, "monólito + HikariCP").** A matriz 1a/1b anterior e a escada de carga (limiar X),
>   com o backend direto no PG 16 (pool e `max_connections` informados no Setup). Ficam como histórico e como
>   comparação. O limiar X **não foi refeito** com o pooler.

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
| CPU / RAM / disco | AMD Ryzen 7 7700X (8 núcleos / 16 threads), 63 GB de RAM (31 GB e 16 CPUs para a VM do Docker), SSD NVMe | AMD Ryzen 5 7600 (6 núcleos / 6 threads, SMT desligado), 15,2 GB de RAM (DDR5-5200), SSD NVMe |
| SO | Windows 11 Home (build 26300) | Windows 11 Pro (build 26200), plano de energia "Alto desempenho" |
| Rede | Ethernet a 1 Gbps, por cabo, mesma rede do gerador | Ethernet a 1 Gbps, por cabo |
| Ferramentas | Docker Desktop (engine 29.8.2) | k6 v2.2.0 (go1.26.5, windows/amd64); `psql` 16.15 |

Configuração levantada em 2026-10-16, dia das escadas da #14. Fontes: `docs/carga/setup.md` (seção 5),
`Projeto/carga/results/01-sem-pool/baseline-20261004-122632.txt` e `Projeto/carga/results/02-hikari/smoke-20261016/baseline-20261016-162438.txt`.

**Versões e configuração do backend e do banco (pré-pooler, referência).**

| Componente | Valor |
|---|---|
| PostgreSQL | 16.15 (imagem `postgres:16-alpine`, `Projeto/Backend/db/Dockerfile`) |
| Backend | Spring Boot 3.5.7, Java 21 na compilação e `temurin:25-jre` na execução (`Projeto/Backend/CLAUDE.md`) |
| HikariCP | 6.3.3 (gerenciada pelo Spring Boot; lida do jar do backend); `maximum-pool-size` padrão 1000 e `minimum-idle` 5 (o pool cresce sob demanda) |
| `max_connections` do Postgres | Escadas da #14: **100** até a `degraus-959190` (o `make setup-host` sobe o Postgres com o padrão) e **1000** nas `degraus-959765`, `-962591` e `-963487` (`make host-rpm MAX_CONN=1000`), valores lidos no host com `SHOW max_connections`. A coluna `max_connections` dos CSVs é só um rótulo passado ao gerador e diz 300 nas escadas com 100: vale o valor medido no host. `pool_hikari` = 1000 em todas. O `matriz.csv` não grava esses campos: nas rodadas 1a e 1b o host rodou com **300** (`make host-rpm MAX_CONN=300`, conferido com `SHOW max_connections`) e Tomcat com 200 threads (padrão). |
| Braço e lag | `RESERVATION_READPATH` (`cas` \| `naive`) e `READMODEL_AVAILABILITY_REFRESH_MS`, lidos no startup do backend (`Projeto/carga/docker-compose.carga.yml`); log de SQL desligado no host de carga para não distorcer a latência |
| Commit testado | coluna `commit` dos CSVs (checkout do gerador); host e gerador no mesmo commit: `5120412` em todas as escadas da #14 (a `degraus-963487` rodou com o `.js` editado localmente, ver "Limiar X"); `569d1be` nas 50 linhas da matriz (rodadas 1a e 1b), sem mudança de código em relação ao `5120412` |

**Condições do ponto 1 (pós-pooler).** Mesmas máquinas e mesmo protocolo da matriz; mudaram o banco, o pooler
e o estado do banco. Rodadas de 2026-10-16, por volta de 21:38 a 21:49 (horário do gerador).

| Componente | Valor |
|---|---|
| Commit | `8ba01b4` (`Fase_4` com as issues #17, #18, #19 e #21; branch `Issue-22`), host e gerador |
| PostgreSQL | 17.6 (imagem `supabase/postgres:17.6.1.136`, locale `en_US` ICU); antes, 16.15 (`postgres:16-alpine`, `pt_BR`) |
| Pooler | Supavisor 2.9.13 em transaction mode; o backend conecta em `supabase-pooler:6543` com `prepareThreshold=0` |
| Conexões | `max_connections` 300 (`subir-host-rpm.ps1 -MaxConnections 300`); pool do Supavisor no Postgres **245** (`max_connections − 55`); `POOLER_MAX_CLIENT_CONN` 1500; HikariCP `maximum-pool-size` 1000 e `minimum-idle` 5 (só enfileira no pooler) |
| Threads do Tomcat | **200** (padrão do Spring Boot): o ponto 1 foi medido assim; a partir do ponto 2 o backend usa **300** (issue #124; no teste de 2026-10-17 pelo pooler, mesmo teto de vazão, menor p95) |
| Role do app | `admin`, sem superuser |
| Estado do banco | **limpo** (volume novo) no começo da rodada 1a |
| Braço e lag | por lag, o host foi reiniciado em `naive` e conferido com `printenv RESERVATION_READPATH READMODEL_AVAILABILITY_REFRESH_MS` |
| Ferramentas do gerador | k6 v2.2.0; `psql` 16.15 |

Fonte: `Projeto/carga/results/03-supabase-pooler/README.md`. O CSV não grava essas colunas.

**Latência base HTTP (não comparável; não subtraída).** O `setup-gerador.ps1` mediu, em 2026-10-04, 30
requisições `GET /actuator/health` cronometradas com `Stopwatch` em torno de `Invoke-WebRequest` (PowerShell):
p50 14,9 / p95 19,0 / máx 21,1 ms, numa única medição (`docs/carga/setup.md`, seção 5). Esse valor vem de
outro cliente que não o k6 e de um endpoint que consulta o Postgres; portanto não é a latência de rede (RTT) e
não é comparável ao tempo de requisição do k6 (excede, por exemplo, a latência de reserva medida pelo k6 nas
rodadas preliminares). Por isso **não é subtraído**: as latências do k6 são reportadas como medidas, com o RTT
embutido e não isolado. O ICMP não respondeu (100 pacotes sem retorno), então não há ping nem perda de
pacotes. Nova medição em 2026-10-16, antes das escadas da #14, pelo mesmo método: p50 14,7 / p95 18,2 / máx
20,1 ms (n = 30; `Projeto/carga/results/02-hikari/smoke-20261016/baseline-20261016-162438.txt`); de novo maior que a
latência de reserva do k6 nos degraus baixos (p95 de 7,7 a 10,9 ms na `degraus-953959`).

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
5. lê `confirmed` e `pending` do evento no Postgres e grava uma linha em `Projeto/carga/results/matriz.csv` (a área de trabalho da rodada corrente; ao fechar a etapa o
   CSV vai para `results/NN-etapa/`, ver Resultados).

| Rodada | Braço | N | Lag (ms) | Repetições | Linhas |
|---|---|---|---|---|---|
| 1a (#12) | `cas` | {100, 200} | 5000 (só rotula a coluna: o CAS não lê o read model) | 5 | 10 |
| 1b (#13) | `naive` | {100, 200} | {500, 2000, 5000, 10000} | 5 | 40 |

O total é de 50 linhas no mesmo CSV, nas duas matrizes (pré-pooler e ponto 1). O lag e o braço são lidos no startup do backend e não mudam em
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
  distribuído). O ponto 1 é o "monólito CRUD + pooler" (subseção "Ponto 1 (pós-pooler)"); os números de
  "monólito + HikariCP" são a referência pré-pooler, não o ponto 1.

## Resultados

Mapa das fontes: a rodada 1a (#12) e a rodada 1b (#13) alimentam o mesmo `matriz.csv` (linhas `braco=cas` e
`braco=naive`). O runner grava em `Projeto/carga/results/matriz.csv` e, ao fechar a etapa, o arquivo é movido para a
pasta dela: o **ponto 1** está em `Projeto/carga/results/03-supabase-pooler/matriz.csv` (50 linhas, commit
`12fc154`) e a matriz **pré-pooler** em `Projeto/carga/results/02-hikari/matriz-prepooler-20261016.csv` (50 linhas,
versionado em `1fbe12b` como `matriz.csv`). O gráfico de cada etapa sai de `report-matriz.sh` sobre o CSV dela
(HTML regenerável, fora do git; `Projeto/carga/README.md`, "Resultados"). A escada (#14) gera
`Projeto/carga/results/02-hikari/degraus-<id>.csv` e `summary-degraus-<id>.json`; o gráfico sai de
`make report-degraus`. Resultado como medido: lags e execuções não são descartados nem repetidos para
melhorar a curva (só as repetições inválidas são refeitas, pelas regras do runner).

### Ponto 1 (pós-pooler): overselling × lag e latência

Rodadas de 2026-10-16 com o Supabase Postgres 17 e o Supavisor (condições no Setup), capacidade 20, 100% de
confirmação, commit `8ba01b4`. Fonte: `Projeto/carga/results/03-supabase-pooler/matriz.csv` (50 linhas, commit
`12fc154`); detalhe e tabelas completas em `docs/carga/resultados-matriz-pospooler-2026-10-16.md`. Nenhuma
repetição foi descartada. Os agregados foram recalculados do CSV.

**Braço CAS (rodada 1a, #12).** `oversell = 0` nas 10 linhas, com `confirmed = 20` e `pending = 0` em todas: **H1
se sustenta** no ponto 1. Latência e vazão, com a comparação com o pré-pooler (média de 5 repetições; p95 com
mín–máx):

| N | p50 pré → pós (ms) | p95 pré → pós (ms) | p99 pré → pós (ms) | Vazão pré → pós (tentativas/s) |
|---|---|---|---|---|
| 100 | 44,3 → 84,0 | 53,7 (46,9–66,3) → 103,9 (84,6–117,6) | 55,4 → 107,2 | 1632,7 → 870,9 (−46,7%) |
| 200 | 59,2 → 115,8 | 77,1 (64,5–86,2) → 155,3 (125,5–203,7) | 81,1 → 160,8 | 2075,5 → 1178,5 (−43,2%) |

A latência do CAS **piorou** cerca de 2× e a vazão caiu 43% a 47%; os intervalos de p95 entre repetições não se
sobrepõem. Esse resultado mistura **três mudanças simultâneas** (a fila do pooler em transaction mode; PG 16 → 17
com outro locale; teto real de conexões de ~300 para 245) e também o estado do banco (limpo agora, inchado antes).
**Não se atribui o aumento ao pooler.** Separar os efeitos exigiria rodadas extras (por exemplo, pooler com pool de
~300, ou PG 17 sem pooler), que **não foram feitas** (ameaça 14).

**Braço ingênuo (rodada 1b, #13).**

| Lag (ms) | N | `oversell` média ± desvio | `oversell` mín–máx | Execuções no teto | `lag_obs_disparo` médio (ms) | p95 da reserva, média (mín–máx) (ms) | Vazão média (tentativas/s) |
|---|---|---|---|---|---|---|---|
| 500 | 100 | 77,6 ± 5,4 | 68–80 | 4 de 5 | 241 | 116,8 (87,0–146,0) | 746,5 |
| 500 | 200 | 116,0 ± 87,7 | 15–180 | 3 de 5 | 309 | 137,5 (90,1–175,9) | 1219,0 |
| 2000 | 100 | 80 ± 0 | 80–80 | 5 de 5 | 1084 | 116,4 (83,9–156,0) | 780,1 |
| 2000 | 200 | 180 ± 0 | 180–180 | 5 de 5 | 1370 | 144,7 (118,0–170,3) | 1079,8 |
| 5000 | 100 | 76,8 ± 7,2 | 64–80 | 4 de 5 | 2729 | 137,0 (90,8–197,6) | 673,4 |
| 5000 | 200 | 180 ± 0 | 180–180 | 5 de 5 | 2762 | 159,0 (139,8–190,7) | 1027,7 |
| 10000 | 100 | 80 ± 0 | 80–80 | 5 de 5 | 6211 | 117,8 (96,9–141,5) | 720,8 |
| 10000 | 200 | 180 ± 0 | 180–180 | 5 de 5 | 4211 | 161,3 (148,8–179,2) | 981,5 |

**O lag observado passou a respeitar o configurado.** Com o banco limpo, no lag de 500 ms o `lag_obs_disparo` ficou
entre 151 e 428 ms (média de 275; `lag_obs_max` até 503), contra 851–2095 ms (média de 1592) na matriz
pré-pooler. Nos demais lags, a média do `lag_obs_disparo` (1227, 2746 e 5211 ms) fica perto de metade do lag, como se
espera de um disparo em fase aleatória do ciclo.

**O overselling deixou de estar sempre no teto.** Em 36 das 40 repetições o overselling ficou no teto `N − capacidade`
(80 e 180), como antes. Em 4 a rajada atravessou um refresh que "fechou" o evento no meio (`confirmed < N`, isto é,
parte dos clientes recebeu "lotado"):

| Lag (ms) | N | Rep | `confirmed` | `oversell` | `lag_obs_disparo` / `lag_obs_max` (ms) |
|---|---|---|---|---|---|
| 500 | 100 | 5 | 88 | 68 | 295 / 295 |
| 500 | 200 | 2 | 35 | 15 | 193 / 503 |
| 500 | 200 | 3 | 45 | 25 | 192 / 495 |
| 5000 | 100 | 5 | 84 | 64 | 4677 / 5014 |

O braço de controle continua com overselling em todos os lags, e a dependência da fase do refresh, que a hipótese
previa, antes ficava escondida pelo lag inflado. A leitura é qualitativa: com 5 repetições por configuração e
desvio de até 87,7 (lag 500, N = 200), a amostra não permite ajustar uma curva nem estimar a probabilidade de a
rajada cruzar um refresh. Dentro desse limite, o resultado é compatível com H2 para N = 200 (116,0 no lag de 500
ms e 180 a partir de 2000 ms), mas não mostra tendência monotônica para N = 100 (77,6, 80, 76,8 e 80), e uma
repetição do lag de 5000 ms também ficou abaixo do teto.

**Comparação do ingênuo com o pré-pooler.** O p95 da reserva subiu nas 8 configurações (de 14% a 48%) e a vazão caiu em 7
de 8 (a exceção, lag 500 com N = 200, é puxada pelas duas repetições com rejeição, que respondem mais rápido). As
mesmas três mudanças simultâneas e o banco limpo valem para essa comparação.

### Overselling × lag, pré-pooler (referência: monólito + HikariCP)

Rodadas de 2026-10-16 (anteriores ao pooler), capacidade 20, 100% de confirmação, `max_connections` 300, commit
`569d1be`. Fonte: `Projeto/carga/results/02-hikari/matriz-prepooler-20261016.csv` (50 linhas; versionado em
`1fbe12b` como `matriz.csv`); gráfico regenerável com `report-matriz.sh` sobre esse CSV.
Nenhuma repetição foi descartada nos dois braços.

**Braço CAS (rodada 1a, #12).** `oversell = 0` nas 10 linhas (N ∈ {100, 200} × 5 repetições), com
`confirmed = 20` e `pending = 0` em todas: exatamente a capacidade, nenhuma a mais. Sem `[FALHA CAS]` (código
de saída 0). p95 da reserva: média de 53,7 ms (46,9–66,3) com N = 100 e 77,1 ms (64,5–86,2) com N = 200.

**Braço ingênuo (rodada 1b, #13).**

| Lag (ms) | N | `oversell` média ± desvio | `oversell` mín–máx | `lag_obs_disparo` médio (ms) | p95 da reserva, média (mín–máx) (ms) |
|---|---|---|---|---|---|
| 500 | 100 | 80 ± 0 | 80–80 | 1714 | 102,8 (77,3–135,2) |
| 500 | 200 | 180 ± 0 | 180–180 | 1470 | 118,1 (113,3–127,7) |
| 2000 | 100 | 80 ± 0 | 80–80 | 2408 | 95,2 (61,5–170,6) |
| 2000 | 200 | 180 ± 0 | 180–180 | 2678 | 111,3 (99,4–133,5) |
| 5000 | 100 | 80 ± 0 | 80–80 | 3620 | 92,3 (68,1–140,4) |
| 5000 | 200 | 180 ± 0 | 180–180 | 3344 | 121,3 (104,4–139,4) |
| 10000 | 100 | 80 ± 0 | 80–80 | 5541 | 95,9 (74,6–139,3) |
| 10000 | 200 | 180 ± 0 | 180–180 | 6337 | 115,7 (98,9–145,1) |

**Forma observada: patamar no teto N − capacidade, sem variação com o lag.** Em todas as 40 execuções todos
os N clientes foram aceitos e confirmaram (`confirmed = N`), então o overselling é sempre o máximo possível
(80 e 180) e o desvio é zero. A hipótese de que o overselling cresce com o lag **não foi observada** nesta
faixa: a rajada dura de ~0,1 a 0,2 s (N ÷ vazão, com vazão de 518 a 1449 tentativas por segundo), menos que o menor período
efetivo de refresh, então o read model nunca reflete a lotação durante a rajada, em nenhum lag. Para ver a
curva subir seria preciso um lag menor que a duração da rajada ou rajadas espalhadas no tempo (não testado).

**Lag observado acima do configurado.** No lag de 500 ms, o `lag_obs_disparo` médio ficou em ~1,5–1,7 s; nos
lags maiores, em ~metade do lag, como se espera de um disparo em fase aleatória do ciclo. O período efetivo é o
lag mais a duração do refresh (`fixedDelay`), e o refresh recalcula a disponibilidade de **todos** os eventos.
No dia das rodadas, o banco do host acumulava ~35 mil eventos e ~2 milhões de linhas em `walletevent` das
escadas da #14, o que alonga cada refresh. O lag de 500 ms, portanto, não foi atingido de fato. O resultado
não muda (o teto já é atingido), mas a coluna `lag_ms` deve ser lida junto com `lag_obs_disparo`. No ponto 1, com o
banco limpo, o lag observado voltou a acompanhar o configurado e o teto deixou de ser universal (acima).

### Limiar X (escada de carga, pré-pooler)

Escadas de 2026-10-16, braço CAS, 200 usuários, **antes do pooler** (HikariCP direto no PG 16; não refeitas no ponto 1). Fonte: `Projeto/carga/results/02-hikari/degraus-<id>.csv` e
`summary-degraus-<id>.json` (commit `5caac91`); veredito de `make report-degraus`. Em **todas** as escadas:
`taxa_5xx = 0` e `erros_5xx_confirma = 0`. O host nunca respondeu 5xx: quando satura, ele fica lento e o critério
que dispara é o p95 por janela de 30 s.

**Escada do protocolo (`degraus-953959`, 12k → 40k, 2000 VUs, `max_connections` 100).** Nenhum degrau
degradou: **X > 40.000 RPM (acima do último degrau)**. p95 de 7,7 a 10,9 ms, 0 descartes, gerador sem
saturação. Como o protocolo não achou o X, as escadas seguintes estenderam a faixa. Essa extensão foi
decidida depois de ver o resultado, então está **fora do pré-registro**.

**Escadas estendidas (fora do pré-registro).**

| Escada | Faixa (RPM) | VUs | `max_connections` (host) | Veredito | Observação |
|---|---|---|---|---|---|
| `degraus-954833` | 50k → 120k | 2000 | 100 | X > 100k, **limite inferior** | gerador esgotou as portas efêmeras do Windows a partir de 60k (`outros` = erros de cliente); descartado como medida do host |
| `degraus-956799` | 60k → 130k | 3000 | 100 | **X = 100.000** | p95 de janela de 1339,8 ms; rpm efetivo estaciona em 97–99,8k |
| `degraus-958305` | 95k → 100k, passo 1k | 3000 | 100 | **X = 97.000**; último degrau limpo 96.000 | 97k teve pico isolado (p95 de janela de 4254,3 ms); 98k–100k estáveis em ~1,3 s |
| `degraus-959190` | 96k → 97k, **60% de confirmação** | 3000 | 100 | X > 97.000 | p95 de 205,8 / 216,2 ms: com 40% menos confirmações o host passa no X |
| `degraus-959765` | 60k → 130k | 3000 | 1000 | **X = 100.000** | rpm efetivo de 94–95k; mais conexões não subiram o teto |
| `degraus-962591` | 95k (diagnóstico) | 3000 | 1000 | — | Tomcat com 400 threads (override local): p95 de 582,2 ms contra 242,5 ms com 200 threads e 100 conexões |
| `degraus-963487` | 100k → 160k, passo 20k | 3000 | 1000 | 120k e 140k limpos | mapeamento **intercalado** (ver abaixo); em 160k o gerador saturou (portas, VUs, CPU de 99%) |

Degraus da escada que define o X (`degraus-956799`), em ms:

| `rpm_alvo` | `rpm_efetivo` | `taxa_5xx` | `p95` | `p95_max_janela_30s` | `throughput` (reservas/s) | `iteracoes_descartadas` |
|---|---|---|---|---|---|---|
| 60.000 | 60.001 | 0 | 105,6 | 126,0 | 1000,0 | 0 |
| 70.000 | 70.001 | 0 | 122,4 | 125,0 | 1166,7 | 0 |
| 80.000 | 80.001 | 0 | 177,7 | 178,2 | 1333,4 | 0 |
| 90.000 | 90.001 | 0 | 221,6 | 235,5 | 1500,0 | 0 |
| 100.000 | 99.033 | 0 | 1315,9 | 1339,8 | 1650,6 | 967 |
| 110.000 | 99.784 | 0 | 1351,8 | 1369,8 | 1663,1 | 10.218 |
| 120.000 | 97.156 | 0 | 1405,7 | 1406,6 | 1619,3 | 22.845 |
| 130.000 | 98.139 | 0 | 1396,5 | 1422,1 | 1635,7 | 31.862 |

Os descartes a partir de 100k vêm da latência do host: com os VUs pré-alocados, só há descarte quando a
iteração passa de `MAX_VUS ÷ (RPM/60)` s. O gerador ficou com CPU de no máximo 66% e mais de 4 GB livres. O
report marca X como firme, com aviso de descartes no mesmo degrau.

**Causa do teto: contenção na linha do `eventcap`.** Nem conexões (100 → 1000) nem threads do Tomcat
(200 → 400) subiram o teto; as duas mudanças só aumentaram a latência na saturação. O container do backend
não tem limite de CPU (a JVM enxerga 16 CPUs) e ficou em ~420–470%. Durante a `degraus-962591`, o Postgres
tinha 162 sessões em `LWLock:LockManager`, 119 em `Lock:transactionid` e 90 em `Lock:tuple` (amostra de
`pg_stat_activity` no host). O motivo está no mapeamento da escada: a iteração `i` usa o evento `floor(i / U)`,
então 200 reservas seguidas fazem o `UPDATE` condicional (CAS) na **mesma linha** de `eventcap`, e essas
atualizações se serializam. Com o mapeamento intercalado (evento `i % E`, `.js` editado só localmente para o
diagnóstico e revertido; a coluna `commit` não reflete a edição), as esperas por lock de linha somem (só
sobra `LWLock:WALWrite`) e o host sustenta **120k e 140k RPM** com p95 de 317,1 e 262,7 ms.

**Leitura.** O X medido, ≈ 97–100k RPM (~1650 reservas/s), é a vazão de **um evento disputado** no CAS
(todas as reservas no mesmo `eventcap`), não a capacidade do host. Com a carga espalhada entre eventos, o
host passa de 140k RPM, e esse teto não é mensurável com um único gerador. Como o gargalo é uma linha do banco,
adicionar réplicas do backend não deve subir o X de um evento disputado (hipótese, não medida). A opção de
mapeamento na escada é a issue #113.

### Resultados preliminares (fora do protocolo)

Antes do protocolo acima, rodadas de 2026-10-04 (`Projeto/carga/results/02-hikari/ANALISE.md`) mediram os dois braços
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
   configurado, e o `lag_obs_*` registra o efeito. No ponto 1, o banco limpo reduziu esse efeito (ameaça 15).
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
   com isso, a latência e o X (não testado). Os resultados pré-pooler são de "monólito + HikariCP (1000)". No ponto
   1 o HikariCP (1000) só enfileira no Supavisor, cujo pool real no Postgres é 245; o efeito disso sobre a latência
   não foi isolado (ameaça 14).
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
   configuração, o que pode antecipar o X. (No ponto 1 isso não se aplica do mesmo modo: o HikariCP conecta ao
   Supavisor, com `POOLER_MAX_CLIENT_CONN` 1500, que enfileira sobre as 245 conexões reais.) Mitigação parcial: `max_connections` e `pool_hikari` vão para o
   CSV, e a causa dos 5xx deve ser conferida no log. Na matriz, repetições com 5xx são descartadas, não
   gravadas. Nas escadas de 2026-10-16 isso não aconteceu: com `max_connections` 100 e pool de 1000, o Postgres
   chegou a recusar um `psql` externo (`too many clients`), mas o backend não registrou erro de conexão e a
   `taxa_5xx` ficou em 0. O risco que se confirmou foi outro: a coluna `max_connections` do CSV é um rótulo e
   divergiu do valor real (300 no CSV, 100 no host).
9. **Saturação do gerador.** Se o k6 não sustenta a taxa (iterações descartadas ou `rpm_efetivo` abaixo de
   95% do alvo), o degrau mede o gerador, e X passa a limite inferior; a tolerância de 5% é fixa. Os 2000 VUs
   pré-alocados custam memória no gerador (estimativa de 1–2 MB por VU). Em 2026-10-16 o gerador limitou de três
   formas: portas efêmeras do Windows acima de ~60k RPM (corrigido com
   `netsh int ipv4 set dynamicport tcp start=10000 num=55535`; a `degraus-954833` fica como limitada pelo
   gerador), memória com 4000 VUs (escada interrompida, sem dados) e, com a carga intercalada, CPU e portas em
   160k RPM. Acima de ~140k RPM é preciso um segundo gerador.
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
14. **Três mudanças simultâneas no ponto 1.** O ponto 1 difere da matriz pré-pooler em três fatores ao mesmo tempo:
    (a) a fila do pooler em transaction mode (Supavisor, um salto a mais por transação), (b) PG 16 → 17 e o locale
    (`postgres:16-alpine` com `pt_BR` → `supabase/postgres:17.6` com `en_US` ICU) e (c) o teto real de conexões, de
    ~300 (HikariCP direto; a role `admin` era superuser e podia usar as 3 conexões reservadas) para 245 (`max_connections − 55`). O CAS ficou cerca de 2× mais
    lento e com 43% a 47% menos vazão, e os dados **não separam** os três efeitos nem o do estado do banco
    (ameaça 15). Não se atribui o aumento ao pooler. Uma leitura plausível, **não medida**, é que o teto de 245 não
    limite estas rodadas, pois no máximo N = 200 clientes disputam ao mesmo tempo. Separar os efeitos exigiria
    rodadas extras, por exemplo pooler com pool de ~300 (isola c) ou PG 17 sem pooler (isola a); **não foram feitas**.
15. **Banco limpo × inchado.** O ponto 1 começou com o banco limpo (volume novo); a matriz pré-pooler rodou sobre ~35
    mil eventos e ~2 milhões de `walletevent` acumulados pelas escadas do dia. O refresh do read model recalcula
    todos os eventos, então o estado do banco muda o lag efetivo do braço ingênuo (lag 500: ~1,6 s antes, 151–428 ms
    agora) e, com ele, a chance de a rajada cruzar um refresh. Também pode afetar a latência do CAS (hipótese, não
    medida). A diferença do overselling do ingênuo entre as duas matrizes acompanha o lag observado, e portanto o estado do
    banco; não foi isolada dos demais fatores (ameaça 14).

## Limitações

- **Só a matriz tem ponto 1.** O ponto 1 (monólito CRUD + pooler) cobre as rodadas 1a e 1b. O limiar X vem da
  etapa pré-pooler (HikariCP direto, PG 16) e não foi refeito com o Supavisor. Os pontos 2 (monólito com CQRS +
  ES) e 3 (distribuído) vêm depois e se comparam com o ponto 1, não com os números pré-pooler. A comparação
  pré × pós-pooler dentro do ponto 1 mistura três mudanças e o estado do banco (ameaças 14 e 15).
- **Lag sintético.** O read model é recalculado por timer (lag fixo e configurável); não é o atraso de uma
  projeção real, que varia com o consumidor. A transferência do resultado para a projeção ES é o objeto dos
  pontos 2 e 3.
- **X preliminar e em RPM agregado.** O X desta fase (pré-pooler) vem de uma única execução da escada e é expresso em
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
  `Projeto/carga/results/02-hikari/resultados.csv`) não são reportados aqui. Há uma hipótese, **não confirmada**, de que meçam a taxa de login do
  `setup()` (bcrypt) e não a de reserva: o `duracao_s` desse teste inclui o `setup()` (`Projeto/carga/README.md`;
  `Projeto/carga/lib-matriz.ps1`). Não se afirma que estejam errados; a vazão desta seção vem só do
  `throughput` da matriz (janela da rajada) e do `throughput` da escada.
- **Amostra e execução.** 5 repetições por (lag, N) e uma execução da escada; a execução é feita pelo
  usuário no gerador, e o resultado depende do estado do host naquele dia.
- **Observabilidade.** Sem `pg_stat_statements`, sem série de recursos do host e sem medição de latência por
  endpoint (ver as ameaças 6 e 7).
