# K2-pos-231 (`Deg-473284`), 2026-11-03, issue #237

A mesma rodada do K2 (trilha `b1`, autoescala 1..3, db no Dell), agora com o `stock` da #231: 1 réplica fixa, Hikari 245, `Recreate` e sem
ScaledObject.

É um A/B de **configuração**: o render só difere da #231. Na **execução**, porém, a autoescala divergiu do K2-repetição (ver "Por que houve 202").

**Veredito, em uma linha.** Com a #231 o b1 parou no mesmo 32k do K2-repetição, que, sem a #231, também tinha passado do 20k. A latência foi bem
menor no 20k (p50 de 891 contra 1.795 ms) e próxima no 26k (1.437 contra 1.551 ms), com 1 rodada por config.

**Ressalvas:**
- A variação entre rodadas da mesma config é grande: no 20k o K2 deu p50 de 2.944 ms e o K2-repetição 1.795 ms.
- O K2 parou só 20 ms acima do limite.
- O limite provável agora, não isolado, é o lock da linha quente do `eventcap` no Dell.

## Configuração

- **Branch:** `Issue-237`, sobre a `Issue-232` em ada814b (contém a #231 e a #235). Nenhum código Java mudou desde o K2: as `*.slim:k8s` são as
  mesmas.
- **Cluster novo:**
  - `K8S_ADMISSION_MIN=1 K8S_OVERLAY=rodada SLIM_TAG=k8s K8S_DB_REMOTO=192.168.68.50:5433 Projeto/k8s/cluster.sh up --trilha b1`.
  - Depois, `cluster.sh db-teste`, com rc 0.
- **Admission com `K8S_ADMISSION_MIN=1`,** para o A/B da #231. A config final usa 2 (#235), validado no soak.
  - O cluster subiu primeiro com o mínimo 2.
  - O `up` foi reaplicado com `K8S_ADMISSION_MIN=1` antes da limpeza.
  - O `minReplicaCount` 1 no ScaledObject vivo foi conferido pelo `kubectl`, sem fonte versionada. Evidência indireta: o `admission` em 1 réplica
    de 09:48:55Z a 09:52:06Z em `pods/replicas-k8sK2pos231.csv`, e `rabbit_consumidores` 32 com 1 réplica na `coleta-host`.
- **Render contra o K2:** comparei o `cluster.sh renderizar --trilha b1`, com as mesmas variáveis, entre e4f862f (K2; a manifest do K2-repetição,
  a7da861, é a mesma) e ada814b.
  - A diferença está em [`render-diff-k2-vs-k2pos231.txt`](render-diff-k2-vs-k2pos231.txt), um `diff -u` com a procedência no cabeçalho, e é só
    a do `stock`:
    - `replicas: 1`;
    - `Recreate` no lugar de `RollingUpdate`;
    - Hikari 60 → 245;
    - sem o `ScaledObject/stock`.
  - Fora do render, o `kind.yaml` ganhou a porta 8025 do soak (#232), sem Service por trás no `rodada`.
- **Pools:**

  | Componente | Pool | Fonte |
  |---|---|---|
  | reservation / stock | Hikari 200 / 245 | `host-telemetria/registro-*` |
  | identity / catalog / backend / notification | Hikari 50 / 50 / 10 / 3 | `Projeto/k8s/base/{identity,catalog,backend,notification}.yaml` |
  | Supavisor (pool real) | 245 | `POOLER_DEFAULT_POOL_SIZE`, em `Projeto/k8s/overlays/rodada/kustomization.yaml` |
  | `admission` | 32 workers por réplica | `rabbit_consumidores` 32/64/96 na `coleta-host` |
- **Limpeza:** `CARGA_K8S=kind-gerenciador limpar-banco.sh`, com rc 0, liberada pelo usuário na sessão. Ficaram só os zeros e 1 linha em
  `identity.users` (saída do script, sem fonte versionada).
- **Seed:** o mesmo do K0, do K1 e do K2 (`preparar-degraus.ps1`): 1000 usuários e 868 eventos de capacidade 500.
- **Réplicas antes do k6:** todas em 1 às 09:48:33Z, pelo `kubectl`, sem fonte versionada. Evidência indireta: todas em 1 às 09:48:55Z em
  `pods/replicas-k8sK2pos231.csv`.
- **Gerador:** clone em **850d38e**, sem jogo nem Steam. A `linha_k6` é idêntica à do K2 e do K2-repetição.
  - A coluna `commit` do CSV e do plano (dd2e8a2) é o commit do host que rodou o `preparar`, não o do k6.
  - RAM livre de 9,57 GB antes e mínima de 1,73 GB às 09:55:34Z, com o k6 em 8,1 GB
    (`gerador/telemetria-gerador-k2pos231-473284.csv`).
  - CPU no máximo em 37% durante os degraus. A 1ª amostra (09:49:40Z, no disparo do k6) teve 74%.
- **k6:** código de saída **99**, com fim às 09:55:44Z. Os 1000 logins do setup levaram 27,9 s (`gerador/k6-console-473284.log`).
- **Coletas:**
  - host: `coleta-host`, `telemetria-host` e o coletor por pod (`pods/coleta-pods.sh`, a cada 5 s), de 09:48:56Z até as sagas zerarem;
  - Dell: sessão DB, `coleta-k8s-K2-pos231`, de 09:49:10Z a 09:57:42Z, com os vigias ligados (0 alertas).

### Regra de janela (a mesma nos três blocos)

- **Início do relógio:** T0 = fim do k6 − `duracao_s` do `summary`.
- **Degrau k (0, 1, 2, …):** 60 s a partir de T0 + 90 + k × 106 s.
  - Os 90 s são o `SETUP_S`.
  - Os 106 s são 60 s de carga mais 46 s de folga (`carga-degraus.js`, `ESPACO_S` = graceful + 1).

| Bloco | Fim do k6 | `duracao_s` | T0 | Degraus |
|---|---|---|---|---|
| K2 | 18:26:20Z | 150,31 | 18:23:49,6Z | 20k de 18:25:19,6Z a 18:26:19,6Z |
| K2-repetição | 19:42:01Z | 362,53 | 19:35:58,4Z | 20k às 19:37:28,4Z; 26k às 19:39:14,4Z; 32k às 19:41:00,4Z |
| K2-pos-231 | 09:55:44Z | 362,35 | 09:49:41,6Z | 20k às 09:51:11,6Z; 26k às 09:52:57,6Z; 32k às 09:54:43,6Z |

No K2-pos-231 o setup sai às 09:51:11Z (`k6-console-473284.log`), que é T0 + 90 s. Todas as métricas de host, Postgres, réplicas e Redis abaixo usam
essas janelas.

A `coleta-host` amostra a cada **~2,2 s neste bloco** (mediana; p90 de 3,4 s), contra 1,3 s no K2 e 1,4 s no K2-repetição (pelo `epoch_ms`). Os
máximos que vêm dela são limites inferiores, e mais fracos aqui.

## Resultado

As latências estão em ms. A tabela sai de `degraus-473284.csv`. O gráfico `degraus-473284.html` é gerado localmente e fica fora do git.

| RPM alvo | efetivo | p50 | p95 | p99 | p95 pior janela 30 s | 202 | 5xx (reserva / confirma) | descartes |
|---|---|---|---|---|---|---|---|---|
| 20k | 20.001 | 891,1 | 2.875,9 | 3.819,1 | 3.132,1 | 2.462 | 0 / 0 | 0 |
| 26k | 26.001 | 1.436,7 | 2.970,8 | 3.595,1 | 3.050,8 | 0 | 0 / 0 | 0 |
| 32k (parcial) | 27.010 | 3.963,9 | **5.515,2** | 6.258,9 | 5.807,7 | 0 | 0 / 0 | **3.277** |

- **Parada no 32k:** p95 de 5.515 ms na janela de 60 s.
- **5xx:** 0 na reserva e na confirmação, nos três degraus.
  - Nenhum serviço logou ERROR e houve 0 restart (`kubectl logs` de backend, reservation, stock, admission, identity e catalog nos últimos 20
    min, e `kubectl get pods`, sem fonte versionada).
- **Overselling:** 0 (`host-telemetria/verificacoes-k2pos231.log`).
  - 868 eventos e 36.608 ativas: 35.678 confirmed + 930 pending, além de 892 expired.
  - Nenhum evento com overselling.
- **Sagas** (`verificar-rpm`, rc 0): convergidas, com 0 velhas, 0 hold `held` com saga compensated, 0 devolução pendente, 0 órfã inversa e 0
  `quant` divergente.
- **Fila:** no 20k, 115 reservas acompanhadas: 40 admitidas e 75 `rejected`, 0 `timed_out`.

### K2 × K2-repetição × K2-pos-231, por degrau

Réplicas na ordem backend/reservation/identity/catalog/admission/notification, como na `coleta-host`, mais o `stock` à parte. "→" indica as
mudanças dentro da janela do degrau (regra acima).

| Bloco | Degrau | p50 | p95 | 202 | descartes | 5xx (res. / conf.) | réplicas (b/r/i/c/a/n) | stock |
|---|---|---|---|---|---|---|---|---|
| K2 | 20k | 2.943,5 | 5.019,6 (parou) | 2.025 | 0 | 0 / 0 | 2/1/3/1/1/1 → 3/1/2/1/3/1 | 1 → 3 (fim) |
| K2-rep | 20k | 1.795,0 | 4.841,3 | 5.592 | 0 | 0 / 0 | 1/1/3/1/1/1 → 3/1/3/1/3/1 | 1 → 3 |
| K2-rep | 26k | 1.550,5 | 3.222,5 | 6.798 | 0 | **67 / 1** | 3/1/2/1/3/1 → 3/1/1/1/3/1 | 3 |
| K2-rep | 32k (parcial) | 4.453,1 | 5.940,9 (parou) | 0 | 5.227 | 0 / 0 | 3/2/1/1/3/1 | 3 |
| **K2-pos-231** | 20k | 891,1 | 2.875,9 | 2.462 | 0 | 0 / 0 | 1/1/2/1/1/1 → 2/2/2/1/2/1 | **1** |
| **K2-pos-231** | 26k | 1.436,7 | 2.970,8 | 0 | 0 | 0 / 0 | 3/2/1/1/3/1 | **1** |
| **K2-pos-231** | 32k (parcial) | 3.963,9 | 5.515,2 (parou) | 0 | 3.277 | 0 / 0 | 3/2/1/1/3/1 | **1** |

**Fontes:**
- p50, p95, 202, descartes e 5xx: `../K2/degraus-331542.csv`, `../K2-repeticao/degraus-335785.csv` e `degraus-473284.csv`.
- Réplicas b/r/i/c/a/n: a `coleta-host` de cada bloco (`host-telemetria/coleta-host-*.csv`).
- Stock:
  - K2: `kubectl` às 18:26:56Z, sem fonte versionada (ver o README da etapa);
  - K2-rep: `../K2-repeticao/pods/replicas-k8sK2rep.csv` (2 às 19:38:16Z e 3 às 19:38:27Z);
  - aqui: `pods/replicas-k8sK2pos231.csv` (87 de 87 amostras em 1).

**Efetivo no 32k:** os dois blocos receberam cargas diferentes, 27.010 reservas e 3.277 descartes aqui contra 25.015 e 5.227 no K2-repetição. Isso
favorece o K2-pos-231 na comparação desse degrau.

Para referência, o K1 (trilha `nenhuma`, autoescala travada em 1, pools da R5) rodou os 14 degraus.
- p50 de 25,1, 24,7 e 122,7 ms em 20k, 26k e 32k.
- O 1º degrau degradado (p95 ≥ 1 s, sem contar o 20k a frio) foi o 32k (`../K1/degraus-308164.csv`).

### Por que houve 202 com X = 56000 (e o que isso faz com a comparação)

- O gatilho da fila é **por evento, numa janela de 200 ms** (script Lua do `admission`): uso = RPM do evento ÷ (X × réplicas do
  `reservation` lidas em `adm:replicas`). Liga com uso ≥ 0,80 e só volta ao síncrono com uso < 0,70 **e** a espera do evento vazia.
- A `coleta-host` amostra o RPM do evento mais quente (`redis_hot`, a chave `adm:hot`) e as réplicas lidas (`redis_replicas`), a cada ~2,2 s
  neste bloco (1,3 s no K2 e 1,4 s no K2-repetição). O uso calculado nessas amostras **nunca chegou a 0,80** em nenhum dos três blocos:

  | Bloco | Degrau | `redis_hot` máx. (réplicas lidas) | uso máx. nas amostras (`redis_hot`, réplicas) | `admission.requests` máx. |
  |---|---|---|---|---|
  | K2 | 20k | 30.340 (1) | 0,54 (30.340, 1) | 842 |
  | K2-rep | 20k | 43.793 (1) | 0,78 (43.793, 1) | 2.060 |
  | K2-rep | 26k | 40.106 (1) | 0,72 (40.106, 1) | 1.106 |
  | K2-rep | 32k | 27.450 (2) | 0,25 (27.450, 2) | 0 |
  | K2-pos-231 | 20k | 35.636 (2), às 09:52:10,6Z | 0,46 (25.769, 1), às 09:51:22Z | 718 |
  | K2-pos-231 | 26k | 39.375 (2) | 0,35 (39.375, 2) | 0 |
  | K2-pos-231 | 32k | 39.493 (2) | 0,35 (39.493, 2) | 0 |

- **Hipótese provável, não provada:** a fila ligou em picos de 200 ms que a amostra de ~2 s não vê.
  - O mapeamento sequencial põe toda a carga num evento por vez, e com a latência alta do b1 as chegadas ao `admission` se agrupam.
  - Ligada, a fila só desliga com a espera do evento vazia (histerese).
  - Não há dado em 200 ms para provar isso. O K2-repetição também teve 202 no 20k (5.592) e no 26k (6.798).
- **A execução divergiu nas réplicas do `reservation`.**
  - Aqui o KEDA o levou a 2 às 09:51:46Z, no meio do 20k (`coleta-host`, `repl_reservation`). O `adm:replicas` passou a 2, o que dobra o
    denominador do uso, e a fila não ligou mais no 26k nem no 32k (`rabbit_requests` 0).
  - No K2-repetição ele só foi a 2 às 19:40:16Z, depois do 26k. A fila ficou ligada no 20k e no 26k, com o RabbitMQ em throttling (ver o README
    da etapa, seção K2-repetição).
- **Efeito na comparação:** a resposta 202 volta antes de a reserva acontecer.
  - O K2-repetição teve mais 202 e mesmo assim latência maior no 20k e no 26k. Isso exclui que o ganho venha do atalho do 202 na latência medida.
  - **Não exclui** que parte do ganho venha da fila desligada no 26k (sem o worker do `admission` e o RabbitMQ disputando CPU e conexões). Isso
    não está isolado.
  - No 32k nenhum dos dois blocos teve 202: p50 de 3.964 contra 4.453 ms, com o efetivo diferente anotado acima.

### O 32k parcial: gerador ou sistema?

**O teto de VUs é consequência da latência do sistema, não de recurso do gerador.** A ordem exata dentro do degrau não fica provada pela janela de
30 s.

- **A latência já era alta antes do 32k:** p50 de 1.437 ms e p95 de 2.971 ms no 26k.
- **O teto pela conta do modelo aberto:**
  - o executor `constant-arrival-rate` precisa de taxa × duração da iteração em VUs;
  - 3000 VUs ÷ ~533 reservas/s ≈ 5,6 s por iteração esgotam o teto;
  - a iteração é a reserva, com p50 de 3.964 ms no 32k, mais a confirmação: as aceitas (~47%) também confirmam.
- **O aviso de VUs:** "Insufficient VUs, reached 3000" às 09:55:04Z (`gerador/k6-console-473284.log`), ~20 s (±1 s) depois do início do degrau
  (09:54:43,6Z).
  - A 1ª janela de 30 s (09:54:43,6Z a 09:55:13,6Z) teve p95 de 5.111 ms (`gerador/summary-degraus-473284.json`, `janelas`), mas **contém** o
    aviso. Por isso não prova sozinha que a latência subiu antes do teto.
- **O gerador não saturou por recurso próprio:** CPU no máximo em 37% durante os degraus e RAM livre acima de 1,73 GB.
- **Os 3.277 descartes deixaram o 32k abaixo do alvo** (27.010 reservas de 32.000). Mesmo com menos carga que o alvo, o p95 passou dos 5 s.

### Dell e host

- **Dell:** coleta da sessão DB, do ref temporário `K8sDB-dell-K2-pos231` (76e266e), pacote de 1.286.038 bytes com sha256
  `832bc00e…cac64fe4b5` conferido.
  - Conexões: pico de 263 (1º às 09:52:48Z), com 75 amostras em 250 ou mais; pico de 241 active às 09:52:07Z.
  - Na coleta inteira, CPU us+sy com pico de 55% e média de 14,3% (`vmstat`; o `mpstat` dá 53% de pico).
  - Na coleta inteira, NVMe com %util máximo de 70% e médio de 20% (`iostat`).
  - Na janela da carga, as médias são de ~21% de CPU e ~36% de NVMe.
  - 0 deadlocks e 0 alertas.
  - **Esperas** (`dell/pg_waits.csv`, amostras-sessão a cada 2 s; pico simultâneo entre parênteses):

    | Espera | Soma | Pico |
    |---|---|---|
    | `Lock/transactionid` | 9.792 | 236 |
    | `Lock/tuple` | 4.626 | 228 |
    | `LWLock/LockManager` | 3.508 | 216 |

    O Dell não saturou: a fila está na espera por lock de linha.
- **Postgres, pela `coleta-host`, no 20k:**

  | Bloco | `pg_conexoes` | `pg_ativas` | `pg_espera_lock` |
  |---|---|---|---|
  | K2-pos-231 | 247 | 243 | 239 |
  | K2-repetição | 247 | 123 | 117 |
  | K2 | 247 | 156 | 153 |

  - No K2-pos-231 há cerca do dobro de sessões ativas do K2-repetição (243 contra 123) e 1,6× as do K2, quase todas esperando lock.
  - Provavelmente pelo Hikari 245 do `stock`: a coleta não separa as sessões por serviço.
- **CPU do host** (`telemetria-host`, média na janela de cada degrau, pela regra acima):

  | Bloco | 20k | 26k | 32k |
  |---|---|---|---|
  | K2-pos-231 | 106,6% | 106,9% | 104,8% |
  | K2-repetição | 109,0% | 109,5% | 109,6% |
  | K2 | 109,6% | — | — |

  - Os 105,4% do K2-repetição no README da etapa usaram outra janela.
  - **Picos por container** na carga inteira (`pods/top-pods-*`, em milicores):
    - aplicação: reservation 1.836, backend 1.631, supabase-pooler 1.335, admission 1.308, stock 1.190;
    - rabbitmq: 1.061, às 09:53:56Z;
    - `istio-proxy`: 937 no backend e 497 no reservation.

### O que não está provado

- **Que a #231 mudou o ponto de parada. Não mudou:** o K2-repetição, sem ela, também parou no 32k.
  - O ganho medido é a latência menor no 20k, com 1 rodada por config e variação entre rodadas grande.
- **Quanto do ganho vem da #231 e quanto da fila desligada no 26k.** A autoescala divergiu entre os blocos (ver acima).
- **Que o lock da linha quente é o limite.** É a leitura mais provável, mas não foi isolada.
  - A favor: o pool real cheio, com ~240 sessões e quase todas em `Lock/transactionid` ou `Lock/tuple`, e o Dell com CPU e disco sobrando.
  - O K1 (L4, sem autoescala, pools da R5) passou pelo 32k com a mesma linha quente e o mesmo pool real de 245. Do K1 para o K2-pos-231 ainda
    mudam o Istio, a autoescala, o Hikari do reservation (1000 → 200) e os workers do `admission`.
- **Por que o 20k ficou em 891 ms no p50.** O teste A do diagnóstico, com o stock em 250 × 1, deu p50 de 163,8 ms no k6 no mesmo degrau
  (`../K2-diagnostico/teste-A/README.md`).
  - Aquele bloco rodou um degrau só, de 3 min.
  - Esta rodada não coletou o Hikari pending do stock nem o access log do Envoy, que explicaram a parada do K2 no diagnóstico.

## Arquivos

- `degraus-473284.csv` e `plano-degraus-473284.json`. O `degraus-473284.html` é gerado localmente e fica fora do git.
- `render-diff-k2-vs-k2pos231.txt`: `diff -u` do render `rodada`/`b1`/db remoto entre e4f862f e ada814b, com a procedência no cabeçalho.
- `gerador/` (ref temporário `K8S-gerador-K2-pos231`, c061543):
  - `summary-degraus-473284.json`;
  - `k6-console-473284.log`, sanitizado pela sessão do gerador;
  - `telemetria-gerador-k2pos231-473284.csv`.
- `host-telemetria/`: `coleta-host-*`, `eventos-*`, `pgss-*`, `registro-*`, `resumo-saga-*`, `saga-*`, `telemetria-host-*` e
  `verificacoes-k2pos231.log`.
- `pods/`: `coleta-pods.sh`, `top-pods-*`, `cgroup-cpu-*` e `replicas-*`.
- `dell/`: recorte do pacote da sessão DB.
  - Arquivos copiados como estão: `mpstat.txt`, `vmstat.txt`, `psi.txt`, `pg_activity.csv`, `pg_waits.csv`, `pg_stat_database_delta.txt`,
    `inicio.txt` e `fim.txt`.
  - `iostat-nvme0n1.txt`: o `iostat` filtrado para as linhas de `avg-cpu` e do `nvme0n1`.
  - `sar-cpu-total.csv`: o `sar` filtrado para a CPU total.
  - O `sar.bin` e o `sar.csv` completos ficaram de fora pelo tamanho (13 MB e 8 MB).
