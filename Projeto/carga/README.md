# Testes de carga

Este kit é o **runner oficial do experimento** da Fase 2 (CAS × caminho ingênuo): a
[matriz](#matriz-do-experimento-runner-oficial) (`make test-matriz`) gera o CSV e o gráfico dos
resultados. O harness de `docs/carga/harness/` é só sanity local (stack no próprio host).

Duas máquinas: o **host** (roda a stack, o alvo do teste) e o **gerador** (roda o k6).
O k6 **nunca** roda no host: competiria por CPU/rede com o backend e invalidaria as latências.

| Máquina | Precisa de | Não precisa de |
|---|---|---|
| Host | Docker Desktop, `Projeto/.env` | k6 |
| Gerador | k6 (o `setup-gerador` instala via winget), PowerShell, Git for Windows (Git Bash, usado pelo seed e pelo report), clone **completo** do repo; só para a matriz: `psql` (cliente PostgreSQL 16) | Docker, banco, `.env` |

> Nunca copie o `Projeto/.env` para o gerador (credenciais reais).

Atalhos via `make` (GNU make; `winget install ezwinports.make`). Rode em `Projeto/carga`:
`make help`. Sem make, chame os `.ps1` direto (exemplos abaixo).

## Preparação

**No host** (PowerShell como Administrador, para criar a regra de firewall da 8081; rede como Privada). A 5433 (Postgres) **não** é aberta aqui: só a matriz usa, e só com `GERADOR_IP` (ver "Matriz do experimento"):

```powershell
make setup-host            # ou: .\setup-host.ps1 [-ReadPath cas|naive] [-RefreshMs 5000]
```

Anote o IP impresso (`HOST_IP`).

**No gerador:**

```powershell
make setup-gerador HOST_IP=<ip>    # ou: .\setup-gerador.ps1 -HostIp <ip>
```

Instala o k6, testa a conexão e salva a latência base de rede em `results\baseline-*.txt`
(o valor a descontar das medições). Registre também em `docs/carga/setup.md`, seção 5.

## Matriz do experimento (runner oficial)

Para **um** lag e **um** braço por vez, roda a disputa de vagas (teste 1, `PCT_CONFIRMA=100`) para
uma lista de N e um número de repetições, e acumula **uma linha por execução** em
`results\matriz.csv`. O lag (`READMODEL_AVAILABILITY_REFRESH_MS`) e o braço (`RESERVATION_READPATH`)
são lidos no startup do backend e não mudam em runtime: o valor passado ao gerador
(`READPATH` / `REFRESH_MS`) só **rotula** as colunas `braco` e `lag_ms` e precisa bater com o que o
host está rodando. Para varrer lags e braços, troque a config no host e rode a matriz de novo; as
linhas se acumulam no mesmo CSV.

Host e gerador devem estar **no mesmo commit** (a coluna `commit` é o do checkout do gerador).

```powershell
# 1) host (PowerShell como Administrador). GERADOR_IP é obrigatório para a matriz: cria a regra de
#    firewall da 5433 (Privada) SEMPRE restrita a esse IP; sem ele a 5433 fica fechada.
make setup-host READPATH=naive REFRESH_MS=2000 GERADOR_IP=<ip do gerador>
#    Ao fim do experimento, feche a 5433:  Remove-NetFirewallRule -DisplayName 'TCC postgres 5433'

# 2) gerador, uma vez por sessão: senha do Postgres do host SÓ no ambiente (não ecoa, não vai pro histórico)
$s = Read-Host 'Senha do Postgres do host' -AsSecureString
$env:PGPASSWORD = [System.Net.NetworkCredential]::new('', $s).Password

# 3) gerador: a matriz (READPATH/REFRESH_MS = os mesmos do passo 1)
make test-matriz HOST_IP=<ip do host> VUS=50,100,200 REPETICOES=10 CAPACIDADE=20 READPATH=naive REFRESH_MS=2000

# 4) outro lag/braço: volte ao passo 1 com os novos valores e repita o passo 3
#    (ex.: REFRESH_MS=500, 1000, 5000 e READPATH=cas)

# 5) gerador: gráfico (results\matriz.csv -> results\matriz.html; Git Bash + awk, sem dependência nova)
make report-matriz
```

Sem `make`: `.\rodar-matriz.ps1 -HostIp <ip> -Vus 50,100,200 -Repeticoes 10 -Capacidade 20 -Rotulo naive -RefreshMs 2000`
e `bash ../../docs/carga/harness/report-matriz.sh results/matriz.csv results/matriz.html`.
`PGUSER`/`PGDATABASE` (ou `-PgUsuario`/`-PgBanco`) assumem `admin`/`meu_banco` (os do projeto; se o
`Projeto/.env` do host usar outros, informe). A **senha** vem só de
`PGPASSWORD`: nunca como argumento ou variável do `make` (o make ecoa a linha de comando), nunca
impressa, nunca gravada. Instalar o `psql` no gerador: `make setup-gerador` mostra o comando
(`winget install PostgreSQL.PostgreSQL.16 --interactive`, marcando só "Command Line Tools").

Com N alto (≥ ~100) e `max_connections=100` (padrão do Postgres) o pool do backend pode estourar o
limite (ver "Por que `MAX_CONN` importa"; o `VUS` padrão chega a 200): rode o passo 1 uma vez (firewall) e
suba a stack com `make host-rpm MAX_CONN=300 READPATH=... REFRESH_MS=...` no lugar do `setup-host` (as
regras de firewall persistem). Repetições afetadas por `too many clients` (5xx) são **descartadas**, não
gravadas (ver abaixo).

### O que cada execução faz

1. **Seed único por N**: `N` usuários + `REPETICOES` eventos novos, todos com a mesma `capacity`
   (um evento novo por repetição). Antes da 1ª repetição de todas, um **aquecimento descartado**
   (rajada pequena, `-VusAquecimento`, evento próprio, sem linha no CSV).
2. Espera o read model ter a linha do evento e depois um tempo **aleatório em `[0, lag_ms)`**: a
   rajada cai numa fase pseudo-aleatória do job de refresh, sem sincronizar com ele.
3. Dispara o k6 (`carga.js`, confirmação 100% embutida) e, enquanto ele roda, consulta o Postgres
   do host por `psql` remoto (`:5433`) a cada ~`-PollMs` (padrão 250 ms):
   `now() - refreshed_at` do evento (relógio do Postgres; o instante da amostra é o do gerador, o
   mesmo do k6).
4. Depois da rajada lê `confirmed` e `pending` do evento no Postgres (fonte de verdade) e grava a linha
   — **só se a repetição for válida**: k6 com código 0, resumo com `disparo_ms`, nenhum usuário sem
   login, nenhuma resposta inesperada/5xx (reserva ou confirma) e toda reserva aceita confirmada
   (`Test-RepeticaoValida`, em `lib-matriz.ps1`). Uma repetição inválida (ex.: `too many clients` no
   host) enviesaria a média/desvio do overselling, então é **pulada**: não entra no CSV, o motivo e a
   contagem aparecem no fim, o resumo do k6 dela fica em `results\matriz-invalida-*.json` (sem
   credenciais, ignorado pelo git) e o script sai com código **2** (matriz incompleta). Repita a matriz
   (ou só o grupo afetado) depois de corrigir a causa.

### Colunas de `results\matriz.csv`

`braco,lag_ms,N,capacity,rep,confirmed,pending,oversell,p50,p95,p99,throughput,lag_obs_disparo,lag_obs_max,commit`

| Coluna | Significado |
|---|---|
| `braco` | `cas` ou `naive` (o `READPATH` rotulado) |
| `lag_ms` | `REFRESH_MS` rotulado (lag configurado no host) |
| `N`, `capacity`, `rep` | clientes concorrentes, vagas do evento, repetição (1..REPETICOES) |
| `confirmed`, `pending` | reservas do evento no Postgres do host após a rajada |
| `oversell` | `max(0, confirmed − capacity)`: **métrica primária**; `pending + confirmed` é derivável (secundária) |
| `p50`, `p95`, `p99` | latência da reserva (`POST /bff/event-wallets`) em ms, vista pelo gerador (descontar a base de rede) |
| `throughput` | tentativas/s (iterações reserva + confirma) **na janela da rajada** (1ª iteração iniciada até a última terminada); exclui os logins do `setup()`, então **não** é o `reservas_por_s` do teste 1 |
| `lag_obs_disparo` | lag observado (ms) na última amostra **antes** do disparo da rajada |
| `lag_obs_max` | maior lag observado (ms) entre essa amostra e as da rajada |
| `commit` | `git rev-parse --short HEAD` do checkout do gerador |

`lag_obs_*` = **-1** significa "sem leitura" (linha do read model inexistente ou nenhuma amostra
válida na janela); o report ignora o -1. A resolução é o intervalo do polling: o instante exato do
disparo vem do k6 (`disparo_ms`/`fim_ms` no resumo, relógio do gerador, depois do `setup()` com os
logins), mas a amostra pode ser até ~`PollMs` anterior ao disparo. A rajada em si é curta (a latência
p95 da reserva é de dezenas de ms; o `duracao_s` do teste 1 inclui os logins do `setup()`), então
na prática não há amostra própria na janela e `lag_obs_max` costuma igualar `lag_obs_disparo`.

O report (`report-matriz.sh`) desenha, por (N, capacity), o overselling × lag (uma curva por braço: média, ±1
desvio amostral e mín–máx das repetições) e a latência p95 × lag, mais a tabela dos agregados.
Auto-testes (sem rede/stack): `bash ../../docs/carga/harness/report-matriz-selftest.sh` e
`.\lib-matriz.selftest.ps1`.

## Teste 1 — disputa de vagas (corretude)

N clientes disputam as vagas de **um** evento, todos de uma vez. Verifica se o CAS impede
overselling (`aceitas <= capacidade`) e compara com o caminho `naive`. Toda reserva aceita (201) é
finalizada na mesma iteração com `POST /bff/event-wallets/confirm`: ao fim, as vagas do evento
ficam `confirmed` (conferir no `verificar-disputa`).

```powershell
# gerador
make test-disputa HOST_IP=<ip> VUS=50,100,200 CAPACIDADE=20
# host
make verificar-disputa EVENT_ID=<id impresso>
```

Resultado: `results\resultados.csv`. Para o braço `naive`, suba o host com
`make setup-host READPATH=naive REFRESH_MS=5000` e use o mesmo `READPATH`/`REFRESH_MS` no gerador
(o gerador não descobre isso sozinho).

Scripts: `carga.js`, `rodar-teste.ps1`.

## Teste 2 — taxa fixa (RPM / saturação)

`RPM` reservas por minuto, constantes, espalhadas em vários eventos. Procura o ponto em que o
sistema satura (erros 5xx, iterações descartadas, p95/p99).

```powershell
# host
make host-rpm MAX_CONN=100        # padrão do Postgres: o teto é o `max_connections`; depois repita com 300
# gerador
make test-rpm HOST_IP=<ip> RPM=2000 DURACAO=60     # aquecimento
make test-rpm HOST_IP=<ip>                         # 10k RPM por 90 s
# host
make verificar-rpm PREFIXO=Rpm-<id impresso>
```

Resultado: `results\resultados-rpm.csv`. Scripts: `rpm/carga-rpm.js`, `rpm/rodar-rpm.ps1`,
`rpm/subir-host-rpm.ps1`, `rpm/docker-compose.rpm.yml`.

Cada reserva aceita é confirmada (`POST /bff/event-wallets/confirm`) com `PCT_CONFIRMA` % de
chance (padrão 100, sorteio determinístico por iteração); as não confirmadas ficam `pending` e
expiram. Ex.: `make test-rpm HOST_IP=<ip> PCT_CONFIRMA=50`. Atalho: `make test-rpm-real` (ver "Versão realista").

Como funciona: cada requisição é um par (usuário, evento) único, então poucos usuários (login e
seed usam bcrypt, caro) geram milhares de reservas sem duplicidade.
`requisições = RPM × DURACAO / 60 <= USUARIOS × eventos` (eventos é calculado, com 5% de folga).

### Por que `MAX_CONN` importa

O backend usa pool HikariCP (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`, default 1000; o pool
cresce só sob demanda a partir de `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE`, default 5). Com
`MAX_CONN=100` (padrão do Postgres) o pool de 1000 pode pedir mais conexões do que o Postgres
aceita → `too many clients` → HTTP 500; para esse caso reduza o pool por env (ex.: 50). Com
`MAX_CONN` >= pool não há esse erro. Compare 100 × 300 para documentar o teto.

## Teste 3 — carga aleatória (estabilidade por tempo)

Durante `TEMPO`, a taxa muda a cada `PASSO` segundos para um alvo sorteado entre `RPM_MIN` e
`RPM_MAX` (rampa de 5 s entre alvos). `PCT_LEITURA` % das iterações são leituras sorteadas
(evento por id, carteira do usuário, busca); o resto são reservas com pares (usuário, evento) únicos,
espalhados entre os eventos. A mesma `SEMENTE` repete o mesmo perfil (`SEMENTE=0` sorteia e imprime).
Cada reserva aceita é confirmada com `PCT_CONFIRMA` % de chance (padrão 100), sorteada de forma
determinística por (`SEMENTE`, par) sem tocar no `Math.random` do perfil: a taxa e a ordem dos
sorteios não mudam com o `PCT_CONFIRMA`.

```powershell
# gerador
make test-aleatorio HOST_IP=<ip>                                   # 10 min, 1k-12k RPM, 30% leituras
make test-aleatorio HOST_IP=<ip> TEMPO=30m RPM_MAX=20000 SEMENTE=42
# host, logo após a rodada
make verificar-rpm PREFIXO=Ale-<id impresso>
```

`TEMPO` aceita `600`, `600s`, `10m`, `1h` e **pode** passar de 110 s: aqui o foco é estabilidade e
saturação ao longo do tempo. Como reservas `pending` expiram e devolvem a vaga, `aceitas` pode passar
da capacidade sem ser overselling; a invariante (ativas <= capacidade) vale no `-Verificar`.

Resultado: `results\resultados-aleatorio.csv` e o perfil sorteado em `results\perfil-ale-<id>.json`.
Scripts: `aleatorio/carga-aleatoria.js`, `aleatorio/rodar-aleatorio.ps1`.

## Versão realista (confirmação aleatória)

Nos testes 1–3 padrão toda reserva aceita é confirmada (`PCT_CONFIRMA=100`). Na versão realista cada
aceita é confirmada ou não **ao acaso** (sorteio determinístico por iteração/semente, como já era nos testes 2 e 3);
as não confirmadas ficam `pending` e expiram sozinhas (~120 s). O teste 1 ganhou `PCT_CONFIRMA`
(padrão 100, comportamento anterior). Os scripts são os mesmos; só muda o percentual.

```powershell
# gerador (PCT_CONFIRMA_REAL=60 por padrão; sobrescreva para outro percentual)
make test-disputa-real    HOST_IP=<ip>
make test-rpm-real        HOST_IP=<ip>
make test-aleatorio-real  HOST_IP=<ip> PCT_CONFIRMA_REAL=40
```

Como ler: `confirmadas < aceitas` é esperado (≈ `aceitas × PCT_CONFIRMA / 100`); o `-Verificar` mostra
`confirmed`, `pending` e `expired` misturados e a invariante continua `(pending + confirmed) <= capacidade`.
As vagas das não confirmadas só voltam após a expiração: rode o `-Verificar` logo após a rodada para ver
`pending`, ou depois de ~120 s para ver `expired`. No teste 3 (dezenas de minutos) as vagas liberadas são
**revendidas**, então `aceitas` pode passar da capacidade sem ser overselling. O rótulo da rodada não muda
(`-Rotulo` precisa bater com o host); distinga as rodadas pela coluna `pct_confirma` do CSV.

## Reserva e confirmação (métricas)

As duas etapas têm métricas k6 separadas (tags `reserva` e `confirma`):

| Etapa | Contadores | Latência (Trend) |
|---|---|---|
| reserva (`POST /bff/event-wallets`) | `reservas_aceitas`, `reservas_lotado`, `reservas_5xx`, `reservas_outros` | `reserva_duration` |
| confirma (`POST /bff/event-wallets/confirm`) | `confirma_aceitas` (200), `confirma_4xx_esperado`, `confirma_5xx`, `confirma_outros` | `confirma_duration` |

`confirma_4xx_esperado` = HTTP 400 "já confirmada" / "nenhuma reserva pendente" (a reserva expirou ou
já estava confirmada): não é erro. Qualquer outra resposta cai em `confirma_outros` (4xx inesperado) ou
`confirma_5xx`. No teste 1 não há contador `reservas_5xx` (5xx da reserva continua em `outros`).

No summary JSON e nos CSVs `resultados*.csv` as colunas novas ficam no **fim**: `confirmadas`,
`confirma_4xx_esperado`, `confirma_5xx`, `confirma_outros`, `confirma_p50_ms`, `confirma_p95_ms`,
`confirma_p99_ms` (e `pct_confirma` antes delas nos testes 2 e 3). Um CSV antigo ganha os nomes no
cabeçalho na próxima rodada; as linhas antigas ficam curtas. `rpm_real`/`rpm_medio`/`reservas_por_s`
seguem contando iterações (reservas), não requisições de confirma; como a iteração agora dura reserva
+ confirma, cada VU fica ocupado mais tempo (atenção a `descartadas`/`vus_max`).

## Seed único

O seed (host + usuários + eventos via `POST /bff/seed`) mora só em `docs/carga/harness/setup.sh`,
o mesmo do harness local. Os testes deste kit o chamam por `lib-seed.ps1` com
`EVENTS`, `EVENT_NAME_PREFIX`, `SEM_TOKENS=1`, `USERS_FILE` e `EVENT_IDS_FILE` (ver o cabeçalho
do script); o k6 faz o login dos usuários no `setup()`. Usuários: `carga-att<i>-<sufixo>@example.com`;
eventos: `Carga-<run>-N<n>-E1` (teste 1) e `Rpm-<run>-E<k>` (teste 2).

## Como ler o resultado

- **`aceitas` + `lotado`** devem somar quase tudo. `erros_5xx` ou `outros` > 0 invalida a rodada.
- **`confirmadas`** deve ser `aceitas × PCT_CONFIRMA / 100`; `confirma_5xx` ou `confirma_outros` > 0
  invalida a rodada. No `-Verificar`, o status `confirmed` aparece na contagem por status e o
  overselling é `(pending + confirmed) - capacidade` (deve ser <= 0).
- **`descartadas` > 0** (teste 2): o k6 não manteve a taxa; gerador ou backend saturou.
- **Overselling** vale só no `-Verificar` (banco). O número do cliente é indicativo.
- Cada `-Verificar` (`verificar-disputa` / `verificar-rpm`) anexa a saída em `Projeto/carga/results/verificacoes.log`, com data/hora, alvo (evento ou prefixo) e config ativa (read path / refresh). Rode logo após a rodada e cite esse arquivo na documentação.
- **Latência:** desconte a base de rede medida no `setup-gerador`.
- No `naive`, o overselling varia com o ponto do ciclo do read model em que a rajada cai:
  repita a rodada e reporte média e dispersão (a matriz faz isso: repetições, rajada em fase
  aleatória do job e `report-matriz`).

## Limites e cuidados

- Mantenha `DURACAO` <= 110 s: reservas `pending` expiram em ~120 s e devolvem a vaga,
  o que falsearia a conta de overselling. Reservas confirmadas não expiram (`expires_at` zera);
  com `PCT_CONFIRMA < 100` as demais continuam sujeitas a essa expiração.
- O seed cria usuários com bcrypt: dezenas de segundos para centenas de usuários.
- O host precisa estar na tomada, no plano "Alto desempenho", sem apps pesados.
- Os scripts criam usuários/eventos de teste no banco (`carga-*@example.com`, `Carga-*`, `Rpm-*`).
  Não rode contra um banco com dados que você quer preservar.

## Arquivos

| Arquivo | Onde roda | Função |
|---|---|---|
| `setup-host.ps1` | host | sobe a stack com o override; firewall 8081 (e a 5433, só com `-GeradorIp`, restrita a esse IP); `-Verificar -EventId N` confere o banco |
| `setup-gerador.ps1` | gerador | instala o k6, confere `psql` e a 5433, mede a latência base |
| `docker-compose.carga.yml` | host | override: read path, lag do read model, log de SQL desligado |
| `rodar-teste.ps1` / `carga.js` | gerador | teste 1 (confirma `PCT_CONFIRMA` % das aceitas, padrão 100) |
| `rodar-matriz.ps1` / `lib-matriz.ps1` | gerador | matriz do experimento: usa o `carga.js` do teste 1; `lib-matriz.ps1` = linha do CSV e lag da rajada (`lib-matriz.selftest.ps1` testa) |
| `../../docs/carga/harness/report-matriz.sh` | gerador | `make report-matriz`: `results\matriz.csv` → `results\matriz.html` (SVG, bash+awk) |
| `lib-seed.ps1` | gerador | `Invoke-Seed`: chama o seed único `docs/carga/harness/setup.sh` via Git Bash |
| `rpm/*` | ambos | teste 2 |
| `aleatorio/*` | gerador | teste 3 |
| `Makefile` | ambos | atalhos |
| `results/` | gerador | CSVs e baselines (gerado) |

Guia manual e tabela de registro do ambiente: `docs/carga/setup.md`.
