# Etapa 10: a rodada da #227 no Kubernetes (K0, K1, K2), issue #228

A #227 (etapa 09) mediu o efeito de tirar o Postgres do host no compose. Esta etapa repete a mesma escada no cluster kind da branch `K8s`
(#197/#205) em três blocos, cada um num cluster novo:

| Bloco | Trilha | Autoescala | Banco | Pools |
|---|---|---|---|---|
| **K1** | `nenhuma` | travada em 1 | Dell (192.168.68.50:5433) | R5 (`K8S_POOLS=r5`) |
| **K2** | `b1` (Istio) | 1..3 | Dell | os do cluster (soma 1492), pool real 245 |
| **K0** | `nenhuma` | travada em 1 | dentro do cluster (PVC novo) | R5 |

O K0 é o controle: separa o efeito do db remoto do efeito do Kubernetes.

**Ordem:** o plano era K1, K2, K0. **O K0 rodou primeiro.** A limpeza do banco do Dell antes do K1, um `TRUNCATE ... RESTART IDENTITY` pelo
`limpar-banco.sh` em modo cluster, foi negada pelo classificador de permissões da sessão que roda a rodada até o usuário liberá-la. O K0
não depende dela: ele sobe com o banco dentro do cluster, num PVC novo e vazio, e não toca o Dell. Depois vieram o K1 e o K2.

Parâmetros por bloco e comandos: `Projeto/k8s/README.md`, "Rodada com o db remoto".

## K0 (`Deg-296361`), 2026-11-01

### Configuração

- **Cluster:** kind de um nó no host da R5 (Ryzen 7 7700X, 2,5 GbE, 192.168.68.64). Branch `Issue-228`, commits 9a7d1b7 e a826c8d.
  - `K8S_OVERLAY=rodada SLIM_TAG=k8s K8S_POOLS=r5 Projeto/k8s/cluster.sh up --trilha nenhuma`, sem `K8S_DB_REMOTO`.
  - Imagens `*.slim:k8s`, as mesmas da verificação pós-R5 da `K8s`.
- **Stack:**
  - db no cluster, com `max_connections` 300 e pool real do Supavisor 245;
  - Hikari: reservation 1000, stock 250, identity 100, catalog 100, backend 10, notification 3; 32 workers no `admission`;
  - 7 ScaledObjects com `maxReplicaCount` 1;
  - gateway pelo NodePort 30081, publicado na 8081 do host.
- **Seed:** 1000 usuários e 868 eventos com capacidade 500 (`preparar-degraus.ps1` no host; host com `user_id=1`).
  - O 1º preparo abortou depois do seed, por um redirecionamento `2>&1` do operador no PowerShell.
  - O cluster foi recriado para o seed começar num banco vazio, sem TRUNCATE.
- **Gerador:** EnzoAMD, k6 2.2.0, clone em 850d38e. O k6 é idêntico ao do commit do plano (`git diff` vazio em `carga-degraus.js`,
  `lib-admissao.js`, `lib-mapeamento.js` e `docs/carga/harness`). RAM livre: 7,68 GB depois do disparo e 10,05 GB no fim.
- **Escada:** a mesma da #227. Sequencial de 20k a 98k RPM, passo de 6k, 14 degraus de 60 s.
  - `USUARIOS` 1000, `PCT_CONFIRMA` 100, `MAX_VUS` 3000;
  - `PCT_ACOMPANHA` 5, `CONSULTA_MS` 1000, `TETO_CONSULTA_S` 35;
  - `PARADA_P95_MS` 5000, `PARADA_JANELA_S` 60, `SETUP_S` 90.
- **k6:** de 08:37:03Z a 09:02:48Z, código de saída 0. Os 1000 logins do setup levaram 28,4 s, contra ~12 s no compose.

### Resultado

| RPM alvo | p50 | p95 | p99 | p95 pior janela 30 s | 202 | 429 | admitir p95 (ms) | ponta a ponta p95 (ms) |
|---|---|---|---|---|---|---|---|---|
| 20k | 27,4 | 910,6 | 1.492,5 | 1.056,4 | 797 | 0 | 2.212 | 1.007 |
| 26k | 298,6 | 1.771,6 | 2.402,8 | 1.789,1 | 12.009 | 0 | 2.023 | 2.260 |
| 32k | 692,1 | 2.169,9 | 2.860,2 | 2.232,2 | 14.758 | 0 | 7.296 | 4.249 |
| 38k | 752,6 | 2.094,6 | 2.594,4 | 2.100,9 | 15.432 | 5.715 | 21.162 | 14.232 |
| 44k | 170,2 | 1.781,4 | 2.442,6 | 2.024,1 | 19.628 | 10.797 | 19.387 | 12.065 |
| 50k | 4,2 | 462,2 | 729,2 | 497,2 | 22.332 | 20.577 | 17.064 | 15.065 |
| 56k | 3,6 | 414,7 | 656,1 | 424,0 | 21.334 | 27.370 | 19.056 | 17.057 |
| 62k | 3,6 | 403,5 | 666,3 | 413,1 | 20.882 | 33.044 | 19.067 | 16.077 |
| 68k | 3,7 | 433,3 | 688,7 | 460,1 | 20.750 | 38.381 | 19.201 | 16.083 |
| 74k | 3,5 | 407,7 | 650,7 | 424,3 | 21.252 | 43.123 | 18.644 | 15.062 |
| 80k | 3,2 | 397,2 | 620,2 | 410,0 | 20.558 | 49.410 | 19.065 | 15.108 |
| 86k | 3,6 | 456,8 | 782,1 | 473,9 | 19.467 | 55.323 | 20.072 | 15.129 |
| 92k | 3,6 | 462,1 | 797,3 | 466,3 | 19.853 | 60.012 | 19.161 | 15.050 |
| 98k | 4,1 | 496,8 | 876,9 | 516,7 | 18.525 | 66.700 | 21.104 | 14.072 |

As latências estão em ms e cobrem todas as respostas do POST. A tabela sai de `degraus-296361.csv` e o HTML é `degraus-296361.html`.

- **5xx:** 0 em todos os degraus, nas reservas e nas confirmações. Efetivo = alvo, 0 descartes, sem parada.
- **1º degrau degradado (p95 ≥ 1 s), sem contar o 20k a frio:** **26k**. Na #227 (compose, db no Dell) foi 32k e na R5 (compose, db local), 38k.
- **Overselling:** 0.
  - No total: 868 eventos, 157.497 ativas, capacidade 434.000.
  - **Por evento:** 0 eventos com ativas acima da capacidade; o máximo é 500 ativas por evento, igual à capacidade; e `capacity − quant` do
    `stock.eventcap` = ativas em todos os 868 eventos.
- **Sagas** (`verificar-rpm`, rc 0): 0 velhas, 0 hold `held` com saga compensated, 0 devolução pendente, 0 órfã inversa, 0 `quant`
  divergente.
  - No fim do bloco (`resumo-saga`): 0 compensações, `devolucao_pendente` 0, `orfa_inversa` 0, 0 sagas `reserving` e 0 `compensating`.
  - Lag de devolução da vaga (n 164.450): p50 111 ms, p95 338 ms, máximo 1.945 ms. Na R5 o p95 foi 448 ms.
- **CPU do host durante o k6** (`telemetria-host`): média 83,5%. RAM livre mínima de 21,8 GB.

**As 100 de diferença no `verificar`:**
- A 1ª execução mostrou 157.497 ativas, mas 144.942 confirmed + 12.455 pending = 157.397. As duas consultas rodaram em instantes diferentes
  enquanto o job de expiração varria as pending: no 1º instante havia 12.555 pending, e 100 expiraram antes da 2ª consulta.
- O total de `walletevent` do prefixo é 309.392 nas duas leituras.
- Releitura depois da varredura: 0 pending, 164.450 expired (151.995 + 12.455) e 144.942 confirmed.

**Ruído:**
- **17 respostas "outros" no degrau de 26k**, todas às 08:40:28Z: o POST `/bff/event-wallets` recebeu EOF. A conexão foi fechada do lado do
  servidor, no caminho NodePort (kube-proxy) → pod do gateway.
- Não houve 5xx nem restart de pod. É uma rajada única, num instante só, e não se repetiu.

**Leitura contra a #227** (compose, db no Dell):
- No K0 a fase lenta é mais longa: de 26k a 44k, com p50 entre 170 e 753 ms.
- Os 429 começam em 38k (5.715, contra 325).
- De 50k para cima o p50 cai a 3–4 ms, mas a admissão é mais lenta: p95 de admitir entre 17 e 21 s (contra 11–14 s) e ponta a ponta entre
  14 e 17 s (contra 10–12 s).
- **Isso ainda não isola nenhum fator:** o K0 tem o Kubernetes com o db local, e a #227 tem o compose com o db remoto. O efeito do db remoto
  no cluster sai da comparação K0 × K1. O efeito do Kubernetes com o db remoto sai de K1 × #227.

### Notas de método

- **Coleta em modo cluster** (`coleta-host.sh` com `CARGA_K8S`): 1390 amostras em 2400 s, `duracao_amostra_ms` com p50 1.275 e p95 3.756.
  O intervalo efetivo passa de 1 s. Na #227 o p95 foi 5.856, então não é mais cara que a do compose.
- **A finalização da coleta foi regenerada.** O `coleta-host.sh` foi editado (correções da #228) enquanto a coleta rodava. O bash lê o script
  aos poucos e, no fim, deu erro de sintaxe antes da finalização. O CSV ficou inteiro.
  - O `pgss`, o `saga-*.csv` e o `resumo-saga` foram gerados depois, com o mesmo `INICIO_ISO` (08:36:34Z) e sem resetar o
    `pg_stat_statements`.
  - **O `pgss` inclui as consultas das duas execuções do `verificar-rpm`**, que rodaram antes da finalização. São poucas chamadas perto da
    carga, mas aparecem no top 25.
- **`registro-*.txt`: o env dos containers saiu vazio.** O harness lia o env por `kubectl exec <pod> -- printenv`, e as imagens slim não
  têm `printenv` (nem `sh`). Pelo mesmo motivo, a coleta usou o `ORPHAN_MS` default de 30000, o mesmo valor do serviço.
  - A #228 passou a ler o env do spec do Deployment (volta 5).
  - Env do K0 reconstruído do render da mesma config (`K8S_OVERLAY=rodada K8S_POOLS=r5 cluster.sh renderizar --trilha nenhuma`, sem
    `K8S_DB_REMOTO`):

    | Serviço | Variável | Valor |
    |---|---|---|
    | reservation | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | 1000 (literal no spec) |
    | stock | idem | 250 (literal) |
    | identity / catalog | idem | 100 / 100 (literal) |
    | notification | idem | 3 (literal) |
    | admission | `ADMISSION_WORKER_CONCURRENCY` | 32 (literal) |
    | supabase-pooler | `POOLER_DEFAULT_POOL_SIZE` | 245 (literal) |
    | db | `max_connections` | 300 (args do StatefulSet) |
    | admission | `ADMISSION_X_RPM`, `_RESERVATION_REPLICAS`, `_QUEUE_ON`, `_SYNC_BELOW` | via `envFrom` do Secret `env-admission`; defaults do compose 56000 / 1 / 0,8 / 0,7 |
    | reservation | `SERVER_TOMCAT_THREADS_MAX`, `RESERVATION_SAGA_ORPHAN_MS`, `_REAPER_SCAN_MS` | via `envFrom` do Secret `env-reservation`; default do compose para o Tomcat: 300; os outros dois ficam no default do serviço |
    | backend | `RESERVATION_READPATH` | ausente: default `cas` do `application.properties` |

  - O que vem por `envFrom` sai do `docker compose config` resolvido, com as mesmas regras da R5, e não aparece no spec. Os literais
    batem com o que foi conferido no Deployment ao vivo durante a validação.
  - **Gatilho do `admission`** (`ADMISSION_X_RPM`, `ADMISSION_RESERVATION_REPLICAS`, `ADMISSION_QUEUE_ON`, `ADMISSION_SYNC_BELOW`):
    - No K0 e no K1, as quatro variáveis vêm do **Secret** `env-admission` (`envFrom.secretRef`; não há ConfigMap). Não há literal no
      spec, e o `admission` não registra esses valores no log.
    - O Secret não foi lido. Os valores saíram da mesma fonte de que o `cluster.sh` gera o Secret: o `docker compose -f
      docker-compose.yml config` resolvido, com o mesmo `Projeto/.env`. Um filtro que só deixa passar essas quatro chaves rodou antes do
      stdout.
    - Resultado: **56000 / 1 / 0,8 / 0,7**, igual à R5 e à #227. O `.env` não mudou entre o K0 e o K1, então os dois blocos usam os
      mesmos valores.
- **`verificar-rpm`:** a 1ª execução abortou depois das tabelas, por dois defeitos do harness em modo cluster, corrigidos na #228:
  - o stderr "Defaulted container" do kubectl virava erro no PS 5.1;
  - uma variável local colidia com o parâmetro `ValidateSet` `$ReadPath`.

  Saída das duas execuções: `host-telemetria/verificacoes-k0.log`.
- **`/actuator/health` na 8081 responde 401 no cluster.** `MANAGEMENT_SERVER_PORT=8090` leva o actuator para a porta de management, das
  probes da #197/#207, e na 8090 a resposta é 200. O preparo da escada passou a checar só o alcance. Imagem, SecurityConfig e `/bff/**` são
  os mesmos.

### Arquivos

- `degraus-296361.csv`, `degraus-296361.html`, `plano-degraus-296361.json`
- `gerador/summary-degraus-296361.json`: resumo do k6, do ref temporário `K8S-gerador-K0` (6459b84)
- `host-telemetria/`: `coleta-host-*`, `eventos-*`, `pgss-*`, `registro-*`, `resumo-saga-*`, `saga-*`, `telemetria-host-*` e
  `verificacoes-k0.log`

## K1 (`Deg-308164`), 2026-11-01

### Configuração

- **Cluster:** o mesmo host do K0, num cluster novo. Branch `Issue-228` em **67c0ef8**: o harness já lê o env pelo spec do Deployment
  (volta 5).
  - `K8S_OVERLAY=rodada SLIM_TAG=k8s K8S_POOLS=r5 K8S_DB_REMOTO=192.168.68.50:5433 Projeto/k8s/cluster.sh up --trilha nenhuma`, depois
    `cluster.sh db-teste` com rc 0 (TCP e `SELECT 1` pelo Supavisor).
  - A stack é a do K0 (mesmos Hikari, 32 workers, ScaledObjects em 1, NodePort 30081). Só o banco muda: o Postgres do Dell, com
    `max_connections` 300, pelo Service sem seletor do componente `db-remoto`.
- **Limpeza antes do bloco:** `CARGA_K8S=kind-gerenciador limpar-banco.sh`, com rc 0. Foi liberada pelo usuário direto na sessão.
  - Depois dela: `event`, `walletevent`, `eventcap` e o `event_store` do reservation zerados.
  - Em `identity.users` fica 1 linha: o usuário que a sonda do próprio `limpar-banco` cria para conferir o JWKS. Por isso o host do seed é
    o `user_id=2`.
  - Também: FLUSHDB do Redis, purge de `admission.requests` e `admission.timed_out`, e a chave do JWT apagada.
- **Seed e escada:** os mesmos do K0. 1000 usuários e 868 eventos com capacidade 500, mesma escada e mesmos parâmetros do k6.
- **Gerador:** clone em 850d38e, como no K0. O código k6 é idêntico ao de 67c0ef8 (`git diff` vazio em `carga-degraus.js` e
  `Projeto/carga/k6`). RAM livre: 9,92 GB antes e 10,19 GB no fim.
- **k6:** de 11:54:00Z a 12:19:41Z, com código de saída 0 e sem parada por p95. Os 1000 logins do setup levaram 28,2 s.
- **Coleta no Dell:** sessão DB, `~/coleta-227.sh` com saída `coleta-k8s-K1`, de 11:53:54Z a 12:34:14Z, com o vigia ligado.

### Resultado

| RPM alvo | p50 | p95 | p99 | p95 pior janela 30 s | 202 | 429 | admitir p95 (ms) | ponta a ponta p95 (ms) |
|---|---|---|---|---|---|---|---|---|
| 20k | 25,1 | 1.795,6 | 2.356,0 | 2.059,6 | 431 | 0 | 1.251 | 3.021 |
| 26k | 24,7 | 293,9 | 569,9 | 298,6 | 0 | 0 | — | — |
| 32k | 122,7 | 1.073,6 | 1.517,1 | 1.118,0 | 3.472 | 0 | 1.021 | 941 |
| 38k | 325,9 | 1.504,5 | 2.050,9 | 1.525,2 | 18.120 | 0 | 2.559 | 2.334 |
| 44k | 64,6 | 1.275,6 | 1.914,1 | 1.310,9 | 25.857 | 3.916 | 11.793 | 8.857 |
| 50k | 6,5 | 321,8 | 526,4 | 340,0 | 30.406 | 12.747 | 10.051 | 10.049 |
| 56k | 5,7 | 296,2 | 507,7 | 307,7 | 30.627 | 18.069 | 11.046 | 10.066 |
| 62k | 4,8 | 308,5 | 496,8 | 323,7 | 29.381 | 24.557 | 12.033 | 11.044 |
| 68k | 4,2 | 298,5 | 502,2 | 315,1 | 28.919 | 30.210 | 12.074 | 11.058 |
| 74k | 4,7 | 1.001,9 | 1.756,2 | 1.585,6 | 26.274 | 36.854 | 14.045 | 12.057 |
| 80k | 4,3 | 335,2 | 525,0 | 349,1 | 26.173 | 42.896 | 14.051 | 12.057 |
| 86k | 4,3 | 386,7 | 621,2 | 445,8 | 24.212 | 50.532 | 14.105 | 13.056 |
| 92k | 4,1 | 344,9 | 601,9 | 348,2 | 24.200 | 55.837 | 16.108 | 12.097 |
| 98k | 4,6 | 341,5 | 543,3 | 341,8 | 24.380 | 60.895 | 15.065 | 13.058 |

As latências estão em ms e cobrem todas as respostas do POST. A tabela sai de `degraus-308164.csv`, e o gráfico está em `degraus-308164.html`.

- **5xx:** 0 em todos os degraus, nas reservas e nas confirmações. Nenhum "outros" nem EOF.
- **Descartes:** só no 74k.
  - O k6 chegou aos 3000 VUs às 12:11:40Z ("Insufficient VUs") e descartou 229 iterações: o efetivo foi 73.772 para um alvo de 74.000.
  - O p95 desse degrau (1.001,9 ms) carrega essa saturação do gerador.
  - No 80k, voltou a 0 descartes e p95 de 335 ms.
- **1º degrau degradado (p95 ≥ 1 s), sem contar o 20k a frio:** **32k**. O 74k também passa de 1 s, pela saturação do gerador.
  - No K0 o primeiro foi 26k, na #227 foi 32k e na R5, 38k.
- **Overselling:** 0.
  - 868 eventos e capacidade total de 434.000.
  - 178.356 ativas: 158.690 confirmed + 19.666 pending, que batem na mesma leitura.
  - Nenhum evento com overselling.
- **Sagas** (`verificar-rpm`, rc 0): convergidas.
  - Pelo `verificar-rpm`: 0 velhas, 0 hold `held` com saga compensated, 0 devolução pendente, 0 órfã inversa, 0 `quant` divergente.
  - No fim do bloco (`resumo-saga`): 0 compensações, `devolucao_pendente` 0, `orfa_inversa` 0, 0 sagas `reserving` e 0 `compensating`.
  - Lag de devolução da vaga (n 186.750): p50 220 ms, p95 622 ms, máximo 1.494 ms. No K0 o p95 foi 338 ms e na R5, 448 ms.
- **Dell, pela coleta da sessão DB:**
  - **Conexões** (`pg_stat_activity` a cada 5 s):
    - Pico de 263 de 300, a primeira vez às 11:55:55Z, com platô em 262–263.
    - Ficou em 250 ou mais de 11:55:50Z a 12:24:26Z: o pool do Supavisor (245) ficou cheio a escada inteira.
    - Pico de 243 active às 11:59:48Z.
  - **CPU** (vmstat): us+sy com pico de 62% às 12:01:10Z e média de 12,4%.
  - **PSI avg10:** cpu some com máximo de 9,87 e io some com máximo de 9,95.
  - **NVMe:** %util médio de 27%, com só 5 de 2.421 amostras em 90% ou mais.
  - **Vigia:** nenhum evento. Sem restart, sem mudança de health, sem falha de psql, sem passar de 270 conexões e sem "too many clients",
    FATAL ou PANIC.
- **CPU do host durante o k6** (`telemetria-host`): média de 86,2%. RAM livre mínima de 23,3 GB.

**K0 × K1, o efeito do db remoto no cluster:**
- A fase lenta encolheu: o p50 passa de 100 ms só em 32k–38k. No K0 isso ia de 26k a 44k, com o p50 chegando a 753 ms.
- Os 429 começam em 44k (3.916). No K0 começavam em 38k (5.715).
- De 50k para cima: admitir p95 de 10 a 16 s (K0: 17–21 s) e ponta a ponta p95 de 10 a 13 s (K0: 14–17 s).
- Com o db fora do nó do kind, o host não divide mais a CPU com o Postgres e a admissão fica mais rápida.

**K1 × #227, o efeito do Kubernetes com o db remoto:**
- O 1º degrau degradado é o mesmo (32k).
- Acima de 50k, a admissão fica perto da da #227 (admitir 11–14 s, ponta a ponta 10–12 s).
- No cluster, o lag de devolução da vaga é maior (p95 622 ms).

### Notas de método

- **Coleta em modo cluster:** 1273 amostras em 2400 s, `duracao_amostra_ms` com p50 1.249 e p95 4.804. A finalização foi normal (o
  script não foi editado durante a coleta).
- **`registro-*.txt`:** o env agora sai do spec do Deployment.
  - Os literais aparecem: Hikari 1000/250 e 32 workers.
  - O que vem por `envFrom` sai vazio, como já documentado no K0: o gatilho do `admission` e o Tomcat e a saga do `reservation`. Os
    valores são os mesmos do K0, 56000 / 1 / 0,8 / 0,7.
- **O `pgss` inclui as consultas do `verificar-rpm`.** Ele rodou às ~12:21Z, antes de a coleta fechar (12:33Z), como no K0.
- **`verificar-rpm`:** rodou depois da escada, sem carga no Dell. Saída em `host-telemetria/verificacoes-k1.log`.

### Arquivos

- `degraus-308164.csv`, `degraus-308164.html`, `plano-degraus-308164.json`
- `gerador/summary-degraus-308164.json`: resumo do k6, do ref temporário `K8S-gerador-K1` (2271066)
- `host-telemetria/`: `coleta-host-*`, `eventos-*`, `pgss-*`, `registro-*`, `resumo-saga-*`, `saga-*`, `telemetria-host-*` e
  `verificacoes-k1.log`
- `dell/`: coleta do Dell pela sessão DB (pacote do ref temporário `K8sDB-dell-K1`, d67101f, sha256 conferido):
  - `mpstat.txt`, `vmstat.txt`, `psi.txt`, `pg_activity.csv`, `inicio.txt` e `fim.txt`;
  - `iostat-nvme0n1.txt` e `sar-filtrado.csv`, com o mesmo recorte da #227. O `sar.csv` original (38,7 MB) e o `sar.bin` (61,7 MB) não
    entraram.

## K2

Pendente. Roda num cluster novo, com a trilha `b1` e a autoescala 1..3, depois da limpeza 2 do Dell.

- **Versão do harness:**
  - K0: #228 em a826c8d, com as correções de `-c` e da variável `ReadPath` aplicadas só no `verificar` final.
  - K1: 67c0ef8.
  - K2: o commit vai na seção dele.
