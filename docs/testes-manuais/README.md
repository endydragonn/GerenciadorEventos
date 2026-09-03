# Testes manuais

Roteiros de sanity manual (documentação + script) que exercitam o sistema no nível
HTTP contra a stack real. Complementam a suíte automatizada de
`Projeto/Backend` — não a substituem.

| Roteiro | Issue | O que prova |
|---|---|---|
| [`overselling-sob-concorrencia.md`](overselling-sob-concorrencia.md) (+ `.sh`) | #9 | Reserva `EventCap`/CAS não permite overselling: `capacity = 1` + 2 reservas concorrentes → 1 `pending`, 1 `Evento lotado`, `eventcap.quant = 0`. |

Cada script **é o teste**: sai com código ≠ 0 se a garantia verificada quebrar.
Pré-requisito comum: `docker compose -f Projeto/docker-compose.yml up -d --build db backend`.
