# Design: extrair o `catalog` como serviço (issue #35)

Data: 2026-10-24. Padrão a repetir: o serviço `reservation` (#33). Fonte das medições de base: `Projeto/carga/results/05-distribuido/`.

## Escopo e aceite (reescrito)

O aceite original da issue prometia `creator_id` UUID e "host auth valida JWT localmente". Foi reescrito:

1. `catalog` vira serviço (módulo Maven, Dockerfile, container), com schema `catalog`, event store, outbox, snapshot e checkpoint próprios; nenhum serviço lê tabela de outro; sem FK cross-schema.
2. `event.creator_id` segue `integer`, sem FK (referência solta). UUID fica como evolução.
3. `/bff/**` inalterado (aditivo: a resposta de `Event` ganha `creator_name`); `HttpContractTest` passa contra um fake do `catalog`.
4. `creator_name` por composição no `gateway`: `UserApi.findNames(ids)` em lote + cache Caffeine.
5. O `gateway` segue validando o JWT e o host; o `catalog` autentica só por `X-Internal-Token`. JWT assimétrico + JWKS é da #36.
6. `creator_id` amarrado ao token: no create o `gateway` força `creator_id` = usuário do token; PUT, DELETE e imagem exigem dono (ou admin). Ligado ao IDOR #57.

## Decisões (todas aceitas em 2026-10-24)

| Tema | Decisão |
|---|---|
| Contrato | API interna REST em `/internal/events/**`, espelhando o `EventApi` 1:1, implementada no `gateway` por `catalog-client` (HttpClient 5, como o `ReservationClient`) |
| Imagem | `GET/POST /internal/events/{id}/image` (bytes); fora de `findById` + `updateEvent` |
| `exists(eventId)` no caminho quente | endpoint leve (`SELECT 1`) + cache positivo de ~30 s no gateway |
| Capacidade | hidratada no `catalog` (cadeia gateway→catalog→reservation); se o `reservation` cair, a listagem dá 500 (nunca `quantity=0`) |
| `image_data` nas listas | omitido na API interna e no BFF (continua em `/image` e `findById`) |
| `findByCreatorId` | SQL `WHERE creator_id = ?` com índice |
| Paginação | sem paginação (dívida registrada) |
| Erros | 400 regra de negócio (inclui 23505 de nome duplicado), 404, 409 (delete com reservas, evento pré-ES), 503 genérico se o `catalog` cair |
| Pool | Hikari 100 (1000+350+3+100 = 1453 ≤ 1500), Tomcat 300, timeout `gateway→catalog` 15 s, sem retry |
| Token interno | um `INTERNAL_API_TOKEN` único até a #36 |
| `creator_name` | TTL 45 s ±20% (`Expiry` customizado), ~10k entradas, sem cache negativo, nome completo; campo somente-leitura no DTO |
| Observabilidade | TCP-only; lag documentado (`catalog.event_outbox`, `rpk group describe reservation-catalog`) |
| Migração | schema próprio, `14-catalog.sql` consolidando `03`/`05` + infra de event store; backup `pg_dump -n public`, sem script reverso |
| `EventCreated` | tópico `catalog.event` e envelope congelados (o `reservation` depende de `capacity` e `buyTimeLimit`) |
| Teste do contrato real | fake fino no monólito + `CatalogApiE2ETest` no módulo |
| Baseline | congelar tag das imagens do ponto 3 antes do cutover |

## Componentes

- **`catalog` (serviço):** `EventService`, `CatalogEventWriter`, `CatalogEventProjection`, `EventRepository`, `EventPresenterRepository`, `@RestController` `/internal/events/**` + `InternalTokenFilter` + handler de erros. `events.schema=catalog`, relay ligado, consumer desligado. Todas as queries qualificadas com `catalog.` (Supavisor em transaction mode proíbe `search_path`).
- **`catalog-client` (lib):** `CatalogClient` implementa `EventApi`; pool/timeouts como o `ReservationClient`.
- **`gateway`:** troca `EventApi` pelo `CatalogClient`; compõe `creator_name`; valida `creator_id` (`UserApi.exists`); cache de `exists(eventId)`.
- **`catalog`→`reservation`:** inalterado (`PUT /internal/eventcaps/{id}` após o commit, `PATCH` policy, `POST /batch`, `DELETE`).
- **Compose:** `catalog` na porta 8083, sem porta no host, sem `env_file`, healthcheck `bash /dev/tcp`, depende de `supabase-pooler` e `redpanda` (não do `reservation`); `backend` depende do `catalog` healthy e recebe `CATALOG_SERVICE_URL` e `CATALOG_CLIENT_MAX_CONNECTIONS`.

## Migração `20261026-catalog-schema.sql`

`SET SCHEMA catalog` em `event` e `eventpresenter`; mover os streams `catalog.event` (event_store, outbox, checkpoint, snapshot, filtrando `stream_type`) preservando `global_position`; `setval` da sequence; derrubar `Event_creator_id_fkey` e qualquer FK para `public.event`/`users` consultando `pg_constraint`; criar índice em `creator_id`; backfill de `version = 0` dentro da migração, antes de mover; reescrever a `20261022` e o `seed-data.sql` para o schema novo (por `to_regclass`). Com `backend` e `catalog` parados; o `reservation` pode ficar de pé.

## Fatias (cada uma deixa o sistema verde)

1. Preparação no monólito: `exists` leve, `findByCreatorId` em SQL + índice, imagem em métodos próprios, sem `image_data` nas listas, validação do criador e `creator_name` no `gateway` (Caffeine), amarração de `creator_id` ao token.
2. Módulos `catalog` e `catalog-client`, controller interno, `14-catalog.sql`, migração, testes migrados (`CatalogEventSourcingTest`, `EventTest`, `EventSearchTest`, trechos de `SchemaConstraintsTest`/`SchemaValidationTest`), `CatalogApiE2ETest`, `CatalogConfigTest`.
3. Cutover atômico: o `gateway` usa o client; sai o pacote `catalog` do monólito; `FakeCatalogServer`, `ModuleBoundaryArchTest` (entra `catalogclient..`, regras espelhadas das do `reservationclient`), `TestSchemas`, compose, Dockerfiles (`COPY` dos poms novos), `run-tests.sh` (guard do container `gerenciador-catalog`).
4. Operação e docs: `smoke-catalog-servico-separado.sh`, `limpar-banco.sh` (fora do repo: truncar `catalog` junto com `reservation`, mover os groups, reiniciar os três), `carga/README.md`, `Backend/CLAUDE.md`, `db/CLAUDE.md`, `fase7-servicos.md`, BACKLOG; medição de listagem, busca e `exists` antes e depois.
5. (Opcional) `pg_trgm` e hidratação da capacidade no `gateway`, só se a medição pedir.

## A verificar antes de confiar (viram tarefas)

Sequence do `event_id` após `SET SCHEMA`; outras FKs em bancos antigos; outbox em voo na migração; peso da listagem total (base64 duas vezes) com 1k e 10k eventos; plano da busca `LIKE '%t%'` e disponibilidade de `pg_trgm`; cadeia de timeouts na criação (transação + PUT ao `reservation` contra o timeout do `gateway`); fidelidade do `FakeCatalogServer` e utilidade do `EventCapMigrationTest`; Caffeine (versão gerida pelo Boot, `Expiry` com jitter) e custo do `findNames` em memória; se `POOLER_MAX_CLIENT_CONN` conta o Hikari no máximo.

## Riscos para a rodada de carga

Em ordem: o salto novo no caminho quente da reserva (mitigado pelo cache de `exists`); a listagem total com cadeia serial e JSON duplicado; a busca sem índice; pressão de memória no `gateway` se imagens em base64 ficarem nas listas.

## Fora de escopo

UUID para `creator_id`; JWT assimétrico + JWKS (#36); projeção de `creator_name`; paginação; `/actuator/health`; `stock` + `wallet` (#46).
