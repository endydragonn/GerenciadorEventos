# Cluster Kubernetes (kind) — issue #197

A stack inteira do compose num cluster **kind de um nó**: os 7 serviços Java, o `frontend`, Postgres + Supavisor, Redpanda, Redis e RabbitMQ, com
os mesmos nomes de Service e portas do compose (`backend:8081`, `reservation:8082`, `catalog:8083`, `identity:8084`, `admission:8085`,
`stock:8086`, `supabase-pooler:6543`, `redpanda:9092`, `redis:6379`, `rabbitmq:5672`). Spec:
[`docs/superpowers/specs/2026-10-29-k8s-autoescala-design.md`](../../docs/superpowers/specs/2026-10-29-k8s-autoescala-design.md).

**Estado (etapa 4b de 4):** cluster, manifests da stack inteira, probes, Secrets/ConfigMaps, `cluster.sh`, o e2e contra o cluster, as **duas trilhas de balanceamento** (`--trilha b1|c`, mais `nenhuma` como controle) e a
**autoescala** (KEDA + metrics-server por helm, 6 ScaledObjects (o `stock` é fixo em 1 réplica, #231), o endpoint `GET /internal/metrics/hot` do `admission` e a leitura das réplicas ativas pela API do Kubernetes), mais os overlays `completa` e `carga`.
O **dry-run de carga com autoescala** (etapa 4b: `registrar-replicas.sh`, `dry-run.sh`) está em "Dry-run de carga com autoescala" abaixo. Sem carga, tudo fica no mínimo (1 réplica).

## Avisos

- **Dados descartáveis.** O banco (PVC do `db`), o Redpanda e a chave do JWT vivem no nó do kind e **somem no `cluster.sh down`**. O banco do cluster
  nasce dos init scripts a cada cluster; nenhuma migração é aplicada. O `meu_banco` do compose não é tocado.
- **Derrube antes de qualquer medição no host.** O cluster inteiro roda no container do nó e disputa CPU e RAM com o gerador/host: ~1,5 GB do control
  plane e do kind, `db` ~1 GB, Supavisor/Redis/RabbitMQ ~0,8 GB, Redpanda 0,5–1 GB, 7 JVMs ~5 GB (limits: até ~11 GB no total). `cluster.sh down` antes da R5 e de
  qualquer rodada. Nunca mate o Docker à força (o `engine.sock` trava); o `down` usa só `kind delete cluster`. Para conviver com outra carga sem derrubar, dá
  para limitar a CPU do nó: `docker update --cpus <n> <nome>-control-plane` (opção, não usada no dry-run).
- **Portas do host:** o cluster principal publica 8081 (gateway) e 8088 (frontend). Enquanto ele existir, o compose completo não sobe (conflito nas duas
  portas). A infra de teste do compose (`up -d db redpanda redis rabbitmq`) e o `run-tests.sh` **não mudam** e podem coexistir (o cluster não publica 5433 nem portas de broker).
- **Lock Docker:** quem roda `up`/`down`/`carregar` segue o lock do projeto (`E:/TCC/.lock-docker`), como para o `docker compose`.
- **`--context` explícito:** todo `kubectl` do `cluster.sh` e do e2e passa `--context kind-<nome>`; nunca dependa do `current-context` (o `kind create cluster` o
  troca). Nos seus comandos manuais use `kubectl --context kind-gerenciador -n gerenciador ...`.

## Usar

```bash
# ferramentas no PATH: kind, kubectl (vem com o Docker Desktop) e helm (instala o KEDA e o metrics-server em toda trilha; kind e helm em E:\TCC\claude-tooling\bin na máquina do TCC)
# pré-requisitos: as 7 *.slim (Projeto/slim/gerar-imagens-slim.sh) e a imagem do frontend (docker compose -f Projeto/docker-compose.yml build frontend)
# + Projeto/.env e Projeto/supabase/.env (nada deles é impresso ou versionado)

Projeto/k8s/cluster.sh up --trilha nenhuma      # (ou --trilha c | --trilha b1) cria o cluster `gerenciador`, carrega as imagens, gera Secrets/ConfigMaps, aplica e espera
Projeto/k8s/cluster.sh status                   # nós, pods, serviços e PVCs
Projeto/k8s/cluster.sh psql -c 'select 1'       # atalhos de kubectl exec: psql (no db), rpk (no redpanda), redis-cli (REDISCLI_AUTH, nunca -a)
Projeto/k8s/cluster.sh carregar identity        # depois de regerar uma slim: recarrega a imagem no nó e reinicia o Deployment
Projeto/k8s/cluster.sh down                     # kind delete cluster
```

`SLIM_TAG` (default o do compose: shell > `Projeto/.env` > `latest`) escolhe a tag das `*.slim`, como no resto do projeto. **Até a R5 terminar, use sempre `SLIM_TAG=k8s`**
(gere com `SLIM_TAG=k8s Projeto/slim/gerar-imagens-slim.sh`; suba com `SLIM_TAG=k8s Projeto/k8s/cluster.sh up --trilha nenhuma`): as `*.slim:latest` são as da R5, sem o actuator dos 5 serviços (a 8090 não abre, a
startupProbe nunca passa), e regerá-las mudaria a configuração medida. O `cluster.sh` **aborta cedo** se a tag resolvida for `latest`; `K8S_PERMITIR_LATEST=1` é para depois da R5, quando as `:latest` forem regeradas com esta issue.
As imagens do projeto são obrigatórias no Docker local (o `up` aborta dizendo qual falta); as de infra ausentes o nó baixa do registro oficial. **Carga de imagens:** com o image store containerd do
Docker Desktop o `kind load docker-image` falha ("content digest ... not found") com imagem de registro multi-plataforma; por isso as do projeto (plataforma única) vão por `kind load` e as de infra por
`docker save --platform linux/<arch> | ctr images import` direto no nó. O **primeiro** `up` leva vários minutos só importando ~6 GB de imagens.

**Trilhas de balanceamento (`--trilha`, obrigatório).** O `Service` do Kubernetes (kube-proxy) balanceia **por conexão TCP**, e os 5 clientes HTTP dos serviços (`reservation-`, `stock-`, `catalog-`, `identity-`,
`admission-client`) mantêm as conexões abertas sob carga: **um pod novo não recebe tráfego** das conexões já abertas (7 arestas: gateway → identity, catalog, reservation, admission; catalog e reservation → stock; admission →
reservation). Sem `--trilha` o script aborta e lista as três opções (não existe subida "sem balanceamento" por acidente). A mesma base e a mesma imagem servem às três; as trilhas são **componentes kustomize**
(`overlays/trilha-b1`, `overlays/trilha-c`) que o `cluster.sh` soma, num kustomization temporário, ao overlay `base` ou `e2e`.

| `--trilha` | O que faz | Custo / limite |
|---|---|---|
| `nenhuma` | a base pura: Service L4 do Kubernetes. É o **controle** da comparação | uma réplica nova fica sem conexão do gateway enquanto o pool mantiver as que tem |
| `c` | **balanceamento no cliente** (módulo Maven `client-lb`, em `Projeto/Backend/client-lb`): `CLIENT_LB_ENABLED=true` nos 4 serviços que chamam serviços (gateway, catalog, reservation, admission), `*_SERVICE_URL` para Services **headless** (`reservation-pods`, `stock-pods`, `catalog-pods`, `identity-pods`, `admission-pods`: o DNS devolve só pods prontos), DNS round-robin + TTL de conexão de 15 s (`CLIENT_LB_TTL_MS`) + reuso FIFO, e o cache de DNS da JVM em 5 s por um `java.security` extra (`JAVA_TOOL_OPTIONS=-Djava.security.properties=...`) | balanceia **por conexão com TTL**, não por requisição; uma réplica nova entra em até TTL + 5 s; requisição em voo numa conexão com um pod que morre falha (não há retry depois de enviar). Sem proxy no caminho |
| `b1` | **malha de serviços com sidecar: Istio 1.31.1** (`istio/base` + `istio/istiod` por helm, versão fixa, sem `istioctl`; `istio/istiod-values.yaml`; repositório `https://blob.istio.io/istio-release/charts`). Sidecar só nos 7 serviços e no frontend; balanceamento **por requisição** (`LEAST_REQUEST`) pelas mesmas URLs do compose | **custo do Envoy:** um proxy por pod (request 100m/128Mi, sem limit de CPU, limit só de memória: 256Mi, e 1Gi no sidecar do `backend` (gateway), que com 256Mi, saturado, foi OOMKilled no dry-run da 4b; decisão de 2026-10-30) + o `istiod` (200m/512Mi); no pico da R4 (~4,9 mil req/s internas, 2 proxies por salto) a documentação do Istio sugere ~2–5 vCPU no host, e ~+3 ms p90 por salto. Medido na rodada formal, não aqui |

Por que Istio e não Linkerd: o Linkerd open source só publica releases `edge` desde fev/2024 (as stable exigem o Buoyant Enterprise); a regra do projeto é versão estável publicada há mais de duas semanas
(Istio 1.31.1 é de 2026-09-21; 1.31.2/1.31.3 são mais novas). **Como trocar de trilha:** `cluster.sh down` e `cluster.sh up --trilha <outra>` (o cluster é descartável).

**B1 em detalhe.** Retry da malha desligado (`meshConfig.defaultHttpRetryPolicy.attempts: 0`: o Istio repetiria 503/connect-failure, inclusive POST, contra o `disableAutomaticRetries` dos clientes);
`holdApplicationUntilProxyStarts` e `EXIT_ON_ZERO_ACTIVE_CONNECTIONS`; mTLS no default (PERMISSIVE). **Só o HTTP entre serviços passa pelo Envoy**: `excludeOutboundPorts` 5432, 6543, 9092, 6379, 5672, 443, 587 e 1025
(Postgres/Supavisor, Kafka, Redis, AMQP, API do Kubernetes, SMTP real e do Mailpit). `inject: "false"` em db, Supavisor, Redpanda, Redis, RabbitMQ, Mailpit e no Job `particoes` (um sidecar impede o Job de terminar) e
nos pods auxiliares do e2e. **Nada tira do ar uma réplica por saturação (#207):** sem `DestinationRule`, sem `outlierDetection`, sem health check ativo do Envoy; as probes não passam pelo sidecar
(`rewriteAppHTTPProbers: "false"`, `excludeInboundPorts: "8090"`), a readiness do sidecar (15021) tem o mesmo `failureThreshold` da do app e o proxy não tem limit de CPU. O sidecar é **nativo** (initContainer com
`restartPolicy: Always`); os `initContainers` `espera` rodam com `runAsUser: 1337` (o UID que o iptables do Istio não redireciona), para a espera testar o Service de verdade e não o listener do Envoy.

**C em detalhe.** `client-lb` soma ao pool de cada cliente (HttpClient 5) um `DnsResolver` round-robin (cada conexão nova resolve o host e roda os endereços), `ConnectionConfig.setTimeToLive` e `PoolReusePolicy.FIFO`; os
timeouts de hoje ficam. Sem retry próprio: o operador de conexão do HttpClient já tenta o próximo endereço quando um recusa (provado em teste). Desligado por padrão (`CLIENT_LB_ENABLED=false`): compose, testes e e2e em
compose usam a montagem de sempre. Ver `Projeto/Backend/client-lb/README.md` e o `Projeto/Backend/CLAUDE.md`, "Trilhas de balanceamento".

**Prova de balanceamento (passo 7 do e2e):** com GETs sequenciais passando pelo gateway, o `catalog` sobe a 2 réplicas e o e2e mede as conexões ESTABLISHED na porta 8083 **dentro do pod novo** (lidas do nó):
`b1` e `c` precisam ter pelo menos 1; `nenhuma` precisa ter 0 em 45 s (o controle mostra o problema). A etapa 4 repete isso com a autoescala e mede o custo de CPU por trilha.

**Variáveis:** `K8S_NOME` (`gerenciador` ou `gerenciador-e2e*`), `K8S_OVERLAY` (`base` | `e2e` | `completa` | `carga` | `rodada`), `K8S_DB_REMOTO` (`<ip>:<porta>`, db fora do cluster) e `K8S_POOLS` (`r5`) da rodada com o db remoto (#228, abaixo), `K8S_ESPERA` (900 s por espera), `K8S_PERMITIR_LATEST` (1 = aceita `SLIM_TAG=latest`; só depois da R5) e as da autoescala
(abaixo: `K8S_<SVC>_MIN`/`_MAX`, `K8S_DESCE_SEG`, `K8S_CPU_ALVO`, `AUTOESCALA_NOTIFICATION_LAG`). **Overlays:** `base` (Secrets REAIS, só no cluster `gerenciador`), `e2e` (Mailpit, credenciais efêmeras, sem NodePort), `completa` (as imagens completas
`gerenciador-eventos-<serviço>:latest`, que o compose constrói com `-f docker-compose.completa.yml build`, no lugar das `*.slim`: para depurar; Secrets REAIS, só em `gerenciador`; `SLIM_TAG` não vale) e `carga` (= `e2e` + `SEED_ENABLED=true` no gateway, para o
dry-run semear por `POST /bff/seed`; Secrets do override do e2e, **nunca o SMTP real**, e `cluster.sh guarda` os confere antes do apply). Segredos reais só vão para `base`/`completa` e nunca para um cluster `gerenciador-e2e*`. **Guarda de trilha:** o namespace leva o label
`trilha-balanceamento=<trilha>` ao nascer; um `up --trilha X` sobre um cluster que subiu com outra trilha **aborta** (misturaria as duas em silêncio e invalidaria a comparação): `down` e suba de novo.

## O que tem

| Pasta | Conteúdo |
|---|---|
| `kind.yaml` / `kind-e2e.yaml` | um nó, imagem `kindest/node` do kind v0.33.0 por digest; o principal publica NodePort 30081 → host 8081 e 30088 → 8088 (`0.0.0.0`); o do e2e não publica nada |
| `base/` | um arquivo por componente (kustomize); namespace `gerenciador` criado pelo `cluster.sh`; StatefulSets `db` e `redpanda` (PVC `standard`), Deployments dos 7 serviços, Supavisor, Redis, RabbitMQ e frontend, Job `particoes` |
| `overlays/e2e/` | a base + Mailpit, os Services do gateway e do frontend sem NodePort |
| `overlays/completa/`, `overlays/carga/` | imagens completas no lugar das slim (depuração); `e2e` + `SEED_ENABLED=true` (dry-run de carga) |
| `helm/` | valores dos charts do KEDA (`2.21.0`) e do metrics-server (`3.14.0`) |
| `overlays/trilha-c/`, `overlays/trilha-b1/` | componentes kustomize das trilhas de balanceamento (somados ao `base` ou ao `e2e` pelo `cluster.sh`); `istio/istiod-values.yaml` = valores do chart do Istio da `b1` |
| `seccomp-extras.txt` | syscalls somadas aos perfis do Slim ao copiá-los para o nó |
| `cluster.sh` | `up`, `guarda --trilha ...`, `renderizar --trilha ...`, `carregar`, `imagem`, `psql`, `rpk`, `redis-cli`, `db-teste` (db remoto), `status` (inclui ScaledObjects e HPAs), `down` |
| `overlays/rodada/`, `overlays/db-remoto/`, `overlays/pools-r5/`, `db-cliente.yaml`, `lib-db-cliente.sh`, `cluster.selftest.sh` | a rodada com o db remoto (#228): overlay da medição, componentes do banco fora do cluster e dos pools de réplica única, o pod cliente do banco (criado sob demanda) e o selftest do `cluster.sh` sem Docker nem cluster. Ver "Rodada com o db remoto" |
| `dry-run.sh`, `dry-run/`, `registrar-replicas.sh` | o dry-run de carga com autoescala (etapa 4b): roteiro, gerador k6 (`carga.js`, `semear.js`), análise dos critérios (`dry-run-analise.py`) e o registro de réplicas em CSV; saída em `saida/` (gitignored) |

- **Ambiente dos pods:** um Secret `env-<componente>` por componente, gerado do `docker compose config` (o mesmo ambiente que o compose entrega a cada container),
  em arquivos temporários (`mktemp`, `chmod 700`, apagados no fim). Lista fechada de componentes: `db`, `supabase-pooler`, `redis`, `rabbitmq` e os 7 serviços (nunca Studio, Kong nem meta). `db` e `supabase-pooler` recebem do compose o `supabase/.env` inteiro; o
  `cluster.sh` os filtra por uma **lista de chaves permitidas** (`PERMITIDAS` em `gerar_env`: as que o entrypoint do db e o Supavisor leem): uma chave nova no `supabase/.env` não entra no cluster até alguém
  acrescentá-la ali. Valor com quebra de linha é descartado com um aviso que traz só o NOME da chave.
  O que muda no cluster entra no `env` do manifest (vence o `envFrom`): porta de management, Hikari por réplica. Nada novo no `.env`. Com o overlay `e2e` (e para o dry-run) os Secrets
  saem do `docker compose config` COM o override do e2e (Mailpit, credenciais efêmeras, X baixo), e `cluster.sh guarda` os confere antes de qualquer `apply` (notification só fala com o
  Mailpit, sem senha; manifests sem NodePort).
- **`db`:** o `/etc/postgresql-custom` (volume nomeado no compose, que o Docker preenche com o conteúdo da imagem) é um `emptyDir` aqui; o `initContainer` `copiar-config` copia o conteúdo da imagem para
  ele (sem isso o Postgres sai com "configuration file ... contains errors"). O `command` do compose vira `args` (é o CMD; o ENTRYPOINT da imagem faz o init e troca de usuário), idem no Supavisor (tini).
- **Arquivos montados** (`functions-role.sql`, `_supabase.sql`, `pooler.sql`, `roles.sql`, `supabase-app-init.sql`, `Backend/db/init-scripts/`, `pooler-upsert.exs`): ConfigMaps criados
  pelo `cluster.sh` dos MESMOS arquivos do repo e montados (um a um, `subPath`) nos caminhos do compose.
- **Ordem de subida:** cada pod tem um `initContainer` `espera` (`busybox:1.37.0`, `timeout 5 nc -w 2`) com as dependências do compose. O `timeout` é necessário: o `-w` do busybox é só o timeout de CONEXÃO, e na trilha `b1` uma conexão aceita pelo sidecar de um pod que acabou de subir pode ficar presa para sempre (observado no `stock` e no `reservation`), travando o `Init`. O `notification` e o `stock` esperam, num `initContainer`
  (imagem do Redpanda, `rpk`), o tópico consumido ter 3 partições e só então fazem o seek "se o group não existe" (idempotente a cada pod); o `reservation` espera as 3 de `reservation.wallet`.
  O Job `particoes` leva `reservation.wallet` e `identity.notification` a 3 partições (no broker do cluster, que nasce a cada cluster: não é irreversível como no compose real).
- **Probes (requisito da #207):** porta de management própria **8090** (`MANAGEMENT_SERVER_PORT`, outro conector e outro pool de threads), nos 6 serviços HTTP; readiness só com o
  `readinessState` (banco, Redis, broker e downstream NÃO entram), liveness tolerante (`failureThreshold 12`, 2 min) e `startupProbe` de 90 s. A 8090 serve só `health`, sem detalhes e sem
  token, e não entra no Service. O `notification` (sem HTTP) repete o healthcheck do compose (conexão TCP estabelecida ao broker). Para isso o `actuator` entrou no `identity`, `catalog`,
  `reservation`, `stock` e `admission` com o HTTP de management **desligado por padrão** (`management.server.port=-1`): o compose não muda.
- **Término sem 5xx:** `preStop.sleep` de 20 s e `terminationGracePeriodSeconds` 60 nos 7 serviços; `maxSurge: 0, maxUnavailable: 1` nos Deployments com banco (o `carregar` com 1 réplica
  fica alguns segundos sem o serviço).
- **Seccomp:** os 7 serviços usam `Localhost` (`profiles/<svc>-seccomp.json` em `/var/lib/kubelet/seccomp/profiles/` do nó); os `initContainers` e a infra ficam com `RuntimeDefault`. **O perfil
  cru do Slim não serve**: ele lista só o que o processo usou durante o probe (~90 syscalls, medidas depois do `exec`), sem as que o runc usa para iniciar o container (`fstatfs`, `close_range`...: o container
  morre em `StartError ... fstatfs ...: operation not permitted`) e sem as que o JVM só usa em caminhos que o probe não exercitou (o catalog não viu `writev`/`shutdown` e o relay do outbox ficou em "Bootstrap broker
  disconnected"). O `cluster.sh` copia para o nó o perfil do Slim **somado** às syscalls de [`seccomp-extras.txt`](seccomp-extras.txt) (só acrescenta; uma por linha, com o motivo no cabeçalho).
  Resultado validado no e2e e no dry-run; uma syscall que nem o Slim nem a lista cobrem vira EPERM num caminho raro (repetir a carga com o perfil ligado antes de confiar nele). **Sonda da etapa 4b** (ver "Dry-run de carga com autoescala"): um perfil em `SCMP_ACT_LOG` não deixa rastro neste host (o kernel do Docker Desktop não tem auditoria: `dmesg` sem linha de seccomp), então a sonda foi uma **bissecção** (`docker run --security-opt seccomp=<perfil mesclado>` com `java -version` e `JAVA_TOOL_OPTIONS`); ela achou **`getegid`** ausente nos 7 perfis: sem ele o HotSpot não confirma `uid==euid` e `gid==egid`, trata o processo como privilegiado e **ignora a `JAVA_TOOL_OPTIONS` em silêncio** (nem o "Picked up JAVA_TOOL_OPTIONS" sai). Na trilha `c` isso desligava o `java.security` extra (cache de DNS de 5 s) na JVM do backend; `getegid` entrou no `seccomp-extras.txt`. **O `seccomp-extras.txt` libera `clone`
  sem filtro de flags** (o perfil do Slim já tinha `clone3`, que não dá para filtrar), então o perfil `Localhost` **não é estritamente mais restrito** que o `RuntimeDefault` nesse ponto (o default barra flags de namespace sem `CAP_SYS_ADMIN`). **AppArmor não se aplica neste host** (o
  kernel do Docker Desktop tem o módulo desligado, `/sys/module/apparmor/parameters/enabled = N`): os perfis `*-apparmor-profile` seguem versionados; num host Linux com AppArmor, o campo é
  `appArmorProfile: {type: Localhost, localhostProfile: ...}` no `securityContext` do container.
- **Recursos (provisórios):** `requests`/`limits` de CPU e memória em todos (obrigatório para o HPA por CPU da etapa 4). Os serviços Java: 500m/768Mi pedidos, 2 CPU/1,5Gi no máximo
  (admission e notification menores). A rodada formal calibra.
- **`identity-keys`:** PVC `ReadWriteOnce`; com um nó só todas as réplicas montariam o mesmo diretório (a chave continua criada com `link(2)`, #112).
- **Pool do Supavisor:** o teto é o do compose (`POOLER_MAX_CLIENT_CONN=1500`, pool real 45). No cluster: Hikari 200 por réplica no `reservation`, 50 no `identity` e no `catalog`,
  10 no `backend`, 3 no `notification` (o compose base mantém 1000 e 250) e **245 no `stock`, que é UMA réplica fixa** (#231, abaixo). Conta com até 3 réplicas por serviço escalável e +1 pod em término por
  serviço, e o `stock` uma vez só: 4×200 + 4×10 + 4×3 + 4×50 + 4×50 + 245 = **1497 ≤ 1500** (com 3 réplicas e nenhuma em término: 3×200 + 3×10 + 3×3 + 3×50 + 3×50 + 245 = 1184).
  **Máximo acima de 3 em qualquer serviço escalável exige refazer a conta:** o `cluster.sh` recusa `K8S_<SVC>_MAX` acima de 3 (o `cluster.selftest.sh` refaz a soma a partir do render e confere 1497).
  - **Por que o `stock` é fixo, com Hikari 245 e `Recreate` (#231).** A #205 o deixou escalar de 1 a 3 réplicas com Hikari 60 por réplica (para a soma caber nos 1500). O diagnóstico do K2
    ([`K2-diagnostico`](../carga/results/10-k8s-db-remoto/K2-diagnostico/README.md), diagnóstico 1 contra o teste A) mostrou que esse pool era o gargalo de origem da parada do braço b1 já a 20k: com 60 × 3 o pool
    esgotava (pending médio 142,6, máximo 237), a entrada do stock levava 729 ms no p50 e a entrada do gateway 5,6 s no p50, com parada; com Hikari 250 e 1 réplica (teste A) o degrau passou
    sem parada, o pending ficou perto de 0 e a entrada do gateway caiu para 160 ms no p50. As réplicas extras não compensam o pool menor: o CAS na linha quente do `eventcap` serializa as reservas
    de qualquer forma. Por isso o `stock` voltou a ser como no K1 e no compose, **1 réplica fixa, sem ScaledObject** (`replicas: 1` no Deployment). O `Recreate` tira o pod velho antes de subir o novo (com o
    `RollingUpdate` o pod em término seguraria conexões em cima das do novo e a soma passaria dos 1500: 1502 com 250, 1752 com o pod velho sobreposto). O **245** é o pool real do Supavisor **no overlay `rodada`**
    (`POOLER_DEFAULT_POOL_SIZE`; em base, e2e e carga é 45): conexão do Hikari acima disso só espera no Supavisor (no teste A, com o Hikari em 250, o `pg_conexoes` parou em 247), então 245 rende o mesmo e fecha a conta em 1497.
    O compose segue com 250. `K8S_STOCK_MIN`/`K8S_STOCK_MAX` deixaram de existir: o `cluster.sh` aborta se receberem valor diferente de 1.
    **Ressalva do `Recreate`:** o "sem sobreposição" vale para o rollout de template. Num `kubectl delete pod` ou numa eviction o ReplicaSet sobe o pod novo com o velho ainda em `Terminating` (grace de 60 s), e podem
    ficar 2×245 conexões por alguns segundos (a soma passa de 1500 nesse intervalo: acima do `POOLER_MAX_CLIENT_CONN` o Supavisor recusa a conexão de cliente nova, e o Hikari do pod novo tenta de novo).
    Num cluster de antes da #231, o `up` apaga o ScaledObject `stock` que sobrou (o `apply` não poda).

## Autoescala (etapa 4a, D5 = a: KEDA sem Prometheus)

`cluster.sh up` instala por **helm**, em toda trilha e com `--wait` (versões de chart fixas, publicadas há mais de duas semanas): **metrics-server** (chart 3.14.0, app 0.9.0; `--kubelet-insecure-tls`, porque o kubelet do kind serve um certificado autoassinado) e **KEDA**
(chart 2.21.0). O KEDA sobe com `KEDA_RESTRICT_SECRET_ACCESS=true` (P3): o operador e o metrics-apiserver só leem Secrets do namespace `keda`. O nome do value no chart é `permissions.operator.restrict.secret` (e `permissions.metricServer.restrict.secret`; ver `helm/keda-values.yaml`).
Antes do `apply` o script espera os APIServices `v1beta1.metrics.k8s.io` e `v1beta1.external.metrics.k8s.io`. Também o `up --so <app>` (o e2e planta um evento antes do resto) instala: o `kubectl apply -l` mapeia todos os recursos do render, então os CRDs precisam existir; o `up` completo seguinte repete o `helm upgrade --install`, que é idempotente.

| Serviço | Gatilho (ScaledObject) | Alvo |
|---|---|---|
| `reservation` | `metrics-api` em `GET http://admission.gerenciador.svc.cluster.local:8085/internal/metrics/hot` (valueLocation `rpm`), com o token só de métrica | `AverageValue` = `0,5 × ADMISSION_X_RPM` (o `cluster.sh` lê a MESMA variável que o `admission` recebe: o dry-run reduz as duas juntas): réplicas = `ceil(RPM ÷ (0,5 × X))` |
| `notification` | `kafka`: lag do group `notification` em `identity.notification` | `lagThreshold` = `0,5 × AUTOESCALA_NOTIFICATION_LAG` (50); `allowIdleConsumers: false` (no máximo uma réplica por partição, 3) |
| `backend`, `identity`, `catalog`, `admission` | `cpu` com `containerName` = o container da aplicação (o sidecar da trilha B1 não entra na conta) | `Utilization` 50 (`K8S_CPU_ALVO`) |

- **Mínimo e máximo** por serviço: `K8S_<SVC>_MIN` / `K8S_<SVC>_MAX` (`SVC` em maiúsculas: `BACKEND`, `IDENTITY`, `CATALOG`, `RESERVATION`, `NOTIFICATION`, `ADMISSION`), defaults **1/3** em todos. O `stock` **não está na lista** (#231): é 1 réplica fixa, sem ScaledObject. Inteiros de 1 a 3, `MIN <= MAX` (o teto 3 é o da
  conta do Supavisor); o `cluster.sh` gera o patch num kustomization temporário (sempre montado, também com `--trilha nenhuma`: o `guarda` renderiza exatamente o que será aplicado). Sem escala a zero. Infra e `frontend` ficam com 1 réplica fixa.
  Os Deployments dos 6 serviços escalados **não têm `spec.replicas`**: reaplicar o manifest não os volta para 1. O do `stock` tem `replicas: 1` e `strategy: Recreate`.
- **Descida suavizada (#206):** `behavior.scaleDown.stabilizationWindowSeconds` = `K8S_DESCE_SEG` (default 120; o HPA desce para a MAIOR recomendação da janela) e no máximo **1 pod por 60 s**; subida **sem janela**, no máximo **1 pod por 15 s**. `pollingInterval` 5 s.
- **Token só de métrica (P3).** A cada **cluster novo** o `cluster.sh` gera um valor aleatório (fora do `.env`, nunca impresso) e o guarda em dois Secrets `admission-metrics`: no namespace `keda` (lido pela `ClusterTriggerAuthentication` `admission-metrics`, parâmetro `apiKey`) e no
  `gerenciador` (o `admission` o lê como `ADMISSION_METRICS_TOKEN`). Um `up` repetido no mesmo cluster **reaproveita** o valor (senão o admission e o KEDA ficariam com valores diferentes: 401 e o reservation congelado). O `admission` aceita esse token **apenas** em `GET /internal/metrics/hot` e
  **nunca** o `INTERNAL_API_TOKEN` mestre nessa rota (o mestre não sai do namespace `gerenciador`); sem `ADMISSION_METRICS_TOKEN` a rota responde 401 a tudo. Sem a chave `adm:hot` o endpoint devolve `200` com `{"rpm":0}`, **nunca 404** (o KEDA, em erro, congela as réplicas); Redis fora = 503 (congela).
- **Kafka no namespace `keda`:** o Redpanda anuncia `redpanda:9092` (nome curto), que o scaler `kafka` (no namespace `keda`) não resolveria; o `cluster.sh` cria lá um Service `redpanda` do tipo `ExternalName` para o do namespace `gerenciador`, e o `bootstrapServers` usa o FQDN.
- **Réplicas ativas no `admission` (D4 = b).** `ServiceAccount` + `Role` (só `get` em `deployments/reservation`) + `RoleBinding` (`base/admission-rbac.yaml`); o admission é o **único** pod com `automountServiceAccountToken: true`. Um job de 2 s (`admission.k8s.replicas.enabled`, ligado só no manifest; desligado no compose)
  lê `status.readyReplicas` do Deployment `reservation` pela API do Kubernetes (`java.net.http.HttpClient`, token relido a cada chamada, CA do cluster num `SSLContext` próprio, timeout de 1 s) e grava `adm:replicas` com TTL de 15 s (a mesma chave do scaler da opção A; o script Lua e o fallback
  `ADMISSION_RESERVATION_REPLICAS` não mudam). Falha (API fora, 401/403, timeout, JSON inválido, `readyReplicas` ausente, 0 ou fora de `[1, K8S_RESERVATION_MAX]`) **não grava**: a chave expira e vale o fallback (o lado seguro: a fila liga mais cedo). `K8S_RESERVATION_MAX` é o mesmo valor do
  `maxReplicaCount` do reservation (uma fonte só, gerada pelo `cluster.sh`). O egress 443 (API do Kubernetes) já está fora do Envoy na trilha B1; não há NetworkPolicy no repo.
- **O que a autoescala prova aqui:** a subida e a descida sob carga, o "0 5xx na descida", o degrau sem descida (#206), o `adm:replicas` acompanhando o `readyReplicas` e a #207 (réplica saturada sem sair do Service) são o dry-run da 4b (abaixo); as asserções do e2e (abaixo) são as baratas.

- **Risco da `ClusterTriggerAuthentication` (cluster-wide).** A `admission-metrics` é um recurso de cluster: qualquer `ScaledObject` de qualquer namespace pode referenciá-la e usar o token de métrica (e ele só abre `GET /internal/metrics/hot`, que devolve um RPM).
  É aceitável num kind local de um dono só, com um namespace de aplicação; num cluster compartilhado trocar por `TriggerAuthentication` por namespace (o Secret teria de viver no namespace do `ScaledObject`, o que desfaz o `KEDA_RESTRICT_SECRET_ACCESS` do `keda`).
- **Falha da JVM antes do `main`.** Um erro nativo da partida (flag `-XX` inválida, "Error occurred during initialization of VM", classe principal ausente) vai para o stderr da JVM e pode não aparecer no `kubectl logs` de um pod com o perfil Seccomp errado;
  olhe o `lastState.terminated.exitCode`/`reason` do container (`kubectl get pod -o jsonpath='{.status.containerStatuses[*].lastState}'`). Na 4a isso foi atribuído a "o perfil nega a escrita nativa no stderr"; a causa real era o `getegid` ausente (a JVM só ignorava a `JAVA_TOOL_OPTIONS`), corrigida na 4b.

**e2e e autoescala.** O roteiro assume um pod por serviço, então o `run-e2e.sh` sobe com `K8S_<SVC>_MIN=MAX=1` (e `K8S_DESCE_SEG=5`) e `K8S_RESERVATION_MAX=2` (o teto que o admission aceita ler), e logo depois do `up` limita o ScaledObject do reservation a 1. O passo 7 (catalog em 2 réplicas) e o novo
passo 8 (reservation em 2) mexem no **min/max do ScaledObject**, nunca em `kubectl scale` (o HPA o desfaria); "stock fora" é um `kubectl scale` direto (o `stock` não tem HPA que o reviva). O passo 8 confere: 6 ScaledObjects `Ready` e 6 HPAs com a métrica lida (sem `<unknown>`),
`kubectl top pods`, `GET /internal/metrics/hot` com o token de métrica (200, corpo com `rpm`), sem token, com token errado e com o token de métrica numa outra rota (401), e `adm:replicas` = `readyReplicas` do reservation com 2 réplicas (e de volta a 1). Também: label da trilha no namespace, `java.security` extra ativo na
trilha `c` (o log do backend, COM o perfil Seccomp, traz `Picked up JAVA_TOOL_OPTIONS: -Djava.security.properties=...`; a prova por `/proc/<pid>/environ` e por um pod efêmero SEM perfil continua como controle independente) e, na `b1`, `attempts: 0` no mesh config e `EXIT_ON_ZERO_ACTIVE_CONNECTIONS` no `istio-proxy`.

## e2e contra o cluster

```bash
SLIM_TAG=k8s E2E_K8S=1 E2E_K8S_TRILHA=nenhuma Projeto/e2e/run-e2e.sh   # (ou =c | =b1) cluster próprio `gerenciador-e2e`, sem porta no host; MANTER=1 mantém
```

Mesmos passos do e2e do compose (cadastro com o código lido do Mailpit, evento, reserva, confirmação, seek, rajada do modo fila, as faltas do `stock` e do `reservation`, replay), mais as
asserções de probes/management. Equivalentes: `stock` fora = `kubectl scale`; pausa do `stock` = `SIGSTOP`/`SIGCONT` no PID, a partir do nó (`crictl inspect`); queda do `reservation` =
SIGKILL no PID, a partir do nó, como o `docker stop -t 0` (desvio do texto da spec, que diz `kubectl delete pod --grace-period=0 --force`: o `delete` desmonta também a rede do pod, os pedidos pendentes no `stock` pausado são descartados e não sobra órfã nenhuma para a compensação; o SIGKILL deixa o kubelet reiniciar o container no mesmo pod); o "2º up" do `notification` = recriar o pod e ler o log do `initContainer` `seek`; o replay roda num pod Maven efêmero (baixa as dependências a
cada rodada). O isolamento (Secrets e NodePort) é conferido por `cluster.sh guarda` antes de criar qualquer coisa.

## Dry-run de carga com autoescala (etapa 4b)

**Local, não é medição.** Prova que a autoescala sobe e desce de verdade sob carga, uma trilha por vez (`b1`, `c` e `nenhuma` como controle), com o mesmo roteiro e os mesmos parâmetros.
É um cluster kind próprio (`gerenciador-e2e-dry`, overlay `carga` = `e2e` + `SEED_ENABLED=true` no gateway, Mailpit no lugar do SMTP real, credenciais efêmeras, sem porta no host) e capacidades
baixas para forçar subida e descida em minutos. O gerador de carga é o do kit (`k6`, num pod `grafana/k6` dentro do cluster, **limitado a 6 CPUs**: ele divide o nó, e o host tem 16 núcleos, com
a stack, o KEDA e o resto), em modelo aberto e em fases (`dry-run/carga.js`; `dry-run/semear.js` semeia pelo `POST /bff/seed`).

```bash
SLIM_TAG=k8s Projeto/k8s/dry-run.sh --trilha c        # (ou b1 | nenhuma) ~35 a 40 min; sob o lock Docker; Projeto/.env e Projeto/supabase/.env presentes (nada é impresso)
Projeto/k8s/registrar-replicas.sh [arquivo.csv]       # o registro de réplicas avulso (o dry-run já o chama); roda até SIGTERM
```

Saída em `Projeto/k8s/saida/dry-run-<trilha>-<ts>/` (gitignored): `replicas.csv` (`ts,servico,prontas,desejadas,metrica` a cada 2 s, mais uma linha `adm-replicas` por ciclo), `fases.csv`, `no-stats.csv`
(`docker stats` do nó), `k6-*.log`, `resumo.txt` (o relatório dos critérios) e os insumos da análise. O código de saída é 0 só com **todos** os critérios ok (`dry-run/dry-run-analise.py`, stdlib).
Ao sair, **também em falha** (trap), o roteiro reaplica o overlay `e2e` (sem `SEED_ENABLED`), confere `POST /bff/seed` → **404** e apaga o cluster (`MANTER=1` mantém).

**Parâmetros do dry-run** (todos por env; `dry-run.sh` documenta o resto): `ADMISSION_X_RPM=600` (o alvo do reservation no KEDA é `0,5 × X` = 300 RPM por réplica), `K8S_CPU_ALVO=20` (% do request de
CPU: o alvo de produção é 50), `K8S_DESCE_SEG=45` (a janela de descida de produção é 120 s: reduzida para caber em minutos), `AUTOESCALA_NOTIFICATION_LAG=4` (lagThreshold 2), min/max 1/3, evento quente
de capacidade 30, 60 usuários. As janelas de 1 pod por 60 s na descida e por 15 s na subida são as de produção.

**Roteiro** (cada marco vai para `fases.csv`):

| Fase | O que faz | O que prova |
|---|---|---|
| `parado` / `ocioso` | `docker stats` do nó só com a infra do kind + KEDA + metrics-server + Redpanda, e com a stack inteira parada | linha de base de CPU/RAM do nó |
| `fundo` | tráfego leve contínuo até o fim (5 req/s: 1 reserva e 2 leituras) | um 5xx em qualquer descida de réplica aparece aqui |
| `A` | 20 req/s de reservas no `cas` num evento só (RPM >> 300) por 300 s | `reservation` (RPM do `adm:hot`) sobe e o `stock` fica em 1 réplica fixa (#231); a réplica nova do reservation recebe conexões |
| `B` | a mesma carga oscilando (12 s pico, 8 s vale) por 180 s | **nenhuma descida do reservation enquanto o degrau dura** (#206: a janela segura a descida) |
| `descida` | só o fundo | o reservation volta ao mínimo depois da janela; o stock segue em 1 |
| `C1` | backend **fixo em 1 réplica** (min=max=1) e leitura (`GET /bff/events/<id>`) a 3000 req/s, que o SATURA | nenhum pod sai do Service, nenhum reinício por probe, sem `Unhealthy`/`Killing` por probe (#207) |
| `C` | a mesma leitura (1500 req/s) com a autoescala solta | o backend sobe e volta |
| `D` | 15000 pedidos de e-mail em `identity.notification` por 3 produtores | `notification` sobe a 3 réplicas (3 partições) e volta; nenhum e-mail duplicado nem perdido |
| `E` | espera o mínimo em todos; SQL | overselling 0 no CAS; `adm:replicas` volta a 1 |

**Critérios automáticos:** o tráfego de **fundo** rodou do início ao fim (o `RESUMO` do k6 existe e `req >= 0,9 × 5 req/s × (fim da fase E − início do fundo)`; sem isso o critério seguinte passaria vácuo, e o roteiro também confere que o k6 do fundo está vivo antes de cada fase);
0 5xx (e falha de conexão) na janela de 60 s depois de cada descida **dos serviços que o fundo exercita por HTTP** (`backend`, `admission`, `reservation`, `stock`, `catalog`); as descidas de `identity` (o gateway valida o JWT sem estado, o fundo só a chama quando o cache do `creator_name` expira, ~1 a 3 vezes por janela) e de `notification` (consumer Kafka, sem HTTP; cobre-o o critério dos e-mails) são só informadas;
desvio de relógio nó × host de até 5 s (o `ERRO5XX` leva o relógio da VM do Docker, o CSV o do host; medido no início e no fim); overselling 0 no CAS **com o evento esgotado** (`reservas ativas == capacidade`, `eventcap.quant` coerente com os holds, por `cluster.sh psql`);
UM e-mail por pedido (o conjunto `dryrun-<sufixo>-1..15000`: nenhum duplicado, perdido nem estranho; o Mailpit sobe com `MP_MAX_MESSAGES=50000`, o padrão dele guarda só 500); CSV com subidas e descidas;
`adm:replicas` igual ao `readyReplicas` do reservation fora das transições (de 4 s antes a 8 s depois da mudança: 2 s do job + 2 s do registro + latência do `kubectl`); o degrau B **oscilou de verdade** (a métrica gravada é o `averageValue` do HPA, RPM ÷ réplicas, **por pod**; o vale tem de ser baixo o bastante para o HPA, sem a janela de estabilização, ter descido: `mínimo × prontas ≤ (prontas − 1) × 0,5 × X`; sem isso a ausência de descida seria vácua); réplica nova do reservation com conexão na 8082 (b1: até 15 s; c: até 35 s = TTL 15 s + cache de DNS 5 s + folga;
`nenhuma`: informativo); a fase C1 saturou a réplica (p95 >= 300 ms **e** CPU do container `backend` >= 1500m, de `kubectl top pod --containers`, **ou** iterações descartadas: sem isso a prova da #207 seria vácua; 300 ms fica com ~3× de folga entre o p95 da fase C, a mesma leitura sem saturar (6 a 103 ms), e o menor p95 saturado da C1 (564 ms); o limiar anterior de 100 ms cabia no ruído de um nó compartilhado; a fase C teve de 86 a 1016 iterações descartadas sem saturar, então quem discrimina é o p95); `restartCount` 0 (o máximo por pod e container entre o fim da fase C e o fim do dry-run: um pod desescalado depois da C não some do critério); CPU/RAM do nó por fase.

**Limites do dry-run (leia antes de citar os números):** é um nó de kind num host compartilhado, com o k6 no mesmo nó; os tempos de subida dependem de a imagem já estar no nó. A "réplica nova com
conexão" é medida pelas conexões ESTABLISHED na 8082 dentro do pod novo (lidas do nó). No controle (`nenhuma`) o Service L4 também leva **conexões novas** ao pod novo quando a carga abre muitas, então
o número do controle é informativo; a prova do controle **ocioso** (uma conexão só, que não migra) é o passo 7 do e2e. O CPU do HPA é `Utilization` sobre o **request** do container (500m), por isso o alvo
reduzido (20%) dispara com ~100m por pod.

### Resultado do dry-run (2026-10-30; local, não é medição)

Cada coluna é um cluster novo, criado pelo `dry-run.sh`; todos os critérios automáticos (acima) passaram na `c` e na `b1`; a `nenhuma` falha **só** o do degrau oscilante (abaixo). `c` rodou **sem** o teto de 6 CPUs no k6 (rodada 1; o teto entrou depois do achado do sidecar na `b1`); `b1` e `nenhuma` rodaram com ele. As três rodadas são da versão anterior do analisador: reavaliadas com a versão atual sobre as evidências arquivadas, a `c` e a `b1` continuam passando e a `nenhuma` falha só o critério do degrau oscilante (limiar corrigido na volta 3: o vale de 213 por pod × 3 prontas ≈ 639 > 600, o HPA sem a janela também teria ficado em 3 réplicas, então a #206 **não fica provada** nessa rodada; a prova da #206 vem da `c` e da `b1`, onde a métrica chegou a 0); dois critérios novos **valem só a partir da próxima execução**, porque os dados não existiam: o desvio de relógio nó × host e o `top` por container (a coluna de CPU abaixo é do **pod**, que na `b1` soma o `istio-proxy`).

| | `c` | `b1` | `nenhuma` (controle) |
|---|---|---|---|
| máximo de réplicas (reservation / stock / notification / backend; o `stock` era escalável nesta rodada, #231 o fixou em 1) | 3 / 3 / 3 / 3 | 3 / 3 / 3 / 3 | 3 / 3 / 3 / 3 |
| descidas no caminho do fundo (backend/admission/reservation/stock/catalog) e 5xx nas janelas de 60 s depois delas | 12 descidas, **0** 5xx | 13 descidas, **0** 5xx | 14 descidas, **0** 5xx |
| descidas de `identity` e `notification` (4 por rodada; o fundo não as exercita por HTTP: só informadas) | 4, 0 5xx | 4, 0 5xx | 4, 0 5xx |
| degrau B: descidas do reservation (métrica por pod: mínimo a pico) | 0 (métrica 0 a 568, prontas 3) | 0 (métrica 0 a 526, prontas 3) | 0 (métrica 213 a 575, prontas 3) |
| `adm:replicas` != `readyReplicas` fora das transições | 0 de 656 | 0 de 662 | 0 de 688 |
| réplica nova do reservation: 1ª conexão na 8082 | 16 s (janela 35 s) | 1 s (janela 15 s) | nenhuma em 60 s (o Service L4 não levou conexão ao pod novo; informativo, não é critério: sob carga o L4 também leva conexões novas, e a prova do controle ocioso é o passo 7 do e2e) |
| C1 (backend em 1 réplica): CPU máx. do **pod** (na `b1` pod + `istio-proxy`) / p95 / descartadas / 5xx | 1749m / 1029 ms / 18196 / 0 | 2620m / 2585 ms / 110313 / 0 | 1594m / 564 ms / 5421 / 0 |
| `restartCount` / `Unhealthy` de probe / `Killing` por probe na fase C | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |
| e-mails (15000 pedidos): mensagens / destinatários distintos | 15000 / 15000 | 15000 / 15000 | 15000 / 15000 |
| overselling (evento quente, capacidade 30): ativas / `quant` | 30 / 0 | 30 / 0 | 30 / 0 |
| `POST /bff/seed` depois do dry-run | 404 | 404 | 404 |

### Dry-run da #231 (2026-11-01; stock fixo em 1 réplica)

- **`c`:** passou em todos os critérios.
- **`b1`:** passou na repetição. Na 1ª rodada a réplica nova do reservation recebeu a primeira conexão na 8082 fora da janela de 15 s; na repetição, em 12 s. Um vigia no pod novo mostrou o sidecar com conexões de entrada ~4 s depois de o pod aparecer: o critério fica na borda da janela.
- **`nenhuma` (controle L4):** falhou nas duas rodadas, sempre no critério "0 5xx nas janelas de 60 s depois de cada descida". Em cada rodada houve 2 respostas 503 em **leitura**, ~20 s depois de cada descida do catalog (o backend desceu junto, então o critério conta em dobro).
  - **O problema já existia antes da #231.** A mesma `nenhuma` no código anterior, com o stock escalável (`Issue-228`, 334adf4), também deu 503 em leitura nas descidas do catalog e do backend: 4 linhas `ERRO5XX`, 12 contagens nas janelas.
  - Causa provável: o gateway chama o catalog pelo Service L4 e mantém keep-alive com o pod do catalog que está saindo. O `CatalogIndisponivelHandler` devolve então 503.
  - Na `b1` e na `c`, que balanceiam por requisição ou no cliente, houve 0 5xx nas descidas.
- **Nas três trilhas:** o stock ficou em 1 réplica o dry-run inteiro, com overselling 0 (30 de 30, `quant` 0) e os 15.000 e-mails chegaram sem duplicar.

**CPU e RAM do nó** (`docker stats` do contêiner do kind; CPU em % de um núcleo, 100 = 1 núcleo; amostra a cada ~3 s; a **linha de base** é o que o cluster custa antes de qualquer carga):

| Fase | `c` CPU média (máx.) / RAM | `b1` CPU média (máx.) / RAM | `nenhuma` |
|---|---|---|---|
| parado (control plane + KEDA + metrics-server + Redpanda) | 14% (19%) / 1347 MiB | 16% (24%) / 1522 MiB | 15% (21%) / 1335 MiB |
| ocioso (stack inteira, sem requisição) | 48% (106%) / 3596 MiB | 53% (91%) / 4263 MiB | 78% (182%) / 3602 MiB |
| A (rajada de reservas) | 144% (561%) / 5043 MiB | 178% (404%) / 5901 MiB | 161% (724%) / 4993 MiB |
| B (degrau oscilante) | 101% (249%) / 4994 MiB | 124% (348%) / 5862 MiB | 110% (238%) / 4991 MiB |
| C (leitura, C1 + autoescala solta) | 668% (1007%) / 6598 MiB | 963% (1124%) / 7683 MiB | 785% (1394%) / 6952 MiB |
| D (15000 e-mails) | 203% (379%) / 6604 MiB | 279% (482%) / 7546 MiB | 251% (352%) / 6982 MiB |

(O custo do sidecar da `b1` aparece como +0,2 GiB de RAM com o nó parado, +0,7 GiB com a stack ociosa e mais CPU do proxy sob carga. O CPU do nó na fase C inclui o k6, que divide o nó; a `c` rodou sem o teto de CPU do k6, que por isso pode ter pesado mais nela.)

**Achados do dry-run** (todos corrigidos na 4b, com a evidência nos relatórios):
- **`istio-proxy` OOMKilled na `b1` (1ª rodada).** Com o gateway saturado (3000 conexões de leitura numa réplica), o sidecar estourou `proxyMemoryLimit: 256Mi` duas vezes: o pod saiu do Service (`readyReplicas` 0), 10506 falhas de conexão e `restartCount` 2.
  Decisão de 2026-10-30 (opção c): o sidecar do **`backend`** tem `proxyMemoryLimit` **1Gi**; **todos os demais ficam em 256Mi**, como a spec; o request segue 128Mi em todos e não há limit de CPU. Na 2ª rodada, com 1Gi em todos, mesma carga, sem OOM e sem 5xx. É um achado sobre dimensionamento, não sobre probes.
  O pico do sidecar do gateway foi só **estimado** (~260 a 280Mi, por diferença do pod do backend entre a `b1` e as outras trilhas, amostras de 5 s do metrics-server, que suavizam picos), e a memória do Envoy cresce com as conexões simultâneas (aqui, as 3000 do k6). **Verificação pendente (próxima janela de host, depois da R5):** rodada de validação da `b1` com a (c) (`SLIM_TAG=k8s Projeto/k8s/dry-run.sh --trilha b1`), medindo o sidecar sozinho com `kubectl top pod --containers`.
- **`getegid` faltando nos 7 perfis Seccomp** (ver "Seccomp"): sem ele a `JAVA_TOOL_OPTIONS` era ignorada em silêncio. Na trilha `c` da 1ª tentativa (perfil antigo), a réplica nova do reservation só recebeu conexão depois de **109 s**; com o `getegid` (rodada final), 16 s, dentro do TTL de 15 s + cache de DNS de 5 s (20 s). O dry-run da trilha `c` agora confere o `Picked up JAVA_TOOL_OPTIONS` no log do backend, COM o perfil.
- **Mailpit guarda só 500 mensagens por padrão** (`MP_MAX_MESSAGES`): subiu para 50000 no overlay `e2e`, senão a conferência de e-mails duplicados/perdidos seria vácua.
- **Os `stages` do k6 são rampas lineares**, não patamares: o roteiro usa um estágio curto de subida e um patamar com o mesmo alvo.

### Dry-run com o `stock` fixo em 1 réplica (#231, no lugar do `stock` escalado da #205)

A #205 deixou o `stock` autoescalável de 1 a 3 réplicas (Hikari 60 por réplica) e o dry-run exigia que ele chegasse a 2+ réplicas. A #231 **reverteu isso no cluster**: o `stock` é 1 réplica fixa, Hikari 245,
`Recreate` e sem ScaledObject, como no K1 e no compose. O motivo e a evidência estão em "Pool do Supavisor" (acima) e em [`K2-diagnostico`](../carga/results/10-k8s-db-remoto/K2-diagnostico/README.md): o pool 60 × 3
era o gargalo da parada do b1 a 20k, e com 250 × 1 (teste A) o degrau passou. O dry-run da #197 (comando abaixo, sem parâmetro novo) continua o mesmo:

```bash
SLIM_TAG=k8s Projeto/k8s/dry-run.sh --trilha nenhuma     # (ou b1 | c)
```

Critérios do `dry-run-analise.py` que mudam ou ficam (código de saída 0 só com todos ok): **o `stock` fica em 1 réplica o dry-run inteiro** (`stock fica em 1 replica o dry-run inteiro, fora da autoescala`: máximo de
`prontas` 1, 0 subidas e 0 descidas; no lugar do antigo `stock chega a 2+ réplicas` da #205) e **overselling 0 no CAS** (com o evento esgotado: reservas ativas == capacidade, `eventcap.quant` coerente com os holds `held`
debitados; `resumo.txt`, arquivos `overselling*.txt` e `quant-incoerente.txt`). O teste `StockReleaseConsumerTest` (2+ réplicas do consumer, infra real) continua valendo como prova de que o `stock` tolera réplicas, mas o cluster não as usa.
Histórico: no dry-run da #197 (tabela acima, 2026-10-30, antes da #231) o `stock` ainda chegou a **3** réplicas nas três trilhas, com overselling 0 (30 ativas para capacidade 30, `quant` 0); a coluna "stock" dessa tabela não vale mais.

## Rodada com o db remoto (issue #228)

Repete no cluster a rodada da #227 (configuração da R5 com o Postgres numa máquina dedicada): o banco sai do host também no Kubernetes, para medir o efeito. Esta seção é o **harness**; os
resultados entram por outro PR (`Projeto/carga/results/10-k8s-db-remoto/`). Banco remoto: Dell (Ubuntu, `supabase/postgres:17.6.1.136`, `max_connections` 300) em **`192.168.68.50:5433`**, rede de 1 Gb; o
firewall dele aceita a 5433 **só** da origem `192.168.68.64` (o host: o tráfego dos pods do kind sai com NAT pelo host). O pooler (Supavisor) e os serviços ficam no cluster.

**Peças** (nada disto muda o cluster sem as variáveis novas):

| Peça | O que faz |
|---|---|
| `overlays/db-remoto/` (componente) | com `K8S_DB_REMOTO=<ip>:<porta>` (IPv4, sem default): remove o StatefulSet `db` (e o PVC dele; o `cluster.sh` não cria os ConfigMaps `db-sql` e `db-app-init`) e troca o Service `db` por um **Service sem selector** (porta 5432) com uma **EndpointSlice** `db-remoto` para o IP e a porta dados. O Supavisor (`POSTGRES_HOST=db`, `POSTGRES_PORT=5432`), o `espera` dele (`nc db 5432`) e os Secrets não mudam |
| `overlays/rodada/` | a base (Secrets **reais**, NodePort 30081/30088 -> host 8081/8088, o gerador da rede local chega no gateway) + `SEED_ENABLED=true` no gateway + **pool real do Supavisor 245** + (db no cluster) `max_connections=300`. `K8S_OVERLAY=rodada`; o `cluster.sh` recusa no cluster `gerenciador-e2e*` |
| `overlays/pools-r5/` (componente) | `K8S_POOLS=r5`: Hikari e workers de **réplica única** da R5 (tabela abaixo). Só com a autoescala travada em 1: `K8S_<SVC>_MAX` passa a valer 1 por padrão e um valor maior **aborta** (a conta do Supavisor só fecha com 1 réplica de cada) |
| `db-cliente.yaml` + `lib-db-cliente.sh` | pod cliente `db-cliente` (mesma imagem do `db`, `sleep infinity`, `sidecar.istio.io/inject: "false"`), criado **sob demanda** (não está no kustomize; some com o `down`). As senhas vêm dos Secrets `env-db`/`env-supabase-pooler` **chave a chave** (`secretKeyRef`, só `APP_DB_USER`, `APP_DB_PASSWORD`, `POSTGRES_PASSWORD`, `POSTGRES_DB` e `POOLER_TENANT_ID`: nunca o Secret inteiro) como variáveis de ambiente **do pod** e são lidas dentro do `sh -c` do `kubectl exec`: nunca em argumento nem em log. A lib decide local × remoto **uma vez por processo** (`db_psql_preparar`: `get sts/db --ignore-not-found`; erro ao perguntar **aborta**, nunca vira "remoto") e garante o pod uma vez; depois cada psql é 1 `kubectl exec` |
| `cluster.sh psql` | db no cluster: o `exec` no `sts/db` de sempre; db remoto: o pod `db-cliente` |
| `cluster.sh db-teste` | conectividade antes da carga (abaixo) |
| `cluster.sh guarda` / `renderizar --trilha X` | `guarda` lê o render e imprime o que será aplicado (Hikari, workers, `maxReplicaCount`, pool real, `max_connections`, NodePort, `SEED_ENABLED`; com o db remoto aborta se o render não tem a EndpointSlice do IP; o resto do render, sem StatefulSet `db` e com o Service sem selector, o `cluster.selftest.sh` confere); `renderizar` imprime os manifests do `up` (kustomize, sem a tag das slim) |

`K8S_DB_REMOTO` só vale com overlay de Secrets reais (`base`, `completa`, `rodada`): os overlays `e2e`/`carga` têm credenciais efêmeras que o banco remoto não conhece. O `up` guarda o modo no label
`db-remoto` do namespace e aborta se um cluster existente subiu no outro modo (o `apply` não poda: ficariam o StatefulSet `db` e a EndpointSlice juntos): trocar de modo = `down` + `up`.

### Os três blocos (planejados como K1, K2, K0; rodaram K0, K1, K2, cada um num cluster novo)

Todos com `K8S_OVERLAY=rodada`, `SLIM_TAG=k8s` (até a R5 terminar), `ADMISSION_X_RPM` 56000 (o do compose, pelo Secret) e a escada do `Deg-59713` rodada pelo gerador (EnzoAMD) contra
**`192.168.68.64:8081`**.

| | **K1** | **K2** | **K0** (controle) |
|---|---|---|---|
| Trilha | `nenhuma` | `b1` | `nenhuma` |
| Banco | Dell `192.168.68.50:5433` | Dell | StatefulSet `db` no cluster |
| Autoescala | travada em 1 (`K8S_<SVC>_MAX=1`) | 1 a 3 (o `stock` fixo em 1) | travada em 1 |
| `K8S_DB_REMOTO` | `192.168.68.50:5433` | `192.168.68.50:5433` | (vazio) |
| `K8S_POOLS` | `r5` | (vazio) | `r5` |
| `max_connections` | 300 (o Dell já roda assim) | 300 (Dell) | 300 (o overlay `rodada` põe no `db` do cluster) |
| Pool real do Supavisor (`POOLER_DEFAULT_POOL_SIZE`) | 245 | 245 | 245 |
| Hikari reservation / stock / identity / catalog / backend / notification | 1000 / 250 / 100 / 100 / 10 / 3 | 200 / 245 / 50 / 50 / 10 / 3 (245 vale a partir da #231; a medição de 2026-11-01 usou o `stock` com 60 × 3) | 1000 / 250 / 100 / 100 / 10 / 3 |
| Workers do admission (`ADMISSION_WORKER_CONCURRENCY`) | 32 | 32 por réplica (do Secret, sem override) | 32 |
| Soma no Supavisor (limite 1500) | 1463 | 1497 (3 réplicas + 1 em término por serviço escalável, stock fixo em 1; conta da seção "Pool do Supavisor"; a medição de 2026-11-01, com o `stock` 60 × 3, somava 1492) | 1463 |

K1 e K0 têm a configuração e os pools do **bloco 1 da R5 / #227** (`carga/rpm/docker-compose.rpm.yml`: `max_connections` 300, pool real 245; Hikari e workers do `docker-compose.yml`), então só o lugar do banco
os separa (K1 x K0 = efeito do db remoto no cluster). O K2 mantém os pools do cluster (os da conta de 1497, #231, com o `stock` fixo em 1 réplica e Hikari 245) com o pool real em 245.

### Comandos, por bloco (no host; `Projeto/.env` e `Projeto/supabase/.env` presentes, nada deles é impresso)

```bash
# K1 (db no Dell, 1 réplica por serviço, pools da R5)
K8S_OVERLAY=rodada SLIM_TAG=k8s K8S_POOLS=r5 K8S_DB_REMOTO=192.168.68.50:5433 Projeto/k8s/cluster.sh up --trilha nenhuma
# K2 (db no Dell, trilha b1, autoescala 1..3, pools do cluster)
K8S_OVERLAY=rodada SLIM_TAG=k8s K8S_DB_REMOTO=192.168.68.50:5433 Projeto/k8s/cluster.sh up --trilha b1
# K0 (db dentro do cluster, 1 réplica por serviço, pools da R5)
K8S_OVERLAY=rodada SLIM_TAG=k8s K8S_POOLS=r5 Projeto/k8s/cluster.sh up --trilha nenhuma

Projeto/k8s/cluster.sh guarda --trilha nenhuma      # (com as mesmas variáveis do up) mostra o que será aplicado, antes de criar qualquer coisa
```

**Ordem de cada bloco:**

1. `cluster.sh down` do bloco anterior (um cluster novo por bloco; **antes de qualquer medição no host**, o cluster disputa CPU e RAM) e o `up` acima.
2. **Só nos blocos com o Dell:** `Projeto/k8s/cluster.sh db-teste` (abaixo). Código diferente de 0 = não rode a carga.
3. **Limpeza do Dell entre blocos:** o cluster novo nasce com Redpanda, Redis e RabbitMQ vazios, mas o banco do Dell guarda as linhas do bloco anterior. Com `CARGA_K8S=kind-gerenciador`, `make limpar-banco`
   (`Projeto/carga/README.md`, "Modo cluster") dá o `TRUNCATE ... RESTART IDENTITY` nos 5 schemas pelo pod `db-cliente`, apaga a chave do JWT do PVC `identity-keys` (a do cluster novo também: ids reciclados),
   zera Redis e filas e prova o JWKS. Rode-a depois do `up` e antes da escada; no K0 o banco já nasce vazio (a limpeza é só a sonda e o estado do broker).
4. No host, em paralelo ao gerador: `CARGA_K8S=kind-gerenciador make coleta-host ROTULO=<bloco>`; ao fim da escada, `CARGA_K8S=kind-gerenciador make verificar-rpm PREFIXO=Deg-59713` (overselling e órfãs).
5. No gerador: a escada do `Deg-59713` contra `192.168.68.64:8081` (kit do gerador, sem mudança).

### Teste de conectividade (`cluster.sh db-teste`)

Roda **antes da carga**, só com o db remoto (lê o IP:porta da EndpointSlice aplicada). Um pod do cluster (o `db-cliente`) faz **o TCP até `<ip>:<porta>`** (firewall/NAT) e **um `SELECT 1` pelo Supavisor**
(`supabase-pooler:6543`, usuário `<app>.<tenant>`, o caminho dos serviços). Códigos de saída: **0** ok | **10** o pod não abre TCP até o banco (firewall aceita só a origem `192.168.68.64`? NAT?) | **11** TCP ok, o
`SELECT 1` pelo Supavisor falhou (`kubectl --context kind-gerenciador -n gerenciador logs deploy/supabase-pooler`) | **12** não deu para rodar o teste (cluster sem db remoto, pod que não sobe).

### Limites

- Validado só offline (`cluster.selftest.sh`, render do kustomize, selftests do kit com `kubectl` falso): nada disto subiu num cluster ainda. A primeira execução real é a do bloco K1.
- O `db-cliente` e o pod `limpar-chave` do `limpar-banco.sh` usam `supabase/postgres:17.6.1.136` e `busybox:1.37.0` (o `up` carrega a do `db-cliente` no nó; a do `busybox` já vem com o `espera`).
- O worker pool do `admission` no cluster vem do Secret `env-admission` (o default do compose base, 32 **por réplica**): no K2, com 3 réplicas, são 96 workers contra o teto de 32 holds simultâneos que a conta do compose
  supõe (no compose com autoescala cada réplica ganha 10). Não foi mudado aqui; quem ler o K2 deve saber disso.
- A latência dos passos da saga (`saga.timing`) só cobre os pods que ainda existem no fim do bloco (`kubectl logs`): no K2 o pod que a autoescala removeu leva os logs junto.

## Pendências anotadas (etapa 4b)

- **Dry-run de carga:** no cluster o `/actuator/health` do gateway na 8081 responde **401** (só a 8090 serve `health`, por `management.server.port`; no compose a 8081 o serve). Afeta o
  `Projeto/carga/limpar-banco.sh` (que só loga o health) e qualquer kit que bata em `BASE_URL/actuator/health` pela 8081: apontar para a 8090 (o Service não a expõe: `kubectl port-forward` ou o IP do pod) ou ignorar.
- **e2e:** o segundo `up` (depois do `--so redpanda`) recarrega no nó todas as imagens de novo (~6 GB, minutos); pular `carregar_imagens` quando a imagem já está no nó é uma melhoria fora desta etapa.
- **e2e:** não há checagem de que a porta da API dos 5 serviços internos não serve `/actuator/health` (o `InternalTokenFilter` responde 401 a tudo sem o token real do `.env`, que o e2e não lê: seria vacuosa);
  a garantia é de construção (`management.server.port` separada, desligada por padrão).
- **e2e da trilha `c` depois do `getegid`:** a asserção nova do `run-e2e.sh` (o log do backend, COM o perfil, traz `Picked up JAVA_TOOL_OPTIONS`) **não foi rodada** (acabou o prazo do host); o dry-run da trilha `c` conferiu o mesmo log (`javasec_log=1`). Comando: `SLIM_TAG=k8s E2E_K8S=1 E2E_K8S_TRILHA=c Projeto/e2e/run-e2e.sh`.
- **Perfis Seccomp do compose:** `Projeto/slim/perfis/*-seccomp.json` não têm `getegid` (o `cluster.sh` o soma só no cluster). Não foram tocados (R5); quem aplicar esses perfis no compose e ligar `JAVA_TOOL_OPTIONS` deve acrescentá-lo.
- **A trilha `c` do dry-run rodou sem o teto de CPU do k6** (6 CPUs): a 1ª rodada dela foi antes do achado do sidecar da `b1`; as outras duas rodaram com o teto. Os critérios passaram na `c` e na `b1` (a `nenhuma` falha só o do degrau oscilante); refazer a `c` com o teto é um `SLIM_TAG=k8s Projeto/k8s/dry-run.sh --trilha c` (~35 min; **próxima janela de host, depois da R5**).
- **Validação da `b1` com o limite de sidecar da decisão (c)** (backend 1Gi, demais 256Mi; ver "`istio-proxy` OOMKilled"): **ainda não rodada** (decidido sem host). Próxima janela de host, depois da R5: `SLIM_TAG=k8s Projeto/k8s/dry-run.sh --trilha b1`, medindo o sidecar sozinho (`kubectl top pod --containers`), para conferir que o do gateway não é OOMKilled com 1Gi e que nenhum outro sidecar chega a 256Mi. O e2e da `b1` confere o limite por Deployment (`run-e2e.sh`), mas também não foi rodado depois da mudança.
- **Overlay `completa`** continua só renderizado, não subido (exige `K8S_PERMITIR_LATEST=1`: as `:latest` completas podem ser as de uma rodada em curso; **não construir a `:latest` durante a R5**).
- **Roteiro e analisador da v2 sem execução completa** (sem host na ocasião): o `dry-run.sh` novo (checagem do fundo vivo, desvio de relógio, `top --containers`, limpeza no Ctrl+C) só passou por `bash -n`; o analisador novo foi rodado só sobre as evidências arquivadas. A próxima execução do dry-run (a `c` com o teto de CPU do k6, ou a da #205) é quem exerce o roteiro novo.
