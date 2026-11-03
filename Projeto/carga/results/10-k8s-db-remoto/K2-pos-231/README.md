# K2-pos-231 (`Deg-473284`), 2026-11-03, issue #237

A mesma rodada do K2 (trilha `b1`, autoescala 1..3, db no Dell), agora com o `stock` da #231: 1 réplica fixa, Hikari 245, `Recreate` e sem
ScaledObject. É um A/B contra o K2 e o K2-repetição que isola a #231.

**Veredito, em uma linha.** Com a #231 o b1 passou do 20k (o K2 parou nele) e chegou ao mesmo ponto de parada do K2-repetição, o 32k, com
latência menor nos dois primeiros degraus. O limite provável agora, não isolado, é o lock da linha quente do `eventcap` no Dell: o pool real
fica cheio, com até 239 sessões esperando lock, e a CPU e o disco do Dell sobram.

## Configuração

- **Branch:** `Issue-237`, sobre a `Issue-232` em ada814b (contém a #231 e a #235). Nenhum código Java mudou desde o K2: as `*.slim:k8s` são as
  mesmas.
- **Cluster novo:**
  - `K8S_ADMISSION_MIN=1 K8S_OVERLAY=rodada SLIM_TAG=k8s K8S_DB_REMOTO=192.168.68.50:5433 Projeto/k8s/cluster.sh up --trilha b1`.
  - Depois, `cluster.sh db-teste`, com rc 0.
- **Admission com `K8S_ADMISSION_MIN=1`,** para o A/B limpo da #231. A config final usa 2 (#235), validado no soak.
  - O cluster subiu primeiro com o mínimo 2.
  - O `up` foi reaplicado com `K8S_ADMISSION_MIN=1` antes da limpeza, e o ScaledObject vivo do `admission` tinha `minReplicaCount` 1 antes da escada.
- **Render contra o K2:** comparei o `cluster.sh renderizar --trilha b1`, com as mesmas variáveis, entre e4f862f (K2; a manifest do K2-repetição,
  a7da861, é a mesma) e este bloco. A diferença está em [`render-diff-k2-vs-k2pos231.txt`](render-diff-k2-vs-k2pos231.txt) e é só a do
  `stock`:
  - `replicas: 1`;
  - `Recreate` no lugar de `RollingUpdate`;
  - Hikari 60 → 245;
  - sem o `ScaledObject/stock`.
  - Fora do render, o `kind.yaml` ganhou a porta 8025 do soak (#232), sem Service por trás no `rodada`.
- **Pools:** reservation 200, stock 245, identity 50, catalog 50, backend 10 e notification 3, com o pool real do Supavisor em 245
  (`host-telemetria/registro-*`). Os workers do `admission` são 32 por réplica.
- **Limpeza:** `CARGA_K8S=kind-gerenciador limpar-banco.sh`, com rc 0, liberada pelo usuário na sessão. Ficaram só os zeros e 1 linha em
  `identity.users`.
- **Seed:** o mesmo do K0, do K1 e do K2 (`preparar-degraus.ps1`): 1000 usuários e 868 eventos de capacidade 500.
- **Réplicas antes do k6:** todas em 1 às 09:48:33Z, pelo `kubectl`.
- **Gerador:** clone em **850d38e**, o mesmo k6 do K0, do K1 e do K2, sem jogo nem Steam.
  - A coluna `commit` do CSV e do plano (dd2e8a2) é o commit do host que rodou o `preparar`, não o do k6.
  - RAM livre de 9,57 GB antes e mínima de 1,73 GB às 09:55:34Z, com o k6 em 8,1 GB. CPU no máximo em 37%
    (`gerador/telemetria-gerador-k2pos231-473284.csv`).
- **k6:** de 09:49:39Z a 09:55:44Z, com código de saída **99**. Os 1000 logins do setup levaram 27,9 s
  (`gerador/k6-console-473284.log`).
  - Os degraus começam a 90 s do início e se espaçam por 106 s (60 s de carga + 46 s de folga, `carga-degraus.js`):
    - 20k de 09:51:09Z a 09:52:09Z;
    - 26k de 09:52:55Z a 09:53:55Z;
    - 32k a partir de 09:54:41Z.
- **Coletas:**
  - host: `coleta-host`, `telemetria-host` e o coletor por pod (`pods/coleta-pods.sh`, a cada 5 s), de 09:48:56Z até as sagas zerarem;
  - Dell: sessão DB, `coleta-k8s-K2-pos231`, de 09:49:10Z a 09:57:42Z, com os vigias ligados (0 alertas).

## Resultado

As latências estão em ms. A tabela sai de `degraus-473284.csv`, e o gráfico está em `degraus-473284.html`.

| RPM alvo | efetivo | p50 | p95 | p99 | p95 pior janela 30 s | 202 | 5xx (reserva / confirma) | descartes |
|---|---|---|---|---|---|---|---|---|
| 20k | 20.001 | 891,1 | 2.875,9 | 3.819,1 | 3.132,1 | 2.462 | 0 / 0 | 0 |
| 26k | 26.001 | 1.436,7 | 2.970,8 | 3.595,1 | 3.050,8 | 0 | 0 / 0 | 0 |
| 32k (parcial) | 27.010 | 3.963,9 | **5.515,2** | 6.258,9 | 5.807,7 | 0 | 0 / 0 | **3.277** |

- **Parada no 32k:** p95 de 5.515 ms na janela de 60 s.
- **5xx:** 0 na reserva e na confirmação, nos três degraus. Nenhum serviço logou ERROR (`kubectl logs` de backend, reservation, stock, admission,
  identity e catalog nos últimos 20 min) e houve 0 restart.
- **Overselling:** 0 (`host-telemetria/verificacoes-k2pos231.log`).
  - 868 eventos e 36.608 ativas: 35.678 confirmed + 930 pending, além de 892 expired.
  - Nenhum evento com overselling.
- **Sagas** (`verificar-rpm`, rc 0): convergidas, com 0 velhas, 0 hold `held` com saga compensated, 0 devolução pendente, 0 órfã inversa e 0
  `quant` divergente.
- **Fila:** no 20k, 115 reservas acompanhadas: 40 admitidas e 75 `rejected`, 0 `timed_out`.

### K2 × K2-repetição × K2-pos-231, por degrau

Réplicas na ordem backend/reservation/identity/catalog/admission/notification, como na `coleta-host`, mais o `stock` à parte. "→" indica as
mudanças dentro da janela do degrau.

| Bloco | Degrau | p50 | p95 | 202 | descartes | 5xx (res. / conf.) | réplicas (b/r/i/c/a/n) | stock |
|---|---|---|---|---|---|---|---|---|
| K2 | 20k | 2.943,5 | 5.019,6 (parou) | 2.025 | 0 | 0 / 0 | 2/1/3/1/1/1 → 3/1/2/1/2/1 | 1 → 3 (fim) |
| K2-rep | 20k | 1.795,0 | 4.841,3 | 5.592 | 0 | 0 / 0 | 1/1/3/1/1/1 → 3/1/3/1/3/1 | 1 → 3 |
| K2-rep | 26k | 1.550,5 | 3.222,5 | 6.798 | 0 | **67 / 1** | 3/1/2/1/3/1 → 3/1/1/1/3/1 | 3 |
| K2-rep | 32k (parcial) | 4.453,1 | 5.940,9 (parou) | 0 | 5.227 | 0 / 0 | 3/2/1/1/3/1 | 3 |
| **K2-pos-231** | 20k | 891,1 | 2.875,9 | 2.462 | 0 | 0 / 0 | 1/1/2/1/1/1 → 2/2/2/1/2/1 | **1** |
| **K2-pos-231** | 26k | 1.436,7 | 2.970,8 | 0 | 0 | 0 / 0 | 3/2/1/1/3/1 → 3/2/2/1/3/1 | **1** |
| **K2-pos-231** | 32k (parcial) | 3.963,9 | 5.515,2 (parou) | 0 | 3.277 | 0 / 0 | 3/2/1/1/3/1 | **1** |

**Fontes:**
- p50, p95, 202, descartes e 5xx: `../K2/degraus-331542.csv`, `../K2-repeticao/degraus-335785.csv` e `degraus-473284.csv`.
- Réplicas b/r/i/c/a/n: a `coleta-host` de cada bloco (`host-telemetria/coleta-host-*.csv`), na janela do degrau.
- Stock:
  - K2: `kubectl` às 18:26:56Z, sem fonte versionada (ver o README da etapa);
  - K2-rep: `../K2-repeticao/pods/replicas-k8sK2rep.csv` (2 às 19:38:16Z e 3 às 19:38:27Z);
  - aqui: `pods/replicas-k8sK2pos231.csv` (87 de 87 amostras em 1).

Para referência, o K1 (trilha `nenhuma`, autoescala travada em 1, pools da R5) rodou os 14 degraus. Ele teve p50 de 25,1, 24,7 e 122,7 ms em 20k,
26k e 32k, e o 1º degrau degradado foi o 32k (`../K1/degraus-308164.csv`).

### Por que houve 202 com X = 56000 (e a comparação de latência)

- O gatilho da fila é **por evento, numa janela de 200 ms** (script Lua do `admission`): uso = RPM do evento ÷ (X × réplicas do
  `reservation` lidas em `adm:replicas`). Liga com uso ≥ 0,80 e só volta ao síncrono com uso < 0,70 **e** a espera do evento vazia.
- A `coleta-host` amostra a cada 1 s o RPM do evento mais quente (`redis_hot`, a chave `adm:hot`) e as réplicas (`redis_replicas`). O uso calculado
  nessas amostras **nunca chegou a 0,80** em nenhum dos três blocos, nas janelas dos degraus:

  | Bloco | Degrau | `redis_hot` máx. | réplicas lidas | uso máx. nas amostras | `admission.requests` máx. |
  |---|---|---|---|---|---|
  | K2 | 20k | 30.340 | 1 | 0,54 | 842 |
  | K2-rep | 20k | 43.793 | 1 | 0,78 | 2.060 |
  | K2-rep | 26k | 40.106 | 1 | 0,72 | 1.106 |
  | K2-rep | 32k | 27.450 | 2 | 0,25 | 0 |
  | K2-pos-231 | 20k | 25.769 | 1 → 2 | 0,46 | 718 |
  | K2-pos-231 | 26k | 39.375 | 2 | 0,35 | 0 |
  | K2-pos-231 | 32k | 39.493 | 2 | 0,35 | 0 |

- **Leitura (provável, não provada):** a fila ligou em picos de 200 ms que a amostra de 1 s não vê. O mapeamento sequencial põe toda a carga
  num evento por vez, e com a latência alta do b1 as chegadas ao `admission` se agrupam. Ligada, a fila só desliga com a espera do evento vazia
  (histerese). O mesmo aconteceu nos três blocos: o K2-repetição também teve 202 no 20k (5.592) e no 26k (6.798).
- **A diferença de 202 entre os blocos segue as réplicas do `reservation`.**
  - Aqui o KEDA levou o `reservation` a 2 às 09:51:46Z, no meio do 20k (`coleta-host`, `repl_reservation`), e o `adm:replicas` passou a 2. Isso
    dobra o denominador do uso, e a fila não ligou mais no 26k nem no 32k (`rabbit_requests` 0).
  - No K2-repetição o `reservation` só foi a 2 no 32k, e a fila ligou no 20k e no 26k.
- **Efeito na comparação:** a resposta 202 volta antes de a reserva acontecer, então mais 202 tende a baixar a latência medida da reserva.
  - O K2-repetição teve **mais** 202 que o K2-pos-231 no 20k e no 26k e mesmo assim teve latência maior nos dois degraus.
  - Então o ganho do K2-pos-231 nesses degraus não vem da fila.
  - No 32k nenhum dos dois blocos teve 202, e a comparação ali é direta: p50 de 3.964 contra 4.453 ms.

### O 32k parcial: gerador ou sistema?

**O sistema veio primeiro.** O teto de VUs do gerador foi consequência.

- **Latência antes do teto de VUs.**
  - A latência já subia antes do 32k: p50 de 1.437 ms no 26k.
  - Na 1ª janela de 30 s do 32k (09:54:41Z a 09:55:11Z) o p95 já era de 5.111 ms (`gerador/summary-degraus-473284.json`, `janelas`).
  - O "Insufficient VUs, reached 3000" veio às 09:55:04Z, 23 s depois do início do degrau (`gerador/k6-console-473284.log`).
- **O teto é a conta do modelo aberto.**
  - O executor `constant-arrival-rate` precisa de taxa × duração da iteração em VUs.
  - Cada iteração faz a reserva e a confirmação (`PCT_CONFIRMA` 100), e as duas juntas levam vários segundos neste degrau (o p50 da reserva
    sozinho foi de 3.964 ms). Com ~533 reservas/s, isso esgota as 3000 VUs.
  - A CPU do gerador ficou no máximo em 37% e a RAM livre não caiu abaixo de 1,73 GB: o gerador não saturou por recurso próprio.
- **Os 3.277 descartes deixaram o 32k abaixo do alvo** (27.010 reservas de 32.000). Mesmo com menos carga que o alvo, o p95 passou dos 5 s.

### Dell e host

- **Dell** (coleta da sessão DB, ref temporário `K8sDB-dell-K2-pos231`, 76e266e, sha256 `832bc00e…cac64fe4b5` conferido):
  - conexões: pico de 263 às 09:54:59Z, com 75 amostras em 250 ou mais; pico de 241 active às 09:52:07Z;
  - CPU us+sy com pico de 55% e média de 14,3%;
  - NVMe com %util máximo de 70% e médio de 20%;
  - 0 deadlocks e 0 alertas.
  - **Esperas** (`dell/pg_waits.csv`, amostras-sessão a cada 2 s; pico simultâneo entre parênteses):

    | Espera | Soma | Pico |
    |---|---|---|
    | `Lock/transactionid` | 9.792 | 236 |
    | `Lock/tuple` | 4.626 | 228 |
    | `LWLock/LockManager` | 3.508 | 216 |

    O Dell não saturou: a fila está na espera por lock de linha.
- **Postgres, pela `coleta-host`, nas janelas dos degraus:**

  | Bloco | Degrau | `pg_conexoes` | `pg_ativas` | `pg_espera_lock` |
  |---|---|---|---|---|
  | K2-pos-231 | 20k | 247 | 243 | 239 |
  | K2-repetição | 20k | 247 | 123 | 117 |
  | K2 | 20k | 235 | 138 | 137 |

  Com o Hikari 245 o `stock` leva ao banco o dobro de sessões simultâneas, e quase todas ficam esperando o lock da linha.
- **CPU do host** (`telemetria-host`, média na janela do degrau): 103% no 20k, 104% no 26k e 103% no 32k. No K2-repetição foram 107,5% a 108,9%.
  - Picos por container (`pods/top-pods-*`, carga inteira, em milicores):
    - aplicação: reservation 1.836, backend 1.631, supabase-pooler 1.335, admission 1.308, stock 1.190;
    - `istio-proxy`: 937 no backend e 497 no reservation.

### O que não está provado

- **Que o lock da linha quente é o limite.** É a leitura mais provável, mas não foi isolada nesta rodada.
  - A favor: o pool real cheio, com ~240 sessões e quase todas em `Lock/transactionid` ou `Lock/tuple`, e o Dell com CPU e disco sobrando.
  - O K1 (L4, sem autoescala, pools da R5) passou pelo 32k com a mesma linha quente e o mesmo pool real de 245. Do K1 para o K2-pos-231 ainda
    mudam o Istio, a autoescala, o Hikari do reservation (1000 → 200) e os workers do `admission`.
- **Por que o 20k ficou em 891 ms no p50.** O teste A do diagnóstico, com o stock em 250 × 1, deu 160 ms no mesmo degrau
  (`../K2-diagnostico/`).
  - Aquele bloco rodou um degrau só, de 3 min.
  - Esta rodada não coletou o Hikari pending do stock nem o access log do Envoy, que explicaram a parada do K2 no diagnóstico.

## Arquivos

- `degraus-473284.csv`, `degraus-473284.html`, `plano-degraus-473284.json`
- `render-diff-k2-vs-k2pos231.txt`: diferença do render `rodada`/`b1`/db remoto entre o K2 (e4f862f) e este bloco, com
  `K8S_ADMISSION_MIN=1`.
- `gerador/` (ref temporário `K8S-gerador-K2-pos231`, c061543):
  - `summary-degraus-473284.json`;
  - `k6-console-473284.log`, sanitizado pela sessão do gerador;
  - `telemetria-gerador-k2pos231-473284.csv`.
- `host-telemetria/`: `coleta-host-*`, `eventos-*`, `pgss-*`, `registro-*`, `resumo-saga-*`, `saga-*`, `telemetria-host-*` e
  `verificacoes-k2pos231.log`.
- `pods/`: `coleta-pods.sh`, `top-pods-*`, `cgroup-cpu-*` e `replicas-*`.
- `dell/`: recorte do pacote da sessão DB, que traz `mpstat.txt`, `vmstat.txt`, `psi.txt`, `pg_activity.csv`, `pg_waits.csv`,
  `pg_stat_database_delta.txt`, `inicio.txt` e `fim.txt`, e mais dois arquivos filtrados:
  - `iostat-nvme0n1.txt`: o `iostat` filtrado para as linhas de `avg-cpu` e do `nvme0n1`;
  - `sar-cpu-total.csv`: o `sar` filtrado para a CPU total.

  O `sar.bin` e o `sar.csv` completos ficaram de fora pelo tamanho (13 MB e 8 MB).
