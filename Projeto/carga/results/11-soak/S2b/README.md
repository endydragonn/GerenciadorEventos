# Soak — bloco S2b (issue #232), 2026-11-03

> **Decisão do usuário (2026-11-03): reprovação registrada e aceita, sem re-teste.**

**Resultado: REPROVADO só no critério 3. Foram 12 respostas 503 num único minuto, todas no `GET /bff/event-wallets/admission`. Causa provável, não provada: timeout do Redis no `admission` com o host saturado.**

O S2b repete o S2 depois das duas correções:

- o cadastro do k6 pagina o Mailpit (commit 02af3d9), aprovada no dry longo do Mailpit;
- o `admission` tem mínimo 2 réplicas (#235).

Os dois defeitos do S2 não voltaram. O critério 1 teve 0 e-mails duplicados (no S2 foram 262), e as 464 respostas 5xx da reserva caíram para 12.

| | |
|---|---|
| Cluster | kind, trilha b1 (Istio), overlay `soak`, db no Dell (`192.168.68.50:5433`), `ADMISSION_X_RPM=4000`, `admission` no mínimo 2, commit 71047ee |
| Perfil | `perfil-S2b.json` (nesta pasta): semente 257867, teto 50000 RPM, 7200 s. É o mesmo do `perfil-S2.json`, com 54.649 cadastros esperados |
| Seed | `Soak-446582`: 2000 usuários e 2000 eventos de 500 vagas, mais 1 evento quente (2001) de 20000 vagas. Foi semeado sobre o banco limpo (`limpar-banco` em modo cluster) |
| k6 | v2.2.0 no gerador (192.168.68.54), `MAX_VUS=1000`, de 02:33:15Z a 04:34:11Z, com exit 0 |
| Relógios | maior diferença de 0,91 s (host +1,48 s, nó +1,53 s, gerador +2,39 s) |

## Números

| Tipo | Requisições | 2xx | 202 | 400 | 429 | 5xx | status 0 | Descartadas | p95 por minuto (máx.) |
|---|---|---|---|---|---|---|---|---|---|
| leitura | 1.605.943 | 1.605.943 | — | 0 | 0 | 0 | 0 | 67.031 | 2.587 ms |
| reserva | 2.320.627 | 1.647.511 | 629.962 | 1.819 (todos "vínculo") | 41.323 | **12** | 0 | 77.817 | 484 ms |
| login | 190.603 | 190.603 | — | 0 | 0 | 0 | 0 | 2.068 | 2.995 ms |
| cadastro | 109.296 | 109.296 (54.648 `verify-email`) | — | 0 | 0 | 0 | 0 | 0 | 2.978 ms |
| escrita | 56.502 | 56.502 | — | 0 | 0 | 0 | 0 | 0 | 3.687 ms |
| Mailpit (k6) | 109.297 | 109.297 | — | — | — | 0 | 0 | — | 26,4 ms (no S2: 28 s) |

- **Critério 1 (e-mail):** 54.648 mensagens do bloco para 54.648 cadastros. Foram 0 perdidas, **0 duplicadas**, 0 extras e 0 From fora de `@example.com`. O Mailpit terminou com 0 restarts em 1356 amostras.
- **Reservas** (`verificar-rpm`, código 0):
  - overselling 0 (conferido; nenhum evento lotou, então a disputa pela última vaga não foi estressada neste bloco, por desenho);
  - 23.872 confirmadas, 11.901 expiradas e 6.021 canceladas;
  - sagas convergidas: 0 velhas, 0 órfãs e 0 devoluções pendentes;
  - `reserva_400{motivo}`: vínculo = 1819, lotado = 0, outro = 0 (stdout do `agregar.py`, em `agregar-S2b.log`).
- **Critério 2:** 0 violações. Subidas e descidas avaliadas:

  | Serviço | Subidas | Descidas |
  |---|---|---|
  | backend | 3 | 3 |
  | reservation | 3 | 4 |
  | catalog | 2 | 4 |
  | identity | 1 | 1 |
  | admission | 0 | 3 |
  | notification | 0 | 0 |

  - **admission:** as subidas não são avaliáveis pelo critério. Com mínimo 2, a métrica já chega acima do alvo com 3 réplicas, que é o máximo, e o `criterios.py` só abre episódio com réplicas abaixo do máximo. Nas amostras houve 4 subidas 2→3 (02:34:58Z, 03:02:45Z, 03:45:02Z e 04:27:49Z), nenhuma amostra acima do alvo com menos de 3 réplicas e nenhuma abaixo de 2. A fonte é o `soak-S2b-replicas.csv`, fora do git pelo `.gitignore`.
  - **notification:** 0/0, ficou em 1 réplica e não foi exercitado.
- **Carga entregue:** todos os tipos com ≥ 90% pela regra do S2, que desconta as descartadas do esperado (o cadastro não teve descartes).
- **Fila:** 629.962 respostas 202 e 41.323 respostas 429.
- **Gerador:** RAM livre mínima de 2,41 GiB, sempre acima do aviso de 1,5 GB. O k6 teve pico de 7,57 GiB (7.751 MB na telemetria), `vus_max` 900 e CPU de no máximo 26%. Houve 3 avisos "Insufficient VUs":
  - reserva (400 VUs) às 02:34:57Z;
  - leitura (300) às 03:50:36Z;
  - login (100) às 03:52:26Z.

## Critério 3: as 12 respostas 503

Todas vieram do `GET /bff/event-wallets/admission` (consulta do resultado da fila), no minuto 77 (03:51Z), e nenhum pod saiu nesse minuto. O caminho foi este (excerto dos logs em `admission-503-S2b.log`):

1. Às 03:51:36Z, um pod do `admission` registrou `Redis indisponível (QueryTimeoutException: Redis command timed out): respondendo 503`. O timeout do Redis no `admission` é de 200 ms (`ADMISSION_REDIS_TIMEOUT_MS`).
2. O gateway registrou `admission indisponível na consulta` e repassou o 503. O excerto tem 1 linha do gateway para as 12 respostas: as outras 11 ficaram no limitador do log (não houve log seguinte). É o contrato da #43: 503 quando o `admission` está fora.
3. O Redis não reiniciou. Nada mudou no log dele depois do boot, e o restartCount ficou em 0.

O contexto da janela de 03:50 a 03:52Z, que é o pico do bloco:

- backend, reservation, identity e admission estavam em 3 réplicas, e o nó com 77% de CPU (leitura ao vivo do `kubectl top node` às ~03:51Z, fora do git);
- a `coleta-host` não mostra o engasgo: o −1 do Redis aparece em amostras alternadas o bloco inteiro (1.639 de 2.920), e a duração das amostras na janela (mediana de 3,2 s) é próxima da do resto do bloco (mediana de 2,4 s);
- de 03:50:00Z a 03:52:59Z, de 18 a 91 sessões do Postgres esperavam lock (`pg_espera_lock`), com mediana de 72;
- os workers do `admission` registraram `Read timed out` no `POST /internal/reservations` e `503 Reserva em andamento`, voltando a mensagem à fila;
- foi a mesma janela dos "Insufficient VUs" de leitura e login, e dos maiores descartes (minutos 76–77, com reserva em 5.981 e 9.184).

**Causa provável, ainda não provada:** um engasgo do Redis acima dos 200 ms do timeout, com o host saturado. Não houve troca de réplica nem defeito novo. A consulta do resultado não tem retry no gateway, então o cliente recebe 503 e o k6 o conta.

O excerto versionado é o `admission-503-S2b.log`: as linhas de "indisponível" do gateway e do `admission`, sem segredo nem e-mail. Os logs completos com timestamp e os eventos do cluster foram guardados no host, fora do git, em `E:\TCC\evidencias\soak-S2b\`, antes de o cluster ser recriado para o S1. Lá estão os logs do `admission` (as 2 réplicas que sobraram; a 3ª já tinha descido), do Redis, do backend e do reservation, e o `kubectl get events -A`.

## Banco (Dell)

A coleta é a `coleta-k8s-soak-S2b`, de 02:33:06Z a 04:40:46Z. Ref `K8sDB-dell-soak-S2`, commit 15c6535, arquivo `dell/coleta-k8s-soak-S2b.tar.gz`, com sha256 `1c5999d4…16397`.

- **Vigia:** sem alerta, sem restart, sem FATAL/PANIC, sem alerta de disco e 0 deadlocks em todos os snapshots.
- **Delta:** commit +9.503.600, rollback +746.179, 0 conflitos. `temp_files` +93 (68 MiB): é o primeiro bloco com spill para disco. `meu_banco` foi de 19 MB para 909 MB.
- **Conexões:** pico de 264 no total (o pool cheio) e 239 active.
- **CPU (us+sy, recalculada do mpstat da ref na coleta inteira):** média de 30,5% (a sessão DB reportou 31,6%), mas em 95% ou mais por minutos inteiros nestas janelas: 03:50–03:52, 03:56–03:57, 04:10–04:14 e 04:24–04:25. **Desta vez o Dell virou gargalo de CPU nos picos**, ao contrário do S2.
- **pg_waits:** dominado por `Lock/transactionid`, com 49.612 amostras-sessão e pico de 92 sessões simultâneas às 02:53Z. É a disputa de linha no evento quente. `LWLock/LockManager` teve pico de 84 sessões (03:52:34Z).
- **Transação mais antiga:** chegou a 42 s em `idle in transaction`, às 03:52Z.

**No minuto dos 503 (03:51Z):**

- o Dell ficou com 100% de CPU o minuto inteiro, 150 processos na fila de execução para 8 threads e PSI cpu de 96;
- as 245 conexões do pool estavam ocupadas, com 213 active em mediana;
- de 24 a 86 sessões esperavam lock de linha;
- uma transação segurou lock por até 35 s.

Ou seja, os dois lados estavam saturados ao mesmo tempo: o nó kind (Redis do `admission`) e o Postgres no Dell. O 503 em si veio do timeout de 200 ms do Redis no `admission`. Os dados não separam se a saturação do banco contribuiu para ele, por exemplo segurando threads do host, ou se foi só coincidência de pico. Grande parte da CPU do Dell é backend disputando lock no evento quente, e não trabalho útil.

## Ressalvas

- **Credencial do Mailpit:** foi gerada para este bloco e passada ao gerador por mensagem entre sessões, por decisão do usuário. No gerador ela ficou só no ambiente do processo do k6 e foi removida ao fim. O valor não aparece em nenhum arquivo.
- **Fora do git:** as amostras do `amostrar` (`soak-S2b-replicas.csv` e `-pods.csv`) ficam fora pelo `.gitignore`. O CSV bruto do k6 (175 MB) ficou no gerador. A `coleta-host` não gravou o `pgss-*`, porque foi parada por `kill`.
- **Horários:** o `soak-S2b-verificacoes.log`, o `verificar-rpm-S2b.log` e o `registro-*.txt` estão em UTC−3. A telemetria e o por-minuto estão em UTC.
- **S1:** rodou em seguida, como o S1b (aprovado). A regra só barra o S1 por reprovação em corretude, e o critério 3 não é de corretude.
