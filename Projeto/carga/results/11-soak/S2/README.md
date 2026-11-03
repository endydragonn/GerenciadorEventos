# Soak — bloco S2 (issue #232), 2026-11-02

**Resultado: REPROVADO nos critérios 1 (e-mail duplicado) e 3 (5xx na reserva).**

- **Passou:** reservas (overselling 0, sagas convergidas) e critério 2 (autoescala).
- **Causas prováveis:** abaixo, com evidência; ainda não foram reproduzidas isoladamente.
- **Nenhuma correção foi aplicada na data do bloco.** O S1 não foi repetido. Depois vieram as correções 02af3d9 e #235, e os blocos S2b e S1b.

| | |
|---|---|
| Cluster | kind, trilha b1 (Istio), overlay `soak`, db no Dell (`192.168.68.50:5433`), `ADMISSION_X_RPM=4000` |
| Perfil | `perfil-S2.json` (nesta pasta): semente 257867, teto 50000 RPM, 7200 s (commit 8c41ceb) |
| Seed | `Soak-380059`, sobre o banco limpo (`limpar-banco` em modo cluster) |
| k6 | v2.2.0 no gerador (192.168.68.54), `MAX_VUS=1000` (baixado pela regra de memória: 7,4 MB por VU alocado). Partiu às 08:00:57Z e terminou às ~10:01Z |
| Relógios | maior diferença de 2,07 s (host e nó adiantados ~2 s, gerador em +0,01 s) |

## Números

| Tipo | Requisições | 2xx | 202 | 400 | 429 | 5xx | status 0 | Descartadas |
|---|---|---|---|---|---|---|---|---|
| leitura | 1.672.808 | 1.672.808 | — | 0 | 0 | 0 | 0 | 166 |
| reserva | 2.367.906 | 1.666.671 | 662.892 | 909 (todos "vínculo") | 36.970 | **464** | 0 | 48.197 |
| login | 192.669 | 192.669 | — | 0 | 0 | 0 | 0 | 2 |
| cadastro | 51.786 | 51.785 (25.892 `verify-email`) | — | 1 | 0 | 0 | 0 | 28.755 |
| escrita | 56.502 | 56.502 | — | 0 | 0 | 0 | 0 | 0 |

- **Reservas** (`verificar-rpm`, código 0):
  - overselling 0 (conferido; nenhum evento lotou, então a disputa pela última vaga não foi estressada neste bloco, por desenho);
  - 24.740 confirmadas, 12.359 expiradas e 6.167 canceladas;
  - sagas convergidas: 0 velhas, 0 órfãs e 0 devoluções pendentes;
  - `reserva_400{motivo}`: vínculo = 909, lotado = 0, outro = 0 (stdout do `agregar.py`, em `agregar-S2.log`).
- **Critério 2**: 0 violações. Subidas e descidas avaliadas:

  | Serviço | Subidas | Descidas |
  |---|---|---|
  | reservation | 4 | 3 |
  | admission | 4 | 3 |
  | backend | 3 | 2 |
  | catalog | 2 | 4 |
  | identity | 1 | 0 |
  | notification | 4 | 4 |
- **Carga entregue**: ok pela regra do S2, que desconta as descartadas do esperado. Sem o desconto, o cadastro ficou em 47% (25.892 `verify-email` 2xx de 54.649 esperados; 28.755 descartadas) e a reserva em 94,5%.
- **Gerador**:
  - RAM livre mínima de 2,92 GiB;
  - k6 com pico de 7,33 GiB;
  - CPU no máximo em 28% (a amostra de 62% das 08:00:09 é anterior à partida do k6);
  - 5 `status 0` no `tipo=mailpit` (timeouts da busca, fora do critério 3).
- **Mailpit**: 0 restarts e 26.155 mensagens.
- **Fail-open do admission**: 399 `POST /internal/admissions` sem resposta (`Read timed out` ou "Redis indisponível"). As reservas
  seguiram síncronas. Não reprova (`failopen-admission-S2.log`).
- **DB** (Dell, ref `K8sDB-dell-soak-S2`, commit 5513f7a):
  - 0 deadlocks e 0 conflitos;
  - pico de 263 conexões, com o pool cheio várias vezes;
  - CPU (us+sy, recalculada do mpstat da ref na coleta inteira) com média de 22,3% (a sessão DB reportou 23%) e pico de ~100% (09:17–09:41 e 09:53–09:54);
  - ~1.290 commits por segundo;
  - `meu_banco` foi de 14 MB para 892 MB;
  - nas janelas dos 5xx de 08:00, 08:01, 08:29 e 09:11, o db estava longe do limite: CPU até 25% e conexões abaixo do pool.

## Critério 1: 262 e-mails duplicados

**Causa provável:** timeout do SMTP com o Mailpit saturado; o retry reenvia o e-mail.

Fatos:

- **Contagem.** 26.155 mensagens para 25.893 endereços, 0 perdidas. Fonte: `mailpit-duplicatas-S2.csv` (as 501 cópias dos endereços duplicados, com o destinatário em hash sha256, a hora e o pod). 239 endereços têm mais de uma cópia: 218 com 2, 19 com 3 e 2 com 4.
- **Um evento por cadastro.** O mesmo evento foi **enviado mais de uma vez**; não houve pedido duplo:
  - `request-verification`: 25.893 respostas 2xx (`verificacoes_2xx` do por-minuto);
  - `verify-email`: 51.785 − 25.893 = 25.892 respostas 2xx;
  - `notification.processed_event`: 25.893 linhas no bloco, todas `VerificationCodeRequested` (contagem por minuto em `processed_event-S2-por-minuto.csv`; o export bruto ficou fora do git, em `E:\TCC\evidencias\soak-S2\processed_event-S2.csv`, sha256 `b9a4e56f05ce0a2e18e535a4b415bc511ec9efeefbeb9360f49c62ca9e78e7cf`).
- **Os intervalos formam uma escada.** Cada degrau é o timeout de leitura do JavaMail (`mail.smtp.timeout=5000`) mais o backoff
  exponencial do consumer (1, 2 e 4 s, `EventsKafkaConfig`):
  - da 1ª para a 2ª cópia: 231 de 239 em ~6 s;
  - da 2ª para a 3ª: 21 de 21 em ~7 s;
  - da 3ª para a 4ª: 2 de 2 em ~9 s;
  - os 8 casos fora de ~6 s (um de 7,4 s e sete de 12 a 36 s) são tentativas intermediárias que falharam sem gravar.
- **O Mailpit satura antes de o notification escalar.** Por minuto (`tipo=mailpit` do k6):
  - com o cadastro a ~870 por minuto, o p95 da API sobe de 94 ms (08:10) para 1,4 s (08:14);
  - às 08:28 o p95 já está em 17 s, com o notification ainda em 1 réplica;
  - o lag cresce e o KEDA leva o notification a 3 réplicas;
  - nos 51 minutos com 3 réplicas (fonte: `soak-S2-replicas.csv`, fora do git pelo `.gitignore`), o p95 fica em ~13 s; o cadastro concluído cai para ~100 por minuto e o resto é descartado.
- **Todas as 239 duplicatas caem nesses 51 minutos.** Nos 66 minutos com 1 réplica houve 0, inclusive em 19 minutos com ≥ 300 cadastros
  por minuto. Nos poucos minutos com 1 réplica e a API do Mailpit já lenta (08:28, 17 s) também não houve duplicata. O p95 do k6 mede a
  API do Mailpit, não o SMTP: a resposta do SMTP passar de 5 s parece exigir também os envios simultâneos das 3 réplicas.
- **Retries sem causa no log.** O pod que sobreviveu registrou 100 `Record in retry and not yet recovered` nesses minutos (`notification-retry-S2.log`), sem WARN nem
  ERROR. A causa só é logada a partir da 10ª tentativa.

Leitura:

- **Mecanismo.** O consumer grava o checkpoint, envia e só então comita (`NotificationConsumer`). Quando o Mailpit grava a mensagem mas
  demora mais de 5 s para responder ao `DATA`, o JavaMail lança timeout. A transação volta, o checkpoint some e o retry envia de novo. É a
  entrega at-least-once documentada no consumer, exposta por um destino lento.
- **Gatilho.** O Mailpit (uma réplica, SQLite) atende ao mesmo tempo o SMTP e a busca do k6: foram 88.910 chamadas à API, e o polling
  cresce quando ele fica lento.
- **Papel das réplicas.** As 3 réplicas do notification são consequência do lag, mas provavelmente também contribuem: 3 sessões SMTP
  simultâneas num Mailpit já lento. Separar os dois efeitos exige reproduzir (Mailpit lento com 1 réplica contra 3).

**Mesmo pod, nova tentativa.** O Message-ID é gerado a cada envio (`SimpleMailMessage`), mas traz o hostname do pod. Em 235 dos 239
endereços, todas as cópias saíram do **mesmo pod**: é o retry do próprio consumer. Os outros 4 trocaram de pod no meio do retry, num
rebalance de subida (às 08:16, 08:29 e 09:29). Fonte: a coluna `pod_notification` do `mailpit-duplicatas-S2.csv`; os headers brutos (Message-ID, Date e To, sem o corpo) ficaram fora do git, em `E:\TCC\evidencias\soak-S2\`.

**Ainda não provado:** a classe exata da exceção, que não foi logada.

## Critério 3: 464 respostas 5xx

**Causa provável:** 503 na consulta da fila, com o admission saturado no degrau da rampa.

Fatos:

- **Os 464 são todos `503` em `GET /bff/event-wallets/admission`.** É a consulta do resultado de uma reserva em fila. Pelo contrato da
  #43, ela responde 503 com o `admission` fora.
  - Fonte: CSV bruto do k6 no gerador (ref `SOAK-gerador-S2-5xx`), com a contagem em `soak-S2-5xx-contagem.csv`.
  - Por minuto: 232 às 08:01, 1 às 08:30, 224 às 09:12, 3 às 09:54 e 4 às 09:55.
  - Nenhum outro endpoint, método ou status. Nenhum pod saiu nesses minutos.
- **Cada janela é um degrau de rampa com o admission ainda em 1 pod pronto** (fonte: `soak-S2-replicas.csv` e `soak-S2-pods.csv`, fora do git pelo `.gitignore`).
  - 08:01: às 08:01:28 a CPU do admission salta de 2% para 170% do request; às 08:01:39 chega a 442% (~1,1 core, com request de 250m
    e limit de 2). Só 1 pod fica pronto até 08:01:44; o 2º fica pronto às 08:01:44 e o 3º às 08:02:01.
  - 09:11:45–09:12:24: o mesmo padrão, de 155% a 224%, com 1 pod pronto até 09:12:08.
  - Reservation e backend sobem junto: até 5 JVMs arrancam no mesmo nó do kind.
- **Log do gateway.** O `AdmissionIndisponivelHandler` ("serviço admission indisponível na consulta") registra 456 ocorrências (as 8 linhas estão em `failopen-admission-S2.log`; o `admission-503-S2.log` traz 6 delas, do pod que sobreviveu):
  - 08:01: 1 + 1 + 168 + 9;
  - 08:30: 49;
  - 09:12: 1;
  - 09:54:58: 224, que são do minuto 71 (o limitador de log acumula desde 09:12:05);
  - 09:55: 3.
  - Ficam 8 respostas que não chegaram ao log.
- **Log do admission.** Nos mesmos segundos, o pod antigo registra `sem confirmação do broker em 200 ms` (RabbitMQ) e responde
  `503 Redis indisponível` (timeout do Redis). O `POST` da reserva faz fail-open (399 no bloco) e não gera 5xx. A consulta não tem
  fail-open.
- **Caminho da reserva.** Reservation e stock sem erro nesses minutos; o stock só tem os WARN de reconciliação do `eventcap`. O db estava
  folgado, exceto em 09:53–09:54.

Leitura:

- **Carga.** O degrau da rampa leva a carga do admission de ~0 a ~4× o request em segundos.
- **Efeito.** O único pod pronto passa a responder acima do timeout do gateway, e o Redis e o RabbitMQ ficam acima dos 200 ms do
  admission.
- **Janela.** O HPA leva ~15 a 35 s para ter outro pod pronto, subindo 1 pod a cada 15 s. Nesse intervalo, a consulta da fila devolve
  503.
- **Cliente.** O Flutter trata esse 503 como instável e continua consultando (`painel_reserva.dart`). O k6 conta o 503 como 5xx.

**Ainda não provado:** se o atraso vem da CPU do próprio pod ou da disputa do nó (o host não teve a CPU coletada neste bloco). Os logs dos
pods que desceram se perderam.

## Ressalvas

- **Credencial do Mailpit.** Foi passada ao gerador por mensagem entre sessões, por decisão do usuário. O valor não aparece em nenhum
  arquivo.
- **Fuso dos horários.** O `soak-S2-verificacoes.log`, o `verificar-rpm-S2.log` e o `registro-*.txt` estão em UTC−3. O `inicio_s` do
  por-minuto está em epoch.
- **Fora do git.** As amostras do `amostrar` (`soak-S2-replicas.csv` e `soak-S2-pods.csv`) ficam fora pelo `.gitignore`. O CSV bruto do
  k6 ficou no gerador. Os logs completos dos pods ficaram no host, em `E:\TCC\evidencias\soak-S2\`; os excertos versionados não têm
  segredo nem e-mail.
- **Saga.** A `coleta-host` deste bloco não gravou o `saga-*.csv` nem o `resumo-saga-*.txt`. As sagas estão no `verificar-rpm-S2.log`.
