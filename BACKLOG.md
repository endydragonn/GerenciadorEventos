# Backlog — Decomposição do Sistema em Issues Iterativas

Gerado a partir da documentação do vault (`D:\Claude\Brain\01-Projects\Event-Management-System\`).
Cada issue é uma fronteira de iteração: **após o merge, `docker compose up` + a suíte de
testes continuam verdes e o contrato servido ao Flutter não muda.** Numa extração de serviço,
"verde" = o `gateway` ainda serve o mesmo contrato externo, não só que a suíte compila.

ADRs de referência (a vendorizar em `docs/adr/` — issue #2):
`Backend-Architecture`, `Architecture-Decision-Stock-Isolation`,
`Architecture-Decision-Async-Checkout-Queue`, `Architecture-Decision-Supabase-Self-Host`,
`Architecture-Decision-Microservices-Split`, `Implementacao-EventCap-e-Reserva-CAS`,
`Database-Schema`, `References`.

## Decisões de arquitetura em vigor

- Back-end decomposto em microsserviços, Opção B (`gateway`, `identity`, `catalog`,
  `reservation`, `notification`, `admission`).
- `reservation` = Capacity + Wallet no mesmo serviço (híbrido CAS + ES); split com saga é
  rodada futura (#46).
- **CQRS + Event Sourcing no escopo.** ES em `catalog` e no lado wallet de `reservation`;
  `eventcap`/CAS fica relacional; `identity` CRUD; `notification` consumidor. Backbone
  **Kafka-compatível** (Kafka ou Redpanda); RabbitMQ para admissão; Redis para sinal de carga.
- **O experimento diferencial roda cedo e várias vezes** (de-risking — feedback do
  orientador). Braço de controle "checar disponibilidade via read model defasado → oversella"
  roda 1º contra o monólito (Fase 2, read model sintético), depois re-roda a cada marco:
  monólito+ES (Fase 6) e distribuído (Fase 7). A série de 3 pontos é a contribuição rica.
- **Geração de carga em máquina/VM separada** — números absolutos de latência/throughput são
  válidos, não só a contagem de corretude.

Prioridade: **back-end e banco primeiro; front-end é prioridade baixa** (só a issue #43).

Labels sugeridas: `fase/0`…`fase/11`, `area/backend`, `area/banco`, `area/infra`,
`area/frontend`, `area/eventos`, `area/carga`, `artigo`, `seguranca`, `bloqueado/docker`.

---

## Estado (2026-10-06)

Snapshot do GitHub (`gh issue list` / `gh pr list`). A definição de cada item continua sendo o
texto abaixo; esta seção só diz onde ele está. Item N = issue #(N+1).

| Item | Issue | Estado | PR |
|---|---|---|---|
| 1 | #2 | fechada | #52 |
| 2 | #3 | fechada | #53 |
| 3 | #4 | **aberta no GitHub, trabalho feito** — 2ª máquina (gerador) provisionada e latência base HTTP medida (`Projeto/carga/results/baseline-20261004-122632.txt`; ICMP não respondeu); falta fechar a issue | #76 (draft), kit em #90 |
| 4 | #5 | fechada | #55 |
| 5 | #6 | fechada | #65 |
| 6 | #7 | fechada | #66, #68 (fone obrigatório, #67) |
| 7 | #8 | fechada | #70 |
| 8 | #9 | fechada (roteiro em `docs/testes-manuais/`; `.sh` precisa de JWT pós-#79) | #73 |
| 9 | #10 | fechada | #75 |
| 10 | #11 | **aberta** — harness mergeado (`docs/carga/harness/`); Aceite refinado em 2026-10-06: o kit `Projeto/carga` vira o runner oficial (matriz, lag coletado do gerador, CSV + gráfico) | #82 |
| 11–14 | #12–#15 | abertas — rodadas preliminares (1 lag, 1–2 repetições) em `Projeto/carga/results/ANALISE.md`; Aceites refinados em 2026-10-06 | — |
| 15–46 | #16–#47 | abertas | — |

Branch de integração da Fase 2: `Fase_2` (PRs `Issue-11`…`Issue-15` entram nela; ela entra em
`main` por um PR único no fim da fase). Vocabulário do experimento em `CONTEXT.md`.

Fora do backlog (follow-ups): #56–#61 abertas (`updatePolicy`, IDOR em `/bff/event-wallets`,
endurecer schema, cobertura, transações do `updateEvent`, N+1/pool); #78 (BFF única + JWT
deny-by-default, PR #79), #83 (quality gates, PR #85), #86 (PR #88), #92 (`/actuator/health`,
PR #93) e #67 fechadas; #81, #84, #89 abertas. PR #90 adicionou os scripts de teste de carga
(disputa de vagas e taxa fixa/RPM) em `Projeto/carga/`.

Caminho crítico (issues): #2 → #5 → #7 → #10 feitas; próximo: #11 → #12 → #13.

## Fase 0 — Desbloqueio de ambiente

### 1. Instalar Docker Desktop e validar baseline verde do `main`
`fase/0` `area/infra` `bloqueado/docker`
- **Aceite:** `docker compose -f Projeto/docker-compose.yml up -d --build` sobe; `Projeto/Backend/run-tests.sh` roda e o resultado fica registrado. Sem mudança de código.
- **Depende de:** —

### 2. Vendorizar as ADRs do vault em `docs/adr/`
`fase/0` `area/infra`
- **Aceite:** os 8 `.md` em `docs/adr/`, links como caminhos relativos, índice em `docs/adr/README.md`.
- **Depende de:** —

### 3. Preparar a máquina/VM separada para geração de carga
`fase/0` `area/carga` `area/infra`
- **Aceite:** máquina ou VM com acesso de rede ao host do sistema; ferramenta de carga instalada (k6/Gatling/JMeter); latência de rede base entre gerador e alvo medida e registrada (pra descontar depois); documentado em `docs/carga/setup.md`.
- **Depende de:** —

---

## Fase 1 — Verificar a implementação EventCap/CAS de 2026-09-01

> Código escrito e nunca compilado. Base verde aqui é pré-requisito de tudo.

### 4. Compilar o backend e passar na suíte EventCap/CAS
`fase/1` `area/backend`
- **Aceite:** `mvn compile` limpo; `EventTest`, `EventWalletTest`, `ReservationExpiryTest`, `SchemaValidationTest` verdes.
- **Depende de:** #1

### 5. Aplicar a migration idempotente em banco populado + `99-tests.sql`
`fase/1` `area/banco`
- **Aceite:** `migrations/20260901-eventcap-isolation.sql` roda via `psql` em banco com dados pré-existentes **sem** `down -v`, idempotente (2× sem erro); `99-tests.sql` passa.
- **Depende de:** #4

### 6. Revisar constraints do Postgres + validar `walletevent.status`
`fase/1` `area/banco` `area/backend`
- **Aceite:** `email` unique, `fone` obrigatório + unique, `event_name` unique confirmados. Fluxo `walletevent.status` validado — UPSERT que revive `expired`/`cancelled`, índice parcial `idx_walletevent_pending_expires`.
- **Depende de:** #5

### 7. Validar o job `ReservationExpiryService`
`fase/1` `area/backend`
- **Aceite:** reserva `pending` não confirmada → após `expires_at`, `@Scheduled` marca `expired` e devolve a vaga numa transação, sem pisar em confirmação concorrente. Teste manual + `ReservationExpiryTest` verde.
- **Depende de:** #4

### 8. Teste manual de overselling sob concorrência (sanity)
`fase/1` `area/backend`
- **Aceite:** `capacity = 1`, dois `POST /api/bff/event-wallets` quase simultâneos → um `pending`, o outro "Evento lotado". Roteiro em `docs/`.
- **Depende de:** #4, #5

---

## Fase 2 — Experimento vs. monólito  ·  ENTREGA PRINCIPAL

> Trava um resultado defensável para a banca antes de qualquer buildout de ES/microsserviços.

### 9. Read model defasado sintético
`fase/2` `area/backend` `area/carga`
- **Aceite:** tabela desnormalizada `event_availability` (event_id, available); refresh por job em timer com **lag configurável**; caminho de leitura "ingênuo" (checa `event_availability` e depois insere, sem CAS) atrás de **feature flag**. Caminho CAS atual permanece o default.
- **Depende de:** #4, #6

### 10. Harness de carga rodando da máquina/VM separada
`fase/2` `area/carga` `artigo`
- **Aceite:** o kit `Projeto/carga` é o runner oficial (o harness de `docs/carga/harness/` fica como sanity local). Alvo de matriz: para um lag fixo, N × repetições, evento novo por repetição, `PCT_CONFIRMA=100`, rajada em instante pseudo-aleatório. O gerador coleta `confirmed`/`pending` e o lag observado via `psql` remoto (`:5433`). CSV com uma linha por execução (`braco, lag_ms, N, capacity, rep, confirmed, pending, oversell, p50, p95, p99, throughput, lag_obs_disparo, lag_obs_max, commit`) + gráfico SVG (overselling × lag, uma curva por braço, e painel de p95). Spec completa na issue #11.
- **Depende de:** #3, #9

### 11. Rodada 1a — no-overselling pelo caminho CAS
`fase/2` `area/carga` `artigo`
- **Aceite:** preset do runner: `readpath=cas`, N ∈ {100, 200}, `capacity` 20, 5 repetições. Overselling = `confirmed > capacity` (primária) e `pending + confirmed > capacity` (secundária), tolerância 0. Execução no gerador pelo usuário; CSV versionado vira a curva "design proposto".
- **Depende de:** #10

### 12. Rodada 1b — braço de controle: caminho ingênuo oversella
`fase/2` `area/carga` `artigo`
- **O diferencial do trabalho.** "O design ingênuo oversella, o nosso não — eis os números."
- **Aceite:** preset do runner: `readpath=naive`, lags ∈ {500, 2000, 5000, 10000} ms × N ∈ {100, 200} × 5 repetições (40 execuções). Gráfico de duas curvas (CAS e ingênuo, overselling × lag, média ± desvio). Execução no gerador pelo usuário; o resultado é reportado como medido.
- **Depende de:** #11

### 13. Throughput/latência preliminares do monólito + limiar X preliminar
`fase/2` `area/carga` `artigo`
- **Aceite:** carga em degraus a partir de 12k RPM até degradar ou até o limite do gerador; X = primeiro degrau com 5xx > 1% ou p95 > 1 s por 30 s (se o gerador saturar antes, X é limite inferior). Curva latência/throughput × carga. Ressalva: números de "monólito + HikariCP" — os números pré-pool (#96) estão invalidados.
- **Depende de:** #11

### 14. Consolidar o resultado da Fase 2 em rascunho de seção do artigo
`fase/2` `artigo`
- **Aceite:** `docs/artigo/experimento-monolito.md` com hipótese, setup (2 PCs, 1 Gbps), workload, métricas (`CONTEXT.md`), resultados (duas curvas + X), ameaças à validade e limitações. Números "monólito + HikariCP", não o ponto 1 definitivo da série. Revisado com o orientador.
- **Depende de:** #12, #13

---

## Fase 3 — Higiene de segredos

### 15. Tirar `Projeto/.env` do git, rotacionar segredos, padronizar `.env` por serviço
`fase/3` `seguranca`
- **Aceite:** `git rm --cached Projeto/.env`; `.gitignore` commitado; segredos rotacionados (senha Postgres, `JWT_SECRET`, senha de app do Gmail, chaves reCAPTCHA); `.env.example` versionado. Reescrita de histórico opcional; **rotacionar é obrigatório**.
- **Depende de:** —

---

## Fase 4 — Camada de banco: Supabase self-hosted enxuto

> Não bloqueia o experimento — destrava os números absolutos comparáveis.
> Ver `Architecture-Decision-Supabase-Self-Host`.

### 16. Vendorizar `supabase/docker/` e gerar segredos
`fase/4` `area/infra` `bloqueado/docker`
- **Aceite:** `supabase/docker/` vendorizada ou via script de setup; segredos com `generate-keys.sh` no `.env` (não commitado).
- **Depende de:** #15

### 17. Substituir o serviço `db` por `supabase-db`
`fase/4` `area/infra` `area/banco`
- **Aceite:** compose usa `supabase/postgres` (Dockerfile atual como fallback no histórico); init-scripts em `/docker-entrypoint-initdb.d/migrations/` com prefixo alto; decisão `meu_banco` vs `postgres` registrada. Testes da Fase 1 verdes.
- **Depende de:** #16

### 18. Adicionar `supabase-pooler` (Supavisor) e apontar o backend
`fase/4` `area/infra` `area/backend`
- **Aceite:** Supavisor no compose; `SPRING_DATASOURCE_URL` → pooler (`:6543` + `?prepareThreshold=0`, ou `:5432`); auditado que não há estado de sessão (`SET`/advisory lock); fluxo de reserva funciona pelo pooler.
- **Depende de:** #17

### 19. Adicionar `supabase-studio` + cadeia mínima
`fase/4` `area/infra` `artigo`
- **Aceite:** Studio sobe (com `kong`, `meta` e o mínimo); inspeção visual de `eventcap`/`walletevent`. GoTrue/PostgREST/Realtime/Storage/imgproxy/edge functions **não** sobem.
- **Depende de:** #17

### 20. Migrar o harness de teste para schema-per-service
`fase/4` `area/backend` `area/banco`
- **Aceite:** `GlobalDbTruncator` e `SchemaValidationTest` recebem a lista de schemas alvo por config; suíte verde com um schema, preparada para vários.
- **Depende de:** #17

### 21. Re-rodar o experimento pós-pooler — baseline oficial "monólito CRUD"
`fase/4` `area/carga` `artigo`
- **Aceite:** rodadas 1a/1b re-executadas com o pooler; latência/throughput agora comparáveis; resultado vira o ponto 1 da série de 3. `docs/artigo/experimento-monolito.md` atualizado.
- **Depende de:** #18, #12

---

## Fase 5 — Preparar a decomposição (dentro do monólito)

### 22. Refatorar o backend em pacotes-por-serviço
`fase/5` `area/backend`
- **Aceite:** `identity/`, `catalog/`, `reservation/`, `notification/` (+ `shared/`); nenhuma chamada cruzada fora de interface; teste de arquitetura (ArchUnit); suíte verde; contrato externo inalterado.
- **Depende de:** #4

### 23. Introduzir idempotency key no fluxo de reserva
`fase/5` `area/backend`
- **Aceite:** `POST /event-wallets` aceita/gera `Idempotency-Key`; repetição não decrementa `eventcap` duas vezes; fronteira interna "decrementar capacidade" vs "gravar reserva" atrás de interface. Invariante e ponto de compensação documentados.
- **Depende de:** #6, #22

### 24. Formalizar o BFF atual como `gateway`
`fase/5` `area/backend` `area/infra`
- **Aceite:** BFF vira serviço `gateway`; contrato consumido pelo Flutter idêntico (teste de contrato); demais módulos não expostos direto.
- **Depende de:** #22

---

## Fase 6 — CQRS + Event Sourcing (nos módulos do monólito)

### 25. ADR — backbone de eventos + event store + contrato do evento de domínio
`fase/6` `area/eventos` `area/backend`
- **Aceite:** ADR decidindo Kafka vs Redpanda; event store = Kafka como log de verdade + projeções em Postgres **vs** tabela de streams no Postgres + publish; formato do evento (JSON/Avro + schema registry?) e versionamento; papéis: Kafka = log/fanout, RabbitMQ = admissão, Redis = sinal de carga.
- **Depende de:** #22

### 26. Subir o backbone Kafka-compatível + infra de event store
`fase/6` `area/eventos` `area/infra` `area/banco`
- **Aceite:** broker no compose; módulo `shared` de append/read por stream com **concorrência otimista** (id do agregado + versão); publish após append; catch-up de projeção (poller ou `pg_cron`); projeções **idempotentes**; tabela de snapshot + política. Teste de replay: append N eventos, reconstruir projeção do zero → idêntico.
- **Depende de:** #25

### 27. Converter o módulo `catalog` para CQRS + ES
`fase/6` `area/backend` `area/eventos` `area/banco`
- **Aceite:** eventos `EventCreated`/`EventUpdated`/`PresenterAdded`/`PresenterRemoved`/`EventCancelled`; command side appenda; read model em tabelas de query; contrato do `gateway` inalterado; suíte verde; teste de replay do agregado `Event`.
- **Depende de:** #26, #20

### 28. Converter o lado wallet/order do módulo `reservation` para ES (híbrido)
`fase/6` `area/backend` `area/eventos` `area/banco`
- **Aceite:** stream `SlotReserved`/`ReservationConfirmed`/`ReservationExpired`/`ReservationCancelled`; `eventcap` + CAS permanecem relacionais; ponte checkout → CAS síncrono → append `SlotReserved`; job de expiração emite `ReservationExpired`; read model "minhas reservas" projetado; suíte + overselling (#8) verdes; teste de replay.
- **Depende de:** #26, #20, #23

### 29. Checkpoint — monólito ES-native verde ponta-a-ponta
`fase/6` `area/backend`
- **Aceite:** `catalog` e `reservation` event-sourced; contrato externo inalterado; replay de ambos funcionando; suíte completa verde; diagrama command/evento/projeção em `docs/`.
- **Depende de:** #27, #28

### 30. Rodada 2 do experimento — contra a projeção ES real
`fase/6` `area/carga` `artigo`
- **Aceite:** braço de controle re-executado, agora lendo a **projeção ES real** (lag de consumer de verdade, não timer sintético); mesmo N concorrentes; comparado com a rodada 1 (Fase 4). Ponto 2 da série. `docs/artigo/` atualizado.
- **Depende de:** #29, #21

---

## Fase 7 — Extração incremental dos serviços (strangler, um por vez)

### 31. Extrair `notification` como serviço
`fase/7` `area/backend` `area/infra`
- **Aceite:** consumidor do backbone, envia e-mail; monólito para de enviar direto; queda do `notification` não afeta cadastro/login.
- **Depende de:** #24, #26, #29

### 32. Extrair `reservation` (híbrido CAS + ES) atrás do `gateway`
`fase/7` `area/backend` `area/banco` `area/eventos`
- **Aceite:** `reservation` com schema + streams próprios; API interna síncrona chamada pelo `gateway`; job de expiração dentro dele; `eventcap`/`walletevent`/streams saem do monólito; suíte + overselling + replay verdes.
- **Depende de:** #31

### 33. Rodada 3 do experimento — sistema distribuído
`fase/7` `area/carga` `artigo`
- **Aceite:** braço de controle com `reservation` como serviço (a checagem de disponibilidade cruza a rede); mesmo N concorrentes; **comparação monólito ES vs distribuído**. Ponto 3 da série — completa o gráfico de 3 pontos. `docs/artigo/` atualizado.
- **Depende de:** #32
- **Nota:** a partir daqui os itens 34–36 podem rodar em paralelo com a Fase 8 se houver tempo.

### 34. Extrair `catalog` (ES) como serviço
`fase/7` `area/backend` `area/banco` `area/eventos`
- **Aceite:** schema + streams próprios; `event.creator_id` vira UUID sem FK cross-service; `creator_name` por projeção consumindo eventos do `identity` (ou composição no `gateway` — decisão da ADR); host auth valida JWT localmente.
- **Depende de:** #32

### 35. Extrair `identity` (Auth, MFA, reCAPTCHA, verificação de e-mail)
`fase/7` `area/backend` `area/banco` `seguranca`
- **Aceite:** schema próprio (CRUD, não ES); emite eventos de ciclo de vida; emite JWT; demais serviços validam JWT stateless (segredo compartilhado ou JWKS — decisão registrada); login/cadastro/MFA/troca de senha verdes ponta-a-ponta.
- **Depende de:** #34

### 36. `docker-compose.yml` (sem `admission`) + suíte de integração ponta-a-ponta
`fase/7` `area/infra` `area/backend`
- **Aceite:** compose com `gateway`, `identity`, `catalog`, `reservation`, `notification`, `supabase-*`, `kafka`/`redpanda`; `depends_on` + healthchecks; suíte de integração: cadastro → criar evento → reservar → confirmar → e-mail, com replay verificado.
- **Depende de:** #35

---

## Fase 8 — Teste de carga completo e limiar

### 37. Harness definitivo contra o sistema distribuído
`fase/8` `area/carga` `artigo`
- **Aceite:** cenários de escrita (reserva) e leitura (catálogo via projeção); coleta throughput sustentado, `pg_stat_statements`, lag write→projeção, lag de consumer, métricas do pooler; disparado da máquina/VM separada.
- **Depende de:** #36, #33

### 38. Relatório comparativo das 3 configurações
`fase/8` `area/carga` `artigo`
- **Aceite:** tabela + gráficos comparando monólito CRUD / monólito ES / distribuído ES em: no-overselling (corretude), throughput, latência p50/p95/p99, lag. É o resultado consolidado do TCC.
- **Depende de:** #37

### 39. Definir o limiar X definitivo (RPM por evento)
`fase/8` `area/carga` `artigo`
- **Aceite:** valor de X onde o fluxo síncrono degrada, com a justificativa dos números, documentado em `Architecture-Decision-Async-Checkout-Queue`.
- **Depende de:** #37

---

## Fase 9 — Serviço de admissão / alta carga

### 40. `admission`: contador RPM por evento em Redis
`fase/9` `area/backend` `area/infra`
- **Aceite:** `admission` no compose; janela deslizante por bucket (`INCR` + `EXPIRE`); expõe "RPM atual do evento X"; só sinal de carga.
- **Depende de:** #36, #39

### 41. `admission`: fila de admissão + worker pool
`fase/9` `area/backend` `area/infra`
- **Aceite:** fila por evento — RabbitMQ (`x-message-ttl` + dead-letter) ou tópico Kafka particionado, conforme ADR #25; worker pool com concorrência controlada.
- **Depende de:** #40

### 42. Integração do modo de alta carga
`fase/9` `area/backend`
- **Aceite:** `RPM ≥ X` → `admission` enfileira → worker chama a API interna de `reservation` (CAS) → `walletevent` `pending` + `expires_at = now + max_time_in_line` (snapshot); `RPM < X` → síncrono direto. Testes dos dois regimes.
- **Depende de:** #41

### 43. UI de reserva `pending` no Flutter
`fase/9` `area/frontend`
- **Prioridade baixa.** Só depois de #42.
- **Aceite:** contagem regressiva até `expires_at` + botão "Confirmar" separado; tela "aguardando confirmação" quando passa pela fila; trata "Evento lotado" e `quantity == -1`.
- **Depende de:** #42

---

## Fase 10 — Entregáveis do artigo / fechamento

### 44. Fechar o material do artigo
`fase/10` `artigo`
- **Aceite:** coluna "Utilizada no Artigo" de `References.md` preenchida; prints do Studio; diagramas finais (monólito → microsserviços; síncrono vs fila; CQRS/ES command/evento/projeção; backbone Kafka); a série de 3 rodadas do experimento consolidada.
- **Depende de:** #38, #33

### 45. Atualizar os `README.md`
`fase/10` `area/infra`
- **Aceite:** README da raiz, de `Backend/`, `Backend/db/` e um por serviço, com a arquitetura final e comandos.
- **Depende de:** #36

---

## Fase 11 — Rodada futura (fora do escopo imediato)

### 46. Separar `reservation` em `stock` + `wallet` com saga
`fase/11` `area/backend`
- Condicionado a análise de trade-off (`Architecture-Decision-Microservices-Split`). Segue `Backend-Architecture` §3: checkout → `stock` (síncrono) → grava `wallet` depois; compensação idempotente + reaper de decrementos órfãos.
- **Depende de:** #32

---

## Espinha de dependências (resumo)

```
F0:  1 → 4 …          2 (solto)          3 → 10
F1:  4 → 5 → 6        4 → 7              4 + 5 → 8
F2:  4 + 6 → 9        3 + 9 → 10 → 11 → 12       11 → 13       12 + 13 → 14   ◄ ENTREGA PRINCIPAL
F3:  15 (solto)
F4:  15 → 16 → 17 → 18            17 → 19, 20        18 + 12 → 21
F5:  4 → 22 → 24                  6 + 22 → 23
F6:  22 → 25 → 26 → 27, 28 → 29   (20 trava 27/28; 23 trava 28)     29 + 21 → 30
F7:  24 + 26 + 29 → 31 → 32 → 33          32 → 34 → 35 → 36
F8:  36 + 33 → 37 → 38            37 → 39
F9:  36 + 39 → 40 → 41 → 42 → 43
F10: 38 + 33 → 44                 36 → 45
F11: 32 → 46
```

## Caminho crítico até o resultado de banca

`1 → 4 → 6 → 9 → 10 → 11 → 12` (7 issues). O resto da Fase 2 (13, 14) consolida.
Tudo de Supabase/ES/microsserviços vem **depois** desse marco.
