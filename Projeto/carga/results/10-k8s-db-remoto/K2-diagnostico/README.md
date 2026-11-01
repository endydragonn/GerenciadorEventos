# K2-diagnostico (`Deg-337214`), 2026-11-01: rodada de DIAGNÓSTICO, não é resultado

Esta pasta é investigação. Os números daqui **não entram** na comparação K0 × K1 × K2. O objetivo era descobrir por que o braço b1 com
autoescala para logo no começo da escada (K2 e K2-repeticao), medindo, a 20k, onde está o tempo de cada salto do `POST /bff/event-wallets`.

## O que mudou em relação ao K2-repeticao

Estas diferenças mudam o regime:
- **O banco começa com as linhas do K2-repeticao.** Não houve limpeza nova. O seed é novo e foi feito por cima: 1000 usuários (sufixo
  337214, host com `user_id` 1003) e 64 eventos (ids 869–932, capacidade 500).
- **A instrumentação gasta CPU extra do host e dos sidecars:** access log do Envoy, `kubectl logs -f` de cada sidecar, raspador do actuator e
  do Envoy por `kubectl exec`, coletor por pod e `coleta-host`.
  - Com o cluster ocioso, a CPU do host foi a **54,5%** com tudo ligado (`host-telemetria/telemetria-host-k2diag-ocioso-tudo-*`).
  - Ociosa sem nada, ela foi a 16,7%, e só com o coletor por pod, a 18,9% (`K2-repeticao/host-telemetria`).
  - Duas medições com o raspador sendo encerrado deram 57,5% e 59,9%: a diferença é ruído, e o grosso vem da `coleta-host` e dos `kubectl
    logs -f`.
  - **A CPU do host durante a carga não foi medida:** a `telemetria-host` não rodou nesta rodada.
- **Não há Hikari nem Tomcat do gateway.** O actuator do backend responde 401 em `/actuator/metrics`, porque o SecurityConfig do gateway só
  libera o `health`, também na porta 8090.
- **Um degrau só, de 20k, por 3 min** (parou antes, ver abaixo), com a mesma linha do k6 (850d38e). Réplicas todas em 1 às 19:59:04Z, mais
  60 s de aquecimento ocioso.

## Instrumentação ligada (e como desfazer)

| O quê | Como foi ligado | Como desfazer |
|---|---|---|
| Access log do Envoy em toda a mesh | `Telemetry` `diag-accesslog` em `istio-system`, provider `envoy` (formato padrão do Istio) | `kubectl --context kind-gerenciador -n istio-system delete telemetry diag-accesslog` |
| Métricas do actuator (Hikari, Tomcat, JVM) | `kubectl set env deploy/<svc> MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true` em backend, reservation, stock e admission (reinicia os pods) | `kubectl --context kind-gerenciador -n gerenciador set env deploy/<svc> MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE- SERVER_TOMCAT_MBEANREGISTRY_ENABLED-` |
| Raspador (`raspa-diag.sh`), coletor por pod (`coleta-pods.sh`), access log (`segue-accesslog.sh`) | scripts no host, encerrados no fim | — |

## Rodada

- **k6:** de 20:04:09Z a 20:06:41Z, com código de saída 99. Logins terminados às 20:05:40Z; "Insufficient VUs" às 20:06:11Z.
  - Fontes: `gerador/k6-console-337214.log` e `gerador/summary-degraus-337214.json`.
- **20k (parcial, ~60 s):**
  - 15.468 reservas, p50 6.238,6 ms, p95 10.031,1 ms, 2.946 descartes, 0 5xx, 2.173 respostas 202.
  - Parada por p95 acima de 5.000 ms na janela de 60 s.
- **Overselling 0 e sagas convergidas** (`host-telemetria/verificacoes-k2diag.log`).
- **Gerador:** não saturou. RAM livre de 10,11 GB antes, mínima de 3,38 GB e CPU no máximo em 77% (`gerador/telemetria-gerador-k2diag-337214.csv`).
- **Dell (DB):** não saturou.
  - Pico de 262 conexões (pool cheio), 173 active e 40 idle in transaction.
  - CPU us+sy com pico de 33%.
  - `pg_stat_database` entre 20:03:57Z e 20:07:34Z: +75.879 commits e +23.544 rollbacks, 0 deadlocks, 0 temp_files.
  - Com o cluster ocioso, entre 19:43Z e 20:03Z, o banco teve ~62 rollbacks/s. Isso é compatível com o tick de 50 ms dos relays de outbox,
    que fazem rollback quando não há o que publicar. Hipótese, não conferida.
  - Pacote: `dell/` (ref temporário `K8sDB-dell-K2-diag`, dfa947e, sha256 `e1413020…c4dd075`, 543.071 bytes, conferido).

## Evidência (janela da carga: 20:05:40Z a 20:06:42Z)

### 1. Tempo por salto, pelo access log do Envoy (`analise-accesslog-carga.txt`)

| Salto (proxy que registra) | n | duração p50 | p95 |
|---|---|---|---|
| entrada do gateway, `POST /bff/event-wallets` | 17.299 | **5.647** | **9.930** |
| gateway → admission (`POST /internal/admissions`) | 16.112 | 16 | 300 |
| gateway → reservation (`POST /internal/reservations`) | 13.972 | 982 | 2.458 |
| entrada do reservation | 16.443 | 929 | 2.365 |
| reservation → stock (`POST /internal/holds`) | 16.083 | 737 | 2.211 |
| entrada do stock | 16.083 | 729 | 2.208 |
| entrada do gateway, `POST /bff/event-wallets/confirm` | 7.326 | 5.294 | 8.328 |
| gateway → reservation (`/confirm`) | 6.461 | 94 | 326 |

Tempos em ms. Cada linha é o proxy que registra o salto; na entrada, a duração inclui o tempo da app.

- **Mais de 4,5 s ficam dentro do gateway antes de ele chamar alguém.** A soma dos saltos para fora (16 + 982 ms no p50) não chega a 1 s,
  mas a entrada do gateway leva 5,6 s.
  - O confirm mostra o mesmo: 94 ms no reservation e 5,3 s na entrada do gateway.
- **O reservation espera o stock:** dos 929 ms do reservation, 737 ms são a chamada ao stock.
- **O stock gasta o tempo no CAS do `eventcap`.** Pelo `pgss-*`, o `UPDATE stock.eventcap SET quant = quant - ...` teve 17.347 chamadas, com
  média de **286 ms**: é a espera pelo lock da linha quente.
  - Média dessa mesma consulta nos outros blocos, que cobrem escadas diferentes e por isso só servem de indício: K0 204 ms, K1 118 ms, K2
    217 ms, K2-repeticao 323 ms.

### 2. Quem dá os 5xx

- **Nesta rodada o k6 não teve 5xx.** No access log aparecem só:
  - 12 respostas 503 do próprio `admission`, sem flag do Envoy: é a app respondendo 503, com o gateway fazendo fail-open;
  - `DC` (o cliente cortou a conexão): 1.876 no POST e 965 no confirm na entrada do gateway, do k6 encerrando o teste.
- **Não houve `UF`, `UO`, `URX`, `UC` nem `NR`** em nenhum salto.
- Os 67 5xx do K2-repeticao (26k) aconteceram sem access log e continuam sem origem.

### 3. Hikari e Tomcat (`pods/actuator-*`, a cada 5 s)

| Pod | Hikari ativo (média / máx.) | Hikari pending (média / máx.) | Tomcat ocupado (média / máx. de 300) |
|---|---|---|---|
| stock original | 59 / **60 de 60** | **142,6 / 237** | 202 / 295 |
| stock réplica 2 | 56 / 60 de 60 | 57,4 / 103 | 98 / 141 |
| stock réplica 3 | 60 / 60 de 60 | 31,5 / 34 | 77 / 98 |
| reservation | 17 / 63 de 200 | 0 / 0 | **290 / 300** |
| admission (3 pods) | — (sem datasource) | — | 1 a 22 |

- **O stock fica com o Hikari esgotado** em todas as réplicas, com até 237 threads esperando conexão.
- **O reservation fica com o Tomcat esgotado** (300 de 300), à espera do stock, mas com o Hikari folgado (pending 0).
- Nenhum `hikaricp.connections.timeout`.

### 4. Conexões do k6 por pod do gateway (`pods/envoy-*`, `http.inbound_0.0.0.0_8081.downstream_cx_active`)

- O k6 abriu **3.006 conexões, todas no pod original** (`bq5xg`): 6 antes da carga, 1.677 às 20:05:44Z e 3.006 às 20:05:49Z.
- As réplicas novas do gateway (`7vw9g` desde 20:05:54Z, `pvfzq` desde 20:06:10Z) ficaram com **0 conexões e 0 POSTs** no access log. Elas
  gastaram 0,22 e 0,34 núcleo, de JVM subindo.
- Entrada do gateway a cada 10 s: ~3.334 POSTs, todos no `bq5xg`.

### 5. CPU por container (`pods/cgroup-cpu-*`)

- Gateway quente: app 1,01 núcleo e sidecar 0,66. Reservation: app 1,12 e sidecar 0,48.
- Throttling por períodos de CFS: rabbitmq 8%, os outros até 5%.
- O `istio-proxy` não tem limite de CPU e roda com concurrency 16.

## Arquivos

- `degraus-337214.csv`, `degraus-337214.html`, `plano-degraus-337214.json`
- `gerador/`: resumo do k6, monitor de RAM e CPU e console do k6 (ref temporário `K8S-gerador-K2-diag`, b3abfd8)
- `accesslog/`: o access log do `istio-proxy` de cada pod de backend, reservation, stock e admission (`accesslog-<pod>.txt.gz`, com gzip;
  sem cabeçalho de autenticação, que o formato padrão não registra)
- `analise-accesslog-carga.txt`: a saída de `analisa-accesslog.py` na janela da carga
- `pods/`: `actuator-*` (as duas partes do raspador), `envoy-*`, `cgroup-cpu-*`, `top-pods-*`, `replicas-*` e os scripts
- `host-telemetria/`: `coleta-host-*`, `pgss-*`, `registro-*`, `resumo-saga-*`, `saga-*`, `eventos-*`, as medições de CPU ociosa e
  `verificacoes-k2diag.log`
- `dell/`: coleta do Dell, com os snapshots e o delta do `pg_stat_database`
