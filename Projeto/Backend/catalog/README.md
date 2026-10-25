# catalog

Serviço de eventos do Gerenciador de Eventos (issue #35, Fase 7: terceira extração do monólito). É dono do agregado `Event` em **CQRS + event
sourcing** (stream `catalog.event`; projeção inline `event`/`eventpresenter` na mesma transação do append), no schema `catalog`, com event
store, outbox, snapshot e checkpoint próprios. Expõe uma **API interna HTTP** (`/internal/events/**`, porta 8083) que espelha o `EventApi` da
lib `catalog-client` 1:1 e é chamada pelo `gateway` do monólito. Imagem própria (`catalog/Dockerfile`); **sem porta no host**.

- **Autenticação:** toda requisição exige o header `X-Internal-Token` = `INTERNAL_API_TOKEN` (401 sem/errado); sem a variável o serviço não
  sobe. O mesmo token vai nas chamadas que ele faz ao `reservation`.
- **API interna:** `POST /internal/events` (201), `PUT|GET|DELETE /internal/events/{id}`, `POST /internal/events/validate` (texto, vazio =
  válido), `GET /internal/events/search?term=`, `GET /internal/events?creatorId=`, `GET /internal/events/{id}/exists` (`SELECT 1`, 200
  `true`/`false`), `GET /internal/events/{id}/creator` (só o `creator_id`), `GET|POST /internal/events/{id}/image` (bytes,
  `application/octet-stream`). As listas não trazem `image_data`. Erros em texto puro: **400** regra de negócio (inclui o `23505` do nome
  duplicado), **404** evento inexistente (com o cabeçalho `X-Catalog-Nao-Encontrado`), **409** só `EventoEmConflitoException` (apagar
  evento com reserva, recusado pelo reservation; evento pré-ES; conflito de versão esgotado), **500** o resto com corpo genérico. O
  `creator_id` do corpo é confiado: quem o amarra ao JWT e confere o dono é o gateway.
- **Capacidade:** o `eventcap` é do serviço `reservation`, por HTTP (`reservation-client`): `PUT /internal/eventcaps/{id}` depois do commit da
  criação, `PATCH .../policy` no update, `POST .../batch` para hidratar listas, `DELETE` antes de cancelar (409 se há reserva).
- **Event sourcing:** o relay publica no tópico `catalog.event` (o reservation reconcilia o `eventcap` pelo `EventCreated`); envelope e tópico
  congelados. Relay desligado por padrão (o compose liga com `EVENTS_RELAY_ENABLED=true`); sem consumer.
- **Configuração:** Tomcat 300, Hikari 100 (`connection-timeout` 5 s; 1000 + 350 + 3 + 100 = 1453 <= `POOLER_MAX_CLIENT_CONN` 1500), keep-alive
  sem teto e 60 s.
- **Banco:** `Projeto/Backend/db/init-scripts/14-catalog.sql` (banco novo); banco existente: migração
  `db/migrations/20261026-catalog-schema.sql`. Variáveis (só pelo nome): `INTERNAL_API_TOKEN`,
  `SPRING_DATASOURCE_*`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `EVENTS_RELAY_*`, `RESERVATION_SERVICE_URL`, `RESERVATION_CLIENT_*`.
- **Testar:** `Projeto/Backend/run-tests.sh` (reactor inteiro; aborta se o banco não estiver migrado ou se o `gerenciador-catalog`
  estiver rodando). O `reservation` é um servidor falso em
  memória (`TestSupport/FakeEventCapServer`); o contrato com o cliente real é o `CatalogApiE2ETest`.
- **Estado (fatia 3 da #35, cutover):** o gateway do monólito chama este serviço pelo `CatalogClient`; container `gerenciador-catalog`
  no compose (`:8083` só na rede `gerenciador-net`, relay ligado).
