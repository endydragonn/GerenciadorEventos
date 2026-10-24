# reservation-client

Lib (jar simples, sem repackage) com o **contrato HTTP** do serviço `reservation` (issue #33): os DTOs (`EventWallet`, `EventCap`), a
`IdempotencyConflictException`, as interfaces `EventWalletApi`/`EventCapacityApi` e o `ReservationClient`.

- **Quem usa:** o `monolito` (gateway e catalog chamam o serviço por aqui; o bean sai de `ReservationClientConfig`) e o `reservation` (os mesmos
  DTOs são o corpo da API interna; os testes ponta a ponta usam o cliente real contra o serviço real).
- **`ReservationClient`:** Apache HttpClient 5 com pool de conexões (`max-connections` no total e por rota, >= threads do Tomcat do gateway),
  keep-alive, timeouts de conexão/pool/resposta explícitos, `evict-idle` menor que o keep-alive do servidor e sem retry automático. No braço
  `naive`, `addLink` faz as DUAS chamadas (disponibilidade e, depois, a reserva sem CAS).
- **Erros:** 400 -> `IllegalArgumentException`, 422 -> `IdempotencyConflictException`, 409 -> `IllegalStateException`, 404 -> `null` onde
  faz sentido, outros -> `RuntimeException`.
- Não depende de nenhum módulo do monólito (ArchUnit: `reservationclient_nao_depende_de_modulo_do_monolito`).
