# stock-client

Lib (jar simples, sem repackage) com o **contrato HTTP** do serviço `stock` (issue #47, saga stock + wallet): os DTOs do hold
(`HoldRequest`, `HoldResponse`, `ReleaseRequest`), a `HoldLiberadoException` (409 da lápide) e o `StockClient`.

- **Quem usa:** o `reservation` (orquestrador da saga, a partir da passada a2) e, nos testes ponta a ponta, o próprio `stock` (o cliente
  real contra o serviço real). Nenhuma lib de contrato depende de outra.
- **`StockClient`:** Apache HttpClient 5 com pool (total e por rota; deve ser >= threads do Tomcat do chamador), keep-alive, timeouts
  explícitos (conectar, pool, resposta), `evict-idle` menor que o keep-alive do servidor e **sem retry**. `X-Internal-Token` em toda chamada.
  Erros: 400 -> `IllegalArgumentException`, 409 em `hold` -> `HoldLiberadoException`, o resto -> `RuntimeException`.
- Na passada a1 só tem as chamadas do hold; a capacidade (`EventCap`, `EventCapacityApi`, conflito de apagar) passa para cá na a2.
