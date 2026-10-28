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

Restrição: `N_res × H_res + N_gw × 10 + 3 + 100 + 100 ≤ 1500`, contando a stand-by (ela abre o `minimum-idle`).

- No override, o Hikari do `reservation` cai de 1000 para **300 por réplica** (= threads do Tomcat, 300: nenhuma thread espera
  conexão no Hikari; a fila real continua no Supavisor, com o pool real de 45).
- Com o máximo (3 + 3): `3 × 300 + 3 × 10 + 3 + 100 + 100 = 1133 ≤ 1500`. Sobram 367 para a #112 (réplicas de `identity` e
  `catalog`, 100 cada, que vão precisar baixar para caber).
- O compose base mantém 1000 no `reservation` (braço sem autoescala comparável com a Rodada R).

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

