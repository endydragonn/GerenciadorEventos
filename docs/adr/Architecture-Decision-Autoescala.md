---
project: Event-Management-System
subproject:
type: note
tags: [architecture, decision, autoscaling, haproxy, docker-compose]
created: 2026-10-28
---

# Decisão de Arquitetura — Autoescala de `gateway` e `reservation` (regra 50/80)

**Status:** **decidida (usuário, 2026-10-28): A agora, D depois na #197.** A #111 implementa a opção A (override com `docker
compose --scale` + HAProxy + scaler no host, com os parâmetros abaixo) e a #112 a estende. A opção D (Kubernetes com HPA/KEDA)
fica para a issue #197, fora do Batch 2.
Escrita direto no repositório (sem cópia no vault).

Complementa [Architecture-Decision-Async-Checkout-Queue](Architecture-Decision-Async-Checkout-Queue.md) (o gatilho 80/70 do
`admission`, #43) e [Architecture-Decision-Microservices-Split](Architecture-Decision-Microservices-Split.md). A #112 estende o
mesmo mecanismo a `admission`, `identity`, `catalog` e `notification`, sem mecanismo novo.

## Contexto

- A stack, o e2e (`Projeto/e2e/run-e2e.sh`), o `run-tests.sh` e o kit de carga são **Docker Compose**. O compose base tem
  `container_name` fixo em 8 serviços e o `backend` (gateway) publica a 8081 no host, então `--scale` não funciona nele.
- Dependem dos nomes fixos: o `run-tests.sh` (aborta se `gerenciador-backend`/`-reservation`/... estiverem no ar), o kit de carga
  (`docker exec gerenciador-reservation|gerenciador-backend` em `setup-host.ps1`, `rpm/subir-host-rpm.ps1`,
  `degraus/rodar-degraus.ps1`) e os smokes em `docs/testes-manuais/`.
- O gateway fala com o `reservation` (`RESERVATION_SERVICE_URL=http://reservation:8082`) por um cliente Apache com pool de
  400 conexões keep-alive; o worker pool do `admission` (#42) também. Sob carga as conexões não ficam ociosas, então **não
  saem das réplicas resolvidas no início**: uma réplica nova não recebe tráfego só por existir no DNS do Docker.
- O nginx do frontend tem `proxy_pass http://backend:8081` e resolve o nome **uma vez** no start.
- O `admission` calcula `capacidade = X × réplicas` **uma vez**, no construtor do `RpmCounter`, a partir de
  `ADMISSION_RESERVATION_REPLICAS` (fixo em 1).
- Concorrência entre réplicas já resolvida no código: o `OutboxRelay` usa `pg_try_advisory_xact_lock` (um relay por vez), a
  expiração usa `FOR UPDATE SKIP LOCKED` em lotes (#61), o JWT é validado sem estado (claim `uid`, JWKS só como cache), a
  `Idempotency-Key` mora no banco do `reservation`, o cache de `exists` do gateway é só cache. Os consumers do `reservation`
  (projeção de disponibilidade e reconciliação) estão em consumer group com 1 partição e checkpoint atômico: com N réplicas, só
  uma consome por vez e as outras ficam ociosas no group. Falta o **teste** com 2+ execuções concorrentes (aceite da #111).
- O JVM leva de alguns segundos a ~20 s para ficar saudável (o `start_period` dos healthchecks é a tolerância). Por isso a regra tem a réplica em **stand-by**: ela sobe a 50% para
  estar pronta quando o uso chega a 80%.
- X = 56.000 RPM por evento (#40) foi medido com **uma** réplica, e o gargalo é a **linha** do `eventcap` do evento. Todas as
  réplicas disputam a mesma linha, então **é provável que X não cresça com réplicas**. O CAS mantém o overselling em 0 com
  qualquer N; o risco é `X × ativas` superestimar a capacidade. Isso é hipótese para a Rodada 4 medir, não fato.
- Supavisor: `POOLER_MAX_CLIENT_CONN = 1500`, soma atual dos Hikari = 1000 (reservation) + 10 (gateway) + 3 (notification) +
  100 (catalog) + 100 (identity) = **1213**. O pool real ao Postgres é 45 e não cresce com réplicas.

## A decisão a tomar

Qual mecanismo sobe, coloca em stand-by, promove e derruba réplicas de `gateway` e `reservation`.

## Opções

### A. `docker compose --scale` + HAProxy + scaler no host, num override (recomendada)

- **Override** `Projeto/docker-compose.autoescala.yml` (padrão do `e2e/docker-compose.e2e.yml`): `container_name: !reset null`
  e `ports: !reset []` em `backend` e `reservation`. O compose base **não muda**, então `run-tests.sh`, o kit de carga, os smokes e
  o braço "sem autoescala" da Rodada 4 continuam idênticos à Rodada R.
- **HAProxy** (serviço `lb`, imagem com tag fixa) no override, com duas seções:
  - entrada: publica a **8081** no host e balanceia as réplicas do `backend` (o contrato do Flutter, porta e path, não muda);
  - interna: `reservation-lb:8082` só na rede `gerenciador-net`; `RESERVATION_SERVICE_URL` do `backend` **e** do `admission`
    apontam para ela (o worker pool entrega "já com a réplica nova").
  - Descoberta por `server-template <nome> 1-<máx> <serviço>:<porta> resolvers docker init-addr none` (DNS do Docker,
    `127.0.0.11`): as réplicas entram e saem sem reiniciar o HAProxy. Health check HTTP ou TCP por réplica.
  - **Stand-by = slot com `weight 0`** (recebe health check, não recebe requisição) pela Runtime API do HAProxy (socket só dentro
    do container, acionado por `docker exec`). Promover = `set weight ... 100`. Escala para baixo = `set server ... state drain`,
    esperar as sessões zerarem, depois parar o container.
  - O nginx do frontend passa a apontar para o `lb` no override (template de env do nginx, default `backend:8081`, que mantém o
    compose base igual).
- **Scaler** = script bash no host (`Projeto/autoescala/scaler.sh`, roda no Git Bash e no Linux), laço de 2 s:
  - lê o uso do `reservation` no Redis (o `admission` publica o RPM do evento mais quente, ver "Aritmética") e o uso do gateway nas
    estatísticas do HAProxy (req/s por réplica ativa);
  - age com `docker compose -p <project> -f docker-compose.yml -f docker-compose.autoescala.yml up -d --no-recreate --scale
    <serviço>=N` e com a Runtime API;
  - escreve o número de réplicas **ativas** do `reservation` numa chave do Redis com TTL (renovada a cada laço), que o script Lua
    do `admission` lê; sem a chave (scaler parado), vale `ADMISSION_RESERVATION_REPLICAS` (1), o lado seguro: entra em fila mais
    cedo;
  - grava `réplicas × tempo` em CSV (`ts, serviço, ativas, standby, uso, ação`).
  - Fica no host para **não montar o `docker.sock`** num container (equivale a root no host).
- **Prós:** mínimo de peças novas (1 container + 1 script), nada muda fora do override, a stand-by é controle de primeira classe,
  a promoção leva < 1 s, e o e2e e o kit seguem em compose.
- **Contras:** um salto a mais no caminho quente (gateway → `lb` → reservation, ~0,1–0,5 ms na bridge do Docker) e o HAProxy como
  ponto único (o mesmo vale para a 8081 hoje); o scaler é código nosso (laço, histerese, drain) e precisa de teste; o scaler parado
  congela as réplicas (o `admission` cai no lado seguro).
- **Para medir o custo do salto:** a Rodada 4 pode rodar o braço C com o scaler desligado e 1 réplica (C0) antes do C.

### B. Traefik com o provider Docker

- Descobre as réplicas pelos labels e balanceia sozinho.
- **Contras:** precisa do `docker.sock` montado no Traefik; o peso por réplica vem de label, que só muda recriando o container,
  então a stand-by sem tráfego fica artificial (ou exige o provider de arquivo, que é o HAProxy da opção A com outra sintaxe). O
  scaler continua sendo nosso.

### C. Docker Swarm (`docker stack deploy`, `docker service scale`)

- VIP com balanceamento nativo (IPVS) e `deploy.replicas`.
- **Contras:** o Swarm ignora `build`, `container_name` e `depends_on: condition`; o e2e, o `run-tests.sh` e o kit de carga teriam
  de ser reescritos (`docker stack`, imagens pré-construídas); o VIP manda tráfego a **todas** as tasks, então a stand-by exige um
  LB na frente do mesmo jeito; `docker swarm init` muda o estado do daemon do host de medição. Não há autoscaler nativo: o scaler
  continua sendo nosso.

### D. Kubernetes (kind/k3d) + HPA ou KEDA

- O autoscaler é o canônico, com métricas customizadas (adapter do Prometheus ou KEDA lendo o Redis/RabbitMQ).
- **Contras:** troca a stack inteira (manifests no lugar do compose, e2e e kit reescritos), o que o `CLAUDE.md` proíbe sem
  alinhamento; o cluster disputa CPU e RAM com o que está sendo medido no host; o HPA não tem stand-by (sobe direto com tráfego,
  sync de 15 s + boot); fora do prazo do Batch 2.

### E. N réplicas pré-provisionadas, só o LB liga e desliga

- Variante degenerada da A: todas as réplicas já sobem no `up`; o scaler só mexe no peso do HAProxy.
- **Prós:** sem tempo de subida, sem `--scale` em execução.
- **Contras:** não é autoescala (as réplicas consomem RAM e conexões do Supavisor o tempo todo) e não mede o tempo de subida, que é
  parte do aceite.

## Comparação

| | A (recomendada) | B Traefik | C Swarm | D k8s | E fixas |
|---|---|---|---|---|---|
| Compose base, e2e e kit intactos | sim (override) | sim (override) | não | não | sim (override) |
| Stand-by sem tráfego | sim (`weight 0`) | artificial | precisa de LB | não | sim |
| `docker.sock` em container | não | sim | não | n/a | não |
| Peças novas | `lb` + script | Traefik + script | stack + LB + script | cluster + adapter | `lb` + script |
| Mede tempo de subida | sim | sim | sim | sim | não |
| Prazo do Batch 2 | cabe | cabe | apertado | não cabe | cabe |

## Decisão: opção A agora, D depois

O menor conjunto de peças que cumpre o aceite (stand-by, promoção, drain, contrato da 8081 intacto) sem mexer no que já mede a
Rodada R. A ressalva vem junto: a hipótese de que X não cresce com réplicas (linha do `eventcap`) vale para **qualquer** opção.

A opção D (Kubernetes com HPA/KEDA) é o passo seguinte, na issue #197, fora do Batch 2. A regra 50/80, a
chave de réplicas ativas no Redis e a conta do Supavisor desta ADR valem para ela também; muda quem sobe as réplicas e quem
balanceia.

## Parâmetros (opção A)

Todos por variável de ambiente no override, com default (nenhum é segredo; nenhuma variável nova vai ao `.env`).

| Parâmetro | Valor | Por quê |
|---|---|---|
| `reservation`: mín. ativas / máx. total | 1 / 3 | 3 × Hikari cabe no Supavisor (abaixo) |
| `gateway`: mín. ativas / máx. total | 1 / 3 | idem |
| Período do laço do scaler | 2 s | 10 janelas de 200 ms do contador |
| Subida da stand-by (u > 0,50) | boot do JVM até o health check: 3–7 s medidos no dry-run local (imagens já construídas, host ocioso); a Rodada 4 mede sob carga (o `start_period` de 20 s do compose é só tolerância) | medido no dry-run |
| Promoção da stand-by (u ≥ 0,80) | < 1 s (Runtime API) | |
| Descida | condição sustentada por 60 s + drain de até 30 s | histerese |
| Cooldown entre ações do mesmo serviço | 30 s | evita oscilar |
| Capacidade por réplica do gateway (`AUTOESCALA_GATEWAY_RPS`) | definida no dry-run (valor baixo) e calibrada na Rodada 4 | não há medida dela hoje |

## Aritmética da regra (sem oscilar)

- **Ativas (A)** = réplicas com peso > 0 no HAProxy. A **stand-by (S)** não conta.
- **Uso do `reservation`:** `u = RPM do evento mais quente ÷ (X × A)`. O `admission` publica no Redis o maior RPM da janela (no
  mesmo script Lua do contador); o scaler lê de lá. O gatilho da fila (#43) continua por evento, com o mesmo `A` (chave do Redis).
- **Uso do gateway:** `u = req/s da entrada ÷ (AUTOESCALA_GATEWAY_RPS × A)`, das estatísticas do HAProxy.
- **Subir stand-by:** `u > 0,50`, `S = 0` e `A + S < máx.` → `--scale +1` com peso 0.
- **Promover:** `u ≥ 0,80` e `S = 1` → peso 100, `A := A + 1`, chave do Redis atualizada. No `reservation`, o `admission` já foi
  para a fila (a 0,80, pelo `A` antigo) e o worker pool passa a entregar pelo `lb`, já com a réplica nova. Depois da promoção `u`
  cai (0,80 → 0,40 com A de 1 para 2): a fila **não** volta ao síncrono na hora, porque a volta exige `u < 0,70` **e** a espera do
  evento vazia (histerese da #43).
- **Descer uma ativa:** `RPM ÷ (X × (A − 1)) < 0,50` por 60 s e fila vazia → drain e parada. A conta usa `A − 1`, então a descida
  não dispara uma subida logo em seguida.
- **Descartar a stand-by:** `u < 0,30` por 60 s.
- Mín. e máx. por variável; o scaler nunca derruba a última ativa.

## Pool (Supavisor ≤ 1500) com réplicas

Restrição: `N_res × H_res + N_gw × 10 + 3 + 100 + 100 + H_stock ≤ 1500`, contando a stand-by (ela abre o `minimum-idle`); `H_stock` = 250, uma réplica fixa (#47).

- No override, o Hikari do `reservation` cai de 1000 para **300 por réplica** (= threads do Tomcat, 300: nenhuma thread espera
  conexão no Hikari; a fila real continua no Supavisor, com o pool real de 45).
- Com o máximo (3 + 3): `3 × 300 + 3 × 10 + 3 + 100 + 100 = 1133 ≤ 1500` (#111, com 1 réplica de `identity`/`catalog`/`notification`). A #112 refez a conta com 3 de cada (ver "Extensão aos demais serviços"): `1239 ≤ 1500`. Sobravam 367 para a #112 (réplicas de `identity` e
  `catalog`, 100 cada, que vão precisar baixar para caber).
- O compose base mantém 1000 no `reservation` (braço sem autoescala comparável com a Rodada R).
- **Com o `stock` (#47, saga stock + wallet):** serviço novo de uma réplica fixa, fora do scaler e do lb (as réplicas do `reservation` o chamam por `stock:8086`), Hikari 250
  literal. Máximo: `3 × 300 + 3 × 10 + 3 × 3 + 3 × 50 + 3 × 50 + 250 = 1489 ≤ 1500` (folga de 11); no base, `1000 + 250 + 10 + 3 + 100 + 100 = 1463`. Pré-condição da R5: Hikari de
  `identity`/`catalog` da R4 ≤ 51 por réplica; senão `H_stock = 1500 − soma da R4`, conferido antes do 1º bloco.

## O que não depende da escolha (adiantado enquanto a decisão não chega)

- Teste com 2+ execuções concorrentes no Postgres real: expiração (`SKIP LOCKED`, cada reserva expira uma vez, um
  `ReservationExpired` por stream), retenção das chaves de idempotência e dois `OutboxRelay` (sem publicação duplicada, ordem
  mantida).
- Auditoria de estado em memória (JWT/JWKS, `Idempotency-Key`, reservas, cache de `exists`).

## Fica para a Rodada 4

A medição de X com N réplicas (escada com autoescala, overselling 0, réplicas × tempo em CSV e gráfico), o custo do salto pelo
`lb` (C0 × C) e o registro do resultado na [ADR da fila](Architecture-Decision-Async-Checkout-Queue.md).

## Como ficou implementado (#111, 2026-10-28)

Arquivos: `Projeto/docker-compose.autoescala.yml`, `Projeto/autoescala/{haproxy.cfg,scaler.sh,scaler.selftest.sh,README.md}`, `rpm.lua` do `admission`
(chave `adm:replicas` e `adm:hot`), `Projeto/frontend/{nginx.conf,Dockerfile}` (template) e a guarda do `Projeto/Backend/run-tests.sh`. Onde a implementação detalha ou
se afasta do texto acima:

- **HAProxy 3.2.25** (variante Debian, tem bash). A Runtime API escuta em `127.0.0.1:9999` **dentro** do container (nada publicado), não em socket Unix: a imagem não traz `socat`
  nem `nc`, e o scaler a alcança por `docker exec` + `/dev/tcp`.
- **Slot nasce com peso 100.** O stand-by é o `set server ... weight 0` que o scaler aplica à réplica nova assim que o lb a enxerga (o JVM leva alguns segundos para passar no health check,
  então o peso 0 chega antes do primeiro tráfego). Efeito: com o scaler parado o lb continua servindo todas as réplicas que existirem (nunca há "réplica sem tráfego e sem dono").
  Slot que sai volta a `state ready` + `weight 100` (um slot em drain não pode herdar o estado na próxima réplica que o DNS puser nele).
- **`catalog` também chama o reservation por `lb:8082`** (além do `backend` e do `admission`): na falta disso ele cairia, por DNS, em réplica em stand-by ou escoando.
- **Leitura ruim nunca vira carga zero** (volta 3): Redis ilegível (fora, recriado, sem PING/valores numéricos) faz o reservation não decidir naquele laço e zera os contadores de "condição sustentada"; `docker ps`/`inspect` com erro ou lb mudo fazem o laço inteiro não agir (uma lista vazia por falha viraria `subir_min` e um `--scale 1` sem drain). `hold` do `resolvers`: só `obsolete 1s` (o resto no default de 30 s: um soluço do DNS embutido não pode derrubar todos os slots).
- **Limite do sinal:** `adm:hot` vem do admission; com o admission fora (e o Redis no ar) a chave expira e a carga lê 0, então o scaler não vê a carga do reservation e pode descer. A alternativa, cruzar com o `req_tot` do backend `reservation` do lb, fica para a Rodada 4.
- **Uso do gateway** = delta de `req_tot` da entrada entre dois laços (média dos 2 s), em vez do `req_rate` de 1 s. **Uso do reservation** = `adm:hot`, o maior RPM visto no último
  200 ms (a janela do contador), gravado a cada tentativa contada e só sobe dentro da janela (`>=`), com TTL de uma janela: staleness <= 1 janela.
- **Promoção ignora o cooldown** (é a ação urgente, a 0,80); o cooldown de 30 s vale para subir, descartar e descer, e é contado também depois de uma promoção. Repor o mínimo
  (`subir_min`, quando o total fica abaixo do mínimo) também o ignora. A stand-by é descartada antes de uma ativa descer; descer só com `S = 0` e `B = 0` (nenhuma réplica subindo).
- **Quem o `--scale N-1` remove** é o container de maior `container-number`; o scaler escoa exatamente esse (e se, ao descartar, ele não for a stand-by, não faz nada e avisa).
- `adm:replicas` vale TTL 15 s e é renovada também durante o drain; só aceita inteiro de 1 a 1000 (qualquer outra coisa cai no `ADMISSION_RESERVATION_REPLICAS`).


## Extensão aos demais serviços (#112)

A #112 estende o mesmo mecanismo (opção A, regra 50/80, stand-by, drain) a `admission`, `identity`, `catalog` e `notification`, **sem mecanismo novo**. A passada (a) tratou a corretude de cada serviço com N réplicas; a passada (b) ligou os serviços ao `lb`/ao scaler e fez o dry-run (seção abaixo).
Máximo de **3 réplicas** em cada um. A auditoria de estado em memória e os testes estão no `Projeto/Backend/CLAUDE.md`, "Os outros quatro serviços com N réplicas (issue #112)". O que muda a decisão de arquitetura:

- **`identity`:** as N réplicas **compartilham a mesma chave** (o volume `identity-keys`); não se publicam várias chaves no JWKS. A chave é criada com `link(2)` (exclusivo; `rename(2)` substituiria o destino em silêncio), então N réplicas subindo juntas com o volume vazio ficam com a mesma chave, e o token de uma valida no JWKS de qualquer outra.
  Código de cadastro e de MFA moram no banco. Sem fallback para `Files.move`: o volume precisa aceitar hard link.
- **`catalog`:** o command se serializa pelo `FOR NO KEY UPDATE` da linha de `event` e, em conflito de versão, repete (5 tentativas) ou vira 409. Leitura é `SELECT`.
- **`admission`:** sem estado em memória (contador, modo, espera e resultado no Redis; fila no RabbitMQ). **Teto de concorrência:** o peso 0 do HAProxy não para os workers, e uma réplica em stand-by já consome a fila. O base tem 32 workers; no override cada réplica ganha `floor(32 / 3) = 10` (30 com as 3 vivas, dentro do teto de 32;
  `ceil` daria 11 e 33, acima dele). Com uma réplica só a fila drena mais devagar (10 workers em vez de 32): efeito que a Rodada 4 mede no braço C.
- **Partições dos consumers** (um group usa no máximo uma réplica por partição; os tópicos nascem com 1, e o `reservation.wallet` e o `catalog.event` têm chave `event_id`, o `identity.notification` tem chave = `streamId`):

  | Tópico | Decisão | Efeito na ordem |
  |---|---|---|
  | `identity.notification` | **3 partições** | nenhum: cada e-mail é um stream de 1 versão |
  | `reservation.wallet` | **3 partições** | mantém a ordem por stream/evento (mesma chave, mesma partição); o evento quente continua numa partição só, então o ganho é com vários eventos |
  | `catalog.event` | **fica 1** | só `EventCreated`, baixo volume, idempotente por `event_id`; réplicas extras ociosas |

  Ampliar de 1 para 3 muda `hash(chave) mod N` uma única vez; versões do mesmo stream podem então chegar por partições diferentes, fora de ordem. O checkpoint por stream (relê do `event_store` as versões que faltam e descarta as já aplicadas) cobre isso (`ReservationRebalanceTest`). O `add-partitions` **não aconteceu na passada (a)**: é irreversível,
  e o broker é compartilhado com os testes e com o braço sem autoescala da Rodada 4; a passada (b) o pôs num one-shot do override (`particoes`, abaixo). Os beans `NewTopic` ficam em `.partitions(1)`: o `KafkaAdmin` aumenta as partições de um tópico existente quando o bean declara mais (nunca diminui), então um bean com 3
  ampliaria o tópico real a cada boot; um bean que diz 1 sobre um tópico de 3 é inofensivo.
- **Rebalance:** nenhum e-mail sai duplicado (`processed_event` com `ON CONFLICT DO NOTHING` na mesma transação do envio: a réplica que recebe a reentrega espera o commit da outra e descarta) e a projeção não é aplicada duas vezes (checkpoint por stream). `40P01` entre lotes concorrentes é repetido (transitório), não trava a partição nem vai ao DLT.
  Janela at-least-once fora do aceite: queda do processo depois do envio e antes do commit (não é rebalance) reenvia o e-mail.
- **Pool:** `identity` e `catalog` com Hikari **50 por réplica** no override, contando a stand-by; `notification` 3, `admission` sem datasource. Com 3 réplicas de cada:
  `3 × 300 + 3 × 10 + 3 × 3 + 3 × 50 + 3 × 50 = 1239 ≤ 1500`.

### Como ficou implementado (#112, passada b, 2026-10-28)

Arquivos: `Projeto/docker-compose.autoescala.yml` (estendido), `Projeto/autoescala/{haproxy.cfg,scaler.sh,scaler.selftest.sh,README.md}`. Nenhum mecanismo novo: a mesma `decidir_acao` decide os 6 serviços.

- **`lb` para os serviços HTTP.** Três backends novos no `haproxy.cfg` (`server-template` de 3 slots, o mesmo `resolvers docker`, TCP check, peso 100 no nascimento e `weight 0` do scaler = stand-by): `catalog` em `:8083`, `identity` em `:8084`, `admission` em `:8085`, só na rede
  (nenhuma porta nova no host). No override o `backend` (gateway) passa a usar `IDENTITY_SERVICE_URL`/`CATALOG_SERVICE_URL`/`ADMISSION_SERVICE_URL` = `http://lb:<porta>` (o JWKS do identity também, derivado da `IDENTITY_SERVICE_URL`). Motivo, o da #111: os clientes têm pool keep-alive e ficariam
  presos às réplicas resolvidas no início. Os `evict-idle` dos clientes (20 s) ficam abaixo do `timeout client`/`http-keep-alive` do lb (75 s). `admission`, `identity`, `catalog` e `notification` ganham `container_name: !reset null`; as réplicas do identity compartilham o volume `identity-keys`.
- **Regra no scaler.** HTTP (`identity`, `catalog`, `admission`): `u = req/s da seção do lb ÷ (AUTOESCALA_<SVC>_RPS × A)`, com o delta de `req_tot` do frontend `<serviço>_interno` entre laços (um par `REQ_ANT`/`T_ANT` por serviço); capacidades **provisórias** `AUTOESCALA_IDENTITY_RPS=100` (login com BCrypt),
  `AUTOESCALA_CATALOG_RPS=500`, `AUTOESCALA_ADMISSION_RPS=1000` (a Rodada 4 calibra). Consumer (`notification`): `u = lag total do group ÷ AUTOESCALA_NOTIFICATION_LAG` (100), lido por `docker exec` no `redpanda` (`rpk group describe` e `rpk topic list`, sem segredo); **sem stand-by** (`S = 0`: o container novo entra no group
  e já recebe partições; a stand-by existe só nos HTTP); `ativa` = container `healthy`. Reuso da função de decisão com `S = 0`, `vazia = (lag == 0)` e `u(A−1) = u`: **sobe uma com `u > 0,50`** (sem réplica ainda subindo, `total < máx.`, fora do cooldown) e **desce uma com lag 0 por 60 s** (a mesma condição "sustentada" da descida);
  o **máximo é `min(AUTOESCALA_NOTIFICATION_MAX, partições de identity.notification)`**, lidas do broker a cada laço e não assumidas (com 1 partição nunca sobe; com `MAX=4` e 3 partições o dry-run parou em 3). Leitura que falha (rpk, group inexistente, `TOTAL-LAG` ou partições não numéricos) = o `notification` não decide e zera a
  contagem de "condição sustentada" (CSV `sem_leitura`), como a leitura do Redis; o mesmo vale para um `req_tot` ilegível (frontend ausente do `show stat`) nos serviços HTTP: nunca vira carga 0. Os consumers do `reservation` escalam junto com o reservation (#111); o scaler só os registra.
- **Partições (decisão da passada a).** O serviço one-shot `particoes` do override sobe `identity.notification` e `reservation.wallet` para 3: lê a contagem atual por `rpk topic list` e soma só o que falta com `rpk topic add-partitions --num` (**`--num` é quanto ADICIONAR, não o total**; `-n 3` num tópico de 1 daria 4, para sempre).
  Idempotente (com 3 não faz nada). O `identity.notification` **não é criado por ele**: o `NewTopic` do identity o cria com a retenção curta (a mensagem tem e-mail e código), então o one-shot espera o `identity` ficar healthy e aborta se o tópico não existir; o `reservation.wallet` (o `NewTopic` não tem config) é criado com 3 se faltar.
  O `notification-seek`, o `reservation` e (indiretamente) o `notification` esperam o one-shot (`service_completed_successfully`). **Irreversível:** quem sobe o override na stack real aumenta as partições do broker real para sempre; por isso o README avisa e a Rodada 4 roda o braço SEM autoescala antes do braço C. Conferido no dry-run: `identity.notification` 3 partições com
  `retention.ms=930000` (a do bean, intacta) e `reservation.wallet` 3. Os beans `NewTopic` ficam em `.partitions(1)` (o `KafkaAdmin` só aumenta). O one-shot termina com `restart: "no"` e, por isso, a guarda do `run-tests.sh` (`docker ps`, só containers rodando) não precisa vê-lo.
  **Não mude a partição padrão do broker** (`default_topic_partitions`): `ReservationRelayConcurrentTest`, `OutboxRelayTest` e outros afirmam a ordem global num tópico auto-criado, que só vale com 1 partição.
- **Pool.** Inalterado em relação à passada (a): `3 × 300 + 3 × 10 + 3 × 3 + 3 × 50 + 3 × 50 = 1239 ≤ 1500`; o Hikari 50 de `identity`/`catalog` fica abaixo do Tomcat 300 deles (threads esperam no Hikari; `connection-timeout` de 5 s); 90 por réplica caberia (1479) sem o `stock`, mas não cabe com ele (#47: 1729). A medir na Rodada 4.
- **Ritmo do laço.** O período NOMINAL é 2 s (`AUTOESCALA_PERIODO`), mas cada laço faz chamadas ao docker (`ps`, `inspect`, `exec` no lb, no redis e no redpanda) que custam ~0,2 s cada no Windows: com 6 serviços o laço levou ~5 s na 1ª versão (um `docker ps` por serviço) e ~4 s depois de trocar os 6 `docker ps` por um só. Como os critérios são por tempo sustentado (60 s) e cooldown, isso só atrasa a detecção em alguns segundos; a Rodada 4 diz se importa.
- **Pendências nomeadas.** Rotação da chave com N réplicas do identity (parar TODAS, apagar o arquivo, subir, reiniciar o backend) e o `limpar-banco.sh` do kit de carga (versionado na #178, ciente de réplicas por label; o scaler tem de estar parado): README e #178. A 1ª busca do JWKS pelo lb pode cair antes de o slot do identity ficar UP; o gateway só tenta de novo a cada 30 s (o dry-run espera os slots e faz um login antes da carga).

**Dry-run local (não é medição)**, em project isolado `gerenciador-e2e-ae112` (override do e2e + autoescala + um terceiro arquivo local, de duas linhas, que tira a porta 8081 do `lb` (receita no README); Mailpit no lugar do Gmail; `config` validado ANTES do `up`: sem `container_name`, sem porta no host, `notification` com `MAIL_HOST=mailpit`, `backend` → `lb`). Capacidades baixas por variável
(`AUTOESCALA_{IDENTITY,CATALOG,ADMISSION}_RPS=5`, `AUTOESCALA_NOTIFICATION_LAG=50`, máx. 2 nos HTTP, gateway e reservation com capacidade alta para ficarem em 1), `DESCE_SEG=DESCARTA_SEG=20`, `COOLDOWN_SEG=10`. Slots UP e um login (warm-up do JWKS) antes da carga. O CSV (`ts,servico,ativas,standby,uso,acao`) é gerado pelo scaler em `Projeto/autoescala/saida/` (gitignored: como na #111, nenhum CSV de dry-run é versionado); a tabela abaixo resume as ações.
Resultado (2 rodadas; a 2ª com estas diferenças: stand-by do `identity` descartada antes da promoção, rajada de 8000 e `AUTOESCALA_NOTIFICATION_MAX=4`):

| Serviço | Carga | Subida | Promoção / escala | Volta |
|---|---|---|---|---|
| `identity` | logins 3 req/s, depois 7 | `subir` com `u = 0,600` (stand-by, peso 0 no slot `id2`) | `promover` com `u = 1,200` a 1,600 | `descer` com `u = 0` (sessões abertas ao parar: 0); na 2ª rodada, 3 req/s e ocioso: `descartar` da stand-by com `u = 0` |
| `catalog` | `GET /bff/events/{id}` 3 req/s, depois 7 | `subir` `u = 0,600` | `promover` `u = 0,8` a 1,0 | `descer` |
| `admission` | `POST /bff/event-wallets` 3 req/s, depois 7 (40 usuários, evento de capacidade 5) | `subir` `u = 0,600` | `promover` `u = 1,2` | `descer` |
| `notification` | 2500 (1ª) e 8000 (2ª) eventos válidos produzidos em `identity.notification` | `subir` com lag 1753 (1ª) / 7414 (2ª), `u = 35` a 148 | 2ª rodada: 2ª subida com lag 3394 e **nenhuma 4ª** (3 partições) | `descer` com lag 0 (3 → 2 → 1 na 2ª) |

- **Lag × tempo** (2ª rodada, amostra a cada ~5 s): 7351, 6476, 4861, 2954, 1526 e 772, com 1, 2, 2, 2, 3 e 3 membros no group, e 0 logo depois; os e-mails no Mailpit foram de 780 a 8001.
- **Overselling 0** nas duas rodadas: evento de capacidade 5, 40 usuários, `ativas = 5` e `eventcap.quant = 0` (respostas: 5 × 201, 169 × 202 pela fila, 71 × 400).
- **Nenhum e-mail duplicado:** 1ª rodada, 2501 mensagens no Mailpit = 2501 destinatários distintos (2500 do lote + 1 cadastro real, cujo código foi lido do e-mail); 2ª, 8001 = 8001, 0 duplicados. Todas as réplicas do `notification` com `MAIL_HOST=mailpit`; nenhum e-mail real saiu.
- **Isolamento:** project removido com `down -v` (0 containers, volumes e redes sobrando); o `gerenciador-db` real seguiu com o `working_dir` de sempre; as env-files do checkout principal entraram por referência (`COMPOSE_ENV_FILES`), sem cópia nem leitura.
- **Fica para a Rodada 4:** a rodada com todos escalando (overselling 0, nenhum e-mail duplicado, gráfico) com capacidades calibradas, o custo do salto pelo `lb` e o efeito do Hikari 50 e dos 10 workers por réplica do `admission`.
