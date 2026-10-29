# stock

Serviço de estoque do Gerenciador de Eventos (issue #47, Fase 7: o `reservation` se divide em **stock + wallet**, coordenados por uma saga). É dono
de `eventcap` (capacidade, **CAS**: `UPDATE` condicional atômico) e de `hold` (o **ledger das reservas de vaga**, uma linha por tentativa), no
schema `stock`. Expõe uma **API interna HTTP** (`/internal/**`, porta **8086**) chamada pelo `reservation` (orquestrador da saga) e pelo
`catalog` (capacidade). Processo, imagem (`stock/Dockerfile`) e container (`gerenciador-stock`) próprios; **sem porta no host**. Desenho
completo: `docs/superpowers/specs/2026-10-29-saga-stock-wallet-design.md`.

> **Estado (passada a1):** o serviço, a lib `stock-client`, o schema (`db/init-scripts/16-stock.sql`) e os testes estão prontos e **aditivos**. O
> `reservation` ainda não o chama (a saga é a passada a2) e `reservation.eventcap` coexiste com `stock.eventcap` até a migração
> `20261029-stock-schema.sql`. Compose, `stock-seek`, e2e e carga entram na passada b.

- **Autenticação:** toda requisição exige o header `X-Internal-Token` = `INTERNAL_API_TOKEN` (401 sem/errado; sem filtro por caminho); sem a
  variável o serviço não sobe.
- **Holds** (`HoldController`): `POST /internal/holds` `{holdId, userId, eventId, mode: cas|naive}` reserva a vaga numa transação curta: lê o
  `eventcap` ("Capacidade do evento não configurada", "Janela de compra encerrada para este evento"), `INSERT hold ... ON CONFLICT DO NOTHING`
  (hold existente: 200 com o mesmo corpo se `held`, 409 se `released` = lápide) e, só em `cas` de evento limitado, o CAS como último comando
  ("Evento lotado" = rollback, o hold some junto) → 201 `{holdId, maxTimeInLine}`. `POST /internal/holds/{holdId}/release` `{eventId, userId}`
  libera num **upsert de um statement só** (espera um "reservar" em voo; um release que chega antes grava a lápide), devolve a vaga se havia
  débito e responde sempre 200, idempotente por `holdId`.
- **Capacidade** (`EventCapController`, veio do `reservation`): `PUT/GET /internal/eventcaps/{id}`, `POST .../batch`, `PATCH .../policy`, `DELETE`,
  com os mesmos verbos e códigos. A conversão ilimitado → limitado conta os holds `held` e os marca `decremented`; o delete é recusado (409) se
  há hold de qualquer status.
- **Devolução ao cancelar/expirar** (`StockReleaseConsumer`): consome `ReservationCancelled`/`ReservationExpired` (com `holdId`) do tópico
  `reservation.wallet`, group `stock`, `auto.offset.reset=earliest`, em lote e numa transação (`UPDATE hold ... WHERE status = 'held'` e depois um
  `UPDATE eventcap` por evento). Liga por `STOCK_RELEASE_CONSUMER_ENABLED` (e `EVENTS_CONSUMER_ENABLED`).
- **Reconciliação** (`CatalogReconciliationConsumer`): o `EventCreated` de `catalog.event` cria o `eventcap` que a chamada síncrona do catalog não
  criou (group `stock-catalog`, `latest`; `STOCK_CATALOG_RECONCILIATION_ENABLED`).
- **Sem event store nem relay:** o stock só consome; não publica eventos.
- **Configuração do caminho quente:** Tomcat 300, keep-alive sem teto (60 s), virtual threads desligadas, Hikari 250 (no compose, um literal).
- **Rodar:** `docker compose -f Projeto/docker-compose.yml up -d --build stock` (a partir da passada b). Variáveis (só pelo nome):
  `INTERNAL_API_TOKEN`, `SPRING_DATASOURCE_*`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `EVENTS_CONSUMER_ENABLED`, `STOCK_RELEASE_CONSUMER_*`,
  `STOCK_CATALOG_RECONCILIATION_*`.
- **Testar:** `Projeto/Backend/run-tests.sh` (reactor inteiro; precisa de `db`, `redpanda`, `redis` e `rabbitmq`, com `gerenciador-stock` parado).
  `StockHoldTest` usa o `StockClient` real contra o serviço real (idempotência, lápide, overselling 0 e a corrida release × reservar em voo),
  `StockApiE2ETest` o contrato HTTP (token, 400, `/internal/eventcaps`), `StockReleaseConsumerTest` a devolução (lote, `earliest` do group novo,
  rebalance de duas instâncias).
