# identity

Serviço `identity` (issue #36, Fase 7: quarta extração). Dono de `users`, `mywallet` e `pending_verifications` (schema `identity`,
CRUD, **não** event sourcing), do login (BCrypt), do cadastro em duas fases, do reset de senha com MFA, da troca de senha, do reCAPTCHA
(`RECAPTCHA_SECRET_KEY` só aqui) e da **emissão do JWT** (RS256, `kid` = thumbprint RFC 7638, claims `sub` = e-mail, `uid`, `isHost`,
`iss` = `gerenciador-identity`, `iat`, `exp`). Spec: `docs/superpowers/specs/2026-10-26-identity-servico-design.md`.

- **Porta 8084**, só na rede `gerenciador-net`. API interna `/internal/**` autenticada por `X-Internal-Token` (`INTERNAL_API_TOKEN`,
  o mesmo dos outros serviços); contrato tabelado em [`../identity-client/README.md`](../identity-client/README.md). `GET /.well-known/jwks.json`
  (só a chave pública) é isento do token, **somente a URI exata com GET**.
- **Chave de assinatura**: gerada no 1º boot e persistida em `JWT_SIGNING_KEY_PATH` (default `/var/lib/identity/jwt-signing-key.json`, volume
  `identity-keys` no compose; 600 quando o FS suporta). Arquivo ilegível/corrompido = falha no boot (nunca regera por cima). Rotação =
  apagar o arquivo e reiniciar (todo token vira 401).
- **Eventos** (event store próprio em `identity.event_store`, `events.schema=identity`, relay ligado no compose): `identity.notification`
  (pedido de e-mail; contrato e tópico congelados, o `notification` não muda) e `identity.user` (ciclo de vida, **payload só com ids**:
  `UserRegistered`, `UserUpdated`, `UserDeactivated`, `UserPasswordChanged`, na mesma transação da escrita; sem consumer nesta issue).
- **Pool**: Hikari 100 (`connection-timeout` 5 s), Tomcat 300, virtual threads off (`IdentityConfigTest`).
- **Testes** (Postgres e Redpanda reais): `IdentityApiE2ETest` (IdentityClient real × serviço real), `JwtSigningKeyTest`,
  `IdentityConfigTest`, `SchemaValidationTest`, `UserLifecycleEventsTest`, `UserTest`, `MyWalletTest`, `IdentityNotificationEventsTest`,
  `NotificationEventRetentionTest`. Chave de assinatura temporária por JVM e reCAPTCHA falso (`TestSupport/IdentityTestSession`).
- **Fatia 2**: o monólito AINDA tem o pacote `identity` e `public.users`; o cutover (migração `20261028`, gateway com `IdentityClient`, guarda
  de boot) é a fatia 3.
