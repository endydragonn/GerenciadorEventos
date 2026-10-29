# Design: autoescala no Kubernetes (kind + HPA/KEDA) e `stock` escalável (issues #197 e #205)

Data: 2026-10-29. Branch de integração `K8s` (de `Batch_2`, `b116c55`). Base: a opção D da
[ADR da autoescala](../../adr/Architecture-Decision-Autoescala.md), os aceites da #197 e da #205 e dois follow-ups da Rodada 4 que
o mecanismo novo **não pode repetir**: #207 (health check que tira do ar réplica saturada) e #206 (sinal `adm:hot` instantâneo). As
correções de #206/#207 para o compose e o HAProxy (opção A) ficam fora: aqui elas viram requisito do cluster.

> **D6 em aberto** (o usuário está comparando proxy L7/malha com balanceamento no cliente). A seção "Balanceamento" traz as
> opções e o que cada uma precisa cumprir; o resto da spec não depende da escolha, salvo onde está marcado **(depende de D6)**.

Ponto de partida no código (`b116c55`): 7 serviços Spring Boot (`backend` = gateway, `identity`, `catalog`, `reservation`, `stock`,
`admission`, `notification`) + `frontend` (nginx), e a infra (`db` = `supabase/postgres` 17, `supabase-pooler` = Supavisor, `redpanda`,
`redis`, `rabbitmq`, Studio/Kong/meta), todos em compose. A autoescala da opção A é um override (`docker-compose.autoescala.yml`: HAProxy
`lb` + `scaler.sh` no host, regra 50/80, stand-by com `weight 0`). O `admission` lê as réplicas ativas do `reservation` da chave
`adm:replicas` (escrita pelo scaler, TTL 15 s; sem ela vale `ADMISSION_RESERVATION_REPLICAS`) no script Lua do contador e publica o RPM
do evento mais quente em `adm:hot`. As imagens `*.slim` (#177) existem, mas entram só pelo override `docker-compose.slim.yml`.

## Decisões

| # | Pergunta | Decidido |
|---|---|---|
| D1 | Onde o cluster roda | **kind** (usuário, 2026-10-29). Sobe e cai por script, config versionada. kind v0.33.0 e helm v3.22.0 em `E:\TCC\claude-tooling\bin` (fora do repo; versão e hash no ledger). |
| D2 | O que vai para o cluster | **(b) a stack inteira** (usuário, 2026-10-29): os 7 serviços, o `frontend`, Postgres + Supavisor, Redpanda, Redis e RabbitMQ. `run-tests.sh` continua no compose (infra de teste); e2e com cluster próprio. Ver "Topologia". |
| D3 | Stand-by da regra 50/80 | **(a) HPA com alvo de uso 0,5** (usuário, 2026-10-29): a réplica sobe a 50% já recebendo tráfego; não existe réplica sem tráfego. O gatilho da fila a 0,8 por evento continua no `admission`. |
| D4 | De onde o `admission` lê as réplicas ativas | **(b) API do Kubernetes** (usuário, 2026-10-29): ServiceAccount + RBAC mínimo (só `get` no Deployment `reservation`), `status.readyReplicas`; falha da API = fallback `ADMISSION_RESERVATION_REPLICAS`. Ver "Réplicas ativas". |
| D5 | Fonte da métrica de escala | **(a) KEDA sem Prometheus** (usuário, 2026-10-29): `reservation` pelo `adm:hot`, `notification` pelo lag, demais por CPU. Ver "Autoescala". |
| D6 | Balanceamento entre réplicas (o Service é L4) | **em aberto.** Ver "Balanceamento". |
| D7 | Slim como padrão | **(a)** (usuário, 2026-10-29): o compose base usa as `*.slim` sem `build:`; a completa vai para um override inverso; o build vira um script único. |

Decidido nesta spec, sem pendência (solução única ou trade-off desprezível): cluster de **um nó**; manifests em **kustomize** (embutido
no `kubectl`), helm só para instalar KEDA e metrics-server com versão de chart fixa; Services com os **mesmos nomes** do compose; ambiente
dos pods gerado do `docker compose config`; dados do cluster **descartáveis**; Studio/Kong/meta **fora** do cluster; probes numa **porta de
management** própria; janela de estabilização do HPA para a descida; AppArmor documentado como não aplicável neste host.

## Topologia (D2 = b)

Namespace `gerenciador`. Tudo o que o compose base sobe para atender o Flutter e o gerador de carga vai para o cluster:

| Componente | Objeto | Notas |
|---|---|---|
| `db` (`supabase/postgres:17.6.1.136`) | `StatefulSet` (1) + `PersistentVolumeClaim` (`local-path`) + Service `db` | mesmos init scripts do compose (abaixo); sem porta no host |
| `supabase-pooler` (`supabase/supavisor:2.9.13`) | `Deployment` (1, fixo) + Service | mesmo `command` (migrate → `pooler-upsert.exs` → server), `POOLER_MAX_CLIENT_CONN=1500`, pool real 45 |
| `redpanda` (v26.2.4) | `StatefulSet` (1) + PVC + Service `redpanda` | mesmos argumentos; `--advertise-kafka-addr=internal://redpanda:9092` resolve no namespace |
| `redis` (7.4.12-alpine) | `Deployment` (1) + Service | senha do Secret, sem persistência, `maxmemory 512mb volatile-ttl` |
| `rabbitmq` (4.1.8-alpine) | `Deployment` (1) + Service | `/var/lib/rabbitmq` num `emptyDir` `medium: Memory` (o `tmpfs` do compose) |
| 7 serviços Java | `Deployment` + Service com o nome do compose | autoescala (abaixo); `notification` sem Service |
| `frontend` | `Deployment` (1) + Service `NodePort` 30088 | `BACKEND_UPSTREAM` aponta para a entrada do gateway **(depende de D6)** |
| `notification-seek`, `stock-seek` | `initContainer` (imagem do redpanda, `rpk`) no próprio pod do `notification`/`stock` | "move o group para o fim só se ele não existe": idempotente, roda a cada pod que sobe, sem coordenação |
| `particoes` | `Job` | sobe `identity.notification` e `reservation.wallet` para 3 partições (`--num` = quanto adicionar; espera o `identity` criar o tópico dele). Não é irreversível no broker real: o broker do cluster é outro e nasce a cada cluster |

- **Dados descartáveis.** O PVC do `db` e do `redpanda` vive no nó do kind e some no `cluster.sh down` (que roda antes de cada medição no
  host). O banco do cluster nasce dos init scripts a cada cluster; **nenhuma migração** é aplicada nele. O `meu_banco` persistente da R4/R5
  continua no compose, intocado.
- **Studio, Kong e meta ficam fora** (não são caminho de requisição nem aceite). Acesso ao banco do cluster por `cluster.sh psql`
  (`kubectl exec` no pod do `db`); o Studio continua disponível no compose.
- **Ordem de subida:** o compose usa `depends_on: condition`; no cluster cada pod tem um `initContainer` que espera as dependências
  aceitarem conexão (`nc -z`, imagem `busybox` com tag fixa), na mesma ordem do compose (ex.: `reservation` espera `supabase-pooler`,
  `redpanda` e `stock`). Os serviços já toleram dependência fora depois de subirem (fail-open, 503, retry do consumer).
- **Arquivos montados** (`functions-role.sql`, `_supabase.sql`, `pooler.sql`, `roles.sql`, `supabase-app-init.sql`, `Backend/db/init-scripts/`
  (80 KB), `pooler-upsert.exs`): `ConfigMap`s criados pelo `cluster.sh` com `kubectl create configmap --from-file` a partir dos **mesmos
  arquivos do repo** (o kustomize não lê fora da sua pasta), montados nos mesmos caminhos do compose. `db-config` (`/etc/postgresql-custom`)
  vira `emptyDir`.
- **`run-tests.sh` não muda:** continua usando a infra do compose (`docker compose -f Projeto/docker-compose.yml up -d db redpanda redis
  rabbitmq`) e o `meu_banco`. Cluster e infra de teste do compose podem coexistir (o cluster não publica 5433 nem portas de broker), mas
  juntos pesam no host.
- **Portas do host:** 8081 (gateway) e 8088 (frontend) pelo nó do kind. Enquanto o cluster existe, o compose completo não pode subir
  (conflito nas duas portas); a infra de teste do compose pode.

## Cluster e ciclo de vida

- `Projeto/k8s/kind.yaml` (versionado): um nó `control-plane`, imagem `kindest/node` fixada por digest (a padrão do kind v0.33.0);
  `extraPortMappings` host 8081 → NodePort 30081 e host 8088 → NodePort 30088, em `0.0.0.0` (o gerador da rede local chega na 8081 como
  hoje). O contrato do Flutter (porta e paths) não muda.
- `Projeto/k8s/cluster.sh` (bash, roda no Git Bash e no Linux), subcomandos:
  - `up`: cria o cluster (se não existe); instala metrics-server e KEDA (helm, versões de chart fixas, `--wait`); copia os perfis Seccomp
    para o nó; carrega as imagens (`kind load docker-image` das 7 `*.slim`, do frontend e das imagens de infra já presentes no Docker local,
    para não baixar de novo; as ausentes o nó baixa do registro oficial); gera Secrets e ConfigMaps; `kubectl apply -k`; espera os
    Deployments/StatefulSets ficarem prontos e o `Job` `particoes` terminar.
  - `carregar <svc...>`: recarrega só as imagens dadas e reinicia os Deployments (ciclo de desenvolvimento).
  - `psql`, `rpk`, `redis-cli`: atalhos de `kubectl exec` (sem imprimir segredo: a senha vai pelo ambiente do pod).
  - `down`: `kind delete cluster` (antes da R5 e de qualquer medição no host).
  - `status`: nós, pods, HPAs/ScaledObjects e réplicas.
- Ferramentas: `kind`, `helm` e `kubectl` no `PATH` (o `kubectl` vem com o Docker Desktop; os dois primeiros em `E:\TCC\claude-tooling\bin`
  na máquina do TCC). O script aborta com mensagem clara se faltar um deles, `Projeto/.env` ou `Projeto/supabase/.env`.
- **Recursos:** o cluster inteiro roda no nó do kind (um container). Ordem de grandeza com tudo no mínimo: control plane + KEDA +
  metrics-server ~1,5 GB; `db` ~1 GB; Supavisor, Redis, RabbitMQ ~0,8 GB; Redpanda 0,5–1 GB; 7 JVMs ~5 GB; no máximo de réplicas as JVMs
  chegam a ~15 GB. Cabe nos 32 GB do host, mas **disputa CPU e RAM com qualquer medição**: o cluster é apagado durante a R5 e nas rodadas.
- **Custo do cluster na medição:** o `docker stats` do nó parado (control plane + CoreDNS + kube-proxy + metrics-server + KEDA, sem a stack)
  e com a stack ociosa é registrado no dry-run como linha de base. Limitar a CPU do nó (`docker update --cpus`) fica documentado como opção.
  A rodada formal de medição é issue própria (fora de escopo).
- **Lock:** quem roda `cluster.sh up`/`down`/`carregar` segue o lock Docker do projeto (`E:/TCC/.lock-docker`), como para `docker compose`.

## Manifests

`Projeto/k8s/base/` (kustomize), um arquivo por componente. Overlays: `e2e` (Mailpit, credenciais efêmeras, sem NodePort), `completa`
(imagens completas, para depuração), `carga` (`SEED_ENABLED=true`, como o override de carga do compose).

- **Nomes e portas iguais ao compose** (`backend:8081`, `reservation:8082`, `catalog:8083`, `identity:8084`, `admission:8085`,
  `stock:8086`, `db:5432`, `supabase-pooler`, `redpanda:9092`, `redis:6379`, `rabbitmq:5672`): os defaults de `*_SERVICE_URL`, do
  `SPRING_DATASOURCE_URL` e o `proxy_pass` do frontend valem sem env nova.
- **Imagens:** `gerenciador-eventos-<svc>.slim:latest`, `imagePullPolicy: Never` (vêm do `kind load`); infra com a mesma tag fixa do
  compose, `IfNotPresent`.
- **Ambiente:** o `cluster.sh` gera **um Secret por componente** a partir do `docker compose config --format json` (o mesmo ambiente que o
  compose entrega a cada container, resolvido de `Projeto/.env` e `Projeto/supabase/.env`; arquivo temporário, nunca impresso nem
  versionado, apagado no fim; mesma técnica do `slim/gerar-imagens-slim.sh`). O manifest usa `envFrom: secretRef`; o que muda no cluster
  entra no `env` do manifest (vence o `envFrom`): porta de management, Hikari por réplica, workers do `admission`, URLs de balanceamento
  **(depende de D6)**. Ninguém precisa acrescentar nada ao `.env`.
- **`identity-keys`:** PVC `ReadWriteOnce` (`local-path`). Com um nó só, todas as réplicas do `identity` montam o mesmo diretório e a chave
  continua sendo criada com `link(2)` (#112): nenhuma mudança de código.
- **`automountServiceAccountToken: false`** em todos os pods, exceto o `admission` (D4).
- **Recursos:** `requests`/`limits` de CPU e memória em todos os pods (obrigatório para o HPA por CPU e para o scheduler não espremer o nó).
  Valores provisórios no dry-run; a rodada formal calibra.
- **Seccomp:** `securityContext.seccompProfile: {type: Localhost, localhostProfile: profiles/<svc>-seccomp.json}` nos 7 serviços, com os
  perfis gerados pelo Slim (`Projeto/slim/perfis/`) copiados para `/var/lib/kubelet/seccomp/profiles/` do nó pelo `cluster.sh`. Serviço
  sem perfil gerado e a infra ficam com `RuntimeDefault`; o README diz quais.
- **AppArmor:** **não aplicável neste host.** O kernel do Docker Desktop (`6.18.33.2-microsoft-standard-WSL2`) tem o módulo desligado
  (`/sys/module/apparmor/parameters/enabled = N`, conferido em 2026-10-29). Os perfis do Slim ficam versionados e o README mostra o campo
  `appArmorProfile` para um host Linux com AppArmor; a ADR registra a diferença em relação ao aceite.

## Probes (requisito da #207)

A causa da #207 foi o health check competir com o tráfego (`GET /actuator/health` nas mesmas 300 threads do Tomcat, `inter 2s fall 3`) e o
`lb` tirar do ar o último servidor vivo. No cluster:

- **Porta de management própria** nos 7 serviços: `MANAGEMENT_SERVER_PORT=8090` só no manifest (o compose não muda). O Spring Boot sobe
  um contexto filho com **outro conector e outro pool de threads**, então a probe não espera atrás das 300 threads ocupadas.
- **Readiness = só o estado do ciclo de vida.** `management.endpoint.health.probes.enabled=true` e
  `management.endpoint.health.group.readiness.include=readinessState` (explícito: banco, Redis, broker e downstream **não** entram; um
  Postgres lento sob carga não pode esvaziar o Service). `readinessProbe` em `/actuator/health/readiness`, `periodSeconds 5`,
  `timeoutSeconds 3`, `failureThreshold 6` (30 s sem resposta antes de sair do Service).
- **Liveness tolerante.** `/actuator/health/liveness` (só `livenessState`), `timeoutSeconds 5`, `failureThreshold 12`, `periodSeconds 10`
  (2 min sem resposta antes do restart): um pod saturado e vivo não é reiniciado, o que agravaria a saturação.
- **Startup.** `startupProbe` na readiness, `periodSeconds 2`, `failureThreshold 45` (90 s para o JVM subir sob CPU disputada).
- **Exposição:** a porta 8090 não entra no Service (só o kubelet a usa). `management.endpoints.web.exposure.include` continua `health`.
- **Infra:** probes equivalentes aos healthchecks do compose (`pg_isready`, `rpk cluster health`, `redis-cli ping`,
  `rabbitmq-diagnostics ping`, `/api/health` do Supavisor), com os mesmos timeouts.
- **Prova (aceite):** no dry-run com carga acima da capacidade de uma réplica, nenhum pod sai do Service nem reinicia por probe
  (`kubectl get events` sem `Unhealthy`/`Killing` de probe; `restartCount` 0), e os 5xx do cliente vêm só da aplicação, se vierem.
- **"0 ativas" (2º item da #207):** quem conta réplicas ativas é o `admission` (D4, `readyReplicas`) e o HPA; com a readiness presa ao ciclo
  de vida, um pod saturado continua pronto e contado.

## Balanceamento (D6, em aberto)

O `Service` do Kubernetes (kube-proxy) balanceia **por conexão TCP**, não por requisição. Os serviços internos têm
`server.tomcat.max-keep-alive-requests=-1` de propósito, e os 5 clientes (`reservation-`, `stock-`, `catalog-`, `identity-`,
`admission-client`: Apache HttpClient 5, um pool por cliente com `maxConnTotal = maxPerRoute`, 400 por padrão, `disableAutomaticRetries`,
`evictIdle` 20 s) mantêm as conexões abertas sob carga: **um pod novo não recebe tráfego** das conexões já abertas. Arestas afetadas
(7): gateway → `identity`, `catalog`, `reservation`, `admission`; `catalog` → `stock`; `reservation` → `stock`; `admission` →
`reservation`. A entrada (cliente → gateway) já gira: o gateway usa o default do Tomcat (100 requisições por conexão) sobre o NodePort.

Requisito para **qualquer** opção (vem da #207): nada no caminho tira do ar uma réplica por saturação; a única fonte de "réplica fora" é a
readiness do Kubernetes (acima), que só reflete o ciclo de vida.

| Opção | Como | Custo | Para não repetir a #207 |
|---|---|---|---|
| **(b) proxy L7 central** | um `Deployment` de proxy (HAProxy 3.2, como a opção A, ou Envoy) com 2 réplicas fixas; frontends 8081–8086; os serviços apontam `*_SERVICE_URL` para ele (já suportado por env) | ~6,5 mil req/s pelo proxy no pico da R4 (98k RPM ≈ 1,6 mil req/s na entrada, ~3 saltos internos por reserva), 1 salto a mais por aresta; na R4 o braço C (HAProxy, 4 saltos a mais) teve p95 de 2,73 s a 32k contra 1,43 s sem `lb` (com confusão da autoescala) | descoberta pelo **DNS headless** (só pods prontos), **nenhum health check ativo** no proxy (`server-template ... resolvers`, sem `check`), `retry-on conn-failure` + `option redispatch` só para falha de conexão |
| **(b) malha com sidecar** | Linkerd ou Istio (Envoy) injetado em cada pod; balanceamento por requisição transparente | ~11 mil proxy-req/s no pico (2 proxies por salto); Istio publica ~0,5 vCPU e 50 MB por 1.000 req/s por proxy e +2,8–3,1 ms p90 por salto (1.5/1.6); Linkerd é bem mais leve (benchmark próprio de 2021), mas a disponibilidade de release stable open source precisa ser confirmada; control plane a mais | desligar a ejeção por outlier/health do proxy; readiness do pod como única fonte |
| **(c) balanceamento no cliente** | um helper (~50 linhas) usado pelas 5 libs: `DnsResolver` round-robin sobre o Service headless, `ConnectionConfig.setTimeToLive` (~15 s) e `PoolReusePolicy.FIFO`; retry só em falha de conexão | sem salto nem CPU de proxy; código nosso nas 5 libs + teste com resolver falso; balanceia por conexão com TTL (pod novo recebe tráfego em até um TTL); requisições em voo num pod que morre falham | não há proxy nem health check de proxy |

A escolha preenche: os `*_SERVICE_URL` dos manifests, o `BACKEND_UPSTREAM` do frontend, a entrada NodePort 30081 (gateway direto ou
proxy) e a prova do dry-run (a réplica nova recebe requisições em até alguns segundos depois da subida).

## Autoescala (D5 = a)

| Serviço | Gatilho | Alvo |
|---|---|---|
| `reservation` | KEDA `metrics-api`: `GET /internal/metrics/hot` (novo no `admission`, `X-Internal-Token` por `TriggerAuthentication`), devolve o `adm:hot` | `AverageValue` = `0,5 × ADMISSION_X_RPM` (D3): `réplicas desejadas = ceil(RPM ÷ (0,5 × X))` |
| `notification` | KEDA `kafka`: lag do group `notification` em `identity.notification` | `lagThreshold` = `0,5 × AUTOESCALA_NOTIFICATION_LAG` (50); `allowIdleConsumers: false` limita às partições |
| `backend`, `identity`, `catalog`, `admission`, `stock` | HPA `Resource` CPU (metrics-server) | `averageUtilization` 50 |

- Diferença documentada na ADR: nos serviços HTTP a regra passa de req/s para CPU (o aceite permite "ou a diferença fica documentada").
  O KEDA não tem scaler que leia uma chave simples do Redis, por isso o `adm:hot` sai por um endpoint do `admission`.
- **Mínimo e máximo** por serviço configuráveis (`K8S_<SVC>_MIN`/`_MAX`, defaults 1/3 em todos, inclusive o `stock`, #205); o `cluster.sh`
  gera o patch de kustomize. Sem escala a zero. Infra (`db`, Supavisor, Redpanda, Redis, RabbitMQ) e o `frontend` com 1 réplica fixa.
- **Descida suavizada (requisito da #206):** `behavior.scaleDown.stabilizationWindowSeconds` configurável (`K8S_DESCE_SEG`, default 120 s,
  o valor que a R4 usou por config). O HPA desce só para a **maior** recomendação da janela: uma amostra baixa isolada no meio de um degrau
  sustentado não derruba réplica. `scaleDown.policies`: no máximo 1 pod por 60 s. Subida sem janela (`stabilizationWindowSeconds: 0`), no
  máximo 1 pod por 15 s. No KEDA isso vai em `advanced.horizontalPodAutoscalerConfig.behavior`; `pollingInterval` 5 s.
- **Prova da #206:** o HPA não é código nosso; a prova é o dry-run com carga em degrau que oscila (rajadas com pausas curtas): nenhuma
  descida enquanto o degrau dura, com o CSV mostrando a métrica oscilando e as réplicas estáveis.
- O gatilho da fila do `admission` (uso ≥ 0,80 de `X × ativas`, histerese da #43) não muda.

## Réplicas ativas no `admission` (D4 = b)

- `ServiceAccount` `admission`, `Role` com `get` em `deployments` (`apiGroups: [apps]`, `resourceNames: [reservation]`), `RoleBinding`. Nada
  mais (nem `list`, nem `watch`, nem escrita).
- Um job do `admission` (`@Scheduled`, a cada 2 s, ligado só pela propriedade `admission.k8s.replicas.enabled`, desligada no compose) faz
  `GET https://kubernetes.default.svc/apis/apps/v1/namespaces/<ns>/deployments/reservation` com o `java.net.http.HttpClient` do JDK: token
  lido de `/var/run/secrets/kubernetes.io/serviceaccount/token` **a cada chamada** (o token projetado gira), CA de `ca.crt` num
  `SSLContext` próprio, namespace do arquivo `namespace`. Lê `status.readyReplicas` (Jackson, já presente) e grava `adm:replicas` com TTL
  de 15 s, a mesma chave e o mesmo TTL que o scaler da opção A grava. **Sem dependência nova.**
- O script Lua, o fallback e o comportamento no compose não mudam. Falha (API fora, 403, timeout de 1 s, `readyReplicas` ausente, 0 ou
  fora de 1..1000) **não grava**: a chave expira e vale `ADMISSION_RESERVATION_REPLICAS`, o lado seguro (fila mais cedo). Várias réplicas
  do `admission` gravam o mesmo valor (idempotente). Log WARN com limite de frequência.
- Teste: servidor HTTP falso no lugar da API (resposta com `readyReplicas`, 403, timeout, JSON inválido) → chave gravada ou não; o caminho
  real é coberto no dry-run (subir o `reservation` para 2 e ver `adm:replicas = 2`).

## `stock` escalável (#205)

- Escala de 1 a 3 por CPU (tabela acima), min/máx configuráveis.
- **Pool:** Hikari **85 por réplica** no cluster (env no manifest; o compose base mantém 250). O Supavisor do cluster tem o mesmo teto
  (`POOLER_MAX_CLIENT_CONN=1500`). Conta com tudo no máximo:

  | Serviço | Réplicas máx. | Hikari | Total |
  |---|---|---|---|
  | `reservation` | 3 | 300 | 900 |
  | `backend` | 3 | 10 | 30 |
  | `notification` | 3 | 3 | 9 |
  | `identity` | 3 | 50 | 150 |
  | `catalog` | 3 | 50 | 150 |
  | `stock` | 3 | 85 | 255 |
  | `admission` | 3 | 0 (sem datasource) | 0 |
  | **Soma** | | | **1494 ≤ 1500** |

  O Hikari 85 fica abaixo do Tomcat 300 do `stock`: threads esperam no Hikari (`connection-timeout` de 5 s); a fila real continua no
  Supavisor (pool real 45). Máximo acima de 3 em qualquer serviço exige refazer a conta (o README diz). A conta vai para o
  `Projeto/Backend/CLAUDE.md`.
- **Consumo de `reservation.wallet` com 2+ réplicas:** o `StockReleaseConsumer` já é idempotente por `holdId` (UPSERT de um statement com
  `WHERE stock.hold.status = 'held'`) e devolve a vaga com um `UPDATE eventcap` por evento **no fim da mesma transação**, contando só os
  holds que mudaram nela. Falta o **teste**: dois listeners no mesmo group, e dois consumidores concorrentes recebendo a mesma mensagem (como
  num rebalance), processando `ReservationCancelled`/`ReservationExpired` repetidos do mesmo `holdId` → o hold vira `released` uma vez e
  `eventcap.quant` sobe exatamente 1. O mesmo para o `CatalogReconciliationConsumer` (idempotente por `event_id`).
- O `stock` não tem job agendado (o reaper é do `reservation`), então não há job a coordenar entre réplicas.
- A [spec da saga](2026-10-29-saga-stock-wallet-design.md) ganha uma nota: D3 (b) implementada no cluster; o compose segue com (a).

## Slim como padrão (D7)

- `docker-compose.yml`: os 7 serviços Java passam a `image: gerenciador-eventos-<svc>.slim:latest` com `pull_policy: never` (sem
  `build:`): sem a imagem local o compose falha com "image not found" em vez de tentar o Docker Hub. O `frontend` segue com `build:`.
- `docker-compose.completa.yml` (novo, override): devolve o `build:` e a imagem completa a cada serviço, para depuração.
  `docker-compose.slim.yml` sai.
- Fluxo de build: `Projeto/slim/gerar-imagens-slim.sh` continua sendo o único jeito de gerar as `*.slim` (constrói a completa e gera a
  slim) e passa a usar o override `completa` no build gordo. Depois de mudar código: rodar o script para os serviços tocados.
- `run-e2e.sh`: as slim viram o padrão (o e2e continua conferindo o ID da imagem de cada serviço); `E2E_COMPLETA=1` usa a completa.
  `E2E_SLIM` sai.
- Atualizados: `CLAUDE.md` da raiz ("Rodar e testar": `up -d --build` vira "gerar as imagens + `up -d`"), `Projeto/slim/README.md`,
  `Projeto/Backend/CLAUDE.md`. Os overrides de carga e de autoescala são conferidos para não reintroduzir `build:` com nome slim.
- A mudança entra na `K8s` só **depois da R5** (merge da `Batch_2` atualizada antes do PR final): a medição da R5 não é afetada.

## e2e contra o cluster (D2 = b)

`E2E_K8S=1 Projeto/e2e/run-e2e.sh`:

- Cria um **cluster kind próprio** (`gerenciador-e2e`, sem `extraPortMappings`) e aplica o overlay `e2e`: a stack inteira com Mailpit no
  lugar do Gmail (`MAIL_HOST=mailpit`), as credenciais efêmeras que o `run-e2e.sh` já gera (nada do `.env` real de SMTP chega ao cluster),
  X baixo no `admission`. Confere o isolamento antes de aplicar (`kubectl kustomize` sem `MAIL_HOST` real, sem NodePort).
- O container auxiliar de curl vira um pod `e2e-cli` no namespace; `GW` passa a `http://<entrada do gateway>:8081` **(depende de D6)**.
  Os helpers que hoje fazem `docker exec` no `db`, `redpanda` e serviços passam a uma função com dois backends (`docker exec` /
  `kubectl exec`).
- Mesmos passos: cadastro (código lido do e-mail), evento, reserva, confirmação, replay de `catalog.event` e `reservation.wallet`, rajada
  4b, passos de queda (a) `stock` fora e (b) queda do `reservation` (`kubectl scale --replicas=0` / `kubectl delete pod` no lugar de
  `docker stop`).
- No fim, `kind delete cluster --name gerenciador-e2e` (`MANTER=1` mantém). O e2e em compose continua o padrão.

## Dry-run de carga com autoescala (aceites da #197 e #205)

Local, não é medição. Overlay `carga` + capacidades baixas (X e alvos de CPU reduzidos por env) para forçar subida e descida em minutos:

- `Projeto/k8s/registrar-replicas.sh`: a cada 2 s grava `ts,servico,prontas,desejadas,metrica` em `Projeto/k8s/saida/replicas-<ts>.csv`
  (gitignored, como o CSV do scaler) a partir de `kubectl get deploy,hpa`.
- Roteiro: rajada no `cas` acima do limiar → `reservation` e `stock` sobem; degrau oscilante → nenhuma descida no meio (#206); carga acima
  da capacidade de uma réplica do `backend` → nenhum pod sai do Service nem reinicia por probe (#207); rajada em `identity.notification`
  → `notification` até 3 e volta; fim da carga → tudo volta ao mínimo depois da janela.
- Critérios: overselling 0 no CAS (`ativas ≤ capacity` e `quant` coerente, por `cluster.sh psql`), nenhum e-mail duplicado (Mailpit do
  overlay), CSV com subidas e descidas, `adm:replicas` acompanhando o `readyReplicas` (D4), réplica nova recebendo tráfego (D6), linha de
  base de CPU/RAM do nó.

## Fora de escopo

- Mudar a regra 50/80 ou o X; rodada formal de medição (issue própria); nuvem ou mais de um host.
- Corrigir #206/#207 no compose/HAProxy (opção A); o scaler e o `lb` continuam como estão para quem usar o override.
- Studio/Kong/meta no cluster; persistência dos dados do cluster entre `down` e `up`.
- `run-tests.sh` contra o cluster; kit de carga apontando para o cluster além do necessário ao dry-run (a rodada formal decide).

## Entrega

Um PR `Issue-197` com base `K8s`, implementado em etapas do gerador (cada uma revisada antes da próxima) e três commits lógicos:
(1) slim padrão (D7); (2) cluster, manifests, probes, balanceamento (D6), e2e contra o cluster; (3) autoescala (KEDA/HPA, endpoint do
`adm:hot`, réplicas pela API), dry-run. Depois, o PR `Issue-205` (Hikari e min/máx do `stock`, teste dos consumers com N réplicas, dry-run
com o `stock` escalado). A ADR da autoescala ganha a seção "Opção D implementada" com as decisões e as diferenças (stand-by, req/s × CPU,
AppArmor, dados descartáveis).

## Riscos

- **Atraso da subida:** HPA sincroniza a cada 15 s, KEDA consulta a cada 5 s, mais o boot do JVM: a fila do `admission` absorve o
  intervalo (é o papel dela).
- **Init do Supabase fora do compose:** o `supabase/postgres` e o Supavisor dependem de arquivos e ordem de init; a etapa 2 começa por um
  `cluster.sh up` só com `db` + Supavisor e um `psql` pelo pooler antes dos serviços.
- **`kind load` lento:** 7 imagens de ~370–460 MB a cada build; o `carregar <svc>` recarrega só o que mudou.
- **Docker Desktop:** nunca matar à força (o `engine.sock` trava). O `cluster.sh down` usa só `kind delete cluster`.
