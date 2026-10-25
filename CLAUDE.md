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

`Projeto/.env` contém credenciais **reais** (SMTP do Gmail, `JWT_SECRET`, chaves reCAPTCHA,
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
  (precisa dos containers `db` **e** `redpanda` no ar — `docker compose -f Projeto/docker-compose.yml up -d db redpanda`;
  os testes usam Postgres real, PG17 do Supabase, banco `meu_banco`, e Redpanda real; o script aborta com mensagem
  clara se faltar um deles). **Pare o `gerenciador-backend` antes** (`docker stop gerenciador-backend`): desde a #28/#29 o
  relay e o consumer dele compartilham o `meu_banco` e drenam o `event_outbox` dos testes; o script aborta com
  mensagem clara se o container estiver rodando. Desde a #32 o script roda o **reactor Maven inteiro** (`shared`, `reservation-client`,
  `catalog-client`, `monolito`, `notification`, `reservation`, `catalog`) e também aborta se o `gerenciador-notification`, o
  `gerenciador-reservation` ou o `gerenciador-catalog` estiverem rodando
  (`docker stop gerenciador-backend gerenciador-notification gerenciador-reservation gerenciador-catalog`; o notification
  consumiria o tópico dos testes e mandaria e-mail de verdade, o reservation e o catalog dividiriam o banco e o broker deles); ao fim o script também move o group `notification` para o
  fim do tópico (`rpk group seek`), para os eventos de teste nunca virarem e-mail quando o serviço subir (um e-mail real pendente
  antes da rodada é pulado). Ao fim o script também move os groups `reservation` e `reservation-catalog` para o fim dos tópicos
  `reservation.wallet` / `catalog.event` (a reconciliação criaria `eventcap` para ids de evento de teste). Banco já existente precisa dos
  schemas novos: aplicar `Projeto/Backend/db/migrations/20261024-notification-schema.sql`, **com o backend e o reservation parados**
  `20261025-reservation-schema.sql` e, **com o backend e o catalog parados e depois do backup
  `pg_dump -h localhost -U admin --clean --if-exists -n public meu_banco`** (backup, migração e rollback no cabeçalho do arquivo),
  `20261026-catalog-schema.sql` (move `event`/`eventpresenter` e o stream `catalog.event` para o schema `catalog`;
  idempotentes; bancos novos já nascem com eles). O `run-tests.sh` **aborta** se o banco ainda tiver `public.event` (migração 20261026
  pendente) ou não tiver `catalog.event`.
- **`INTERNAL_API_TOKEN` (desde a #33, volta 3):** as chamadas internas gateway → reservation e gateway → catalog (`/internal/**`; #35) exigem um token de serviço
  (header `X-Internal-Token`; 401 sem/errado). **Acrescente ao `Projeto/.env` (você mesmo; o repo nunca edita nem imprime o `.env`)**
  `INTERNAL_API_TOKEN=<valor aleatório>`, por exemplo gerado com `openssl rand -hex 32`. O compose repassa o MESMO valor ao `backend`, ao
  `reservation` e ao `catalog`. Não há valor default: sem a variável **qualquer `docker compose`** (inclusive o `up -d db redpanda` dos testes)
  recusa com mensagem clara (`${INTERNAL_API_TOKEN:?...}`), e os serviços também falham no boot. O `run-tests.sh` usa um valor de teste
  explícito (não é segredo).
- O serviço `notification` sobe junto com `up -d --build` (container `gerenciador-notification`); o `backend` não depende dele. O serviço
  `reservation` (container `gerenciador-reservation`, API interna `:8082` só na rede `gerenciador-net`) e o serviço `catalog` (#35, container
  `gerenciador-catalog`, API interna `:8083` só na rede `gerenciador-net`) também sobem junto, e o `backend` **depende dos dois**
  (`service_healthy`). `POST /bff/seed` fica **desligado** (404) no compose normal; o override de carga (`carga/docker-compose.carga.yml`)
  liga `SEED_ENABLED=true`.
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

**Decidido, não implementado:** extrair os demais módulos como microsserviços (Opção B — `gateway`, `identity`,
`catalog`, `admission`; o `notification` saiu na #32 e o `reservation` na #33);
camada de alta carga (Redis RPM + fila + worker pool). Nada disso existe no código — ver
`Architecture-Decision-Microservices-Split.md` e o `BACKLOG.md`.
