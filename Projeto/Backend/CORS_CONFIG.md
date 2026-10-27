# Mapeamento de Endpoints CORS

## Configuração Centralizada
**Arquivo:** `Backend/monolito/src/main/java/com/gerenciador/eventos/shared/CorsConfig.java`

Um único bean `CorsConfigurationSource` (issue #133). O `SecurityConfig` o recebe por injeção e o usa em
`.cors(...)`: o filtro do Spring Security responde o preflight antes de qualquer controller. Não existe
mapping `WebMvcConfigurer` nem outro bean de CORS; ao mexer em CORS, mexa só no `CorsConfig`.

**Variável de Ambiente:** `CORS_ALLOWED_ORIGINS`
```
CORS_ALLOWED_ORIGINS=http://localhost:8088,http://localhost:3000
```

Lista separada por vírgula, com `trim()` e descarte de itens vazios; cada item é uma origem EXATA
(esquema + host + porta, sem barra final). `*` é recusado na subida (`IllegalStateException`), porque as
credenciais ficam habilitadas. Origem fora da lista não recebe `Access-Control-Allow-Origin` (o preflight
volta 403 sem o header). Sem a variável vale o default acima (Flutter em `:8088` e `:3000`).

O `docker-compose.yml` não lista a variável: o `backend` herda o `Projeto/.env` inteiro (`env_file`).
Para liberar outra origem (ex.: o front servido em outro host/porta), acrescente a linha ao `Projeto/.env`
com a lista COMPLETA (o valor substitui o default) e recrie o `backend`.

Abrir o front por `http://127.0.0.1:8088` ou pelo IP da LAN conta como outra origem: acrescente-a ao
`CORS_ALLOWED_ORIGINS` do `.env`. O `flutter run` deve usar `--web-port=3000` para bater com o default.

## Headers expostos (`exposedHeaders`)

`shared/CorsConfig` expõe ao browser os headers de resposta `Authorization`, `Content-Type` e, desde a
issue #24, `Idempotency-Key` (o `POST /bff/event-wallets` devolve a chave usada, gerada pelo servidor ou
recebida; sem expor, JS de outra origem não consegue lê-la). Desde a #133 essa lista vale para todas as
respostas, porque só existe uma configuração (antes o filtro do `SecurityConfig` respondia primeiro e expunha
só `Idempotency-Key`). Para enviar o header na requisição, `allowedHeaders("*")` já cobre.

## Endpoints Cobertos pelo CorsConfig

Superfície HTTP única sob `/bff/**` (issue #78) — os controllers `gateway/Bff*Controller`:

### 1. BffUserController - `/bff/users`
- `POST /bff/users/request-verification`, `/verify-email`, `/resend-verification-code`
- `POST /bff/users/login`, `GET /bff/users/me`
- `GET /bff/users/{id}`, `PUT /bff/users/{id}`, `PUT /bff/users/{id}/password`
- Reset de senha com MFA: `/bff/users/{id}/request-password-reset-mfa`,
  `/verify-password-reset-mfa`, `/password-with-mfa`

### 2. BffUserController - reCAPTCHA
- `POST /bff/users/verify-recaptcha` - Validar token reCAPTCHA v3

**Variável de Ambiente:** `RECAPTCHA_SECRET_KEY`
```
RECAPTCHA_SECRET_KEY=<sua-chave-secreta-do-recaptcha>
```

### 3. BffEventController - `/bff/events`
- `GET /bff/events`, `GET /bff/events/{id}`, `POST /bff/events`, `PUT /bff/events/{id}`,
  `DELETE /bff/events/{id}`, `GET /bff/events/search`, imagem em `/bff/events/{id}/image`

### 4. BffEventWalletController - `/bff/event-wallets`
- `POST /bff/event-wallets`, `POST /bff/event-wallets/confirm`, `DELETE /bff/event-wallets`,
  `GET /bff/event-wallets/user/{userId}`

### 5. BffMyWalletController - `/bff/my-wallets`
- `GET /bff/my-wallets/{userId}`, `POST /bff/my-wallets/validate`,
  `POST /bff/my-wallets/ensure/{userId}`

### 6. BffSeedController - `/bff/seed`
- `POST /bff/seed` - Popular banco de dados (dev only)

## Configuração CORS Aplicada
- **Origins Permitidas:** `http://localhost:8088`, `http://localhost:3000` (default; configurável via `CORS_ALLOWED_ORIGINS` no .env, sempre origens exatas, nunca `*`)
- **Caminhos:** `/**` (o filtro de segurança também atende o preflight de `/actuator/health`)
- **Métodos:** GET, POST, PUT, DELETE, PATCH, OPTIONS
- **Headers Permitidos:** Todos (*)
- **Headers Expostos:** Authorization, Content-Type, Idempotency-Key
- **Credenciais:** Habilitadas
- **Cache:** 3600 segundos

## Como Adicionar Novos Endpoints
Novos endpoints criados em `/bff/**` serão automaticamente cobertos pelo CorsConfig.
A cobertura está em `HttpContractTest` (`corsPreflight_*`, `cors_respostaReal_*`) e `CorsConfigTest`.
Não é necessário adicionar `@CrossOrigin` em nenhum lugar.

## Variáveis de Ambiente Necessárias
```bash
# .env
CORS_ALLOWED_ORIGINS=http://localhost:8088,http://localhost:3000
RECAPTCHA_SECRET_KEY=<sua-chave-secreta-do-recaptcha>
```
