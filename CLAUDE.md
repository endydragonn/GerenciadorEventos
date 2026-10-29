# CLAUDE.md — Regras do projeto (Gerenciador de Eventos / TCC)

Instruções gerais válidas para todo o repositório. Cada parte tem seu próprio CLAUDE.md
com os detalhes técnicos:

**Sempre rodar /using-superpowers**
**Sempre rodar com Headroom ativo**

## Implementar issue / item do BACKLOG → SEMPRE pelo pipeline

Qualquer tarefa do tipo "implementar a issue #N", "fazer o item N do `BACKLOG.md`",
"resolver a issue X" **passa obrigatoriamente pelo skill `/implement-issue <N>`**, que
orquestra os 4 papéis:

| Papel | Quem |
|---|---|
| Orquestrador | a sessão principal (conduz, roda testes, relata — não escreve código de feature) |
| Gerador | subagent `code-generator` |
| Revisor | subagent `code-reviewer` |
| Validador de spec | subagent `spec-validator` |

Definições em `.claude/skills/implement-issue/SKILL.md` e `.claude/agents/*.md`.
Não implemente uma issue "na mão" na sessão principal pulando esse fluxo. O roadmap e a
numeração das issues estão em `BACKLOG.md` (item N do backlog = issue #N+1 no GitHub).

Exceção: issues de Fase 0 puramente de ambiente (instalar Docker, preparar VM de carga) são
passos manuais, sem diff — não precisam do pipeline.


- [`Projeto/Backend/CLAUDE.md`](Projeto/Backend/CLAUDE.md) — API Spring Boot
- [`Projeto/Backend/db/CLAUDE.md`](Projeto/Backend/db/CLAUDE.md) — banco PostgreSQL
- [`Projeto/frontend/CLAUDE.md`](Projeto/frontend/CLAUDE.md) — app Flutter Web

## Idioma

Código, comentários, nomes, mensagens de commit, PRs e documentação em **PT-BR**. Manter o
padrão do arquivo que você está editando.

## Git

- **Nunca** commit ou push direto em `main`. Tudo entra por PR.
- Implementação de issue (`/implement-issue`): branch `Issue-N` (uma por issue, criada no
  passo 3 com commit vazio + push). O **PR entra só no passo 7, como draft**; o passo 7.5
  avalia as reviews do Copilot e do Gemini (findings relevantes vão pro usuário) e o PR **sai
  do draft automaticamente** quando os findings das duas foram tratados + validador CONFORME.
  Commits, pushes e atualização do PR nessa branch são automáticos. **Mergear** o PR `Issue-N` → `main` **só
  quando o usuário pedir**.
- Fora desse fluxo (docs, config, etc.): **só faça commit ou push quando o usuário pedir**;
  antes de push, confirme.
- Mensagens no estilo do histórico (Conventional Commits): `feat:`, `refactor:`, `fix:`,
  `chore:`, `docs:`. Uma mudança lógica por commit.
- Feche a mensagem de commit com:
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`
- Corpo de PR termina com:
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)`
- **Nunca** versione: `Projeto/.env`, `**/target/`, `**/build/`, dumps de banco, relatórios
  gerados (`*-report-*.txt`, `database-export-*.txt`), e o estado local do Claude Code
  (`.claude/settings.local.json`, `.claude/*.local.*`).
- **Versione** `.claude/agents/` e `.claude/skills/` — são a estrutura de implementação do
  pipeline (ver acima), compartilhada com quem clonar o repo.

## Segredos

`Projeto/.env` contém credenciais **reais** (SMTP do Gmail, `JWT_SECRET` — sem uso desde a #36, chaves reCAPTCHA,
senha do Postgres). Nunca imprima o conteúdo, cole em PR/issue, mande para serviço externo,
ou registre em log. Para exemplos, use placeholders (`<...>`). O `.env` é lido por
`application.properties` e pelo `docker-compose.yml`; código-fonte lê via variável de
ambiente, nunca com valor hardcoded.

Os segredos do Supabase self-hosted ficam em `Projeto/supabase/.env` (gitignored, gerado a
partir do `.env.example` pelo `generate-keys.sh`), com a mesma política: nunca imprimir,
commitar ou registrar em log.

## Código

- Siga o estilo e a estrutura do código vizinho. Não reformate arquivos inteiros.
- **Não troque de abordagem/stack sem alinhar com o usuário.** Em especial: o backend usa
  JDBC puro de propósito (não Spring Data JPA); o schema mora em SQL nos scripts de init,
  não em `@Entity`. Não "migre" isso por conta própria.
- Não edite artefatos gerados nem código legado marcado como tal
  (ex.: `Projeto/frontend/blocoInteiro.dart` é referência histórica, não o app ativo).
- Mudou contrato de API, schema ou env var → atualize os 3 lados afetados
  (SQL / backend / frontend) e o CLAUDE.md da área.

## Rodar e testar

- Pré-requisito: `Projeto/supabase/.env` existir (o `db` é o `supabase/postgres` e lê os segredos de lá;
  gerar conforme `Projeto/supabase/README.md`).
- Stack completa: `docker compose -f Projeto/docker-compose.yml up -d --build`
  (frontend :8088, backend :8081, Postgres host :5433, Supabase Studio :8000 — login com
  `DASHBOARD_USERNAME`/`DASHBOARD_PASSWORD` de `Projeto/supabase/.env`; sobe junto `meta` e `kong`).
  O backend em Docker fala com o Postgres pelo `supabase-pooler` (Supavisor, sem porta no host);
  os testes e a porta 5433 vão direto no `db`. Sobe junto o `redpanda` (broker Kafka-compatível da Fase 6, issue #27;
  só `redpanda:9092` na rede `gerenciador-net`, sem porta no host) e o backend espera ele ficar `healthy`.
- Antes de considerar uma tarefa de backend pronta: `Projeto/Backend/run-tests.sh`
  (precisa dos containers `db`, `redpanda`, `redis` **e** `rabbitmq` no ar — `docker compose -f Projeto/docker-compose.yml up -d db redpanda redis rabbitmq`;
  os testes usam Postgres real, PG17 do Supabase, banco `meu_banco`, Redpanda real, Redis real (#41) e RabbitMQ real (#42); o script aborta com mensagem
  clara se faltar um deles). **Pare o `gerenciador-backend` antes** (`docker stop gerenciador-backend`): desde a #28/#29 o
  relay e o consumer dele compartilham o `meu_banco` e drenam o `event_outbox` dos testes; o script aborta com
  mensagem clara se o container estiver rodando. Desde a #32 o script roda o **reactor Maven inteiro** (`shared`, `reservation-client`,
  `stock-client`, `catalog-client`, `identity-client`, `admission-client`, `monolito`, `notification`, `reservation`, `stock`, `catalog`, `identity`, `admission`) e também aborta se o `gerenciador-notification`, o
  `gerenciador-reservation`, o `gerenciador-stock`, o `gerenciador-catalog`, o `gerenciador-identity` ou o `gerenciador-admission` estiverem rodando
  (`docker stop gerenciador-backend gerenciador-notification gerenciador-reservation gerenciador-stock gerenciador-catalog gerenciador-identity gerenciador-admission`; o notification
  consumiria o tópico dos testes e mandaria e-mail de verdade, o reservation, o catalog e o identity dividiriam o banco e o broker deles); ao fim o script também move o group `notification` para o
  fim do tópico (`rpk group seek`), para os eventos de teste nunca virarem e-mail quando o serviço subir (um e-mail real pendente
  antes da rodada é pulado). Ao fim o script também move os groups `reservation` e `stock` (tópico `reservation.wallet`) e `stock-catalog` (`catalog.event`; era `reservation-catalog` até a #47) para o fim dos tópicos
  `reservation.wallet` / `catalog.event` (a reconciliação criaria `eventcap` para ids de evento de teste). Banco já existente precisa dos
  schemas novos: aplicar `Projeto/Backend/db/migrations/20261024-notification-schema.sql`, **com o backend e o reservation parados**
  `20261025-reservation-schema.sql` e, **com o backend e o catalog parados e depois do backup
  `pg_dump -h localhost -U admin --clean --if-exists -n public meu_banco`** (backup, migração e rollback no cabeçalho do arquivo),
  `20261026-catalog-schema.sql` (move `event`/`eventpresenter` e o stream `catalog.event` para o schema `catalog`) e, **com o backend e o reservation parados e
  depois do backup `pg_dump -h localhost -U admin --clean --if-exists -n reservation meu_banco`** (backup, migração e rollback no cabeçalho do arquivo),
  `20261027-reservation-timestamptz.sql` (#58: `eventcap.buy_time_limit`/`walletevent.expires_at` em `timestamptz`, índice `idx_walletevent_event_id`,
  `capacity = -1 OR >= 1`; aborta se houver `capacity = 0`) e, **com o backend e o identity parados e depois do backup `pg_dump -h localhost -U admin --clean --if-exists -n public meu_banco`** (backup, migração e rollback no cabeçalho do arquivo),
  `20261028-identity-schema.sql` (#36: move `users`, `mywallet` e `pending_verifications` e o stream `identity.notification` para o schema `identity`, preservando `user_id` e a identity; aborta se houver FK de fora ou sobra com linha) e, **com o backend, o reservation, o catalog e o stock parados e depois do backup `pg_dump -h localhost -U admin --clean --if-exists -n reservation meu_banco`** (backup, migração e rollback no cabeçalho do arquivo), `20261029-stock-schema.sql` (#47: move `reservation.eventcap` para o schema `stock`, cria `stock.hold`, as colunas da saga em `walletevent`/`idempotency_key` e faz o backfill dos holds, com exatamente `capacity − quant` holds debitados por evento limitado; aborta se esse número passar das ativas); idempotentes; bancos novos já nascem com eles). O `run-tests.sh` **aborta** se o banco ainda tiver `public.event` (migração 20261026
  pendente) ou não tiver `catalog.event`, e se ainda tiver `public.users` (20261028 pendente) ou não tiver `identity.users`, e se ainda tiver `reservation.eventcap` (20261029 pendente) ou não tiver `stock.eventcap`/`stock.hold`. `TEST_DB_NAME` (default `meu_banco`) e `ENV_FILE` (default `Projeto/.env`) permitem rodar os testes num banco do mesmo container criado com os init scripts atuais (foi como a #47 foi testada sem tocar o `meu_banco` da R4). **Os testes dão `TRUNCATE ... RESTART IDENTITY` em `identity.users` e o JWT é validado sem estado (claim `uid`):** no início e ao fim da rodada o script apaga a chave de assinatura do volume `identity-keys` (identity e backend estão parados), e todo token antigo vira 401 — relogue depois de subir a stack. Limpeza de banco fora dos testes (carga, `TRUNCATE` manual) deve fazer o mesmo e reiniciar identity e backend (cache de JWKS do gateway de até 24 h).
- **`INTERNAL_API_TOKEN` (desde a #33, volta 3):** as chamadas internas gateway → reservation, gateway → catalog (`/internal/**`; #35) e gateway → identity (#36; só o JWKS é isento) exigem um token de serviço
  (header `X-Internal-Token`; 401 sem/errado). **Acrescente ao `Projeto/.env` (você mesmo; o repo nunca edita nem imprime o `.env`)**
  `INTERNAL_API_TOKEN=<valor aleatório>`, por exemplo gerado com `openssl rand -hex 32`. O compose repassa o MESMO valor ao `backend`, ao `identity`, ao
  `reservation`, ao `stock` e ao `catalog`. Não há valor default: sem a variável **qualquer `docker compose`** (inclusive o `up -d db redpanda` dos testes)
  recusa com mensagem clara (`${INTERNAL_API_TOKEN:?...}`), e os serviços também falham no boot. O `run-tests.sh` usa um valor de teste
  explícito (não é segredo).
- **`REDIS_PASSWORD` (desde a #41):** o Redis (`gerenciador-redis`, só o serviço `admission` o usa) exige senha, sem default. **Acrescente ao `Projeto/.env` (você mesmo; o repo nunca edita nem imprime o `.env`)**
  `REDIS_PASSWORD=<valor aleatório>`, por exemplo `openssl rand -hex 32`. Sem a variável **qualquer `docker compose`** (inclusive o `up -d db redpanda redis` dos testes) recusa com mensagem clara
  (`${REDIS_PASSWORD:?...}`); até lá, exporte-a na shell só para o comando (a shell vale mais que o `.env`). O `run-tests.sh` usa a do `.env` ou, se exportada, a da shell (`-e REDIS_PASSWORD`),
  exige o `gerenciador-redis` no ar, **aborta com o `gerenciador-admission` rodando** (`docker stop gerenciador-admission`) e dá `FLUSHDB` no Redis no início e ao fim da rodada (os ids de evento são reciclados
  pelo `RESTART IDENTITY`). O backend recebe `REDIS_PASSWORD` vazia (sobreposta no compose).
- **`RABBITMQ_USER` e `RABBITMQ_PASSWORD` (desde a #42):** o RabbitMQ (`gerenciador-rabbitmq`, fila de admissão do serviço `admission`; imagem sem management, sem porta no host, **sem volume**: reiniciar perde as mensagens em fila)
  exige usuário e senha, sem default. **Acrescente ao `Projeto/.env` (você mesmo; o repo nunca edita nem imprime o `.env`)** `RABBITMQ_USER=<nome>` e `RABBITMQ_PASSWORD=<valor aleatório>` (ex.: `openssl rand -hex 32`). Sem elas
  **qualquer `docker compose`** recusa com mensagem clara; até lá, exporte-as na shell só para o comando. O `run-tests.sh` usa as do `.env` ou, se exportadas, as da shell (`-e RABBITMQ_USER`, `-e RABBITMQ_PASSWORD`) e exige o
  `gerenciador-rabbitmq` no ar. O backend recebe as duas vazias (sobrepostas no compose). Os testes usam filas com prefixo próprio (nunca as `admission.*` do serviço).
- O serviço `admission` (#41, container `gerenciador-admission`, API interna `:8085` só na rede `gerenciador-net`, sem datasource) sobe junto com `up -d --build` (depende do `redis` e, desde a #42, do `rabbitmq` e do `reservation`); o `backend`
  **não** depende dele: o gateway conta cada tentativa de reserva por HTTP e, com o `admission` ou o Redis fora, faz fail-open (a reserva segue síncrona, com log). Desde a #42 ele também tem a fila de admissão e o worker pool, e desde a #43 o gatilho (uso de X por evento, histerese) os liga ao
  `POST /internal/admissions` (`ADMISSION_X_RPM`, `ADMISSION_RESERVATION_REPLICAS`, `ADMISSION_QUEUE_ON`, `ADMISSION_SYNC_BELOW`; o gateway responde `202` no modo fila). O e2e sobe `redis`, `rabbitmq` e `admission` isolados, com credenciais efêmeras e X baixo, e faz uma rajada concorrente (passo 4b).
- O serviço `notification` sobe junto com `up -d --build` (container `gerenciador-notification`); o `backend` não depende dele. O serviço
  `reservation` (container `gerenciador-reservation`, API interna `:8082` só na rede `gerenciador-net`) e o serviço `catalog` (#35, container
  `gerenciador-catalog`, API interna `:8083` só na rede `gerenciador-net`) e o serviço `identity` (#36, container `gerenciador-identity`, API interna `:8084` só na rede, chave do JWT no volume `identity-keys`) também sobem junto, e o `backend` **depende dos três**
  (`service_healthy`); o serviço `stock` (#47, container `gerenciador-stock`, API interna `:8086` só na rede, Hikari 250) também sobe junto e o `reservation` **depende dele** (`service_healthy`, que espera o one-shot `stock-seek`, análogo ao `notification-seek`: move o group `stock` para o fim de `reservation.wallet` só se o group não existe). `POST /bff/seed` fica **desligado** (404) no compose normal; o override de carga (`carga/docker-compose.carga.yml`)
  liga `SEED_ENABLED=true`.
- **Suíte e2e da stack inteira (#37):** `Projeto/e2e/run-e2e.sh` sobe tudo num project temporário `gerenciador-e2e` (rede e volumes próprios, sem
  porta no host, e-mail num captador Mailpit — nenhum e-mail real), roda cadastro (código lido do e-mail) → evento → reserva → confirmação e o replay
  de `catalog.event` e `reservation.wallet`, e derruba só esse project no fim (`MANTER=1` mantém). Confere o isolamento por `config` antes do `up`.
  Detalhes no `Projeto/Backend/CLAUDE.md`, "Suíte e2e da stack inteira". Os serviços do compose agora têm healthcheck (`backend`, `notification`,
  `frontend`), então `docker compose -f Projeto/docker-compose.yml up -d --build --wait` espera tudo ficar `healthy`.
- **Imagens enxutas com o Slim (#177, opcional):** `Projeto/slim/gerar-imagens-slim.sh` gera `gerenciador-eventos-<serviço>.slim` (backend, identity,
  catalog, reservation, stock, notification, admission) num project isolado `gerenciador-e2e-slim*` (nunca na stack real), e `docker-compose.slim.yml` as usa por
  override (`-f docker-compose.yml -f docker-compose.slim.yml`); a imagem original (Dockerfile intocado) segue o padrão. `E2E_SLIM=1 Projeto/e2e/run-e2e.sh`
  roda o e2e com elas. Tabela antes/depois, probe, includes e perfis Seccomp/AppArmor em `Projeto/slim/README.md`.
- **Primeiro `up` e o group `notification`:** o serviço one-shot `notification-seek` move o group `notification` para o fim de `identity.notification`
  SÓ se o group ainda não existe (stack nova não manda e-mail de evento antigo); o `notification` espera ele terminar. O SMTP do `notification` é
  parametrizável (`MAIL_HOST`/`MAIL_PORT`/`MAIL_SMTP_AUTH`/`MAIL_STARTTLS`; defaults = Gmail).
- Relate o resultado real dos testes; se algo falhou ou foi pulado, diga.

## Documentação de arquitetura (vault Obsidian)

`D:\Claude\Brain\01-Projects\Event-Management-System\` guarda as decisões de arquitetura do
TCC (ADRs). O roadmap de execução está em `BACKLOG.md` (raiz) e, navegável, na nota
`Roadmap-e-Issues.md` do vault. Issues #2–#47 no GitHub.

**Escrito no código (2026-09-01, ainda não compilado/testado — issue #5):** isolamento de
`eventcap` como agregado próprio, reserva por `UPDATE` condicional atômico (CAS),
`eventpresenter` como tabela filha, máquina de estados `walletevent`
(`pending → confirmed / expired / cancelled`) com job `@Scheduled` de expiração.

**Implementado (Fase 4, 2026-10-16):** Supabase self-hosted enxuto — `supabase-db`
(`supabase/postgres` 17) no lugar do `db`, `supabase-pooler` (Supavisor, transaction mode) entre o
backend e o Postgres, e Studio com a cadeia mínima. O ponto 1 da série de 3 (monólito CRUD +
pooler) foi medido com isso (`docs/artigo/experimento-monolito.md`).

**Implementado (Fase 5, 2026-10-18):** o monólito foi dividido em módulos por serviço, ainda no
mesmo processo, deploy e schema `public`:
- `identity`, `catalog`, `reservation`, `notification` e um `shared` mínimo, cada um com pacote `api`;
- `gateway` com os controllers `bff/*`;
- ArchUnit (`ModuleBoundaryArchTest`) trava as chamadas entre módulos fora de `api` e `@RestController`
  fora do `gateway`;
- `Idempotency-Key` no `POST /bff/event-wallets` (tabela `idempotency_key`).

Detalhes no `Projeto/Backend/CLAUDE.md`.

**Implementado (Fase 6, issue #27, 2026-10-18):** só a infra do backbone de eventos, ainda sem domínio — broker
Redpanda no compose e, em `shared/events`, `event_store` imutável + `event_outbox` + relay para o broker, base do
consumer idempotente (checkpoint atômico), replay em schema-sombra e snapshots.

**Implementado (Fase 6, issue #28):** `catalog` em CQRS + Event Sourcing — stream `catalog.event`
(`EventCreated`/`EventUpdated`/`PresenterAdded`/`PresenterRemoved`/`EventCancelled`), append na mesma transação da
projeção inline `event`/`eventpresenter` (coluna `event.version` como versão esperada; conflito = repete o command),
`image_data` fora do ES, replay do agregado comparado com a viva, relay ligado no compose (`EVENTS_RELAY_ENABLED`).
`/bff/events/**` inalterado. Detalhes no `Projeto/Backend/CLAUDE.md`, "Catalog em CQRS + Event Sourcing".

**Implementado (Fase 6, issue #29):** o lado wallet de `reservation` em event sourcing (híbrido) — stream
`reservation.wallet` (`SlotReserved`, `ReservationConfirmed`, `ReservationExpired`, `ReservationCancelled`) com a versão
vinda da guarda (`UPDATE walletevent ... RETURNING version`) e o append na mesma transação; `eventcap` + CAS continuam
relacionais. A disponibilidade do braço `naive` virou projeção assíncrona (consumer do tópico; `event_availability` só com
`ativos`, disponibilidade calculada na leitura) e o `ReadModelRefreshService` saiu: o lag deixou de ser variável controlada e
passou a ser medido (pré-requisito da Rodada 2, #31). O compose liga o relay (desde a #28) e o consumer (desde a #29) no backend.

**Implementado (Fase 6, issue #30):** checkpoint — `catalog` e `reservation` event-sourced, contrato `/bff/**` inalterado
(`HttpContractTest`), replay dos dois agregados coberto (`CatalogEventSourcingTest`, `ReservationReplayTest`) e teste
ponta a ponta do braço `naive` lendo a projeção real (`EventAvailabilityProjectionTest`). Diagrama command / evento /
projeção / replay em [`docs/arquitetura/fase6-cqrs-es.md`](docs/arquitetura/fase6-cqrs-es.md). A Rodada 2 (#31) mede o resultado.

**Implementado (Fase 7, issue #32):** primeira extração de microsserviço, o `notification`. O backend virou **Maven
multi-módulo** (`Projeto/Backend/pom.xml` agregador; módulos `shared` = lib, `monolito` = o app de antes, `notification`), com
**Dockerfile e imagem por serviço** (`monolito/Dockerfile`, `notification/Dockerfile`) e container próprio no compose
(`gerenciador-notification`, sem porta). O monólito **deixou de enviar e-mail**: `identity` appenda um evento no stream
`identity.notification` (event_store + outbox, na mesma transação do cadastro/código) e o serviço, consumer idempotente do
tópico com schema próprio (`notification.processed_event`), monta e manda o e-mail por SMTP. Queda do `notification` não afeta
cadastro/login (o e-mail sai quando ele volta). `/bff/**` inalterado. Detalhes e a conta do pool Hikari/Supavisor no
`Projeto/Backend/CLAUDE.md`, "Serviço notification"; layout em "Layout multi-módulo Maven".

**Implementado (Fase 7, issue #33):** segunda extração, o `reservation` (API interna autenticada por `INTERNAL_API_TOKEN`) — `eventcap` (CAS) + `walletevent` (ES) viraram o serviço
`reservation` (módulo Maven próprio, **Dockerfile, imagem e container próprios**, schema `reservation`, **event store e relay próprios**,
`ReservationExpiryService`, retenção das chaves de idempotência e a projeção de disponibilidade dentro dele). O `gateway` (monólito) o chama
por **API interna HTTP** (`/internal/**`, só na rede `gerenciador-net`) com um cliente de pool dimensionado (lib `reservation-client`): braço
`cas` em **uma** chamada (CAS atômico no serviço), braço `naive` em **duas** (disponibilidade e depois a reserva — a janela check-then-act
inclui a rede). O `eventcap` nasce por chamada síncrona do catalog depois do commit do evento (idempotente por `event_id`, reconciliado pelo
`EventCreated` de `catalog.event` se a chamada falhar — sem atomicidade distribuída, janelas documentadas). Sem FK cross-schema; nenhum serviço
lê tabela de outro; `mywallet` ficou no `identity`. `/bff/**` inalterado. Configuração do caminho quente no serviço (Tomcat 300, Hikari 1000, virtual
threads off), Hikari 350 no monólito, e a conta do pool do Supavisor (mesmo tenant: o pool real não cresce; 1000 + 350 + 3 <= 1500) no
`Projeto/Backend/CLAUDE.md`, "Serviço reservation"; diagrama em [`docs/arquitetura/fase7-servicos.md`](docs/arquitetura/fase7-servicos.md).
O kit de carga aponta para o schema/serviço novos (`Projeto/carga/README.md`); a Rodada 3 (#34) mede.

**Medido (Fase 7, Rodada 3, issue #34, 2026-10-24):** o ponto 3 (`reservation` e `notification` como serviços), no cabo e em SSD, ficou com
X ≈ 58k RPM, overselling 0 no CAS (20 de 20) e naive no teto; controle do ponto 2 no cabo em `Projeto/carga/results/04b-fase6-es-cabo`, resultados em
`Projeto/carga/results/05-distribuido` e seção "Ponto 3" em `docs/artigo/experimento-monolito.md`.

**Implementado (Fase 7, issue #35, 2026-10-24):** terceira extração, o `catalog` — `event`/`eventpresenter` (CQRS + ES, stream
`catalog.event`) viraram o serviço `catalog` (módulo Maven, Dockerfile, imagem e container `gerenciador-catalog`, API interna `:8083` só na
rede, schema `catalog` com **event store e relay próprios**; migração `20261026-catalog-schema.sql`). O `gateway` o chama pela lib
`catalog-client` (`/internal/**` com `X-Internal-Token`; cadeia gateway → catalog → reservation nas leituras), segue validando JWT e host,
amarra `creator_id` (integer, sem FK) ao token e exige o dono em PUT/DELETE/imagem (403), e compõe o `creator_name` (lote ao `identity` +
cache). Catalog fora → 503 (inclusive na reserva, salvo positivo de `exists` ainda no cache de 30 s do gateway). `POST /bff/seed` desligado por padrão (`SEED_ENABLED`). (JWT assimétrico + JWKS: #36, abaixo.)
Detalhes no `Projeto/Backend/CLAUDE.md`, "Serviço catalog"; diagrama em [`docs/arquitetura/fase7-servicos.md`](docs/arquitetura/fase7-servicos.md);
smoke da stack real em `docs/testes-manuais/smoke-catalog-servico-separado.sh`.

**Implementado (Fase 7, issue #36, 2026-10-27):** quarta extração, o `identity` — `users`, `mywallet` (junto, pela FK + trigger 1:1) e
`pending_verifications` viraram o serviço `identity` (módulo Maven, Dockerfile, imagem e container `gerenciador-identity`, API interna `:8084` só na
rede, schema `identity` em CRUD com **event store e relay próprios**: `identity.notification` congelado e `identity.user` de ciclo de vida; migração
`20261028-identity-schema.sql`). O serviço faz login (BCrypt), cadastro em duas fases, MFA, troca de senha, reCAPTCHA e **emite o JWT RS256** (`kid`,
claims `sub`, `uid`, `isHost`, `iss`; chave RSA gerada no 1º boot e guardada no volume `identity-keys`, sem variável nova no `.env`; `JWT_SECRET`
saiu de uso) com `GET /.well-known/jwks.json`. O `gateway` (agora o único conteúdo do monólito) o chama pela lib `identity-client` e valida o token
**sem estado** pelo JWKS em cache (24 h por `kid`, 1 fetch a cada 30 s, tolerante a queda): `currentUserId()` é a claim `uid` e host é a claim
`isHost`, sem consulta ao banco (some o `LOWER(email)` por reserva da #57). Identity fora → 503 em login/cadastro/`/bff/users/**`/`/bff/my-wallets/**`,
mas quem tem token válido segue reservando. Consequências aceitas da validação sem estado: desativar conta, trocar senha ou mudar `host` só valem para
tokens novos; `uid` inexistente não dá mais 401 em `event-wallets`. `TRUNCATE ... RESTART IDENTITY` recicla o `user_id`, então `run-tests.sh` e a limpeza
da carga apagam a chave do JWT (e o backend reinicia, por causa do cache do JWKS). `/bff/**` com o mesmo contrato (`HttpContractTest`); tokens HS256
antigos viram 401. Hikari: identity 100, backend 10 (soma no Supavisor 1000 + 10 + 3 + 100 + 100 = 1213 <= 1500). Detalhes no
`Projeto/Backend/CLAUDE.md`, "Serviço identity"; spec em `docs/superpowers/specs/2026-10-26-identity-servico-design.md`; diagrama em
[`docs/arquitetura/fase7-servicos.md`](docs/arquitetura/fase7-servicos.md); smoke da stack real em
`docs/testes-manuais/smoke-identity-servico-separado.sh`.

**Implementado (Fase 7, issue #41, 2026-10-28, Batch 2):** primeira peça da camada de alta carga — o serviço `admission` (módulo Maven, Dockerfile, imagem e container `gerenciador-admission`, `:8085` só na rede, **sem
datasource**) mantém um **contador de RPM por evento em Redis** (buckets de 50 ms numa janela de 200 ms, script Lua = 1 ida) e o `gateway` o chama **uma vez por `POST /bff/event-wallets`**, nos dois braços, depois das
validações e antes do `reservation`, pela lib `admission-client` (HTTP, timeouts curtos, **fail-open** e disjuntor; o `backend` não depende do `admission`). Na #41 o modo devolvido é sempre `sync` e o RPM é só sinal de carga
(`HttpContractTest` sem mudança de expectativa, overselling 0 no `cas`); sem `Idempotency-Key` na requisição o gateway gera um UUID e o manda SÓ ao `admission` (o `reservation` segue recebendo a chave do cliente, ou nenhuma: o caminho síncrono é o de antes). Redis no compose com senha obrigatória (`REDIS_PASSWORD`,
sem default), sem persistência, `maxmemory`, sem porta no host. A fila (RabbitMQ) e o worker pool são a #42 e o gatilho por uso de X, a #43. Detalhes no `Projeto/Backend/CLAUDE.md`, "Serviço admission"; spec em
`docs/superpowers/specs/2026-10-28-admission-servico-design.md`; diagrama em [`docs/arquitetura/fase7-servicos.md`](docs/arquitetura/fase7-servicos.md).

**Implementado (Fase 7, issue #42, 2026-10-28, Batch 2):** a fila da camada de alta carga — o `admission` ganhou o **RabbitMQ** (`gerenciador-rabbitmq`, tag fixa sem management, sem porta no host, **sem volume**) com a topologia declarada
por ele (exchange topic `admission`, fila `admission.requests` com TTL, `max-length` + `reject-publish` e dead-letter `admission.dlx` → `admission.timed_out`), o **caminho de enfileirar** (`FilaDeAdmissao.enfileirar`: resultado `queued` + espera + limite
por usuário num script Lua, publicação com confirms e `mandatory`, desfazer, 422/429; **sem ligar ao `POST /internal/admissions`**, que seguia devolvendo `sync`: o gatilho é da #43, abaixo), o **worker pool** (`@RabbitListener` com ack manual, concorrência
fixa 32 = teto de CAS simultâneos, prefetch 1, chama `POST /internal/reservations?path=cas` pelo `ReservationClient` com a `Idempotency-Key` da mensagem; 201 → `pending`, 4xx de negócio → `rejected`, reservation fora/5xx/exceção → backoff e nack com
requeue), a **dead-letter** reconciliada pelo `replay` (`pending` se há reserva com a chave, senão `timed_out`) e o **resultado consultável** no Redis (`GET /internal/admissions/result`). Redis do compose: 512 MB `volatile-ttl`. Credenciais novas no `Projeto/.env`:
`RABBITMQ_USER` e `RABBITMQ_PASSWORD`. Detalhes no `Projeto/Backend/CLAUDE.md`, "Fila de admissão (RabbitMQ) e worker pool — issue #42"; diagrama em [`docs/arquitetura/fase7-servicos.md`](docs/arquitetura/fase7-servicos.md).

**Implementado (Fase 7, issue #43, 2026-10-28, Batch 2):** o gatilho do modo de alta carga — no script Lua do contador do `admission`, **por evento**, `uso = RPM ÷ (X × réplicas)` (`ADMISSION_X_RPM` 56000, `ADMISSION_RESERVATION_REPLICAS` 1): uso ≥ 0,80
liga a fila (`ADMISSION_QUEUE_ON`) e volta ao síncrono só com uso < 0,70 (`ADMISSION_SYNC_BELOW`) **e** a espera do evento vazia (histerese). Em modo fila o `POST /internal/admissions` enfileira (`FilaDeAdmissao`, #42) e devolve `queued`; o `gateway` responde
**`202 Accepted`** (`Idempotency-Key`, `Retry-After: 1`, corpo `queued`; 422/429 do `admission` repassados) e o cliente consulta **`GET /bff/event-wallets/admission?idempotencyKey=`** (autenticado, 404 igual para inexistente e de outro usuário, 503 com o
`admission` fora). **Abaixo do gatilho nada muda** (201, corpo e headers de antes; `HttpContractTest` sem mudança de expectativa nos casos existentes, aditivo no 202/429/GET) e a fila termina no mesmo `pending` do caminho síncrono (a confirmação segue no
`/confirm`). Só o braço `cas` enfileira. No fail-open do `cas` o gateway manda a chave efetiva ao `reservation` (deduplica se a mensagem também for processada). A UI de `pending` é a #44 (o contrato novo está no `Projeto/frontend/CLAUDE.md`);
o e2e ganhou o passo 4b (rajada concorrente, fila drenada, overselling 0 contra o `reservation` REAL; com `E2E_RAJADA_USUARIOS` alto é o dry-run acima do limiar). Detalhes no `Projeto/Backend/CLAUDE.md`, "Gatilho, modo fila e 202 — issue #43"; diagrama em
[`docs/arquitetura/fase7-servicos.md`](docs/arquitetura/fase7-servicos.md).

**Implementado e medido (Batch 1, 2026-10-26 a 2026-10-28, branch de integração `Batch_1`):** além da #36, o compose final e a suíte e2e
(#37), o harness definitivo (#38: cenário de leitura, `coleta-host.sh`; #113: escada `MAPEAMENTO=sequencial|intercalado`) e os follow-ups de
corretude e segurança (#56, #57, #58, #59, #60, #61, #89, #133; #81 fechada sem PR). A Rodada R mediu a configuração final em
`Projeto/carga/results/06-batch1-final`: CAS com overselling 0 (matriz e aleatório), naive no teto, **X definitivo = 56k RPM por evento**
(escada sequencial, linha quente do `eventcap`; #40) e ≈ 86k no intercalado (teto do host). A comparação das 3 configurações (#39) está em
`docs/artigo/experimento-monolito.md` (tabela e `docs/artigo/graficos/`). Rodando escada no host: **suba a escada aos poucos**, porque um 1º
degrau já alto, a frio, enfileira na linha do `eventcap` e não se recupera (ver o README da etapa 06).

**Implementado (issue #111, 2026-10-28, Batch 2):** autoescala de `gateway` (`backend`) e `reservation` pela regra 50/80, opção A da ADR `docs/adr/Architecture-Decision-Autoescala.md` — um override
**opcional** `Projeto/docker-compose.autoescala.yml` (réplicas sem `container_name`/porta, HAProxy `lb` que publica a 8081 e balanceia `lb:8082` para o reservation, Hikari 300 por réplica: 3×300 + 3×10 + 3 + 100 + 100 = 1133 <= 1500)
e um scaler em bash no host (`Projeto/autoescala/scaler.sh`: stand-by com peso 0 no lb, promoção a 0,80, drain antes de descer, CSV réplicas × tempo, selftest sem Docker). O `admission` lê as réplicas ativas da chave
`adm:replicas` (escrita pelo scaler, com TTL; sem ela vale `ADMISSION_RESERVATION_REPLICAS`) no mesmo script Lua do gatilho e publica o RPM do evento mais quente em `adm:hot`. O compose base, o `run-tests.sh` (que agora também vê as
réplicas pelo label), o kit de carga e o braço sem autoescala não mudam; jobs com 2+ réplicas (expiração, retenção, relay) provados em teste (`Projeto/Backend/CLAUDE.md`, "Réplicas: estado em memória e jobs" e "Autoescala"). Operação em
`Projeto/autoescala/README.md`. A medição é da Rodada 4 (#179); o kit de carga com nomes sem `gerenciador-*` é da #178; Kubernetes (opção D) é a #197.

**Implementado (issue #112, 2026-10-28, Batch 2):** a autoescala (mesmo mecanismo da #111, regra 50/80) estendida a `identity`, `catalog`, `admission` e `notification`. Corretude com N réplicas provada em teste (chave do JWT criada com `link(2)` e igual em todas as réplicas, command do catalog sob concorrência, contador de RPM do admission em 2 réplicas, rebalance sem e-mail duplicado
nem projeção em dobro); `lb` (HAProxy) com `catalog` `:8083`, `identity` `:8084` e `admission` `:8085` (o gateway os chama por ele, o JWKS inclusive); scaler com a mesma decisão para os 6 serviços (HTTP por req/s da seção do lb; o `notification` pelo lag do group, sem stand-by, no máximo uma réplica por partição lida do broker); one-shot `particoes` do override leva
`identity.notification` e `reservation.wallet` a 3 partições (**irreversível na stack real**: a Rodada 4 roda o braço sem autoescala antes); Hikari 50 por réplica em identity/catalog e 10 workers por réplica no admission (pool 1239 <= 1500). Dry-run em project isolado com Mailpit (overselling 0, nenhum e-mail duplicado). Detalhes no `Projeto/Backend/CLAUDE.md`,
"Autoescala dos outros quatro", e em `Projeto/autoescala/README.md`; capacidades PROVISÓRIAS e a medição ficam na Rodada 4 (#179).

**Implementado (issue #47, 2026-10-29, Batch 2):** a saga `stock` + `wallet` — o `reservation` foi dividido: o `eventcap` (CAS), um ledger de holds (`stock.hold`) e a API `/internal/eventcaps/**` viraram o serviço **`stock`** (módulos Maven `stock` e `stock-client`, Dockerfile, imagem e container `gerenciador-stock`, `:8086` só na rede, schema `stock`, **uma réplica fixa**, Hikari 250 literal; migração `20261029-stock-schema.sql`), e o `reservation` (mesmo nome, container e porta) é o lado `wallet` e o **orquestrador síncrono** da reserva: tx1 (chave `reserving`), `POST /internal/holds` no `stock` **sem conexão aberta**, tx2 (`reserved` + `walletevent` + `SlotReserved{holdId}`), com compensação inline (`release(holdId)`, lápide contra o "reservar" atrasado) e um **reaper** de órfãs (`reserving`/`compensating` velhas, `FOR UPDATE SKIP LOCKED`). Cancelar e expirar só appendam o evento com o `holdId`; a vaga volta pelo `StockReleaseConsumer` do `stock` (group `stock`, idempotente por `holdId`). `/bff/**` inalterado (`HttpContractTest`), `admission` sem mudança de código, a reconciliação do `eventcap` mudou para o `stock` (group `stock-catalog`). Soma do Supavisor 1463 (base) e 1489 (override de autoescala), folga de 11. Testes com o `stock` REAL (W1–W13, S1–S9), e2e com os passos (a) stock fora e (b) queda do `reservation` no meio da rajada. Detalhes no `Projeto/Backend/CLAUDE.md`, "Serviço stock e saga stock + wallet"; spec em `docs/superpowers/specs/2026-10-29-saga-stock-wallet-design.md`; diagrama e janelas de falha em [`docs/arquitetura/fase7-servicos.md`](docs/arquitetura/fase7-servicos.md). A medição é da Rodada 5 (#180), sobre a mesma configuração da R4.

**Decidido, não implementado:** extrair o `gateway` como microsserviço (Opção B; o `notification` saiu na #32, o `reservation` na #33,
o `catalog` na #35, o `identity` na #36 e o `admission` nasceu na #41); a camada de alta carga (Redis RPM + fila + worker pool) existe: o contador de RPM
(`admission` + Redis) desde a #41, a fila (RabbitMQ) e o worker pool desde a #42 e o gatilho por uso de X (histerese, `202` no gateway, consulta de estado) desde a #43; a UI de `pending` no Flutter existe desde a #44 (`Projeto/frontend/CLAUDE.md`); falta a medição (Rodada 4, #179) — ver
`Architecture-Decision-Microservices-Split.md` e o `BACKLOG.md`.
