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
> - **Consultas de banco** (psql do gerador e verificações do host) leem `stock.eventcap` (desde a #47; o `eventcap` e do serviço `stock`), `reservation.walletevent`,
>   `reservation.event_availability`, `reservation.event_outbox`. O consumer group da projeção é `reservation` (`docker exec
>   gerenciador-redpanda rpk group describe reservation`), não mais `gerenciador-eventos`.
> - **Limpeza do banco entre blocos** (`limpar-banco.sh`, versionado em `Projeto/carga/` desde a #178; `make limpar-banco`): para `backend`, `catalog`, `reservation`
>   (o `stock`, #47, e o `notification`, se no ar), move os groups `reservation` e `stock` (`reservation.wallet`), `stock-catalog` (`catalog.event`) e
>   `notification` para o fim dos tópicos, trunca numa única instrução `public`, `reservation`, `stock` **e `catalog`**
>   (`catalog.event` e `stock.eventcap` andam juntos; os event stores vão junto) e sobe o `stock` (healthy) antes de `reservation` + `catalog`, e estes antes do
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
>   como carga do seguinte. O `limpar-banco.sh` (`Projeto/carga/limpar-banco.sh`) para o `admission` junto com os outros serviços, dá `FLUSHDB` no `gerenciador-redis`
>   (e, a partir da #42, purge das filas `admission.*` do `gerenciador-rabbitmq`) e sobe o `admission` (healthy) **antes** do `backend`.
> - **`REDIS_PASSWORD`** precisa estar no `Projeto/.env` do HOST (o compose recusa subir sem ela; repassada ao `redis` e ao `admission`, e sobreposta por vazio no `backend`). O gerador não precisa dela.
> - **Desde a #42, `RABBITMQ_USER` e `RABBITMQ_PASSWORD`** também precisam estar no `Projeto/.env` do HOST (o compose recusa subir sem elas; repassadas ao `rabbitmq` e ao `admission`, sobrepostas por vazio no `backend`).
>   O `gerenciador-rabbitmq` não tem volume (tmpfs): reiniciá-lo zera as filas. **A limpeza entre blocos faz purge das filas `admission.requests` e `admission.timed_out`** (com o `admission` parado, junto do `FLUSHDB`), senão mensagens
>   e `queued` do bloco anterior seriam processados no seguinte; desde a #43 o `admission` enfileira (modo fila, abaixo), então o purge é necessário. Sem o management, o purge manual é
>   `docker exec gerenciador-rabbitmq rabbitmqctl purge_queue admission.requests` (idem `admission.timed_out`), ou reiniciar o container (tmpfs).
> - **Pool do Supavisor não muda**: o `admission` não tem datasource (soma dos Hikari segue 1213 <= 1500).
> - **Conferir o contador** no host: `docker exec gerenciador-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli INFO commandstats'` (`cmdstat_evalsha` cresce com as reservas) e
>   `docker exec gerenciador-admission printenv ADMISSION_BUCKET_MS ADMISSION_WINDOW_MS` (defaults 50/200).
> - **Desde a #43 o modo fila liga sozinho acima de 80% de X por evento** (`uso = RPM do evento ÷ (X × réplicas)`; defaults `ADMISSION_X_RPM=56000` e `ADMISSION_RESERVATION_REPLICAS=1`, ou seja 44,8k RPM no evento;
>   volta ao síncrono só com uso < 70% e a espera do evento vazia). Acima do limiar o `POST /bff/event-wallets` responde **`202`** (corpo `queued`, `Idempotency-Key`, `Retry-After: 1`) em vez de 201 e o resultado vem pelo
>   `GET /bff/event-wallets/admission?idempotencyKey=`: o harness da Rodada 4 (#179) precisa tratar 202 como "aceito" e consultar o estado, e **os degraus acima do limiar já não medem o caminho síncrono**. A janela do RPM é de
>   **200 ms** (`ADMISSION_WINDOW_MS`, bucket de 50 ms): a rajada por evento da escada sequencial dura ~0,2 s, e uma janela maior nunca dispararia. Envs do gatilho no `admission` (todas com default no compose):
>   `ADMISSION_X_RPM`, `ADMISSION_RESERVATION_REPLICAS`, `ADMISSION_QUEUE_ON` (0,80), `ADMISSION_SYNC_BELOW` (0,70); worker e fila: `ADMISSION_WORKER_CONCURRENCY` (32), `ADMISSION_QUEUE_TTL_MS` (30000), `ADMISSION_QUEUE_MAX_LENGTH`.
>   Conferir o modo de um evento: `docker exec gerenciador-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli EXISTS adm:mode:<eventId>'` (1 = em fila) e a profundidade da fila com
>   `docker exec gerenciador-rabbitmq rabbitmqctl list_queues name messages`. O dry-run local acima do limiar (não é medição) é o passo 4b do `Projeto/e2e/run-e2e.sh` com `E2E_RAJADA_USUARIOS` alto.

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
| Escrita acima do X (Rodada 4, #178) | `make test-degraus HOST_IP=<ip> PCT_ACOMPANHA=<n> PARADA_P95_MS=<ms> ARRANJO=...` | o mesmo, mais o modo fila (202, `rejected`, `timed_out`, tempo até admitir, ponta a ponta); ver a seção "Rodada 4" |
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
`TIMEOUT_S` (5) limita cada coleta lenta (`rabbitmqctl`, Redis e `docker ps`): estourou = -1 nas colunas da amostra, o laço não congela. No dry-run local (stack ociosa) a amostra levou ~2,1 s em média (máx. 2,4 s): os 3 `rpk group describe` seguidos e o `psql` dominam, não o `rabbitmqctl` (~0,9 s); o intervalo real é o da amostra. Selftest sem Docker: `bash coleta-host.selftest.sh`.

## Rodada 4: escada acima do X, admission e autoescala (issue #178)

A Rodada 4 (#179) mede o sistema **acima do X** (56k RPM por evento) com o modo de alta carga (#41-#43) e com a autoescala (#111/#112). Este kit
estende a escada (`degraus/`), a coleta do host e a limpeza entre blocos; **nada em `/bff/**` muda**. Os selftests rodam sem Docker: `make selftests`.

> **Ordem obrigatória dos braços: sem autoescala ANTES do braço C.** O override `docker-compose.autoescala.yml` tem o one-shot `particoes`, que leva
> `identity.notification` e `reservation.wallet` a **3 partições, para sempre** (irreversível na stack real). Rode o braço sem autoescala (tópicos de 1
> partição, a linha de base) e só depois suba o braço C; voltar ao compose base não devolve a 1 partição.

### Braços e como subir o host

| Braço | Host (`-f` do `up`) | Notas |
|---|---|---|
| Sem autoescala | `make host-rpm` / `make setup-host` (base + `carga/docker-compose.carga.yml` [+ `rpm/docker-compose.rpm.yml`]) | `Hikari` do `reservation` = **1000**. Rótulo: `ARRANJO=sem-autoescala` |
| Com autoescala (braço C) | `make host-rpm COMPOSE_EXTRAS=docker-compose.autoescala.yml` | `Hikari` do `reservation` = **300 por réplica**. `ARRANJO=autoescala`; scaler no host (abaixo) |
| Imagens completas (depuração) | `COMPOSE_EXTRAS=docker-compose.completa.yml` (e `,docker-compose.autoescala.yml` no braço C) | as `*.slim` são o **padrão** (#197): gere-as antes (`slim/README.md`; sem elas o `up` falha com "image not found"). O `completa` constrói os 7 serviços pelo Dockerfile. O `--build` que `setup-host`/`host-rpm` acrescentam só reconstrói o `frontend` no base (com o `completa`, também os 7 serviços) |

- **Ordem dos overrides (importa):** `docker-compose.yml`, `carga/docker-compose.carga.yml`, `rpm/docker-compose.rpm.yml`, depois os extras (`completa`, se usado, e por último
  `autoescala`). O override de carga põe o Hikari do `reservation` em 1000 e o da autoescala em 300; **a autoescala vem depois**, então vale 300 (3 × 1000 estoura o
  Supavisor, 1500). Os scripts montam a lista (`Get-ArquivosCompose` em `lib-servicos.ps1`, coberta por `lib-servicos.selftest.ps1`), conferem o Hikari de **cada** réplica do
  reservation depois do `up` e avisam se divergir do esperado do braço. Para conferir a lista **antes** de subir, sem imprimir segredo (nunca `docker compose config` sem
  filtro): `docker compose -f ... config --format json` e leia só `services.reservation.environment.SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`.
- **O scaler tem de rodar com os MESMOS `-f` e o mesmo ambiente do `up`** (réplica nova nasce com `--no-recreate`; o ambiente inclui `RESERVATION_READPATH` e `AUTOESCALA_*`). O
  `make host-rpm`/`setup-host` imprime a linha pronta: `AUTOESCALA_COMPOSE_ARQUIVOS='...' Projeto/autoescala/scaler.sh`.
- **Parâmetros provisórios da autoescala**, todos por variável de ambiente do scaler (defaults e significado em `autoescala/README.md`): `AUTOESCALA_GATEWAY_RPS` (500),
  `AUTOESCALA_IDENTITY_RPS` (100), `AUTOESCALA_CATALOG_RPS` (500), `AUTOESCALA_ADMISSION_RPS` (1000), `AUTOESCALA_NOTIFICATION_LAG` (100), `AUTOESCALA_X_RPM` (= `ADMISSION_X_RPM`).
  São os do bloco 2 da R4; o bloco 3 calibrou `AUTOESCALA_GATEWAY_RPS` 1600, `AUTOESCALA_ADMISSION_RPS` 1600, `AUTOESCALA_IDENTITY_RPS` 200 e `AUTOESCALA_DESCE_SEG` 120
  (só no shell do scaler; os defaults não mudaram; resultado em `results/07-rodada4-admission`). Ficam gravados na rodada de dois jeitos: no `registro-<rótulo>-<data>.txt` do `coleta-host.sh` (lê o `AUTOESCALA_*`/`ADMISSION_*` do shell
  onde ele roda: exporte lá os mesmos do scaler) e na coluna `params_autoescala` do CSV da escada (`-ParamsAutoescala 'AUTOESCALA_GATEWAY_RPS=500;AUTOESCALA_X_RPM=56000'` /
  `PARAMS_AUTOESCALA=` no make), porque o gerador não enxerga o host.
- **Réplicas = containers `<project>-<serviço>-<n>`**, sem `gerenciador-*`. O kit (`setup-host.ps1`, `rpm/subir-host-rpm.ps1`, `coleta-host.sh`, `limpar-banco.sh`) as acha por **label**
  (`com.docker.compose.project` + `com.docker.compose.service`; `CARGA_PROJETO`/`PROJETO` mudam o project, default `gerenciador-eventos`). `db`, `redpanda`, `redis` e `rabbitmq` seguem com nome fixo.
- **Dry-run em project isolado (`gerenciador-e2e*`):** nada é publicado no host e o `db` não tem `container_name`. O kit aceita `CARGA_PROJETO` (project das réplicas), `CARGA_DB` (container do Postgres),
  `CARGA_HOST_URL` (entrada do gateway para o health, default `http://localhost:8081`), `CARGA_RABBITMQ` (fila no `verificar-rpm`) e, para `coleta-host.sh`/`limpar-banco.sh`, `PROJETO` e `CONTAINER_{DB,RPK,REDIS,RABBIT}`.
  O k6 precisa de um jeito de chegar ao gateway (porta publicada só no `lb` do project real); o procedimento do project isolado está em `autoescala/README.md` ("Dry-run local com Mailpit"). Dry-run da #178: gateway publicado só em `127.0.0.1:<porta>` por um YAML local FORA do repo (`-Porta`), e o k6 como container (`grafana/k6:<versão fixa>`, nunca `latest`) na rede do project, por um shim `k6.cmd` fora do repo.
- **`ADMISSION_X_RPM` reduzido para o dry-run** (não é medição): exporte antes do `up` (o compose lê da shell) e use a escada com RPMs proporcionais. O `AUTOESCALA_X_RPM` herda o mesmo valor.

### Cenário de escrita acima do X (`make test-degraus`)

O `degraus/carga-degraus.js` ganha o modo fila **sem script novo**: o POST pode responder **201** (síncrono) ou **202** (fila). A lógica pura está em `degraus/lib-admissao.js`
(`lib-admissao.selftest.mjs` a testa; `carga-degraus.selftest.mjs` roda o script inteiro contra um k6 de mentira).

- **Por padrão o POST NÃO leva `Idempotency-Key`** (o caminho síncrono fica idêntico ao da R, X = 56k, sem escrita da chave no `reservation`); o k6 lê a chave do **202** (header `Idempotency-Key`, ou `idempotencyKey` do corpo) para consultar o estado. `-ComChave` / `COM_CHAVE=1` / `IDEMPOTENCY_KEY=1` manda uma chave própria `k6-<RUN_ID>-<i>` por iteração (opt-in).
- **202 conta como aceita** (`reservas_202`), nunca em `outros`. Uma fração `PCT_ACOMPANHA` % das 202 consulta `GET /bff/event-wallets/admission?idempotencyKey=` a cada
  `CONSULTA_MS` até `pending` / `rejected` / `timed_out` ou até `TETO_CONSULTA_S` (`sem_estado`), e **confirma o `pending`** como o síncrono.
- **429 e 422** (limite por usuário/fila cheia; chave de outro evento) têm contador próprio (`recusadas_429`, `conflitos_422`); não são 5xx nem `outros`.
- **Orçamento de VUs do gerador (decisão: amostragem por env + fórmula).** Acima do X uma iteração acompanhada dura até o TTL da fila (30 s), não ~10 ms:
  `VUs ≈ it/s × (PCT_ACOMPANHA/100 × espera média + (1 − PCT_ACOMPANHA/100) × 0,2 s)`. Com `MAX_VUS=2000` e ~1000 it/s só cabe uma espera média de 2 s; com 100% acompanhado e fila cheia isso vira
  `dropped_iterations` e o report chamaria de "gerador saturado". Por isso o acompanhamento é **amostrado**: as demais 202 só contam o 202 (não consultam nem confirmam). O runner avisa quando o
  maior degrau pede mais VUs que `MaxVus` na espera de 30 s. Consequências: as 202 não acompanhadas viram `pending` e seguram a vaga até expirar (~120 s), o que desloca `lotado` e `rejected`
  (documente a fração usada); as consultas somam carga no gateway (`consultas_estado`; ≈ it/s × fração × espera ÷ `CONSULTA_MS` por segundo).
  O padrão `PCT_ACOMPANHA=100` favorece o dry-run e a escada curta; numa escada da R4 bem acima do X (ex.: 96k RPM × 30 s ≈ 48k VUs) o aviso de VUs aparece com certeza: use uma amostragem menor (ex.: `PCT_ACOMPANHA=10`) e registre-a.
- **Dimensione `USUARIOS`:** o admission recusa (429) mais que `ADMISSION_MAX_WAITING_PER_USER` (5) mensagens em espera por usuário. Para a escada não virar uma parede de 429:
  `USUARIOS ≥ it/s × espera média ÷ 5`. O mapeamento e o seed continuam como na seção do limiar X (`USUARIOS × eventos ≥ soma das cotas`).
- **gracefulStop e espaço entre degraus** passam a `max(30, TETO_CONSULTA_S + 10)` (e `+ 1` de espaço) quando há acompanhamento (a iteração acompanhada vive até o teto); sem acompanhamento
  (`PCT_ACOMPANHA=0`) ficam 30 s / 31 s como antes. `Get-GracefulS` (`lib-degraus.ps1`) e `gracefulS` (`lib-admissao.js`) têm a mesma conta e a mesma tabela nos dois selftests.

**Parâmetros** (make / `rodar-degraus.ps1` / env do k6):

| Make | `rodar-degraus.ps1` | env do k6 | Padrão | Significado |
|---|---|---|---|---|
| `PCT_ACOMPANHA` | `-PctAcompanha` | `PCT_ACOMPANHA` | 100 | % das 202 que acompanham o estado |
| `CONSULTA_MS` | `-ConsultaMs` | `CONSULTA_MS` | 1000 | intervalo da consulta (= `Retry-After`; resolução do tempo até admitir) |
| `TETO_CONSULTA_S` | `-TetoConsultaS` | `TETO_CONSULTA_S` | 35 | teto do acompanhamento (> TTL da fila, 30 s) |
| `PARADA_P95_MS` | `-ParadaP95Ms` | `PARADA_P95_MS` | 0 (desligada) | p95 da reserva, numa janela, acima do qual a escada **para** |
| `PARADA_JANELA_S` | `-ParadaJanelaS` | `PARADA_JANELA_S` | 60 | janela da parada: múltiplo de 30 e divisor de `DEG_DURACAO` |
| `SETUP_S` | `-SetupS` | `SETUP_S` | 0 = estimativa (`Get-SetupS`: 0,6 s por lote de 25 usuários, piso 30 s) | só com parada: orçamento do `setup()` (logins) no relógio do k6; ver abaixo. **`k6 run` direto com parada exige `SETUP_S > 0`** (o `setup()` aborta antes do login com 0); o `setupTimeout` do k6 é `max(300, SETUP_S + 60)` s |
| `ARRANJO` | `-Arranjo` | (só CSV) | `sem-autoescala` | rótulo do braço do host |
| `PARAMS_AUTOESCALA` | `-ParamsAutoescala` | (só CSV) | vazio | `k=v;k=v` dos parâmetros do host |
| `COM_CHAVE=1` | `-ComChave` | `IDEMPOTENCY_KEY=1` | sem chave | manda `Idempotency-Key` própria por iteração (opt-in) |

**Condição de parada (latência que não se recupera).** Depois da promoção a réplicas (A = 2) o síncrono volta quando a fila esvazia, e a fila só religa a ~1,6 X (~89,6k); entre ~X e ~1,6 X o tráfego vai direto à
linha do `eventcap` e a latência pode não voltar. Com `PARADA_P95_MS > 0` o k6 declara, por **janela de `PARADA_JANELA_S` s de cada degrau**, um threshold `p(95) < limite` com `abortOnFail` e
`delayAbortEval` no fim da janela (a tag `parada` é única por janela, então o p95 cumulativo do threshold vale só para a janela). **O relógio do `delayAbortEval` é o do TESTE e inclui o `setup()`** (medido na #178, k6 2.2.0: com um
`setup()` de 45 s o abort caiu 1,9 s depois do início do degrau, e não aos 30 s da janela; sem setup, 30,9 s. Com o script real, `SETUP_S=100`, logins em 20,6 s: abort aos 167 s do início dos degraus, esperado 106 + 60 = 166 s). Por isso o `setup()` espera o relógio do teste chegar em `SETUP_S` (o orçamento do setup) e todo `delayAbortEval` soma `SETUP_S`: `SETUP_S + início do degrau + (janela + 1) × J`. Se o `setup()` passar do orçamento, o k6 aborta ANTES da carga dizendo
quanto levou (rode de novo com `-SetupS` maior; o seed é refeito). O `-SetupS` padrão é uma estimativa por lote de 25 logins; ajuste pelo `setup: ... logins ok` do log. Reprovou, o k6 aborta (código
99), o `handleSummary` ainda grava o resumo e o runner trata o 99 como **parada esperada** (sem `PARADA_P95_MS` o 99 continua só um aviso). Os degraus **completos** e o **parcial** (o da parada, com `duracao_s` =
duração efetiva = `min(duração do degrau, (janela da parada + 1) × J, (última janela de 30 s com amostras + 1) × 30)`: o fim da janela que reprovou, e não o instante do abort (as reservas dos ~2 s até o abort caem na janela seguinte e não estendem a duração); os dados só encurtam se o abort veio adiantado. Assim o `rpm_efetivo` não parece "gerador saturado", e superestima no máximo ~2 s de tráfego) viram CSV; os seguintes são **omitidos** (sem linha). A coluna `parada` traz o motivo
(`p95_acima_de_<limite>ms_em_janela_de_<J>s_no_<degrau>`, ou `nenhuma`). A avaliação do k6 é a cada ~2 s, então a parada cai até ~2 s depois do fim da janela.

**Colunas novas no FIM do CSV** (depois de `mapeamento`; o report lê por nome e CSV antigo, sem elas, segue valendo):
`arranjo,pct_acompanha,consulta_ms,reservas_202,acompanhadas,admitidas,rejected,timed_out,sem_estado,taxa_rejected,taxa_timed_out,recusadas_429,conflitos_422,consultas_estado,admitir_p50,admitir_p95,admitir_p99,fim_a_fim_p50,fim_a_fim_p95,fim_a_fim_p99,parada,params_autoescala`

| Coluna | Significado |
|---|---|
| `reservas_202` | POST que responderam 202 (modo fila) |
| `acompanhadas` | 202 que acompanharam o estado; `admitidas + rejected + timed_out + sem_estado = acompanhadas` |
| `admitidas` | acompanhadas que viraram `pending` |
| `rejected`, `timed_out`, `sem_estado` | destinos das acompanhadas (`sem_estado` = nada final dentro do teto da consulta) |
| `taxa_rejected`, `taxa_timed_out` | fração sobre as **acompanhadas** |
| `recusadas_429`, `conflitos_422` | recusas do admission (limite por usuário/fila cheia; chave de outro evento) |
| `consultas_estado` | GET de estado feitos (carga extra no gateway) |
| `admitir_p50/p95/p99` | tempo 202 → `pending`, em ms (resolução = `consulta_ms`) |
| `fim_a_fim_p50/p95/p99` | envio → resultado final em ms (201 + confirma, ou estado final + confirma) |
| `parada`, `params_autoescala`, `arranjo`, `pct_acompanha`, `consulta_ms` | registro da condição de parada e do que o host/gerador rodavam |

A **trava do resumo** continua fechando com os contadores novos (global = soma dos degraus).

> **Comparabilidade com o X da #40:** o caminho síncrono é idêntico ao da R (padrão **sem** `Idempotency-Key` própria; com ela, `-ComChave`, o `reservation` grava a chave também no síncrono e o X = 56k não a pagava). O que
> difere é só o espaço entre degraus: `PCT_ACOMPANHA=100` (padrão) alonga de 31 s para 46 s; para reproduzir a escada da #40 use `PCT_ACOMPANHA=0` (31 s). Compare sempre na mesma configuração ("X(2) ≈ X(1)?").
>
> **Iterações cortadas:** o acompanhamento só entra em `acompanhadas`/`consultas_estado` quando termina. Uma iteração que o k6 corta no meio (a parada por latência aborta o teste com acompanhamentos de até 30 s em voo;
> ou o `gracefulStop` vence) some desses contadores e só aparece em `reservas_202`: as taxas de `rejected`/`timed_out` ficam inclinadas para os desfechos mais rápidos nos degraus finais. Documente a parada ao ler as taxas.
>
> Limite conhecido: as semânticas do k6 de `abortOnFail`/`delayAbortEval` e do campo `thresholds` no `handleSummary` foram conferidas no dry-run da #178 com o k6 2.2.0 (imagem `grafana/k6:2.2.0`): código 99, `handleSummary` roda e grava o resumo, e `thresholds` chega ao `handleSummary` na forma documentada (`{ "p(95)<500": { "ok": false } }` por expressão; o runner lê o `ok`). Sem o campo `thresholds` (k6 antigo) o runner cai no p95 final da janela.

### Coleta do host (`make coleta-host`)

Além do que a #38 coleta (colunas antigas inalteradas e nas mesmas posições), o `coleta-host.sh` grava **no fim** de `results\coleta-host-<rótulo>-<data>.csv`: `walletevent_ins`, `rabbit_requests`,
`rabbit_requests_unacked`, `rabbit_consumidores`, `rabbit_timed_out`, `walletevent_ins_delta`, `redis_hot`, `redis_replicas`, `redis_dbsize`, `redis_eventos_em_fila`, `redis_espera_total`,
`repl_backend`, `repl_reservation`, `repl_identity`, `repl_catalog`, `repl_admission`, `repl_notification` e `duracao_amostra_ms` (31 colunas; `-1` = sem leitura; a Rodada 5 acrescenta mais 5 depois, 36 no total). Detalhes no cabeçalho do script.

- **Fila (RabbitMQ):** UMA chamada `rabbitmqctl list_queues` por amostra (CLI Erlang, lenta: veja `duracao_amostra_ms` no resumo final; as coletas lentas rodam em paralelo com o `psql`/`rpk`, e o intervalo
  real passa a ser o da amostra se ela levar mais que `INTERVALO_S`). Colunas disponíveis sem o management (confirmado na imagem fixada): `messages_ready`, `messages_unacknowledged`, `consumers`.
  **Não há taxa de consumo** sem o plugin de management; o substituto honesto é `walletevent_ins_delta` (inserts novos em `reservation.walletevent` desde a amostra anterior, de `pg_stat_user_tables`:
  o que o worker pool e o síncrono gravaram). `rabbit_timed_out` é a profundidade **instantânea** da dead-letter: o admission a consome (listener da DLX), então ela fica perto de 0; o total de expiradas
  vem do k6 (`timed_out`).
- **Redis:** `adm:hot` (RPM do evento mais quente), `adm:replicas` (réplicas ativas que o scaler publica; 0 = scaler parado), `DBSIZE`, e por evento o contador e o **modo** (`adm:mode:*`) e a espera
  (`adm:wait:*`) com **`SCAN`, nunca `KEYS`**, a cada `EVENTOS_CADA_S` (5 s): o `SCAN` percorre o keyspace inteiro (`adm:res:*` chega a centenas de milhares sob fila), então não vai a cada segundo. O por evento sai
  em `eventos-<rótulo>-<data>.csv` (formato longo: `epoch_ms,evento,soma_buckets,rpm_aprox,modo,espera`; `rpm_aprox` = soma dos buckets vivos × 60000 ÷ `ADMISSION_WINDOW_MS`, aproximado; o exato do mais quente é
  `redis_hot`). A senha vem do ambiente **do container** (`REDISCLI_AUTH="$REDIS_PASSWORD"` dentro do `sh -c`): nenhum segredo no host.
- **Réplicas × tempo:** 1 `docker ps` filtrado por label por amostra (containers em execução por serviço, stand-by inclusive); ativas × stand-by estão no CSV do scaler (`autoescala/saida/replicas-*.csv`).
- **Registro da rodada:** `registro-<rótulo>-<data>.txt` (data, containers por serviço, `AUTOESCALA_*`/`ADMISSION_*` do shell e variáveis públicas lidas dos containers; allowlist de nomes, nenhum segredo).

### Overselling por bloco e limpeza entre blocos

- **`make verificar-rpm PREFIXO=Deg-<id>`** (host, ao fim de **cada** bloco): espera a fila `admission.requests` esvaziar (mensagens que ainda viram reserva depois do fim do bloco falseariam a conta), soma o
  overselling do prefixo e **sai com código 3 se houver overselling no braço CAS** (critério de parada do bloco; no braço `naive` o overselling é o que se mede e só informa).
- **`make limpar-banco`** (`Projeto/carga/limpar-banco.sh`; **pare o scaler antes**, ele detecta pelo `adm:replicas` no Redis e recusa rodar, sem parar nada, se o scaler estiver no ar). Para **todas** as réplicas
  (achadas por label) de backend, admission, reservation, catalog, identity e o notification; apaga a chave do JWT do volume `<project>_identity-keys` (nome derivado do project; **decide pelo volume**, não pela réplica em execução: com o identity fora do ar a chave é apagada igual; sem volume e com container do identity no project ele recusa); move os consumer groups; trunca
  `public`, `reservation`, `catalog` e `identity` numa instrução; **`FLUSHDB` no Redis e purge das filas `admission.*`** (sem `OK` do `FLUSHDB` ou purge que falha, aborta e religa o que parou); sobe de volta só o que ele parou (`docker start`: preserva o ambiente da rodada, ao contrário de
  reaproveitar um container antigo), esperando todas as réplicas `healthy` na ordem identity → reservation/catalog → admission → backend → notification. No braço C espera os slots do identity e do gateway UP no lb e
  faz um **login de sonda** (usuário criado pelo `POST /bff/seed`, que fica no banco) seguido de chamadas autenticadas seguidas (3 × réplicas do gateway) com `200`/`404`; `401`, `000` (sem conexão) e 5xx zeram a contagem: prova o JWKS em cache (a janela do JWKS no boot, em que o gateway
  só tenta de novo a cada 30 s). Se não conseguir apagar a chave, **aborta sem religar nada**. Selftest sem Docker: `bash limpar-banco.selftest.sh` (ordem das operações contra um `docker` de mentira).
  Reinicie o scaler **depois**; ele recalcula do estado atual.
- Sequência por bloco: `make limpar-banco` → (braço C: subir o scaler) → `make coleta-host` + `make telemetria-host` no host → `make test-degraus` no gerador → `make verificar-rpm` → próximo bloco.

### Rodada 4 no gerador: só `git checkout` e `k6 run` (madrugada, sem ninguém para aprovar)

Na R4 o gerador roda **sem ninguém para aprovar** e só pode executar: `git checkout <commit>`, `k6 run ...` (envs inline com `-e`) e o `psql` pelo caminho completo
(`C:\Program Files\PostgreSQL\16\bin\psql.exe`). Não depende do gerador: matar processo, mudar configuração/sistema, instalar, `netsh` (o `dynamicport` do gerador já está em
10000/55535; **o kit não o reverte**, o usuário reverte de manhã). Tudo o mais é do **host**, que pode rodar `.ps1`, `make` e Git Bash.

**Auditoria do lado gerador** (o que o kit antigo fazia no gerador e *não* cabe nessa lista; nenhum `taskkill`/`Stop-Process`/`netsh` no kit):

| O quê | Onde (arquivo:linha) | Na R4 |
|---|---|---|
| Seed pelo Git Bash (`setup.sh`, que usa `curl`) | `lib-seed.ps1:22-36`, chamado em `degraus/rodar-degraus.ps1:168` | o **host** semeia (`degraus/preparar-degraus.ps1`) |
| Arquivo de credenciais `users-*.json`/`events-*.json` gravado e apagado no gerador | `degraus/rodar-degraus.ps1:142-143`, `:210` | o k6 monta os usuários na memória (`USUARIOS` + `SUFIXO`) e os eventos vêm em `EVENT_IDS`; nenhum arquivo de credencial no gerador |
| Health check, `git rev-parse`, `runId`, `Test-Path` | `degraus/rodar-degraus.ps1:114-146` | o host (commit, `runId` e prefixo saem do `preparar-degraus.ps1`) |
| Telemetria do gerador (`Start-Job` + `Get-CimInstance`) | `lib-telemetria.ps1:15-22`, `degraus/rodar-degraus.ps1:181` | **não existe** no modo direto: a saturação do gerador sai de `iteracoes_descartadas` e `rpm_efetivo` do CSV (e do `dropped_iterations` do resumo) |
| Resumo do k6 → CSV e trava do resumo | `degraus/rodar-degraus.ps1:183-206` | o host (`degraus/csv-do-resumo.ps1`, mesmas funções de `lib-degraus.ps1`) |
| Instalar o k6 (`winget`), plano de energia (`powercfg`), `Test-NetConnection`, baseline | `setup-gerador.ps1:39`, `:63`, `:69`, `:77`, `:130` | **não entra na R4** (o gerador já tem o k6; a versão **exigida é a 2.2.0**: ver "Dependências declaradas", item 6) |
| Desinstalar o k6 e voltar o plano de energia | `limpar-gerador.ps1:53`, `:60` | **não entra na R4** |
| `make` (`SHELL := powershell.exe`) e `.ps1` | `Makefile:3`, todos os alvos `test-*` | não existem para o gerador (`make` não está na lista); o equivalente é a linha de `k6 run` abaixo |
| Gráfico/limiar X (`make report-degraus`, Git Bash + awk) | `Makefile:184-186` | host |
| **Matriz** (`rodar-matriz.ps1`) | seed em `:245`/`:258`; `Wait-Estavel` e `Invoke-Psql` por repetição em `:131-188`, `:264`, `:281`; `Start-Process` do k6 com o laço de `psql` concorrente (lag da projeção) em `:198-207`; telemetria `:231` | **não cabe** (ver abaixo) |
| Taxa fixa e aleatório (`rpm/rodar-rpm.ps1:58,74,83`, `aleatorio/rodar-aleatorio.ps1:66,108,118`, `rodar-teste.ps1:48,72,86`) | mesmo padrão do `rodar-degraus` (seed pelo Git Bash, arquivos de credenciais, CSV pelo script) | **não adaptados** (a R4 desta passada é a escada) |

**Escada: o que o gerador digita (sempre os dois comandos, iguais nos dois braços).** O `degraus/preparar-degraus.ps1` (host) semeia, grava o plano em
`results\plano-degraus-<id>.json` e **imprime a linha pronta**. O gerador, na raiz do clone, em Windows PowerShell:

```powershell
git checkout <commit impresso pelo preparar>
k6 run --quiet -e 'BASE_URL=http://<ip do host>:8081' -e 'RPMS=28000,42000,56000,...' -e 'DURACAO_S=60' -e 'USUARIOS=2000' -e 'SUFIXO=<runId>' -e 'EVENT_IDS=1-300' `
  -e 'SUMMARY_FILE=Projeto/carga/results/summary-degraus-<runId>.json' -e 'PCT_CONFIRMA=100' -e 'MAX_VUS=2000' -e 'MAPEAMENTO=sequencial' -e 'PCT_ACOMPANHA=10' ... Projeto/carga/degraus/carga-degraus.js
```

(a linha real é uma só; todos os valores entre aspas **simples**: no Windows PowerShell 5.1 a vírgula sem aspas vira dois argumentos, `;` encerra o comando e as aspas duplas são removidas. O
`preparar-degraus.ps1` recusa valor com aspa. `SUMMARY_FILE` é único por escada, porque o k6 sobrescreve em silêncio, e `Projeto/carga/results/` existe depois do `checkout`.)

Parâmetros por env do k6 (os valores certos já vêm na linha impressa; os de baixo são os que o operador do host escolhe em `preparar-degraus.ps1`):

| env do k6 | Sai de (`preparar-degraus.ps1`) | Significado |
|---|---|---|
| `BASE_URL` | `-HostIp`, `-Porta` | gateway como o gerador o enxerga (no braço C é o `lb`, que publica a 8081) |
| `RPMS`, `DURACAO_S` | `-RpmInicio/-RpmPasso/-RpmMax`, `-Duracao` | a escada (≥ 60 s e múltiplo de 30) |
| `USUARIOS`, `SUFIXO` | `-Usuarios`, `runId` | usuários `carga-att<i>-<SUFIXO>@example.com`, senha `CargaTeste#<SUFIXO>` (a do `setup.sh`: são usuários de teste do seed, apagados na limpeza; a senha não aparece na linha) |
| `EVENT_IDS` | seed | ids dos eventos semeados, em faixas (`1-300,305`) |
| `SUMMARY_FILE` | `runId` | onde o k6 grava o resumo (relativo à raiz do clone) |
| `PCT_CONFIRMA`, `MAX_VUS`, `MAPEAMENTO` | `-PctConfirma`, `-MaxVus`, `-Mapeamento` | como antes |
| `PCT_ACOMPANHA`, `CONSULTA_MS`, `TETO_CONSULTA_S` | idem | modo fila (tabela acima) |
| `PARADA_P95_MS`, `PARADA_JANELA_S`, `SETUP_S` | `-ParadaP95Ms`, `-ParadaJanelaS`, `-SetupS` | condição de parada |
| `IDEMPOTENCY_KEY`, `RUN_ID` | `-ComChave`, `runId` | chave própria por iteração (opt-in) / prefixo das chaves; o k6 grava `run_id` no resumo e o `csv-do-resumo.ps1` **recusa** resumo cujo `run_id` não bata com o do plano (mesmos parâmetros de outro bloco não bastam) |

**O que muda por braço é só do host** (a linha do gerador é idêntica; `-Arranjo`, `-PoolHikari`, `-ParamsAutoescala` e `-MaxConnections` só rotulam o CSV):

| Passo no host | Braço sem autoescala (base) | Braço C (autoescala) |
|---|---|---|
| subir | `make host-rpm` (Hikari do `reservation` = 1000) | `make host-rpm COMPOSE_EXTRAS=docker-compose.autoescala.yml` (300 por réplica) + scaler (`AUTOESCALA_COMPOSE_ARQUIVOS='...' Projeto/autoescala/scaler.sh`, parâmetros `AUTOESCALA_*` exportados antes) |
| entre blocos | `make limpar-banco` | pare o scaler, `make limpar-banco`, suba o scaler |
| ligar | `make coleta-host ROTULO=<bloco>` e `make telemetria-host ROTULO=<bloco>` | idem (a coleta registra `AUTOESCALA_*` do shell) |
| preparar | `degraus/preparar-degraus.ps1 -HostIp <ip> -MaxConnections 300 -PoolHikari 1000 -Arranjo sem-autoescala ...` | `... -PoolHikari 300 -Arranjo autoescala -ParamsAutoescala 'AUTOESCALA_GATEWAY_RPS=500;AUTOESCALA_X_RPM=56000'` |
| gerador | `git checkout` + `k6 run ...` (linha impressa) | o mesmo |
| depois | `degraus/csv-do-resumo.ps1 -Plano results\plano-degraus-<id>.json -Resumo <summary-degraus-<id>.json>`; `make report-degraus CSV=results\degraus-<id>.csv`; `make verificar-rpm PREFIXO=Deg-<id>` (código 3 = overselling no CAS: pare) | o mesmo |

`preparar-degraus.ps1` aceita os mesmos parâmetros do `rodar-degraus.ps1` (`-Mapeamento intercalado`, `-PctAcompanha`, `-ParadaP95Ms`, ...) e `-SeedUrl` (como o host enxerga o gateway para o seed; padrão `http://localhost:<porta>`).
`csv-do-resumo.ps1` não vê o código de saída do k6: a **parada** vem do campo `parada` do resumo (o 99 do k6 é esperado com `PARADA_P95_MS > 0`), e um k6 interrompido sem parada registrada é
recusado pela duração do teste (`Get-FalhaResumo`). O `csv-do-resumo.ps1` também só aceita o resumo cujo `run_id` (o `RUN_ID` da linha) bata com o `run_id` do plano. Os selftests (`make selftests`) cobrem a linha, as faixas de eventos e o CSV, comparado com o do caminho antigo.

**Dependências declaradas, que o kit não resolve:**

1. **O `<commit>` já tem de estar no clone do gerador** (só há `git checkout`, sem `fetch`); host e gerador no mesmo commit.
2. **O resumo do gerador tem de chegar ao host** (`Projeto/carga/results/summary-degraus-<id>.json` no gerador): nenhum dos três comandos leva um arquivo de uma máquina a outra; o transporte
   (cópia pela sessão de interface, compartilhamento) fica fora do kit. O resumo não tem credenciais.
3. **Sem telemetria do gerador** (acima): afirmar "o gerador não saturou" só pelo `iteracoes_descartadas` e pelo `rpm_efetivo`; a telemetria do host (`make telemetria-host`) segue valendo.
4. Quem digita a linha precisa de `k6` no PATH do Windows PowerShell 5.1 e do diretório de trabalho na raiz do clone (os caminhos da linha são relativos).
5. **Teto do seed pelo `lb`:** o `POST /bff/seed` dos usuários é uma chamada só (bcrypt) e, entrando pelo `lb` (braço C: a 8081 é o `lb`), esbarra no `timeout server 75s` do HAProxy: no dry-run local
   2000 usuários deram **504** e 1000 passaram. Com `-Usuarios` acima de ~1000 no braço C semeie pelo gateway sem o `lb` (`-SeedUrl`) ou use menos usuários (o seed cria mais eventos para fechar os pares).
6. **Versão do k6 no gerador: exige a 2.2.0.** A semântica de `delayAbortEval` × `setup()` (base do `SETUP_S`) e a forma de `thresholds` no `handleSummary` foram medidas nela (imagem `grafana/k6:2.2.0`, no
   host); o `setup-gerador.ps1` instala por `winget` **sem versão** e **o kit não confere a versão em tempo de execução** (o k6 não a expõe ao script nem ao resumo). Quem conduz confere `k6 version` no
   gerador **ANTES** da R4 (fora das três chamadas, pela sessão de interface/usuário); com outra versão o alinhamento das janelas pode mudar sem aviso.

**Matriz: não cabe nas três chamadas.** Cada repetição semeia (host), espera o pipeline ficar quieto com `psql` (`rodar-matriz.ps1:174-188`), dispara o k6 enquanto um laço de `psql` concorrente amostra o lag da
projeção (`:190-209`, `Start-Process` + polling a cada 250 ms) e lê `confirmed/pending` no banco (`:281`) para gravar uma linha. Isso é um laço que o gerador não pode conduzir
(`k6 run` e `psql` sozinhos não têm laço nem executam em paralelo), e o k6 da matriz (`carga.js:26`) depende de `USERS_FILE`. Quem conduz a matriz é o host; **não há** versão da matriz só com
`k6 run` + `psql` nesta passada (e a escada acima do X, que é o cenário da R4, não precisa dela).

**Dry-run local X(1) × X(2) (#178, não é medição):** num project isolado, com o k6 no mesmo host da stack, `ADMISSION_X_RPM` enorme (a fila nunca liga: 0 respostas 202 nas duas escadas), `MAPEAMENTO=sequencial` e a mesma escada (5k a 60k RPM, passo 5k, 60 s, 1000 usuários) pelo caminho direto acima, o `reservation` com 1 réplica ativa deu X(1) = 40k RPM e com 2 réplicas ativas (sem scaler; o `lb` repartiu 224.118 × 224.119 sessões entre elas) deu X(2) = 40k RPM por evento, os dois firmes pelo critério do report (o host degradou antes de o k6 descartar iteração) e com overselling 0; mas a CPU do host já estava em 84-95% desde 30k RPM, com o pooler e o banco acima de 2 núcleos e o `reservation` abaixo de 1, então o teto local é do host compartilhado e **não separa o efeito da segunda réplica** (limite inferior do X do sistema; só compara X(1) com X(2) entre si). X(2) ≈ X(1): a regra segue "× réplicas" (ADR da #111).

## Rodada 5: saga stock + wallet (issue #204)

A Rodada 5 (#180) repete a R4 sobre a **saga `stock` + `wallet`** (#47): o `reservation` orquestra tx1 → `stock` (hold + CAS) → tx2, com compensação inline e o reaper de órfãs. **O protocolo é o da R4**
(braços, ordem sem autoescala → braço C, escada acima do X, limpeza entre blocos, gerador só com `git checkout` + `k6 run`); **só a saga muda**, e este kit só acrescenta a coleta e a parada dela.
A configuração é a final da R4, mais o `stock` fixo (uma réplica). Os selftests rodam sem Docker: `make selftests`.

### Regra de custo da coleta

O host é limitado por CPU (S3: X(1) = X(2)) e a R5 compara com a R4, então **a coleta não pode custar mais na R5 do que na R4**, senão o custo dela vira "custo da saga". Por isso, **por amostra**:
só **contagens servidas pelo índice parcial** `idx_idempotency_key_saga_pendente`, num segundo `-c` do **mesmo** `psql` da amostra (nenhum `docker exec` a mais) e **um** `rpk` a mais (multi-group, em paralelo
com as outras coletas lentas). Todo `JOIN` entre schemas e todo percentil rodam **uma vez, no fim da coleta** (ou no `verificar-rpm`), nunca por amostra, e depois de capturado o `pg_stat_statements` (as
consultas do resumo não entram nele).

### Colunas novas de `coleta-host-<rótulo>-<data>.csv`

DEPOIS de `duracao_amostra_ms`; as 31 colunas da R4 seguem idênticas (nome e posição, travado no selftest). `-1` = sem leitura. Total: 36 colunas.

| Coluna | Fonte |
|---|---|
| `lag_stock`, `lag_stock_catalog` | `TOTAL-LAG` dos groups `stock` (devolução da vaga, tópico `reservation.wallet`) e `stock-catalog` (`catalog.event`), num único `rpk group describe stock stock-catalog`. Group ausente (ou `Dead`) = `-1`. O group antigo `reservation-catalog` não existe mais: a coluna `lag_reservation_catalog` da R4 fica (o `rpk` devolve `STATE Dead` com `TOTAL-LAG 0` e a leitura antiga não olha o `STATE`), então vale `0`, não `-1` (conferido no dry-run) |
| `saga_reservando` | `idempotency_key` em `saga_status = 'reserving'` |
| `saga_orfas` | `reserving` com `updated_at` mais velho que `ORPHAN_MS` (as que o reaper vai tomar) |
| `saga_compensando` | `idempotency_key` em `compensating` |

Falha só da consulta da saga (stack sem a saga) deixa `saga_*` em `-1` e **não estraga** as colunas da R4.

### Arquivos novos (gravados ao encerrar a coleta com Ctrl+C ou `COLETA_S`)

- **`saga-<rótulo>-<data>.csv`**: compensações por minuto (`minuto,compensadas_inline,compensadas_reaper`, por `date_trunc('minute', resolved_at)` e `resolved_by`, janela ≥ início da coleta, lido do `now()` do banco: o mesmo relógio dos `created_at`/`resolved_at`/`occurred_at` e do `docker logs --since`).
  **Subcontagem conhecida:** o `reabrir` (retry de uma chave `compensated`) apaga o `resolved_at`; o k6 da escada não reusa `Idempotency-Key`, então essa subcontagem é ~0 na rodada.
- **`resumo-saga-<rótulo>-<data>.txt`** (também impresso): p50/p95/máx do **tempo até resolver** (`resolved_at − created_at` das `compensated`, separado por `resolved_by`; o do `reaper` é o "tempo até a órfã se
  resolver"), do **lag de devolução** (`hold.updated_at − occurred_at` do `ReservationCancelled`/`ReservationExpired` com o mesmo `holdId`, hold `released`), o **estado no fim do bloco** (devolução pendente,
  órfã inversa, sagas ainda `reserving`/`compensating`) e a **latência dos passos**.
- **Latência dos passos** (`tx1_ms`, `stock_ms`, `tx2_ms`, `total_ms`): o `reservation` loga uma linha por reserva **aceita** no logger `saga.timing` (DEBUG, desligado por padrão, como o `events.lag`). Ligue
  **só no bloco que mede a latência dos passos**, descomentando `LOGGING_LEVEL_SAGA_TIMING: "DEBUG"` no `reservation` de `carga/docker-compose.carga.yml` (um log por reserva infla a latência: esse bloco não vale
  como medida de X). A coleta lê `docker logs --since <início da coleta>` de **todas** as réplicas do `reservation` do project (o braço C tem várias). Sem nenhuma linha, o resumo diz que o logger está desligado e
  que a latência dos passos ficou fora.
- Falha de qualquer parte do resumo (consulta que quebra) vira uma linha "falhou" no `.txt` e **não impede** o `pg_stat_statements` nem o resumo antigo do CSV.
- O `registro-<rótulo>-<data>.txt` ganha o `ORPHAN_MS` usado, `RESERVATION_SAGA_ORPHAN_MS`/`RESERVATION_SAGA_REAPER_SCAN_MS` do `reservation` e o `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` do `stock`.

### Parâmetros

| Parâmetro | Onde | Padrão / significado |
|---|---|---|
| `RESERVATION_SAGA_ORPHAN_MS`, `RESERVATION_SAGA_REAPER_SCAN_MS` | **env do container `reservation`** (override do compose; não são definidas no compose, valem os defaults do `application.properties`: 30000 / 5000) | o limiar e a varredura do **reaper**. É AQUI que se reduz o limiar do dry-run: a coleta e o `verificar-rpm` leem estes valores do container sozinhos |
| `ORPHAN_MS` | env do `coleta-host.sh` | limiar de `saga_orfas`. Lido **uma vez** do `RESERVATION_SAGA_ORPHAN_MS` do container `reservation` do project (achado por label); 30000 se não achar. **Só escape:** exportar força outro valor no harness, mas **não muda o reaper**; precisa bater com o do container, senão `saga_orfas` conta órfãs que o reaper ainda não considera órfãs |
| `ORFA_LIMITE_S` | `make verificar-rpm` (`-OrfaLimiteS` no `subir-host-rpm.ps1`) | tempo máximo para uma órfã se resolver antes do código 4. Padrão = `ORPHAN_MS + 3 × SCAN_MS` (spec, "Parada da R5") lidos do container (`RESERVATION_SAGA_REAPER_SCAN_MS`, padrão 5000): **45 s** com os defaults. **Só escape:** passado à mão, também precisa bater com o container (reaper mais lento que o limite = código 4 falso); o `verificar-rpm` imprime a origem do limite (parâmetro ou container) |
| `LOGGING_LEVEL_SAGA_TIMING` | env do `reservation` (linha comentada no override de carga) | `DEBUG` liga a latência dos passos; ligar só no bloco que mede |
| Hikari do `stock` | compose (literal) | **pré-condição:** `min(250, 1500 − soma da R4)`, conferido antes do 1º bloco (usar os Hikari finais da R4: identity/catalog acima de 51 por réplica estouram o Supavisor com o `stock`); registrar o valor no README da etapa 08 |

### Parada por bloco: `make verificar-rpm PREFIXO=Deg-<id>` (host, ao fim de cada bloco)

Ordem: espera da fila `admission.requests` → **overselling** → lag do group `stock` a 0 (teto de 30 s) → **sagas do prefixo** (polling a cada 2 s até `ORFA_LIMITE_S`).

| Código | Significado |
|---|---|
| 0 | ok |
| 1 | erro (não consegui ler o banco / a consulta quebrou) |
| 2 | matriz incompleta (`rodar-matriz.ps1`) |
| 3 | **overselling no braço CAS** (continua sendo SÓ overselling e tem precedência sobre o 4) |
| 4 | **invariante da saga que não convergiu em `ORFA_LIMITE_S`** ("reserva órfã que não se resolve") |
| 99 | parada de latência do k6 (escada) |

O código 4 sai com a mensagem das contagens, e o resultado vai ao `results\verificacoes.log` como o do overselling. As invariantes (decisão pura em `Get-DecisaoSaga`, `lib-servicos.ps1`, coberta no selftest):
saga `reserving`/`compensating` do prefixo que **sobrou** depois do limite (o bloco já acabou, então ela tem idade ≥ o limite); hold `held` cuja saga está `compensated`; hold `held` com `ReservationCancelled`/`ReservationExpired` no
`event_store` (pelo `holdId`, sem JOIN com o `walletevent`: o UPSERT que revive a reserva troca o `hold_id`) **há mais de `ORFA_LIMITE_S`** (devolução pendente; só conta o cancelamento/expiração mais velho que o limite, porque as reservas `pending` não acompanhadas expiram ~120 s depois do bloco, em cima da verificação, e o consumer do `stock` ainda as drena); órfã inversa (`walletevent` `pending`/`confirmed` sem hold `held` de mesmo `hold_id`); e `quant ≠ capacity − (holds held decrementados)` em
evento limitado. A consulta junta schemas (`reservation`, `stock`, `catalog`) como operador, com `psql` como admin: não é leitura de um serviço em tabela de outro.

### Ordem por bloco (importa)

1. `make coleta-host ROTULO=<bloco>` (+ `make telemetria-host`), antes do `k6 run` no gerador;
2. ao fim do k6, **a coleta continua rodando** até `saga_reservando` e `saga_compensando` (últimas linhas do CSV ao vivo) chegarem a 0, **ou** até passar `ORFA_LIMITE_S` (45 s com os padrões). **Só então Ctrl+C** (gera
   `saga-*.csv` e `resumo-saga-*.txt`). Cortar logo ao fim do k6 perde a cauda do "tempo até a órfã se resolver": as sagas que ficam `reserving`/`compensating` nos últimos ~`ORPHAN_MS + SCAN_MS` só são
   resolvidas pelo reaper depois, o p95/máx do `resolver_reaper` sai viesado para baixo (justo no último degrau, o mais propenso a timeout do `stock`) e não dá para recuperar, porque o passo 4 trunca as linhas;
3. `make verificar-rpm PREFIXO=Deg-<id>` (código 3 ou 4: pare), **depois** do Ctrl+C: as consultas cross-schema dele não entram no `pg_stat_statements` da coleta (o protocolo da R4 não muda);
4. `make limpar-banco` (também limpa o schema `stock` e o group `stock`, desde a #47).

O **lag de devolução** do resumo é uma **amostra**, não o total: as `pending` não confirmadas expiram ~120 s depois de criadas, então as devoluções dos últimos ~120 s do bloco ficam fora (a expiração delas é
depois da coleta, e o `limpar-banco` as apaga). Os **p95 também têm duas definições**: o `percentile_cont` do banco (tempo até resolver, lag de devolução; interpolado) e o rank do `awk` (latência dos passos,
`saga.timing`); não compare um com o outro valor a valor.

**A coleta vem antes do `limpar-banco`**: o resumo lê `idempotency_key`, `walletevent`, `event_store` e `stock.hold`, que a limpeza trunca. A retenção de 24 h das chaves de idempotência não interfere (o bloco é mais curto).

### O que continua igual à R4

Fila, Redis, réplicas, `pg_stat_statements`, lag dos groups antigos, pooler e a limpeza entre blocos seguem como na seção da R4; o `limpar-banco.sh` não mudou. O que muda entre os caminhos da R4 e da R5
(saltos e transações por caminho) está na tabela do README da etapa 08. O **dry-run curto** contra a stack local, em project isolado, é uma fase à parte (`CARGA_PROJETO`, `CARGA_DB`, `CARGA_REDPANDA`,
`PROJETO`, `CONTAINER_DB` e `CONTAINER_RPK` apontam o kit para o project; o limiar reduzido vai no **container `reservation`** do project, por override de `RESERVATION_SAGA_ORPHAN_MS` /
`RESERVATION_SAGA_REAPER_SCAN_MS`: a coleta e o `verificar-rpm` leem de lá. `ORPHAN_MS` / `ORFA_LIMITE_S` exportados ficam só como escape e precisam bater com o container).

## Modo cluster (issue #228): o kit contra o Kubernetes

Com **`CARGA_K8S=<contexto kubectl>`** (ex.: `kind-gerenciador`) o kit do **host** fala com o cluster de `Projeto/k8s` no lugar do compose, sem Docker: `limpar-banco.sh`, `coleta-host.sh` e `make verificar-rpm`.
**Sem a variável nada muda** (o compose e os selftests de antes seguem iguais; nada do modo cluster é carregado). Todo `kubectl` leva `--context $CARGA_K8S` e `-n gerenciador` (nunca o `current-context`). O gerador
(`rodar-degraus.ps1` etc.) não muda: bate no gateway pelo NodePort (host `:8081`), que o overlay `rodada` do cluster publica (`Projeto/k8s/README.md`, "Rodada com o db remoto").

```powershell
$env:CARGA_K8S = 'kind-gerenciador'      # ANTES do make/bash, no shell do host
make limpar-banco                        # entre blocos
make coleta-host ROTULO=k1               # durante a escada
make verificar-rpm PREFIXO=Deg-59713     # ao fim de cada bloco (codigos 3 e 4 como antes)
```

**Banco do cluster ou remoto:** o kit decide pelo estado do cluster, **uma vez por processo** (`db_psql_preparar`: `get sts/db --ignore-not-found`; um erro ao perguntar aborta, nunca vira "remoto"): com o `StatefulSet db`, o `psql` roda por `kubectl exec` nele; sem ele (db remoto, componente `db-remoto`), roda no pod cliente `db-cliente` (criado e esperado Ready uma vez, mesma imagem do `db`), que fala com o Service `db` -> EndpointSlice do banco remoto. Depois da decisão cada psql é **1 `kubectl` (o exec)**: a coleta faz um por amostra. A senha vem do Secret (`secretKeyRef` só das chaves lidas) como variável **do pod** e é lida dentro do `sh -c`: nunca em argumento nem em log (`Projeto/k8s/lib-db-cliente.sh`, a mesma lib do `cluster.sh psql`).

| Script | O que faz no cluster (equivalente do compose) |
|---|---|
| `limpar-banco.sh` | **recusa** (código != 0, sem parar nada) se um ScaledObject já tem `autoscaling.keda.sh/paused-replicas` ou se algum Deployment tem a anotação `carga.gerenciador/replicas-antes` (uma limpeza anterior abortou: rodar de novo "daria certo" com o cluster parado) e imprime os comandos de recuperação (`kubectl --context <ctx> -n gerenciador annotate scaledobject --all autoscaling.keda.sh/paused-replicas-`, `scale deploy/<svc> --replicas=<n>` com as réplicas anotadas, `annotate deploy --all carga.gerenciador/replicas-antes-`). Depois: **anota as réplicas de antes em cada Deployment**, pausa o ScaledObject (`paused-replicas=0`: sem isso o KEDA sobe o pod de volta) e zera os 7 Deployments (backend, admission, catalog, reservation, stock, identity, notification); apaga a chave do JWT do PVC `identity-keys` com um pod descartável (`limpar-chave`; se não apagar, **nada é religado** e a mensagem traz os comandos de recuperação com as réplicas); `rpk group seek` dos groups (`kubectl exec sts/redpanda`); o mesmo `TRUNCATE ... RESTART IDENTITY` nos 5 schemas; `FLUSHDB` no Redis e `purge_queue` das filas `admission.*` (`kubectl exec`); religa: **primeiro tira a pausa e faz o `scale` de todos** os que parou, **depois** espera `readyReplicas` na ordem identity, stock, reservation + catalog, admission, backend, notification (uma espera estourada não deixa os seguintes pausados) e apaga as anotações; sonda de login pela `BASE_URL` (o NodePort `localhost:8081`); backend que estava em 0 réplicas = a sonda não roda e a limpeza **falha** (JWKS não provado). Sem o `lb` e sem o `scaler.sh` (a autoescala é o KEDA). Aborto antes de religar religa o que parou (inclusive o Deployment em que o `annotate` deu certo e o `scale` falhou); se o religar também falhar, imprime a recuperação |
| `coleta-host.sh` | as **mesmas 36 colunas**, todas com equivalente: `psql` pelo `sts/db` ou pelo `db-cliente` (como `supabase_admin`, senha do pod); `rpk`, `redis-cli` e `rabbitmqctl` por `kubectl exec` (`sts/redpanda`, `deploy/redis`, `deploy/rabbitmq`) sob `timeout`; `repl_*` = `readyReplicas` de cada Deployment; `redis_replicas` = a chave `adm:replicas`, que no cluster o próprio `admission` grava (`ADMISSION_K8S_REPLICAS_ENABLED`); `ORPHAN_MS` e o registro da rodada lidos do Deployment `reservation`; `saga.timing` por `kubectl logs -l app=reservation --since-time` |
| `make verificar-rpm` | o `-Verificar` do `subir-host-rpm.ps1` (overselling, código 3; órfãs da saga, código 4): `psql` pelo mesmo caminho (via o Git Bash do host, `Invoke-PsqlDb` em `lib-servicos.ps1`), env do `reservation`/`backend` lido do spec do Deployment (`kubectl get deploy -o jsonpath`; as imagens slim não têm `printenv`), lag do group `stock` por `sts/redpanda` e a fila por `deploy/rabbitmq` |

**Variáveis de ambiente dos serviços (registro da coleta, `ORPHAN_MS`, `-Verificar`):** as imagens slim não têm `printenv` nem `sh`, então em modo cluster o valor sai do **spec do Deployment** (`kubectl get deploy/<svc> -o jsonpath` no container `<svc>`: `leitura_env` no `coleta-host.sh`, `Get-EnvServico` no `lib-servicos.ps1`). Valor literal = o valor; `valueFrom` (Secret/ConfigMap) = `<de Secret/ConfigMap>` (o valor do Secret nunca é lido); ausente do spec = vazio (o que vem só de `envFrom` não aparece: o `ORPHAN_MS` cai no default 30000 se não for literal). No compose segue o `docker exec printenv`.

**Limites do modo cluster:** a latência dos passos da saga só cobre os pods que ainda existem ao fim do bloco (o `kubectl logs` não lê pod removido pela autoescala); o `/actuator/health` do gateway na 8081 não serve
no cluster (a 8090 não é exposta), então o `limpar-banco.sh` loga as réplicas prontas em vez do health, e o `preparar-degraus.ps1` e o `rodar-degraus.ps1` tratam a checagem de `/actuator/health` como de **alcance** (qualquer resposta HTTP **abaixo de 500**, inclusive o 401, conta; falha de conexão, timeout ou status >= 500, como o 503 do actuator DOWN no compose, aborta). A causa do 401: no cluster o manifest liga `MANAGEMENT_SERVER_PORT=8090` (`Projeto/k8s/base/*.yaml`), o actuator vai para a porta de management e o `/actuator/health` da 8081 cai no Spring Security; é uma diferença só de ambiente (as probes da #197/#207) e o `/bff/**` não muda; só o `-Verificar` do `subir-host-rpm.ps1` e o `coleta-host.sh`/`limpar-banco.sh` têm modo cluster (subir a stack é
`Projeto/k8s/cluster.sh`). Selftests sem Docker nem cluster (`kubectl` falso): `make selftests` (inclui `Projeto/k8s/cluster.selftest.sh`). Nada disto foi executado contra um cluster real ainda.

## Teste longo (soak) (issue #232)

Teste de 2 h ou mais por bloco (S1 = teto de 35k RPM com `ADMISSION_X_RPM`=56000, S2 = 50k RPM com `ADMISSION_X_RPM`=4000) contra o cluster Kubernetes, com carga que **sobe e desce ao acaso em cada tipo de forma independente** (vários podem estar em pico ao mesmo tempo).
Confere **corretude** sob carga longa, **autoescala acompanhando a carga** (subindo e descendo) e **troca de réplica sem erro**; não mede capacidade. Desenho: `docs/superpowers/specs/2026-11-01-teste-longo-soak-design.md`.
A infraestrutura (overlay `soak`, Mailpit, firewall, ordem do bloco) está em `Projeto/k8s/README.md`, "Teste longo (soak)". **Nenhum e-mail real sai**: o cadastro lê o código de verificação num Mailpit dentro do cluster.
Tudo aqui está em `Projeto/carga/soak/`, exceto o `lib-sorteio.js`.

**Gatilho por bloco e eventos quentes (decisões do usuário; conta completa na spec, seção "Decisões").** O KEDA do `reservation` usa `R = min(3, ceil(hot/(0,5·X)))` e o `admission` liga a fila com `hot ≥ 0,8·X·R` e a desliga
com `hot < 0,7·X·R` e a espera vazia (`hot` = RPM do evento mais quente; `R` = réplicas prontas do `reservation`). Em regime a fila só liga com `R` no teto 3 (`hot ≥ 2,4·X`) ou de passagem. O pico de reserva é 7,35k a 10,5k no S1 e 10,5k a
15k no S2 (o máximo do S1 é o mínimo do S2: nenhum X único serve). Por isso o X é do bloco, passado no ambiente do `cluster.sh up`:

| | S1 (X = 56000) | S2 (X = 4000) |
|---|---|---|
| alvo do KEDA do `reservation` | 28000 | 2000 |
| reserva | **espalhada** pelos 2000 eventos do seed (60% confirma, 10% cancela, 30% expira): `hot` ≈ 3,7 a 5,3 RPM por evento | 95% no(s) evento(s) quente(s) (que só cancelam) |
| `hot` (RPM do evento mais quente) | ≈ 3,7 a 5,3 (vale ≈ 0,5) | 9,975k a 14,25k (vale 1,425k; 95% da reserva em 1 quente) |
| `reservation` | 1 réplica (alvo 28k, nunca alcançado); o critério 2 **não se aplica** a ele | `R`=3 em todo pico, 1 no vale |
| fila (202) | nunca liga (limiar 44,8k) | liga em todo pico (limiar 9,6k com `R`=3 < pico mínimo 9,975k; 3,2k com `R`=1); desliga abaixo de 8,4k com `R`=3 e em 2,8k com `R`=1 no vale |

**Os eventos quentes existem só no S2** (o único bloco que precisa da fila e do X = 4000): `preparar-soak.ps1` usa `-Quentes` = 1 no S2 e 0 no S1 por padrão (`Get-QuentesDoBloco`, `lib-soak.ps1`; um valor explícito vale) e só então semeia os quentes e exporta `QUENTES_IDS` ao k6. No **S1** (X = 56000, sem fila) a reserva
fica **espalhada** pelos eventos do seed (mapeamento `intercalado` do kit, pares únicos) com o mix realista 60% confirma / 10% cancela / 30% expira e confirmação de verdade: é o bloco da confirmação em massa, com o overselling conferido (200 vagas por evento: ≈ 240 a 400 mil confirmações conforme a semente; a fronteira de capacidade é exercitada nas sementes altas, nos últimos 15 a 30 min: conta de capacidade abaixo). No **S2**, a reserva mira poucos **eventos quentes** (`-Quentes 1 -CapacidadeQuente 20000`; `QUENTES_IDS` no k6): `PCT_QUENTE` (95) % das reservas vão a ele e **cancelam logo** (o par usuário × evento se repete; toda 202 quente é acompanhada),
`INSISTENTES` (1) usuário recebe `PCT_INSISTENTE` (25) % delas para tentar o 429 do limite por usuário do `admission` **só por carga**; os 5% restantes seguem o mapeamento par-único e o 60/10/30 de sempre nos 2000 eventos comuns (500 vagas). **Mix efetivo da reserva no S2** (quentes só cancelam): ≈ 3% confirma, ≈ 95,5% cancela, ≈ 1,5% expira.
Capacidade do quente = **20000** (finita): os holds em aberto são `reservas quentes/s × (tempo até cancelar + atraso da devolução assíncrona)`, ≈ 237/s no pico do S2 × 60 s tolerados ≈ 14,3k (spec, "Decisões" 2). Com 20000 vagas e 2000 usuários o quente nunca enche por reservas ativas: no S2 o soak confere a convergência da saga (hold/release por `holdId`, revive `cancelled → pending`), não estressa o overselling; o overselling sob disputa já foi medido na R4/R5 e fica fora do soak.

**Capacidade do S1 (decisão do usuário: 200 vagas por evento, padrão do bloco; 2000 × 200 = 400 mil vagas, 4 M de pares).** O `preparar-soak.ps1` semeia o S1 com `-Capacidade` 200 por padrão (`Get-CapacidadeDoBloco`, `lib-soak.ps1`; o S2 usa 500; um `-Capacidade` explícito vence), como faz com `-Quentes`.
Tentativas de reserva em 2 h = área do perfil da reserva (teto 30% × 35k = 10,5k; pico 70–100%, vale 10%): média ≈ 5k/min, **≈ 627 mil** (8 sementes: 404 mil a 770 mil; teórico se todo o bloco ficasse no pico máximo: 1,26 M); o `intercalado` manda a tentativa `i` ao evento `i % 2000`, então cada evento recebe o mesmo número ±1 (média 313). Das aceitas, 60% confirma (a vaga some), 10% cancela (volta em segundos) e 30% expira (volta em `max_time_in_line` = 120 s + até 30 s da varredura: ≈ 2 a 4 em voo por evento, ≈ 8 mil no total).
Um evento **lota** quando confirmadas + em voo chegam a 200, o que pede ≈ 330 tentativas no evento (≈ 660 mil no total): um pouco acima da média (≈ 188 confirmadas + 2 a 4 em voo, desvio ≈ 9). **Logo a lotação depende da semente (sorteada por bloco) e só acontece nos últimos 15 a 30 min.** Conta (perfil.py de cada semente + Monte Carlo do 60/10/30; ordem de grandeza, tabela completa na spec, "Decisões" 2):

| semente (tentativas) | eventos que lotam (de 2000) | o 1º lota em | 400 "lotado" |
|---|---|---|---|
| baixa, 404 mil e 443 mil (sementes 2 e 6) | 0 | – | 0 |
| média, 597 mil e 616 mil (sementes 1 e 7) | 24 e 116 (1% a 6%) | ≈ 105 min | 91 e 472 |
| alta, 701 mil a 770 mil (sementes 3, 4, 5 e 8; 4 de 8) | 1850 a 2000 (92% a 100%) | 88 a 102 min | 37 mil a 104 mil |

**Escolha da semente do S1 (decisão do usuário: alguns eventos têm de lotar).** A semente é escolhida ANTES de rodar, pelo total esperado de tentativas de reserva do perfil (área da taxa da reserva no `perfil-<id>.json`, que o `preparar-soak.ps1` mostra antes de rodar): é uma propriedade do perfil, não do resultado. Regra: usar uma semente com **≥ 700 mil tentativas** esperadas (pela simulação, lotam 92% a 100% dos eventos a partir de ≈ 88 a 102 min). **Semente usada no S1: 4** (≈ 701 mil tentativas, a mais baixa das que lotam com folga, para passar menos tempo em modo "lotado"). Totais com teto 35k e 2 h, sementes 1 a 12: ≥ 700 mil = 3 (770k), 4 (701k), 5 (734k), 8 (748k); 580 a 620 mil = 1, 7, 9, 12 (lotam só 1% a 6%, perto de 105 min, com risco de nenhum lotar se o gerador descartar iterações); ≤ 480 mil = 2, 6, 10, 11 (não lotam).

**"Lotado" é 400 de negócio:** não reprova o critério 3 (só 5xx e status 0 reprovam), aparece em `c400` da reserva no por-minuto, e como cancelados e expirados devolvem a vaga o evento oscila na fronteira. As confirmações ficam em ≈ 240 a 400 mil (teto: as 400 mil vagas). O critério de overselling (ativas ≤ capacidade por evento, `verificar-rpm`) lê a capacidade do próprio banco e continua o mesmo, agora com a fronteira de fato tocada nas sementes altas (`quant` chega a 0). Trade-offs: não há **disputa concorrente** pela última vaga (≈ 5 RPM por evento; a disputa é da R4/R5); nas sementes altas, nos últimos 15 a 30 min a reserva vira rejeição barata (sem append nem confirm); com semente baixa o S1 não chega à fronteira. `-Capacidade 500` volta ao comportamento sem lotação; `-e MAPEAMENTO=sequencial` no k6 enche cada evento (2000 tentativas seguidas; `hot` ≈ 7,35k a 10,5k, ainda sem fila nem réplica do `reservation`; a maioria vira 400 "lotado" e as confirmações caem para ≈ 100 mil): nenhum dos dois é o padrão.
Pares: o `intercalado` usa o usuário `⌊i / 2000⌋`, então 627 mil tentativas tocam só ≈ 313 usuários, cada um em eventos diferentes; **nenhum par se repete** (i < 4 M), logo **0 respostas 400 de "vínculo existente"** no S1. O mix do S1 tem o `reservation` em 1 réplica e a fila fora (`hot` ≈ 5 RPM).


| Arquivo | Onde roda | O que faz |
|---|---|---|
| `perfil.py` | host ou gerador (Python ≥ 3.8) | a partir de `--semente` e `--teto`, sorteia o perfil dos 5 tipos e grava `perfil-<id>.json` (**mesma semente = mesmos bytes**: só `random()`, chaves ordenadas, LF, sem timestamps). Fatias do teto: leitura 60%, reserva 30%, login 7%, cadastro 2%, escrita de catálogo 1%. Fases de 3 a 10 min (a última absorve o resto, sempre ≥ 3 min), rampa de 30 a 60 s no início de cada fase, pico (70% a 100% do teto do tipo) ou vale (10%), 50% cada. O arquivo traz os `stages` do k6, as fases (tag `fase`), a taxa e o pico **por minuto** e o `total_cadastros_esperado` (1 e-mail por cadastro). `--fixo dry-s2` (sem `--semente`) grava o perfil FIXO do dry-run do S2 (3 subidas vale → pico, 600 s; ver "Dry-runs curtos"), também byte a byte igual |
| `carga-soak.js` | gerador (k6) | 5 cenários `ramping-arrival-rate` em paralelo (`timeUnit` 1 min: o alvo é RPM de **iterações**; um cadastro são 2 chamadas e uma reserva com confirmação também, e contam 1). Lê o perfil de `PERFIL_FILE`. Reusa `degraus/lib-admissao.js` (classificação da reserva, acompanhamento do 202, usuários do seed), `degraus/lib-mapeamento.js` (par usuário × evento, `intercalado` por padrão) e `lib-sorteio.js` (o sorteio determinístico que era interno do `carga-aleatoria.js` e do `carga-degraus.js`; agora é um módulo só, com selftest de equivalência). Tags em toda amostra: `tipo` (`mailpit` nas chamadas ao captador e na sonda do `setup()`), `fase`, `min` (minuto do bloco; **as duas calculadas por requisição**, não por iteração) e `name` (o caminho com `{id}`); `url` fica fora das `systemTags`. **Nenhuma Trend** (só o CSV bruto) |
| `agregar.py` | host ou gerador (Python 3.14.7 no gerador) | lê o CSV do `k6 --out csv` (`.csv` ou `.csv.gz`) **por streaming** (vários GB sem compressão por bloco) e grava `soak-<id>-por-minuto.csv`: por minuto e por tipo, `taxa`, `reqs`, p50/p95/p99 (nearest-rank), contagem por código **mutuamente exclusiva** (`c2xx` = 200–299 menos o 202, `c202`, `c400`, `c429`, `c5xx`, `c0` = status 0 / erro de transporte, `outros`), `descartadas`, `verificacoes_2xx` e `inicio_s` (epoch do minuto). **Nunca descarta contagem em silêncio**: amostra atrasada (minuto já fechado) vai para uma linha `atrasada=1` do mesmo (minuto, tipo) **no próprio por-minuto** (o que o `crit3` soma), é impressa por tipo, e o programa sai com **código 3** se ela tiver 5xx ou status 0. Soma também a métrica `reserva_400` (Counter do `carga-soak.js`, tag `motivo` tirada do corpo do 400) e imprime no fim `reserva 400 por motivo: lotado=N vinculo=N outro=N` (fora do por-minuto: não vira requisição nem entra em `c400`) |
| `verificar-soak.sh` | host (Git Bash) | `pre`, `amostrar` e `pos` (abaixo) |
| `amostra.py`, `criterios.py` | host | as peças puras do `verificar-soak.sh` (amostra do `kubectl`, pré-checks, critérios 1 a 3), com selftest |
| `preparar-soak.ps1` | host | gera o perfil, semeia (2000 × 2000, com a capacidade do bloco: `-Capacidade` S1 = 200, S2 = 500; e, só no S2, os eventos quentes; `-Quentes` padrão do bloco); `-Fixo dry-s2` usa o perfil fixo do dry-run do S2, promove o host do seed por SQL e imprime a linha EXATA do gerador (mesmo modelo do `preparar-degraus.ps1`); exige `-Bloco S1|S2` |
| `firewall-mailpit.ps1` (+ `lib-firewall.ps1`, `conferir-firewall.ps1`) | host (Administrador só no `Criar`/`Remover`) | **duas** regras permanentes do Windows para a API do Mailpit (8025), criadas UMA vez e removidas só no fim dos testes: `Block` para todo o IPv4 menos o gerador (faixas complementares) **e para todo o IPv6** (`::-ffff:…:ffff`; o gerador tem IPv6 global, mas o k6 usa a URL IPv4), que continua barrando o resto da LAN, e `Allow` (`TCC soak mailpit 8025 - gerador`) só do IP do gerador, perfil Any. A `Allow` própria é necessária porque as do Docker Desktop são só do perfil Public e as redes do host são Private (sem ela o gerador leva timeout); ela não alarga nada, porque o escopo é só o gerador. O IP do gerador vem do `GERADOR_IP` (DHCP: não é fixo; mudou = rode o `Criar` de novo). `Status` mostra as duas regras (com `-GeradorIp`, se conferem) e as outras `Allow` largas de rede ativa (fora as de app UWP); `-WhatIf` nativo. O `conferir-firewall.ps1` é o que o `verificar-soak pre` roda, sem administrador |

### Cada tipo de carga

| Tipo | Fatia | Fluxo |
|---|---|---|
| leitura | 60% | `GET /bff/events/{id}` (50%), `GET /bff/event-wallets/user/{id}` (30%) ou `GET /bff/events/search` (20%) |
| reserva | 30% | `POST /bff/event-wallets` (braço `cas`). **S1:** espalhada pelos eventos do seed. **S2:** 95% vão ao(s) evento(s) quente(s) e cancelam logo (toda 202 quente é acompanhada; ver acima); as demais (e todas no S1): o 202 é acompanhado em `PCT_ACOMPANHA` % (padrão 10) até `pending`, `rejected` ou `timed_out`, como no harness, e depois da reserva aceita 60% `POST /confirm`, 10% `DELETE /bff/event-wallets` (cancela), 30% expira sozinha |
| login | 7% | `POST /bff/users/login` com usuário do seed (bcrypt) |
| cadastro | 2% | `request-verification` → código lido no Mailpit (`/api/v1/search?query=to:<endereço>`, depois a mensagem se o resumo não trouxer o código) → `verify-email`. Endereço sempre `soak-<sufixo>-<n>@example.com`; sem mensagem em `MAILPIT_TETO_S` (30 s) a iteração desiste (o e-mail perdido é conferido no fim) |
| escrita de catálogo | 1% | o host do seed cria `<PREFIXO>-W<n>` (`POST /bff/events`) e edita (`PUT`, com apresentador). Nenhuma rota promove usuário a host: o `preparar-soak.ps1` o faz por SQL |

### Passo a passo (um bloco)

Pré-requisitos no host: o cluster `soak` no ar e as duas regras permanentes de firewall já criadas (uma vez, até o fim dos testes; `Projeto/k8s/README.md`), `CARGA_K8S=kind-gerenciador` no ambiente do shell, `MAILPIT_AUTH` no ambiente (a mesma do `cluster.sh up`), `MAILPIT_URL`
(padrão `http://localhost:8025`), `Projeto/.env` e `Projeto/supabase/.env` presentes (nada deles é impresso). Nos dry-runs a credencial efêmera do Mailpit foi passada ao gerador por mensagem entre sessões do Claude Code, por decisão do usuário (o valor não consta em nenhum arquivo).

```powershell
# HOST (PowerShell, em Projeto/carga), nesta ordem. Os alvos do make chamam o bash do Git (o do PATH costuma ser o do WSL)
$env:CARGA_K8S = 'kind-gerenciador'
# $env:MAILPIT_AUTH ja esta no ambiente (a mesma do cluster.sh up)
# $env:GERADOR_IP = o IP ATUAL do gerador (ipconfig la; DHCP: ja foi 192.168.68.54/22 e pode mudar). Mudou desde o ultimo bloco? Rode de novo, como administrador:
#   .\soak\firewall-mailpit.ps1 -Acao Criar -GeradorIp $env:GERADOR_IP      (fail-safe: com o IP velho o gerador fica bloqueado, a porta nao abre; o pre aborta pedindo isso)
# GERADOR_OFFSET_S: no GERADOR, w32tm /stripchart /computer:time.windows.com /samples:1 /dataonly e cole o numero apos a virgula COMO O W32TM IMPRIME (ex. -01.3043740s; virgula decimal vale). Ele imprime referencia - local, entao negativo = relogio ADIANTADO; o pre inverte o sinal ao gravar (+ = adiantado)
# (0) o cluster ja subiu com o gatilho do bloco: ADMISSION_X_RPM=56000 (S1) ou 4000 (S2) no ambiente do cluster.sh up, e o admission reiniciado se o X mudou (k8s/README.md)
make limpar-banco                                   # 1. banco limpo (modo cluster); depois espere readyReplicas=1 em todos os Deployments com ScaledObject
.\soak\preparar-soak.ps1 -Id S1 -Bloco S1 -Semente 4 -Teto 35000 -HostIp <ip do host na LAN> -MailpitUrl http://<ip do host na LAN>:8025   # 2. perfil + seed (S1: 2000 usuarios x 2000 eventos de 200 vagas, semente 4 pela regra da escolha; S2: 500 vagas + quentes) + host por SQL; imprime a linha do gerador
make limpar-mailpit                                 # 3. Mailpit vazio (DELETE /api/v1/messages; o seed nao manda e-mail, mas e depois dele que vale)
make verificar-soak-pre SOAK_ID=S1 SOAK_BLOCO=S1 GERADOR_IP=$env:GERADOR_IP GERADOR_OFFSET_S=<offset do w32tm no gerador>   # 4. GERADOR_IP = o IP ATUAL do gerador (DHCP). Aborta (codigo 1) se algo estiver errado: e-mail real, sem #231, replica != 1, X/alvo do KEDA que nao e o do bloco, regras de firewall da 8025 (Block e Allow do gerador) fora do esperado, Mailpit pequeno, sem 401 ou nao vazio
make amostrar-soak SOAK_ID=S1                       # 5. em um TERMINAL PROPRIO durante o bloco (replicas, HPA e pods a cada 5 s); Ctrl+C ao fim
make coleta-host ROTULO=soak-S1                     #    em outro terminal; a coleta do kit em modo cluster
```

```powershell
# GERADOR: as linhas que o preparar-soak.ps1 imprime (git checkout do commit com o perfil, MAILPIT_AUTH digitada com Read-Host -AsSecureString (sem eco), $perfil, k6 run ...).
# Resumo do que ela faz: k6 run --quiet --no-thresholds --summary-mode=disabled --out csv=soak-S1-k6.csv.gz -e 'BASE_URL=...' -e 'SUFIXO=...' ... Projeto/carga/soak/carga-soak.js
# k6 >= 1.3 (o gerador tem a v2.2.0): --summary-mode=disabled (K6_SUMMARY_MODE=disabled); --no-summary e K6_NO_SUMMARY foram REMOVIDOS na v2.0.0. Sem thresholds e sem resumo o k6 nao acumula metricas em memoria.
# O bruto vai direto para .csv.gz: vários GB por bloco sem compressao (estimativa ~10 a 15 GB no S2; ~1 a 2 GB em gz); o gerador tem 1,5 TB livres, confira o espaco antes.
python soak/agregar.py soak-S1-k6.csv.gz --id S1     # Python 3.14.7 do gerador (python/py/python3); le o .gz por streaming; grava soak-S1-por-minuto.csv (so ele e o perfil entram no repo; o bruto fica no gerador)
```

```powershell
# HOST, ao fim do bloco (copie o soak-S1-por-minuto.csv do gerador para Projeto/carga/results/); pare o amostrar-soak e a coleta (Ctrl+C)
make verificar-soak-pos SOAK_ID=S1 SOAK_BLOCO=S1 SOAK_SUFIXO=<SUFIXO> SOAK_PREFIXO=Soak-<SUFIXO>
```

**Relógios:** o `inicio_s` do por-minuto vem do relógio do gerador (k6) e as amostras de pods do `amostrar` vêm do relógio do host (e os timestamps do kubelet, da VM do Docker); a ligação minuto ↔ pod que saiu usa ±5 s. Na partida de cada bloco o `verificar-soak pre` **grava no log do bloco** (não sincroniza nada) o offset dos três relógios contra a mesma
referência (`time.windows.com`): host (`w32tm /stripchart /computer:time.windows.com /samples:1 /dataonly`), nó do kind (`docker exec <cluster>-control-plane date -u`, contra o do host, no ponto médio da chamada) e gerador (`GERADOR_OFFSET_S`, medido lá com o mesmo `w32tm`). Diferença relativa > 5 s **só avisa** (o pre não aborta). Com deriva maior, o "nenhum pod saiu neste minuto" do relatório pode estar errado: `w32tm /resync` nos três e rode o pre de novo.

### Fim dos testes

Depois do último bloco, como administrador no host: `.\soak\firewall-mailpit.ps1 -Acao Remover` (apaga as duas regras permanentes da 8025, o `Block` e a `Allow` do gerador, e confirma que `Get-NetFirewallRule` não acha nenhuma). Derrube o cluster (`cluster.sh down`) e apague o CSV bruto do gerador.

**`MAX_VUS` (padrão 2000) e a RAM do gerador:** o k6 entrega a cada VU uma **cópia** do retorno do `setup()` (as 2000 sessões, ≈ 1,5 a 2 MB), além de ~1 a 2 MB do próprio VU; com 2000 VUs isso passa de 3 a 4 GB só em dados. Dimensione `-MaxVus` pela RAM
livre do gerador (critério 4: livre ≥ 1 GB) e pelos VUs que o 202 acompanhado segura (≈ taxa de 202 por segundo × ≥ 1 s de consulta): o dry-run mede.

**`verificar-soak.sh pre --bloco S1|S2`** aborta (código 1) se: o `notification` **vivo** não tiver os seis valores do Mailpit (a entrada `MAIL_PASSWORD` tem de existir, vazia e sem `valueFrom`; a API do Kubernetes omite `value` vazio, então "ausente" e "vazio" são
distinguidos pelo nome) ou trouxer `SPRING_MAIL_*` / `SPRING_APPLICATION_JSON`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` com `spring.mail` (também como chave do Secret `env-notification`; só as chaves são lidas); o gatilho não for o do bloco (`ADMISSION_X_RPM` do Secret
`env-admission` ≠ 56000/4000, pod do `admission` que subiu antes do Secret, `targetValue` do ScaledObject do `reservation` ≠ 0,5·X); as duas regras permanentes de firewall da 8025 não conferirem (`conferir-firewall.ps1` pelo `powershell.exe`, sem administrador: existem, habilitadas, entrada, TCP 8025, perfil Any; `Block` com `RemoteAddress` exatamente o complemento do IP ATUAL do gerador (`GERADOR_IP`) **mais todo o IPv6** (`::/0` ou `::-ffff:…:ffff`; qualquer outra forma IPv6 reprova); `Allow` (`TCC soak mailpit 8025 - gerador`) com `RemoteAddress` exatamente o IP do gerador, um item só; se o IP do gerador mudou (DHCP) ou a `Allow` não existe, aborta com "rode o Criar de novo" como administrador); a API do Mailpit não responder 401 a um GET sem credencial; existir ScaledObject do `stock` ou o Hikari do `stock` for diferente de 245 (**a #231 não foi aplicada**); algum Deployment autoescalado tiver `replicas` ou `readyReplicas` diferente de 1; o total esperado de
cadastros do perfil × 2 passar do `MP_MAX_MESSAGES` do Mailpit aplicado (o do render do overlay `soak`; cobre o "total > `MP_MAX_MESSAGES`"); o Mailpit não estiver vazio (registra `mailpit_inicio=0`).
**`pos`**, nesta ordem: (1) espera o lag do group `notification` chegar a 0 e registra `lag_notification=0`; (2) conta o Mailpit filtrado pelo sufixo; (3) confere o `From` de **todas** as mensagens (`@example.com`); (4) `verificar-rpm`
(overselling, código 3; sagas, código 4; sobem como estão); (5) os critérios 1 a 3 e a fila (S2: 202 obrigatório, 429 só registrado); se o critério 3 reprovar, lista os minutos com 5xx/status 0 por tipo e, ao lado, os pods que saíram neles (horário, serviço, nome; das amostras do `amostrar`); (6) só então o bloco seguinte roda o `limpar-banco`. O registro vai para `results/soak-<id>-verificacoes.log`.

### Critérios de aprovação (por bloco)

1. **Corretude:** overselling 0 e sagas convergidas (`verificar-rpm`). **S1:** overselling 0 *conferido sob confirmação em massa* (reserva espalhada, 60/10/30, 200 vagas por evento, ≈ 240 a 400 mil confirmações em 2 h conforme a semente; nas sementes altas a fronteira de capacidade é exercitada e o "lotado" é 400 de negócio, que não reprova; sem disputa concorrente pela última vaga), com a confirmação de verdade; **S2:** *convergência da saga* (hold/release por `holdId`, revive `cancelled → pending`, 0 órfãs) com o overselling *conferido, mas não estressado* (os quentes só cancelam: mix efetivo ≈ 3% confirma, 95,5% cancela, 1,5% expira; o overselling sob disputa é da R4/R5); no Mailpit, 1 e-mail por `request-verification` com 2xx (`verificacoes_2xx` do por-minuto) — **e-mail perdido sempre reprova**, duplicado só é tolerado a ±60 s de um pod do
   `notification` saindo (`deletionTimestamp` ou `restartCount` que subiu, das amostras de pods); todo `From` termina em `@example.com`; `restartCount` do Mailpit não sobe e o pod não é trocado no bloco.
2. **Autoescala acompanha a carga**, por serviço com HPA do KEDA, pela série `soak-<id>-replicas.csv` (métrica e alvo do HPA, `spec.replicas`): a métrica por pod passa de alvo × 1,1 (a tolerância do HPA) com réplicas < 3 → em até 60 s
   o serviço ganha réplica; a carga total (réplicas × métrica) cabe em 1 pod no alvo por pelo menos 5 min → volta a 1 em até 5 min. Episódios mais curtos que o prazo não são avaliados (o `crit2` avisa se nenhum foi: critério vácuo). O `stock`
   (sem HPA) deve ficar em 1 o bloco inteiro. **No S1 o `reservation` fica fora** (X=56k: o alvo do KEDA, 28k, nunca é alcançado de propósito; ele fica em 1 réplica).
3. **Troca de réplica sem erro:** 0 respostas 5xx **e 0 com status 0** (erro de transporte do k6; coluna `c0`) em todos os tipos, exceto `tipo=mailpit`. É o que "troca de réplica sem erro" quer provar: a limitação do L4 do gateway pode dar status 0 quando um pod sai com keep-alive
   aberto, e isso é **achado real**, não exceção (decisão do usuário; trocar para "só registrar" é a constante `C0_REPROVA` em `criterios.py`). 202, 429 e os 400 de negócio não reprovam.
   **Fila:** no S2 a reserva precisa ter 202 (a fila ligou); 429 só é registrado (ver abaixo); no S1 só registra.
4. **Gerador válido** (RAM livre ≥ 1 GB, sem "Insufficient VUs" fora do S2, memória do k6 estável): conferido no gerador; o `descartadas` do por-minuto mostra as iterações descartadas. Não sai do `verificar-soak.sh`.

Limitação conhecida, que não reprova: as réplicas novas do gateway não recebem tráfego do k6 (conexões abertas; teste B do K2-diagnóstico).

**O 429.** O `admission` devolve 429 quando o usuário tem mais de 5 mensagens em espera (`max-waiting-per-user`, script Lua) ou a fila passa de `max-length` 100000. Em regime a espera por usuário é ≈ 0, então o kit tenta provocá-lo só por carga
(1 usuário insistente com 25% das reservas quentes: 42 a 59 req/s no pico do S2, o que enche 5 em espera se a latência da fila passar de ~85 a 120 ms). Se não sair, o critério do S2 fica **202 obrigatório, 429 só registrado**
(o `fila` imprime "0 respostas 429" sem reprovar).

### Dry-runs curtos (antes dos blocos de 2 h)

DOIS, em cluster `soak` de verdade, ≈10 min cada, com a #231 aplicada e critérios 1 a 3 verdes. Cada um sobe com o X do SEU bloco (`ADMISSION_X_RPM` no `cluster.sh up`, e `rollout restart deploy/admission` se o X mudou), como os blocos:

| | S1 (dry) | S2 (dry) |
|---|---|---|
| X / teto | 56000 / teto baixo (3000) | 4000 / teto ~20000, **perfil FIXO `dry-s2`** (`hot` de pico 0,95 × 6000 = 5,7k passa dos 3,2k que ligam a fila com `R`=1; no `dry-s2` abaixo de ~11,2k de teto a fila não liga: 3200 / (0,95 × 0,3)) |
| perfil | sorteado por semente (impressa) | fixo, 3 subidas vale → pico na reserva (abaixo) |
| reserva | espalhada, 60/10/30 (sem quentes), 200 vagas por evento | 1 quente (95%, só cancela) |
| o que prova | pipeline inteiro, confirmação real, `reservation` em 1 | **a troca de bloco** e **o critério da fila (202 obrigatório)** |

```powershell
# S1 (dry): cluster com ADMISSION_X_RPM=56000
.\soak\preparar-soak.ps1 -Id dryS1 -Bloco S1 -Teto 3000 -Duracao 600 -HostIp <ip do host> -MailpitUrl http://<ip do host>:8025 -Usuarios 200 -Eventos 200 -MaxVus 400
# ... pre (make verificar-soak-pre SOAK_ID=dryS1 SOAK_BLOCO=S1 GERADOR_IP=... GERADOR_OFFSET_S=...), amostrar, k6 no gerador, agregar, pos.

# TROCA DE BLOCO (a do S2): subir o cluster com o X novo (bloco Git Bash abaixo), reiniciar o admission (o envFrom nao recarrega) e conferir pelo pre
kubectl --context kind-gerenciador -n gerenciador rollout restart deploy/admission ; kubectl --context kind-gerenciador -n gerenciador rollout status deploy/admission
make limpar-banco ; .\soak\preparar-soak.ps1 -Id dryS2 -Bloco S2 -Teto 20000 -Fixo dry-s2 -HostIp <ip do host> -MailpitUrl http://<ip do host>:8025 -Usuarios 200 -Eventos 200 -MaxVus 800
make limpar-mailpit ; make verificar-soak-pre SOAK_ID=dryS2 SOAK_BLOCO=S2 GERADOR_IP=$env:GERADOR_IP GERADOR_OFFSET_S=<offset>   # o pre aborta se o X vivo, o pod do admission ou o alvo do KEDA nao forem os do S2
```

```bash
# TROCA DE BLOCO, Git Bash no host (o mesmo comando do bloco, com o X novo; MAILPIT_AUTH ja exportada): atualiza o Secret env-admission e o ScaledObject
ADMISSION_X_RPM=4000 K8S_OVERLAY=soak SLIM_TAG=k8s K8S_DB_REMOTO=192.168.68.50:5433 Projeto/k8s/cluster.sh up --trilha <b1|c|nenhuma>
```

**Perfil FIXO do dry-run do S2 (`-Fixo dry-s2`, `perfil.py --fixo dry-s2`).** Não é sorteado por semente (não existe semente do perfil: o `preparar-soak.ps1` recusa `-Semente` com `-Fixo`); os 5 tipos seguem a mesma tabela de 600 s, com rampa de 30 s no início de cada fase: vale (10% do teto do tipo) 0–60 s, pico (100%) 60–180 s, vale 180–300 s, pico 300–420 s, vale 420–480 s, pico 480–600 s. Com teto 20000 a reserva (30% = 6000 RPM) tem vale de 600 e pico de 6000: **3 subidas bruscas vale → pico** (aos 60 s, 300 s e 480 s), e o `hot` de pico é 0,95 × 6000 = 5,7k, acima dos 3,2k (0,8 × X × R, `R`=1) que ligam a fila; na rampa o `hot` passa dos 3,2k aos ≈ 15 s. A **1ª subida acontece com o `reservation` em 1 réplica**, antes de o KEDA subir a 2–3 (o alvo é 2000): é ela que garante os 202. As subidas 2 e 3 só dão 202 se o `reservation` tiver voltado a 1 réplica (a descida do KEDA leva a janela de 120 s + 1 pod a cada 60 s), então valem como chances extras. No pico, com `R`=2–3, `0,7·X·R` passa de `hot` e a fila desliga.

**Não troque de semente (não há): se o critério "202 obrigatório" der 0 de 202, é defeito do mecanismo (gatilho, `adm:hot`, fila); pare e relate.**

`-Seco` só gera o perfil e imprime o plano (sem seed, sem SQL). Com 600 s o perfil tem 3 a 4 fases por tipo. Para exercitar a autoescala, o teto e as fases curtas não bastam: confira o aviso "critério vácuo" do `crit2`.

**Portões que só dá para ver ao vivo (o selftest não os cobre), no roteiro dos dois dry-runs:**

1. `Get-NetTCPConnection -LocalPort 8025 -State Listen`: ver se a 8025 escuta também em `[::]` (por isso a regra Block cobre todo o IPv6).
2. O `pre` alcançando `localhost:8025` **com o Block ativo** (loopback não é filtrado por ele: o `GET` sem credencial tem de dar 401 mesmo assim).
3. `.\soak\firewall-mailpit.ps1 -Acao Status -GeradorIp $env:GERADOR_IP` logo depois do `Criar`: confere o **formato real** do `RemoteAddress` que o Windows devolve (faixa IPv6 ou `::/0`; na `Allow`, o IP puro) e que o `conferir-firewall.ps1` o aceita, e lista as outras `Allow` largas de rede ativa (a do Docker é Public e a rede do host é Private: não libera o gerador, por isso a `Allow` do kit).
4. De OUTRA máquina da LAN: `curl.exe -m 5 http://<host>:8025/api/v1/messages` = timeout (IPv4 e, se tiver, IPv6); do gerador, sem credencial = 401.
5. `descartadas` da reserva no por-minuto (iterações que o k6 descartou por falta de VU; no S2 toda 202 quente é acompanhada e segura o VU ≥ 1 s).
6. RSS e `restartCount` do Mailpit no S2 (`kubectl top pod`, `get pod mailpit`), com a taxa de cadastro do teto 20000.
7. Os relógios gravados no log do `pre` (host/nó/gerador) e o aviso se passarem de 5 s.

### Selftests (sem Docker, cluster, k6 nem Mailpit)

`make selftests` roda, além dos de antes: `python soak/perfil_selftest.py`, `python soak/agregar_selftest.py` (inclui `.gz`, `c0` e a amostra atrasada com 5xx/status 0 → linha `atrasada=1` no por-minuto, `crit3` reprova e código 3), `python soak/criterios_selftest.py` (inclui o `amostra.py`, o gatilho por bloco,
`spring.mail`, o `c0` e o relatório de pods), `node soak/carga-soak.selftest.mjs` (o script roda com stubs do k6:
Mailpit fora aborta o `setup()` sem nenhuma chamada ao BFF; credencial só no header; endereços só `@example.com`; `min`/`fase` por requisição; reserva quente), `node lib-sorteio.selftest.mjs`, `bash soak/verificar-soak.selftest.sh` (`kubectl`, `curl` e `verificar-rpm` falsos)
`soak/firewall-mailpit.selftest.ps1` (faixas complementares, a conferência do `Block` e da `Allow` do gerador propriedade a propriedade, o IPv6 total, o filtro de perfil ativo e de app UWP das `Allow` largas e `-WhatIf`, sem tocar no firewall; o `verificar-soak.selftest.sh` roda o `pre` com um `powershell.exe`, `w32tm` e `docker` falsos, inclusive os relógios) e `soak/lib-soak.selftest.ps1` (o padrão de quentes por bloco).
Os do `cluster.sh` (os seis valores do SMTP, ausente e errado; a poda do Mailpit fail-closed com lag > 0, ilegível e 0) estão no `Projeto/k8s/cluster.selftest.sh`.

### Pendências

- O cluster `soak` real, o firewall (timeout na LAN, 401 sem credencial do gerador, formato real do `RemoteAddress`) e os dois dry-runs ainda não rodaram; a #231 precisa estar aplicada.
- **A reserva exercita o `reservation` e a fila só no S2** (decisões 1 e 2 da spec): eventos quentes + X=4000; no S1 a reserva é espalhada (60/10/30 com confirmação) e o `reservation` fica em 1. O 429 depende de a latência da fila deixar um usuário com 5 em espera: se não sair, fica só registrado.
- **O cluster precisa subir com o X do bloco e o `admission` reiniciar ao trocar de bloco** (o `envFrom` não recarrega): o `verificar-soak pre` confere o X do Secret, a idade dos pods do `admission` e o alvo do KEDA.
- O `verificar-soak.sh pre` nao espera `readyReplicas=1`: aborta se algum Deployment autoescalado nao estiver em 1 pronta (espere antes de rodar o `make verificar-soak-pre`).
- O gerador tem Python 3.14.7 (`python`/`py`/`python3`): o `agregar.py` roda lá; o `perfil.py` roda em qualquer um dos dois (o perfil é determinístico, basta a semente).
- A busca `to:` do Mailpit é um `LIKE` sem índice no JSON do destinatário: com ≈100 mil mensagens cada busca varre a tabela (o Mailpit do soak tem até 2 CPU). Se virar gargalo, `MAILPIT_TETO_S` e a taxa de cadastro são os botões.

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
Auto-testes (sem rede/stack; `make selftests` roda os da Rodada 4): `bash ../../docs/carga/harness/report-matriz-selftest.sh` e
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

**Colunas do CSV** (as da Rodada 4 vêm depois, na seção própria): `escada,rpm_alvo,rpm_efetivo,duracao_s,reservas,erros_5xx,taxa_5xx,erros_5xx_confirma,outros,p50,p95,p99,p95_max_janela_30s,throughput,iteracoes_descartadas,braco,max_connections,pool_hikari,commit,mapeamento`

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
Padrão (12k…40k de 4k em 4k, 60 s, 200 usuários): 8 degraus, 208.008 pares, 1.093 eventos, ~11,6 min de k6 com `PCT_ACOMPANHA=0` (desde a #178 o padrão é 100 e o espaço sobe de 31 s para 46 s: ~13,5 min; 31 s
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
saída do k6 ≠ 0 também impede o CSV; os thresholds de p95/contagem do script são só declarativos (sempre verdadeiros, só materializam
as submétricas), então nenhum deveria reprovar, e o código 99 (thresholds cruzados) só avisa, **exceto** com a condição de parada ligada (`PARADA_P95_MS`, Rodada 4): lá o 99 é a parada esperada e o resumo traz `parada`.

Limites: os degraus são contíguos (só os 31 s ociosos, 46 s com o padrão da #178; sem resfriamento do host entre eles, então a degradação de um
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
| `degraus/*` | gerador | escada de carga (limiar X preliminar): `carga-degraus.js`, `rodar-degraus.ps1`, `lib-degraus.ps1` (plano, p95 em janelas de 30 s, linha do CSV; `lib-degraus.selftest.ps1` testa), `lib-mapeamento.js` (mapeamento iteração → par usuário/evento, `sequencial`/`intercalado`; `lib-mapeamento.selftest.mjs` testa com `node`), `lib-admissao.js` (202, acompanhamento do estado, parada; `lib-admissao.selftest.mjs`) e `carga-degraus.selftest.mjs` (o script inteiro contra um k6 de mentira) |
| `lib-servicos.ps1` | host | containers por label do compose, lista de `-f` do braço e Hikari esperado (`lib-servicos.selftest.ps1`) |
| `limpar-banco.sh` | host | limpeza entre blocos, ciente de réplicas (`limpar-banco.selftest.sh`; `make limpar-banco`); com `CARGA_K8S`, contra o cluster Kubernetes (#228, "Modo cluster") |
| `coleta-host.sh` | host | lags, outbox, pooler, `pg_stat_statements` e, desde a #178, fila, Redis por evento e réplicas × tempo (`coleta-host.selftest.sh`) |
| `../../docs/carga/harness/report-degraus.sh` | gerador | `make report-degraus`: `results\degraus-<id>.csv` → curva × carga + degrau X (SVG, bash+awk; `report-degraus-selftest.sh` testa) |
| `aleatorio/*` | gerador | teste 3 |
| `Makefile` | ambos | atalhos |
| `results/` | gerador | resultados: uma pasta por etapa (`NN-etapa/`) e, na raiz, a rodada corrente (ver "Resultados") |

Guia manual e tabela de registro do ambiente: `docs/carga/setup.md`.
