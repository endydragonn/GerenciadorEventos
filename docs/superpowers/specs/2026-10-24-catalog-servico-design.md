# Design: extrair o `catalog` como serviço (issue #35)

Data: 2026-10-24. Padrão a repetir: o serviço `reservation` (#33). Fonte das medições de base: `Projeto/carga/results/05-distribuido/`.

## Escopo e aceite (reescrito)

O aceite original da issue prometia `creator_id` UUID e "host auth valida JWT localmente". Foi reescrito:

1. `catalog` vira serviço (módulo Maven, Dockerfile, container), com schema `catalog`, event store, outbox, snapshot e checkpoint próprios; nenhum serviço lê tabela de outro; sem FK cross-schema.
2. `event.creator_id` segue `integer`, sem FK (referência solta). UUID fica como evolução.
3. `/bff/**` mantém o contrato, com as mudanças aditivas ou de status listadas em "Erros e contrato" (a resposta de `Event` ganha `creator_name`; `image_data` sai das listas; não-dono → 403; DELETE de id inexistente → 404; catalog fora → 503); `HttpContractTest` passa contra um fake do `catalog`.
4. `creator_name` por composição no `gateway`: `UserApi.findNames(ids)` em lote + cache Caffeine.
5. O `gateway` segue validando o JWT e o host; o `catalog` autentica só por `X-Internal-Token`. JWT assimétrico + JWKS é da #36.
6. `creator_id` amarrado ao token: no create o `gateway` força `creator_id` = usuário do token; no PUT ignora o `creator_id` do corpo e usa o gravado; PUT, DELETE e imagem exigem dono (`creator_id` do evento == usuário do token). **Sem exceção de admin nesta issue:** nenhum gate usa `users.admin` e ele é gravável pelo próprio usuário (`PUT /bff/users/{id}` e `POST /bff/seed` aceitam `isAdmin`); admin é follow-up. Ligado ao IDOR #57.
7. `POST /bff/seed` fica **desligado por padrão** (`SEED_ENABLED=false` → 404). Liga-se só no override de carga/dev (`docker-compose.carga.yml`) e nos testes; ligado, o `creator_id` do corpo é aceito (ferramenta de dev). O kit de carga (`lib-seed.ps1`, `rodar-teste.ps1`, `setup.sh`, `populate-data.ps1`) segue funcionando com a flag ligada.

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
| Erros e contrato | 400 regra de negócio (inclui 23505 de nome duplicado), 404, 409 (delete com reservas, evento pré-ES). Catalog fora → `CatalogClient` lança `CatalogUnavailableException`, mapeada para **503 genérico** no `gateway` antes dos `catch RuntimeException` dos controllers (que hoje viram 404/500); `exists` nunca devolve `false` por falha. Não-dono → 403. DELETE de id inexistente passa de 204 para 404 (mudança documentada). Timeout no create → 503 "resultado incerto, confira em Meus eventos" |
| Pool | Hikari 100 (1000+350+3+100 = 1453 ≤ 1500), `hikari.connection-timeout` 5000 ms, Tomcat 300, timeout `gateway→catalog` 15 s, sem retry |
| Token interno | um `INTERNAL_API_TOKEN` único até a #36 |
| `creator_name` | TTL 45 s ±20% (`Expiry` customizado), ~10k entradas, sem cache negativo, nome completo; campo somente-leitura no DTO. `findNames` devolve só `isactive=true`: conta desativada vira `creator_name` nulo |
| Observabilidade | TCP-only; lag documentado (`catalog.event_outbox`, `rpk group describe reservation-catalog`) |
| Migração | schema próprio, `14-catalog.sql` consolidando `03`/`05` + infra de event store; backup `pg_dump -n public` válido **só antes de reabrir o tráfego** (rollback = `DROP SCHEMA catalog CASCADE` + restore; tópico e groups ficam fora), sem script reverso |
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

Cria o schema `catalog` (owner `admin`) e `event_store`/`event_outbox`/`event_snapshot`/`projection_checkpoint` com `IF NOT EXISTS`, como a `20261025`; `SET SCHEMA catalog` em `event` e `eventpresenter`; mover os streams `catalog.event` (event_store, outbox, checkpoint, snapshot, filtrando `stream_type`) preservando `global_position`; `setval` **só** do `global_position` do `catalog.event_store` (a identity de `event_id` acompanha a tabela e não é tocada: `max+1` reciclaria ids apagados e o evento novo herdaria `eventcap`/reservas antigos); derrubar só FK com `conrelid` em `catalog.*` e `confrelid` fora de `catalog` (nunca `mywallet`→`users`; `eventpresenter`→`event` fica, com `ON DELETE CASCADE`); criar índice em `creator_id`; backfill de `version = 0` dentro da migração, antes de mover; reescrever a `20261022` e o `seed-data.sql` para o schema novo (por `to_regclass`). Com `backend` e `catalog` parados; o `reservation` pode ficar de pé.

## Fatias (cada uma deixa o sistema verde)

1. Preparação no monólito: `exists` leve, `findByCreatorId` em SQL + índice, imagem em métodos próprios, sem `image_data` nas listas, validação do criador e `creator_name` no `gateway` (Caffeine), amarração de `creator_id` ao token.
2. Módulos `catalog` e `catalog-client`, controller interno, `14-catalog.sql` **convivendo com `03`/`05`** (a migração e a remoção de `03`/`05` ficam na fatia 3, porque o monólito ainda usa `public.event`), testes migrados (`CatalogEventSourcingTest`, `EventTest`, `EventSearchTest`, trechos de `SchemaConstraintsTest`/`SchemaValidationTest`), `CatalogApiE2ETest`, `CatalogConfigTest`.
3. Cutover atômico: aplica-se a migração e saem `03`/`05`; `supabase-app-init.sql` ganha `\i 14-catalog.sql` antes do `99-tests`, e `99-tests.sql` migra `event`/`eventpresenter` para `catalog.*` (TRUNCATE e teste de cascata); o `gateway` usa o client; sai o pacote `catalog` do monólito; `SEED_ENABLED` (item 7); `FakeCatalogServer`, `ModuleBoundaryArchTest` (entra `catalogclient..`, regras espelhadas das do `reservationclient`), `TestSchemas`, compose, Dockerfiles (`COPY` dos poms novos), `run-tests.sh` (guard do container `gerenciador-catalog`).
4. Operação e docs: `smoke-catalog-servico-separado.sh`, `limpar-banco.sh` (fora do repo: truncar `catalog` junto com `reservation`, mover os groups, reiniciar os três), `carga/README.md`, `carga/rpm/subir-host-rpm.ps1` (`public.event` → `catalog.event`), `Backend/DOCKER-COMANDOS.md` (TRUNCATE), `Backend/CLAUDE.md`, `db/CLAUDE.md`, `fase7-servicos.md`, ADR Microservices-Split (`creator_name` por composição), `Database-Schema.md` (`creator_id` integer), `CLAUDE.md` raiz (catalog sai de "Decidido, não implementado"), README (`creator_name`), BACKLOG; medição de listagem, busca e `exists` antes e depois.
5. (Opcional) `pg_trgm` e hidratação da capacidade no `gateway`, só se a medição pedir.

## Testes (critério de aceite)

Entrada → resposta esperada; cada caso vira teste TDD.

1. Usuário B (host, não dono) faz `PUT /bff/users/B {isAdmin:true}` e depois `PUT /bff/events/{id de A}` → 403.
2. Dono A faz `PUT /bff/events/7` com `creator_id:B` → 200 e `GET /bff/events/7` devolve `creator_id:A`.
3. `POST /bff/events` com `creator_id` de outro usuário → o evento nasce com o `creator_id` do token.
4. `POST /bff/seed` sem `SEED_ENABLED` → 404 e nenhum evento criado; com a flag ligada o kit de carga semeia como antes.
5. Conta desativada: `GET /bff/events/{id}` devolve `creator_name` nulo.
6. Com o container `catalog` parado: `GET /bff/events/1` → 503 (não 404); a reserva (`exists`) falha com erro, nunca com "evento não existe". `DELETE /bff/events/999999` como host → 404.
7. Depois da migração: `mywallet_user_id_fkey` existe em `public.mywallet`; `eventpresenter_event_id_fkey` existe com `confdeltype='c'`; zero FKs de `catalog.*` para outro schema.
8. `docker compose up -d` em volume vazio → `db` healthy, o init termina com "Testes minimos concluidos com sucesso" e `to_regclass('public.event') IS NULL`.
9. Criar evento de id maior, apagar, migrar: o `event_id` do próximo evento não reaproveita o id apagado (a identity continua da sequence).
10. Restore do dump pré-migração, com `backend` e `catalog` parados → `count(*)` de `public.event` igual ao anterior e `to_regnamespace('catalog') IS NULL`.
11. `subir-host-rpm.ps1 -Verificar -Prefixo Rpm-X` após o cutover → retorna a linha de contagem, sem `relation "public.event" does not exist`.
12. `git grep -n "Vira UUID solto" -- docs/adr` → 0 ocorrências.

## A verificar antes de confiar (viram tarefas)

Sequence do `event_id` após `SET SCHEMA`; outras FKs em bancos antigos; outbox em voo na migração; peso da listagem total (base64 duas vezes) com 1k e 10k eventos; plano da busca `LIKE '%t%'` e disponibilidade de `pg_trgm`; cadeia de timeouts na criação (transação + PUT ao `reservation` contra o timeout do `gateway`); fidelidade do `FakeCatalogServer` e utilidade do `EventCapMigrationTest`; Caffeine (versão gerida pelo Boot, `Expiry` com jitter) e custo do `findNames` em memória; se `POOLER_MAX_CLIENT_CONN` conta o Hikari no máximo.

## Riscos para a rodada de carga

Em ordem: o salto novo no caminho quente da reserva (mitigado pelo cache de `exists`); a listagem total com cadeia serial e JSON duplicado; a busca sem índice; pressão de memória no `gateway` se imagens em base64 ficarem nas listas.

## Fora de escopo

UUID para `creator_id`; JWT assimétrico + JWKS (#36); projeção de `creator_name`; paginação; `/actuator/health`; `stock` + `wallet` (#46).
