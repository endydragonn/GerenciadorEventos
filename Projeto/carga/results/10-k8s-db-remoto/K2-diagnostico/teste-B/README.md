# K2-diagnostico, teste B (B0 e B1), 2026-11-01: DIAGNÓSTICO, não é resultado

**HB:** com as conexões do k6 presas num pod só do gateway, escalar o backend não dá capacidade. Num degrau em que um pod do gateway satura,
abrir as conexões já espalhadas entre 3 pods melhora o p95.

**Base fixa** nas duas rodadas, igual ao teste A:
- o `stock` em Hikari 250 e 1 réplica;
- a mesma instrumentação e o mesmo `kubectl logs -f`;
- seed novo por cima;
- 60 s ociosos antes do disparo;
- um degrau constante de **40k por 90 s** (~60 mil iterações, o volume do teste A; a 44k o uso ficaria colado no gatilho da fila, 0,786).
  - O `PARADA_JANELA_S` foi para 90, porque o preparar exige que ela divida a duração do degrau. Vale igual nas duas rodadas.

| Rodada | Variável |
|---|---|
| **B0** (controle) | backend com min 1 e max 3, réplicas em 1 no início; as conexões ficam presas no pod original |
| **B1** | backend com `minReplicaCount` 3 no ScaledObject, os 3 pods Ready e aquecidos antes de o k6 abrir as conexões; o kube-proxy as distribui no connect |

**Desfazer o B1:** `kubectl --context kind-gerenciador -n gerenciador patch scaledobject backend --type merge -p '{"spec":{"minReplicaCount":1}}'`.
O teste A (stock em 250 × 1) e a instrumentação continuam aplicados; o desfazer deles está nos READMEs de `teste-A/` e da pasta acima.

## Previsões, registradas ANTES do B1

1. **Se HB vale:** o tempo interno do gateway no p50 cai de ~3,8 s (B0) para dezenas de ms, e as conexões do k6 ficam ~1/3 por pod.
2. **Mas o gargalo de baixo já aparece no B0:** o Tomcat do reservation em 300 de 300 e pending no Hikari do stock. Então é provável que a fila
   só desça um andar e o p95 do k6 melhore pouco.
   - Se for isso, o resultado vale como "espalhar o gateway não basta enquanto reservation e stock estão no teto", e não como refutação de
     HB.
3. **Comparar com o B0** nos mesmos campos, mais:
   - a fração de 202 (B0: 24,9%; se a do B1 sair de 0,5× a 2× disso, a fila vira uma segunda variável);
   - o tempo interno do reservation (entrada do reservation − salto ao stock).

## B0 (`Deg-339462`), controle

- **k6:** de 20:38:06Z a 20:41:08Z, com exit 99 (parado por p95 na janela de 90 s).
  - p50 4.521, p95 10.167, p99 12.418 ms; 34.467 reservas, **23.714 descartes** ("Insufficient VUs" 11 s depois de a carga começar).
  - 0 5xx, overselling 0, sagas convergidas.
- **Códigos na entrada do gateway** (access log):

  | Código | n | % | p50 (ms) | p95 (ms) |
  |---|---|---|---|---|
  | 201 | 13.087 | 36,2% | 5.328 | 10.190 |
  | 202 | 9.003 | 24,9% | 2.535 | 6.521 |
  | 400 (negócio) | 10.290 | 28,4% | — | — |
  | 429 | 1.988 | 5,5% | — | — |
  | 0 (DC) | 1.817 | 5,0% | — | — |

- **Por salto**, p50 / p95 em ms:
  - entrada do gateway 4.396 / 10.124;
  - gateway → reservation 578 / 2.977 e gateway → admission 12 / 274;
  - entrada do reservation 444 / 2.638;
  - entrada do stock 188 / 2.450.
  - Tempo interno do gateway no p50: **~3.800** (4.396 − 578 − 12). Tempo interno do reservation no p50: ~253 (444 − 191).
- **Conexões do k6:** 3.006, todas no pod original (`bq5xg`), do começo ao fim. O backend escalou a 2 às 20:40:27Z e a 3 às 20:40:43Z; as
  réplicas novas ficaram com 0.
- **CPU, no cgroup:**
  - gateway quente: app 0,72 núcleo e `istio-proxy` 0,81, sem throttling. **O pod satura de threads, não de CPU.**
  - réplicas novas do gateway: 0,06–0,11.
  - rabbitmq: throttling em 13% dos períodos.
- **Gargalo de baixo:**
  - Tomcat do reservation em 300 de 300 (média de 235);
  - Hikari do reservation com pending médio de 6,9, chegando a 93, com 2 réplicas;
  - Hikari do stock com ativo médio de 199 de 250 e pending médio de 17,5, chegando a 191.
- **5xx no access log:** 23 respostas 503 da app do `admission` (com fail-open) e 20 no polling `GET /bff/event-wallets/admission`. Não houve
  `UF`, `UO` nem `URX`.
- **Host:** CPU média de 110,7%. **Dell:** active 240, idle-in-tx 66 e o vigia sem nada.
- **Arquivos do B0:** `B0/` (`degraus-339462.*`, `gerador/` do ref `K8S-gerador-K2-diagB0` 69a767d, `accesslog/` com gzip, `pods/`,
  `host-telemetria/` e `dell/` do ref `K8sDB-dell-K2-diagB0` 7b2689f, sha256 `fd939e25…29cdb1` conferido).

## B1 (`Deg-340211`), backend com 3 réplicas fixas

- **Preparo:** `minReplicaCount` 3 no ScaledObject `backend` às 20:43:16Z; seed às 20:46:03Z. Às 20:49:31Z estavam o backend em 3/3 e o resto
  em 1/1, sem pod em término. O k6 saiu às 20:50:36Z.
  - As duas réplicas novas do gateway subiram ~6 min antes da carga e ficaram ociosas: JVM no ar, mas sem tráfego de aquecimento.
- **k6:** até 20:53:38Z, com exit 99 (parado por p95 na janela de 90 s).
  - p50 3.358, p95 8.872, p99 10.521 ms; 37.961 reservas, **20.136 descartes** ("Insufficient VUs" 7 s depois de a carga começar).
  - 0 5xx, overselling 0, sagas convergidas.
  - No acompanhamento da fila apareceram 21 `timed_out` e 178 `sem_estado` (no B0, 0 e 0).
- **Códigos na entrada do gateway** (access log):

  | Código | n | % | p50 (ms) | p95 (ms) |
  |---|---|---|---|---|
  | 201 | 13.803 | 34,6% | 5.487 | 8.625 |
  | 202 | 5.490 | **13,8%** | 1.368 | 3.636 |
  | 400 (negócio) | 9.306 | 23,3% | — | — |
  | 429 | 9.363 | **23,5%** | — | — |
  | 0 (DC) | 1.903 | 4,8% | — | — |

- **Conexões do k6:** 3.006, divididas em ~1/3 por pod desde o início: `bq5xg` 1.004, `blkd7` 1.013 e `242ns` 989. Os POSTs também se dividiram
  em ~1/3 por pod em cada intervalo de 10 s.
- **CPU, no cgroup:** gateway com app de 0,37 a 0,73 núcleo e `istio-proxy` de 0,37 a 0,38 por pod. Reservation: 1,01 núcleo no pod novo e 0,47
  no original. Stock: 0,39. rabbitmq com throttling em 10% dos períodos. Soma dos containers (`top`): média de 7,9 núcleos.
  - A CPU do host não foi medida: a `telemetria-host` não gravou nesta rodada.
- **Gargalo de baixo:**
  - Tomcat do reservation em 300 de 300 nos dois pods (média de 289 e 276); o reservation escalou a 2 às 20:52:38Z.
  - Hikari do reservation com pending médio de 19,6, chegando a 171, no pod novo.
  - Hikari do stock com ativo médio de 208 de 250 e pending médio de 55,3, chegando a 243.
- **5xx no access log:** 8 respostas 503 da app do `admission` e 3 no polling. Não houve `UF`, `UO` nem `URX`.
- **Dell:** active 242, idle-in-tx 43 e o vigia sem nada. Pacote `B1/dell/` (ref `K8sDB-dell-K2-diagB1` 314ba0b, sha256 `4612e4ad…cd8ea2`,
  conferido).

## B0 × B1

| Campo | B0 (conexões presas) | B1 (3 pods desde o início) |
|---|---|---|
| Conexões do k6 por pod do gateway | 3.006 / 0 / 0 | 1.004 / 1.013 / 989 |
| Entrada do gateway, p50 / p95 | 4.396 / 10.124 | 3.314 / 8.793 |
| Gateway → reservation, p50 / p95 | 578 / 2.977 | **2.199 / 4.731** |
| **Tempo interno do gateway**, p50 | **~3.800** | **~1.100** (3.314 − 2.199 − 17) |
| Entrada do reservation, p50 | 444 | 2.114 |
| **Tempo interno do reservation**, p50 | ~253 (444 − 191) | **~1.040** (2.114 − 1.071) |
| Entrada do stock, p50 / p95 | 188 / 2.450 | **1.068 / 3.574** |
| Tomcat do reservation (média / máx. de 300) | 235 / 300 | 289 e 276 / 300 |
| Hikari pending do stock (média / máx.) | 17,5 / 191 | 55,3 / 243 |
| k6 p50 / p95 / p99 | 4.521 / 10.167 / 12.418 | 3.358 / 8.872 / 10.521 |
| Reservas e descartes | 34.467 e 23.714 | 37.961 e 20.136 |
| 202 (% do total) | 24,9% | 13,8% (0,55× o B0) |
| 429 (% do total) | 5,5% | 23,5% |
| 5xx no k6 | 0 | 0 |
| Overselling | 0 | 0 |

Tempos em ms.

**Leitura, contra as previsões registradas antes:**
- **Previsão 1 (HB) bateu em parte.** As conexões ficaram ~1/3 por pod, e o tempo interno do gateway caiu de ~3,8 s para ~1,1 s. Não caiu para
  dezenas de ms.
- **Previsão 2 bateu.** A fila desceu um andar:
  - o tempo interno do reservation foi de ~250 ms para ~1 s, com o Tomcat dele no teto nos dois pods;
  - a entrada do stock foi de 188 ms para 1.068 ms no p50, com o pending do Hikari dele subindo de 17,5 para 55,3 em média;
  - o p95 do k6 melhorou pouco (10,2 s → 8,9 s) e a rodada parou de novo.
  - Pela regra combinada, vale como "espalhar o gateway não basta enquanto reservation e stock estão no teto", não como refutação de HB.
- **Fila:** a fração de 202 do B1 (13,8%) ficou em 0,55× a do B0, dentro da faixa de 0,5× a 2×, mas perto da borda. Já as 429 subiram de 5,5%
  para 23,5%: respostas baratas do `admission` que mudam o caminho. O registro fica aqui para a interface julgar se isso é uma segunda
  variável.

## Leitura final e ressalvas

**HB confirmada em parte.** Espalhar as conexões do k6 entre 3 pods tira a fila do gateway, mas ela desce para o reservation e o stock, que
estão no teto, e o p95 melhora pouco.

Ressalvas:
- **As 429 subiram de 5,5% (B0) para 23,5% (B1).** São respostas baratas do `admission`, que mudam o caminho, e por isso uma segunda variável
  possível entre as rodadas. As 202 ficaram em 0,55× as do B0, dentro da faixa combinada, mas perto da borda.
- **No B1, a fila acompanhada teve 21 `timed_out` e 178 `sem_estado`** (0 e 0 no B0): reservas enfileiradas sem resposta dentro do teto de
  consulta de 35 s.
- **A `telemetria-host` não gravou no B1.** A CPU do host dessa rodada não tem dado; no B0 a média foi de 110,7%.
- **As duas réplicas novas do gateway ficaram ~6 min ociosas antes do B1:** JVM no ar, mas sem aquecimento por tráfego.

Estado deixado no cluster, até o usuário decidir se desfaz: o B1 (backend com `minReplicaCount` 3), o teste A (stock em Hikari 250 e
`maxReplicaCount` 1) e a instrumentação. O desfazer de cada um está no README da pasta acima, em `teste-A/` e no começo deste arquivo.
