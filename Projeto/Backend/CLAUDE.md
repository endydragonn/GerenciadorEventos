# CLAUDE.md — Backend (API Spring Boot)

API REST do Gerenciador de Eventos. Spring Boot 3.5.7, Java 21, Maven (sem wrapper `mvnw`).
Leia também o CLAUDE.md da raiz (regras gerais) e o de `db/` (schema).

## Rodar

- Via Docker (recomendado): `docker compose -f Projeto/docker-compose.yml up -d --build backend`
  — porta `8081`, container `gerenciador-backend`. Sobe junto `db` e `supabase-pooler`; no Docker o
  backend fala **pelo pooler** (Supavisor), ver "Pooler" em Persistência.
- Local: `cd Projeto/Backend && mvn spring-boot:run` — precisa de um Postgres em
  `localhost:5433`. `application.properties` tem defaults de dev para as vars do `.env`.
- Entrypoint do jar: `com.gerenciador.eventos.Main` (fixado no `pom.xml` e no `Dockerfile`).
- Build da imagem: multi-stage — compila com `maven:3.9.7-temurin-21`, roda em `temurin:25-jre`.

## Testes

`./run-tests.sh` — sobe um container Maven na rede `gerenciador-net`, com `--env-file ../.env`
(URL direta `db:5432`, **sem passar pelo pooler**),
e roda `mvn clean test`. **Exige o container `db` no ar**: os testes batem num Postgres real.

- `src/test/.../SchemaValidationTest` valida o schema real contra o esperado.
- `TestSupport/GlobalDbTruncator` dá `TRUNCATE` (início/fim da suíte) em **todas** as tabelas dos
  schemas configurados, descobertas por catálogo (`TestSupport/TestSchemas`) — não há mock de banco.
  Schemas alvo: propriedade `test.db.schemas` (lista separada por vírgula, default `public`; vale
  também via env `TEST_DB_SCHEMAS` ou `-Dtest.db.schemas=`), usada também por `SchemaValidationTest`
  (`table_schema = any(?)`). Nomes validados por `[a-z_][a-z0-9_]*` (identificador não é
  parametrizável em SQL). **Mantenha o default `public`**: os schemas do Supabase (`auth`, `storage`,
  `extensions`...) vivem no mesmo banco e não podem ser truncados nem validados. Fase 5
  (schema por serviço): `test.db.schemas=catalog,reservation,...`. O `run-tests.sh` roda o mvn num
  container com env fixo (`--env-file ../.env`): para mudar `test.db.schemas` por ali, passe
  `-e TEST_DB_SCHEMAS=...` no `docker run` do script. Coberto por `TestSchemasTest`.
- `EventTest`, `UserTest`, `EventWalletTest`, `MyWalletTest` exercitam services + repositories.
- `IdempotencyKeyTest` (issue #24): repetição com a mesma `Idempotency-Key` não decrementa `eventcap` de novo
  (sequencial, após `confirm`, e 8 threads concorrentes com a mesma chave), conflito com outro usuário/evento,
  falha não deixa chave. O braço `naive` tem o seu em `EventWalletNaivePathTest`; o header/422 em `HttpContractTest`.
- `HttpContractTest` (`webEnvironment = RANDOM_PORT` + `TestRestTemplate`) fixa path + método
  + forma de request/response de cada endpoint `/bff/**` que o Flutter consome — bate no
  HTTP real (inclusive `SecurityConfig`), não MockMvc. `JavaMailSender` é mockado
  (`@MockitoBean`) e `RECAPTCHA_SECRET_KEY` é forçada vazia via `@TestPropertySource` para
  não depender de SMTP/Google reais.
- Não há testes unitários isolados; tudo é de integração com banco (exceto os mocks pontuais
  do `HttpContractTest` para dependências externas).
- `LayerBoundaryArchTest` e `ModuleBoundaryArchTest` (ArchUnit) são a exceção: não usam Spring/banco, só analisam bytecode.
  Roda em qualquer `mvn test`, com ou sem `db` no ar.

## Quality gates (Checkstyle + ArchUnit) — issue #83

- `maven-checkstyle-plugin` roda no `mvn test`/`mvn clean test` (preso na fase `validate`,
  antes de compilar) — por isso `./run-tests.sh` já quebra se uma regra travar. Config em
  `checkstyle.xml`, baseline de violações antigas em `checkstyle-suppressions.xml` (ambos na
  raiz de `Backend/`).
  - Rodar isolado (sem subir o resto da suíte): `mvn checkstyle:check` (ou `mvn validate`).
    Via Docker, sem precisar do container `db`:
    ```
    docker run --rm -v gerenciador-eventos-m2:/root/.m2 \
      -v "$(pwd -W)":/workspace-src:ro maven:3.9.7-eclipse-temurin-21-jammy \
      bash -c 'mkdir -p /tmp/build && cp -r /workspace-src/* /tmp/build/ && cd /tmp/build \
        && mvn -Dmaven.repo.local=/root/.m2/repository checkstyle:check'
    ```
  - Regras hoje: `FileLength` (máx. 350 linhas, só `src/main/java` — medido em 2026-09-04:
    a maioria dos arquivos fica bem abaixo de 300; só `BffUserController.java` (465) e
    `UserRepository.java` (351) violavam, e estão na baseline) e `RegexpSinglelineJava`
    banindo `System.out/err.print*`/`println` (id `ProibirConsoleDireto` — baseline:
    `UserRepository.java`, `EventRepository.java`) e
    `printStackTrace()` (id `ProibirPrintStackTrace` — zero ocorrências, já trava sem
    baseline). Use um logger SLF4J (`org.slf4j.Logger`/`LoggerFactory`, ver
    `reservation/ReservationExpiryService`) em vez de escrever direto no console.
  - Método "medir antes de travar": regra sem violação nasce em `error` sem entrada na
    baseline; regra com violação existente também fica em `error`, mas os arquivos que já
    violavam no dia em que a regra entrou ganham uma linha em `checkstyle-suppressions.xml`
    (não corrigidos por essa issue — é o próximo passo). Corrigiu um arquivo da baseline?
    Remova a linha dele do suppressions.
- ArchUnit (`archunit-junit5`, em `src/test/java`), dois testes, sem baseline (zero violações):
  - `LayerBoundaryArchTest` trava se o pacote `gateway` (controllers) depender direto de uma classe
    `@Repository` (DAO) ou de JDBC cru
    (`java.sql.Connection/PreparedStatement/Statement/ResultSet/DriverManager`) — acesso a dado
    deve passar por `Service`.
  - `ModuleBoundaryArchTest` (issue #23) trava: (a) `identity`/`catalog`/`reservation`/
    `notification`/`gateway` só dependem de outro módulo por `<modulo>.api` (nenhum módulo importa `gateway.*`);
    (b) `shared` não depende de módulo de domínio; (c) todo código de produção mora num módulo (nada volta para
    `POJO/Repository/Service`); (d) `<modulo>.api` não depende de classe interna de módulo nenhum
    (só de outros `*.api`, `shared` e libs); (e) `@RestController`/`@Controller` só no `gateway`
    (`controller_so_no_gateway`, issue #25: os demais módulos não expõem HTTP). Ver "Módulos".
  - Rodar isolado: `mvn -Dtest='LayerBoundaryArchTest,ModuleBoundaryArchTest' -DfailIfNoTests=false test`
    (não precisa do container `db`, mesmo comando funciona no container Maven efêmero do
    `run-tests.sh`).

## Módulos (pacote `com.gerenciador.eventos`) — issues #23 e #25

Pacotes-por-serviço, ainda no mesmo processo/deploy/banco (schema `public`): é a preparação da
Fase 5 para a decomposição em microsserviços (CQRS/ES na Fase 6, extração na Fase 7). Dentro de
cada módulo as classes ficam **planas** (sem `POJO/`/`Repository/`/`Service/`), mais o subpacote
`api`: as **interfaces e DTOs públicos** que os outros módulos podem usar.

```
Main.java        entrypoint @SpringBootApplication (único; o component scan cobre os subpacotes)
identity/        UserService, UserRepository, EmailVerificationService, PendingVerification(+Repository),
                 security/ (JwtUtil, JwtAuthenticationFilter, AuthorizationUtil, SecurityConfig)
  api/           User, UserApi, EmailVerificationApi, TokenApi, HostAuthorizationApi
catalog/         EventService, EventRepository, EventPresenterRepository
  api/           Event, EventApi
reservation/     EventWalletService, MyWalletService, ReadModelRefreshService, ReservationExpiryService,
                 EventCapRepository, EventWalletRepository, MyWalletRepository, EventAvailabilityRepository,
                 IdempotencyKeyRepository, CapacityReserver + ReservationWriter (interfaces internas, #24)
  api/           EventCap, EventWallet, MyWallet, EventCapacityApi, EventWalletApi, MyWalletApi,
                 IdempotencyConflictException
notification/    EmailNotificationService, EmailTemplates (envio de e-mail / JavaMailSender)
  api/           EmailNotificationApi
shared/          DatabaseConnection, CorsConfig, SchedulingConfig (infra sem domínio)
gateway/         BffUserController, BffEventController, BffEventWalletController, BffMyWalletController,
                 BffSeedController (@RestController, paths /bff/**) + SeedData; roteia e agrega, sem domínio
```

- **Regra de fronteira**: um módulo (e o `gateway`) só enxerga outro módulo pelo pacote `<modulo>.api`;
  nunca importa `Repository`/`Service`/config interno de outro. `shared` não depende de módulo de
  domínio (módulos dependem dele). Dado de outro módulo entra por id ou DTO da `api`. Travado por
  `ModuleBoundaryArchTest` (ver "Quality gates"); as FKs do SQL ficam como estão (schema é Fase 7).
- **Grafo de dependências** (sempre via `api`):
  `gateway → identity, catalog, reservation`;
  `identity → notification`;
  `catalog → identity` (`UserApi.exists`, valida `creator_id`) e `catalog → reservation`
  (`EventCapacityApi`: `EventRepository.save` cria o `eventcap` na **mesma transação JDBC** do
  `event`, por isso a `Connection` aparece na interface; a hidratação lê a capacidade);
  `reservation → catalog` (`EventApi.exists`, "Evento não encontrado").
  Ciclo `catalog ↔ reservation` aceito por ora, e só pela `api` de cada lado; a interface fica no
  módulo dono do dado (`eventcap` é de `reservation`, `event` é de `catalog`). Some quando a
  `reservation` ganhar read model próprio de eventos (Fase 6/7).
- **`gateway` é módulo, não container (issue #25)**: o antigo pacote `bff` virou `gateway` (roteamento +
  agregação) **dentro do mesmo processo/deploy do monólito**; container próprio só na Fase 7. Os paths HTTP
  continuam `/bff/**` (o contrato do Flutter não mudou; `HttpContractTest` é o juiz). Os controllers só
  roteiam e agregam via `*.api`: a orquestração do reset de senha com MFA (gerar o código + enviar o
  e-mail) mora em `UserApi.requestPasswordResetMfa` (`identity`), não no `BffUserController`. As regras de
  rota/segurança de `/bff/**` **continuam em `identity/security/SecurityConfig`** (única cadeia
  deny-by-default, auditável num arquivo só; movê-las pro `gateway` exigiria um contrato de
  "contribuição de rotas" com bean/interface novos sem mudar comportamento).
- **Decisões**: `SecurityConfig` fica em `identity/security` (conhece as rotas e o filtro JWT, não é
  infra pura — em `shared` violaria a regra); `SeedData` fica em `gateway` (é o corpo do
  `POST /bff/seed` e agrega `User`+`Event`+`EventWallet` de três módulos); o envio de e-mail saiu
  de `EmailVerificationService` para `notification` (o cadastro em duas fases continua em
  `identity`); as `*Api` são implementadas pelas classes que já existiam (`UserService implements
  UserApi`, `EventCapRepository implements EventCapacityApi`...), sem classe nova de adaptação.
- Módulo novo ou chamada entre módulos nova → exponha só o necessário em `<modulo>.api`.

## Persistência — JDBC puro, não JPA

- Os `*Repository` usam `shared.DatabaseConnection.getConnection()` +
  `PreparedStatement` com SQL escrito à mão. `try-with-resources` para fechar conexão.
- `DatabaseConnection` delega ao `DataSource` do Spring (pool **HikariCP**, issue #96), não a
  `DriverManager`: `close()` devolve a conexão ao pool. Tamanho do pool:
  `spring.datasource.hikari.maximum-pool-size` (env `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`,
  default 1000; o Hikari pede conexões ao **pooler** (não ao Postgres), então quem limita o Postgres é
  `POOLER_DEFAULT_POOL_SIZE` (ver "Pooler"). Em conexão direta (testes), o `db` do compose padrão tem
  100, então lá reduza o pool por env) e
  `spring.datasource.hikari.minimum-idle` (env `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE`, default 5:
  o pool cresce sob demanda). **Cada contexto Spring de teste distinto tem seu próprio pool**; sem
  `minimum-idle` pequeno, N contextos em cache segurariam N×50 conexões e estourariam o
  `max_connections`. Coberto por `ConnectionPoolTest` e `ConnectionPoolSizeTest`.
- `spring-boot-starter-data-jpa` está no classpath e `spring.jpa.hibernate.ddl-auto=update`
  está setado, **mas não há nenhuma `@Entity`** → Hibernate não cria/altera nada.
- O schema é definido em `db/init-scripts/*.sql`. Para alterar o modelo, edite o SQL lá
  (ver `db/CLAUDE.md`), não crie entidade.
- **POJO ≠ coluna.** Ex.: `Event` usa `is_EAD`; a tabela `event` tem `ead`. A conversão é
  feita à mão em cada repository — ao mexer num campo, ajuste o mapeamento nos dois lados.
- **`Event` é um POJO de transporte.** Ele ainda carrega `lot_quantity` (= `eventcap.capacity`),
  `quantity` (= `eventcap.quant`), `buy_time_limit` e `presenters`, mas esses campos **não
  são colunas de `event`**: `EventRepository.save` os grava nos agregados `eventcap` /
  `eventpresenter` (numa transação), e `EventService.hydrate(...)` os relê de volta ao sair
  do service. `lot_quantity` na criação: **`>= 1`** (capacidade fixa) ou **`-1`** (sem limite
  de vendas). Constante `EventCap.UNLIMITED == -1`; use `EventCap.isUnlimited()`.
  `buy_time_limit` não informado (`null`) ⇒ `EventService.applyDefaults` iguala à data do
  evento — roda no `createEvent` **e** no `updateEvent`.
- **Busca de eventos sem N+1 (issue #109).** `EventService.searchEvents` filtra no banco
  (`EventRepository.searchByName`: `lower(event_name) LIKE lower(?) ESCAPE '\'`, `%`/`_`/`\` do
  termo escapados; termo em branco = todos, ordem `event_id`) e hidrata em lote
  (`EventService.hydrateAll` → `EventCapRepository.findByEventIds` /
  `EventPresenterRepository.findByEventIds`, `event_id = ANY(?)`): 3 consultas no total,
  independente do nº de eventos. `hydrate` (1 evento) segue igual; `findByCreatorId` ainda faz N+1
  (#61). Coberto por `EventSearchTest`.
- **Agregado `eventcap` isolado.** `EventCapRepository` faz a reserva de vaga por `UPDATE`
  condicional atômico (`tryReserve` → `false` = lotado) e a devolução (`release`). Os métodos
  que precisam ser atômicos com `walletevent` recebem a `Connection` da transação do service.
  Para evento ilimitado (`capacity = -1`) `tryReserve`/`release` são no-op e
  `EventWalletService` trata a reserva como sempre permitida.
- Nomes de tabela em minúsculas: `users`, `event`, `eventcap`, `eventpresenter`, `mywallet`,
  `walletevent`, `pending_verifications`, `event_availability`, `idempotency_key`.

### Pooler (Supavisor, transaction mode) — issue #19

O backend **em Docker** fala com o Postgres pelo Supavisor, não direto: no `environment:` do
`backend` em `Projeto/docker-compose.yml`,
`SPRING_DATASOURCE_URL=jdbc:postgresql://supabase-pooler:6543/meu_banco?prepareThreshold=0` e
`SPRING_DATASOURCE_USERNAME=${POSTGRES_USER}.gerenciador-eventos` (formato `<role>.<tenant>`; o
tenant `gerenciador-eventos` é fixo, ver `Projeto/supabase/README.md`). O `application.properties`
mantém o default de dev direto em `localhost:5433`, e os testes (`run-tests.sh`, `--env-file
../.env`) seguem **direto** no `db:5432`, pela URL do `Projeto/.env`.

- **`prepareThreshold=0`** é o que o PgJDBC precisa: ele só promove um `PreparedStatement` a
  prepared statement nomeado no servidor depois de 5 execuções, e em transaction pooling a próxima
  execução cairia em outra conexão do Postgres (erro típico "prepared statement ... does not exist").
  Com `0`, tudo vai pelo protocolo estendido não nomeado, que o Supavisor aceita.
- **Compatível com transaction pooling porque** (auditoria de `src/main`, 2026-10-16): nenhum
  `SET`/`SET LOCAL`/`SET ROLE`/`set_config`, `search_path`, advisory lock (`pg_advisory*`),
  `LISTEN`/`NOTIFY`, `TEMP TABLE`, `PREPARE`/`DECLARE CURSOR` nem `DISCARD`. Os 15 `SET` do código
  são todos de `UPDATE ... SET` / `ON CONFLICT DO UPDATE SET`. Não há `connectionInitSql` no Hikari
  nem `setReadOnly`/`setCatalog`/`setSchema`/`setTransactionIsolation`. O único estado de conexão
  mexido é `setAutoCommit(false)` → `commit`/`rollback` → `setAutoCommit(true)` (em
  `EventWalletService`, `ReservationExpiryService`, `EventRepository`, `EventPresenterRepository`),
  que o PgJDBC traduz em `BEGIN`/`COMMIT`: a transação inteira (incl. o CAS de `eventcap` e o
  `walletevent`) roda numa conexão real só, que o Supavisor segura até o `COMMIT`/`ROLLBACK`.
  `DatabaseConnection` só devolve conexões do `DataSource`. **Código novo não pode** depender de
  estado de sessão (`SET`, advisory lock de sessão, `LISTEN`, temp table, prepared statement
  nomeado): em transaction mode ele vaza entre clientes ou some.
- **Dimensionamento** (números literais em `Projeto/supabase/docker-compose.pooler.yml`): Hikari `maximum-pool-size` 1000
  (default) / `minimum-idle` 5 → `POOLER_MAX_CLIENT_CONN=1500` (>= Hikari; senão o Supavisor recusa
  cliente). Conexões reais ao Postgres: `POOLER_DEFAULT_POOL_SIZE=45` para o `max_connections=100`
  do compose padrão (+ `DB_POOL_SIZE=5` do próprio Supavisor, 3 reservadas do superuser,
  testes e psql); com `carga/rpm` (`max_connections=300`) o pool vai para 245 sozinho (override + `subir-host-rpm.ps1`). Ou seja,
  o `too many clients` não vem mais do Hikari, e sim do pool do Supavisor + o resto passar do
  `max_connections`.
- **Threads do Tomcat** (issue #124): `server.tomcat.threads.max` = 300 (env `SERVER_TOMCAT_THREADS_MAX`;
  o default do Spring Boot é 200). Na configuração de carga: Tomcat 300, Hikari 1000, pool do Supavisor
  245. Os testes de 2026-10-17 pelo pooler deram o mesmo teto de vazão com 200, 300 e 400 threads, mas 300
  teve o menor p95 (matriz CAS N=200: 155 → 114 ms; escada 50k–70k: 180–319 ms contra 192–392 ms com 200).
  **Virtual threads ficam desligadas** (padrão; não há `spring.threads.virtual.enabled`): sobem a vazão
  (~99k efetivos), mas pioram o p95 — a prioridade é a menor latência. Coberto por `TomcatThreadsTest`.
- Os logs do Supavisor trazem `User requested SSL connection but no downstream cert/key found`:
  o PgJDBC tenta SSL (`sslmode=prefer`), o pooler (sem cert) recusa e ele cai para texto puro, na
  rede interna do Docker. É inofensivo.

## Padrão dos Services

Cada service expõe, além do CRUD: `canBeCreated(...)` / `getValidationErrors(...)` para o
endpoint `/validate` correspondente (valida sem persistir). Fluxo típico de `create`:
`applyDefaults` → `validateRequired` → `validateRules` → `validateDuplicates*` → `repository.save`.
Erros de validação = `IllegalArgumentException` (vira HTTP 400 no controller).

## Rotas e segurança

- **Superfície HTTP única sob `/bff/**`** (issue #78): `gateway/BffUserController` (users — CRUD,
  login, `/me`, cadastro em duas fases, reset de senha com MFA, reCAPTCHA),
  `gateway/BffEventController` (events — CRUD, validate, search, imagem),
  `gateway/BffEventWalletController` (event-wallets — addLink/confirm/validate/removeLink/
  listByUser), `gateway/BffMyWalletController` (my-wallets — validate/getByUser/ensure),
  `gateway/BffSeedController` (seed, dev). Não existe mais `/api/**` de negócio nem um 2º
  `@SpringBootApplication`; `Main.java` é o único entrypoint e as classes `gateway/*` são
  `@RestController` de nível top-level, descobertas pelo component scan do próprio `Main`.
- **Nginx do frontend faz proxy de `/bff/` → `backend:8081/bff/` sem rewrite/strip**
  (`nginx.conf`): o path chega igual no backend. Em acesso direto ao backend (dev sem
  Nginx), use o mesmo path `/bff/...`.
- `identity/security/SecurityConfig`: stateless, CSRF off. Desde o PR #79 (issue #78), deny-by-default também
  dentro de `/bff/**` — só a lista abaixo é pública, todo o resto exige
  `Authorization: Bearer <jwt>` (401 fixo via `HttpStatusEntryPoint`, sem token/token
  inválido numa rota protegida).
  - **Público**: `POST /bff/users/login`, `POST /bff/users/request-verification`,
    `POST /bff/users/verify-email`, `POST /bff/users/resend-verification-code`,
    `POST /bff/users/verify-recaptcha`, `GET /bff/events/**` (inclui `/bff/events/search` e
    `/bff/events/{id}/image` — este último servido via `<img>`/`Image.network` no Flutter,
    que nunca manda header customizado), `POST /bff/seed` (ferramenta de dev) e
    `GET /actuator/health`.
  - **Autenticado**: todo o resto de `/bff/**` — `/bff/event-wallets/**` e
    `/bff/my-wallets/**` por inteiro, mutações de evento (`POST/PUT/DELETE /bff/events/**`,
    inclusive upload de imagem) e o resto de `/bff/users/**` (`/me`, `GET/PUT /{id}`,
    `/{id}/password`, `/{id}/password-with-mfa`, `/{id}/request-password-reset-mfa`,
    `/{id}/verify-password-reset-mfa`, `GET /email/{email}`, `/validate`,
    `POST /` criação direta).
  - **Preflight CORS**: `OPTIONS /**` é liberado por verbo (`HttpMethod.OPTIONS`, não por
    path) ANTES das regras acima, como defesa em profundidade — o `CorsFilter` que
    `.cors(...)` registra no filter chain já intercepta e responde o preflight antes do
    `AuthorizationFilter`, então na prática o preflight já não caía em `.authenticated()`
    mesmo sem essa regra (verificado empiricamente, ver
    `HttpContractTest#corsPreflight_onProtectedRoute_isNotBlockedByAuth`); a regra explícita
    protege contra reordenar filtros ou remover `.cors(...)` no futuro.
- `JwtAuthenticationFilter` valida o token e põe o e-mail como `subject`/username. Não há
  roles dinâmicas no token (authority fixa `USER`).
- `identity/security/AuthorizationUtil.isCurrentUserHost()` lê `users.host` (coluna criada sem aspas
  no DDL como `Host`, então o Postgres dobra pra minúsculas — `UserRepository` lê/grava com
  `host` minúsculo, igual `isactive`). Desde o PR #79 (issue #78), `gateway/BffEventController.createEvent`
  /`updateEvent`/`deleteEvent` chamam `ensureUserIsHost()` (IllegalArgumentException → HTTP
  400, padrão do projeto); leitura (get/search/list/validate) e a imagem não ganham esse
  gate. **Nenhuma rota promove um usuário a host** (de propósito — `UserRepository.save`/
  `update` não persistem `host`, só o leem, pra não abrir auto-promoção via
  `PUT /bff/users/{id}`); hoje só existe host via SQL direto/seed manual. Promover host por
  um fluxo de produto (ex.: endpoint de admin) é gap conhecido, não resolvido nesta issue.
- Senhas: `BCryptPasswordEncoder` (bean em `SecurityConfig`). Nunca gravar senha em claro
  (o seed SQL grava em claro só para dados de teste — não replicar em código de produção).
- CORS está definido **duas vezes** (`shared.CorsConfig` e o bean em `SecurityConfig`, este
  bem permissivo). Ao mexer em CORS, alinhe os dois e veja `CORS_CONFIG.md`. Os dois expõem o header
  de resposta `Idempotency-Key` (#24).

## Inscrição em evento — reserva em duas fases (CAS)

`reservation/EventWalletService`:

1. `addLink` (`POST .../event-wallets`): valida, checa janela de compra (`eventcap.buy_time_limit`)
   e duplicidade (`existsActive` = pending/confirmed), então numa **transação** (uma `Connection`,
   `setAutoCommit(false)` manual, não `@Transactional`): (a) reivindica a `Idempotency-Key`
   (`IdempotencyKeyRepository.claim`); (b) `CapacityReserver.tryReserve(conn, eventId)` (UPDATE
   condicional) → se `false`, rollback + `IllegalArgumentException("Evento lotado")`; (c)
   `ReservationWriter.reservePending(conn, ...)` grava `walletevent` como `pending` com
   `expires_at = now + eventcap.max_time_in_line` (UPSERT, revive linhas `expired`/`cancelled`);
   (d) commit.
2. `confirm` (`POST .../event-wallets/confirm`): `pending → confirmed` (`expires_at` = NULL).
3. `removeLink` (`DELETE .../event-wallets`): numa transação, um `UPDATE` condicional único
   (`cancelIfActive`: `pending`/`confirmed` → `cancelled`) e `release` **só se** esse UPDATE
   afetou 1 linha — evita devolução dupla de vaga em cancelamento concorrente / corrida com o
   job. Reserva já `cancelled`/`expired`/inexistente ⇒ no-op silencioso (idempotente).
4. `reservation/ReservationExpiryService` — `@Scheduled` (a cada `reservation.expiry.scan.ms`,
   default 30s; `@EnableScheduling` em `shared/SchedulingConfig`): varre `pending` vencidas e,
   por transação, `expireIfStale` (condicional, não pisa em confirmação concorrente) + `release`.

O `gateway/BffSeedController` (`POST /bff/seed`) cria inscrições já confirmadas (`addLink`
seguido de `confirm`).

### Idempotency-Key, invariante e compensação — issue #24

- **Invariante**: (1) reservas ativas (`pending`/`confirmed`) ≤ capacidade — o CAS em `eventcap` e o
  `CHECK eventcap_quant_bounds` garantem; (2) **uma `Idempotency-Key` produz no máximo uma reserva**
  (PK de `idempotency_key`). Nenhuma outra requisição observa um estado em que a vaga foi debitada
  sem a reserva gravada (ou o contrário): tudo é uma transação.
- **Header** `Idempotency-Key` em `POST /bff/event-wallets`, **opcional e aditivo**: sem ele, o servidor
  gera um UUID; a chave usada (gerada ou recebida) volta no header `Idempotency-Key` da resposta 201
  (exposto no CORS). O corpo JSON e o contrato do Flutter não mudam (`EventWallet.idempotencyKey` é
  `@JsonIgnore`, só em memória). Máx. 255 caracteres (senão 400).
- **Repetição** (mesma chave, mesmo usuário e evento) devolve **201 com o resultado da primeira**
  (`status = pending`, `expiresAt` salvo — mesmo depois de confirmar/cancelar a reserva) e **não** toca
  `eventcap`. **Mesma chave com outro usuário/evento ⇒ 422** (`IdempotencyConflictException`; segue o rascunho
  IETF do header, que reserva 422 a "mesma chave, payload diferente"). Como o `INSERT` da chave
  *bloqueia* até a transação concorrente terminar, não há estado "em andamento" visível e portanto
  nenhum 409. Para reservar de novo o mesmo par depois de cancelar, use outra chave.
- **Só reserva bem-sucedida grava a chave**: lotado / vínculo já existente fazem rollback e a mesma
  chave pode ser repetida depois.
- **Concorrência**: duas requisições com a mesma chave serializam no `INSERT ... ON CONFLICT DO NOTHING`
  (a PK segura a 2ª até a 1ª dar commit/rollback). 1ª commita → a 2ª lê a linha salva e devolve o
  resultado (sem CAS). 1ª faz rollback → a 2ª segue como primeira. Antes da transação, se uma checagem
  falha (ex.: "Vínculo já existente" numa repetição sequencial), `addLink` consulta a chave e devolve o
  resultado salvo — a consulta extra só roda no caminho de erro.
- **Fronteira interna**: `CapacityReserver` ("decrementar capacidade", `EventCapRepository`) e
  `ReservationWriter` ("gravar reserva", `EventWalletRepository`) são interfaces de `reservation` (não
  `api`); `EventWalletService.addLink` orquestra as duas na **mesma `Connection`**. É a costura do split
  futuro `stock` + `wallet` (#47).
- **Ponto de compensação**: se a gravação da reserva (ou qualquer passo) falha **depois** do CAS, faz-se
  `rollback` da `Connection` — a vaga e a chave voltam juntas. No split futuro `stock` + `wallet`, o
  `rollback` deixa de cobrir os dois lados e a compensação vira `CapacityReserver.release` (já existe;
  é o que `removeLink`/expiração usam), idempotente por chave.
- **Braço `naive`**: a idempotência vale nos dois caminhos (a reivindicação da chave está na mesma
  transação curta; só o `tryReserve` é pulado). Na repetição sequencial a checagem do read model
  ("Evento lotado") falha como o "Vínculo já existente" e cai no mesmo desvio para o resultado salvo.
- **Retenção**: **sem limpeza por ora** — uma linha por reserva bem-sucedida, nunca apagada (e sem FK:
  não some com o evento). Sob carga a tabela cresce sem limite; follow-up: apagar por `created_at` num job.
- **Latência (ponto 2 da série, não medido)**: o caminho quente ganhou 1 `INSERT` (na transação, +1 ida ao
  banco) e a tabela ganhou escrita por reserva e índice da PK. A consulta de repetição fica fora do caminho
  quente (só em erro).

### Feature flag `reservation.readpath` — caminho CAS (default) vs. ingênuo (experimento)

`addLink` tem dois caminhos de checagem de capacidade, escolhidos por
`reservation.readpath` (`@Value` em `EventWalletService`, env `RESERVATION_READPATH`):

- **`cas`** (default) — o que está descrito acima: `EventCapRepository.tryReserve` (UPDATE
  condicional atômico). Nunca oversella. **Zero mudança** de comportamento.
- **`naive`** — lê o read model **defasado** `event_availability` via
  `EventAvailabilityRepository.findAvailable` e insere em `walletevent` **sem CAS** (só
  `reservePending`, **nunca** toca `eventcap`). `available == null` ⇒ erro "ainda não
  calculada"; `== 0` ⇒ "Evento lotado"; `> 0` ou `-1` ⇒ segue. Oversella proporcional ao
  lag do read model — é o **braço de controle** do experimento de concorrência da Fase 2
  (issues #12/#13), não um bug.

`reservation/ReadModelRefreshService` — `@Scheduled` (a cada `readmodel.availability.refresh.ms`,
default 5000; env `READMODEL_AVAILABILITY_REFRESH_MS`): um único
`INSERT ... SELECT ... ON CONFLICT DO UPDATE` que recalcula `event_availability`
(`available = capacity - reservas ativas`, piso 0; `-1` = ilimitado). Intervalo maior ⇒
read model mais defasado ⇒ mais overselling no caminho ingênuo. `refresh()` é `public` e
chamável direto (os testes o invocam sem esperar o timer).

## Cadastro em duas fases + e-mail

1. `POST /bff/users/request-verification` grava em `pending_verifications` (hash da senha +
   código de 6 dígitos, expira em 5 min — ver `application.properties`).
2. `POST /bff/users/verify-email` confere o código e só então insere em `users`.
3. `identity/EmailVerificationService` pede o envio ao módulo `notification` (`EmailNotificationApi`), que manda o código via SMTP Gmail (`spring-boot-starter-mail`),
   templates em `notification/EmailTemplates`.
4. reCAPTCHA v3: `POST /bff/users/verify-recaptcha` (em `gateway/BffUserController`) valida o
   token com a Google usando `RECAPTCHA_SECRET_KEY`.

## Config

- `application.properties` lê tudo de env var com default de dev:
  `SPRING_DATASOURCE_*` (inclui `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` e
  `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE`), `JWT_SECRET`, `JWT_EXPIRATION_SECONDS`, `MAIL_*`,
  `RECAPTCHA_*`, `SERVER_TOMCAT_THREADS_MAX` (default `300`),
  `RESERVATION_READPATH` (default `cas`; `naive` = braço de controle do experimento da
  Fase 2), `READMODEL_AVAILABILITY_REFRESH_MS` (default `5000`; intervalo do job de refresh
  do read model `event_availability` = o "lag" do experimento).
  Valores reais vêm de `Projeto/.env` — não commitar, não imprimir.
- Actuator (issue #92): `spring-boot-starter-actuator` com **só** `health` exposto
  (`management.endpoints.web.exposure.include=health`); `GET /actuator/health` público devolve
  `{"status":"UP"}` (depende do Postgres). `management.health.mail.enabled=false` — o health do
  SMTP derrubaria o status por falha de rede/credencial do Gmail. Cobertura em `HttpContractTest`.
- `spring.jpa.show-sql=true` e log de SQL em DEBUG: saída verbosa é esperada em dev.

## Ao mexer aqui

- Novo endpoint → entra em `gateway/*` (superfície HTTP única, ver "Rotas e segurança"), no
  controller do agregado correspondente. Desde o PR #79 (issue #78) o default dentro de `/bff/**` é
  **autenticado**: endpoint novo já nasce protegido; só adicione em `SecurityConfig` se ele
  precisar ser público (raro — normalmente já resolvido pelo padrão `GET /bff/events/**`).
- Mudou path, método ou forma de request/response de um endpoint consumido pelo Flutter →
  atualize `src/test/.../HttpContractTest` (trava a superfície pro harness de carga da
  issue #11) e o `frontend/CLAUDE.md`.
- Nova query → JDBC parametrizado (`?`), nunca concatenar string em SQL.
- Rode `./run-tests.sh` (com `db` no ar) antes de dar por pronto.
