# Design: autoescala no Kubernetes (kind + HPA/KEDA) e `stock` escalável (issues #197 e #205)

Data: 2026-10-29. Branch de integração `K8s` (de `Batch_2`, `b116c55`). Base: a opção D da
[ADR da autoescala](../../adr/Architecture-Decision-Autoescala.md), os aceites da #197 e da #205 e dois follow-ups da Rodada 4 que
o mecanismo novo **não pode repetir**: #207 (health check que tira do ar réplica saturada) e #206 (sinal `adm:hot` instantâneo). As
correções de #206/#207 para o compose e o HAProxy (opção A) ficam fora: aqui elas viram requisito do cluster.

> **Rascunho.** D2, D4, D5 e D6 aguardam decisão do usuário. As seções marcadas **(depende de Dn)** trazem a recomendação e serão
> fechadas quando a decisão chegar; o resto já vale.

Ponto de partida no código (`b116c55`): 7 serviços Spring Boot (`backend` = gateway, `identity`, `catalog`, `reservation`, `stock`,
`admission`, `notification`) + `frontend` (nginx), todos em compose, com a autoescala da opção A num override
(`docker-compose.autoescala.yml`: HAProxy `lb` + `scaler.sh` no host, regra 50/80, stand-by com `weight 0`). O `admission` lê as réplicas
ativas do `reservation` da chave `adm:replicas` (escrita pelo scaler, TTL 15 s; sem ela vale `ADMISSION_RESERVATION_REPLICAS`) no script Lua
do contador e publica o RPM do evento mais quente em `adm:hot`. As imagens `*.slim` (#177) existem, mas entram só pelo override
`docker-compose.slim.yml`.

## Decisões

| # | Pergunta | Decidido |
|---|---|---|
| D1 | Onde o cluster roda | **kind** (usuário, 2026-10-29). Sobe e cai por script, config versionada; dá para limitar a CPU do nó. kind v0.33.0 e helm v3.22.0 em `E:\TCC\claude-tooling\bin` (fora do repo; versão e hash no ledger). |
| D2 | O que vai para o cluster | **pendente.** Recomendado: (a) híbrido, ver "Topologia". |
| D3 | Stand-by da regra 50/80 | **(a) HPA com alvo de uso 0,5** (usuário, 2026-10-29): a réplica sobe a 50% já recebendo tráfego; não existe réplica sem tráfego. O gatilho da fila a 0,8 por evento continua no `admission`. |
| D4 | De onde o `admission` lê as réplicas ativas | **pendente.** Recomendado: (a) DNS do Service headless, ver "Réplicas ativas". |
| D5 | Fonte da métrica de escala | **pendente.** Recomendado: (a) KEDA sem Prometheus, ver "Autoescala". |
| D6 | Balanceamento com keep-alive (Service é L4) | **pendente.** Recomendado: (a) `max-keep-alive-requests` finito no cluster, ver "Balanceamento". |
| D7 | Slim como padrão | **(a)** (usuário, 2026-10-29): o compose base usa as `*.slim` sem `build:`; a completa vai para um override inverso; o build vira um script único. |

Decidido nesta spec, sem pendência (solução única ou trade-off desprezível): cluster de **um nó**; manifests em **kustomize** (embutido
no `kubectl`), helm só para instalar KEDA e metrics-server com versão de chart fixa; Services com os **mesmos nomes** do compose; o
ambiente dos pods gerado do `docker compose config`; probes numa **porta de management** própria; janela de estabilização do HPA para
a descida; AppArmor documentado como não aplicável neste host.

## Topologia (depende de D2)

**Recomendado (a), híbrido**, confirmado por spike em 2026-10-29 (pod resolve e conecta por nome em `redis:6379`, `redpanda:9092`,
`db:5432` com o nó do kind ligado à `gerenciador-net`):

- **No cluster** (namespace `gerenciador`): `backend`, `identity`, `catalog`, `reservation`, `stock`, `admission`, `notification` e
  `frontend`.
- **No compose**, como hoje: `db`/`supabase-pooler`/Kong/Studio/meta, `redpanda`, `redis`, `rabbitmq`, os one-shots
  (`notification-seek`, `stock-seek`). O nó do kind entra na rede `gerenciador-net` (`docker network connect`) e os pods chegam aos
  containers pelo nome, via CoreDNS → resolver do nó → DNS embutido do Docker.
- O `run-tests.sh` não muda (testa contra o compose, nunca contra o cluster). O `meu_banco` e os segredos continuam onde estão.
- Compose completo e cluster **não convivem**: o cluster ocupa as portas 8081 e 8088 do host enquanto existe. Para usar o cluster sobe-se
  só a infra do compose (`up -d db supabase-pooler redpanda redis rabbitmq ...`, lista exata no README).

Alternativa (b), stack inteira no cluster: portar Supabase, Supavisor, Kong e Studio para manifests, com outro banco separado do
`meu_banco`. Muito mais trabalho sem ganho para o aceite.

## Cluster e ciclo de vida

- `Projeto/k8s/kind.yaml` (versionado): um nó `control-plane`, imagem `kindest/node` fixada por digest (a padrão do kind v0.33.0);
  `extraPortMappings` host 8081 → NodePort 30081 (`backend`) e host 8088 → NodePort 30088 (`frontend`), em `0.0.0.0` (o gerador da
  rede local chega na 8081 como hoje). O contrato do Flutter (porta e paths) não muda.
- `Projeto/k8s/cluster.sh` (bash, roda no Git Bash e no Linux), subcomandos:
  - `up`: cria o cluster (se não existe), liga o nó à rede do compose, instala metrics-server e KEDA (helm, versões fixas, `--wait`),
    copia os perfis Seccomp para o nó, carrega as imagens (`kind load docker-image` das 7 `*.slim` + frontend), gera os Secrets e
    aplica os manifests (`kubectl apply -k`), esperando os Deployments ficarem `Available`.
  - `carregar <svc...>`: recarrega só as imagens dadas e reinicia os Deployments (ciclo de desenvolvimento).
  - `down`: `kind delete cluster` (é o que roda antes da R5 e de qualquer medição no host).
  - `status`: nós, pods, HPAs/ScaledObjects e réplicas.
- Ferramentas: `kind`, `helm` e `kubectl` no `PATH` (o `kubectl` vem com o Docker Desktop; os dois primeiros em `E:\TCC\claude-tooling\bin`
  na máquina do TCC). O script aborta com mensagem clara se faltar um deles ou se a infra do compose não estiver no ar.
- **Custo do cluster na medição:** o nó do kind é um container; o `docker stats` dele parado (control plane + CoreDNS + kube-proxy +
  metrics-server + KEDA, sem os serviços) é registrado no dry-run como linha de base. Limitar a CPU do nó (`docker update --cpus`) fica
  documentado como opção, não é o padrão. A rodada formal de medição é issue própria (fora de escopo).
- **Lock:** quem roda `cluster.sh up`/`down`/`carregar` segue o lock Docker do projeto (`E:/TCC/.lock-docker`), como para `docker compose`.

## Manifests

`Projeto/k8s/base/` (kustomize): por serviço um `Deployment` e um `Service` `ClusterIP` com o **mesmo nome** do serviço no compose
(`backend`, `identity`, `catalog`, `reservation`, `stock`, `admission`) e a mesma porta; assim os defaults de `*_SERVICE_URL` e o
`proxy_pass` do frontend continuam valendo sem nenhuma env nova. `backend` e `frontend` são `NodePort` (30081/30088). O `notification`
não tem Service (só consome).

- **Imagens:** `gerenciador-eventos-<svc>.slim:latest`, `imagePullPolicy: Never` (vêm do `kind load`). A completa é usada trocando a tag
  por um patch de kustomize (`overlays/completa`), para depuração.
- **Ambiente:** o `cluster.sh` gera **um Secret por serviço** a partir do `docker compose config --format json` (o mesmo ambiente que o
  compose entrega ao container, resolvido do `Projeto/.env`; arquivo temporário, nunca impresso nem versionado, apagado no fim; mesma
  técnica do `slim/gerar-imagens-slim.sh`) e o manifest usa `envFrom: secretRef`. O que muda no cluster entra no `env` do manifest
  (vence o `envFrom`): porta de management, keep-alive (D6), Hikari por réplica, workers do `admission`, URLs que apontavam para o `lb`.
- **`identity-keys`:** um `PersistentVolumeClaim` `ReadWriteOnce` (o `local-path` do kind). Com um nó só, todas as réplicas do `identity`
  montam o mesmo diretório e a chave continua sendo criada com `link(2)` (#112): nenhuma mudança de código.
- **Recursos:** `requests`/`limits` de CPU e memória em todos os pods (obrigatório para o HPA por CPU, se D5 = a, e para o kube-scheduler
  não espremer o nó). Valores provisórios no dry-run; a rodada formal calibra.
- **Seccomp:** `securityContext.seccompProfile: {type: Localhost, localhostProfile: profiles/<svc>-seccomp.json}`, com os perfis gerados
  pelo Slim (`Projeto/slim/perfis/`) copiados para `/var/lib/kubelet/seccomp/profiles/` do nó pelo `cluster.sh`. Serviço sem perfil
  gerado fica com `RuntimeDefault` e o README diz qual.
- **AppArmor:** **não aplicável neste host.** O kernel do Docker Desktop (`6.18.33.2-microsoft-standard-WSL2`) tem o módulo desligado
  (`/sys/module/apparmor/parameters/enabled = N`, conferido no spike). Os perfis do Slim ficam versionados e o README mostra o campo
  `appArmorProfile` para um host Linux com AppArmor; a ADR registra a diferença em relação ao aceite.

## Probes (requisito da #207)

A causa da #207 foi o health check competir com o tráfego: `GET /actuator/health` nas mesmas 300 threads do Tomcat, `inter 2s fall 3`, e o
`lb` tirando do ar o último servidor vivo. No cluster:

- **Porta de management própria** em todos os serviços: `MANAGEMENT_SERVER_PORT=8090` só no manifest (o compose não muda). O Spring Boot
  sobe um contexto filho com **outro conector e outro pool de threads**, então a probe não espera atrás das 300 threads ocupadas.
- **Readiness = só o estado do ciclo de vida.** `management.endpoint.health.probes.enabled=true` e
  `management.endpoint.health.group.readiness.include=readinessState` (explícito: banco, Redis, broker e downstream **não** entram; um
  Postgres lento sob carga não pode esvaziar o Service). `readinessProbe` em `/actuator/health/readiness`, `periodSeconds 5`,
  `timeoutSeconds 3`, `failureThreshold 6` (30 s sem resposta antes de sair do Service).
- **Liveness tolerante.** `/actuator/health/liveness` (só `livenessState`), `timeoutSeconds 5`, `failureThreshold 12`, `periodSeconds 10`
  (2 min sem resposta antes do restart): um pod saturado e vivo não é reiniciado, o que agravaria a saturação.
- **Startup.** `startupProbe` na readiness, `periodSeconds 2`, `failureThreshold 45` (90 s para o JVM subir), para a liveness não matar um
  boot lento sob CPU disputada.
- **Exposição:** a porta 8090 não entra no Service (só o kubelet a usa). `management.endpoints.web.exposure.include` continua `health`.
- **Prova (aceite):** no dry-run com carga acima da capacidade de uma réplica, nenhum pod sai do Service nem reinicia por probe
  (`kubectl get events` sem `Unhealthy`/`Killing` de probe; `restartCount` 0), e os 5xx do cliente vêm só da aplicação, se vierem.
- **"0 ativas" (2º item da #207):** quem conta réplicas ativas é o `admission` (D4) e o HPA; com a readiness presa ao ciclo de vida, um pod
  saturado continua pronto e contado.

## Balanceamento (depende de D6)

Achado: o `Service` do Kubernetes (kube-proxy) balanceia **por conexão TCP**, não por requisição. Os serviços internos têm
`server.tomcat.max-keep-alive-requests=-1` de propósito (reuso máximo de conexão), e os clientes (`*-client`, pools Apache de até 400
conexões) mantêm as conexões abertas sob carga: um pod novo **não receberia tráfego** das conexões já abertas, o mesmo problema que a opção
A resolveu com o HAProxy em modo HTTP. O gateway usa o default do Tomcat (100 requisições por conexão), então a entrada do k6 já gira.

**Recomendado (a):** só no cluster, `SERVER_TOMCAT_MAX_KEEP_ALIVE_REQUESTS` finito (valor inicial 1000, por env no manifest) nos serviços
internos. O servidor fecha a conexão a cada N requisições (`Connection: close` na resposta; o cliente Apache não reusa e abre outra) e o
kube-proxy sorteia de novo. Custo: um handshake TCP a cada N requisições. Sem código; compose intacto. A prova é o dry-run: depois de
uma subida, a réplica nova recebe requisições (contadas no access log ou na métrica de CPU) em até alguns segundos.

Alternativas: (b) um L7 (ingress-nginx/Envoy) na frente e entre os serviços, que repete o salto e o ponto único do HAProxy; (c) Service
headless + balanceamento no cliente nas libs `*-client`, que exige código e teste novos.

## Autoescala (depende de D5)

**Comum a qualquer opção:**

- Mínimo e máximo por serviço configuráveis (patch de kustomize gerado pelo `cluster.sh` a partir de env `K8S_<SVC>_MIN`/`_MAX`, defaults
  1/3 em todos, inclusive o `stock`, #205). Sem escala a zero.
- **Descida suavizada (requisito da #206):** `behavior.scaleDown.stabilizationWindowSeconds` configurável (`K8S_DESCE_SEG`, default
  120 s, o valor que a R4 usou por config). O HPA desce só para a **maior** recomendação da janela: uma amostra baixa isolada no meio de um
  degrau sustentado não derruba réplica. `scaleDown.policies`: no máximo 1 pod por 60 s. Subida sem janela (`stabilizationWindowSeconds:
  0`), no máximo 1 pod por 15 s (o equivalente ao "uma ação por vez" do scaler). No KEDA isso vai em
  `advanced.horizontalPodAutoscalerConfig.behavior`.
- **Prova da #206:** teste de unidade não se aplica (o HPA não é código nosso); a prova é o dry-run com carga em degrau que oscila
  (rajadas com pausas curtas): nenhuma descida enquanto o degrau dura, com o CSV mostrando a métrica oscilando e as réplicas estáveis.
- **D3 (a):** alvo de uso 0,5. Para o `reservation`, com a métrica = RPM do evento mais quente e o tipo `AverageValue` (o HPA divide pelo
  número de pods): alvo = `0,5 × ADMISSION_X_RPM` por pod, ou seja `réplicas desejadas = ceil(RPM ÷ (0,5 × X))`. O gatilho da fila do
  `admission` (uso ≥ 0,80 de `X × ativas`, histerese da #43) não muda.

**Recomendado (a), KEDA sem Prometheus:**

| Serviço | Gatilho | Alvo |
|---|---|---|
| `reservation` | KEDA `metrics-api`: `GET /internal/metrics/hot` (novo no `admission`, `X-Internal-Token` via `TriggerAuthentication`), devolve o `adm:hot` | `AverageValue` = `0,5 × X` |
| `notification` | KEDA `kafka`: lag do group `notification` em `identity.notification` | `lagThreshold` = `0,5 × AUTOESCALA_NOTIFICATION_LAG` (50); `allowIdleConsumers: false` limita às partições sozinho |
| `backend`, `identity`, `catalog`, `admission`, `stock` | HPA `Resource` CPU (metrics-server) | `averageUtilization` 50 |

Diferença documentada na ADR: nos serviços HTTP a regra passa de req/s para CPU (o aceite permite "ou a diferença fica documentada").
Alternativa (b): KEDA + Prometheus + `micrometer-registry-prometheus` nos 7 serviços, fiel ao req/s, mas com um Prometheus disputando CPU
e RAM no host de medição e uma dependência nova em todos os serviços.

- **Partições:** o group de um tópico usa no máximo uma réplica por partição. O `cluster.sh up` aplica a mesma lógica do one-shot
  `particoes` do override (sobe `identity.notification` e `reservation.wallet` para 3 se estiverem abaixo; `--num` = quanto adicionar;
  idempotente; espera o `identity` criar o `identity.notification`). **Irreversível no broker real**, como no override: o README avisa.

## Réplicas ativas no `admission` (depende de D4)

**Recomendado (a):** um Service **headless** `reservation-pods` (`clusterIP: None`, mesmo seletor do `reservation`); o DNS dele devolve só
os pods **prontos**. Um job do `admission` (`@Scheduled`, a cada 2 s, ligado só por propriedade `admission.replicas-dns.host`, vazio no
compose) resolve o nome (`InetAddress.getAllByName`, cache do JVM baixado para 1 s com `networkaddress.cache.ttl` nesse lookup) e grava
`adm:replicas` com TTL de 15 s, a mesma chave e o mesmo TTL que o scaler da opção A grava. O script Lua, o fallback
(`ADMISSION_RESERVATION_REPLICAS` quando a chave falta) e o comportamento no compose não mudam. Várias réplicas do `admission` gravam o
mesmo valor (idempotente). Falha de DNS não grava nada (a chave expira e vale o fallback, o lado seguro: fila mais cedo).

Alternativas: (b) API do Kubernetes (ServiceAccount + RBAC lendo `readyReplicas`), mais acoplado; (c) heartbeat dos pods do
`reservation` no Redis, que faria o `reservation` depender do Redis e da senha dele.

## `stock` escalável (#205)

- Escala de 1 a 3 pelo mesmo mecanismo dos outros (tabela acima), min/máx configuráveis.
- **Pool:** Hikari **85 por réplica** no cluster (env no manifest; o compose base mantém 250). Conta do Supavisor com tudo no máximo:

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

  O Hikari 85 fica abaixo do Tomcat 300 do `stock`: threads esperam no Hikari (`connection-timeout` de 5 s), a fila real continua no
  Supavisor (pool real 45). A conta vai para o `Projeto/Backend/CLAUDE.md`.
- **Consumo de `reservation.wallet` com 2+ réplicas:** o `StockReleaseConsumer` já é idempotente por `holdId` (UPSERT de um statement com
  `WHERE stock.hold.status = 'held'`) e devolve a vaga com um `UPDATE eventcap` por evento **no fim da mesma transação**, contando só os
  holds que mudaram nela. Falta o **teste**: dois listeners no mesmo group (e dois consumidores concorrentes recebendo a mesma mensagem,
  como num rebalance) processando `ReservationCancelled`/`ReservationExpired` repetidos do mesmo `holdId` → o hold vira `released` uma vez e
  `eventcap.quant` sobe exatamente 1. Mesma coisa para o `CatalogReconciliationConsumer` (idempotente por `event_id`).
- O `stock` não tem job agendado (o reaper é do `reservation`), então não há job a coordenar entre réplicas.
- A [spec da saga](2026-10-29-saga-stock-wallet-design.md) ganha uma nota: D3 (b) implementada no cluster; o compose segue com (a).

## Slim como padrão (D7)

- `docker-compose.yml`: os 7 serviços Java passam a `image: gerenciador-eventos-<svc>.slim:latest` com `pull_policy: never` (sem
  `build:`): sem a imagem local o compose falha com "image not found" em vez de tentar o Docker Hub. O `frontend` segue com `build:`.
- `docker-compose.completa.yml` (novo, override): devolve o `build:` e a imagem completa a cada serviço, para depuração.
  `docker-compose.slim.yml` sai.
- Fluxo de build: `Projeto/slim/gerar-imagens-slim.sh` continua sendo o único jeito de gerar as `*.slim` (constrói a completa e gera a slim);
  ele passa a usar o override `completa` para o build gordo. Depois de mudar código: rodar o script para os serviços tocados.
- `run-e2e.sh`: as slim viram o padrão (o e2e continua conferindo o ID da imagem de cada serviço); `E2E_COMPLETA=1` usa a completa.
  `E2E_SLIM` sai.
- Atualizados: `CLAUDE.md` da raiz ("Rodar e testar": o `up -d --build` vira "gerar as imagens + `up -d`"), `Projeto/slim/README.md`,
  `Projeto/Backend/CLAUDE.md`. Os overrides de carga (`carga/`) e de autoescala são conferidos para não reintroduzir `build:` com nome slim.
- A mudança entra na `K8s` só **depois da R5** (merge da `Batch_2` atualizada antes do PR final): a medição da R5 não é afetada.

## e2e contra o cluster (depende de D2)

**Com D2 (a):** `E2E_K8S=1 Projeto/e2e/run-e2e.sh`. A infra do project `gerenciador-e2e` sobe em compose como hoje (Mailpit, rede e volumes
próprios); o script cria um **cluster kind próprio** (`gerenciador-e2e`, sem `extraPortMappings`) ligado **só** à rede do project (ligar o
nó a duas redes com containers de mesmo nome, como `db`, deixaria o DNS ambíguo), aplica os mesmos manifests com o ambiente do project e
troca o `GW` do container auxiliar de curl para `http://gerenciador-e2e-control-plane:30081`. Os passos (cadastro com código lido do
e-mail, evento, reserva, confirmação, replay, rajada 4b, passos de queda (a)/(b) com `kubectl delete pod` no lugar de `docker stop`) são os
mesmos. No fim, `kind delete cluster --name gerenciador-e2e` junto do `down -v` (`MANTER=1` mantém os dois).

## Dry-run de carga com autoescala (aceites da #197 e #205)

Local, não é medição. Cluster com capacidades baixas (X e alvos de CPU reduzidos por env) para forçar subida e descida em minutos:

- `Projeto/k8s/registrar-replicas.sh`: a cada 2 s grava `ts,servico,prontas,desejadas,metrica` em `Projeto/k8s/saida/replicas-<ts>.csv`
  (gitignored, como o CSV do scaler) a partir de `kubectl get deploy,hpa`.
- Roteiro: rajada no `cas` acima do limiar → `reservation` e `stock` sobem; degrau oscilante → nenhuma descida no meio (#206); carga acima
  da capacidade de uma réplica do `backend` → nenhum pod sai do Service nem reinicia por probe (#207); rajada em `identity.notification`
  → `notification` até 3 e volta; fim da carga → tudo volta ao mínimo depois da janela.
- Critérios: overselling 0 no CAS (`ativas ≤ capacity` e `quant` coerente, mesma conferência do harness), nenhum e-mail duplicado
  (Mailpit), CSV com subidas e descidas, réplica nova recebendo tráfego (D6), linha de base de CPU/RAM do nó.

## Fora de escopo

- Mudar a regra 50/80 ou o X; rodada formal de medição (issue própria); nuvem ou mais de um host.
- Corrigir #206/#207 no compose/HAProxy (opção A); o scaler e o `lb` continuam como estão para quem usar o override.
- Kit de carga apontando para o cluster além do necessário ao dry-run (a rodada formal decide).

## Entrega

Um PR `Issue-197` com base `K8s`, em três commits lógicos: (1) slim padrão (D7); (2) cluster, manifests, probes, balanceamento, e2e
contra o cluster; (3) autoescala (KEDA/HPA, réplicas ativas no `admission`, dry-run). Depois, o PR `Issue-205` (pool e teste do
`stock` com N réplicas, min/máx dele, dry-run com o `stock` escalado). A ADR da autoescala ganha a seção "Opção D implementada" com
as decisões e as diferenças (stand-by, req/s × CPU, AppArmor).

## Riscos

- **kube-proxy por conexão (D6):** se o `max-keep-alive-requests` finito não espalhar o bastante, a réplica nova fica ociosa; o dry-run
  mede e, se falhar, a opção (c) volta para decisão.
- **Atraso da subida:** HPA sincroniza a cada 15 s, KEDA consulta a cada `pollingInterval` (5 s), mais o boot do JVM: a fila do `admission`
  absorve o intervalo (é o papel dela).
- **`kind load` lento:** 7 imagens de ~370–460 MB a cada build; o `carregar <svc>` recarrega só o que mudou.
- **Docker Desktop:** nunca matar à força (o `engine.sock` trava). O `cluster.sh down` usa só `kind delete cluster`.
