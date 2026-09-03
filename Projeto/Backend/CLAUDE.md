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
- Não há testes unitários isolados; tudo é de integração com banco.

## Camadas (pacote `com.gerenciador.eventos`)

```
Main.java              entrypoint @SpringBootApplication
POJO/                  objetos simples (getters/setters), SEM anotação JPA
Repository/            acesso a dados — JDBC puro
Service/               regras de negócio e validação
Controller/            endpoints /api/**  e  /api/bff/**
bff/BffApplication     endpoints /bff/**  (classes @RestController internas)
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

- **Nginx do frontend remove o prefixo `/api/`** antes de encaminhar para `backend:8081`
  (`/api/bff/users/login` → `/bff/users/login`). Em acesso direto ao backend, use o path real.
- Dois conjuntos de controllers coexistem (migração inacabada):
  `Controller/*` sob `/api/**` e `/api/bff/**`; `bff/BffApplication` sob `/bff/**`.
  O frontend mira principalmente a camada BFF.
- `security/SecurityConfig`: stateless, CSRF off. **Público**: `/bff/**`, `/api/users/**`,
  `/api/seed`, `/actuator/health`, e os fluxos de reset de senha com MFA. Todo o resto exige
  `Authorization: Bearer <jwt>`.
- `JwtAuthenticationFilter` valida o token e põe o e-mail como `subject`/username. Não há
  roles dinâmicas no token (authority fixa `USER`).
- Autorização por papel: `security/AuthorizationUtil.isCurrentUserHost()` lê `users.Host`
  (coluna com maiúscula) — gate para criação/edição de evento.
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

O `SeedController` cria inscrições já confirmadas (`addLink` seguido de `confirm`).

## Cadastro em duas fases + e-mail

1. `POST /api/users/request-verification` grava em `pending_verifications` (hash da senha +
   código de 6 dígitos, expira em 5 min — ver `application.properties`).
2. `POST /api/users/verify-email` confere o código e só então insere em `users`.
3. `Service/EmailVerificationService` envia o código via SMTP Gmail (`spring-boot-starter-mail`),
   templates em `config/EmailTemplates`.
4. reCAPTCHA v3: `Controller/RecaptchaController` valida o token com a Google usando
   `RECAPTCHA_SECRET_KEY`.

## Config

- `application.properties` lê tudo de env var com default de dev:
  `SPRING_DATASOURCE_*`, `JWT_SECRET`, `JWT_EXPIRATION_SECONDS`, `MAIL_*`, `RECAPTCHA_*`.
  Valores reais vêm de `Projeto/.env` — não commitar, não imprimir.
- `spring.jpa.show-sql=true` e log de SQL em DEBUG: saída verbosa é esperada em dev.

## Ao mexer aqui

- Novo endpoint → decida a camada (BFF vs `/api`) coerente com o vizinho; ajuste
  `SecurityConfig` se for público.
- Nova query → JDBC parametrizado (`?`), nunca concatenar string em SQL.
- Rode `./run-tests.sh` (com `db` no ar) antes de dar por pronto.
