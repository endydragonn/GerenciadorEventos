# Testes de carga

Este kit é o **runner oficial do experimento** da Fase 2 (CAS × caminho ingênuo): a
[matriz](#matriz-do-experimento-runner-oficial) (`make test-matriz`) gera o CSV e o gráfico dos
resultados. O harness de `docs/carga/harness/` é só sanity local (stack no próprio host).

> **Desde a issue #33 (Fase 7) a reserva é o serviço `reservation`** (container `gerenciador-reservation`, schema `reservation`), atrás do
> `gateway` (container `gerenciador-backend`). O que mudou para este kit:
>
> - **Alvo HTTP igual**: o k6 continua em `http://<host>:8081/bff/**` (o `reservation` não tem porta no host); o contrato `/bff/**` não mudou.
> - **`READPATH` / `RESERVATION_READPATH`** vai para o **backend** (o gateway decide o fluxo): `cas` = 1 chamada ao `reservation`; `naive` = 2
>   (GET da disponibilidade e depois POST da reserva). A conferência é `docker exec gerenciador-backend printenv RESERVATION_READPATH`.
> - **Relay, consumer, Hikari 1000 e Tomcat 300 do caminho quente estão no `reservation`** (`docker exec gerenciador-reservation printenv
>   EVENTS_RELAY_ENABLED EVENTS_CONSUMER_ENABLED SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`); o backend tem Hikari 350 (10 desde a #36). `POOL_HIKARI` do
>   `make test-degraus` rotula o do **reservation**. O `LOGGING_LEVEL_EVENTS_LAG=DEBUG` também é do `reservation`.
> - **Consultas de banco** (psql do gerador e verificações do host) leem `reservation.eventcap`, `reservation.walletevent`,
>   `reservation.event_availability`, `reservation.event_outbox`. O consumer group da projeção é `reservation` (`docker exec
>   gerenciador-redpanda rpk group describe reservation`), não mais `gerenciador-eventos`.
> - **Limpeza do banco entre blocos** (`E:\TCC\carga-overrides\limpar-banco.sh`, fora do repo): para `backend`, `catalog`, `reservation`
>   (e o `notification`, se no ar), move os groups `reservation`, `reservation-catalog` e `notification` para o fim dos tópicos
>   `reservation.wallet` / `catalog.event` / `identity.notification`, trunca numa única instrução `public`, `reservation` **e `catalog`**
>   (`catalog.event` e `reservation.eventcap` andam juntos; os event stores vão junto) e sobe `reservation` + `catalog` (healthy) antes do
>   `backend`.
> - **Smoke antes de medir**: `docs/testes-manuais/smoke-reservation-servico-separado.sh` (stack no ar) prova overselling 0 no `cas` com o
>   serviço separado e confere o banco lendo o schema `reservation`; `docs/testes-manuais/smoke-catalog-servico-separado.sh` prova o
>   `catalog` separado (#35); ou `make test-matriz-cas HOST_IP=<ip> VUS=50 REPETICOES=2` (N pequeno).
> - **`INTERNAL_API_TOKEN`** precisa estar no `Projeto/.env` do HOST (o compose o repassa ao `backend`, ao `reservation` e ao `catalog`, que
>   não sobem sem ele). O gerador não precisa dele: o k6 só fala `/bff/**`.
> - **Pool do Supavisor não cresce** (mesmo tenant e usuário de todos os serviços); a soma dos Hikari de cliente é
>   1000 + 350 + 3 + 100 (catalog) = 1453 <= 1500 na #35 (1213 desde a #36, abaixo).
>
> **Desde a issue #35 os eventos são o serviço `catalog`** (container `gerenciador-catalog`, `:8083` só na rede, schema `catalog`):
>
> - **Seed só com o override**: `POST /bff/seed` (usado por `lib-seed.ps1` → `docs/carga/harness/setup.sh`) fica desligado (404) no compose
>   normal; o `docker-compose.carga.yml` liga `SEED_ENABLED=true` (o `setup-host.ps1` e o `rpm/subir-host-rpm.ps1` já sobem com ele).
> - **Consultas de banco** pelo nome/prefixo do evento leem `catalog.event` (`public.event` não existe mais; o `rpm/subir-host-rpm.ps1
>   -Verificar` já lê de lá). O lag do relay do catalog é `catalog.event_outbox` (deve ficar vazio) e `rpk group describe reservation-catalog`.
> - **Salto novo**: o gateway confere o evento da reserva por HTTP no `catalog` (`exists`, com cache positivo de 30 s no gateway);
>   ler/listar/buscar evento passa por gateway → catalog → reservation (capacidade). Com o `catalog` fora, `/bff/events/**` e a reserva
>   respondem 503 (na reserva, um positivo de `exists` ainda no cache de 30 s deixa a reserva seguir).
>
> **Desde a issue #36 o `identity` é o serviço `gerenciador-identity`** (`:8084` só na rede, schema `identity`; JWT RS256 com JWKS):
>
> - **Alvo HTTP igual**: o k6 continua em `/bff/**`; `POST /bff/users/login` devolve o mesmo `{user, token}`, agora com um token RS256 emitido pelo identity
>   (o k6 só guarda e reenvia o bearer). Tokens antigos (HS256) viram 401: o kit já re-semeia e reloga a cada bloco.
> - **Reserva sem consulta ao banco por usuário**: o gateway lê `uid` e `isHost` do token (sem `LOWER(email)` por reserva, sem conferir a carteira); a reserva
>   com token válido **não depende do identity** (JWKS em cache no gateway). Uma rodada de carga **antes da #36** pagava essa consulta a mais por reserva e não é
>   comparável com as de depois.
> - **Consultas de banco** sobre usuários leem `identity.users` / `identity.mywallet` (`public.users` não existe mais).
> - **Limpeza do banco entre blocos** (`limpar-banco.sh`): trunca também o schema `identity` e, como o `TRUNCATE ... RESTART IDENTITY` recicla o `user_id` e o JWT é
>   validado sem estado, **apaga a chave de assinatura do volume `identity-keys`** (com o identity parado, ANTES do TRUNCATE; se falhar, aborta sem religar nada), sobe o identity, espera `healthy` e só então religa o `backend`
>   (cache de JWKS vazio). Sem isso um token do bloco anterior valeria como o usuário novo com o mesmo `uid`.
> - **Smoke antes de medir**: `docs/testes-manuais/smoke-identity-servico-separado.sh` (cadastro, token RS256, `/internal/**` com token, reserva `cas` com overselling 0,
>   identity parado e `restart`).
> - **`INTERNAL_API_TOKEN`** também vai para o `identity` (mesmo valor). **Pool do Supavisor** (mesmo tenant): o Hikari do backend é 10 (o gateway não toca em tabela de
>   domínio) e o do identity 100; soma 1000 + 10 + 3 + 100 + 100 = **1213** <= 1500 (o override de carga mantém o backend em 10).

> **Desde a issue #41 há o serviço `admission` (`gerenciador-admission`, `:8085` só na rede) e o Redis (`gerenciador-redis`, só na rede)**: o gateway conta cada `POST /bff/event-wallets` num contador de RPM por
> evento (nos dois braços, depois das validações e antes do `reservation`). Na #41 o modo é sempre `sync` e o RPM é só sinal de carga:
>
> - **Salto novo no caminho síncrono**: +1 ida HTTP gateway → admission e +1 ida admission → Redis por reserva (fail-open: com o admission ou o Redis fora a reserva segue, com log). Uma rodada **antes da #41** não paga
>   esse custo e não é comparável com as de depois sem considerá-lo. Sem `Idempotency-Key` na requisição o gateway gera um UUID e o manda só ao `admission`; o `reservation` recebe a requisição como antes (sem header).
> - **Limpeza do banco entre blocos zera o Redis**: o `TRUNCATE ... RESTART IDENTITY` recicla ids de evento e de usuário, e uma contagem do bloco anterior seria lida
>   como carga do seguinte. O `limpar-banco.sh` (fora do repo, em `E:\TCC\carga-overrides`) para o `admission` junto com os outros serviços, dá `FLUSHDB` no `gerenciador-redis`
>   (e, a partir da #42, purge das filas `admission.*` do `gerenciador-rabbitmq`) e sobe o `admission` (healthy) **antes** do `backend`.
> - **`REDIS_PASSWORD`** precisa estar no `Projeto/.env` do HOST (o compose recusa subir sem ela; repassada ao `redis` e ao `admission`, e sobreposta por vazio no `backend`). O gerador não precisa dela.
> - **Pool do Supavisor não muda**: o `admission` não tem datasource (soma dos Hikari segue 1213 <= 1500).
> - **Conferir o contador** no host: `docker exec gerenciador-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli INFO commandstats'` (`cmdstat_evalsha` cresce com as reservas) e
>   `docker exec gerenciador-admission printenv ADMISSION_BUCKET_MS ADMISSION_WINDOW_MS` (defaults 50/200).

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
make setup-host            # ou: .\setup-host.ps1 [-ReadPath cas|naive]
```

Anote o IP impresso (`HOST_IP`).

**No gerador:**

```powershell
make setup-gerador HOST_IP=<ip>    # ou: .\setup-gerador.ps1 -HostIp <ip>
```

Instala o k6, testa a conexão e salva a latência base de rede em `results\baseline-*.txt`
(o valor a descontar das medições). Registre também em `docs/carga/setup.md`, seção 5.

## Telemetria das máquinas

Cada rodada precisa provar que o **gerador não saturou** e que o **host não sofreu throttling**, com dado e não com
observação do operador. Uma amostra por ~1,5 s (CPU, frequência, RAM livre e, se a máquina expuser, temperatura
ACPI) em CSV com `epoch_ms` UTC, para cruzar com os CSVs do k6 (colunas em `lib-telemetria.ps1`):

- **Gerador: automático.** `rodar-degraus.ps1` grava `results\telemetria-gerador-degraus-<id>.csv` e `rodar-matriz.ps1`
  grava `results\telemetria-gerador-matriz-<data>.csv`; o pior caso sai numa linha ao fim da rodada.
- **Host: ligue você, ANTES de disparar o teste no gerador** (`make telemetria-host ROTULO=<nome>`; Ctrl+C ao fim).
  Gera `results\telemetria-host-<rótulo>-<data>.csv`. Mantenha os relógios das duas máquinas sincronizados (`w32tm /resync`).
- **Temperatura:** muitos desktops não expõem a zona térmica ACPI e a coluna `temp_c` fica vazia. Nesse caso, leia a
  temperatura da CPU com os sensores do RESET EXPLODE (ou HWiNFO) **antes e depois** da rodada, não durante: eles também
  consomem CPU. Em `pct_freq_max` uma queda sustentada sob carga já indica throttling de energia ou térmico.
- Registre o pior caso de cada máquina junto do resultado (substitui o "CPU ≤ 73%" de observação do operador).

## Harness definitivo (issue #38)

O que roda numa rodada contra o sistema distribuído, sempre do **gerador** (k6) contra o **host** (stack):

| Cenário | Comando (gerador) | Mede |
|---|---|---|
| Escrita: reserva + confirma | `make test-degraus HOST_IP=<ip> MAPEAMENTO=sequencial\|intercalado` (escada, limiar X) ou `make test-rpm HOST_IP=<ip>` (taxa fixa) | throughput sustentado (`rpm_efetivo`), p50/p95/p99, 5xx |
| Leitura: catálogo | `make test-rpm HOST_IP=<ip> CENARIO=leitura RPM=<n> DURACAO=<s até 3600>` | o mesmo, para `GET /bff/events/{id}` (gateway → catalog → disponibilidade no reservation), sem login, em rodízio por 50 eventos semeados |

Na escrita cada usuário semeado faz login no `/bff/users/login` antes da carga e reserva com **o próprio token** (o `userId` do corpo é o
do dono do token, #57); o harness nunca assina JWT. A coluna `cenario` do `results\resultados-rpm.csv` separa as duas (linhas antigas sem
valor são escrita); no cenário `leitura` as colunas `aceitas`/`erros_5xx`/`outros`/`p*_ms` são das leituras (200 / 5xx / outro).

**No host**, junto com o `make telemetria-host`, ligue a coleta do banco/broker **antes** de disparar o gerador:

```powershell
make coleta-host ROTULO=<nome>            # Ctrl+C ao fim; COLETA_S=<s> para parar sozinho
```

`coleta-host.sh` (Git Bash; só `docker exec`, nenhum segredo) reseta o `pg_stat_statements` no início (cria a extensão no `meu_banco` se
faltar) e grava, a cada `INTERVALO_S` (1 s), `results\coleta-host-<rótulo>-<data>.csv` com `epoch_ms` UTC (cruza com a telemetria):

- `lag_projecao_ms`: write → projeção, `MAX(applied_at - last_occurred_at)` de `reservation.event_availability` nas linhas aplicadas no
  intervalo (-1 = nada aplicado);
- `lag_reservation`, `lag_reservation_catalog`, `lag_notification`: lag do consumer (`TOTAL-LAG` do `rpk group describe`; -1 = grupo ausente);
- `outbox_reservation`, `outbox_catalog`, `outbox_identity`: pendentes nos `event_outbox` (relay atrasado);
- `pg_conexoes`, `pg_ativas`, `pg_idle`, `pg_idle_tx`, `pg_espera_lock`: o lado servidor do pooler, as conexões que o Supavisor mantém no
  `meu_banco` por estado (`pg_stat_activity`). Não se lê o endpoint de métricas do Supavisor: ele exige um JWT assinado com segredo do
  `supabase/.env`.

Ao fim grava `results\pgss-<rótulo>-<data>.csv` (top 25 do `pg_stat_statements` por tempo total) e imprime max e p95 de cada coluna.
Selftest sem Docker: `bash coleta-host.selftest.sh`.

## Matriz do experimento (runner oficial)

Para **um** braço por vez, roda a disputa de vagas (teste 1, `PCT_CONFIRMA=100`) para
uma lista de N e um número de repetições, e acumula **uma linha por execução** em
`results\matriz.csv`. O braço (`RESERVATION_READPATH`) é lido no startup do backend e não muda em
runtime: o valor passado ao gerador (`READPATH`) só **rotula** a coluna `braco` e precisa bater com o que o
host está rodando. Para varrer os braços, troque a config no host e rode a matriz de novo; as linhas se
acumulam no mesmo CSV.

> **O lag deixou de ser variável controlada (issue #29).** O refresh por timer (`READMODEL_AVAILABILITY_REFRESH_MS`,
> `REFRESH_MS`) saiu: a disponibilidade do braço `naive` é uma projeção **assíncrona** (append → relay →
> Redpanda → consumer), e o lag é o desse pipeline real, **medido sob a carga** (variável independente = a carga:
> N concorrentes / RPM). `-RefreshMs` e `REFRESH_MS` não existem mais nos alvos do `make` (o `rodar-matriz.ps1`
> ainda aceita `-RefreshMs` e o ignora), e a coluna `lag_ms` do CSV vale **sempre 0** ("não controlado"; foi mantida
> só para não mudar o formato). O `report-matriz.sh` ainda agrupa/plota por `lag_ms`: para o eixo "overselling × lag
> medido" ele precisa passar a usar `lag_obs_*` (trabalho da #31).

Host e gerador devem estar **no mesmo commit** (a coluna `commit` é o do checkout do gerador).

```powershell
# 1) host (PowerShell como Administrador). GERADOR_IP é obrigatório para a matriz: cria a regra de
#    firewall da 5433 (Privada) SEMPRE restrita a esse IP; sem ele a 5433 fica fechada.
make setup-host READPATH=naive GERADOR_IP=<ip do gerador>
#    Ao fim do experimento, feche a 5433:  Remove-NetFirewallRule -DisplayName 'TCC postgres 5433'

# 2) gerador, uma vez por sessão: senha do Postgres do host SÓ no ambiente (não ecoa, não vai pro histórico)
$s = Read-Host 'Senha do Postgres do host' -AsSecureString
$env:PGPASSWORD = [System.Net.NetworkCredential]::new('', $s).Password

# 3) gerador: a matriz (READPATH = o mesmo do passo 1)
make test-matriz HOST_IP=<ip do host> VUS=50,100,200 REPETICOES=10 CAPACIDADE=20 READPATH=naive

# 4) outro braço: volte ao passo 1 com o novo valor e repita o passo 3 (ex.: READPATH=cas)

# 5) gerador: gráfico (results\matriz.csv -> results\matriz.html; Git Bash + awk, sem dependência nova)
make report-matriz
```

Sem `make`: `.\rodar-matriz.ps1 -HostIp <ip> -Vus 50,100,200 -Repeticoes 10 -Capacidade 20 -Rotulo naive`
e `bash ../../docs/carga/harness/report-matriz.sh results/matriz.csv results/matriz.html`.
`PGUSER`/`PGDATABASE` (ou `-PgUsuario`/`-PgBanco`) assumem `admin`/`meu_banco` (os do projeto; se o
`Projeto/.env` do host usar outros, informe). A **senha** vem só de
`PGPASSWORD`: nunca como argumento ou variável do `make` (o make ecoa a linha de comando), nunca
impressa, nunca gravada. Instalar o `psql` no gerador: `make setup-gerador` mostra o comando
(`winget install PostgreSQL.PostgreSQL.16 --interactive`, marcando só "Command Line Tools").

Com N alto (≥ ~100) e `max_connections=100` (padrão do Postgres) o pool do backend pode estourar o
limite (ver "Por que `MAX_CONN` importa"; o `VUS` padrão chega a 200): rode o passo 1 uma vez (firewall) e
suba a stack com `make host-rpm MAX_CONN=300 READPATH=...` no lugar do `setup-host` (as
regras de firewall persistem). Repetições afetadas por `too many clients` (5xx) são **descartadas**, não
gravadas (ver abaixo).

### O que cada execução faz

1. **Seed único por N**: `N` usuários + `REPETICOES` eventos novos, todos com a mesma `capacity`
   (um evento novo por repetição). Antes da 1ª repetição de todas, um **aquecimento descartado**
   (rajada pequena, `-VusAquecimento`, evento próprio, sem linha no CSV).
2. Espera o pipeline de eventos **do reservation** ficar **quieto** (`reservation.event_outbox` vazio e o `max(applied_at)` da projeção
   parado entre duas leituras), para o backlog de uma repetição não vazar para a seguinte (a disponibilidade do `naive` é `eventcap LEFT
   JOIN` a projeção: não há mais "linha do read model" a esperar nem fase de timer a sortear). Limite: um consumer
   travado também parece quieto, confira o offset lag.
3. Dispara o k6 (`carga.js`, confirmação 100% embutida) e, enquanto ele roda, consulta o Postgres
   do host por `psql` remoto (`:5433`) a cada ~`-PollMs` (padrão 250 ms):
   `applied_at - last_occurred_at` de `reservation.event_availability` do evento: a idade, no relógio do Postgres, do último
   evento aplicado pelo consumer (do append ao commit do consumer, relay incluído); o instante da amostra é o do
   gerador, o mesmo do k6. **Limite:** é um **limite inferior** do atraso: a amostra só anda quando o consumer aplica um evento (consumer
   atrasado ou parado não a atualiza). A **métrica principal da #31** é o log `events.lag` (ligue na rodada com
   `LOGGING_LEVEL_EVENTS_LAG=DEBUG` no host; vem desligado) **mais** o offset lag. Confira também o **offset lag** do consumer group, no host:
   `docker exec gerenciador-redpanda rpk group describe reservation`. O lag por evento, com relay e consumer
   separados (`relay_ms` / `consumer_ms` / `total_ms`), sai no log `events.lag` do container `gerenciador-reservation`.
4. Depois da rajada lê `confirmed` e `pending` do evento no Postgres (fonte de verdade) e grava a linha
   — **só se a repetição for válida**: k6 com código 0, resumo com `disparo_ms`, nenhum usuário sem
   login, nenhuma resposta inesperada/5xx (reserva ou confirma) e toda reserva aceita confirmada
   (`Test-RepeticaoValida`, em `lib-matriz.ps1`). Uma repetição inválida (ex.: `too many clients` no
   host) enviesaria a média/desvio do overselling, então é **pulada**: não entra no CSV, o motivo e a
   contagem aparecem no fim, o resumo do k6 dela fica em `results\matriz-invalida-*.json` (sem
   credenciais, ignorado pelo git) e o script sai com código **2** (matriz incompleta). Repita a matriz
   (ou só o grupo afetado) depois de corrigir a causa. No braço `cas` há ainda a tolerância 0 e o código
   **3** (ver "Códigos de saída").

### Colunas de `results\matriz.csv`

`braco,lag_ms,N,capacity,rep,confirmed,pending,oversell,p50,p95,p99,throughput,lag_obs_disparo,lag_obs_max,commit`

| Coluna | Significado |
|---|---|
| `braco` | `cas` ou `naive` (o `READPATH` rotulado) |
| `lag_ms` | sempre `0`: o lag não é mais configurado (issue #29), é medido em `lag_obs_*` |
| `N`, `capacity`, `rep` | clientes concorrentes, vagas do evento, repetição (1..REPETICOES) |
| `confirmed`, `pending` | reservas do evento no Postgres do host após a rajada |
| `oversell` | `max(0, confirmed − capacity)`: **métrica primária**; `pending + confirmed` é derivável (secundária) |
| `p50`, `p95`, `p99` | latência da reserva (`POST /bff/event-wallets`) em ms, vista pelo gerador (descontar a base de rede) |
| `throughput` | tentativas/s (iterações reserva + confirma) **na janela da rajada** (1ª iteração iniciada até a última terminada); exclui os logins do `setup()`, então **não** é o `reservas_por_s` do teste 1 |
| `lag_obs_disparo` | lag real da projeção (`applied_at - last_occurred_at`, ms) na última amostra **antes** do disparo da rajada |
| `lag_obs_max` | maior lag observado (ms) entre essa amostra e as da rajada |
| `commit` | `git rev-parse --short HEAD` do checkout do gerador |

`lag_obs_*` = **-1** significa "sem leitura" (a projeção ainda não tem linha do evento ou nenhuma amostra
válida na janela); o report ignora o -1. A resolução é o intervalo do polling: o instante exato do
disparo vem do k6 (`disparo_ms`/`fim_ms` no resumo, relógio do gerador, depois do `setup()` com os
logins), mas a amostra pode ser até ~`PollMs` anterior ao disparo. A rajada em si é curta (a latência
p95 da reserva é de dezenas de ms; o `duracao_s` do teste 1 inclui os logins do `setup()`), então
na prática não há amostra própria na janela e `lag_obs_max` costuma igualar `lag_obs_disparo`.

O report (`report-matriz.sh`) desenha, por (N, capacity), o overselling × lag e a latência p95 × lag, mais a
tabela dos agregados. Eixo x linear em ms (de 0 ao maior lag). Cada braço tem média, ±1 desvio amostral e mín–máx
das repetições por lag; o `naive` é uma curva e o `cas`, que não depende do lag, uma linha de referência
tracejada (média de todas as suas execuções do grupo) com marcadores nos lags em que foi medido.
Auto-testes (sem rede/stack): `bash ../../docs/carga/harness/report-matriz-selftest.sh` e
`.\lib-matriz.selftest.ps1`.

### Códigos de saída do `rodar-matriz.ps1`

| Código | Significado |
|---|---|
| `0` | matriz completa; no braço `cas`, todas as execuções dentro da tolerância 0 |
| `1` | erro de ambiente (pré-requisito faltando, host/Postgres inacessível, resposta inesperada) |
| `2` | houve repetição **inválida** (não gravada): matriz incompleta |
| `3` | **braço `cas` com overselling** (tolerância 0 violada; ver abaixo). Prevalece sobre o `2` se os dois ocorrerem: ambos são listados no fim |

Via `make`, o código real do runner é o `Error N` da linha `make[1]: *** [...test-matriz] Error N` (no preset
`make test-matriz-cas`; a linha seguinte, do make-pai, mostra sempre `Error 1`); em `make test-matriz` direto é o
`Error N` da última linha. O status do próprio `make` é sempre `2` em qualquer falha da receita. Chamando
`.\rodar-matriz.ps1` direto, é o `$LASTEXITCODE`.

**Tolerância 0 no braço `cas`** (`Test-ToleranciaCas`, em `lib-matriz.ps1`): a execução falha se
`confirmed > capacity` (primária) **ou** `pending + confirmed > capacity` (secundária); igual a `capacity` passa.
Isso **não** é validade: uma execução CAS que oversella é um **resultado** (bug do CAS), então a linha entra no
`matriz.csv` normalmente, o runner a imprime em vermelho (`[FALHA CAS]`), lista no fim e sai com `3`. Se a
repetição for, além disso, inválida (ex.: 5xx), a linha não é gravada (regra do `2`), mas o overselling é
sinalizado do mesmo jeito. No braço `naive` não há tolerância: o overselling é o que se mede.

### Rodada 1a — braço CAS (issue #12)

Preset `make test-matriz-cas`: `READPATH=cas`, N ∈ {100, 200}, `capacity` 20, 5 repetições, `PCT_CONFIRMA=100`
(fixo no runner) e `lag_ms` = 0 (o lag não é mais configurado, issue #29; o CAS nem lê a projeção). Gera **10 linhas**.

```powershell
# 1) host (PowerShell como Administrador). Uma vez, para a regra de firewall da 5433 restrita ao gerador:
make setup-host READPATH=cas GERADOR_IP=<ip do gerador>
#    N=100/200 pode estourar o max_connections=100 do Postgres (repetições com 5xx seriam descartadas e o CSV
#    ficaria com menos de 10 linhas): suba de novo com max_connections maior (as regras de firewall persistem)
make host-rpm MAX_CONN=300 READPATH=cas

# 2) gerador: senha do Postgres do host SÓ no ambiente
$s = Read-Host 'Senha do Postgres do host' -AsSecureString
$env:PGPASSWORD = [System.Net.NetworkCredential]::new('', $s).Password

# 3) gerador: comece de um results\matriz.csv vazio/inexistente (as linhas se acumulam); rode o preset
make test-matriz-cas HOST_IP=<ip do host>

# 4) gerador: gráfico (results\matriz.html)
make report-matriz
```

Versione o `Projeto/carga/results/matriz.csv` resultante (as 10 linhas); ele entra no gráfico como a linha de
referência "design proposto" (a rodada 1b, braço `naive`, acrescenta linhas ao mesmo arquivo). **Esperado: `oversell = 0`
em todas as linhas** e o `make` terminando sem erro. Qualquer valor diferente (ou `Error 3` na linha `make[1]`,
exit `3` do script) é um bug do CAS, não ruído de medição: investigue antes de seguir. `Error 2` na linha
`make[1]` = repetição inválida: a rodada ficou com menos de 10 linhas; corrija o host e repita.

### Rodada 1b — braço ingênuo (issue #13; refeita na Rodada 2, issue #31)

Preset `make test-matriz-naive`: `READPATH=naive`, N ∈ {100, 200}, `capacity` 20, 5 repetições, `PCT_CONFIRMA=100`
(fixo no runner). Desde a issue #29 **não há mais um lag por chamada** (nem `REFRESH_MS`, nem os 4 lags 500/2000/5000/10000
da Fase 2): o lag é o do consumer real e sai medido em `lag_obs_*`. Gera **10 linhas**, que se acumulam no **mesmo**
`results\matriz.csv` das 10 do CAS (**não apague o CSV**). As linhas da Fase 2 (com `lag_ms` > 0) são de outro
experimento: não misture no mesmo CSV; guarde o antigo em `results/` com outro nome.

```powershell
# host (PowerShell como Administrador): reinicia a stack com o braço ingênuo e max_connections=300 (N=200 estoura o
# padrão 100). O compose já liga o relay e o consumer de eventos (EVENTS_RELAY_ENABLED / EVENTS_CONSUMER_ENABLED).
make host-rpm MAX_CONN=300 READPATH=naive
#    conferência: docker exec gerenciador-backend printenv RESERVATION_READPATH
#                 docker exec gerenciador-reservation printenv EVENTS_RELAY_ENABLED EVENTS_CONSUMER_ENABLED

# gerador (PGPASSWORD só no ambiente, como na 1a)
make test-matriz-naive HOST_IP=<ip do host>
make report-matriz
```

Não use `make setup-host` para trocar de braço entre as chamadas: ele recria a stack sem o override do `host-rpm` e o
`max_connections` pode voltar ao padrão (100), o que derruba repetições do N=200 (`too many clients`).

- **Esperado:** cada (ingênuo, N) com 5 linhas no CSV e o `make` sem erro. No ingênuo não há tolerância: o
  overselling é o que se mede, então `oversell > 0` **não** é falha. `Error 2` na linha `make[1]` = repetição
  inválida (5xx etc.): a rodada ficou com menos de 10 linhas. Se a chamada for interrompida no meio por qualquer motivo
  (`Error 1`, `Error 2`, Ctrl+C), **apague do `matriz.csv` as linhas dessa rodada** (`naive`) antes de repetir; senão as
  repetições já gravadas se duplicam e distorcem média e desvio.
- **Versione** o `Projeto/carga/results/matriz.csv` final (20 linhas: 10 CAS + 10 ingênuo); o gráfico se gera a
  partir dele.
- **Resultado como medido.** Se o overselling não acompanhar o lag medido (platô, queda, ruído), isso é o achado:
  registre como saiu, sem repetir rodadas para "achar" a tendência esperada.
- **Gráfico:** o `report-matriz.sh` ainda monta o eixo x por `lag_ms` (agora sempre 0): para plotar overselling ×
  lag **medido** ele precisa passar a usar `lag_obs_*` (ajuste da #31). O lag observado no disparo (`lag_obs_disparo`)
  já vai em cada linha do CSV e, em média, na tabela do report.

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
`make setup-host READPATH=naive` e use o mesmo `READPATH` no gerador
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

O caminho quente (o serviço `reservation`) usa pool HikariCP (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`, default 1000; o backend/gateway usa 10 desde a #36 (350 até a #35); o pool
cresce só sob demanda a partir de `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE`, default 5). Com
`MAX_CONN=100` (padrão do Postgres) o pool de 1000 pode pedir mais conexões do que o Postgres
aceita → `too many clients` → HTTP 500; para esse caso reduza o pool por env (ex.: 50). Com
`MAX_CONN` >= pool não há esse erro. Compare 100 × 300 para documentar o teto.

**Desde a issue #19 o backend fala com o Postgres pelo Supavisor** (transaction mode), então quem
chega ao Postgres não é mais o pool Hikari (1000) e sim o pool do Supavisor
(`POOLER_DEFAULT_POOL_SIZE`, literal em `Projeto/supabase/docker-compose.pooler.yml`, 45 para o `max_connections=100`
padrão). O Hikari só enfileira no pooler, e o `too many clients` do parágrafo acima só ocorre se
esse pool passar do `max_connections`. O `make host-rpm MAX_CONN=<n>` acompanha: o
override `rpm/docker-compose.rpm.yml` põe o pool do Supavisor em `PG_POOLER_POOL_SIZE` (o
`subir-host-rpm.ps1` exporta `MAX_CONN - 55`; 245 para 300), e ao voltar ao compose padrão o pooler
reaplica 45. Registre o pool do pooler junto de `MAX_CONN`/`POOL_HIKARI` nas rodadas.

**Mudança de condição do experimento (atenção ao comparar rodadas).** Sem pooler (antes da #19), o
Hikari ia direto ao Postgres e podia abrir até ~`MAX_CONN` conexões reais (a role `admin` era superuser e usava também as 3
reservadas: ~100 e ~300). Com o pooler, o teto real passou a ser o pool do Supavisor, `MAX_CONN − 55` (45 para
100, 245 para 300); a folga de 55 cobre o próprio Supavisor, as reservadas e a suíte de testes.
Por isso rodadas pré-#19 e pós-#19 com o mesmo `MAX_CONN` **não são comparáveis 1:1**: parte da
diferença vem do teto de conexões, não só do pooler. Os CSVs gravam só `MAX_CONN`/`POOL_HIKARI`;
o pool do pooler é derivável (`MAX_CONN − 55`) apenas quando o host sobe pelo `subir-host-rpm.ps1`
(`make host-rpm`); no `make setup-host` (sem override rpm) ele é 45.

## Limiar X preliminar (issue #14)

Escada de carga que estende o teste 2: degraus de taxa fixa (12k, 16k, 20k, … RPM) numa **única** execução do k6
(seed e login uma vez só), um cenário `constant-arrival-rate` por degrau. O limiar X (`CONTEXT.md`) é o **primeiro
degrau** com **5xx > 1% das reservas** (5xx das reservas / reservas do degrau; os 5xx da confirmação não contam) ou
**p95 da reserva > 1 s sustentado por 30 s** (alguma janela de 30 s do degrau com p95 > 1 s; os dois limites são estritos). Os números são de **monólito + HikariCP** (pool e `max_connections` registrados no
CSV); os de antes do pool (#96) estão invalidados (ver `results/02-hikari/ANALISE.md`).

```powershell
# 1) host: stack com o max_connections e o pool HikariCP escolhidos (os dois vão para o CSV)
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '1000'      # opcional (padrão do backend: 1000)
make host-rpm MAX_CONN=300 READPATH=cas
#    conferência: docker exec gerenciador-reservation printenv SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
#                 docker exec gerenciador-backend printenv RESERVATION_READPATH

# 2) gerador: a escada (MAX_CONN / POOL_HIKARI = o que o host roda; só rotulam o CSV)
make test-degraus HOST_IP=<ip do host> MAX_CONN=300 POOL_HIKARI=1000

# 3) gerador: gráfico + degrau X (results\degraus-<id>.csv -> results\degraus-<id>.html; Git Bash + awk)
make report-degraus CSV=results\degraus-<id>.csv

# 4) host: a verdade do banco (prefixo Deg-<id> impresso no passo 2)
make verificar-rpm PREFIXO=Deg-<id>
```

Sem `make`: `.\degraus\rodar-degraus.ps1 -HostIp <ip> -MaxConnections 300 -PoolHikari 1000` e
`bash ../../docs/carga/harness/report-degraus.sh results/degraus-<id>.csv results/degraus-<id>.html`.
Parâmetros: `DEG_INICIO=12000`, `DEG_PASSO=4000`, `DEG_MAX=40000` (degraus de INICIO a MAX, no máximo 40),
`DEG_DURACAO=60` (por degrau: ≥ 60 s e múltiplo de 30), `USUARIOS=200`, `MAX_VUS=2000`, `PCT_CONFIRMA=100`,
`MAPEAMENTO=sequencial` (ou `intercalado`; ver "Mapeamento" abaixo; sem `make`: `-Mapeamento`).
A escada **vai até o máximo**, mesmo depois de o host degradar: ajuste `DEG_MAX` perto do esperado.

**Versione** `Projeto/carga/results/degraus-<id>.csv` (uma linha por degrau) e o `summary-degraus-<id>.json` da
escada; anote o X preliminar (refinado na Fase 8, issue #40) e se o report o marcou como **limite inferior**. O `.html`
regenera do CSV e fica fora do git.

**Colunas do CSV:** `escada,rpm_alvo,rpm_efetivo,duracao_s,reservas,erros_5xx,taxa_5xx,erros_5xx_confirma,outros,p50,p95,p99,p95_max_janela_30s,throughput,iteracoes_descartadas,braco,max_connections,pool_hikari,commit,mapeamento`

| Coluna | Significado |
|---|---|
| `escada` | prefixo dos eventos (`Deg-<id>`), o mesmo do `make verificar-rpm` |
| `rpm_alvo`, `rpm_efetivo` | taxa configurada e reservas enviadas / duração × 60 (cai abaixo do alvo se o k6 descartou iterações) |
| `reservas`, `erros_5xx`, `taxa_5xx` | reservas respondidas no degrau; 5xx **das reservas**; `taxa_5xx` = `erros_5xx / reservas` como **fração** (0,01 = 1%). Só a reserva: o X decide quando a reserva vai para a fila, e as confirmações diluiriam a taxa |
| `erros_5xx_confirma` | 5xx da confirmação: só informativo, **não** entra em `taxa_5xx` nem em X (o runner avisa se houver) |
| `outros` | respostas inesperadas da reserva (4xx fora do "lotado", timeout, conexão recusada): **não** entram em X, mas o report avisa |
| `p50`, `p95`, `p99` | latência da reserva no degrau inteiro (ms), do k6 (descontar a base de rede) |
| `p95_max_janela_30s` | maior p95 da reserva entre as janelas de 30 s do degrau (ms); `-1` = sem janela |
| `throughput` | reservas/s respondidas sem 5xx (`(reservas − 5xx da reserva) / duração`) |
| `iteracoes_descartadas` | `dropped_iterations` do k6 no degrau |
| `braco`, `max_connections`, `pool_hikari`, `commit` | o que o host roda (rotulado) e o commit do checkout do gerador |
| `mapeamento` | `sequencial` ou `intercalado` (última coluna): como a iteração virou o par (usuário, evento). O report lê por nome e a mostra no subtítulo; CSV antigo, sem a coluna, continua valendo e aparece como `mapeamento n/d` (não se presume `sequencial`) |

**Regra do X** (só em `report-degraus.sh`; o runner apenas grava as colunas; `report-degraus-selftest.sh` cobre as
fixtures): *degradou* = `taxa_5xx > 1%` (5xx das reservas / reservas) ou `p95_max_janela_30s > 1000 ms`; *gerador saturado* = `iteracoes_descartadas > 0`
ou `rpm_efetivo < 95%` do alvo (tolerância fixa de 5%). X = primeiro degrau que degradou. Se o gerador saturou
**estritamente antes** (ou nenhum degrau degradou e algum saturou), X vira **limite inferior**: X > último degrau sem
saturação. Saturou já no 1º degrau: sem limite inferior. Degradou e saturou no **mesmo** degrau: X firme, com aviso
(as descartadas podem vir do próprio host lento, que esgota os VUs; o 5xx é sinal do host). Nenhum degradou nem
saturou: "X acima do último degrau". O report desenha latência (p50, p95, p99, pior janela), throughput × carga
oferecida e taxa de 5xx das reservas × RPM alvo (eixo x linear, painéis separados) com a marca do X, e a tabela de todos
os degraus. CSV no formato antigo (coluna `requisicoes`, com a taxa somando as confirmações) é recusado pelo report:
gere a escada de novo.

**A conta do seed** (`Get-PlanoEscada` em `degraus/lib-degraus.ps1` = `carga-degraus.js`): o k6 reinicia
`iterationInTest` a cada cenário, então cada degrau usa uma faixa própria de pares (usuário, evento), de tamanho
`ceil(RPM × duração / 60) + 1` (o `+ 1` é a iteração extra que o k6 pode emitir). O total (soma das cotas) tem de caber
em `USUARIOS × eventos`, com `eventos = ceil(total × 1,05 / USUARIOS)`; o k6 aborta no `setup()` se não couber.
Padrão (12k…40k de 4k em 4k, 60 s, 200 usuários): 8 degraus, 208.008 pares, 1.093 eventos, ~11,6 min de k6 (31 s
ociosos entre degraus: 30 s de `gracefulStop`, para as iterações em voo terminarem antes do próximo, mais 1 s de folga
para os planos de VUs dos cenários não se tocarem).

**Mapeamento (`MAPEAMENTO`, issue #113):** como o índice `i` da iteração vira o par (usuário, evento), com `U` usuários e
`E` eventos. A lógica mora em `degraus/lib-mapeamento.js` (módulo puro, importado pelo `carga-degraus.js`; o k6 resolve o
`import` relativo ao script).

| Valor | Par | Efeito | Use para |
|---|---|---|---|
| `sequencial` (padrão) | usuário `i % U`, evento `⌊i / U⌋` | `U` reservas seguidas caem no **mesmo** evento: uma linha quente de cada vez no `eventcap` | **evento disputado**: onde o CAS degrada quando muita gente vai ao mesmo evento |
| `intercalado` | usuário `⌊i / E⌋`, evento `i % E` | `E` reservas seguidas caem em eventos **diferentes**: a carga se espalha pelos eventos | **capacidade do host**: o X sem a linha quente de um evento |

Nos dois modos cada par (usuário, evento) é único dentro da escada e a checagem `USUARIOS × eventos ≥ soma das cotas` vale
igual (o `setup()` aborta se não couber). O padrão `sequencial` gera exatamente os mesmos pares e requisições de antes
da opção. Valor diferente dos dois aborta o `setup()` com mensagem clara antes do login; maiúscula (`Intercalado`) também é recusada
pelo k6 quando a env `MAPEAMENTO` é setada à mão. O `rodar-degraus.ps1` aceita qualquer caixa no `-Mapeamento` (`ValidateSet`)
e normaliza para minúsculas antes de repassar ao k6 e ao CSV.

**Capacidade por evento no mapeamento (`-Capacidade 0`, o padrão):** `sequencial` mantém `USUARIOS / 2` (como o teste 2: cada
evento recebe `U` reservas seguidas e metade vira "lotado", de propósito). No `intercalado` o evento recebe a *u*-ésima
reserva na "rodada" *u*, então `USUARIOS / 2` lotaria **todos os eventos juntos** por volta do meio da escada e a segunda
metade viraria 100% "lotado" (X inflado). Por isso o padrão do `intercalado` é `USUARIOS`: cada evento aceita no máximo `U`
reservas (uma por usuário), todas aceitas na escada inteira. O runner imprime a capacidade usada (`Pares: ... (capacidade
N/evento)`) e avisa se um `-Capacidade` explícito ficar abaixo de `USUARIOS` no `intercalado`
(`Get-CapacidadePadrao` em `degraus/lib-degraus.ps1`, coberta pelo `lib-degraus.selftest.ps1`). Para comparar com CSVs antigos, lembre que os históricos não trazem a
coluna: confira o `commit`/o script usado antes de presumir o mapeamento.

**VUs:** os `MAX_VUS` (2000) VUs são **todos pré-alocados** (`preAllocatedVUs = maxVUs`). No `constant-arrival-rate` o
k6 descarta a iteração quando não há VU livre e só aloca VU extra depois, em segundo plano (o que pesa no gerador e o
report leria como "gerador saturado"). Com tudo pré-alocado só há descarte quando uma iteração (reserva + confirma)
dura mais que `MAX_VUS / (RPM/60)` s: 3 s no degrau de 40k (667 it/s), isto é, quando o host já degradou (com p95 de
1 s por requisição são ≈ 1.300 VUs). Cada VU custa ~1–2 MB (estimativa; 2000 VUs ≈ 2–4 GB, alocados antes do 1º
degrau): dimensione pela RAM do gerador e, se baixar `MAX_VUS`, baixe `DEG_MAX` junto (o runner avisa se `MAX_VUS` cobre
menos de 2,5 s no maior degrau).

**p95 sustentado por 30 s:** o k6 tagueia cada reserva com `janela:<degrau>_<w>` pelo instante de **envio** (`w =
floor((envio − exec.scenario.startTime) / 30 s)`: janelas fixas alinhadas ao início do degrau, só as `duração / 30`
inteiras) e o `handleSummary` emite o p95 de cada janela (submétrica `reserva_duration{janela:…}`, declarada em
`thresholds`); `p95_max_janela_30s` é o maior. Pelo envio, não pelo término: a reserva lenta enviada no fim do degrau
conta na janela em que foi enviada, sem retardatária de fora. Até 1 s de jitter de relógio nas pontas vai para a janela
da ponta. Sem série temporal em disco nem `--out csv`.

**Trava do resumo:** o resumo do k6 traz as métricas por degrau (submétricas por tag `scenario`); o runner só grava o CSV se
cada contador global (5xx, descartadas, reservas…) **fechar** com a soma dos degraus **e** no máximo 1% das reservas de
cada degrau ficar fora das janelas de 30 s. Se não fechar ("Resumo do k6 inconsistente"), 5xx/descartadas ou o p95 por
janela poderiam sair errados: o CSV não é gerado e o resumo fica em `results\` para diagnóstico. Até 1% fora (atraso de
agendamento com o gerador saturado no fim do degrau) **não** recusa: a contagem vai em `fora_das_janelas` por degrau no
`summary-degraus-<id>.json` e o runner avisa; essas reservas contam no p50/p95/p99 do degrau, mas não no p95 por janela
(sem coluna nova no CSV). Mais de 1% é sistemático (ex.: `exec.scenario.startTime` com outra semântica). Submétrica sem amostras
(ex.: nenhuma confirma com `PCT_CONFIRMA=0`, ou degrau em que toda reserva deu 5xx) vale 0 e só é informada. Código de
saída do k6 ≠ 0 também impede o CSV; os thresholds do script são só declarativos (sempre verdadeiros, só materializam
as submétricas), então nenhum deveria reprovar, e o código 99 (thresholds cruzados) só avisa.

Limites: os degraus são contíguos (só os 31 s ociosos; sem resfriamento do host entre eles, então a degradação de um
degrau pode contaminar o seguinte); um degrau cujo host trava de vez gera timeouts (`outros`), que não contam como
5xx. O limite de 110 s do teste 2 não vale aqui: ele protege a conta de overselling contra reservas `pending` que
expiram, e a escada não mede overselling (e com `PCT_CONFIRMA=100` nada fica `pending`). Auto-testes (sem rede/stack):
`.\degraus\lib-degraus.selftest.ps1`, `node degraus/lib-mapeamento.selftest.mjs` (mapeamento: pares exatos e únicos nos
dois modos; exige Node >= 22.7, que detecta ESM em `.js` sem `package.json`) e `bash ../../docs/carga/harness/report-degraus-selftest.sh`.

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

Leitura por endpoint: o `summary-<tag>.json` e a última linha do stdout trazem p50/p95/p99 de
`leitura_evento_*`, `leitura_carteira_*` e `leitura_busca_*` (o `leitura_*` agregado continua). Se
`leitura_busca_p95_ms` ficar muito acima de evento/carteira, confirma a suspeita de varredura em
memória em `/bff/events/search`. Não entram no CSV.

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
- Cada `-Verificar` (`verificar-disputa` / `verificar-rpm`) anexa a saída em `Projeto/carga/results/verificacoes.log`, com data/hora, alvo (evento ou prefixo) e config ativa (read path / relay / consumer). Rode logo após a rodada e cite esse arquivo na documentação.
- **Latência:** desconte a base de rede medida no `setup-gerador`.
- No `naive`, o overselling varia com o lag real do consumer sob a carga: repita a rodada e reporte média e
  dispersão (a matriz faz isso: repetições e `report-matriz`), junto do `lag_obs_*`.

## Limites e cuidados

- Mantenha `DURACAO` <= 110 s: reservas `pending` expiram em ~120 s e devolvem a vaga,
  o que falsearia a conta de overselling. Reservas confirmadas não expiram (`expires_at` zera);
  com `PCT_CONFIRMA < 100` as demais continuam sujeitas a essa expiração.
- O seed cria usuários com bcrypt: dezenas de segundos para centenas de usuários.
- O host precisa estar na tomada, no plano "Alto desempenho", sem apps pesados.
- Os scripts criam usuários/eventos de teste no banco (`carga-*@example.com`, `Carga-*`, `Rpm-*`).
  Não rode contra um banco com dados que você quer preservar.

## Resultados (`results/`)

Regra: **resultados em `results/NN-etapa/`, cada pasta com um `README.md` sintético do que estava implementado
naquela etapa; a raiz de `results/` é a área de trabalho da rodada corrente.** O índice das etapas está em
[`results/README.md`](results/README.md).

- **Raiz = rodada corrente.** Os scripts gravam em `results/` fixo: `matriz.csv`, `degraus-<id>.csv`,
  `summary-*.json`, `resultados*.csv`, `verificacoes.log` e `baseline-*.txt`. Uma matriz nova começa de um
  `results/matriz.csv` inexistente (as linhas se acumulam).
- **Ao fechar a etapa**, mova os arquivos dela para `results/NN-etapa/` com `git mv`, escreva o `README.md` da
  pasta (o que estava implementado, período, arquivos) e ajuste as citações em `docs/`: o que é **protocolo**
  (o runner grava `results/matriz.csv`) continua apontando para a raiz; o que é **fonte dos números** aponta para a etapa.
- **Nunca mova `users-*.json` nem `events-*.json`**: têm credenciais de teste. O `.gitignore` os ignora na raiz e nas
  subpastas (`Projeto/carga/results/**/users-*.json`, `events-*.json`), mas não os coloque lá de propósito.
- **Gráfico de uma etapa.** `make report-matriz` lê só o `results/matriz.csv` da raiz. Para uma etapa, rode o report
  sobre o CSV dela (Git Bash, a partir de `Projeto/carga`):

  ```bash
  bash ../../docs/carga/harness/report-matriz.sh results/03-supabase-pooler/matriz.csv results/03-supabase-pooler/matriz.html
  ```

  A escada é análoga, com `report-degraus.sh results/02-hikari/degraus-<id>.csv results/02-hikari/degraus-<id>.html`.
  O `.gitignore` ignora esses HTMLs dentro das etapas (`results/**/matriz.html`, `results/**/degraus-*.html`);
  só o CSV é versionado.

## Arquivos

| Arquivo | Onde roda | Função |
|---|---|---|
| `setup-host.ps1` | host | sobe a stack com o override; firewall 8081 (e a 5433, só com `-GeradorIp`, restrita a esse IP); `-Verificar -EventId N` confere o banco |
| `setup-gerador.ps1` | gerador | instala o k6, confere `psql` e a 5433, mede a latência base |
| `docker-compose.carga.yml` | host | override: read path, log de SQL desligado |
| `rodar-teste.ps1` / `carga.js` | gerador | teste 1 (confirma `PCT_CONFIRMA` % das aceitas, padrão 100) |
| `rodar-matriz.ps1` / `lib-matriz.ps1` | gerador | matriz do experimento: usa o `carga.js` do teste 1; `lib-matriz.ps1` = linha do CSV, lag da rajada e tolerância 0 do braço CAS (`lib-matriz.selftest.ps1` testa) |
| `../../docs/carga/harness/report-matriz.sh` | gerador | `make report-matriz`: `results\matriz.csv` → `results\matriz.html` (SVG, bash+awk) |
| `lib-seed.ps1` | gerador | `Invoke-Seed`: chama o seed único `docs/carga/harness/setup.sh` via Git Bash |
| `rpm/*` | ambos | teste 2 |
| `degraus/*` | gerador | escada de carga (limiar X preliminar): `carga-degraus.js`, `rodar-degraus.ps1`, `lib-degraus.ps1` (plano, p95 em janelas de 30 s, linha do CSV; `lib-degraus.selftest.ps1` testa), `lib-mapeamento.js` (mapeamento iteração → par usuário/evento, `sequencial`/`intercalado`; `lib-mapeamento.selftest.mjs` testa com `node`) |
| `../../docs/carga/harness/report-degraus.sh` | gerador | `make report-degraus`: `results\degraus-<id>.csv` → curva × carga + degrau X (SVG, bash+awk; `report-degraus-selftest.sh` testa) |
| `aleatorio/*` | gerador | teste 3 |
| `Makefile` | ambos | atalhos |
| `results/` | gerador | resultados: uma pasta por etapa (`NN-etapa/`) e, na raiz, a rodada corrente (ver "Resultados") |

Guia manual e tabela de registro do ambiente: `docs/carga/setup.md`.
