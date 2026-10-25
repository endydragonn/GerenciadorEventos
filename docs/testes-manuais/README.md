# Testes manuais

Roteiros de sanity manual (documentação + script) que exercitam o sistema no nível
HTTP contra a stack real. Complementam a suíte automatizada de
`Projeto/Backend` — não a substituem.

| Roteiro | Issue | O que prova |
|---|---|---|
| [`overselling-sob-concorrencia.md`](overselling-sob-concorrencia.md) (+ `.sh`) | #9 | Reserva `EventCap`/CAS não permite overselling: `capacity = 1` + 2 reservas concorrentes → 1 `pending`, 1 `Evento lotado`, `eventcap.quant = 0`. |
| `smoke-reservation-servico-separado.sh` | #33 | Reserva com o serviço `reservation` separado, capacidade 3 + 8 reservas simultâneas, banco lido no schema `reservation`; o braço é o `RESERVATION_READPATH` do backend. `cas`: 3 × 201 + 5 × "Evento lotado", overselling 0 exigido. `naive` (braço de controle): só exige 201 + "Evento lotado" = usuários e **reporta** o overselling (a janela check-then-act passa pela rede e oversela mesmo sem backlog relevante na projeção). |
| `smoke-catalog-servico-separado.sh` | #35 | Serviço `catalog` separado: host cria evento pelo BFF (com `creator_name`), API interna só com `X-Internal-Token` e sem porta no host, `gerenciador-catalog` parado → 503 (nunca "evento não existe") e recuperação, `catalog.event` sem `public.event`, outbox do catalog escoando. |

Os dois smokes da Fase 7 precisam da stack inteira com o seed ligado (`SEED_ENABLED=true`, #35):
`docker compose -f Projeto/docker-compose.yml -f Projeto/carga/docker-compose.carga.yml up -d --build`.

Cada script **é o teste**: sai com código ≠ 0 se a garantia verificada quebrar.
Pré-requisito comum: `docker compose -f Projeto/docker-compose.yml up -d --build db backend`.
