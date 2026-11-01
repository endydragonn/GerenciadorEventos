# Teste longo (soak) com picos e vales por serviço — design

Data: 2026-11-01. Origem: pedido do usuário depois do diagnóstico do K2 (#228, PR #230) e da #231
(stock como no K1). Vale para a configuração final do K8s: trilha b1, autoescala 1..3, `stock` com
1 réplica fixa e Hikari 245 (#231), db no Dell (`K8S_DB_REMOTO`).

## Objetivo

Rodar a stack por 2 h ou mais com carga que sobe e desce ao acaso em **cada serviço de forma
independente** (vários serviços podem estar em pico ao mesmo tempo). O teste confere três coisas:
**corretude** sob carga longa e variável, **autoescala acompanhando a carga** (subindo e descendo) e
**troca de réplica sem erro**. Não é teste de capacidade: o X e o teto já foram medidos nas rodadas
anteriores.

## Blocos

Dois blocos separados, cada um com 2 h de carga e o mesmo desenho. Só o teto muda.

| Bloco | Teto do bloco (soma dos picos) | O que mede |
|---|---|---|
| S1 | 35k RPM | só a autoescala (acima de 1 réplica, abaixo do teto medido de ~40k) |
| S2 | 50k RPM | autoescala com a fila (202) e o limite (429) ligados |

Cada bloco começa assim, nesta ordem:

1. banco limpo (`limpar-banco.sh` em modo cluster);
2. seed novo;
3. Mailpit vazio (`DELETE /api/v1/messages`);
4. **espera até `readyReplicas=1`** em todos os Deployments com ScaledObject. O `limpar-banco`
   religa as réplicas anotadas, não 1.

A ordem dos blocos é S1 e depois S2.

## Perfil de carga

O k6 roda **5 cenários `ramping-arrival-rate` em paralelo**, um por tipo. Cada tipo tem um perfil
próprio, sorteado a partir de `SEMENTE` (a mesma semente gera o mesmo perfil):

- fases de 3 a 10 min (sorteado), com rampa de 30 a 60 s entre elas;
- cada fase é **pico** (70% a 100% do teto do tipo) ou **vale** (~10% do teto do tipo), com 50% de
  chance para cada;
- o teto do tipo é a fatia dele no teto do bloco (tabela abaixo). Quando todos coincidem no pico, a
  soma chega ao teto do bloco.

A taxa conta **iterações** (fluxos). Um fluxo de cadastro são 2 chamadas e um de reserva com
confirmação também são 2, mas cada um conta como 1 na fatia.

| Tipo | Fatia | Fluxo |
|---|---|---|
| leitura | 60% | `GET /bff/events/{id}`, `GET /bff/events/search` ou `GET /bff/event-wallets/user/{id}` |
| reserva | 30% | `POST /bff/event-wallets` (braço cas). Depois: 60% `POST /confirm`, 10% `DELETE /bff/event-wallets` (cancela), 30% expira sozinha |
| login | 7% | `POST /bff/users/login` com usuário do seed (bcrypt) |
| cadastro | 2% | `request-verification`, depois o código lido no Mailpit, depois `verify-email`. Endereço sempre `soak-<sufixo>-<n>@example.com` |
| escrita de catálogo | 1% | o host cria evento (`POST /bff/events`) e edita (`PUT /bff/events/{id}`, com apresentador) |

O 202 da reserva segue o fluxo do harness atual: uma amostra (`PCT_ACOMPANHA`) é acompanhada até
`pending`, `rejected` ou `timed_out`.

O perfil sorteado é gravado em `perfil-<id>.json`. Para cada minuto, ele registra a taxa de cada
tipo, se o tipo está em pico e o **total esperado de cadastros do bloco**.

## Peças

1. **`carga/soak/carga-soak.js`**: cenário k6 novo, derivado de `aleatorio/carga-aleatoria.js` e da
   admissão de `degraus/lib-admissao.js` (reuso, não cópia).
   - Roda com `--no-summary --no-thresholds --out csv=<arquivo>`, sem nenhuma Trend em memória.
   - Cada amostra leva as tags `tipo` e `fase`. As chamadas ao Mailpit levam `tipo=mailpit`.
   - O `setup()` aborta antes de qualquer cadastro se a API do Mailpit não responder.
   - Nenhum `console.*` imprime corpo de resposta do Mailpit.
2. **`carga/soak/perfil.py`** (ou PS, no padrão do kit): gera os 5 perfis a partir da semente e do
   teto do bloco, e calcula o total esperado de cadastros.
3. **`carga/soak/agregar.py`**: lê o CSV bruto por streaming e grava `soak-<id>-por-minuto.csv`.
   - O arquivo tem, por minuto e por tipo, a taxa, p50/p95/p99 e a contagem por código (2xx, 202,
     400, 429, 5xx).
   - Só esse arquivo e o perfil entram no repo. O CSV bruto (~1 GB por bloco) fica no gerador.
4. **Mailpit como Component compartilhado**: o `mailpit.yaml` sai de `k8s/overlays/e2e/` para
   `k8s/overlays/mailpit/` como `kind: Component`, e o `e2e` passa a consumi-lo. O render do `e2e`
   não pode mudar (diff vazio).
   - No overlay `soak`, o Mailpit sobe com `MP_MAX_MESSAGES` ≥ 2× o total esperado do perfil e
     memória dimensionada para isso.
   - Tem basic auth (`MP_UI_AUTH`, que cobre a API) com credencial efêmera por bloco, gerada fora do
     repo e nunca impressa.
5. **Overlay novo `soak`** = `rodada` + Component Mailpit. **O overlay `rodada` não muda**: os K0,
   K1 e K2 foram medidos com ele, então o render dele tem diff vazio.
   - O `soak` faz um patch de `env` no `notification` com os seis valores: `MAIL_HOST=mailpit`,
     `MAIL_PORT=1025`, `MAIL_SMTP_AUTH=false`, `MAIL_STARTTLS=false`,
     `MAIL_USERNAME=soak@example.com` e `MAIL_PASSWORD=""`.
   - O `guardar` do `cluster.sh` ganha, para o `soak`, uma checagem **fail-closed** que lê o render
     e aborta (nada é criado) se faltar ou estiver errado qualquer um dos seis valores.
   - A API do Mailpit sai por uma NodePort nova, com mapeamento novo no `kind.yaml` (exige recriar o
     cluster).
6. **Seed maior**: 2000 usuários e 2000 eventos de 500 vagas (1M de vagas e 4M de pares). Basta
   para 2 h, porque a expiração devolve vagas.
7. **Coleta**: a de hoje (`coleta-host` em modo cluster, telemetria do host, coletor por pod e Dell),
   com as réplicas de cada serviço a cada 5 s.
8. **`verificar-soak`** (no host):
   - **Pré-bloco**, aborta se:
     - o `notification` vivo (`kubectl get deploy -o jsonpath`) não tiver `MAIL_HOST=mailpit` e
       `MAIL_PASSWORD` vazia;
     - existir ScaledObject `stock`, ou o Hikari do `stock` for diferente de 245 (pré-condição: a
       #231 aplicada);
     - algum Deployment autoescalado tiver `replicas` diferente de 1;
     - o total esperado do perfil passar do `MP_MAX_MESSAGES` do render;
     - o Mailpit não estiver vazio.
   - **Pós-bloco**, nesta ordem:
     1. espera o lag do group `notification` chegar a 0 e registra;
     2. conta o Mailpit filtrado pelo sufixo do bloco;
     3. confere o From de todas as mensagens;
     4. roda o `verificar-rpm`;
     5. calcula os critérios abaixo a partir do por-minuto, do perfil e das réplicas;
     6. só então o bloco seguinte roda o `limpar-banco`.

## Critérios de aprovação (por bloco)

1. **Corretude**:
   - overselling 0 (ativas ≤ capacidade por evento);
   - sagas convergidas (0 órfãs, 0 hold `held` com saga compensada, 0 devolução pendente);
   - no Mailpit, 1 e-mail por `request-verification` com 2xx. **E-mail perdido sempre reprova.**
     **Duplicado só é tolerado se cair a ±60 s de um pod do `notification` saindo** (entrega
     at-least-once documentada no `NotificationConsumer`). Fora disso, reprova;
   - todas as mensagens têm From terminando em `@example.com`;
   - `restartCount` do Mailpit fica em 0 no bloco.
2. **A autoescala acompanha a carga**, em cada serviço autoescalado:
   - quando a métrica do ScaledObject passa do alvo, o serviço ganha 1 réplica em até 60 s;
   - quando fica abaixo do alvo, volta a 1 em até 5 min.

   Os prazos vêm do comportamento do KEDA: consulta a cada 5 s, sobe 1 pod a cada 15 s e desce com
   janela de 120 s e 1 pod a cada 60 s. O `stock` (1 réplica fixa) fica fora deste critério e deve
   ficar em 1 o bloco inteiro.
3. **Troca de réplica sem erro**: 0 respostas 5xx no bloco inteiro, em todos os tipos, exceto
   `tipo=mailpit`. No S2, 202 e 429 são esperados e não reprovam. Os 400 de negócio ("lotado",
   vínculo existente) também não.
4. **Gerador válido**: o bloco só conta se o gerador não saturou (RAM livre ≥ 1 GB, sem "Insufficient
   VUs" fora do S2) e se a memória do k6 ficou estável.

**Limitação conhecida, que não reprova**: as réplicas novas do gateway não recebem tráfego do k6, que
mantém as conexões abertas (teste B do K2-diagnostico). O critério 2 olha a contagem de réplicas, e o
relatório registra a distribuição por pod.

## Segurança

- **Nenhum e-mail sai do cluster.** Isso depende de três coisas:
  - o overlay `soak` com os seis valores de SMTP;
  - a guarda fail-closed do `cluster.sh`, que lê o render;
  - o pré-check do `verificar-soak` no cluster vivo.

  Um `up` com outro overlay (base, completa, rodada) mantém o Gmail real, e o pré-check impede que o
  k6 rode nele.
- **NodePort do Mailpit**: a restrição é **obrigatoriamente** uma regra do firewall do Windows no
  host, que libera a porta só para o IP do gerador, é criada antes do bloco e removida depois. Somam-se
  a basic auth efêmera do Mailpit e o fato de o `listenAddress` do kind ficar em `0.0.0.0`.
  `loadBalancerSourceRanges` e "regra do kind" não filtram no kind, por isso não são usados.
- O CSV bruto, o por-minuto e o perfil não têm segredo: o `--out csv` do k6 grava métricas, tags e
  URL, sem headers nem corpo. As senhas são de teste, do seed.

## Testes (critério de aceite)

1. `cluster.selftest.sh` cobre cada um dos seis valores de SMTP do `soak`, ausente ou errado. Em todos
   os casos, `cluster.sh guarda` sai com código diferente de 0 e "nada foi criado".
2. `cluster.sh renderizar` do `rodada` e do `e2e` dá diff vazio contra o atual. O do `soak` tem
   exatamente 1 Deployment `mailpit`, e o `notification` tem `MAIL_PASSWORD` com valor vazio.
3. Com o Mailpit fora do ar, o k6 termina com 0 chamadas a `/bff/users/request-verification` no CSV
   bruto.
4. O pré-check do `verificar-soak` sai com código diferente de 0 num cluster `base`, num cluster sem a
   #231 e com réplica diferente de 1.
5. Grep em `carga-soak.js`: nenhum endereço fora de `@example.com`.
6. De outra máquina da LAN, `curl http://<host>:<porta>/api/v1/messages` dá timeout. Do gerador sem
   credencial, dá 401. Depois do bloco, `Get-NetFirewallRule -DisplayName <regra>` não acha nada.
7. O `verificar-soak` aborta se o total do perfil passar do `MP_MAX_MESSAGES`. Também registra o lag 0
   do `notification` antes da contagem e um Mailpit com 0 mensagens no início do bloco.
8. `perfil.py` com a mesma semente gera o mesmo perfil, byte a byte. `agregar.py` reproduz os
   percentis de um CSV pequeno de referência.
9. Dry-run curto do soak (por exemplo, 10 min com teto baixo), num cluster `soak` de verdade, verde
   nos critérios 1 a 3.

## Fora de escopo

- Memória e p95 ao longo do tempo (vazamento). O usuário não marcou esse critério.
- Mudar a autoescala, os pools ou o código dos serviços.
- O compose (autoescala opção A).
- Corrigir o L4 do gateway.

## Riscos

- **Tamanho do CSV bruto** (~1 GB por bloco): disco local no gerador, com o espaço conferido antes.
  O agregador lê por streaming.
- **Banco do Dell crescendo por 2 h**: o vigia do DB monitora o disco e as conexões, e a limpeza
  entre os blocos zera o banco.
- **Pré-condição**: o soak só roda depois da #231 (pré-check 8).
