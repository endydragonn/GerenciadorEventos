# Extrair o catalog como serviço (issue #35) — Plano de implementação

> **Para agentes:** executar com `/implement-issue 35` (code-generator → code-reviewer → spec-validator, máx. 3 voltas por fatia). Passos com checkbox (`- [ ]`).

**Objetivo:** `catalog` (CQRS + ES) vira serviço próprio (módulo Maven, imagem, container, schema `catalog`), chamado pelo `gateway` via `catalog-client`, repetindo o padrão do `reservation` (#33).

**Arquitetura:** API interna REST `/internal/events/**` espelhando `EventApi` 1:1, autenticada só por `X-Internal-Token`; `gateway` valida JWT/host, amarra `creator_id` ao token, compõe `creator_name` (Caffeine) e mapeia falha do catalog para 503.

**Stack:** Spring Boot 3.5.7, JDBC puro, Hikari, HttpClient 5, Caffeine, Postgres (Supabase), Redpanda, Maven multi-módulo, Docker Compose.

**Spec (autoridade única, ler inteira antes de qualquer fatia):** `docs/superpowers/specs/2026-10-24-catalog-servico-design.md`

## Global Constraints

- Idioma PT-BR; Conventional Commits; commits em `Issue-35` com `Refs #35`, terminando com o trailer `Co-Authored-By` do `CLAUDE.md`. Nunca commit/push em `main`; nunca ler/imprimir `Projeto/.env` nem `Projeto/supabase/.env`; nunca citar o vault no repo.
- Backend em JDBC puro; schema em SQL de init; todas as queries do catalog qualificadas com `catalog.` (Supavisor transaction mode: sem `search_path`).
- Tópico `catalog.event` e envelope de eventos congelados (o `reservation` depende de `capacity` e `buyTimeLimit`).
- Pool: Hikari 100 no catalog (1000+350+3+100 = 1453 ≤ 1500), `connection-timeout` 5000 ms, Tomcat 300, timeout gateway→catalog 15 s, sem retry. `INTERNAL_API_TOKEN` único.
- Sem exceção de admin; PUT ignora o `creator_id` do corpo; 503/403/404 conforme "Erros e contrato" do spec.
- Cada fatia termina verde (`Projeto/Backend/run-tests.sh` com a pré-condição do `CLAUDE.md`: parar `gerenciador-backend`, `-reservation`, `-notification`, `-catalog`; `docker compose -f Projeto/docker-compose.yml up -d db redpanda`; seek dos groups; reactor inteiro) e commitada/pushada.
- Migração `20261026` roda com `backend` e `catalog` parados e `pg_dump -n public` antes. Após teste que publique em `identity.notification`: seek do group `notification` antes de subir o serviço.
- Checkstyle (`checkstyle.xml`) roda em `validate`; respeitar.

## Mapa de arquivos (resumo; detalhe no spec, "Componentes" e "Fatias")

- Novos módulos: `Projeto/Backend/catalog/` (serviço, modelo: `reservation/`), `Projeto/Backend/catalog-client/` (lib, modelo: `reservation-client/`); registrar em `Backend/pom.xml` (`modules` + `dependencyManagement`).
- Origem do código do serviço: `monolito/src/main/java/com/gerenciador/eventos/catalog/*` (+ `catalog/api/Event.java`, `EventApi.java` vão para `catalog-client/.../api`).
- Gateway: `monolito/.../gateway/BffEventController.java`, `BffSeedController.java`, `EventWalletGateway.java`, `SeedData.java`.
- SQL: `db/init-scripts/03-event.sql`, `05-eventpresenter.sql` (saem na fatia 3), `14-catalog.sql` (novo), `99-tests.sql`, `db/supabase-app-init.sql`, `db/migrations/20261026-catalog-schema.sql` (novo), `20261022-catalog-event-version.sql` e `seed-data.sql` (reescritos).
- Infra: `Projeto/docker-compose.yml`, `Projeto/carga/docker-compose.carga.yml`, `monolito/Dockerfile`, `catalog/Dockerfile`, `Backend/run-tests.sh`.

---

### Task 1 (Fatia 1): Preparação no monólito

**Escopo:** spec, "Fatias" item 1 + "Erros e contrato" + escopo/aceite itens 4, 6, 7(parcial).

- [ ] `exists(eventId)` leve (`SELECT 1`) + cache positivo ~30 s no gateway (nunca `false` por falha).
- [ ] `findByCreatorId` em SQL (`WHERE creator_id = ?`) + índice em `creator_id` (em `03-event.sql` por enquanto).
- [ ] Imagem em métodos próprios (`findImage`/`saveImage`), fora de `findById`/`updateEvent`; `image_data` fora das listas (service e BFF).
- [ ] Gateway: valida criador (`UserApi.exists`), compõe `creator_name` (`UserApi.findNames(ids)` em lote, só `isactive=true`; Caffeine, TTL 45 s ±20% via `Expiry`, ~10k, sem cache negativo; campo somente-leitura no DTO `Event`).
- [ ] Gateway: create força `creator_id` = usuário do token; PUT ignora `creator_id` do corpo (usa o gravado); PUT/DELETE/imagem exigem dono (403 se não); DELETE de id inexistente → 404; sem exceção de admin.
- [ ] Testes (TDD) dos casos 1, 2, 3, 5 e do `DELETE 999999` → 404 do caso 6 em `HttpContractTest`/`EventTest`; ajustar testes que dependiam do comportamento antigo.
- [ ] Gate: reactor verde, commit `refactor(catalog): … Refs #35`, push.

### Task 2 (Fatia 2): Módulos `catalog` e `catalog-client`

**Escopo:** spec, "Componentes" + fatia 2. O monólito ainda usa `public.event`; `14-catalog.sql` convive com `03`/`05`.

- [ ] `catalog-client`: `Event`, `EventApi`, `CatalogUnavailableException`, `CatalogClient implements EventApi` (HttpClient 5; pool/timeouts como `ReservationClient`; timeout 15 s, sem retry; `CATALOG_SERVICE_URL`, `CATALOG_CLIENT_MAX_CONNECTIONS`), `CatalogClientConfig`.
- [ ] `catalog`: `CatalogApplication`, `EventService`, `CatalogEventWriter`, `CatalogEventProjection`, `EventRepository`, `EventPresenterRepository`, `EventPayloads`, controller `/internal/events/**` (espelha `EventApi`; `/{id}/exists`; `/{id}/image` GET/POST), `InternalTokenFilter`, `InternalApiExceptionHandler` (400 regra de negócio incl. 23505, 404, 409), `application.properties` (`events.schema=catalog`, relay ligado, consumer desligado, Hikari 100, Tomcat 300), `Dockerfile`, `README.md`.
- [ ] `db/init-scripts/14-catalog.sql`: schema `catalog` (owner `admin`) com `event`, `eventpresenter` (FK `ON DELETE CASCADE`), índice `creator_id`, `event_store`/`event_outbox`/`event_snapshot`/`projection_checkpoint`, sem FK cross-schema; incluído por `supabase-app-init.sql` (convivendo com `03`/`05`).
- [ ] Testes migrados para o módulo: `CatalogEventSourcingTest`, `EventTest`, `EventSearchTest`, trechos de `SchemaConstraintsTest`/`SchemaValidationTest`; novos `CatalogApiE2ETest` (contrato real via HTTP, 503/403/404 aplicáveis ao serviço) e `CatalogConfigTest`.
- [ ] Gate: reactor verde (monólito ainda sem usar o client), commit, push.

### Task 3 (Fatia 3): Cutover atômico

**Escopo:** spec, "Migração `20261026-catalog-schema.sql`" + fatia 3 + casos 4, 6, 7, 8, 9, 10.

- [ ] `db/migrations/20261026-catalog-schema.sql` exatamente como descrito no spec (schema, `SET SCHEMA`, mover streams preservando `global_position`, `setval` só do `global_position`, derrubar só FK de `catalog.*` para fora, índice, backfill `version = 0` antes de mover, reescrever `20261022` e `seed-data.sql` por `to_regclass`). Idempotente.
- [ ] Saem `03-event.sql`/`05-eventpresenter.sql`; `supabase-app-init.sql` ganha `\i 14-catalog.sql` antes do `99-tests`; `99-tests.sql` migra `event`/`eventpresenter` para `catalog.*` (TRUNCATE e cascata); `to_regclass('public.event') IS NULL` em volume novo.
- [ ] Gateway usa `CatalogClient` (dependência no `monolito/pom.xml`); `CatalogUnavailableException` → 503 genérico antes dos `catch RuntimeException`; sai o pacote `catalog` do monólito; `exists` com cache; timeout no create → 503 "resultado incerto, confira em Meus eventos".
- [ ] `SEED_ENABLED` (default `false` → 404; ligado aceita `creator_id` do corpo); ligado em `docker-compose.carga.yml` e nos testes.
- [ ] Testes: `FakeCatalogServer`, `HttpContractTest` contra o fake, `ModuleBoundaryArchTest` (entra `catalogclient..`, regras espelhadas do `reservationclient`), `TestSchemas`, `EventCapMigrationTest` (avaliar utilidade, spec "A verificar"), caso 4 (seed 404/flag), caso 6 (503 com catalog fora), casos 7, 9, 10 (script de verificação da migração, como `verify-20260901-migration.sh`).
- [ ] Compose: serviço `catalog` na 8083 sem porta no host, sem `env_file`, healthcheck `bash /dev/tcp`, depende de `supabase-pooler` e `redpanda`; `backend` depende do `catalog` healthy + `CATALOG_SERVICE_URL`/`CATALOG_CLIENT_MAX_CONNECTIONS`; `INTERNAL_API_TOKEN` repassado; Dockerfiles com `COPY` dos poms novos; `run-tests.sh` (reactor com os módulos novos, guard do container `gerenciador-catalog`, seek dos groups `reservation-catalog` etc.).
- [ ] Gate: reactor inteiro verde (saída real), commit, push.

### Task 4 (Fatia 4): Operação e docs

**Escopo:** spec, fatia 4 + casos 11, 12; critérios 4 e 6 do goal.

- [ ] `Projeto/carga/smoke-catalog-servico-separado.sh` (modelo: smoke do reservation), `carga/rpm/subir-host-rpm.ps1` (`public.event` → `catalog.event`), `carga/README.md`, `Backend/DOCKER-COMANDOS.md` (TRUNCATE), `Backend/CLAUDE.md`, `db/CLAUDE.md`, `docs/arquitetura/fase7-servicos.md`, ADR Microservices-Split no repo (se existir em `docs/adr`; `git grep -n "Vira UUID solto" -- docs/adr` → 0), `Database-Schema` no repo (`creator_id` integer), `CLAUDE.md` raiz da GerenciadorEventos (catalog sai de "Decidido, não implementado"), README (`creator_name`), `BACKLOG.md`.
- [ ] Fora do repo: `E:\TCC\carga-overrides\limpar-banco.sh` (truncar `catalog` junto com `reservation`, mover os groups, reiniciar catalog, reservation e backend) — testado uma vez com a stack no ar.
- [ ] Verificação da stack real: migração aplicada com dump prévio; smoke nos dois braços (cas com overselling 0; naive com 2 chamadas) rodado 2×; medir listagem, busca e `exists` antes e depois (baseline na imagem/tag do ponto 3) e registrar no corpo do PR.
- [ ] Gate: commit, push, PR draft (`Refs #35`), review Copilot marcada, Gemini avaliada, findings tratados; `gh pr ready` + `Closes #35` ou parada com relatório.

---

## Auto-revisão

- Cobertura do spec: escopo itens 1–7 → T1 (4, 6), T2 (1), T3 (3, 5, 7), T4 (docs); 12 casos de teste → 1,2,3,5,6(delete) em T1; 6,4,7,8,9,10 em T3; 11,12 em T4.
- Revisão adversarial: o spec já passou por revisão adversarial (commit `96f0a41`); este plano não adiciona superfície nova além do spec.
