# Soak — bloco S1b (issue #232), 2026-11-03

**Resultado: APROVADO nos critérios 1 a 3, com o critério 4 (gerador válido) intacto. Teve 0 respostas 5xx, overselling 0, e os 37.253 e-mails chegaram sem perda nem duplicação.**

O S1b repete o S1, com a mesma semente, a mesma capacidade e um sufixo novo. É o primeiro S1 que testa o e-mail: na `S1-tentativa1` os 37.253 cadastros deram 400 por causa do telefone, que já foi corrigido. Ele rodou depois do S2b, no cluster recriado com `ADMISSION_X_RPM=56000`.

| | |
|---|---|
| Cluster | kind, trilha b1 (Istio), overlay `soak`, db no Dell (`192.168.68.50:5433`), `ADMISSION_X_RPM=56000`, `admission` no mínimo 2 (#235), commit ffc4b8b |
| Perfil | `perfil-S1b.json` (nesta pasta): semente 4, teto 35000 RPM, 7200 s, 200 vagas por evento, reserva espalhada (sem quentes), 37.254 cadastros esperados |
| Seed | `Soak-455933`: 2000 usuários e 2000 eventos de 200 vagas, sobre o banco limpo (`limpar-banco` em modo cluster) |
| k6 | v2.2.0 no gerador (192.168.68.54), `MAX_VUS=1000`, de 05:04:04Z a 07:04:54Z, com exit 0 |
| Relógios | maior diferença de 0,95 s (host +1,74 s, nó +1,78 s, gerador +2,69 s) |

## Números

| Tipo | Requisições | 2xx | 400 | 202 / 429 | 5xx | status 0 | Descartadas | p95 por minuto (máx.) |
|---|---|---|---|---|---|---|---|---|
| leitura | 1.029.829 | 1.029.829 | 0 | — | 0 | 0 | 0 | 88 ms |
| reserva | 1.165.491 | 1.128.951 | 36.540 (todos "lotado") | 0 / 0 | 0 | 0 | 0 | 315 ms |
| login | 161.349 | 161.349 | 0 | — | 0 | 0 | 0 | 181 ms |
| cadastro | 74.506 | 74.506 (37.253 `verify-email`) | 0 | — | 0 | 0 | 0 | 272 ms |
| escrita | 30.932 | 30.932 | 0 | — | 0 | 0 | 0 | 363 ms |
| Mailpit (k6) | 74.507 | 74.507 | — | — | 0 | 0 | — | 17 ms |

- **Critério 1 (e-mail):** 37.253 mensagens do bloco para 37.253 cadastros. Foram 0 perdidas, 0 duplicadas, 0 extras e 0 From fora de `@example.com`. O Mailpit teve 0 restarts em 1337 amostras.
- **Reservas** (`verificar-rpm`, código 0):
  - overselling 0;
  - 398.275 confirmadas, 199.701 expiradas e 66.350 canceladas;
  - sagas convergidas: 0 velhas, 0 órfãs e 0 devoluções pendentes;
  - **os eventos lotaram, como a semente 4 previa**: `reserva_400{motivo}` com lotado = 36.540, vínculo = 0 e outro = 0 (stdout do `agregar.py`, em `agregar-S1b.log`). Na `S1-tentativa1` foram 36.562.
- **Critério 2:** 0 violações. Subidas e descidas avaliadas:

  | Serviço | Subidas | Descidas |
  |---|---|---|
  | backend | 1 | 2 |
  | catalog | 1 | 6 |
  | identity | 1 | 0 |
  | admission | 0 | 1 |
  | notification | 0 | 0 |

  - **admission:** as subidas não são avaliáveis pelo critério. Com mínimo 2, a métrica já chega acima do alvo com 3 réplicas, que é o máximo. Nas amostras houve 1 subida 2→3 (05:12:47Z), nenhuma amostra acima do alvo com menos de 3 réplicas e nenhuma abaixo de 2. A fonte é o `soak-S1b-replicas.csv`, fora do git pelo `.gitignore`.
  - **notification:** 0/0, ficou em 1 réplica e não foi exercitado.
  - **reservation:** fica fora do critério no S1: com X = 56k, o alvo do KEDA é de 28k RPM, que nunca é alcançado de propósito.
- **Critério 3:** 0 respostas 5xx e 0 com status 0.
- **Fila:** 0 respostas 202 e 0 respostas 429. Com X = 56k a fila não liga, como esperado.
- **Carga entregue:** todos os tipos com ≥ 90% das iterações do perfil, e 0 descartadas em todos os tipos.
- **Critério 4 (gerador):** 0 "Insufficient VUs", RAM livre mínima de 5,61 GiB e k6 com pico de 4,23 GiB (4.331 MB), sem crescer depois do aquecimento (de 3,0 a 4,23 GiB por minuto), com `vus_max` 500, no máximo 125 VUs e CPU em até 19%.

## Banco (Dell)

A coleta é a `coleta-k8s-soak-S1b`, de 05:03:38Z a 07:09:24Z. Ref `K8sDB-dell-soak-S1`, commit 56eb7e8, arquivo `dell/coleta-k8s-soak-S1b.tar.gz`, com sha256 `c28bae6a…01bb9`.

- **Vigia:** sem alerta, sem restart, sem FATAL/PANIC, sem alerta de disco e 0 deadlocks em todos os snapshots.
- **Delta:** commit +7.357.880, rollback +685.725, 0 conflitos, `temp_files` +45 (43 MiB). `meu_banco` foi de 28 MB para 1.073 MB.
- **Conexões:** pico de 140 no total, longe do pool de 245, e 93 active.
- **CPU (us+sy, recalculada do mpstat da ref na coleta inteira):** média de 12,1% e pico de 67,0% (a sessão DB reportou 13% e 68%), sem nenhum segundo em 95% ou mais. O Dell ficou folgado o bloco inteiro.
- **pg_waits:**
  - `LWLock/WALWrite`: 853 amostras-sessão, com pico de 93, em rajadas curtas de commits esperando o WAL. É o candidato a próximo gargalo se a carga subir.
  - `IO/WalSync`: 975 amostras-sessão, com pico de 2.
  - `Lock/transactionid`: só 19, com pico de 6, contra 49.612 no S2b.
- **Transação mais antiga:** 9 s em `idle in transaction`. No S2b chegou a 42 s.

Comparado ao S2b, sem o evento quente a fila de lock some e a CPU do Dell não satura. Isso reforça que a saturação do S2b veio da disputa no evento quente, e não de um limite geral do banco.

## Ressalvas

- **Credencial do Mailpit:** foi gerada para este bloco e passada ao gerador por mensagem entre sessões, por decisão do usuário. No gerador ela ficou só no ambiente do processo do k6 e foi removida ao fim. O valor não aparece em nenhum arquivo.
- **Fora do git:** as amostras do `amostrar` (`soak-S1b-replicas.csv` e `-pods.csv`) ficam fora pelo `.gitignore`. O CSV bruto do k6 ficou no gerador. A `coleta-host` não gravou o `pgss-*`, porque foi parada por `kill`.
- **Horários:** o `soak-S1b-verificacoes.log`, o `verificar-rpm-S1b.log` e o `registro-*.txt` estão em UTC−3. A telemetria e o por-minuto estão em UTC.
