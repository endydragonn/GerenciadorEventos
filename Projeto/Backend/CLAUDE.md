# CLAUDE.md — Backend (API Spring Boot)

API REST do Gerenciador de Eventos. Spring Boot 3.5.7, Java 21, Maven (sem wrapper `mvnw`).
Leia também o CLAUDE.md da raiz (regras gerais) e o de `db/` (schema).

## Rodar

- Via Docker (recomendado): `docker compose -f Projeto/docker-compose.yml up -d --build backend`
  — porta `8081`, container `gerenciador-backend`.
- Local: `cd Projeto/Backend && mvn spring-boot:run` — precisa de um Postgres em
  `localhost:5433`. `application.properties` tem defaults de dev para as vars do `.env`.
- Entrypoint do jar: `com.gerenciador.eventos.Main` (fixado no `pom.xml` e no `Dockerfile`).
- Build da imagem: multi-stage — compila com `maven:3.9.7-temurin-21`, roda em `temurin:25-jre`.

## Testes

`./run-tests.sh` — sobe um container Maven na rede `gerenciador-net`, com `--env-file ../.env`,
e roda `mvn clean test`. **Exige o container `db` no ar**: os testes batem num Postgres real.

- `src/test/.../SchemaValidationTest` valida o schema real contra o esperado.
- `TestSupport/GlobalDbTruncator` dá `TRUNCATE` nas tabelas entre testes — não há mock de banco.
- `EventTest`, `UserTest`, `EventWalletTest`, `MyWalletTest` exercitam services + repositories.
- `HttpContractTest` (`webEnvironment = RANDOM_PORT` + `TestRestTemplate`) fixa path + método
  + forma de request/response de cada endpoint `/bff/**` que o Flutter consome — bate no
  HTTP real (inclusive `SecurityConfig`), não MockMvc. `JavaMailSender` é mockado
  (`@MockitoBean`) e `RECAPTCHA_SECRET_KEY` é forçada vazia via `@TestPropertySource` para
  não depender de SMTP/Google reais.
- Não há testes unitários isolados; tudo é de integração com banco (exceto os mocks pontuais
  do `HttpContractTest` para dependências externas).
- `LayerBoundaryArchTest` (ArchUnit) é a exceção: não usa Spring/banco, só analisa bytecode.
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
    `UserRepository.java`, `EventRepository.java`, `EmailVerificationService.java`) e
    `printStackTrace()` (id `ProibirPrintStackTrace` — zero ocorrências, já trava sem
    baseline). Use um logger SLF4J (`org.slf4j.Logger`/`LoggerFactory`, ver
    `Service/ReservationExpiryService`) em vez de escrever direto no console.
  - Método "medir antes de travar": regra sem violação nasce em `error` sem entrada na
    baseline; regra com violação existente também fica em `error`, mas os arquivos que já
    violavam no dia em que a regra entrou ganham uma linha em `checkstyle-suppressions.xml`
    (não corrigidos por essa issue — é o próximo passo). Corrigiu um arquivo da baseline?
    Remova a linha dele do suppressions.
- ArchUnit (`archunit-junit5`, teste `LayerBoundaryArchTest` em `src/test/java`) trava se o
  pacote `bff` (controllers) depender direto de `Repository` (DAO) ou de JDBC cru
  (`java.sql.Connection/PreparedStatement/Statement/ResultSet/DriverManager`) — acesso a dado
  deve passar por `Service`. Zero violações hoje, regra já trava o build, sem baseline.
  - Rodar isolado: `mvn -Dtest=LayerBoundaryArchTest -DfailIfNoTests=false test` (não precisa
    do container `db`, mesmo comando funciona no container Maven efêmero do `run-tests.sh`).

## Camadas (pacote `com.gerenciador.eventos`)

```
Main.java              entrypoint @SpringBootApplication (único)
POJO/                  objetos simples (getters/setters), SEM anotação JPA
Repository/            acesso a dados — JDBC puro
Service/               regras de negócio e validação
bff/                   superfície HTTP única: /bff/** (arquivos @RestController próprios)
security/              JWT + Spring Security + BCrypt + papel "host"
config/                DatabaseConnection, CorsConfig, EmailTemplates
```

## Persistência — JDBC puro, não JPA

- `Repository/*` usam `config.DatabaseConnection.getConnection()` +
  `PreparedStatement` com SQL escrito à mão. `try-with-resources` para fechar conexão.
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
- **Agregado `eventcap` isolado.** `EventCapRepository` faz a reserva de vaga por `UPDATE`
  condicional atômico (`tryReserve` → `false` = lotado) e a devolução (`release`). Os métodos
  que precisam ser atômicos com `walletevent` recebem a `Connection` da transação do service.
  Para evento ilimitado (`capacity = -1`) `tryReserve`/`release` são no-op e
  `EventWalletService` trata a reserva como sempre permitida.
- Nomes de tabela em minúsculas: `users`, `event`, `eventcap`, `eventpresenter`, `mywallet`,
  `walletevent`, `pending_verifications`.

## Padrão dos Services

Cada service expõe, além do CRUD: `canBeCreated(...)` / `getValidationErrors(...)` para o
endpoint `/validate` correspondente (valida sem persistir). Fluxo típico de `create`:
`applyDefaults` → `validateRequired` → `validateRules` → `validateDuplicates*` → `repository.save`.
Erros de validação = `IllegalArgumentException` (vira HTTP 400 no controller).

## Rotas e segurança

- **Superfície HTTP única sob `/bff/**`** (issue #78): `bff/BffUserController` (users — CRUD,
  login, `/me`, cadastro em duas fases, reset de senha com MFA, reCAPTCHA),
  `bff/BffEventController` (events — CRUD, validate, search, imagem),
  `bff/BffEventWalletController` (event-wallets — addLink/confirm/validate/removeLink/
  listByUser), `bff/BffMyWalletController` (my-wallets — validate/getByUser/ensure),
  `bff/BffSeedController` (seed, dev). Não existe mais `/api/**` de negócio nem um 2º
  `@SpringBootApplication`; `Main.java` é o único entrypoint e as classes `bff/*` são
  `@RestController` de nível top-level, descobertas pelo component scan do próprio `Main`.
- **Nginx do frontend faz proxy de `/bff/` → `backend:8081/bff/` sem rewrite/strip**
  (`nginx.conf`): o path chega igual no backend. Em acesso direto ao backend (dev sem
  Nginx), use o mesmo path `/bff/...`.
- `security/SecurityConfig`: stateless, CSRF off. Desde a issue #79, deny-by-default também
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
- `security/AuthorizationUtil.isCurrentUserHost()` lê `users.host` (coluna criada sem aspas
  no DDL como `Host`, então o Postgres dobra pra minúsculas — `UserRepository` lê/grava com
  `host` minúsculo, igual `isactive`). Desde a issue #79, `bff/BffEventController.createEvent`
  /`updateEvent`/`deleteEvent` chamam `ensureUserIsHost()` (IllegalArgumentException → HTTP
  400, padrão do projeto); leitura (get/search/list/validate) e a imagem não ganham esse
  gate. **Nenhuma rota promove um usuário a host** (de propósito — `UserRepository.save`/
  `update` não persistem `host`, só o leem, pra não abrir auto-promoção via
  `PUT /bff/users/{id}`); hoje só existe host via SQL direto/seed manual. Promover host por
  um fluxo de produto (ex.: endpoint de admin) é gap conhecido, não resolvido nesta issue.
- Senhas: `BCryptPasswordEncoder` (bean em `SecurityConfig`). Nunca gravar senha em claro
  (o seed SQL grava em claro só para dados de teste — não replicar em código de produção).
- CORS está definido **duas vezes** (`config.CorsConfig` e o bean em `SecurityConfig`, este
  bem permissivo). Ao mexer em CORS, alinhe os dois e veja `CORS_CONFIG.md`.

## Inscrição em evento — reserva em duas fases (CAS)

`Service/EventWalletService`:

1. `addLink` (`POST .../event-wallets`): valida, checa janela de compra (`eventcap.buy_time_limit`)
   e duplicidade (`existsActive` = pending/confirmed), então numa **transação**:
   `EventCapRepository.tryReserve(conn, eventId)` (UPDATE condicional) → se `false`, rollback +
   `IllegalArgumentException("Evento lotado")`; se `true`, grava `walletevent` como `pending`
   com `expires_at = now + eventcap.max_time_in_line` (UPSERT, revive linhas `expired`/`cancelled`).
2. `confirm` (`POST .../event-wallets/confirm`): `pending → confirmed` (`expires_at` = NULL).
3. `removeLink` (`DELETE .../event-wallets`): numa transação, um `UPDATE` condicional único
   (`cancelIfActive`: `pending`/`confirmed` → `cancelled`) e `release` **só se** esse UPDATE
   afetou 1 linha — evita devolução dupla de vaga em cancelamento concorrente / corrida com o
   job. Reserva já `cancelled`/`expired`/inexistente ⇒ no-op silencioso (idempotente).
4. `Service/ReservationExpiryService` — `@Scheduled` (a cada `reservation.expiry.scan.ms`,
   default 30s; `@EnableScheduling` em `config/SchedulingConfig`): varre `pending` vencidas e,
   por transação, `expireIfStale` (condicional, não pisa em confirmação concorrente) + `release`.

O `bff/BffSeedController` (`POST /bff/seed`) cria inscrições já confirmadas (`addLink`
seguido de `confirm`).

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
  (issues #11/#12), não um bug.

`Service/ReadModelRefreshService` — `@Scheduled` (a cada `readmodel.availability.refresh.ms`,
default 5000; env `READMODEL_AVAILABILITY_REFRESH_MS`): um único
`INSERT ... SELECT ... ON CONFLICT DO UPDATE` que recalcula `event_availability`
(`available = capacity - reservas ativas`, piso 0; `-1` = ilimitado). Intervalo maior ⇒
read model mais defasado ⇒ mais overselling no caminho ingênuo. `refresh()` é `public` e
chamável direto (os testes o invocam sem esperar o timer).

## Cadastro em duas fases + e-mail

1. `POST /bff/users/request-verification` grava em `pending_verifications` (hash da senha +
   código de 6 dígitos, expira em 5 min — ver `application.properties`).
2. `POST /bff/users/verify-email` confere o código e só então insere em `users`.
3. `Service/EmailVerificationService` envia o código via SMTP Gmail (`spring-boot-starter-mail`),
   templates em `config/EmailTemplates`.
4. reCAPTCHA v3: `POST /bff/users/verify-recaptcha` (em `bff/BffUserController`) valida o
   token com a Google usando `RECAPTCHA_SECRET_KEY`.

## Config

- `application.properties` lê tudo de env var com default de dev:
  `SPRING_DATASOURCE_*`, `JWT_SECRET`, `JWT_EXPIRATION_SECONDS`, `MAIL_*`, `RECAPTCHA_*`,
  `RESERVATION_READPATH` (default `cas`; `naive` = braço de controle do experimento da
  Fase 2), `READMODEL_AVAILABILITY_REFRESH_MS` (default `5000`; intervalo do job de refresh
  do read model `event_availability` = o "lag" do experimento).
  Valores reais vêm de `Projeto/.env` — não commitar, não imprimir.
- `spring.jpa.show-sql=true` e log de SQL em DEBUG: saída verbosa é esperada em dev.

## Ao mexer aqui

- Novo endpoint → entra em `bff/*` (superfície HTTP única, ver "Rotas e segurança"), no
  controller do agregado correspondente. Desde a issue #79 o default dentro de `/bff/**` é
  **autenticado**: endpoint novo já nasce protegido; só adicione em `SecurityConfig` se ele
  precisar ser público (raro — normalmente já resolvido pelo padrão `GET /bff/events/**`).
- Mudou path, método ou forma de request/response de um endpoint consumido pelo Flutter →
  atualize `src/test/.../HttpContractTest` (trava a superfície pro harness de carga da
  issue #11) e o `frontend/CLAUDE.md`.
- Nova query → JDBC parametrizado (`?`), nunca concatenar string em SQL.
- Rode `./run-tests.sh` (com `db` no ar) antes de dar por pronto.
