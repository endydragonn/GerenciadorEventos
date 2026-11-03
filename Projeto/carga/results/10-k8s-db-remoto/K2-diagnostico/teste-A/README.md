# K2-diagnostico, teste A (`Deg-338431`), 2026-11-01: DIAGNÓSTICO, não é resultado

Este teste muda uma variável em relação ao diagnóstico 1 (pasta acima): **o `stock` fica igual ao do K1, com Hikari 250 e 1 réplica**
(ScaledObject com `maxReplicaCount` 1). O resto é o mesmo:
- a instrumentação (access log do Envoy, `kubectl logs -f`, raspador, coletor por pod e `coleta-host`), agora com a `telemetria-host`;
- seed novo por cima, sem limpeza: 1000 usuários com sufixo 338431, eventos 933–996 e capacidade 500;
- as réplicas todas em 1 (às 20:19:24Z, sem pod em término);
- 60 s ociosos antes do disparo (o k6 saiu às 20:20:31Z);
- um degrau de 20k por 3 min, com a mesma linha do k6 (850d38e).

A hipótese testada (H1, da interface) é que o Hikari de 60 por pod do `stock` é o gargalo de origem. A cadeia seria: pool esgotado → hold
lento → salto reservation de ~1 s → threads do Tomcat do gateway presas → fila dentro do gateway, porque todo o k6 está num pod só.

## Mudança aplicada (e como desfazer)

| O quê | Antes | Teste A | Desfazer |
|---|---|---|---|
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` do `stock` | 60 | 250 | `kubectl --context kind-gerenciador -n gerenciador set env deploy/stock SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=60` |
| `maxReplicaCount` do ScaledObject `stock` | 3 | 1 | `kubectl --context kind-gerenciador -n gerenciador patch scaledobject stock --type merge -p '{"spec":{"maxReplicaCount":3}}'` |

A instrumentação do diagnóstico 1 continua ligada; o desfazer dela está no README da pasta acima.

**Pool do Supavisor:** a regra conservadora do `Projeto/k8s/README.md` (3 réplicas por serviço mais 1 pod em término) daria 1752 conexões de
cliente, acima do limite de 1500. Por decisão da interface, o rollout do `stock` terminou antes do k6 (sem pod em término). O pior caso na
carga ficou em 3×200 + 3×10 + 3×3 + 3×50 + 3×50 + 250 = 1189.
- **Não houve recusa de cliente.** O vigia do Dell não viu amostra acima de 270 conexões nem "too many clients", FATAL ou PANIC, e não houve
  restart.
- `pg_conexoes` chegou a 247 (`coleta-host`) e o `hikaricp.connections.timeout` ficou em 0 em todos os pods.
- O client count do Supavisor não foi coletado: a API de métricas dele (porta 4000) responde Forbidden.

## Rodada

- **k6:** de 20:20:31Z a 20:25:03Z, com **código de saída 0, sem parada**. Logins terminados às 20:22:02Z (`gerador/`).
- **20k (3 min inteiros):**
  - 60.001 reservas, p50 **163,8 ms**, p95 **2.168,5 ms**, p99 3.519,1 ms, pior janela de 30 s 3.474,0 ms;
  - 0 5xx, 0 descartes, sem "Insufficient VUs";
  - 3.042 respostas 202; das 137 acompanhadas, 45 foram admitidas.
- **Overselling 0 e sagas convergidas** (`host-telemetria/verificacoes-k2diagA.log`).
- **Gerador:** não saturou (CPU no máximo em 28%). RAM livre de 10,11 GB antes, com mínima de 1,92 GB no fim do degrau (o k6 em 8,25 GB).
- **Host** (`telemetria-host`, janela da carga): CPU média de 107,7%, máxima de 113%. RAM livre mínima de 20,3 GB.
- **Dell (DB):**
  - Pico de 263 conexões (pool cheio), 238 active e 33 idle in transaction. CPU us+sy com pico de 45%.
  - `pg_stat_database` em 374 s: +279.854 commits e +52.222 rollbacks, 0 deadlocks.
  - Pacote: `dell/` (ref temporário `K8sDB-dell-K2-diagA`, da95e83, sha256 `7527aee6…b065ee`, 942.487 bytes, conferido).

## O que H1 previa, contra o que aconteceu

Janela da carga: de 20:22:02Z a 20:25:04Z. Fontes: `analise-accesslog-carga.txt` e `pods/actuator-*`.

| Previsão de H1 | Diagnóstico 1 (stock 60 × 3) | Teste A (stock 250 × 1) | Bateu? |
|---|---|---|---|
| pending do stock perto de 0 | média de 142,6, máx. de 237 | média de 9,9, máx. de 238 (só a frio, no 1º ponto às 20:22:02Z; depois 0 na maior parte, com picos de 80, 12, 10, 4 e 1) | sim, fora o arranque |
| entrada do stock na casa do CAS, não ~730 ms | p50 729 / p95 2.208 | p50 **64** / p95 1.642 | sim no p50; o p95 continua alto |
| salto gateway → reservation bem menor que 982 ms | p50 982 / p95 2.458 | p50 **138** / p95 1.760 | sim |
| tempo interno do gateway despencando | entrada p50 5.647 − 982 − 16 ≈ 4,6 s | entrada p50 160 − 138 − 3 ≈ **19 ms** | sim |
| p95 da entrada do gateway na casa do K1 (~1–2 s), sem parada | 9.930, parada | **2.164**, sem parada | perto: um pouco acima de 2 s |

Tempos em ms, salvo onde indicado.

Outros números:
- **CAS do `eventcap`** (`pgss-*`): 60.001 chamadas com média de 296 ms (no diagnóstico 1, 286 ms). A espera pelo lock da linha quente não mudou;
  mudou a fila antes dela.
- **Hikari do stock:** ativo médio de 149, chegando a 250, o teto. O pool real do Supavisor é 245, então acima disso a espera passa para o
  Supavisor.
- **Reservation:** Tomcat ocupado médio de 191, com máximo de 300 (no diagnóstico 1, média de 290). Hikari ativo médio de 23,5, pending 0.
- **5xx e flags:** nenhum 5xx em salto nenhum. Só 10 `DC` no `admission`.
- **Conexões do k6:** 3.006, de novo todas no pod original do gateway (`bq5xg`). As réplicas novas (backend a 2 às 20:22:37Z e a 3 às
  20:22:53Z) não receberam conexão.
  - Aqui isso não derrubou o degrau: com o salto reservation em ~140 ms, as 300 threads do Tomcat do gateway dão conta dos ~333 req/s.

## Leitura (para a interface montar a próxima hipótese)

- Os números batem com H1 no p50 e no fim da fila dentro do gateway. O Hikari 60 × 3 do stock explica a parada do K2 a 20k neste ambiente.
- O p95 continua alto (2,2 s na entrada, 1,6 s no stock), e o CAS segue em ~300 ms em média. Isso aponta para a linha quente do `eventcap`
  (e o pool real de 245 do Supavisor), não para o Hikari.
- O L4 preso no pod original segue igual. Ele vira o gargalo quando o salto reservation passa de ~0,9 s, porque 300 threads ÷ 333 req/s ≈
  0,9 s. É o teste B.
