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

| Bloco | Teto do bloco (soma dos picos) | Gatilho (`ADMISSION_X_RPM`) | O que mede |
|---|---|---|---|
| S1 | 35k RPM | 56000 (padrão) | a autoescala dos serviços por CPU e do `notification` pelo lag; **confirmação em massa com overselling 0 conferido** (reserva espalhada, 60/10/30, 200 vagas por evento: ≈ 240 a 400 mil confirmações conforme a semente; a fronteira de capacidade é exercitada nas sementes altas, só nos últimos 15 a 30 min: "Decisões" 2); o `reservation` fica em 1 réplica (alvo 28k nunca alcançado) |
| S2 | 50k RPM | 4000 | autoescala **inclusive a do `reservation`** (alvo do KEDA = 2000) com a fila (202) ligada e, se a carga conseguir, o limite (429); **convergência da saga** sob reservar/cancelar em massa num evento quente (overselling conferido, não estressado) |

O gatilho é aplicado pelo ambiente do `cluster.sh up` (`ADMISSION_X_RPM=<X> K8S_OVERLAY=soak ...`): o `admission` recebe o X pelo Secret `env-admission` e o alvo do KEDA do `reservation` sai da MESMA
variável (0,5 × X). O overlay `rodada` não muda. Trocar de bloco = `up` de novo com o X do bloco **e reiniciar o `admission`** (o `envFrom` não recarrega); o `verificar-soak pre` confere os dois.

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
| reserva | 30% | `POST /bff/event-wallets` (braço cas). **S1:** espalhada pelos 2000 eventos do seed; depois da reserva aceita, 60% `POST /confirm`, 10% `DELETE /bff/event-wallets` (cancela), 30% expira sozinha. **S2:** 95% vão a 1 evento quente e só cancelam (decisão 2); os 5% restantes seguem o 60/10/30. **Mix efetivo no S2** (a ressalva dos quentes): ≈ 3% confirma, ≈ 95,5% cancela, ≈ 1,5% expira |
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
   - Roda com `--no-thresholds --summary-mode=disabled --out csv=<arquivo>.csv.gz`, sem nenhuma Trend em memória. O gerador tem o k6 v2.2.0: `--no-summary` e `K6_NO_SUMMARY` foram **removidos na v2.0.0**
     e `--summary-mode=disabled` (`K6_SUMMARY_MODE=disabled`) existe desde a v1.3.
   - Cada amostra leva as tags `tipo`, `fase` e `min`, **calculadas por requisição** (não uma vez por iteração: um cadastro esperando o e-mail ou um 202 acompanhado cruzam a fronteira do minuto).
     As chamadas ao Mailpit, a sonda do `setup()` inclusive, levam `tipo=mailpit`.
   - O `setup()` aborta antes de qualquer cadastro se a API do Mailpit não responder.
   - Nenhum `console.*` imprime corpo de resposta do Mailpit.
2. **`carga/soak/perfil.py`** (ou PS, no padrão do kit): gera os 5 perfis a partir da semente e do
   teto do bloco, e calcula o total esperado de cadastros.
3. **`carga/soak/agregar.py`**: lê o CSV bruto (`.csv` ou `.csv.gz`) por streaming e grava `soak-<id>-por-minuto.csv`. Roda no Python 3.14.7 do gerador.
   - O arquivo tem, por minuto e por tipo, a taxa, p50/p95/p99 e a contagem por código (2xx, 202,
     400, 429, 5xx, **status 0** na coluna `c0`, separada de `outros`) e o `inicio_s` do minuto (liga o minuto aos pods que saíram).
   - **Nunca descarta contagem em silêncio:** a amostra que chega com o minuto já fechado é contada por tipo e impressa; o programa sai com código 3 se uma delas tiver 5xx ou status 0.
   - Só esse arquivo e o perfil entram no repo. O CSV bruto fica no gerador, comprimido (`.csv.gz`).
4. **Mailpit como Component compartilhado** (a readinessProbe do Mailpit, `mailpit readyz`, bate em `/readyz`, que o Mailpit registra **fora** da basic auth; por isso não muda com `MP_UI_AUTH`): o `mailpit.yaml` sai de `k8s/overlays/e2e/` para
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
6. **Seed maior**: 2000 usuários e 2000 eventos, com a capacidade **por bloco** (`-Capacidade`, `Get-CapacidadeDoBloco`: **S1 = 200** por evento, 400 mil vagas, para exercitar a fronteira de capacidade; **S2 = 500**, 1M de vagas; 4M de pares; um valor explícito vence). Parte dos eventos do S1 lota nos últimos 15 a 30 min,
   conforme a semente (conta da decisão 2: 0 a 100% dos eventos lotam, "lotado" é 400 de negócio). O S2 acrescenta o(s) evento(s) quente(s) de 20000 vagas; o S1 **não** tem quentes. O modo é um parâmetro **por bloco** do `preparar-soak.ps1` (`-Quentes`: padrão 1 no S2 e 0 no S1, `Get-QuentesDoBloco`), sem duplicar código: sem `QUENTES_IDS` o `carga-soak.js` usa o mapeamento de sempre.
7. **Coleta**: a de hoje (`coleta-host` em modo cluster, telemetria do host, coletor por pod e Dell),
   com as réplicas de cada serviço a cada 5 s.
8. **`verificar-soak`** (no host):
   - **Pré-bloco**, aborta se:
     - o `notification` vivo (`kubectl get deploy -o jsonpath`) não tiver `MAIL_HOST=mailpit` e
       `MAIL_PASSWORD` vazia, ou trouxer `SPRING_MAIL_*`, `SPRING_APPLICATION_JSON`/`JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS` com `spring.mail` ou (no Secret `env-notification`, só as chaves)
       uma chave `spring.mail.*`;
     - o gatilho não for o do bloco (recebe `--bloco S1|S2`): `ADMISSION_X_RPM` do Secret `env-admission` diferente de 56000 (S1) ou 4000 (S2), pod do `admission` que subiu antes do Secret, ou
       `targetValue` do ScaledObject do `reservation` diferente de 0,5 × X;
     - a API do Mailpit não responder **401** a um GET sem credencial (prova que a basic auth está ligada);
     - a regra de firewall `Block` da 8025 não existir, não estiver habilitada, não for de entrada/TCP/8025/`Block` ou o `RemoteAddress` não for **exatamente** o complemento do IP **atual** do gerador (`GERADOR_IP`, informado na partida: o IP é por DHCP, nunca fixo) **mais todo o IPv6**; com o IP do gerador mudado, aborta com "rode o Criar de novo com o IP novo" (fail-safe: o gerador fica bloqueado, a porta não abre);
     - existir ScaledObject `stock`, ou o Hikari do `stock` for diferente de 245 (pré-condição: a
       #231 aplicada);
     - algum Deployment autoescalado tiver `replicas` diferente de 1;
     - o total esperado do perfil passar do `MP_MAX_MESSAGES` do render;
     - o Mailpit não estiver vazio.

     E **grava** no log do bloco (não aborta nem sincroniza) o offset dos três relógios contra uma referência comum (`time.windows.com`): host (`w32tm /stripchart /computer:time.windows.com /samples:1 /dataonly`), nó do kind (`docker exec <cluster>-control-plane date -u`, medido contra o `date` do host no ponto médio da chamada; os timestamps do kubelet vêm da VM do Docker) e gerador (`--gerador-offset-s`, o número que o mesmo `w32tm` imprime lá). O `w32tm /stripchart` imprime *referência − local* (conferido contra um SNTP próprio: relógio local 1,3 s adiantado imprime `-01.30s`), então o `pre` inverte o sinal e grava o *adiantamento* (+ = adiantado); aceita vírgula decimal. Se algum offset relativo passar de 5 s, **avisa** (não aborta).
   - **Pós-bloco**, nesta ordem:
     1. espera o lag do group `notification` chegar a 0 e registra;
     2. conta o Mailpit filtrado pelo sufixo do bloco;
     3. confere o From de todas as mensagens;
     4. roda o `verificar-rpm`;
     5. calcula os critérios abaixo a partir do por-minuto, do perfil e das réplicas (se o critério 3 reprovar, lista os minutos com 5xx/status 0 por tipo ao lado dos pods que saíram neles);
     6. só então o bloco seguinte roda o `limpar-banco`.

## Critérios de aprovação (por bloco)

1. **Corretude** (o que cada bloco *estressa* é diferente):
   - overselling 0 (ativas ≤ capacidade por evento). **S1:** conferido sob confirmação em massa e com a confirmação real (reserva espalhada, 60/10/30, capacidade 200 por evento; ≈ 240 a 400 mil confirmações em 2 h conforme a semente): a fronteira de capacidade é exercitada nas sementes altas (eventos "lotados", 400 de negócio que **não reprova o critério 3**), mas, como cada evento recebe só ≈ 5 RPM, **não há disputa concorrente pela última vaga** (o que se estressa é o caminho `confirm` e o CAS em milhares de linhas diferentes). A conta é "ativas ≤ capacidade" contra a capacidade do próprio banco (200). **S2:** conferido, mas *não* estressado (os quentes só cancelam e nunca enchem). O overselling sob disputa já foi medido na R4/R5 e fica fora do soak;
   - sagas convergidas (0 órfãs, 0 hold `held` com saga compensada, 0 devolução pendente). **S2:** é o que o bloco estressa (hold/release por `holdId`, revive `cancelled → pending` em centenas de milhares de reservar/cancelar);
   - no Mailpit, 1 e-mail por `request-verification` com 2xx. **E-mail perdido sempre reprova.**
     **Duplicado só é tolerado se cair a ±60 s de um pod do `notification` saindo** (entrega
     at-least-once documentada no `NotificationConsumer`). Fora disso, reprova;
   - todas as mensagens têm From terminando em `@example.com`;
   - `restartCount` do Mailpit fica em 0 no bloco.
2. **A autoescala acompanha a carga**, em cada serviço autoescalado (**no S1 o `reservation` fica fora**: com X=56k o alvo do KEDA, 28k RPM, nunca é alcançado de propósito; ver "Decisões"):
   - quando a métrica do ScaledObject passa do alvo, o serviço ganha 1 réplica em até 60 s;
   - quando fica abaixo do alvo, volta a 1 em até 5 min.

   Os prazos vêm do comportamento do KEDA: consulta a cada 5 s, sobe 1 pod a cada 15 s e desce com
   janela de 120 s e 1 pod a cada 60 s. O `stock` (1 réplica fixa) fica fora deste critério e deve
   ficar em 1 o bloco inteiro.
3. **Troca de réplica sem erro**: 0 respostas 5xx **e 0 respostas com status 0** (erro de transporte do k6) no bloco inteiro, em todos os tipos, exceto
   `tipo=mailpit`. 202 e 429 são esperados e não reprovam. Os 400 de negócio ("lotado",
   vínculo existente) também não. Reprovado, o relatório lista os minutos com erro e os pods que saíram neles (horário, serviço, pod).
   **Fila (S2):** 202 obrigatório na reserva (a fila ligou); 429 só registrado, porque só sai por carga (ver "Decisões"). No S1 só registra.
4. **Gerador válido**: o bloco só conta se o gerador não saturou (RAM livre ≥ 1 GB, sem "Insufficient
   VUs" fora do S2) e se a memória do k6 ficou estável.

**Limitação conhecida, que não reprova**: as réplicas novas do gateway não recebem tráfego do k6, que
mantém as conexões abertas (teste B do K2-diagnostico). O critério 2 olha a contagem de réplicas, e o
relatório registra a distribuição por pod.

## Segurança

- **Nenhum e-mail sai do cluster.** Isso depende de três coisas:
  - o overlay `soak` com os seis valores de SMTP;
  - a guarda fail-closed do `cluster.sh`, que lê o render e as chaves do Secret `env-notification` e barra também `SPRING_MAIL_*`, `spring.mail.*` e `SPRING_APPLICATION_JSON`/`JAVA_TOOL_OPTIONS`/
    `JDK_JAVA_OPTIONS` com `spring.mail`;
  - o pré-check do `verificar-soak` no cluster vivo;
  - o `up` com um overlay sem Mailpit (`base`, `rodada`, `completa`) apaga o Mailpit de um cluster anterior (o `apply` não poda), para que trocar de overlay sem `down` não deixe o Mailpit de pé
    com o `notification` de volta ao Gmail. **Fail-closed:** se o Mailpit ainda existe **ou o env VIVO do Deployment `notification` ainda tem `MAIL_HOST=mailpit`** (mesmo sem o Mailpit, apagado à mão) e o lag do group `notification` é > 0 (bloco abortado com backlog `soak-*@example.com`) ou não dá para ler, o `up` aborta
    (o apply tira o patch de SMTP e os pods novos do `notification` consumiriam o backlog pelo Gmail): drene no soak (`pos`) ou `cluster.sh down`. A **checagem** (`checar_poda_mailpit`) roda logo depois do `criar_cluster`, **antes** de qualquer Secret, helm, label ou apply, para o abort não deixar efeito colateral;
    o delete do Mailpit (`podar_mailpit_antigo`) continua depois dos Secrets, antes do apply. **Limite conhecido:** lag 0 só cobre o que já foi publicado no tópico; o `event_outbox` do identity ainda não publicado pelo relay (janela pequena, logo após o último cadastro) não aparece. Com o Redpanda fora o lag já vira -1 e aborta. Não há consulta ao banco para fechar essa janela.

  Um `up` com outro overlay (base, completa, rodada) mantém o Gmail real, e o pré-check impede que o
  k6 rode nele.
- **NodePort do Mailpit**: a restrição é **obrigatoriamente** uma regra **`Block`** do firewall do Windows no host, **permanente até o fim dos testes** (decisão 5): TCP 8025, entrada, para todo o IPv4 **menos**
  o gerador (faixas complementares `0.0.0.0-<ip-1>` e `<ip+1>-255.255.255.255`, mais todo o IPv6; o Docker Desktop costuma ter regras `Allow` largas, de qualquer origem, que o Windows soma, e o `Block` vence o `Allow`, então
  ele não pode incluir o gerador). Criada uma vez pelo usuário como administrador; o `verificar-soak pre` de cada bloco a confere sem administrador; o `Remover` roda no fim dos testes; o `Status` lista a regra
  e as `Allow` largas que cobrem a porta. Somam-se a basic auth efêmera do Mailpit (o pré-check exige 401 sem credencial) e o fato de o `listenAddress` do kind ficar em `0.0.0.0`.
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
6. A regra de firewall existe e bloqueia: de outra máquina da LAN, `curl http://<host>:<porta>/api/v1/messages` dá timeout; do gerador sem
   credencial, dá 401. Offline: `firewall-mailpit.selftest.ps1` cobre as faixas complementares (o gerador fora de todas, os vizinhos dentro, extremos 0.0.0.1 e 255.255.255.254), a conferência da regra
   (cada propriedade errada, o complemento em outras notações, o **IPv6 total** exigido em `::/0` ou `::-ffff:…:ffff` e qualquer outra forma IPv6 reprovada) e o `-WhatIf`; o `verificar-soak.selftest.sh` cobre o `pre` com o `powershell.exe` falso, o IP do gerador mudado (aborta com a instrução) e os relógios (`w32tm` e `docker` falsos; aviso > 5 s sem abortar). A remoção é um item de "fim dos testes" do README do carga.
7. O `verificar-soak` aborta se o total do perfil passar do `MP_MAX_MESSAGES`. Também registra o lag 0
   do `notification` antes da contagem e um Mailpit com 0 mensagens no início do bloco.
8. `perfil.py` com a mesma semente gera o mesmo perfil, byte a byte; o perfil fixo `dry-s2` também (byte a byte, hash fixado no teste) e tem pelo menos 2 subidas vale → pico no tipo reserva. `agregar.py` reproduz os
   percentis de um CSV pequeno de referência, lê `.gz` e **não perde 5xx/status 0 atrasados**: eles vão para uma linha `atrasada=1` do próprio por-minuto (o que o `crit3` soma; testado de ponta a ponta) e o
   programa sai com 3. O k6 carimba `min`/`fase` por requisição.
9. O pré-check recusa o gatilho que não é o do bloco (X vivo, pod antigo, `targetValue`), `spring.mail` por qualquer caminho e uma API do Mailpit sem 401; o critério 3 reprova com `c0` e o
   relatório liga o minuto ao pod que saiu; o `cluster.selftest.sh` cobre a poda do Mailpit, **fail-closed**: com o Mailpit de pé (ou, sem ele, com o `notification` vivo em `MAIL_HOST=mailpit`), lag do group `notification` > 0 ou ilegível aborta (a checagem roda logo depois do `criar_cluster`, antes de qualquer Secret/helm/label/apply; teste de ordem por `awk`) (senão os pods novos do `notification` voltariam ao Gmail e consumiriam o backlog `soak-*@example.com`), e lag 0 poda. Os dois modos de reserva têm teste: `carga-soak.selftest.mjs` (S1 sem `QUENTES_IDS`, espalhada e confirmando; S2 com quente) e `lib-soak.selftest.ps1` (o padrão de quentes por bloco).
10. **Dois** dry-runs curtos do soak, num cluster `soak` de verdade, verdes nos critérios 1 a 3 (comandos no README do carga, "Dry-runs curtos"):
   - **S1:** 10 min, teto baixo (3000), X = 56000;
   - **S2:** 10 min, teto ~20000, X = 4000, com o **perfil FIXO `dry-s2`** (`perfil.py --fixo dry-s2`, `preparar-soak.ps1 -Fixo dry-s2`: não é sorteado por semente; os 5 tipos seguem a mesma tabela: vale 10% por 60 s, pico 100% por 120 s, vale 120 s, pico 120 s, vale 60 s, pico 120 s, rampa de 30 s: **3 subidas bruscas vale → pico**, a 1ª com o `reservation` em 1 réplica, o que garante a 202 na subida). **Não troque de semente (não há): se der 0 de 202, é defeito do mecanismo; pare e relate.** Exercita a **troca de bloco** (`cluster.sh up` com o X novo + `rollout restart deploy/admission` + `pre`) e o critério da fila (**202 obrigatório**);
   - portões que só dão para ver ao vivo, no roteiro de ambos: `Get-NetTCPConnection -LocalPort 8025 -State Listen` (ver se há `[::]`); o `pre` alcançando `localhost:8025` com o Block ativo (loopback); `firewall-mailpit.ps1 -Acao Status -GeradorIp $env:GERADOR_IP` logo depois do `Criar` (formato real do `RemoteAddress`); `descartadas` da reserva; RSS e restarts do Mailpit no S2; os relógios gravados no `pre`.

## Fora de escopo

- Memória e p95 ao longo do tempo (vazamento). O usuário não marcou esse critério.
- Mudar a autoescala, exceto o gatilho do soak (`ADMISSION_X_RPM` por bloco), os pools ou o código dos serviços.
- O compose (autoescala opção A).
- Corrigir o L4 do gateway.

## Riscos

- **Tamanho do CSV bruto**: vários GB por bloco (estimativa: ~7 milhões de requisições no S2 × ~14 linhas de métrica do k6 ≈ 10 a 15 GB sem compressão; ~1 a 2 GB em `.csv.gz`). O k6 grava direto em
  `.csv.gz` (`--out csv=soak-<id>-k6.csv.gz`) e o agregador lê o `.gz` por streaming. O gerador tem 1,5 TB livres: o espaço ainda é conferido antes.
- **Banco do Dell crescendo por 2 h**: o vigia do DB monitora o disco e as conexões, e a limpeza
  entre os blocos zera o banco.
- **Pré-condição**: o soak só roda depois da #231 (pré-check 8).

## Decisões

### 1. Gatilho do soak por bloco (decidido pelo usuário)

O pico de reserva de cada bloco é 70% a 100% do teto do tipo: S1 = 30% × 35k = 10,5k, então **7,35k a 10,5k**; S2 = 30% × 50k = 15k, então **10,5k a 15k**. O pico máximo do S1 é o pico mínimo
do S2: **nenhum X único serve aos dois blocos**, daí o gatilho por bloco. Como funcionam as duas peças (`ADMISSION_X_RPM` é a mesma variável nas duas):

- **KEDA do `reservation`:** `R = min(3, ceil(hot / (0,5·X)))`, com `hot` = RPM do evento mais quente (`adm:hot`).
- **Fila do `admission`:** liga com `hot ≥ 0,8·X·R` (`R` = `adm:replicas` = réplicas prontas do `reservation`) e desliga com `hot < 0,7·X·R` **e** a espera do evento vazia.
- Em regime, `R ≥ hot/(0,5·X)`, então `0,8·X·R ≥ 1,6·hot`: **a fila só liga em regime com `R` no teto 3** (`hot ≥ 2,4·X`) **ou de passagem**, antes de subir a réplica.

| | S1, X = 56000 | S2, X = 4000 |
|---|---|---|
| alvo do KEDA (0,5·X) | 28000 | 2000 |
| `hot` no pico (veja 2) | **≈ 3,7 a 5,3 RPM** (reserva espalhada: taxa de 7,35k a 10,5k ÷ 2000 eventos) | 9,975k a 14,25k (0,95 × taxa de reserva, 1 evento quente) |
| `hot` no vale (10% do teto) | ≈ 0,5 RPM | 1,425k |
| `R` | 1 sempre | 3 em todo pico; 1 no vale |
| limiar para ligar a fila | 44,8k com `R`=1 (nunca é alcançado) | 3,2k com `R`=1, 6,4k com 2, **9,6k com 3** (o pico mínimo, 9,975k, já passa dele: liga em todo pico, não só na subida) |
| limiar para desligar | | 8,4k com `R`=3 (o pico fica acima: a fila fica ligada); 2,8k com `R`=1 (o vale 1,425k desliga, com a espera vazia) |
| resultado | fila nunca liga; `reservation` fica em 1 réplica (alvo 28k, nunca alcançado) | 202 em todo pico; escala e desce o `reservation` |

**No S1 o critério 2 do `reservation` NÃO se aplica** (o `crit2 --bloco S1` o deixa fora e imprime isso). O S1 mede a autoescala dos outros serviços (CPU e lag do `notification`). O
`reservation` só é medido no S2.

**Teto mínimo de um dry-run do S2:** o `hot` de pico precisa passar de 3,2k (limiar com `R`=1): `0,95 × 0,7 × 0,3 × teto ≥ 3200`, ou seja teto ≳ 16k (use 17k ou mais). O dry-run de 3000 RPM só serve para o
`-Bloco S1` (a fila não liga, e o `fila (S1)` só registra); no S2 daria 0 respostas 202 e o critério da fila reprovaria.

### 2. Eventos quentes só no S2; reserva espalhada no S1 (decisão do usuário) e a conta de capacidade

O usuário escolheu o mix **"realista"** (60% confirma / 10% cancela / 30% expira). Quentes que só cancelam o distorceriam (≈ 3 / 95,5 / 1,5). Por isso o modo da reserva é um parâmetro **por bloco** (`preparar-soak.ps1 -Quentes`, padrão do bloco em `Get-QuentesDoBloco`): **o S2** (o único que precisa da fila e do X = 4000) tem quentes; **o S1 não**, e estressa o overselling e a confirmação.

**S1: reserva espalhada.** Sem `QUENTES_IDS`, o `carga-soak.js` usa o mapeamento `intercalado` do kit (usuário `⌊i / 2000⌋`, evento `i % 2000`, pares únicos) com o 60/10/30 e confirmação de verdade. O `hot` é o de um evento comum (≈ 5 RPM): a fila nunca liga e o `reservation` fica em 1.

*Conta de capacidade do S1 (decisão do usuário: 200 vagas por evento, padrão do bloco).* O `preparar-soak.ps1` semeia o S1 com `-Capacidade` 200 por padrão (`Get-CapacidadeDoBloco`; o S2 segue com 500, e um `-Capacidade` explícito vence), como o `Get-QuentesDoBloco` faz com os quentes: 2000 × 200 = **400 mil vagas** (e 4 M de pares). A ocupação esperada sai de quatro fatos:

- **Tentativas** = área do perfil da reserva (teto 30% × 35k = 10,5k; pico 70–100%, vale 10%): média ≈ 5k/min × 120 min ≈ **627 mil** (8 sementes: 404 mil a 770 mil; o teórico de um bloco todo no pico máximo seria 1,26 M). A semente é sorteada por bloco, então ela decide a ocupação.
- **Distribuição:** o `intercalado` manda a tentativa `i` ao evento `i % 2000` (usuário `⌊i / 2000⌋`), então cada evento recebe **o mesmo número de tentativas ±1**: média 313 (de 202 a 385 conforme a semente).
- **Destino de cada tentativa aceita:** 60% confirma (a vaga some de vez), 10% cancela (volta em segundos, por evento) e 30% expira (volta em `max_time_in_line` = 120 s + até 30 s da varredura: **≈ 2 a 4 em voo por evento** no pico, ≈ 8 mil no total).
- **Quando um evento "lota":** quando confirmadas + em voo chegam a 200. Com 60% de confirmação isso pede **≈ 330 tentativas no evento, ou seja ≈ 660 mil no total**: um pouco mais que a média (627 mil, ≈ 188 confirmadas + 2 a 4 em voo por evento, desvio ≈ 9 por evento). **A lotação depende da semente e só acontece nos últimos 15 a 30 min**, quando o acumulado de tentativas passa dos ≈ 600 a 650 mil.

Conta feita com o `perfil.py` de cada semente (tempo de cada tentativa pela integral da taxa da reserva) e um Monte Carlo do 60/10/30 com a devolução (script de conferência, não versionado; o sorteio do Monte Carlo usa a própria semente do perfil, então os números valem pela ordem de grandeza, não no detalhe). Sementes 1 a 8 (as do intervalo de 404 mil a 770 mil):

| semente | tentativas | eventos que lotam (de 2000) | o 1º lota em | metade dos que lotam já lotou em | respostas 400 "lotado" | confirmadas (teto 400 mil) |
|---|---|---|---|---|---|---|
| 2 | 404 mil | 0 | – | – | 0 | 242 mil |
| 6 | 443 mil | 0 | – | – | 0 | 265 mil |
| 1 | 597 mil | 24 | 106 min | 115 min | 91 | 358 mil |
| 7 | 616 mil | 116 | 105 min | 110 min | 472 | 370 mil |
| 4 | 701 mil | 1850 | 102 min | 111 min | 37 mil | 398 mil |
| 5 | 734 mil | 1983 | 88 min | 98 min | 68 mil | 400 mil |
| 8 | 748 mil | 1997 | 95 min | 105 min | 81 mil | 400 mil |
| 3 | 770 mil | 2000 | 90 min | 101 min | 104 mil | 400 mil |

Leitura: com a **semente média (≈ 600 a 620 mil, sementes 1 e 7)** lotam de 1% a 6% dos eventos (dezenas a poucas centenas), depois de ≈ 105 min; com **semente alta (≥ 700 mil, 4 de 8)** lotam 92% a 100% dos eventos entre ≈ 88 e 102 min e os últimos 15 a 30 min devolvem de 37 mil a 104 mil respostas 400 "lotado"; com **semente baixa (≤ 443 mil)** nenhum lota. **"Lotado" é 400 de negócio:** não reprova o critério 3 (que só conta 5xx e status 0) e aparece em `c400` da reserva no por-minuto; como as expiradas e canceladas devolvem a vaga, o evento volta a aceitar por instantes (a lotação oscila na fronteira). Pares: 627 mil tentativas tocam só ≈ 313 usuários (o `intercalado` percorre os eventos antes de trocar de usuário) e `i` < 4 M: **nenhum par se repete**, então **0 respostas 400 de "vínculo existente"**.

**Escolha da semente do S1 (decisão do usuário: alguns eventos têm de lotar).** A semente é escolhida ANTES de rodar, pelo total esperado de tentativas de reserva do perfil (área da taxa da reserva no `perfil-<id>.json`, que o `preparar-soak.ps1` mostra antes de rodar): é uma propriedade do perfil, não do resultado. Regra: usar uma semente com **≥ 700 mil tentativas** esperadas (pela simulação, lotam 92% a 100% dos eventos a partir de ≈ 88 a 102 min). **Semente usada no S1: 4** (≈ 701 mil tentativas, a mais baixa das que lotam com folga, para passar menos tempo em modo "lotado"). Totais com teto 35k e 2 h, sementes 1 a 12: ≥ 700 mil = 3 (770k), 4 (701k), 5 (734k), 8 (748k); 580 a 620 mil = 1, 7, 9, 12 (lotam só 1% a 6%, perto de 105 min, com risco de nenhum lotar se o gerador descartar iterações); ≤ 480 mil = 2, 6, 10, 11 (não lotam).

**O critério de overselling não muda:** "ativas ≤ capacidade por evento" é conferido pelo `verificar-rpm` contra a capacidade do PRÓPRIO banco (`stock.eventcap.capacity`, 200 nos eventos comuns do S1), e agora a fronteira é de fato tocada nas sementes altas (o contador `quant` chega a 0 e o CAS devolve o 400). *Trade-offs (decisão do usuário):* (a) mesmo com a fronteira exercitada **não há disputa concorrente pela última vaga** (≈ 5 RPM por evento): é o CAS em milhares de linhas diferentes, não a disputa; o overselling "estressado" no sentido da disputa segue sendo da R4/R5; (b) nas sementes altas, nos últimos 15 a 30 min a reserva vira rejeição barata (sem append nem confirm) e a confirmação em massa fica em ≈ 360 a 400 mil (não 376 mil fixos), então o S1 mede menos escrita nesse trecho; (c) com semente baixa o S1 não chega à fronteira (o padrão é 200, não uma garantia de lotar). Para voltar ao comportamento sem lotação, `-Capacidade 500`; `-e MAPEAMENTO=sequencial` no k6 concentra 2000 tentativas seguidas por evento (a linha do `eventcap` fica quente e enche; o `hot` sobe para ≈ 7,35k a 10,5k, ainda abaixo do alvo do KEDA, 28k, e do limiar da fila, 44,8k, mas a maioria das tentativas vira 400 "lotado" e as confirmações caem para ≈ 100 mil): nenhum dos dois é o padrão.

**S2: eventos quentes.** A reserva do bloco mira **poucos eventos quentes** (`hot` = taxa de reserva do evento mais quente; com `k` quentes iguais seria taxa/`k`): usa-se **k = 1** (`-Quentes 1`) e `PCT_QUENTE` = **95%** das reservas vão
a ele (90% daria um pico mínimo de 9,45k, abaixo do limiar de 9,6k com `R`=3), o resto (5%) segue o mapeamento par-único de sempre e mantém o 60/10/30 nos 2000 eventos comuns. Logo
`hot = 0,95 × taxa de reserva`: as faixas da tabela acima. **Mix efetivo da reserva no S2: ≈ 3% confirma, ≈ 95,5% cancela, ≈ 1,5% expira** (95% cancelam + 5% × 60/10/30). O 2º seed do `preparar-soak.ps1` cria o(s) quente(s) com nome `<PREFIXO>-Q-E<k>` (o `verificar-rpm`, por prefixo, os cobre).

**Reserva quente cancela logo.** O par `(user_id, event_id)` é a PK de `walletevent`: um usuário só tem 1 reserva ativa no evento, e as reservas quentes repetem o par (2000 usuários, ~810 mil tentativas em 2 h
no evento quente do S2). Sem devolver, depois de ~2000 aceitas o resto seria 400 "vínculo existente", sem exercitar o `stock` nem a saga. Por isso toda reserva quente aceita é **cancelada logo** (e toda 202 quente é
acompanhada até `pending`, senão o par ficaria `pending` até expirar). Os quentes **nunca confirmam**: a confirmação e a expiração ficam por conta dos 5% de reserva nos eventos comuns (e, no S1, de toda a reserva).

**Conta da capacidade (2 h do S2).** Cancelar só appenda o evento; a vaga volta **de forma assíncrona** (`StockReleaseConsumer`) e o mesmo usuário já pode reservar de novo e pegar um hold novo. Os holds em aberto
no `stock` são então `reservas quentes por segundo × (tempo até cancelar + atraso da devolução)`, **sem teto pela PK** (a PK limita as linhas de `walletevent`, não os holds). Taxa quente no pico do S2:
14,25k/min = 237,5/s. Tolerando 60 s de atraso na devolução (uma troca de réplica do `stock`/`notification`, que é justamente o que se testa): 237,5 × 60 ≈ 14,3k. **Capacidade do quente = 20000**
(`-CapacidadeQuente`; ≈ 84 s do pico), **finita** (não `-1`, que pularia o CAS e o `stock.hold`). Um atraso maior que isso esgota o evento e vira 400 "lotado" (negócio, não 5xx), que aparece no por-minuto.
*Trade-off:* com 20000 vagas e 2000 usuários o evento quente **nunca fica cheio por reservas ativas**, então a conta "ativas ≤ capacidade" do `verificar-rpm` é satisfeita trivialmente aqui (nos eventos comuns do S2, de 500 vagas, a reserva é só 5% e também não enche); o que o S2 confere de verdade é a **convergência da saga** (0 órfãs, 0 hold `held` com saga compensada, 0 devolução pendente) depois de centenas de milhares de reservar/cancelar. **O overselling sob
disputa já foi medido nas rodadas R4/R5 e fica fora do soak**; o S1 o confere, sob confirmação em massa, sem a disputa por vaga. Os demais eventos do seed continuam com 500 vagas.

### 3. O 429

Lido no `admission` (`FilaDeAdmissao.enfileirar`): o 429 sai de (a) o usuário ter mais que `admission.max-waiting-per-user` (5) mensagens em espera (script Lua) e (b) a fila passar de `max-length`
(100000, `reject-publish`). Em regime a espera por usuário é ≈ 0 (32 workers drenam em milissegundos), então (a) exige **um usuário concentrando a taxa**: provocado **só por carga**, sem mudar código: `INSISTENTES` = 1
usuário (o índice 0 do seed) recebe `PCT_INSISTENTE` = 25% das reservas quentes (≈ 2,5k a 3,6k RPM = 42 a 59 por segundo de um usuário no pico do S2), o que dá 5 em espera quando a latência da fila passa de ~85 a 120 ms. Se
a fila ficar mais rápida que isso o 429 **não sai de forma natural**: o critério do S2 fica **202 obrigatório, 429 só registrado** (registrado sem perguntar, como combinado), e o relatório diz
"0 respostas 429". (b) não é provocável sem encher 100 mil mensagens.
Cuidado de leitura: todas as 202 quentes são acompanhadas (≥ 1 s de VU cada); se os VUs da reserva acabarem, o k6 descarta iterações (`descartadas` do por-minuto) e o `hot` real cai abaixo do planejado. Confira `descartadas`
da reserva no dry-run e em cada bloco.

### 4. Status 0 reprova o critério 3 (decidido pelo usuário)

O status 0 (erro de transporte do k6: timeout, conexão recusada ou resetada) conta como 5xx no critério 3, exceto em `tipo=mailpit`. Motivo: é exatamente o que "troca de réplica sem erro" quer provar. A
limitação do L4 do gateway pode causar status 0 quando um pod sai com keep-alive aberto; isso é **achado real para o relatório, não exceção**. Por isso o `agregar.py` tem a coluna `c0` (separada de `outros`) e,
quando o critério 3 reprova, o `verificar-soak pos` lista os minutos com 5xx/status 0 por tipo ao lado dos pods que saíram nesses minutos (horário, serviço, pod; das amostras do `amostrar`, a cada 5 s, ±5 s),
para ligar (ou não) a causa ao L4 do gateway. Trocar para "só registrar" é uma constante (`C0_REPROVA` em `criterios.py`). **Os relógios do gerador (o `inicio_s` vem do k6), do host (as amostras de pods) e do nó do kind (timestamps do kubelet, da VM do Docker) precisam estar
sincronizados**: o `verificar-soak pre` **grava** o offset dos três contra `time.windows.com` e **avisa** se a diferença passar de 5 s (não aborta, não sincroniza; `w32tm /resync` e rodar o pre de novo). Com deriva maior que ±5 s o "nenhum pod saiu neste minuto" pode estar errado.

### 5. Firewall permanente, só o `Block` (decidido pelo usuário)

A regra de **bloqueio** da 8025 (`Block`, entrada, TCP 8025, todas as origens **menos** o gerador, por faixas complementares) é criada **uma vez** pelo usuário, como administrador, e **fica até o fim dos testes**: não é
criada nem removida a cada bloco. O `Remover` continua, para o usuário rodar no fim. **A regra `Allow` só do gerador deixou de existir** (ponytail: uma peça a menos): o `Block` já não inclui o gerador, e quem decide se
o gerador alcança a porta são as regras `Allow` que o host já tem (o Docker Desktop e várias do Windows liberam todas as portas, qualquer origem; o `-Acao Status` as lista). Se uma máquina não tiver nenhuma,
o `Criar` avisa e o passo do gerador (401 sem credencial) acusa o timeout; aí basta uma `Allow` do IP do gerador na 8025. *Trade-off:* sem a nossa `Allow` o acesso do gerador depende de regras que não são do kit.
O `verificar-soak pre` de **cada bloco** confere, **sem administrador**, que a regra existe, está habilitada, é de entrada, TCP, LocalPort 8025, ação `Block`, perfil Any e que o `RemoteAddress` é **exatamente** o
complemento do IP do gerador (o gerador fora, todo o resto do IPv4 dentro; compara os intervalos, não o texto): `Get-NetFirewallRule` + `Get-NetFirewallPortFilter` + `Get-NetFirewallAddressFilter` pelo `powershell.exe`
(`soak/conferir-firewall.ps1`). O IP do gerador é o `GERADOR_IP` que o kit já usa (`make ... GERADOR_IP=<ip>`; `verificar-soak.sh pre --gerador-ip`). O `Block` cobre **todo o IPv6** além do IPv4 complementar ao gerador (decisão do usuário: o gerador tem IPv6 global, mas o k6 usa a URL IPv4, então o IPv6 inteiro fica fechado): o `Criar` grava a faixa `::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff` e o `conferir-firewall.ps1` **exige** esse item (aceita `::/0` ou a faixa, as formas que o Windows pode devolver; qualquer outra forma IPv6 reprova).
**O IP do gerador não é fixo** (DHCP: já foi 192.168.68.54/22 e pode mudar): vem sempre do `GERADOR_IP`. O `pre` de cada bloco confere que o IP atual, informado na partida, é exatamente a exceção da regra; se não for, aborta com "rode o Criar de novo com o IP novo" (fail-safe: o gerador fica bloqueado, a porta não abre).
