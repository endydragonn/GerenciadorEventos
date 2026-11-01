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
