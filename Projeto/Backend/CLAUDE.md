# CLAUDE.md — Backend (API Spring Boot)

API REST do Gerenciador de Eventos. Spring Boot 3.5.7, Java 21, Maven (sem wrapper `mvnw`).
Leia também o CLAUDE.md da raiz (regras gerais) e o de `db/` (schema).

## Rodar

- Via Docker (recomendado): `docker compose -f Projeto/docker-compose.yml up -d --build backend`
  — porta `8081`, container `gerenciador-backend`. Sobe junto `db`, `supabase-pooler` e `redpanda` (o
  backend espera o broker ficar `healthy`); no Docker o backend fala **pelo pooler** (Supavisor), ver
  "Pooler" em Persistência, e com o broker em `redpanda:9092` (`SPRING_KAFKA_BOOTSTRAP_SERVERS`).
- Serviço `notification` (issue #32): `docker compose -f Projeto/docker-compose.yml up -d --build notification`
  — container `gerenciador-notification`, sem porta. Ver "Serviço notification". O `backend` NÃO depende dele.
- Local: `cd Projeto/Backend && mvn -pl monolito -am spring-boot:run` (ou `mvn -pl notification -am spring-boot:run`) —
  precisa de um Postgres em `localhost:5433`. `application.properties` de cada módulo tem defaults de dev para as vars do `.env`,
  **exceto** `MAIL_USERNAME`/`MAIL_PASSWORD` do `notification` (sem default de propósito: não vai e-mail pessoal no código):
  para rodar o serviço local, exporte as duas.
- Entrypoint do jar do monólito: `com.gerenciador.eventos.Main` (fixado em `monolito/pom.xml` e `monolito/Dockerfile`); o do
  `notification`: `com.gerenciador.eventos.notification.NotificationApplication`.
- Build da imagem: um Dockerfile por serviço (`monolito/Dockerfile`, `notification/Dockerfile`), contexto = `Projeto/Backend/`
  (a raiz do multi-módulo); multi-stage — compila com `maven:3.9.7-temurin-21` (`-pl <serviço> -am`), roda em `temurin:25-jre`.

## Layout multi-módulo Maven — issue #32 (Fase 7)

`Projeto/Backend/` deixou de ser um módulo só: o `pom.xml` da raiz é o **agregador** (`packaging=pom`, parent
`spring-boot-starter-parent` 3.5.7, `dependencyManagement` de `shared`, compiler e Checkstyle herdados por todos). Cada
serviço é um módulo com o próprio jar e Dockerfile; o que é comum é uma lib.

```
Backend/
  pom.xml                agregador (módulos: shared, monolito, notification)
  checkstyle*.xml        um só para todos os módulos (${maven.multiModuleProjectDirectory})
  run-tests.sh           roda o reactor inteiro
  shared/                LIB (jar simples, sem repackage): DatabaseConnection + shared.events (EventStore, relay, consumer,
                         replay...) + NotificationEvents (contrato do stream identity.notification)
  monolito/              identity, catalog, reservation, gateway (/bff/**), CorsConfig/SchedulingConfig; src/, Dockerfile, pom.xml
  notification/          serviço extraído (README.md próprio); src/, Dockerfile, pom.xml
  db/                    schema (fora do Maven)
```

- **Fronteira física**: `notification` NÃO depende de `monolito` (só de `shared`) e não lê tabela do monólito; tem schema próprio
  (`notification`) e processo/imagem próprios. `shared` só leva o que mais de um serviço usa de verdade; o pacote Java continua
  `com.gerenciador.eventos.shared[.events]` (mesmos imports do monólito). **Módulo novo** (a #33 `reservation` será o próximo):
  novo diretório com `pom.xml` (parent `backend`, dep em `shared`), uma linha em `<modules>` do agregador, um
  `<serviço>/Dockerfile` copiado dos existentes e um serviço no compose.
- **Testes** de cada módulo ficam no próprio módulo. Os de `shared.events` (e o `TestSupport`) continuam em `monolito/src/test`,
  porque sobem o contexto Spring do monólito (`Main`) para exercitar a lib com Postgres e Redpanda reais.
- `Backend/.mvn/maven.config` existe para o Maven achar a raiz do multi-módulo (`${maven.multiModuleProjectDirectory}`, onde
  moram `checkstyle*.xml`) mesmo rodando de dentro de `monolito/` ou `notification/` (IDE); sem a pasta `.mvn` o Maven usa o cwd e o
  Checkstyle não acha a config.
- Comandos: tudo na raiz de `Backend/`. `mvn -pl monolito -am test` (monólito + shared), `mvn -pl notification -am test`,
  `mvn -Dtest=ClasseTest -Dsurefire.failIfNoSpecifiedTests=false test` (uma classe, em qualquer módulo). `-pl X -am` compila
  `shared` junto; sem `-am` o Maven procura `shared` no repositório local e falha.

## Testes

`./run-tests.sh` — sobe um container Maven na rede `gerenciador-net`, com `--env-file ../.env`
(URL direta `db:5432`, **sem passar pelo pooler**),
e roda `mvn -fae clean test` na raiz: o **reactor inteiro** (`shared`, `monolito`, `notification`; `-fae` = um módulo vermelho
não esconde o resultado dos outros). Antes disso apaga `target/surefire-reports` sozinho, então o total do
resumo reflete só os testes desta rodada — não precisa limpar na mão; os relatórios de todos os módulos são copiados para
`Backend/target/surefire-reports` (nomes de classe únicos). **Pré-requisito do `notification` (#32):** o schema `notification`
no banco (`db/migrations/20261024-notification-schema.sql`, num volume antigo; bancos novos já nascem com ele). **Pare também o
`gerenciador-notification`** (`docker stop gerenciador-notification`): o relay dos testes do monólito publica em
`identity.notification` e um notification vivo mandaria e-mail de verdade (SMTP real) para os endereços de teste; o script aborta
com mensagem clara se ele estiver rodando. **Ao fim da rodada (verde ou vermelha) o script move o consumer group `notification` para o
FIM do tópico** (`docker exec gerenciador-redpanda rpk group seek notification --to end --topics identity.notification
--allow-new-topics`): os testes publicam ali (direto e pelo relay), e sem isso o próximo `up` do serviço (offset atrasado, ou group
novo em `earliest`) mandaria e-mail REAL do Gmail para cada endereço de teste. **Trade-off:** um e-mail legítimo que estivesse
pendente antes da rodada é pulado (aceitável em dev: o backend, e portanto o cadastro real, precisa estar parado para testar). Se o
seek falhar, o script avisa e imprime o comando: rode-o ANTES de qualquer `up` do `notification`.
**Exige os containers `db` e `redpanda` no ar** (`docker compose -f Projeto/docker-compose.yml up -d db redpanda`;
o script aborta com mensagem clara se faltar um): os testes batem num Postgres e num Redpanda reais.
**Pare o `gerenciador-backend` antes** (`docker stop gerenciador-backend`): o relay e o consumer dele (ligados no compose desde a #28/#29)
compartilham o `meu_banco` e drenariam o `event_outbox` dos testes (quebrando `ReservationReplayTest` e a prova de overselling do
naive); o script aborta com mensagem clara se o container estiver rodando. O script
passa `SPRING_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092` e `EVENTS_CONSUMER_GROUP=gerenciador-eventos-test` ao container
de teste (group próprio: os testes nunca entram no group do backend vivo que consome do mesmo Redpanda).

- `monolito/src/test/.../SchemaValidationTest` valida o schema real contra o esperado (inclui `notification.processed_event`).
- `TestSupport/GlobalDbTruncator` dá `TRUNCATE` (início/fim da suíte) em **todas** as tabelas dos
  schemas configurados, descobertas por catálogo (`TestSupport/TestSchemas`) — não há mock de banco.
  Schemas alvo: propriedade `test.db.schemas` (lista separada por vírgula, default `public,notification` desde a #32; vale
  também via env `TEST_DB_SCHEMAS` ou `-Dtest.db.schemas=`), usada também por `SchemaValidationTest`
  (`table_schema = any(?)`). Nomes validados por `[a-z_][a-z0-9_]*` (identificador não é
  parametrizável em SQL). **O default só lista schemas do app** (`TestSchemas.DEFAULT`): os schemas do Supabase (`auth`, `storage`,
  `extensions`...) vivem no mesmo banco e não podem ser truncados nem validados. Cada serviço extraído acrescenta o seu schema
  ao default (Fase 7: `notification`; a #33 acrescenta `reservation`). O `run-tests.sh` roda o mvn num
  container com env fixo (`--env-file ../.env`): para mudar `test.db.schemas` por ali, passe
  `-e TEST_DB_SCHEMAS=...` no `docker run` do script. Coberto por `TestSchemasTest`.
- `EventTest`, `UserTest`, `EventWalletTest`, `MyWalletTest` exercitam services + repositories.
- `CatalogEventSourcingTest` (issue #28; ver "Catalog em CQRS + Event Sourcing"): **teste de replay do agregado `Event`**
  (cria/edita/adiciona e remove apresentador/troca imagem/cancela vários eventos, reconstrói `event`/`eventpresenter` na
  sombra a partir do `event_store` e compara com a viva nas colunas comparáveis, sem `image_data` e sem carimbos; uma
  divergência provocada é detectada), tipos e versões contíguas de cada stream, payload sem PII/imagem e com valores
  calculados (o `EventUpdated` não carrega capacidade), evento pré-ES (`version = 0`) recusado, backfill da migração
  `20261022` aplicado 2× (um `EventCreated` v1, replay == viva), conflito de versão (transitório: o command repete; permanente: esgota as tentativas e não grava nada),
  8 updates concorrentes no mesmo evento (log contíguo, sem versão perdida) e `DELETE` que falha por FK desfazendo o
  `EventCancelled`.
- Reservation em event sourcing (issue #29; ver "Reservation em event sourcing"): `ReservationEventSourcingTest` (o ciclo
  reserva / confirma / cancela / reserva de novo / expira gera os 4 eventos no mesmo stream com versões contíguas vindas da
  guarda; payload sem dado pessoal; transição que a guarda não aceita não gera evento; conflito de versão no append faz
  rollback de reserva, confirm, cancelamento e expiração), `ReservationReplayTest` (**teste de replay do wallet**: roteiro
  aleatório de 200 jogadas por 6 usuários em 3 eventos; `walletevent` e `ativos` reconstruídos na sombra só do `event_store`
  == vivos nas colunas comparáveis; `ativos` == reservas ativas do `walletevent`), `EventAvailabilityProjectionTest`
  (**ponta a ponta**: reserva → relay → tópico → consumer → `ativos` → `findAvailable`, com relay e consumer ligados e
  `@DirtiesContext`; lag observável; reenvio não conta de novo; e, na #30, o `addLink` do braço `naive`
  (`readPath` trocado por reflexão, restaurado no `finally`) reflete na disponibilidade pela projeção real, com
  `eventcap.quant` intocado, e o `addLink` seguinte é barrado como "Evento lotado"), `EventWalletNaivePathTest` (o braço naive com o consumer
  **desligado**: é o "consumer parado" determinístico que prova o overselling do braço de controle; também confere que o
  naive appenda `SlotReserved` e que a leitura nunca usa `eventcap.quant`), `EventsKafkaConfigTest` (o `RetryListener` loga
  em ERROR a partir da N-ésima tentativa). Os testes ponta a ponta esperam o EFEITO no banco, nunca a atribuição de
  partição do próprio contexto: o tópico tem 1 partição e o mesmo group, e outros contextos Spring em cache também têm o
  listener (todos escrevem no mesmo banco, o checkpoint atômico evita contar duas vezes).
- `IdempotencyRetentionTest` (issue #130): o job apaga chave com `created_at` além do prazo (envelhecida via `UPDATE`) e preserva a recente; repetição com chave ainda retida segue idempotente; apaga em lotes (2500 linhas; o lote de 1000 devolve exatamente 1000); o scheduler tem mais de 1 thread.
- `IdempotencyKeyTest` (issue #24; escopo por usuário na #129): repetição com a mesma `Idempotency-Key` não decrementa `eventcap` de novo
  (sequencial, após `confirm`, e 8 threads concorrentes com a mesma chave), conflito do mesmo usuário com outro evento, mesma chave em usuários diferentes sem colisão,
  falha não deixa chave. O braço `naive` tem o seu em `EventWalletNaivePathTest`; o header/422 em `HttpContractTest`.
- Backbone de eventos (issue #27, `src/test/.../shared/events`, Postgres e Redpanda reais; ver "Event store e backbone
  de eventos"): `EventStoreTest` (append com conflito otimista, outbox na transação do chamador com rollback, leitura de
  stream), `OutboxRelayTest` (publica no tópico na ordem por stream com chave e header `publishedAt`, apaga o outbox,
  falha de envio = rollback, dois relays concorrentes: só um pega o lock), `IdempotentConsumerTest` (checkpoint atômico:
  duplicata, `eventId` divergente, buraco preenchido do `event_store`, buraco que falta = lança, 8 aplicadores
  concorrentes; na #146, o `onBatch`: lote com duplicata, dois streams intercalados, buraco preenchido, `eventId` divergente,
  falha no meio do lote — buraco, JSON inválido e `SQLException` — comitando só o prefixo e acusando o índice via
  `BatchListenerFailedException`, e 8 aplicadores concorrentes do mesmo lote), `EventReplayTest` (**teste de replay**: N eventos, projeção reconstruída do zero na sombra == viva;
  snapshot + cauda == replay completo; as páginas do replay leem o mesmo snapshot, `REPEATABLE READ`), `EventBackboneTest` (ponta a ponta com relay `@Scheduled` + `@KafkaListener` +
  dead-letter topic; política de erro: SQLException transitória é reentregue sem limite e nunca vai ao DLT, buraco
  irrecuperável e JSON inválido vão ao DLT; o listener de teste usa `idIsGroup = false` e um teste confere o `group.id`;
  na #146, um segundo listener `batch = "true"` em outro tópico, com o container pausado para acumular o lote: só a mensagem
  culpada (buraco ou JSON inválido) vai ao DLT e o resto do lote é aplicado, e o erro transitório no meio do lote não vai ao
  DLT; os filtros do DLT usam os streams de cada teste porque o DLT é lido do início e compartilhado).
  Os que precisam de relay/consumer ligam a flag por `@SpringBootTest(properties=...)`; `EventReplay.divergentTables`
  ganhou o overload com `Map<tabela, colunas>` (compara só as colunas informadas: as com `clock_timestamp()` diferem entre viva
  e sombra);
  `EventBackboneTest` usa `@DirtiesContext` para o relay de 50 ms não sobrar no cache de contextos e roubar o outbox
  dos outros testes. Projeção de teste: `TestSomatorio` (tabela `public.test_somatorio`, criada pelos testes e
  derrubada no `@AfterAll` de cada classe: não é schema do app e não sobra no banco compartilhado; nenhum outro teste
  depende dela). O `GlobalDbTruncator` trunca **todas** as tabelas de `public` descobertas por catálogo, `test_somatorio`
  inclusive quando existir; ela só não existe nessa hora. O `SchemaValidationTest` não a menciona. Helpers em `TestSupport/EventsTestSupport` (espera com timeout, leitura de tópico).
- Extração do `notification` (issue #32; ver "Serviço notification"): `IdentityNotificationEventsTest` (monólito: pedido de
  cadastro, reenvio e MFA appendam `VerificationCodeRequested`/`PasswordResetMfaRequested` em `identity.notification` com o
  `event_outbox` na mesma transação e o mesmo código gravado em `pending_verifications`/`users`; falha no append desfaz o cadastro
  pendente; **cadastro, verificação e login funcionam sem o serviço** e o evento sai pelo relay para o tópico) e, no módulo
  `notification`, `NotificationConsumerTest` (Postgres e Redpanda reais + `JavaMailSender` fake via `@MockitoBean`: e-mail de
  verificação e de MFA montados com código/nome/prazo, **mensagem publicada antes do contexto subir é enviada quando ele sobe**,
  **reentrega do mesmo evento não manda dois e-mails**, falha transitória de SMTP reentrega até enviar sem duplicar e sem
  checkpoint prematuro, rejeição permanente do destinatário e JSON inválido vão ao `.DLT` sem travar os próximos, **código já
  vencido não é enviado**, evento sem `expiresAt` vai ao `.DLT`, tipo desconhecido não envia), `RejeicaoPermanenteTest` (unitário:
  usuário inexistente = permanente; quota/limite de taxa do remetente, falta de conexão e autenticação = reentrega) e
  `NotificationEventRetentionTest` (monólito, retenção do dado pessoal: vencido há mais de 30 s e já publicado some; dentro da
  carência, ainda válido, ainda no outbox e outro stream ficam). O group do teste (`notification-test`) é próprio, nunca o do
  serviço vivo.
- `HttpContractTest` (`webEnvironment = RANDOM_PORT` + `TestRestTemplate`) fixa path + método
  + forma de request/response de cada endpoint `/bff/**` que o Flutter consome — bate no
  HTTP real (inclusive `SecurityConfig`), não MockMvc. Desde a #32 não há mock de `JavaMailSender` (o monólito
  não tem SMTP); `RECAPTCHA_SECRET_KEY` é forçada vazia via `@TestPropertySource` para não depender do Google real.
- Não há testes unitários isolados; tudo é de integração com banco (exceto os mocks pontuais
  do `HttpContractTest` para dependências externas).
- `LayerBoundaryArchTest` e `ModuleBoundaryArchTest` (ArchUnit) são a exceção: não usam Spring/banco, só analisam bytecode.
  Roda em qualquer `mvn test`, com ou sem `db` no ar.

## Quality gates (Checkstyle + ArchUnit) — issue #83

- `maven-checkstyle-plugin` roda no `mvn test`/`mvn clean test` (preso na fase `validate`,
  antes de compilar) — por isso `./run-tests.sh` já quebra se uma regra travar. Config em
  `checkstyle.xml`, baseline de violações antigas em `checkstyle-suppressions.xml` (ambos na
  raiz de `Backend/`, valendo para todos os módulos; as `suppress` casam por nome de arquivo, não por caminho).
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
    `UserRepository.java`; o do `EventRepository.java` saiu na #28) e
    `printStackTrace()` (id `ProibirPrintStackTrace` — zero ocorrências, já trava sem
    baseline). Use um logger SLF4J (`org.slf4j.Logger`/`LoggerFactory`, ver
    `reservation/ReservationExpiryService`) em vez de escrever direto no console.
  - Método "medir antes de travar": regra sem violação nasce em `error` sem entrada na
    baseline; regra com violação existente também fica em `error`, mas os arquivos que já
    violavam no dia em que a regra entrou ganham uma linha em `checkstyle-suppressions.xml`
    (não corrigidos por essa issue — é o próximo passo). Corrigiu um arquivo da baseline?
    Remova a linha dele do suppressions.
- ArchUnit (`archunit-junit5`, em `monolito/src/test/java`), dois testes, sem baseline (zero violações):
  - `LayerBoundaryArchTest` trava se o pacote `gateway` (controllers) depender direto de uma classe
    `@Repository` (DAO) ou de JDBC cru
    (`java.sql.Connection/PreparedStatement/Statement/ResultSet/DriverManager`) — acesso a dado
    deve passar por `Service`.
  - `ModuleBoundaryArchTest` (issue #23) trava: (a) `identity`/`catalog`/`reservation`/
    `gateway` só dependem de outro módulo por `<modulo>.api` (nenhum módulo importa `gateway.*`; `notification` saiu do
    monólito na #32 e já não está no classpath dele); (f) `monolito_nao_envia_email` (#32): nada do monólito depende de
    `org.springframework.mail..`/`jakarta.mail..` — o e-mail é do serviço `notification`;
    (b) `shared` não depende de módulo de domínio; (c) todo código de produção mora num módulo (nada volta para
    `POJO/Repository/Service`); (d) `<modulo>.api` não depende de classe interna de módulo nenhum
    (só de outros `*.api`, `shared` e libs); (e) `@RestController`/`@Controller` só no `gateway`
    (`controller_so_no_gateway`, issue #25: os demais módulos não expõem HTTP). Ver "Módulos".
  - Rodar isolado: `mvn -Dtest='LayerBoundaryArchTest,ModuleBoundaryArchTest' -DfailIfNoTests=false test`
    (não precisa do container `db`, mesmo comando funciona no container Maven efêmero do
    `run-tests.sh`).

## Módulos (pacote `com.gerenciador.eventos`) — issues #23 e #25

Pacotes-por-serviço, no mesmo processo/deploy/banco (schema `public`) do monólito: é a preparação da
Fase 5 para a decomposição em microsserviços (CQRS/ES na Fase 6, extração na Fase 7: o `notification` já saiu, na #32 —
é o único módulo abaixo que NÃO está em `monolito/`; os demais seguem lá até a sua vez). Dentro de
cada módulo as classes ficam **planas** (sem `POJO/`/`Repository/`/`Service/`), mais o subpacote
`api`: as **interfaces e DTOs públicos** que os outros módulos podem usar.

```
Main.java        entrypoint @SpringBootApplication (único; o component scan cobre os subpacotes)
identity/        UserService, UserRepository, EmailVerificationService, PendingVerification(+Repository),
                 IdentityNotifications (#32: appenda o evento que pede o e-mail; o monólito não envia mais),
                 security/ (JwtUtil, JwtAuthenticationFilter, AuthorizationUtil, SecurityConfig)
  api/           User, UserApi, EmailVerificationApi, TokenApi, HostAuthorizationApi
catalog/         EventService, EventRepository, EventPresenterRepository (leitura),
                 CatalogEventWriter (command side ES, #28), CatalogEventProjection (projeção inline), EventPayloads
  api/           Event, EventApi
reservation/     EventWalletService, MyWalletService, ReservationExpiryService,
                 IdempotencyKeyRetentionService (job de retenção, #130),
                 EventCapRepository, EventWalletRepository, MyWalletRepository, EventAvailabilityRepository,
                 IdempotencyKeyRepository, CapacityReserver + ReservationWriter (interfaces internas, #24),
                 ReservationEvents (eventos do stream reservation.wallet, #29),
                 EventAvailabilityProjection + EventAvailabilityConsumer (projeção assíncrona de disponibilidade, #29)
  api/           EventCap, EventWallet, MyWallet, EventCapacityApi, EventWalletApi, MyWalletApi,
                 IdempotencyConflictException
shared/          DatabaseConnection (módulo `shared`, lib), CorsConfig, SchedulingConfig (monólito) (infra sem domínio)
  events/        (módulo `shared`) infra de event sourcing sem domínio (#27): EventEnvelope, NovoEvento, EventStore, OutboxRelay,
                 IdempotentConsumer + Projection, EventReplay, EventSnapshots/Snapshot, EventsKafkaConfig,
                 NotificationEvents (#32: contrato do stream identity.notification)
gateway/         BffUserController, BffEventController, BffEventWalletController, BffMyWalletController,
                 BffSeedController (@RestController, paths /bff/**) + SeedData; roteia e agrega, sem domínio
```

- **Regra de fronteira**: um módulo (e o `gateway`) só enxerga outro módulo pelo pacote `<modulo>.api`;
  nunca importa `Repository`/`Service`/config interno de outro. `shared` não depende de módulo de
  domínio (módulos dependem dele). Dado de outro módulo entra por id ou DTO da `api`. Travado por
  `ModuleBoundaryArchTest` (ver "Quality gates"); as FKs do SQL ficam como estão (schema é Fase 7).
- **Grafo de dependências** (sempre via `api`):
  `gateway → identity, catalog, reservation`;
  (`identity → notification` acabou na #32: o `notification` é outro processo e se fala com ele só por evento no backbone);
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
  de `EmailVerificationService` para `notification` (#23) e, na #32, de dentro do monólito (o cadastro em duas fases continua em
  `identity`, que agora só publica o evento); as `*Api` são implementadas pelas classes que já existiam (`UserService implements
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
  `eventpresenter` (numa transação; o `eventpresenter` pela projeção do `EventCreated`, ver "Catalog em CQRS + Event Sourcing"), e `EventService.hydrate(...)` os relê de volta ao sair
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
  `walletevent`, `pending_verifications`, `event_availability`, `idempotency_key`, `event_store`,
  `event_outbox`, `event_snapshot`, `projection_checkpoint`.

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
- **Compatível com transaction pooling porque** (auditoria de `src/main`, 2026-10-16; atualizada na #27): nenhum
  `SET`/`SET LOCAL`/`SET ROLE`/`set_config`, `search_path`, advisory lock **de sessão** (`pg_advisory_lock`...; a
  única exceção é o `pg_try_advisory_xact_lock` do `OutboxRelay`, **permitido porque é de transação**: o Postgres o
  solta sozinho no commit/rollback, na mesma conexão que o Supavisor segura até lá),
  `LISTEN`/`NOTIFY`, `TEMP TABLE`, `PREPARE`/`DECLARE CURSOR` nem `DISCARD`. Os 15 `SET` do código
  são todos de `UPDATE ... SET` / `ON CONFLICT DO UPDATE SET`; a única outra exceção (#27) é o
  `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ` do `EventReplay`, que vale **só para a transação** em que é o
  primeiro statement (não é estado de sessão, some no commit/rollback). Fica proibido
  `conn.setTransactionIsolation(...)`: no PgJDBC ele grava estado de sessão. Não há `connectionInitSql` no Hikari
  nem `setReadOnly`/`setCatalog`/`setSchema`/`setTransactionIsolation`. O único estado de conexão
  mexido é `setAutoCommit(false)` → `commit`/`rollback` → `setAutoCommit(true)` (em
  `EventWalletService`, `ReservationExpiryService`, `CatalogEventWriter`, e, em `shared/events`, `EventReplay`, `IdempotentConsumer`, `OutboxRelay`),
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
- **Outros clientes do pooler** (#32): o serviço `notification` entra pelo mesmo tenant com Hikari 3 e não aumenta o pool real
  (a conta está em "Serviço notification"). Todo serviço novo (a #33 `reservation`...) divide esses mesmos 45/245: documente o
  Hikari dele e refaça a conta aqui antes de subir.
- **Threads do Tomcat** (issue #124): `server.tomcat.threads.max` = 300 (env `SERVER_TOMCAT_THREADS_MAX`;
  o default do Spring Boot é 200). Na configuração de carga: Tomcat 300, Hikari 1000, pool do Supavisor
  245. Os testes de 2026-10-17 pelo pooler deram o mesmo teto de vazão com 200, 300 e 400 threads, mas 300
  teve o menor p95 (matriz CAS N=200: 155 → 114 ms; escada 50k–70k: 180–319 ms contra 192–392 ms com 200).
  **Virtual threads ficam desligadas** (padrão; não há `spring.threads.virtual.enabled`): sobem a vazão
  (~99k efetivos), mas pioram o p95 — a prioridade é a menor latência. Coberto por `TomcatThreadsTest`.
- Os logs do Supavisor trazem `User requested SSL connection but no downstream cert/key found`:
  o PgJDBC tenta SSL (`sslmode=prefer`), o pooler (sem cert) recusa e ele cai para texto puro, na
  rede interna do Docker. É inofensivo.

## Event store e backbone de eventos — issue #27 (ADR "Event-Backbone-Event-Store")

Diagrama command / evento / projeção / replay da Fase 6 (`catalog` e `reservation`): [`docs/arquitetura/fase6-cqrs-es.md`](../../docs/arquitetura/fase6-cqrs-es.md).

Infra genérica de CQRS + Event Sourcing em `shared/events` (sem domínio; a #28 converteu `catalog`, ver a seção
seguinte, e a #29 converte o lado wallet de `reservation`, só appendando eventos com o seu `streamType`). Broker: **Redpanda** (container
`gerenciador-redpanda`, `redpanda:9092` na rede `gerenciador-net`, sem porta no host; imagem com tag fixa no compose;
`docker exec gerenciador-redpanda rpk topic list` para inspecionar). Cliente: `spring-kafka` (versão do Boot).
Tudo em `public` por ora (schema por serviço é a Fase 7: o `notification` da #32 já tem o seu, `notification`; o
`event_store`/`event_outbox` seguem em `public`, do monólito). Streams hoje: `catalog.event`, `reservation.wallet` e
`identity.notification` (#32, ver "Serviço notification": a única exceção à regra de não pôr dado pessoal no payload).
Esta infra vive no módulo Maven `shared` (lib); os testes dela, em `monolito/src/test`.

- **`EventStore.append(Connection, NovoEvento)`**: um único statement (`INSERT ... ON CONFLICT (stream_type, stream_id,
  version) DO NOTHING` + `INSERT` em `event_outbox` num CTE). Devolve `Optional<EventEnvelope>`: **vazio = a versão já
  existe** (conflito otimista; sem `unique_violation`, então a transação do chamador segue viva e ele decide: rollback e a
  resposta da corrida, ou repetir o command). Usa a `Connection` do chamador: evento e outbox só valem com o commit dele.
  `readStream(conn, tipo, id, deVersao)` lê o stream em ordem de `version`. A `version` esperada vem do estado lido na
  mesma transação (na reserva, do `UPDATE walletevent ... RETURNING version`). `partitionKey` (coluna `partition_key`) é
  a chave da mensagem: o id do evento DE DOMÍNIO, passado pelo chamador. **Sem dado pessoal no `payload`.**
- **`OutboxRelay`** (`events.relay.enabled`): `@Scheduled` a cada `events.relay.interval-ms` (50), drena até esvaziar; uma
  transação por lote (`events.relay.batch-size`, 200): `pg_try_advisory_xact_lock` → lê `event_outbox ⋈ event_store ORDER
  BY global_position LIMIT n` (sem `SKIP LOCKED`) → envia tudo e espera os acks → apaga o lote → commit. Falha de envio =
  rollback, o lote volta no tick seguinte (at-least-once). Tópico = `streamType`; chave = `partition_key`; header
  `publishedAt`; corpo = o envelope em JSON. Producer idempotente, `acks=all`, `max.block.ms`/`delivery.timeout.ms` curtos.
  O relay roda no scheduler do Spring (`spring.task.scheduling.pool.size`, 3 desde a #130, ver "Config"): o loop
  `drain()` ocupa uma das threads enquanto houver outbox; o relay está ligado no compose desde a #28: conferir se 3 threads bastam
  para o relay, `ReservationExpiryService` e `IdempotencyKeyRetentionService` junto com a medição da #31.
- **`IdempotentConsumer`** (`events.consumer.enabled`): o listener de cada projeção chama
  `onMessage(Projection, json, ack)` (uma mensagem por transação) ou `onBatch(Projection, List<json>, ack)` (#146, abaixo). Por mensagem, numa transação (checkpoint + projeção) e com ack manual depois do
  commit: confere o `eventId` no `event_store` (inexistente/diferente = descarta), descarta `version <= checkpoint`, relê
  as versões que faltam (`checkpoint+1 .. version`) e avança o checkpoint por CAS (`UPDATE projection_checkpoint ... WHERE
  version = v-1`; `INSERT ... ON CONFLICT DO NOTHING` na v1) aplicando só se o rowcount for 1. `Projection` = `name()`, `tables()`,
  `apply(conn, evento, schema)`. **Política de erro** (`EventsKafkaConfig`; o DLT é só para o que reentregar não
  conserta): (1) erro **transitório** (`SQLException`, conexão caída, pool esgotado — qualquer outro): backoff exponencial
  (`events.consumer.retry.interval-ms`, dobra até 30 s) **sem limite e sem DLT**, sem avançar o offset (mandar ao DLT
  perderia, por exemplo, o último evento de um stream); (2) **buraco irrecuperável** (`EventGapException`: a versão
  anterior não existe nem no `event_store`; não avança nada): `events.consumer.retry.max-attempts` retries a cada
  `interval-ms` e depois `<tópico>.DLT`; (3) **payload inválido** (`InvalidEventMessageException`: JSON quebrado/sem campos
  obrigatórios): DLT direto, sem retry.
  - **Em lote (`onBatch`, issue #146).** As mensagens do lote, na ordem, rodam a MESMA lógica de cima (`tentar`) na MESMA
    transação: um commit e um ack (`ack.acknowledge()` depois do commit) por lote, em vez de um por mensagem. Duplicata e
    várias versões do mesmo stream no lote funcionam porque a leitura do checkpoint, na mesma conexão, enxerga o avanço ainda
    não comitado; perder a corrida do CAS para outro aplicador desfaz e refaz o lote inteiro. **Falha na mensagem `i`
    (decisão): comita o prefixo `0..i-1` e acusa só a `i`.** A transação inteira é desfeita, as `0..i-1` são reaplicadas e
    comitadas numa transação nova e então se lança `BatchListenerFailedException(i)` (causa = a exceção original); o `DefaultErrorHandler` comita o offset de
    `0..i-1`, reentrega de `i` em diante e aplica a política de erro acima à CAUSA, então só a mensagem `i` vai ao DLT
    (buraco após `max-attempts`, JSON inválido direto) e o transitório (`SQLException`) tenta de novo sem limite, nunca DLT.
    O prefixo precisa estar comitado no banco porque o offset dele avança: o último evento de um stream, perdido ali,
    nunca seria preenchido como "buraco". Nunca escapa exceção crua do lote: uma exceção crua cairia no handler de lote
    genérico, que manda o LOTE INTEIRO ao DLT quando o retry esgota e não chama o `RetryListener` por mensagem (o
    `RetryListener` de lote é um método vazio por padrão). O ack não é dado na falha; mensagens já comitadas e reentregues
    são descartadas pelo checkpoint. Um `eventId` que não bate com o `event_store` não é falha: é descartado como antes.
    **Limite conhecido:** lotes concorrentes em ordens de stream diferentes podem dar deadlock de linha de checkpoint
    (`40P01`, tratado como `SQLException` transitória); em produção há 1 consumer e 1 partição, e na sobreposição de consumers de um
    rebalance o deadlock se recupera pelo retry. Quando o lote falha, o prefixo comitado não aparece no `events.lag` (o log só sai
    com o lote bem-sucedido).
- **`EventReplay`**: `rebuild(projecao, streamType, schema)` cria o schema-sombra (`CREATE TABLE ... (LIKE public.t
  INCLUDING ALL)`), reaplica todos os eventos do tipo por `global_position` e `divergentTables` compara com a viva
  (`EXCEPT ALL` nos dois sentidos); `drop(schema)` derruba. Roda em **`REPEATABLE READ`** (`SET TRANSACTION` como primeiro
  statement): `global_position` é ordem de alocação e paginar por ela em READ COMMITTED (snapshot novo a cada página) pularia o
  evento que comita no meio; assim todas as páginas leem o mesmo snapshot (a sombra = o log no início da leitura).
  `divergentTables(projecao, schema)` compara `SELECT *`; `divergentTables(projecao, schema, colunasComparaveis)` (#28)
  recebe um mapa tabela -> colunas e compara só elas (tabela fora do mapa, ou com lista vazia = `SELECT *`), para o que o
  log não determina (carimbo `now()`, `applied_at = clock_timestamp()`, `created_at`/`updated_at`, blob fora do ES);
  `LIKE ... INCLUDING ALL` **não**
  copia FK nem trigger, e coluna `serial` da cópia aponta para a sequence da tabela viva. Sem `TEMP TABLE`; o schema-sombra fica fora do
  `SchemaValidationTest` e do `GlobalDbTruncator` (que só olham `test.db.schemas`, default `public`).
- **`EventSnapshots`**: `due(version)` (a cada `events.snapshot.every`, 100), `save`/`load` (um snapshot por stream, o mais
  novo vence). **Cache descartável**: mudou o formato do estado → apaga `event_snapshot` e reconstrói pelo log.
- **`global_position` não é cursor**: é a ordem de alocação, não a de commit. Ninguém lê o `event_store` "a partir da
  posição X" para acompanhar novidades (o acompanhamento é pelo broker; o replay é completo).
- **Latência/medição (para a #31)**: cada evento custa +1 `INSERT` em `event_store` e +1 em `event_outbox` na transação
  do request (um statement só) e +1 `DELETE` assíncrono no relay; o `INSERT`+`DELETE` contínuo gera tuplas mortas (por
  isso o autovacuum agressivo em `event_outbox`). `occurred_at` (append) → `publishedAt` (relay) → `applied_at`
  (consumer, #29) delimitam o lag. O custo na reserva, desde a #29, está em "Latência (ponto 2 da série)". **Nota para a #31:** o Redpanda sobe com `--mode=dev-container`, que desliga o fsync
  (`--unsafe-bypass-fsync`): a latência do relay medida aqui é **otimista** em relação a um broker com disco durável.
- **Tópicos** nascem na primeira publicação (o modo dev-container do Redpanda liga `auto_create_topics_enabled`), com 1
  partição. O listener de teste do `EventBackboneTest` mostra o padrão do `@KafkaListener` para a #28/#29:
  **`idIsGroup = false`** (por padrão o `id` do listener vira o `group.id` e o `spring.kafka.consumer.group-id` — logo o
  `EVENTS_CONSUMER_GROUP` do `run-tests.sh` — não valeria). Um único `group.id` por ambiente; `auto.offset.reset=earliest`
  fixo (um group novo não pula nada; o checkpoint por stream e a conferência do `eventId` descartam o que já foi aplicado).

## Serviço notification — issue #32 (Fase 7: primeira extração)

O envio de e-mail saiu do monólito e virou o módulo/serviço `notification` (`Backend/notification/`, README próprio): processo,
imagem (`notification/Dockerfile`), container (`gerenciador-notification`) e schema (`notification`) próprios, sem porta HTTP.
O `gateway` e o contrato `/bff/**` não mudaram.

- **Fluxo**: `identity` (monólito) NÃO fala com SMTP. `EmailVerificationService.requestEmailVerification`/`resendVerificationCode`
  e `UserService.requestPasswordResetMfa` gravam o código (`pending_verifications` / `users.password_reset_mfa_code`) e, **na MESMA
  transação JDBC**, appendam um evento em `event_store` + `event_outbox` (`IdentityNotifications`, que usa o `EventStore` da
  `shared`). O relay (já ligado no compose) publica no tópico `identity.notification`; o `notification` consome e envia.
  Cadastro e login **não dependem** do serviço: parado, o evento espera no outbox/tópico e o e-mail sai quando ele volta
  (coberto por `IdentityNotificationEventsTest` e `NotificationConsumerTest`).
- **Contrato** (`shared.events.NotificationEvents`): `streamType` = `identity.notification` (= nome do tópico, 1 partição);
  cada notificação é **um stream novo de uma versão só** (`streamId` = UUID, `version` 1, chave da mensagem = o `streamId`).
  `eventType`: `VerificationCodeRequested` e `PasswordResetMfaRequested`, ambos com payload `email`, `userName`, `code`,
  `expirationMinutes` e `expiresAt` (ISO-8601, quando o código vence), `schemaVersion` 1, no envelope JSON da ADR do backbone. A
  validade vem das constantes que já existem (`verification.code.expiration.minutes` no cadastro; `NotificationEvents.MFA_VALIDADE`
  no MFA, usada também no SQL de `users.password_reset_mfa_expiration`): nada de número duplicado. Tipo novo: constante em
  `NotificationEvents` + `if` no consumer; mudança incompatível de payload incrementa `schemaVersion`.
- **Exceção deliberada à regra "sem dado pessoal no payload" — decisão do usuário (2026-10-21)** (ADR do backbone, seção 3): o
  e-mail só pode ser montado com destinatário e código, então estes eventos levam `email`, `userName` e o código de uso único.
  A senha e o hash NUNCA entram. **Retenção do dado pessoal = validade do código + 30 s** (`NotificationEvents.CARENCIA`), nas
  três cópias: (1) **`event_store`**: `identity/NotificationEventRetentionService` (`@Scheduled` a cada
  `notification.events.retention.scan.ms`, default 30 s, env `NOTIFICATION_EVENTS_RETENTION_SCAN_MS`; `run-tests.sh` estica para
  1 h) apaga, em lotes de 1000, as linhas do stream cujo `payload.expiresAt` venceu há mais de 30 s e que **já saíram do
  `event_outbox`** (cadastro: 5 min 30 s; MFA: 15 min 30 s; evento sem `expiresAt` conta como vencido em `occurred_at`); o
  stream tem 1 versão por stream e nunca passa por replay/projeção, e a "imutabilidade" do `event_store` é convenção (a tabela
  não tem trigger nem REVOKE), então nada quebra; (2) **tópico `identity.notification` e `.DLT`**: `retention.ms` = maior
  validade + 30 s = 15 min 30 s (`NotificationEvents.RETENCAO_TOPICO`, declarado por `NotificationEvents.topic()` /
  `deadLetterTopic()` nos DOIS lados, com `spring.kafka.admin.modify-topic-configs=true` para valer também no tópico que já
  existe) e `segment.ms` = 5 min (o broker só apaga segmento FECHADO; sem isso o `retention.ms` não teria efeito, então o dado
  some em até ~20 min, não em 15 min 30 s exatos). A validade do cadastro tem de ser `<=` a do MFA; (3) **consumer**: descarta, com
  log e sem enviar, evento cujo código já venceu (e-mail com código vencido é inútil). O `.DLT` guarda a mensagem original (e
  headers de exceção que podem trazer o endereço), por isso a mesma retenção curta.
- **Consumer** (`NotificationConsumer`): `@KafkaListener` com `idIsGroup = false`, group próprio `notification`
  (`NOTIFICATION_CONSUMER_GROUP`), ack manual. Por mensagem, numa transação de banco: `INSERT INTO notification.processed_event
  (event_id, ...) ON CONFLICT DO NOTHING` (rowcount 0 = reentrega, descarta) → envia o e-mail → commit → ack. Envio que falha
  desfaz a transação (sem checkpoint) e a mensagem volta. **Não usa o `IdempotentConsumer` da `shared`**: ele confere o
  `event_id` contra `public.event_store` e preenche buraco relendo o log, ou seja, depende de tabelas do monólito; o serviço
  extraído não lê o banco de ninguém. Reusa da `shared` o envelope, o `DatabaseConnection` e a política de erro/DLT
  (`EventsKafkaConfig`, importada por `@Import`).
- **Política de erro**: SMTP fora/banco fora = transitório, retry exponencial sem limite e sem DLT (loga ERROR a partir da 10ª
  tentativa). Payload inválido e **destinatário recusado de vez pelo SMTP** (`SendFailedException` com `invalidAddresses`, ou
  `MailParseException`) = `identity.notification.DLT` direto, sem retry — senão um e-mail digitado errado travaria todos os
  seguintes (tópico de 1 partição). **Exceção:** recusa por culpa do REMETENTE (quota diária do Gmail `550 5.4.5`, limite de taxa:
  o texto casa `NotificationConsumer.CAUSA_DO_REMETENTE`: "sending quota/limit", rate limit, `5.4.5`) também chega em
  `invalidAddresses`, mas passa quando a quota volta, então continua reentregue; já "over quota" (`552 5.2.2`) é a caixa do
  DESTINATÁRIO cheia e continua permanente. Tipo de evento desconhecido: aviso no log e ack, sem enviar. Código vencido: aviso no log e ack, sem enviar.
  Evento sem `expiresAt`/`expirationMinutes` válidos: `.DLT`. **Poll**: `max-poll-records=10` e `ack-mode=manual_immediate`
  (cada e-mail leva 1 a 3 s, até ~15 s com timeout; com os 500 do default um backlog grande estouraria o `max.poll.interval.ms` de
  5 min e giraria em rebalance).
- **Pool Hikari do serviço — a conta**: `maximum-pool-size` **3** / `minimum-idle` 1 (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`
  no compose; o default do `application.properties` do módulo também é 3). Tópico de 1 partição + 1 thread no listener ⇒ no máximo
  **1** transação aberta por vez (ela segura a conexão durante o envio SMTP, timeouts de 5 s); 3 dá folga para reconexão. O
  serviço usa o **mesmo tenant e o mesmo usuário do Supavisor** (`${POSTGRES_USER}.gerenciador-eventos`), então consome **de
  dentro** do pool real de conexões ao Postgres (`POOLER_DEFAULT_POOL_SIZE` = 45 no compose padrão, 245 na configuração de
  carga) que o monólito já usa: o teto **não cresce** (nem consome a folga do `max_connections`, que é para superuser, Supavisor,
  suíte e `psql`). No transaction mode as 3 conexões de cliente só pegam uma conexão real durante a transação (1 em regime, segurada
  durante o SMTP; 3 no pior caso) e a devolvem no commit; ou seja, no máximo 1 dos 245 fica ocupado por ele de cada vez, o que
  tira uma conexão do monólito só enquanto o e-mail sai. O `POOLER_MAX_CLIENT_CONN` (1500) cobre 1000 (monólito) + 3.
  **Não aumente** esse Hikari sem refazer a conta: ele divide o pool com o monólito, que é o que a carga mede.
- **Config do serviço** (todas por env var, mesmos nomes do monólito; valores reais em `Projeto/.env`, nunca imprimir):
  `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD`, `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`/`_MINIMUM_IDLE`,
  `MAIL_USERNAME`/`MAIL_PASSWORD` (SMTP Gmail, `spring.mail.*` igual ao do monólito antigo), `SPRING_KAFKA_BOOTSTRAP_SERVERS`,
  `NOTIFICATION_CONSUMER_GROUP` (default `notification`), `EVENTS_CONSUMER_RETRY_*`. O compose passa só esses nomes (interpolados do
  `.env`; **sem `env_file`**, para o serviço não receber `JWT_SECRET`/reCAPTCHA). O monólito não lê mais `MAIL_*`.
- **Pendências conhecidas**: (a) `notification.processed_event` cresce sem limpeza (com a retenção de 15 min 30 s do tópico, linhas
  mais velhas que isso nunca mais serão reentregues e poderiam ser apagadas; `processed_event` não tem dado pessoal, só id e tipo);
  (b) sem healthcheck no compose (o serviço não tem HTTP; `docker logs gerenciador-notification` e o lag do group `rpk group
  describe notification` mostram a saúde); (c) o `backend` do compose recebe `MAIL_USERNAME`/`MAIL_PASSWORD` vazios (sobrepostos,
  porque o `env_file` entrega o `.env` inteiro).

## Catalog em CQRS + Event Sourcing — issue #28

Agregado `Event`: stream **`catalog.event`**, `streamId` = `partitionKey` = `event_id` (tópico `catalog.event`, relay
ligado no compose desde a #28; o `catalog` não tem projeção assíncrona, o consumer é o da #29 para `reservation.wallet`). `/bff/events/**` não mudou (`HttpContractTest`
sem mudança de expectativa).

- **Eventos** (`schemaVersion` 1; payloads em `catalog/EventPayloads`, com os valores **já calculados**, ex.:
  `buyTimeLimit` defaultado para a data do evento; datas em ISO-8601): `EventCreated` (`eventId`, `creatorId`, `eventName`,
  `isEad`, `address`, `eventDate`, `description`, `capacity`, `buyTimeLimit`, `presenters[]`), `EventUpdated` (só os
  metadados: `eventId`, `creatorId`, `eventName`, `isEad`, `address`, `eventDate`, `description`; **sem** `capacity`/
  `buyTimeLimit`), `PresenterAdded`/`PresenterRemoved` (`eventId`, `presenterName`), `EventCancelled` (`eventId`).
  **Sem PII**: só ids (`creatorId`) e dados do evento; nome de apresentador é dado público do evento, e-mail/telefone/senha
  nunca entram. **Capacidade é estado de `reservation`, não do stream `catalog`**: `eventcap` é o agregado relacional/CAS
  e não é projetado aqui. Só o `EventCreated` leva `capacity`/`buyTimeLimit` (valores iniciais), porque o `eventcap` nasce
  na MESMA transação do append. Mudanças de capacidade (`updatePolicy`, outra transação, com `CHECK` que pode recusá-la
  depois do append) **não** entram no `EventUpdated`: o log imutável e o tópico nunca registram uma capacidade que o
  `eventcap` não aceitou (nem divergem em PUTs concorrentes). O histórico de capacidade, se for preciso, é do módulo `reservation`.
- **`image_data` fica FORA do ES.** É blob (até 5 MB, base64 no JSON inflaria o `event_store` imutável), servido por
  `/bff/events/{id}/image`. Não entra em payload nenhum; o command grava a coluna direto na linha viva
  (`EventRepository.gravarImagem`, mesma transação), a projeção/replay nunca a tocam, e a comparação do replay a ignora. Trocar
  só a imagem (`POST .../image` faz `findById` + `updateEvent`) gera um `EventUpdated` sem mudança de metadado.
  `created_at`/`updated_at` também ficam fora da comparação: são carimbos do banco (`CURRENT_TIMESTAMP`), não vêm do log.
- **Command side** (`CatalogEventWriter.executar`): uma transação JDBC manual por command, na ordem: (1) trava a linha de
  `event` (`SELECT version ... FOR NO KEY UPDATE`) e lê a `version` = versão do último evento aplicado. Esse lock não
  conflita com o `KEY SHARE` que as FKs de `eventcap`/`walletevent` tomam em `event` (reservas comuns seguem), mas
  operações que sobem a `FOR UPDATE` (renomear `event_name`, que é `UNIQUE`, e o `DELETE`) conflitam com o `KEY SHARE` e
  esperam as reservas em curso, como já era antes da #28; (2) `EventStore.append` na versão + 1;
  (3) aplica a `CatalogEventProjection` na MESMA `Connection` (atualiza `event`/`eventpresenter` e `event.version`); (4) commit.
  `append` vazio = conflito: rollback e o command **repete inteiro** (relê a versão), até 5 tentativas
  (`IllegalStateException`/HTTP 500, nada gravado). Com a linha travada o conflito só acontece se alguém appenda no stream
  por fora da projeção; commands concorrentes no mesmo evento se serializam pelo lock. Escolhido o `SELECT ... FOR NO KEY
  UPDATE` em vez de `UPDATE ... SET version = version + 1 RETURNING` porque a projeção já grava `version` com o valor do
  envelope (a mesma função serve ao replay) e um command pode appendar vários eventos.
  - `createEvent`: o `event_id` sai da sequence da identity (`nextval`) antes do append, vira o `streamId`, e a projeção
    insere a linha com `OVERRIDING SYSTEM VALUE`. Numa transação: `EventCreated` v1 (+ apresentadores no payload) +
    `event`/`eventpresenter` + `image_data` (se vier) + `eventcap`.
  - `updateEvent` **continua em 3 transações** (issue #60, não consertada aqui): (1) `EventUpdated` + `event` (+ `image_data`);
    (2) `updatePolicy` de `reservation.api` (conexão própria; **não** gera evento de `catalog`: a capacidade é do
    `eventcap`, e uma recusa do `CHECK`, HTTP 400, não deixa rastro no log; a não atomicidade com (1) é a de antes, #60); (3) `trocarApresentadores`: 1 `PresenterRemoved`/`PresenterAdded` por diferença
    (nomes aparados, sem vazios/repetidos; sem diferença não appenda). O append está sempre na transação da escrita que ele descreve.
  - `delete`: `EventCancelled` appendado e, **na mesma transação**, a projeção faz o `DELETE` de `event` que sempre existiu
    (só `eventcap` cai por `CASCADE`; `event_availability` não tem FK desde a #29 e a linha fica órfã, inofensiva porque a leitura parte de `eventcap`). O stream fica no `event_store` como auditoria. `DELETE` que falha
    (ex.: `walletevent` com `RESTRICT`) desfaz o `EventCancelled`. Evento inexistente = no-op, sem evento (204 como antes).
- **Read model** = `event`/`eventpresenter` (+ `event.version`), projeção **inline**: `HttpContractTest` lê o que acabou de
  escrever. A mesma `CatalogEventProjection` roda no command (schema `public`) e no replay (schema-sombra).
- **Replay**: `EventReplay.rebuild(CatalogEventProjection, "catalog.event", sombra)` +
  `divergentTables(..., colunasComparaveis)` com `event`: `event_id, creator_id, event_name, ead, address, event_date,
  description, version` (e `eventpresenter` inteira). Ver `CatalogEventSourcingTest`.
- **Snapshot não é usado em `catalog`**: nenhum command reconstrói estado lendo o stream (a projeção inline já guarda o
  estado e a versão), então não há load do agregado onde um snapshot ajudaria. `EventSnapshots` continua disponível.
- **Eventos que já existiam** (antes da #28) têm `version = 0` e nenhum stream. A migração
  `20261022-catalog-event-version.sql` faz o backfill: um `EventCreated` v1 (estado atual, sem imagem) + a linha do outbox e
  `version = 1` (só nas linhas cujo `EventCreated` foi de fato inserido), então o replay também os reconstrói. **O command
  RECUSA evento com `version = 0`** (`IllegalStateException` "é pré-ES", em `Tx.bloquear`; HTTP 500 no update/delete):
  appendar aí deixaria um stream órfão começando na v1 sem `EventCreated`. Reaplique a migração depois de carregar o
  `db/seed-data.sql`, que insere em `event` direto (aviso no topo do seed).
- **Limitações conhecidas (para o artigo, sem código):** (a) o replay recria `event` com o `UNIQUE (event_name)` copiado
  (`LIKE ... INCLUDING ALL`); como o replay aplica por `global_position` (ordem de alocação, não de commit), um rename
  concorrente com um create do mesmo nome pode inverter a ordem na sombra e violar o `UNIQUE`, mesmo a viva estando
  consistente. Caso raro, não tratado. (b) Com o relay ligado ele faz ~80 statements/s pelo pooler mesmo com o outbox
  vazio (o tick de 50 ms); é uma variável a acompanhar na #31, não foi medida aqui.
- **Relay**: com `EVENTS_RELAY_ENABLED=true` no compose, o `catalog` publica no tópico `catalog.event`. O relay ocupa uma das
  3 threads do scheduler só enquanto houver outbox (o volume do catálogo é baixo); o efeito sobre a medição fica para a #31.

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
   `expires_at = now + eventcap.max_time_in_line` (UPSERT, revive linhas `expired`/`cancelled`; devolve a `version`);
   (d) appenda `SlotReserved` (#29); (e) commit.
2. `confirm` (`POST .../event-wallets/confirm`): numa transação, `pending → confirmed` (`expires_at` = NULL) e append de
   `ReservationConfirmed`.
3. `removeLink` (`DELETE .../event-wallets`): numa transação, um `UPDATE` condicional único
   (`cancelIfActive`: `pending`/`confirmed` → `cancelled`) e `release` + append de `ReservationCancelled` **só se** esse
   UPDATE afetou 1 linha — evita devolução dupla de vaga em cancelamento concorrente / corrida com o
   job. Reserva já `cancelled`/`expired`/inexistente ⇒ no-op silencioso (idempotente, sem evento).
4. `reservation/ReservationExpiryService` — `@Scheduled` (a cada `reservation.expiry.scan.ms`,
   default 30s; `@EnableScheduling` em `shared/SchedulingConfig`): varre `pending` vencidas e,
   por transação, `expireIfStale` (condicional, não pisa em confirmação concorrente) + `release` + append de
   `ReservationExpired` (um por linha expirada, na transação dela; erro numa linha não para as outras).

### Reservation em event sourcing (híbrido) — issue #29 (ADR "Event-Backbone-Event-Store")

`eventcap` + CAS ficam **relacionais e síncronos**, sem mudança. O lado wallet/order passa a ser event-sourced: o
stream `reservation.wallet` (`streamId = "userId:eventId"`, **chave de partição = `event_id`**, tópico = `streamType`)
tem os eventos `SlotReserved` (`{userId, eventId, expiresAt}`, metadata `{idempotencyKey}`), `ReservationConfirmed`,
`ReservationExpired` e `ReservationCancelled` (`{userId, eventId}`): ids e valores já calculados, **nunca dado pessoal**
(o replay não chama `now()`). Código: `reservation/ReservationEvents`.

- **A versão sai da própria guarda.** `reservePending`, `confirm`, `cancelIfActive` e `expireIfStale` fazem
  `UPDATE/UPSERT walletevent ... SET version = version + 1 ... RETURNING version` (coluna `walletevent.version`, 0 =
  linha anterior ao ES; a 1ª reserva do par é a v1). Sem `SELECT` separado: uma reserva concorrente com a expiração do
  mesmo par não lê versão velha (a linha fica travada até o commit, e o `UPDATE` reavalia a guarda depois do lock).
  Reservar de novo uma cancelada/expirada **continua o mesmo stream** (`version` segue).
- **Append só com rowcount 1, na mesma transação.** Sem linha afetada (`RETURNING` vazio) não há evento. O append é o
  último comando antes do commit (`EventStore.append`, 1 statement com o `INSERT` do outbox). Conflito de versão no append
  (não deveria acontecer: a versão vem da guarda) = rollback: na reserva a resposta é "Vínculo já existente", como na
  corrida atual; em confirm/cancelamento/expiração é `IllegalStateException` (a guarda, a vaga e o evento voltam juntos).
  Os `catch` dessas transações pegam `SQLException | RuntimeException` (senão o `finally setAutoCommit(true)` comitaria a
  guarda sem o evento).
- **O braço `naive` também appenda `SlotReserved`** (só não toca em `eventcap`): a projeção vê as reservas dos dois braços.
- **`walletevent` = projeção inline** ("minhas reservas"): continua sendo gravado pelas guardas, na transação; o
  read-your-writes do `HttpContractTest` não mudou (a `version` não aparece no JSON de `EventWallet`).
- **Projeção assíncrona de disponibilidade** (`event_availability`, lida só pelo braço `naive`): o
  `EventAvailabilityConsumer` (`@KafkaListener(id = "reservation-availability", idIsGroup = false, topics =
  "reservation.wallet", batch = "true", properties = "max.poll.records=${events.consumer.batch-size:500}")`,
  `events.consumer.enabled`) usa o `IdempotentConsumer` da #27 e a `EventAvailabilityProjection`.
  **Em lote desde a #146:** recebe `List<ConsumerRecord>` (até `events.consumer.batch-size`, env `EVENTS_CONSUMER_BATCH_SIZE`,
  default **500**; vira o `max.poll.records` só deste listener) e chama `onBatch`: uma transação e um ack por lote, em vez de uma
  transação curta por mensagem. Só este listener é em lote (`spring.kafka.listener.type` continua `single`; o `onMessage` fica
  para os testes da #27). **Efeito esperado (a medir na Rodada 2, #31):** o achado da #31 foi X ≈ 56k RPM com o consumer ligado
  contra 60k estável com ele desligado, e backlog de ~793 mil mensagens; o lote troca N transações/commits/acks por 1, então
  o custo por mensagem cai (menos transações curtas disputando o mesmo Postgres e o mesmo pooler do caminho quente), o consumer
  passa a acompanhar o append (o lag deve cair e o backlog não crescer) e o X deve voltar para perto dos 60k. Com o consumer em dia o lote
  tem poucos registros e o comportamento é o de antes. Continuam 1 partição e 1 consumer (mais partições/consumers e mudar
  o relay estão fora do escopo da #146). O que não muda: o lag por evento (`events.lag`) é medido depois do commit do lote.
  A tabela guarda **só `ativos`** por evento (`SlotReserved` +1; `ReservationExpired`/`ReservationCancelled` −1;
  `ReservationConfirmed` 0) + `applied_at` (`clock_timestamp()` do statement; com o lote da #146 é a hora dentro de uma transação de até N mensagens, não exatamente
  a do commit, então `applied_at − last_occurred_at` pode subestimar o lag visível em até a duração do lote; o `events.lag` é medido depois do commit) + `last_occurred_at` (maior
  `occurredAt` visto, igual ao `occurred_at` do `event_store`). **Sem FK e sem CHECK** em `ativos`: toda violação no consumer
  é `SQLException`, retentada sem limite, e travaria a partição; um `Cancelled` sem `SlotReserved` (linha anterior ao ES)
  deixa o saldo negativo, e a leitura trata `ativos` negativo como 0 (senão a disponibilidade passaria da capacidade). A **disponibilidade é calculada na leitura**
  (`EventAvailabilityRepository.findAvailable`: `eventcap LEFT JOIN event_availability`, `CASE WHEN capacity = -1 THEN -1
  ELSE GREATEST(capacity - GREATEST(COALESCE(ativos, 0), 0), 0) END`; **nunca** lê `eventcap.quant`). Um evento sem nenhuma reserva já
  tem disponibilidade (= capacity) e a mudança de capacidade por `updatePolicy` vale na hora: só `ativos` é defasado.
  `null` só significa "sem `eventcap`" (já barrado antes por `validarPreRequisitos`); o antigo erro "disponibilidade ainda
  não calculada" não existe mais.
- **Lag observável (ADR, seção 4).** Na linha da projeção: `applied_at − last_occurred_at` (idade do último evento
  aplicado; o `Get-AmostraLag` do harness de carga mede isso). Por evento, o consumer loga no logger `events.lag`:
  `relay_ms` (`publishedAt − occurredAt`), `consumer_ms` (aplicação − `publishedAt`) e `total_ms`
  (**DEBUG, desligado por padrão**: um log por evento no thread do único consumer infla o `consumer_ms` medido; ligue com `LOGGING_LEVEL_EVENTS_LAG=DEBUG` só na rodada que mede o lag, e veja o offset lag). Complemento: offset lag
  do group (`docker exec gerenciador-redpanda rpk group describe gerenciador-eventos`). A amostra de `applied_at` só anda
  quando o consumer aplica evento: consumer parado não a atualiza, olhe também o offset lag. Falha transitória do consumer:
  retry sem limite, e a partir da 10ª tentativa (`events.consumer.retry.error-after-attempts`) cada tentativa loga em
  ERROR (`RetryListener` em `EventsKafkaConfig`).
- **`ReadModelRefreshService` e `READMODEL_AVAILABILITY_REFRESH_MS` saíram.** O **lag do braço naive deixou de ser
  variável controlada** (`lag_ms` da matriz): é o lag real do relay + consumer, medido sob a carga (variável independente =
  a carga: N concorrentes / RPM). Ver `Projeto/carga/README.md`.
- **Overselling (#9).** Os testes concorrentes do CAS seguem verdes (`eventcap` nunca negativo, reservas ≤ capacidade). O
  braço naive com o consumer atrasado continua oversellando (é o braço de controle):
  `EventWalletNaivePathTest.doisAddLinkNaMesmaJanelaOversellamSemTocarOCas` prova, de forma determinística, com o consumer
  desligado.
- **Replay** (`ReservationReplayTest`): reconstrói na sombra o `walletevent` (colunas `user_id, event_id, status,
  expires_at, version`) e a projeção (`event_id, ativos, last_occurred_at`; `applied_at` fica de fora) só do `event_store`.
  Só vale para streams nascidos depois da migração `20261023` (linhas anteriores têm `version = 0` e nenhum evento; o
  baseline de `ativos` vem das reservas ativas que já existiam).

O `gateway/BffSeedController` (`POST /bff/seed`) cria inscrições já confirmadas (`addLink`
seguido de `confirm`).

### Idempotency-Key, invariante e compensação — issue #24

- **Invariante**: (1) reservas ativas (`pending`/`confirmed`) ≤ capacidade — **só no braço `cas`** (o CAS em
  `eventcap` e o `CHECK eventcap_quant_bounds` garantem; o `naive` oversella de propósito); (2) **uma `Idempotency-Key`, por usuário, produz no máximo uma reserva**
  (PK `(user_id, idempotency_key)`). Nenhuma outra requisição observa um estado em que a vaga foi debitada
  sem a reserva gravada (ou o contrário): tudo é uma transação.
- **Header** `Idempotency-Key` em `POST /bff/event-wallets`, **opcional e aditivo**: sem ele, o servidor
  gera um UUID; a chave usada (gerada ou recebida) volta no header `Idempotency-Key` da resposta 201
  (exposto no CORS). O corpo JSON e o contrato do Flutter não mudam (`EventWallet.idempotencyKey` é
  `@JsonIgnore`, só em memória). Máx. 255 caracteres (senão 400). **A chave tem escopo por usuário**
  (issue #129): a PK é `(user_id, idempotency_key)`, então dois usuários podem usar a mesma chave sem
  colidir — cada um tem a própria reserva (claim e lookup usam o par).
- **Repetição** (mesma chave, mesmo usuário e evento) devolve **201 com o resultado da primeira**
  (`status = pending`, `expiresAt` salvo — mesmo depois de confirmar/cancelar a reserva) e **não** toca
  `eventcap`. **Mesma chave, mesmo usuário e outro evento ⇒ 422** (`IdempotencyConflictException`; segue o
  rascunho IETF do header, que reserva 422 a "mesma chave, payload diferente"). Como o `INSERT` da chave
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
  `rollback` deixa de cobrir os dois lados e a compensação vira um `release` (hoje
  `EventCapRepository.release`, o que `removeLink`/expiração usam, ainda fora da interface
  `CapacityReserver`) que **terá de ser idempotente por chave no split**.
- **Braço `naive`**: a idempotência vale nos dois caminhos (a reivindicação da chave está na mesma
  transação curta; só o `tryReserve` é pulado). Na repetição sequencial a checagem do read model
  ("Evento lotado") falha como o "Vínculo já existente" e cai no mesmo desvio para o resultado salvo.
- **Retenção** (issue #130): `reservation/IdempotencyKeyRetentionService` — `@Scheduled` (a cada
  `idempotency.retention.scan.ms`, default 10 min, env `IDEMPOTENCY_RETENTION_SCAN_MS`) apaga as linhas de
  `idempotency_key` com `created_at` mais antigo que `idempotency.retention.hours` (default **24h**, a janela
  em que um retry do cliente faz sentido; env `IDEMPOTENCY_RETENTION_HOURS`). Passado o prazo, repetir a
  mesma chave deixa de ser reconhecida: vira reserva nova (ou "Vínculo já existente" se a anterior ainda
  estiver ativa). O `DELETE` roda em **lotes de 1000** (CTE com `LIMIT ... FOR UPDATE` + junção por `ctid`),
  um por instrução em autocommit, repetidos até um lote voltar incompleto — não varre nem trava a tabela de
  uma vez. Filtra por `created_at` com o índice `idx_idempotency_key_created_at`. `purgeExpired()` é `public`
  e chamável direto (os testes o invocam; `run-tests.sh` estica o timer para 1h via env). A tabela não tem FK, então nada mais a apaga (não some com o evento).
- **Latência (ponto 2 da série, não medido)**: sob carga a rejeição ("Evento lotado") é o caminho quente,
  não só o sucesso. **Custo novo por reserva bem-sucedida desde a #29, nos DOIS braços:** +1 `INSERT` em `event_store`
  e +1 `INSERT` em `event_outbox` (num único statement, o último antes do commit, +1 ida ao banco) na transação do request,
  mais a escrita da linha e dos índices do `event_store` (PK + `UNIQUE (stream_type, stream_id, version)`) e do outbox, e +1
  `DELETE` assíncrono do outbox no relay (tuplas mortas, autovacuum agressivo). Além disso, o `confirm` deixou de ser 1
  `UPDATE` em autocommit e virou `UPDATE ... RETURNING` + append + `COMMIT` (a mesma ida de rede a mais por transição:
  cancelamento e expiração também appendam); a pré-checagem do naive virou `eventcap LEFT JOIN event_availability` (join por
  PK) no lugar da leitura de uma tabela. A rejeição ("lotado") **não** paga o append (rollback antes dele no `cas`; pré-checagem
  no `naive`). Custo por braço: no **sucesso**, `cas` e `naive` pagam 1 `INSERT` de chave (+1 ida ao banco, mais
  a escrita da linha e dos 2 índices: PK + `created_at`, #130). Na **rejeição**, o `cas` paga `INSERT` + rollback (tupla morta na
  `idempotency_key` **e entrada morta nos dois índices**, vacuum), pois o `tryReserve` roda depois do `claim`; o `naive` rejeita nas
  pré-checagens, antes da transação, e **não paga nada** (a consulta prévia da chave só roda com chave do
  cliente, nunca com a gerada). O ponto 2 da série compara os braços medidos **com este código**, não com
  as rodadas antigas.

### Feature flag `reservation.readpath` — caminho CAS (default) vs. ingênuo (experimento)

`addLink` tem dois caminhos de checagem de capacidade, escolhidos por
`reservation.readpath` (`@Value` em `EventWalletService`, env `RESERVATION_READPATH`):

- **`cas`** (default) — o que está descrito acima: `EventCapRepository.tryReserve` (UPDATE
  condicional atômico). Nunca oversella. **Zero mudança** de comportamento.
- **`naive`** — lê a disponibilidade **defasada** via `EventAvailabilityRepository.findAvailable`
  (`eventcap.capacity` menos `ativos` da projeção assíncrona `event_availability`, ver "Reservation em event sourcing") e
  insere em `walletevent` **sem CAS** (só `reservePending`, **nunca** toca `eventcap`, mas appenda `SlotReserved`).
  `== 0` ⇒ "Evento lotado"; `> 0` ou `-1` ⇒ segue. Oversella proporcional ao lag do consumer — é o **braço de
  controle** do experimento de concorrência (issues #12/#13), não um bug.

O refresh por timer da Fase 2 (`ReadModelRefreshService`, `READMODEL_AVAILABILITY_REFRESH_MS`) **saiu** na #29: o lag deixou
de ser variável controlada e passou a ser o lag real do consumer, medido (ver "Reservation em event sourcing"). Os dados da
Rodada 1 (`docs/carga/resultados-*`) foram medidos com o refresh e ficam como histórico.

## Cadastro em duas fases + e-mail

1. `POST /bff/users/request-verification` grava em `pending_verifications` (hash da senha +
   código de 6 dígitos, expira em 5 min — ver `application.properties`).
2. `POST /bff/users/verify-email` confere o código e só então insere em `users`.
3. `identity/EmailVerificationService` grava o código e, na mesma transação, appenda o evento `VerificationCodeRequested`
   (`IdentityNotifications`); o **serviço `notification`** (processo à parte, #32) consome o tópico e manda o código via SMTP
   Gmail (`spring-boot-starter-mail` só nele), templates em `notification/EmailTemplates`. O monólito não envia e-mail. O
   `POST /bff/users/request-verification` responde igual; o e-mail chega de forma assíncrona (com o serviço fora, só depois que
   ele voltar). O MFA do reset de senha segue o mesmo caminho (`PasswordResetMfaRequested`).
4. reCAPTCHA v3: `POST /bff/users/verify-recaptcha` (em `gateway/BffUserController`) valida o
   token com a Google usando `RECAPTCHA_SECRET_KEY`.

## Config

- `application.properties` lê tudo de env var com default de dev:
  `SPRING_DATASOURCE_*` (inclui `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` e
  `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE`), `JWT_SECRET`, `JWT_EXPIRATION_SECONDS`,
  `RECAPTCHA_*` (`MAIL_*` saiu do monólito na #32: é do serviço `notification`), `SERVER_TOMCAT_THREADS_MAX` (default `300`),
  `RESERVATION_READPATH` (default `cas`; `naive` = braço de controle do experimento),
  `IDEMPOTENCY_RETENTION_HOURS` (default `24`; prazo de retenção das chaves de idempotência),
  `IDEMPOTENCY_RETENTION_SCAN_MS` (default `600000` = 10 min; intervalo do job de limpeza, issue #130),
  `NOTIFICATION_EVENTS_RETENTION_SCAN_MS` (default `30000`; intervalo do job que apaga do `event_store` os pedidos de e-mail
  vencidos há mais de 30 s, #32),
  `SPRING_TASK_SCHEDULING_POOL_SIZE` (default `3`; threads do scheduler dos `@Scheduled`. O default do Boot é
  **1**, compartilhada pelo `OutboxRelay`, `ReservationExpiryService` e
  `IdempotencyKeyRetentionService`: um purge em massa seguraria o relay e falsearia o lag medido do braço `naive`).
  Valores reais vêm de `Projeto/.env` — não commitar, não imprimir.
- Backbone de eventos (issue #27; ver "Event store e backbone de eventos"), todos com default de dev:
  `SPRING_KAFKA_BOOTSTRAP_SERVERS` (default `localhost:9092`; `redpanda:9092` no compose e no `run-tests.sh`),
  `EVENTS_RELAY_ENABLED` (default `false`), `EVENTS_RELAY_INTERVAL_MS` (`50`), `EVENTS_RELAY_BATCH_SIZE` (`200`),
  `EVENTS_CONSUMER_ENABLED` (default `false`), `EVENTS_CONSUMER_GROUP` (`gerenciador-eventos`),
  `EVENTS_CONSUMER_RETRY_INTERVAL_MS` (`1000`; base do backoff transitório e intervalo do retry do buraco),
  `EVENTS_CONSUMER_RETRY_MAX_ATTEMPTS` (`5`; só para o buraco irrecuperável), `EVENTS_CONSUMER_RETRY_ERROR_AFTER_ATTEMPTS` (`10`; a
  partir daí cada tentativa do retry transitório ilimitado loga em ERROR), `EVENTS_CONSUMER_BATCH_SIZE` (`500`, #146; tamanho máximo
  do lote do listener da projeção de disponibilidade, o `max.poll.records` dele; o compose o repassa), `EVENTS_SNAPSHOT_EVERY` (`100`). Sem segredo novo. As flags
  `EVENTS_RELAY_ENABLED`/`EVENTS_CONSUMER_ENABLED` têm default `false` (os testes ligam por `@SpringBootTest(properties=...)`); o
  `docker-compose.yml` passa `EVENTS_RELAY_ENABLED=true` desde a #28 (o `catalog` appenda eventos) e `EVENTS_CONSUMER_ENABLED=true` desde a #29
  (projeção assíncrona de disponibilidade). **Aviso:** com o `gerenciador-backend` no ar, o relay dele drena o `event_outbox` do
  mesmo banco que o `run-tests.sh` usa e o consumer dele move `ativos`: o `run-tests.sh` **aborta** se o container estiver
  rodando (`docker stop gerenciador-backend` antes).
- Actuator (issue #92): `spring-boot-starter-actuator` com **só** `health` exposto
  (`management.endpoints.web.exposure.include=health`); `GET /actuator/health` público devolve
  `{"status":"UP"}` (depende do Postgres). O health do mail deixou de existir com o `spring-boot-starter-mail` (#32).
  Cobertura em `HttpContractTest`.
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
