# admission

Serviço de admissão do Gerenciador de Eventos (issue #41, Fase 7: quinta peça da decomposição). Conta cada tentativa de reserva por evento
num **contador de janela deslizante em Redis** e expõe o RPM atual como sinal de carga, numa **API interna HTTP** (`/internal/**`, porta
8085) chamada pelo `gateway` do monólito. Processo, imagem e container (`gerenciador-admission`) próprios; **sem porta no host**; **sem
datasource** (não toca no Postgres e não depende da lib `shared`).

Na #41 o modo devolvido é **sempre `sync`** (a reserva segue pelo caminho de hoje). Na **#42** o serviço ganhou a **fila de admissão** (RabbitMQ), o **worker pool** que chama o `reservation`, o resultado por mensagem no
Redis e o `GET /internal/admissions/result`; **na #43 o gatilho liga a fila ao `POST`**: acima de 80% de X × réplicas (`ADMISSION_X_RPM` 56000, `ADMISSION_RESERVATION_REPLICAS` 1, `ADMISSION_QUEUE_ON` 0,80) o evento entra em modo fila e o `POST` devolve `queued` (o gateway responde `202`); volta ao `sync` só com uso < 0,70 (`ADMISSION_SYNC_BELOW`) e a espera do evento vazia. Abaixo do gatilho o `POST` é idêntico ao da #41. Desde a #111 (autoescala) as réplicas da conta `X × réplicas` vêm da chave `adm:replicas` do Redis (escrita pelo scaler, TTL 15 s; ausente ou inválida => `ADMISSION_RESERVATION_REPLICAS`) e o script publica o RPM do evento mais quente em `adm:hot` (TTL de uma janela). Respostas 422/429 de negócio; o braço `naive` nunca enfileira.

- **Autenticação:** todo `/internal/**` exige o header `X-Internal-Token` = `INTERNAL_API_TOKEN` (401 sem/errado, em toda requisição; sem
  rota pública); sem a variável o serviço não sobe. O mesmo valor vai ao backend (o compose repassa do `Projeto/.env`).
- **API interna:**
  - `POST /internal/admissions` — corpo `{eventId, userId, idempotencyKey, path}` (`path` = `cas`|`naive`); conta a tentativa e responde
    `200 {"mode":"sync","rpm":n}`. `400` para pedido malformado, `eventId`/`userId` não positivos, `path` inválido, chave vazia ou com mais de
    255 caracteres (mesma regra e mesma mensagem do `reservation`). Redis fora ou lento (timeout de 200 ms) = **503**: o fail-open é do cliente
    (gateway) e o 503 é o que o disjuntor dele conta; um 200 `sync` esconderia a falha.
  - `GET /internal/admissions/events/{eventId}/rpm` — `{"eventId":n,"rpm":n}`, só leitura (não conta). Redis fora = `503`.
  - `GET /internal/admissions/result?userId=&idempotencyKey=` (#42) — `200 {status, eventId, expiresAt?, reason?}` (`queued`, `pending`, `rejected` ou `timed_out`; um `queued` com o `deadline` + 60 s vencido vale
    `timed_out`) ou `404` sem corpo; `400` sem parâmetros ou inválidos.
- **Contador:** buckets de `ADMISSION_BUCKET_MS` (50 ms) somados numa janela de `ADMISSION_WINDOW_MS` (200 ms, múltiplo do bucket). Chave
  `adm:cnt:<eventId>:<bucket>`, `INCR` + `PEXPIRE` (janela + 1 bucket: **toda chave tem TTL**); RPM = soma dos buckets da janela x 60000 /
  tempo coberto (`janela − bucket + resto do bucket corrente + 1 ms`: o bucket corrente é parcial). Um script Lua (`scripts/rpm.lua`) = UMA ida ao Redis, atômica. Justificativa da janela de 200 ms (a rajada por evento na escada
  dura ~0,2 s; 1 s leria 1/5 da taxa e 60 s nunca dispararia o gatilho da #43): `docs/superpowers/specs/2026-10-28-admission-servico-design.md`.
  O RPM é **só sinal de carga**: nada de capacidade é decidido por ele.
- **Redis:** container `gerenciador-redis` (imagem com tag fixa, `--requirepass`, sem persistência, `maxmemory` 512 MB `volatile-ttl`, sem
  porta no host). Lettuce com timeout de **200 ms** e comandos rejeitados na hora sem conexão (`REJECT_COMMANDS`). Além do contador, guarda o resultado de cada mensagem da fila
  (`adm:res:<userId>:<chave>`, toda escrita com TTL, estado final por compare-and-set pela tentativa) e a espera (ZSET `adm:wait:<eventId>`, `adm:uwait:<userId>`).
- **Fila (#42):** container `gerenciador-rabbitmq` (`rabbitmq:4.1.8-alpine`, **sem** management, sem porta no host, **sem volume**: `tmpfs`). Topologia declarada pelo serviço: exchange topic `admission` (routing
  `event.<eventId>`) → fila durável `admission.requests` (`x-message-ttl` = `ADMISSION_QUEUE_TTL_MS`, `x-max-length` = `ADMISSION_QUEUE_MAX_LENGTH` com `reject-publish`, dead-letter para `admission.dlx`) e
  `admission.timed_out`. Mensagem `{userId, eventId, idempotencyKey, attemptId, deadline}`, persistente, sem dado pessoal. **Worker pool:** `@RabbitListener` com ack manual, concorrência fixa
  `ADMISSION_WORKER_CONCURRENCY` (o teto de CAS simultâneos no `reservation`), prefetch 1; `POST /internal/reservations?path=cas` com a `Idempotency-Key` da mensagem: 201 → `pending`, 4xx de negócio → `rejected`,
  reservation fora/5xx/exceção (inclusive `Error`) → backoff e a mensagem volta (nack com requeue). O publicador usa uma conexão AMQP própria e `enfileirar` só aceita `path=cas`. Dead-letter: reconcilia pelo `replay` do reservation (`pending` se há reserva com a chave, senão `timed_out`; sempre ack). A ordem por evento
  não é garantida (a corretude é do CAS). Detalhes e a justificativa no `Projeto/Backend/CLAUDE.md`, "Fila de admissão (RabbitMQ) e worker pool — issue #42".
- **Variáveis novas no `Projeto/.env`:** `RABBITMQ_USER=<nome>` e `RABBITMQ_PASSWORD=<valor aleatório>` (`openssl rand -hex 32`), além da `REDIS_PASSWORD` da #41; sem elas o compose recusa.
- **Configuração** (só pelo nome): `INTERNAL_API_TOKEN`, `REDIS_HOST` (`redis` no compose), `REDIS_PORT`, `REDIS_PASSWORD` (sem default: o
  compose recusa subir sem ela), `ADMISSION_REDIS_TIMEOUT_MS`, `ADMISSION_BUCKET_MS`, `ADMISSION_WINDOW_MS`, `SERVER_TOMCAT_THREADS_MAX` (300); da fila (#42): `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`,
  `RABBITMQ_PASSWORD`, `RESERVATION_SERVICE_URL`, `ADMISSION_QUEUE_TTL_MS` (30000), `ADMISSION_QUEUE_MAX_LENGTH` (100000), `ADMISSION_WORKER_CONCURRENCY` (32), `ADMISSION_DLX_CONCURRENCY` (4),
  `ADMISSION_RETRY_BACKOFF_MS` (500), `ADMISSION_MAX_WAITING_PER_USER` (5), `ADMISSION_RESULT_TTL_SECONDS` (600), `ADMISSION_PUBLISH_CONFIRM_TIMEOUT_MS` (200).
  Tomcat 300, virtual threads desligadas, keep-alive sem teto de requisições e de 60 s (acima do `evict-idle` de 20 s do cliente).
- **Rodar:** `docker compose -f Projeto/docker-compose.yml up -d --build admission` (sobe junto `redis`, `rabbitmq` e `reservation`, de quem depende). O `backend` **não** depende dele.
- **Testar:** `Projeto/Backend/run-tests.sh` (reactor inteiro; precisa do `redis` e do `rabbitmq` no ar e do `gerenciador-admission` parado). A fila e o worker são testados com **RabbitMQ e Redis reais** e um
  `reservation` falso (`FakeReservationServer`): `AdmissionStoreTest`, `AdmissionTopologyTest`, `AdmissionLogsTest`, `FilaDeAdmissaoTest`, `FilaCheiaTest`, `AdmissionWorkerTest`, `AdmissionDeadLetterTest`. O contador é
  testado contra o **Redis real** (`RpmCounterTest`), a API por HTTP real com o `AdmissionClient` real (`AdmissionApiTest`), o 503 com o
  Redis recusando e congelado (`AdmissionRedisRecusadoTest`, `AdmissionRedisTravadoTest`).
