---
project: Event-Management-System
subproject:
type: note
tags: [architecture, decision, queue, rabbitmq, rate-limiting]
created: 2026-08-26
---

> **Vendorizado de:** `D:\Claude\Brain\01-Projects\Event-Management-System\Architecture-Decision-Async-Checkout-Queue.md`
> **Sincronizado em:** 2026-09-03 · sincronização manual (ver `README.md`)

# Decisão de Arquitetura — Fila Assíncrona de Checkout e Reserva com Expiração em Alta Carga

Complementa [Backend-Architecture](Backend-Architecture.md) e [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md): o `UPDATE` condicional resolve a corretude do `EventCap` sob contenção, mas não resolve dois problemas de *volume e retenção*: (1) um pico de requisições simultâneas competindo pela mesma linha gera uma rajada inútil de tentativas; (2) sem limite de tempo, um item colocado no carrinho e nunca finalizado prende a vaga indefinidamente — pior ainda sob alta demanda, quando outras pessoas estão competindo por ela.

> **Nota (2026-09-02):** esta camada virou o microsserviço **`admission`** na decomposição
> ([Architecture-Decision-Microservices-Split](Architecture-Decision-Microservices-Split.md)) — Fase 9 do roadmap, issues #41–#43 em
> [Roadmap e Issues](../../BACKLOG.md). Entra por último, depois do teste de carga definir o limiar X.
> Com o backbone Kafka já no stack (para os eventos de domínio), a escolha "fila RabbitMQ vs.
> tópico Kafka particionado" para a admissão está **em aberto** — ver "Alternativas".

## Sinal de carga

Contador de requisições por evento numa janela deslizante, em Redis (`INCR` + `EXPIRE` por bucket). É só um sinal de carga — não é fonte de verdade de capacidade.

- **RPM(evento) < X**: fluxo síncrono direto, como já era — `UPDATE` condicional na hora, `WalletEvent` já nasce `confirmed`, sem expiração forçada.
- **RPM(evento) ≥ X**: os dois mecanismos abaixo entram em ação.

## Mecanismo 1 — Fila (RabbitMQ) para admissão

- Fila (ou routing key) por evento, consumida por um worker pool com concorrência controlada.
- Cada worker consome uma mensagem de "colocar no carrinho" e tenta o `UPDATE` condicional em nome daquela requisição.
- A concorrência do worker pool é o controle real de quantas tentativas simultâneas chegam ao banco — a fila não protege a invariante (isso já é papel do CAS), só amortece o volume.
- Espera na própria fila (antes do worker processar) usa TTL nativo de mensagem do RabbitMQ (`x-message-ttl` + dead-letter), sem precisar de campo próprio no banco — é só descarte de requisição muito antiga, não a regra de negócio do carrinho.

## Mecanismo 2 — Reserva com expiração (`max_time_in_line`)

Quando o worker consegue o `UPDATE` condicional (vaga decrementada), o item já está reservado — não é só "na fila esperando", é **estoque debitado na hora**:

1. `EventCap.quant` decrementado.
2. `WalletEvent` criada com `status = 'pending'` e `expires_at = now() + EventCap.max_time_in_line`.
3. Cliente tem até `expires_at` para chamar a ação de fechar compra (`pending → confirmed`).
4. Se não fechar a tempo, um **job de expiração** varre `WalletEvent` com `status = 'pending' AND expires_at < now()`; para cada uma, numa transação: `UPDATE EventCap SET quant = quant + 1 ...` e `status = 'expired'`. Implementado em 2026-09-01 como `@Scheduled` a cada 30s (`ReservationExpiryService`); com o Supabase no stack, `pg_cron` é alternativa. O job vive no serviço `reservation`.

`max_time_in_line` é lido no momento da reserva (snapshot em `WalletEvent.expires_at`) — mudar o valor em `EventCap` depois não afeta reservas já em andamento, só as novas.

## Fluxo completo

```
Cliente ──"colocar no carrinho"──▶ RPM(evento) < X?
                                        │
                       sim ────────────┴──────────── não
                        │                              │
                        ▼                              ▼
              UPDATE condicional              publica na fila (RabbitMQ)
              direto (síncrono)                        │
                        │                               ▼
                        ▼                       worker consome, tenta
              WalletEvent = confirmed           UPDATE condicional
              (sem expiração)                           │
                                              sucesso ───┴─── sem vaga
                                                 │               │
                                                 ▼               ▼
                                   WalletEvent = pending    "sem vaga"
                                   expires_at = now()+
                                   max_time_in_line
                                                 │
                             cliente confirma ───┴─── expires_at passa
                             antes do prazo              │
                                 │                        ▼
                                 ▼                job de expiração:
                       WalletEvent = confirmed    quant += 1,
                                                   WalletEvent = expired
```

## Vantagens

- Separa três problemas que antes ficavam misturados: corretude da invariante (CAS), controle de volume/admissão sob pico (fila), e retenção de estoque por carrinhos abandonados (expiração).
- `max_time_in_line` resolve estruturalmente o "efeito colateral mais citado" já identificado em [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) (reservas abandonadas prendendo estoque) — mas só ativa o custo operacional dele quando a demanda realmente justifica (alta carga).
- Snapshot em `WalletEvent.expires_at` evita que mudar a política global afete reservas já em andamento.

## Desvantagens / custo real

- Precisa de um job de expiração rodando continuamente e de forma confiável (se parar, reservas pendentes acumulam sem devolver estoque).
- Mais um estado (`pending`) e uma transição (`pending → confirmed`/`expired`) no ciclo de vida de `WalletEvent`, que antes era só "existe ou não existe".
- Sob RPM ≥ X, a experiência de "colocar no carrinho" deixa de ser instantânea (passa pela fila) — precisa de UI própria pra isso (aguardando confirmação de reserva).

## Alternativas consideradas

- **Redis (Streams/List) em vez de RabbitMQ**: mais leve, sem infra nova. Descartado porque RabbitMQ dá ack/nack, retry, dead-letter e TTL de mensagem nativos.
- **Tópico Kafka particionado em vez de RabbitMQ** (reavaliação de 2026-09-02): agora que o backbone Kafka/Redpanda já está no stack para os eventos de domínio, usar um tópico particionado por evento para a admissão evita o segundo broker. Contra: Kafka não tem TTL de mensagem nem dead-letter nativos como o RabbitMQ; a ordem/consumo por partição serve, mas o descarte de requisição antiga vira lógica de aplicação. **Decisão adiada para a implementação do `admission`** (issue #42).
- **Expiração sempre ativa (não só em alta carga)**: mais simples de implementar (uma regra só), mas descartada por ora — sob baixa demanda não há motivo pra apressar o usuário a fechar a compra.
- **Sem fila real, só `429 Too Many Requests` + `Retry-After`**: mais simples, mas joga o controle de quando tentar de novo pro cliente, sem noção de posição/ordem.

## Relacionado
- Event Management System
- [Backend-Architecture](Backend-Architecture.md)
- [Esta camada = serviço `admission`](Architecture-Decision-Microservices-Split.md)
- [Fase 9 — issues #41–#43](../../BACKLOG.md)
- [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md)
- [Database-Schema](Database-Schema.md)
