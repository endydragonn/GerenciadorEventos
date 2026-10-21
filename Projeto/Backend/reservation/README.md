# reservation

Serviço de reservas do Gerenciador de Eventos (issue #33, Fase 7: segunda extração do monólito). É dono de `eventcap` (capacidade,
**CAS**: `UPDATE` condicional atômico), `walletevent` (inscrição, **event sourcing**, stream `reservation.wallet`), `idempotency_key` e da
projeção `event_availability`, no schema `reservation`. Expõe uma **API interna HTTP** (`/internal/**`, porta 8082) chamada pelo `gateway` do
monólito. Processo, imagem e container (`gerenciador-reservation`) próprios; **sem porta no host**.

- **API interna:** reservar (`POST /internal/reservations?path=cas|naive`, com `Idempotency-Key`), confirmar, cancelar, listar por usuário,
  disponibilidade defasada (`GET /internal/events/{id}/availability`, usada só pelo braço `naive`) e a capacidade do evento
  (`/internal/eventcaps`, criada pelo catalog, idempotente por `event_id`; busca em lote por `POST .../batch`, ids no corpo). O `userId` vai como parâmetro explícito. Lista completa e códigos
  de erro: `Projeto/Backend/CLAUDE.md`, seção "Serviço reservation". Os DTOs e o cliente vivem na lib `reservation-client`.
- **Braços do experimento:** quem escolhe é o gateway (`RESERVATION_READPATH`): `cas` = uma chamada; `naive` = duas (disponibilidade e,
  depois, a reserva sem CAS). Este serviço não tem a flag.
- **Event sourcing:** a reserva appenda `SlotReserved`/`ReservationConfirmed`/`ReservationExpired`/`ReservationCancelled` no
  `reservation.event_store` **do serviço** (event store próprio, relay e consumer próprios) na mesma transação do CAS/guarda; a projeção de
  disponibilidade (`event_availability`) é assíncrona (group `reservation`).
- **Reconciliação:** o `EventCreated` do tópico `catalog.event` cria o `eventcap` que a chamada síncrona do catalog não criou
  (`CatalogReconciliationConsumer`, group `reservation-catalog`).
- **Jobs:** expiração das reservas `pending` vencidas e retenção das chaves de idempotência (24 h).
- **Independência:** só depende das libs `shared` e `reservation-client`, do Postgres e do Redpanda; não lê tabela do monólito.
- **Configuração do caminho quente** (comparabilidade com o ponto 2): Tomcat 300, Hikari 1000, virtual threads desligadas. A conta do pool do
  Supavisor (mesmo tenant e usuário, o pool real não cresce; 1000 + 350 + 3 <= 1500 conexões de cliente) está no `CLAUDE.md`.
- **Rodar:** `docker compose -f Projeto/docker-compose.yml up -d --build reservation`. Variáveis (só pelo nome): `SPRING_DATASOURCE_*`,
  `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `EVENTS_*`, `RESERVATION_CATALOG_RECONCILIATION_ENABLED`.
- **Banco existente:** aplicar `Projeto/Backend/db/migrations/20261025-reservation-schema.sql` (com o backend e o reservation parados).
- **Testar:** `Projeto/Backend/run-tests.sh` (reactor inteiro; precisa de `db` e `redpanda`, com `gerenciador-backend`,
  `gerenciador-notification` e `gerenciador-reservation` parados). Os testes ponta a ponta usam o `ReservationClient` real contra o serviço
  real (`ReservationApiE2ETest`).
