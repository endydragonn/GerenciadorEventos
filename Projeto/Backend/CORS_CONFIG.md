# Mapeamento de Endpoints CORS

## Configuração Centralizada
**Arquivo:** `Backend/src/main/java/com/gerenciador/eventos/config/CorsConfig.java`

**Variável de Ambiente:** `CORS_ALLOWED_ORIGINS`
```
CORS_ALLOWED_ORIGINS=http://localhost:8088,http://localhost:3000
```

## Endpoints Cobertos pelo CorsConfig

Superfície HTTP única sob `/bff/**` (issue #78) — os controllers `bff/Bff*Controller`:

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
RECAPTCHA_SECRET_KEY=6Lc7frUsAAAAABxecI7MuI8NOqtPDKdUfs5CjXr6
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
- **Origins Permitidas:** `http://localhost:8088`, `http://localhost:3000` (configurável via .env)
- **Métodos:** GET, POST, PUT, DELETE, PATCH, OPTIONS
- **Headers Permitidos:** Todos (*)
- **Headers Expostos:** Authorization, Content-Type
- **Credenciais:** Habilitadas
- **Cache:** 3600 segundos

## Como Adicionar Novos Endpoints
Novos endpoints criados em `/bff/**` serão automaticamente cobertos pelo CorsConfig.
Não é necessário adicionar `@CrossOrigin` em nenhum lugar.

## Variáveis de Ambiente Necessárias
```bash
# .env
CORS_ALLOWED_ORIGINS=http://localhost:8088,http://localhost:3000
RECAPTCHA_SECRET_KEY=6Lc7frUsAAAAABxecI7MuI8NOqtPDKdUfs5CjXr6
```
