# notification

Serviço de e-mail do Gerenciador de Eventos (issue #32, Fase 7: primeira extração do monólito). Consome o tópico
`identity.notification` do backbone (Redpanda) e envia o e-mail por SMTP. Sem HTTP.

- **Entrada:** eventos `VerificationCodeRequested` e `PasswordResetMfaRequested`, publicados pelo `identity` do monólito
  (event_store + outbox na mesma transação do cadastro/código; o relay do monólito leva ao tópico). Contrato em
  `shared/.../NotificationEvents.java`.
- **Idempotência:** `notification.processed_event` (schema próprio). Reentrega do mesmo evento não manda dois e-mails.
- **Retenção do dado pessoal (decisão do usuário, 2026-10-21):** o evento leva e-mail e código, e só vive a validade do código + 30 s:
  o monólito apaga a linha do `event_store`, o tópico e o `.DLT` têm `retention.ms` de 15 min 30 s e o serviço descarta evento
  com código vencido.
- **Independência:** só depende da lib `shared` e do Postgres/Redpanda; não lê tabela do monólito. Com ele parado,
  cadastro e login seguem normais e os e-mails saem quando ele volta.
- **Rodar:** `docker compose -f Projeto/docker-compose.yml up -d --build notification` (container `gerenciador-notification`).
  Variáveis de ambiente (só pelo nome): `SPRING_DATASOURCE_*`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`.
- **Testar:** `Projeto/Backend/run-tests.sh` (reactor inteiro; precisa de `db` e `redpanda`, com `gerenciador-notification` e
  `gerenciador-backend` parados).

Detalhes (fluxo, pool Hikari e a conta do Supavisor, política de erro, pendências): `Projeto/Backend/CLAUDE.md`, seção
"Serviço notification".
