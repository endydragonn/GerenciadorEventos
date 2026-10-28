# ADRs de Arquitetura — cópia versionada do vault

Esta pasta é uma **cópia versionada** das ADRs (Architecture Decision Records) e notas de
arquitetura do TCC (Gerenciador de Eventos). A **fonte de verdade continua no vault Obsidian**
em `D:\Claude\Brain\01-Projects\Event-Management-System\`; o que está aqui é um espelho para
que quem clonar o repositório não precise do vault. A sincronização é **manual** por ora —
cada arquivo traz, logo após o frontmatter, um bloco de proveniência dizendo de onde veio e
quando foi sincronizado. Os wikilinks do Obsidian foram reescritos como links markdown
relativos; o frontmatter YAML original foi mantido de propósito (é proveniência).

## Como re-sincronizar

- Fonte: `D:\Claude\Brain\01-Projects\Event-Management-System\` (vault Obsidian).
- Processo: cópia **manual** dos 8 `.md` listados abaixo, reescrevendo os wikilinks para
  links relativos e atualizando o bloco de proveniência (data de sincronização).
- Quando: sempre que as ADRs correspondentes mudarem no vault.
- ADRs novas a partir da Fase 6 (ex.: a do backbone de eventos) nascem **aqui**, sem bloco de
  proveniência; esta pasta é a versão de referência delas.

## Arquivos

| Arquivo | Do que trata |
|---|---|
| [Backend-Architecture](Backend-Architecture.md) | Princípio central: isolar o agregado de estoque/capacidade (consistência forte via `UPDATE` condicional / CAS) do resto do domínio, que fica event-sourced (CQRS+ES). |
| [Architecture-Decision-Stock-Isolation](Architecture-Decision-Stock-Isolation.md) | ADR que generaliza o isolamento de estoque/capacidade para qualquer marketplace com item de quantidade limitada — vantagens, custos, alternativas e quando reavaliar. |
| [Architecture-Decision-Async-Checkout-Queue](Architecture-Decision-Async-Checkout-Queue.md) | ADR da camada de alta carga: sinal de RPM por evento em Redis, fila RabbitMQ para admissão, reserva com expiração (`max_time_in_line`) e job de expiração. |
| [Architecture-Decision-Supabase-Self-Host](Architecture-Decision-Supabase-Self-Host.md) | ADR para substituir o contêiner `db` por um Supabase self-hosted enxuto (Postgres + pooler Supavisor + Studio), viabilizando o teste de carga e múltiplos serviços. Decidido, não implementado. |
| [Architecture-Decision-Microservices-Split](Architecture-Decision-Microservices-Split.md) | ADR da decomposição do back-end em microsserviços (Opção B: `gateway`, `identity`, `catalog`, `reservation`, `notification`, `admission`), escopo de CQRS+ES e migração por strangler. Decidido, não implementado. |
| [Architecture-Decision-Event-Backbone-Event-Store](Architecture-Decision-Event-Backbone-Event-Store.md) | ADR da Fase 6 (issue #26): Redpanda como broker; event store = tabela de streams no Postgres com concorrência otimista + outbox → Redpanda; projeções inline (read-your-writes) e assíncronas (lag de consumer da Rodada 2); envelope JSON com `schemaVersion`. **Escrita direto no repo** (sem bloco de proveniência). Status: implementada (Fase 6: #27–#30); diagrama em [fase6-cqrs-es](../arquitetura/fase6-cqrs-es.md). |
| [Architecture-Decision-Autoescala](Architecture-Decision-Autoescala.md) | ADR da autoescala de `gateway` e `reservation` (#111, regra 50/80 com stand-by): opções (compose `--scale` + HAProxy + scaler no host, Traefik, Swarm, Kubernetes, réplicas fixas), parâmetros, aritmética sem oscilar e conta do Supavisor. **Escrita direto no repo.** Status: proposta, aguardando decisão. |
| [Implementacao-EventCap-e-Reserva-CAS](Implementacao-EventCap-e-Reserva-CAS.md) | Changelog da sessão de 2026-09-01: schema novo (`eventcap`, `eventpresenter`, `walletevent` com máquina de estados), reserva em dois passos e job `@Scheduled` de expiração. Código sem commit/teste. |
| [Database-Schema](Database-Schema.md) | Modelo de dados (DBML) do monólito, decisões e correções em relação à versão inicial, e o impacto de microsserviços + CQRS/ES no schema (schema por serviço, streams/projeções/snapshots). |
| [References](References.md) | Tabela de fontes que ancoram cada afirmação das ADRs (isolamento de estoque) e a lista de referências ainda pendentes para os temas de 2026-09-02. |
