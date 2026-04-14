# Mapeamento de Endpoints CORS

## Configuração Centralizada
**Arquivo:** `Backend/src/main/java/com/gerenciador/eventos/config/CorsConfig.java`

**Variável de Ambiente:** `CORS_ALLOWED_ORIGINS`
```
CORS_ALLOWED_ORIGINS=http://localhost:8088,http://localhost:3000
```

## Endpoints Cobertos pelo CorsConfig

### 1. UserController - `/api/users`
- `POST /api/users/request-verification` - Solicitar verificação de email
- `POST /api/users/verify-email` - Verificar email e gerar JWT
- `POST /api/users/login` - Login de usuário
- `GET /api/users/{id}` - Obter dados do usuário
- `PUT /api/users/{id}` - Atualizar usuário

### 2. RecaptchaController - `/api/users`
- `POST /api/users/verify-recaptcha` - Validar token reCAPTCHA v3

**Variável de Ambiente:** `RECAPTCHA_SECRET_KEY`
```
RECAPTCHA_SECRET_KEY=6Lc7frUsAAAAABxecI7MuI8NOqtPDKdUfs5CjXr6
```

### 3. EventController - `/api/events`
- `GET /api/events` - Listar eventos
- `GET /api/events/{id}` - Obter evento
- `POST /api/events` - Criar evento
- `PUT /api/events/{id}` - Atualizar evento
- `DELETE /api/events/{id}` - Deletar evento

### 4. EventWalletController - `/api/event-wallets`
- `GET /api/event-wallets` - Listar event-wallets
- `POST /api/event-wallets` - Criar event-wallet
- `PUT /api/event-wallets/{id}` - Atualizar event-wallet

### 5. MyWalletController - `/api/wallets`
- `GET /api/wallets` - Listar carteiras
- `POST /api/wallets` - Criar carteira
- `PUT /api/wallets/{id}` - Atualizar carteira

### 6. SeedController - `/api/seed`
- `POST /api/seed/truncate` - Limpar banco de dados (dev only)

## Configuração CORS Aplicada
- **Origins Permitidas:** `http://localhost:8088`, `http://localhost:3000` (configurável via .env)
- **Métodos:** GET, POST, PUT, DELETE, PATCH, OPTIONS
- **Headers Permitidos:** Todos (*)
- **Headers Expostos:** Authorization, Content-Type
- **Credenciais:** Habilitadas
- **Cache:** 3600 segundos

## Como Adicionar Novos Endpoints
Novos endpoints criados em `/api/**` serão automaticamente cobertos pelo CorsConfig.
Não é necessário adicionar `@CrossOrigin` em nenhum lugar.

## Variáveis de Ambiente Necessárias
```bash
# .env
CORS_ALLOWED_ORIGINS=http://localhost:8088,http://localhost:3000
RECAPTCHA_SECRET_KEY=6Lc7frUsAAAAABxecI7MuI8NOqtPDKdUfs5CjXr6
```
