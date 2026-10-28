# admission

Serviço de admissão do Gerenciador de Eventos (issue #41, Fase 7: quinta peça da decomposição). Conta cada tentativa de reserva por evento
num **contador de janela deslizante em Redis** e expõe o RPM atual como sinal de carga, numa **API interna HTTP** (`/internal/**`, porta
8085) chamada pelo `gateway` do monólito. Processo, imagem e container (`gerenciador-admission`) próprios; **sem porta no host**; **sem
datasource** (não toca no Postgres e não depende da lib `shared`).

Na #41 o modo devolvido é **sempre `sync`** (a reserva segue pelo caminho de hoje). A fila RabbitMQ e o gatilho de alta carga são as
issues #42 e #43; o corpo do `POST` já tem a forma final.

- **Autenticação:** todo `/internal/**` exige o header `X-Internal-Token` = `INTERNAL_API_TOKEN` (401 sem/errado, em toda requisição; sem
  rota pública); sem a variável o serviço não sobe. O mesmo valor vai ao backend (o compose repassa do `Projeto/.env`).
- **API interna:**
  - `POST /internal/admissions` — corpo `{eventId, userId, idempotencyKey, path}` (`path` = `cas`|`naive`); conta a tentativa e responde
    `200 {"mode":"sync","rpm":n}`. `400` para pedido malformado, `eventId`/`userId` não positivos, `path` inválido, chave vazia ou com mais de
    255 caracteres (mesma regra e mesma mensagem do `reservation`). Redis fora ou lento (timeout de 200 ms) = **503**: o fail-open é do cliente
    (gateway) e o 503 é o que o disjuntor dele conta; um 200 `sync` esconderia a falha.
  - `GET /internal/admissions/events/{eventId}/rpm` — `{"eventId":n,"rpm":n}`, só leitura (não conta). Redis fora = `503`.
- **Contador:** buckets de `ADMISSION_BUCKET_MS` (50 ms) somados numa janela de `ADMISSION_WINDOW_MS` (200 ms, múltiplo do bucket). Chave
  `adm:cnt:<eventId>:<bucket>`, `INCR` + `PEXPIRE` (janela + 1 bucket: **toda chave tem TTL**); RPM = soma dos buckets da janela x 60000 /
  tempo coberto (`janela − bucket + resto do bucket corrente + 1 ms`: o bucket corrente é parcial). Um script Lua (`scripts/rpm.lua`) = UMA ida ao Redis, atômica. Justificativa da janela de 200 ms (a rajada por evento na escada
  dura ~0,2 s; 1 s leria 1/5 da taxa e 60 s nunca dispararia o gatilho da #43): `docs/superpowers/specs/2026-10-28-admission-servico-design.md`.
  O RPM é **só sinal de carga**: nada de capacidade é decidido por ele.
- **Redis:** container `gerenciador-redis` (imagem com tag fixa, `--requirepass`, sem persistência, `maxmemory` 256 MB `volatile-lru`, sem
  porta no host). Lettuce com timeout de **200 ms** e comandos rejeitados na hora sem conexão (`REJECT_COMMANDS`).
- **Configuração** (só pelo nome): `INTERNAL_API_TOKEN`, `REDIS_HOST` (`redis` no compose), `REDIS_PORT`, `REDIS_PASSWORD` (sem default: o
  compose recusa subir sem ela), `ADMISSION_REDIS_TIMEOUT_MS`, `ADMISSION_BUCKET_MS`, `ADMISSION_WINDOW_MS`, `SERVER_TOMCAT_THREADS_MAX` (300).
  Tomcat 300, virtual threads desligadas, keep-alive sem teto de requisições e de 60 s (acima do `evict-idle` de 20 s do cliente).
- **Rodar:** `docker compose -f Projeto/docker-compose.yml up -d --build admission` (sobe junto `redis`, de quem depende). O `backend` **não** depende dele.
- **Testar:** `Projeto/Backend/run-tests.sh` (reactor inteiro; precisa do `redis` no ar e do `gerenciador-admission` parado). O contador é
  testado contra o **Redis real** (`RpmCounterTest`), a API por HTTP real com o `AdmissionClient` real (`AdmissionApiTest`), o 503 com o
  Redis recusando e congelado (`AdmissionRedisRecusadoTest`, `AdmissionRedisTravadoTest`).
