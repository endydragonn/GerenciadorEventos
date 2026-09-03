---
project: Event-Management-System
subproject:
type: note
tags: [references, architecture, marketplace]
created: 2026-08-24
---

> **Vendorizado de:** `D:\Claude\Brain\01-Projects\Event-Management-System\References.md`
> **Sincronizado em:** 2026-09-03 · sincronização manual (ver `README.md`)

# Referências — Estoque Isolado / Marketplace

Fontes usadas para ancorar as escolhas descritas em [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md). Cada uma corresponde a uma afirmação específica do documento (vantagem, desvantagem, ou justificativa de decisão).

> A tabela abaixo cobre a decisão de **isolamento de estoque**. As decisões novas de
> 2026-09-02 (microsserviços, CQRS/ES, backbone Kafka, teste de carga) ainda **precisam de
> fontes** — ver "Referências pendentes" no fim. Coluna "Utilizada no Artigo" a preencher na
> issue #44 ([Roadmap e Issues](../../BACKLOG.md)).

| Referencial                                                                                                                                                                                                                                           | Tema                                                                                                                       | Parte do projeto que usa                                                                             | Utilizada no Artigo |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- | ------------------- |
| [How I Eliminated Inventory Race Conditions in a Production E-Commerce System](https://medium.com/@chaturvediinitin/how-i-eliminated-inventory-race-conditions-in-a-production-e-commerce-system-2302ba81846b) — Medium, Nitin Chaturvedi             | Race condition clássica de ler-e-depois-escrever em estoque, e por que gera overselling                                    | [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) — seção "Estrutura escolhida"                              |                     |
| [Inventory Reservation Patterns: How to Stop Overselling](https://stoalogistics.com/blog/inventory-reservation-patterns) — Stoa Logistics                                                                                                             | Padrões de reserva atômica (transação com `SELECT FOR UPDATE`, reserva com expiração)                                      | [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) — seção "Estrutura escolhida"                              |                     |
| [Replacing Redis with MySQL: Scaling Inventory Reservations with SKIP LOCKED](https://dasroot.net/posts/2026/06/replacing-redis-mysql-scaling-inventory-reservations-skip-locked/)                                                                    | Escalar reservas de estoque com `SKIP LOCKED`, uma linha por unidade em vez de uma linha por item                          | [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) — seção "Quando isso deixa de valer a pena"                |                     |
| [We replaced Redis with MySQL for inventory reservations—and it scaled](https://shopify.engineering/scaling-inventory-reservations) — Shopify Engineering                                                                                             | Redis como cache (não fonte de verdade) e o gap de consistência entre camada de reserva e banco relacional                 | [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) — seção "Vantagens"                                        |                     |
| [Designing an E-Commerce System like Amazon That Actually Works](https://medium.com/@k.khusi431/designing-an-e-commerce-system-like-amazon-that-actually-works-7f72143fcbe2) — Medium                                                                 | Máquina de estados de reserva (ACTIVE → CONSUMED/EXPIRED) via `UPDATE` condicional, regra do "primeiro escritor vence"     | [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) — seção "Por que escolhemos seguir assim"                  |                     |
| [Polyglot Persistence: Database Per Service Pattern](https://www.dataexpert.io/blog/polyglot-persistence-database-per-service-pattern) — dataexpert.io                                                                                                | Cada serviço escolhe o motor de banco certo pro seu padrão de acesso (nenhum banco é bom em tudo)                          | [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) — seção "Vantagens"                                        |                     |
| [Polyglot Persistence in Microservices: Managing Data Diversity in Distributed Systems](https://www.researchgate.net/publication/395403249_Polyglot_Persistence_in_Microservices_Managing_Data_Diversity_in_Distributed_Systems) — arXiv/ResearchGate | Trade-offs de polyglot persistence: ganho de adaptabilidade/performance vs. custo de governança e complexidade operacional | [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) — seção "Desvantagens / custo real"                        |                     |
| [Polyglot Persistence in Microservices: Let the Domain Choose the Database](https://dev.to/dancodingbr/polyglot-persistence-in-microservices-choosing-the-right-database-for-each-service-1m8g) — DEV Community                                       | Ausência de join nativo entre serviços com bancos diferentes; consistência eventual entre eles                             | [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) — seção "Desvantagens / custo real"                        |                     |
| [CQRS and Event Sourcing: A Comprehensive Guide to Architecture, Practices, and Resources](https://www.javathinking.com/blog/cqrs-and-event-sourcing-resources/) — javathinking.com                                                                   | Bounded contexts em CQRS/ES: separar contexto de Pedido do contexto de Estoque dentro do mesmo domínio                     | [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) — seção "Por que escolhemos seguir assim"                  |                     |
| [Design Inventory Management System: Step-by-Step Guide 2026](https://www.systemdesignhandbook.com/guides/design-inventory-management-system/) — System Design Handbook                                                                               | Visão geral de arquitetura de sistema de inventário, uso de transações ACID no serviço que detém a fonte de verdade        | [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) — seção "Estrutura escolhida" / contexto geral do problema |                     |

## Referências pendentes — temas de 2026-09-02 (sem fonte ainda)

Cada item ancora uma afirmação nas ADRs novas; achar 1–2 fontes por tema antes do artigo.

| Tema | Onde é usado | Afirmação a ancorar |
|---|---|---|
| **Strangler pattern** (Fowler) | [Architecture-Decision-Microservices-Split](Architecture-Decision-Microservices-Split.md) — "Estratégia de migração" | migrar monólito → microsserviços um serviço por vez sem big-bang |
| **Event Sourcing / event store design** (append-only, concorrência otimista por versão, snapshots) | [Architecture-Decision-Microservices-Split](Architecture-Decision-Microservices-Split.md) — "Escopo do ES" | como o command side e as projeções funcionam; replay |
| **Kafka como event store vs. broker** / **Kafka vs. Redpanda** | [Architecture-Decision-Microservices-Split](Architecture-Decision-Microservices-Split.md) — "Discriminador resolvido" | log de verdade + fanout; throughput/elasticidade do backbone |
| **CQRS read model lag e janela de inconsistência** | [Backend-Architecture](Backend-Architecture.md); braço de controle do experimento (issue #29) | por que checar disponibilidade via projeção defasada gera overselling |
| **Saga pattern / compensação** | [Architecture-Decision-Microservices-Split](Architecture-Decision-Microservices-Split.md) — split `stock`+`wallet` (#47) | reserva → wallet → liberação idempotente + reaper de órfãos |
| **Connection pooling** (Supavisor / transaction vs session mode) | [Architecture-Decision-Supabase-Self-Host](Architecture-Decision-Supabase-Self-Host.md) | conexão-por-request satura `max_connections`; pooler multiplexa |
| **Metodologia de teste de carga** (gerador co-localizado contamina latência/throughput; carga em máquina separada) | [Architecture-Decision-Microservices-Split](Architecture-Decision-Microservices-Split.md) — "De-risking"; issues #3/#27 | validade dos números absolutos vs. contagem de corretude |
| **Database-per-service / bounded context** | [Architecture-Decision-Microservices-Split](Architecture-Decision-Microservices-Split.md) — Opção B | schema por serviço; `creator_id` sem FK cross-service |

## Relacionado
- Event Management System
- [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md)
- [Architecture-Decision-Microservices-Split](Architecture-Decision-Microservices-Split.md)
- [Roadmap e Issues](../../BACKLOG.md)
