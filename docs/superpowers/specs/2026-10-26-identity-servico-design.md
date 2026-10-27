# Design: extrair o `identity` como serviço (issue #36)

Data: 2026-10-26. Padrão a repetir: os serviços `reservation` (#33) e `catalog` (#35, spec `2026-10-24-catalog-servico-design.md`).
Decisões de arquitetura já tomadas antes desta spec (G0 da #35): JWT **assimétrico com JWKS**, `identity` assina e expõe
`/.well-known/jwks.json`, o `gateway` valida sem estado com cache por `kid`, claims `uid` e `isHost`; `/internal/users/names` em lote para o
`creator_name`; `creator_id` `integer` sem FK.

## Escopo e aceite

1. `identity` vira serviço: módulo Maven `identity`, `identity/Dockerfile`, imagem e container `gerenciador-identity`, API interna
   `/internal/**` na porta **8084** só na rede `gerenciador-net` (sem porta no host), autenticada por `X-Internal-Token`
   (`INTERNAL_API_TOKEN`, o mesmo dos outros serviços), schema `identity` (CRUD, **não** event sourcing), lib `identity-client` (contrato +
   `IdentityClient` HTTP). Nenhum serviço lê tabela de outro; sem FK cross-schema.
2. O `identity` é dono de: `users`, `mywallet`, `pending_verifications` (com as funções e triggers deles), login (BCrypt), cadastro em duas
   fases, reset de senha com MFA, troca de senha, reCAPTCHA (verificação com o Google; `RECAPTCHA_SECRET_KEY` só nele), a emissão do JWT, o
   stream `identity.notification` (pedido de e-mail, contrato e tópico **congelados**: o `notification` não muda) com o relay e o
   `NotificationEventRetentionService`, e os eventos de ciclo de vida.
3. O `gateway` (monólito) não tem mais o pacote `identity`: só `gateway/*`, `security/` (validação do JWT) e `shared` (CORS, scheduling).
   Toda chamada ao `identity` é HTTP pelo `IdentityClient`. `/bff/**` mantém o contrato do Flutter (`HttpContractTest`), com as mudanças
   listadas em "Contrato".
4. Login, cadastro (request-verification, verify-email, resend), MFA (request, verify, password-with-mfa), troca de senha e reCAPTCHA cobertos
   por teste de integração **no módulo `identity`** (cliente real × serviço real, Postgres real) e pelo `HttpContractTest` (gateway × fake).

## Decisões

| Tema | Decisão |
|---|---|
| Algoritmo | **RS256** (RSA 2048), header `kid` = thumbprint SHA-256 da JWK pública (RFC 7638). O gateway aceita **só RS256** (o `NimbusJwtDecoder` com JWK set já restringe) e valida `exp`, `nbf` e `iss` = `gerenciador-identity` |
| Claims | `sub` = e-mail (como hoje; o `/me` e o Flutter não decodificam o token), `uid` (long), `isHost` (boolean, lido do banco **na emissão**), `iss`, `iat`, `exp` (`JWT_EXPIRATION_SECONDS`, default 36000, agora só no identity). **Todo** token emitido (login e verify-email) leva `uid` e `isHost` (hoje o login não leva `isHost`) |
| Chave de assinatura | **gerada no 1º boot e persistida** num arquivo JWK (privada + pública) em `JWT_SIGNING_KEY_PATH` (default `/var/lib/identity/jwt-signing-key.json`; volume nomeado `identity-keys` no compose; permissão 600 quando o FS suporta). Sobrevive a `docker restart`/`up --build` (o kit de carga segura tokens entre blocos e a limpeza reinicia serviços). Nenhuma variável nova obrigatória no `.env`. Arquivo ilegível ou corrompido = **falha no boot** (nunca regera por cima: tokens válidos sumiriam em silêncio). `docker compose down -v` apaga o volume: todo token antigo vira 401 (relogar). Rotação = apagar o arquivo e reiniciar (fora de escopo automatizar). `JWT_SECRET` deixa de ser lido por qualquer serviço |
| JWKS | `GET /.well-known/jwks.json` no identity, só a parte pública. O `InternalTokenFilter` do identity isenta **somente a URI exata** `/.well-known/jwks.json` com método GET (`request.getRequestURI().equals(...)`, sem prefixo; `/.well-known/jwks.json;x` ou `%2e` caem no filtro e dão 401). Chave pública não é segredo, e o endpoint nem é alcançável de fora da rede |
| Validação no gateway | **filtro próprio** (o `JwtAuthenticationFilter` atual) trocando só a validação por um `JwtDecoder` (`NimbusJwtDecoder.withJwkSetUri(IDENTITY_SERVICE_URL + "/.well-known/jwks.json")`, `RestOperations` com connect 2 s / read 3 s, validador `createDefaultWithIssuer`). **Não** usar `oauth2ResourceServer()`: ele dá 401 a bearer inválido mesmo em rota `permitAll`, e hoje bearer inválido/vencido em rota pública segue como anônimo (contrato). O `JWKSource` é montado **explicitamente** (`JWKSourceBuilder` do Nimbus; o `withJwkSetUri` do Spring 6.5 vem com `rateLimited(false)` e cache de 5 min): cache de **24 h**, refetch por `kid` desconhecido **com limite de taxa** (no máximo 1 fetch a cada 30 s; `kid` aleatório num bearer anônimo nunca vira um fetch por requisição) e **tolerância a queda** (`outageTolerant`, 24 h: identity fora não derruba a validação enquanto a chave conhecida estiver no cache). Identity fora com cache vazio = token não valida = 401 em rota protegida, anônimo em rota pública (nunca 500, nunca thread presa além do timeout de 3 s) |
| Principal | `Authentication` com nome = `sub` (e-mail) e o `Jwt` disponível; `HostAuthorizationApi.currentUserId()` = claim `uid` (**sem consulta**: some o `LOWER(email)` por reserva da #57), `ensureUserIsHost()` = claim `isHost` (sem consulta). A interface `HostAuthorizationApi` passa para o pacote do gateway (`gateway/security`), não é mais do identity |
| Estado do token | Consequência do G0 (validação sem estado): conta desativada, troca de senha ou mudança de `host` só valem para tokens **novos**; um token emitido continua válido até `exp`. Usuário do token apagado do banco não dá mais 401 na `event-wallets` (#57 dava): nenhuma rota apaga usuário. O teste da #57 muda de expectativa e o PR registra |
| `mywallet` | **Vai para o schema `identity`** com `users` (FK `mywallet_user_id_fkey` e o trigger `create_wallet_for_user` amarram as duas; é o único arranjo sem FK cross-schema). O gateway **deixa de conferir a carteira na reserva** (`EventWalletGateway`, `addLink` e `/validate`): a carteira nasce no MESMO `INSERT` do usuário (trigger), nenhuma rota apaga usuário ou carteira (`MyWalletRepository.deleteByUserId` sem chamador) e o `uid` vem assinado pelo identity; conferir viraria um salto HTTP por reserva. Verificado no `meu_banco` em 2026-10-26: 0 usuários sem carteira. `/bff/my-wallets/**` passa a chamar o identity por HTTP (`/internal/wallets/**`), mesmo contrato |
| API interna | `/internal/users/**` espelhando o `UserApi` (create, get por id/e-mail, update, deactivate, validate, login, change-password, MFA request/verify/change, `exists`, `POST /internal/users/names` com ids no corpo), `/internal/registrations/**` (`EmailVerificationApi`: request, verify, resend), `/internal/wallets/**` (`MyWalletApi`), `POST /internal/recaptcha/verify`. **Login e verify-email devolvem `{user, token}`** (o token é emitido no identity; o gateway não assina nada). Erros: 400 regra (`IllegalArgumentException`, texto puro; pedido malformado também, nunca o JSON padrão do Spring), 401 credenciais/código inválidos onde o `/bff` já dava 401 **sempre com o marcador `X-Identity-Credenciais-Invalidas`** (só login e verify-MFA; o 401 do `InternalTokenFilter` NÃO leva o marcador, para um `INTERNAL_API_TOKEN` errado virar 503 e não "senha errada"), 404 com marcador `X-Identity-Nao-Encontrado`, 500 genérico (detalhe só no log). Pedido malformado = 400. **Três pontos fixados na fatia 2 (pendências do revisor da fatia 1):** (a) **verify-MFA de usuário inexistente = 401 com o marcador de credenciais** (nunca 404: o cliente lançaria `IdentityNaoEncontradoException` e o `/bff` viraria 500); e-mail inexistente no login e senha ou código nulos também = 401 marcado, nunca 500; (b) `POST /internal/recaptcha/verify` responde **sempre 200 com o envelope `{status, body}`** (`status` 200 + `{success, score, action}`, ou `status` 500 + `{success:false, error}`): o status/corpo do reCAPTCHA de hoje não se confunde com o 5xx de infraestrutura (que vira indisponível); (c) `MyWalletApi.exists` chama-se **`walletExists`** (o `IdentityClient` implementa `UserApi` e `MyWalletApi` e as duas tinham `boolean exists(Long)`). `POST`/`PUT /internal/users` gravam `admin` como hoje (nenhum código autoriza por `admin`) e **nunca `host`** |
| `host` não é gravável | `PUT /internal/users/{id}` continua **sem** persistir `host` (como o `UserRepository.update` hoje). Teste: `PUT` com `isHost: true` e depois login → token com `isHost=false`. Sem isso o IDOR #166 viraria auto-promoção a host |
| Cliente | `IdentityClient` (HttpClient 5 como `CatalogClient`): pool `IDENTITY_CLIENT_MAX_CONNECTIONS` (default **400**, >= as 300 threads do Tomcat do gateway, como os outros clientes), conectar 2 s, pool 5 s, resposta **5 s**, evict 20 s, sem retry; `IDENTITY_SERVICE_URL` (default `http://localhost:8084`; `http://identity:8084` no compose). Rede, timeout, 5xx, 404 sem marcador → `IdentityUnavailableException` → **503 genérico** no gateway (handler como o `CatalogIndisponivelHandler`). `exists` nunca devolve `false` por falha. `CreatorNames` segue tolerante (nome nulo) |
| reCAPTCHA | `POST /bff/users/verify-recaptcha` → identity, que chama `RECAPTCHA_VERIFY_URL` (property, default a URL do Google; o teste aponta para um fake local) com `RECAPTCHA_SECRET_KEY`. Mesma resposta e mesmo limiar de score de hoje |
| Eventos de ciclo de vida | stream `identity.user` (tópico de mesmo nome), um stream por usuário (`streamId` = `user_id`), na mesma transação da escrita: `UserRegistered` (verify-email e create direto), `UserUpdated`, `UserDeactivated`, `UserPasswordChanged`. **Payload só com ids** (`userId`, `schemaVersion` 1), sem e-mail/nome (a exceção de dado pessoal é só do `identity.notification`). Sem consumer nesta issue (logo, sem group novo para o `run-tests.sh` mover) |
| Event store | `events.schema=identity` (event_store, outbox, snapshot, checkpoint próprios); relay ligado no compose. O monólito passa a `EVENTS_RELAY_ENABLED=false` (não tem mais o que publicar) |
| Pool | identity: Hikari **100**, `connection-timeout` 5 s, Tomcat 300, virtual threads off. Monólito (gateway): Hikari **350 → 10** (só `actuator/health` e os testes da lib `shared.events`, que continuam no módulo). Soma: 1000 + **10** + 3 + 100 + **100** = **1213** ≤ `POOLER_MAX_CLIENT_CONN` 1500. Mesmo tenant/usuário do Supavisor: o pool real não cresce. `ConnectionPoolSizeTest` muda |
| Migração | `db/migrations/20261028-identity-schema.sql` (padrão da `20261026`): backup `pg_dump -h localhost -U admin --clean --if-exists -n public meu_banco` antes; com `backend` e `identity` parados; `CREATE SCHEMA identity`, event store do serviço, `ALTER TABLE ... SET SCHEMA identity` de `users`, `mywallet`, `pending_verifications` (identity de `user_id` vai junto, sem `setval`), recria as funções/triggers apontando para `identity.` (as de `01`/`08` têm `public.` fixo), **move os streams `identity.notification`** (event_store, outbox, checkpoint, snapshot) de `public` preservando `global_position` e ajusta só a sequence de `global_position`; ABORTA se existir FK de fora de `identity` apontando para `users`/`mywallet`; antes do `SET SCHEMA`, derruba as tabelas `identity.users`/`mywallet`/`pending_verifications` que o `15-identity.sql` da fatia 2 tenha criado **só se estiverem vazias** (com linha = aborta; nunca recicla `user_id`); `lock_timeout` 5 s; idempotente. Verificada por `db/migrations/verify-20261028-migration.sh` (banco descartável, padrão do `verify-20261026`). Rollback no cabeçalho (`DROP SCHEMA identity CASCADE` + restore do dump, válido só antes de reabrir o tráfego). Init de banco novo: `15-identity.sql` substitui `02`/`06`/`08` e a parte de `01` que for de usuário, incluído no `supabase-app-init.sql`; `99-tests.sql` e `seed-data.sql` qualificados |
| Guarda de boot | o `identity` **não sobe** se `to_regclass('public.users')` não for nulo (migração `20261028` pendente): senão rodaria sobre tabelas novas vazias e o 1º cadastro herdaria `user_id` 1 de outro usuário (eventos e reservas). O `run-tests.sh` aborta pelo mesmo motivo |
| Token interno | segue **um** `INTERNAL_API_TOKEN` para todos os serviços (resolve o "único até a #36" da spec do catalog): todos usam o **mesmo usuário de banco** do Supavisor, então quem tem o container de qualquer serviço já escreve em `identity.users` direto; tokens por serviço não isolariam nada enquanto o usuário de banco for um só (evolução junto com usuários de banco por serviço). Nenhuma variável nova no `.env` |
| `uid` reciclado | `TRUNCATE ... RESTART IDENTITY` só existe em teste/carga. A limpeza entre blocos (`limpar-banco.sh`) **apaga a chave** (`docker exec gerenciador-identity rm` do arquivo, ou o volume) e reinicia o identity: todo token anterior ao TRUNCATE vira 401 (o kit re-semeia e reloga a cada bloco de qualquer forma). Os testes usam chave temporária por JVM |
| Cadastro: tentativas | `verify-email` com código errado **incrementa `attempts`** e recusa a partir de `max_attempts` (hoje o contador nunca sobe: o código de 6 dígitos era força-bruta livre); o resend gera código novo e zera o contador (custa um e-mail à vítima por rodada) |
| `/me` | resolve pelo `uid` (`findById`), não pelo `sub`: depois de trocar o e-mail o mesmo token não vira duas identidades |
| `env_file` | o `identity` **não** usa `env_file` (recebe só banco, `INTERNAL_API_TOKEN`, `RECAPTCHA_SECRET_KEY`, `JWT_EXPIRATION_SECONDS`); o `backend` segue com `env_file` (CORS etc.), mas não lê mais `JWT_SECRET` nem `RECAPTCHA_SECRET_KEY` |
| reCAPTCHA | `RestTemplate` do identity com timeout (conectar 2 s, ler 5 s); Google fora/lento = a mesma resposta de erro de hoje; identity fora = 503 no gateway |
| Teste do contrato real | `IdentityApiE2ETest` no módulo (cliente real × serviço real) + `FakeIdentityServer` no monólito (JDK `HttpServer`, `LauncherSessionListener`, assina com uma chave RSA de teste e serve o JWKS dela; `foraDoAr(true)`) |

## Contrato `/bff/**` (o que muda)

- Token: passa de HS256 para RS256 com `uid`/`isHost`/`iss`/`kid`. O Flutter só guarda e reenvia (verificar por grep em `Projeto/frontend`).
  Tokens HS256 antigos viram 401 (relogar uma vez).
- Identity fora: rotas `/bff/users/**` e `/bff/my-wallets/**` → 503 genérico; login/cadastro → 503. Reserva e leitura de evento com token
  válido **não dependem** do identity (JWKS em cache).
- `event-wallets` com `uid` de usuário inexistente: não dá mais 401 (estado do token, acima).
- Bearer inválido/vencido em rota pública: continua anônimo (200), nunca 401 (teste novo no `HttpContractTest`).
- O resto (paths, corpos, status) igual. #166 (IDOR em `/bff/my-wallets/{userId}` e `PUT /bff/users/{id}`) **não** é resolvida aqui e a
  superfície não muda; o PR registra.

## Fatias (cada uma verde, um commit + push por fatia)

1. **Contrato e lib**: módulo `identity-client` (DTOs `User`, `MyWallet`, `LoginResult`; interfaces `UserApi`, `EmailVerificationApi`,
   `MyWalletApi`, `RecaptchaApi`; exceções; `IdentityClient`). O monólito passa a importar os tipos de `identityclient.api` (o pacote
   `identity.api` sai), ainda com a implementação local. Testes sem Spring do cliente (`IdentityClientIndisponivelTest`).
2. **Serviço**: módulo `identity` (código do pacote `identity` com queries qualificadas `identity.`, RS256 + chave persistida + JWKS,
   controllers internos, `InternalTokenFilter`, reCAPTCHA, eventos de ciclo de vida, relay/retenção do `identity.notification`),
   `15-identity.sql` **convivendo** com `02`/`06`/`08` (o monólito ainda usa `public`), testes migrados (`UserTest`,
   `IdentityNotificationEventsTest`, `NotificationEventRetentionTest`, `MyWalletTest`, trechos de schema) + `IdentityApiE2ETest`,
   `IdentityConfigTest`, `JwtSigningKeyTest` (restart: mesma chave e token antigo válido; arquivo corrompido = falha no boot).
3. **Cutover**: migração `20261028` + saída de `02`/`06`/`08`; gateway com `IdentityClient` e validação JWKS; `HostAuthorizationApi` no
   gateway; sai o pacote `identity` do monólito e o jjwt dele; `EventWalletGateway` sem a checagem de carteira; `FakeIdentityServer`;
   `HttpContractTest` (novos casos: bearer lixo em rota pública = 200; token RS256 de outra chave = 401; token sem `uid` = 401; HS256 = 401;
   identity fora = 503 em `/bff/users/me` e reserva com token válido segue); `ModuleBoundaryArchTest` (`identityclient..` como o
   `catalogclient..`; nada depende do código do serviço `identity`); `TestSchemas`/`GlobalDbTruncator`; compose (serviço `identity`,
   volume `identity-keys`, `backend` depende dele healthy, recebe `IDENTITY_SERVICE_URL`, perde `JWT_SECRET`/`RECAPTCHA_SECRET_KEY` se
   não usar mais `env_file` para eles); todos os Dockerfiles copiam os poms novos; `run-tests.sh` (aborta com `gerenciador-identity` no ar
   e se o banco ainda tiver `public.users`).
4. **Operação e docs**: `docs/testes-manuais/smoke-identity-servico-separado.sh` (com o override de carga: cadastro/login pelo BFF,
   `/internal/**` 401 sem token, JWKS público só pela rede, `docker stop gerenciador-identity` → reserva com token existente = 201 e
   `/bff/users/me` = 503; `docker restart gerenciador-identity` → token antigo continua válido; `smoke-reservation` no braço `cas` com
   overselling 0), `E:\TCC\carga-overrides\limpar-banco.sh` (fora do repo: trunca `identity` junto, reinicia o identity),
   `carga/README.md`, `Backend/CLAUDE.md`, `db/CLAUDE.md`, `CLAUDE.md` raiz, `docs/arquitetura/fase7-servicos.md`, `DOCKER-COMANDOS.md`.

## Testes (critério de aceite)

1. Login certo → 200 com `token` cujo header tem `alg=RS256` e `kid` presente no JWKS, claims `uid`/`isHost`/`iss`; senha errada → 401.
2. Cadastro: request-verification → evento `VerificationCodeRequested` no `identity.event_store`; verify-email com o código → 201 + token +
   `UserRegistered` + carteira criada; código errado → 400; resend → novo código.
3. MFA: request → `PasswordResetMfaRequested`; verify certo/errado; password-with-mfa troca a senha (login com a nova funciona, a antiga não).
4. Troca de senha com a atual certa/errada.
5. reCAPTCHA com o fake: score alto → ok; baixo/erro → recusa; sem secret → a mesma resposta de hoje.
6. Gateway: bearer lixo em `GET /bff/events` → 200; token de outra chave/HS256/sem `uid`/vencido em rota protegida → 401; `uid` do token
   é o `userId` da reserva sem consulta ao identity (fake fora do ar e reserva 201).
7. Restart do identity (dois contextos com o mesmo `JWT_SIGNING_KEY_PATH`) → o token do primeiro valida no JWKS do segundo.
8. `PUT /internal/users/{id}` com `isHost: true` → login seguinte com `isHost=false`.
9. Depois da migração: zero FK de `identity.*` para outro schema e de fora para `identity.*`; `mywallet_user_id_fkey` em `identity.mywallet`;
   `to_regclass('public.users') IS NULL`; trigger cria a carteira em `identity.mywallet`.
10. `X-Internal-Token` ausente/errado → 401 em toda rota de `/internal/**` (inclusive `/internal;x=1/...`), JWKS 200 sem token, e
    `/.well-known/jwks.json;x` → 401.
11. JWKS: 100 bearers com `kid` aleatório em `GET /bff/events` → no máximo 1 fetch ao identity no intervalo do limite; identity fora por
    mais de 5 min com a chave em cache → reserva com token válido segue 201.
12. `verify-email` com código errado `max_attempts` vezes → o código certo é recusado em seguida.
13. Identity com `public.users` presente → não sobe (mensagem clara).
14. Migração sobre banco com `identity.users` vazia criada pelo `15` → migra; com `identity.users` com linha → aborta.

## Revisão adversarial (2026-10-26, modo rápido, achados não reverificados)

Relatórios da revisão ficaram fora do repo. Aplicados acima (solução única): cache/limite de taxa/tolerância a
queda do JWKS; pool 400 e resposta 5 s do cliente; migração tolerante às tabelas vazias da fatia 2 + `verify-20261028`; guarda de boot;
token interno único com a justificativa; chave apagada na limpeza entre blocos (`uid` reciclado); `attempts` no `verify-email`; `/me` por
`uid`; `env_file`; timeout do reCAPTCHA. Descartados com motivo: flood de login com BCrypt prende threads do gateway (pré-existente: hoje o
BCrypt roda NAS threads do gateway; o projeto não tem rate limit em lugar nenhum); conta desativada ainda faz login (pré-existente, fora do
escopo). **Pré-existentes ligados à #166 (fora de escopo, registrados na issue):** `PUT /bff/users/{id}` grava `password` como veio (com o
IDOR, um usuário grava um hash bcrypt conhecido na conta de outro e entra como ele) e o MFA de reset não conta tentativas; a extração
mantém a superfície igual (não piora). Consequência aceita do G0: rebaixar `host` por SQL só vale para tokens novos (até `exp`).

## Fora de escopo

#166 (IDOR pré-existente); revogação de token / lista negra; rotação automática de chave; UUID de usuário; consumer dos eventos de ciclo de
vida (projeção de `creator_name` fica como evolução); remover o datasource do gateway (S2/#37 pode decidir); paginação.
