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
  clara se faltar um deles).
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
consumer idempotente (checkpoint atômico), replay em schema-sombra e snapshots. `reservation` ainda não appenda
eventos (#29).

**Implementado (Fase 6, issue #28):** `catalog` em CQRS + Event Sourcing — stream `catalog.event`
(`EventCreated`/`EventUpdated`/`PresenterAdded`/`PresenterRemoved`/`EventCancelled`), append na mesma transação da
projeção inline `event`/`eventpresenter` (coluna `event.version` como versão esperada; conflito = repete o command),
`image_data` fora do ES, replay do agregado comparado com a viva, relay ligado no compose (`EVENTS_RELAY_ENABLED`).
`/bff/events/**` inalterado. Detalhes no `Projeto/Backend/CLAUDE.md`, "Catalog em CQRS + Event Sourcing".

**Decidido, não implementado:** extrair os módulos como microsserviços (Opção B — `gateway`, `identity`,
`catalog`, `reservation`, `notification`, `admission`; a Fase 5 só os separou dentro do monólito); CQRS + Event Sourcing
no lado wallet de `reservation` (`eventcap` fica relacional/CAS; o `catalog` já foi, #28);
camada de alta carga (Redis RPM + fila + worker pool). Nada disso existe no código — ver
`Architecture-Decision-Microservices-Split.md` e o `BACKLOG.md`.
