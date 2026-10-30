# Cluster Kubernetes (kind) — issue #197

A stack inteira do compose num cluster **kind de um nó**: os 7 serviços Java, o `frontend`, Postgres + Supavisor, Redpanda, Redis e RabbitMQ, com
os mesmos nomes de Service e portas do compose (`backend:8081`, `reservation:8082`, `catalog:8083`, `identity:8084`, `admission:8085`,
`stock:8086`, `supabase-pooler:6543`, `redpanda:9092`, `redis:6379`, `rabbitmq:5672`). Spec:
[`docs/superpowers/specs/2026-10-29-k8s-autoescala-design.md`](../../docs/superpowers/specs/2026-10-29-k8s-autoescala-design.md).

**Estado (etapa 3 de 4):** cluster, manifests da stack inteira, probes, Secrets/ConfigMaps, `cluster.sh`, o e2e contra o cluster e as **duas trilhas de balanceamento** (`--trilha b1|c`, mais `nenhuma` como controle). **Ainda não
existem:** KEDA/HPA e o `metrics-server` (etapa 4; o único helm de hoje é o do Istio na trilha `b1`), o endpoint
`/internal/metrics/hot`, a leitura das réplicas pela API do Kubernetes e o dry-run de carga. As réplicas são **fixas em 1**.

## Avisos

- **Dados descartáveis.** O banco (PVC do `db`), o Redpanda e a chave do JWT vivem no nó do kind e **somem no `cluster.sh down`**. O banco do cluster
  nasce dos init scripts a cada cluster; nenhuma migração é aplicada. O `meu_banco` do compose não é tocado.
- **Derrube antes de qualquer medição no host.** O cluster inteiro roda no container do nó e disputa CPU e RAM com o gerador/host: ~1,5 GB do control
  plane e do kind, `db` ~1 GB, Supavisor/Redis/RabbitMQ ~0,8 GB, Redpanda 0,5–1 GB, 7 JVMs ~5 GB (limits: até ~11 GB no total). `cluster.sh down` antes da R5 e de
  qualquer rodada. Nunca mate o Docker à força (o `engine.sock` trava); o `down` usa só `kind delete cluster`.
- **Portas do host:** o cluster principal publica 8081 (gateway) e 8088 (frontend). Enquanto ele existir, o compose completo não sobe (conflito nas duas
  portas). A infra de teste do compose (`up -d db redpanda redis rabbitmq`) e o `run-tests.sh` **não mudam** e podem coexistir (o cluster não publica 5433 nem portas de broker).
- **Lock Docker:** quem roda `up`/`down`/`carregar` segue o lock do projeto (`E:/TCC/.lock-docker`), como para o `docker compose`.
- **`--context` explícito:** todo `kubectl` do `cluster.sh` e do e2e passa `--context kind-<nome>`; nunca dependa do `current-context` (o `kind create cluster` o
  troca). Nos seus comandos manuais use `kubectl --context kind-gerenciador -n gerenciador ...`.

## Usar

```bash
# ferramentas no PATH: kind, kubectl (vem com o Docker Desktop) e, só para a trilha b1 (e para a etapa 4), helm (kind e helm em E:\TCC\claude-tooling\bin na máquina do TCC)
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
| `b1` | **malha de serviços com sidecar: Istio 1.31.1** (`istio/base` + `istio/istiod` por helm, versão fixa, sem `istioctl`; `istio/istiod-values.yaml`; repositório `https://blob.istio.io/istio-release/charts`). Sidecar só nos 7 serviços e no frontend; balanceamento **por requisição** (`LEAST_REQUEST`) pelas mesmas URLs do compose | **custo do Envoy:** um proxy por pod (request 100m/128Mi, sem limit de CPU, 256Mi de memória) + o `istiod` (200m/512Mi); no pico da R4 (~4,9 mil req/s internas, 2 proxies por salto) a documentação do Istio sugere ~2–5 vCPU no host, e ~+3 ms p90 por salto. Medido na rodada formal, não aqui |

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

**Variáveis:** `K8S_NOME` (`gerenciador` ou `gerenciador-e2e*`), `K8S_OVERLAY` (`base` | `e2e`), `K8S_ESPERA` (900 s por espera), `K8S_PERMITIR_LATEST` (1 = aceita `SLIM_TAG=latest`; só depois da R5). Segredos reais só vão para o overlay `base`
e nunca para um cluster `gerenciador-e2e*`.

## O que tem

| Pasta | Conteúdo |
|---|---|
| `kind.yaml` / `kind-e2e.yaml` | um nó, imagem `kindest/node` do kind v0.33.0 por digest; o principal publica NodePort 30081 → host 8081 e 30088 → 8088 (`0.0.0.0`); o do e2e não publica nada |
| `base/` | um arquivo por componente (kustomize); namespace `gerenciador` criado pelo `cluster.sh`; StatefulSets `db` e `redpanda` (PVC `standard`), Deployments dos 7 serviços, Supavisor, Redis, RabbitMQ e frontend, Job `particoes` |
| `overlays/e2e/` | a base + Mailpit, os Services do gateway e do frontend sem NodePort |
| `overlays/trilha-c/`, `overlays/trilha-b1/` | componentes kustomize das trilhas de balanceamento (somados ao `base` ou ao `e2e` pelo `cluster.sh`); `istio/istiod-values.yaml` = valores do chart do Istio da `b1` |
| `seccomp-extras.txt` | syscalls somadas aos perfis do Slim ao copiá-los para o nó |
| `cluster.sh` | `up`, `guarda --trilha ...`, `carregar`, `imagem`, `psql`, `rpk`, `redis-cli`, `status`, `down` |

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
- **Ordem de subida:** cada pod tem um `initContainer` `espera` (`busybox:1.37.0`, `nc`) com as dependências do compose. O `notification` e o `stock` esperam, num `initContainer`
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
  Resultado validado no e2e; uma syscall que nem o Slim nem a lista cobrem vira EPERM num caminho raro (repetir a carga com o perfil ligado antes de confiar nele). **O `seccomp-extras.txt` libera `clone`
  sem filtro de flags** (o perfil do Slim já tinha `clone3`, que não dá para filtrar), então o perfil `Localhost` **não é estritamente mais restrito** que o `RuntimeDefault` nesse ponto (o default barra flags de namespace sem `CAP_SYS_ADMIN`). **AppArmor não se aplica neste host** (o
  kernel do Docker Desktop tem o módulo desligado, `/sys/module/apparmor/parameters/enabled = N`): os perfis `*-apparmor-profile` seguem versionados; num host Linux com AppArmor, o campo é
  `appArmorProfile: {type: Localhost, localhostProfile: ...}` no `securityContext` do container.
- **Recursos (provisórios):** `requests`/`limits` de CPU e memória em todos (obrigatório para o HPA por CPU da etapa 4). Os serviços Java: 500m/768Mi pedidos, 2 CPU/1,5Gi no máximo
  (admission e notification menores). A rodada formal calibra.
- **`identity-keys`:** PVC `ReadWriteOnce`; com um nó só todas as réplicas montariam o mesmo diretório (a chave continua criada com `link(2)`, #112).
- **Pool do Supavisor:** o teto é o do compose (`POOLER_MAX_CLIENT_CONN=1500`, pool real 45). No cluster: Hikari 200 por réplica no `reservation`, 60 no `stock`, 50 no `identity` e no `catalog`,
  10 no `backend`, 3 no `notification` (o compose base mantém 1000 e 250). Conta com até 3 réplicas por serviço e +1 pod em término por serviço: 4×200 + 4×10 + 4×3 + 4×50 + 4×50 + 4×60 = **1492 ≤ 1500**
  (com 3 réplicas e nenhuma em término: 1119). **Máximo acima de 3 em qualquer serviço exige refazer a conta** (hoje as réplicas são fixas em 1).

## e2e contra o cluster

```bash
SLIM_TAG=k8s E2E_K8S=1 E2E_K8S_TRILHA=nenhuma Projeto/e2e/run-e2e.sh   # (ou =c | =b1) cluster próprio `gerenciador-e2e`, sem porta no host; MANTER=1 mantém
```

Mesmos passos do e2e do compose (cadastro com o código lido do Mailpit, evento, reserva, confirmação, seek, rajada do modo fila, as faltas do `stock` e do `reservation`, replay), mais as
asserções de probes/management. Equivalentes: `stock` fora = `kubectl scale`; pausa do `stock` = `SIGSTOP`/`SIGCONT` no PID, a partir do nó (`crictl inspect`); queda do `reservation` =
SIGKILL no PID, a partir do nó, como o `docker stop -t 0` (desvio do texto da spec, que diz `kubectl delete pod --grace-period=0 --force`: o `delete` desmonta também a rede do pod, os pedidos pendentes no `stock` pausado são descartados e não sobra órfã nenhuma para a compensação; o SIGKILL deixa o kubelet reiniciar o container no mesmo pod); o "2º up" do `notification` = recriar o pod e ler o log do `initContainer` `seek`; o replay roda num pod Maven efêmero (baixa as dependências a
cada rodada). O isolamento (Secrets e NodePort) é conferido por `cluster.sh guarda` antes de criar qualquer coisa.

## Pendências anotadas (etapa 4)

- **Etapa 4 (dry-run de carga):** no cluster o `/actuator/health` do gateway na 8081 responde **401** (só a 8090 serve `health`, por `management.server.port`; no compose a 8081 o serve). Afeta o
  `Projeto/carga/limpar-banco.sh` (que só loga o health) e qualquer kit que bata em `BASE_URL/actuator/health` pela 8081: apontar para a 8090 (o Service não a expõe: `kubectl port-forward` ou o IP do pod) ou ignorar.
- **e2e:** o segundo `up` (depois do `--so redpanda`) recarrega no nó todas as imagens de novo (~6 GB, minutos); pular `carregar_imagens` quando a imagem já está no nó é uma melhoria fora desta etapa.
- **e2e:** não há checagem de que a porta da API dos 5 serviços internos não serve `/actuator/health` (o `InternalTokenFilter` responde 401 a tudo sem o token real do `.env`, que o e2e não lê: seria vacuosa);
  a garantia é de construção (`management.server.port` separada, desligada por padrão).
