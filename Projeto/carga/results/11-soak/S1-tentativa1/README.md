# Soak — bloco S1, 1ª tentativa (issue #232), 2026-11-02

**Resultado: inválido no critério 1 (notificação e e-mail não foram exercitados).** O bloco vale nos critérios 2, 3 e 4 e na parte
de reservas do critério 1: overselling 0 e sagas convergidas. A notificação e o e-mail **não** foram exercitados, por um bug do
harness: o commit 2b3e8e8 passou a gerar o telefone do cadastro com 15 dígitos, e o gateway recusa qualquer telefone fora de 10
ou 11 dígitos (`BffUserController:245`). Os 37.253 cadastros deram 400 no `request-verification`, e nenhum e-mail foi enviado.

O pós-bloco da época marcou "critério 1 ok" **por vácuo**: 0 mensagens para 0 cadastros 2xx, contra 37.254 esperados pelo perfil.
Esse "ok" não vale. O pós-bloco foi corrigido para reprovar quando um tipo fica abaixo do esperado pelo perfil, e a reprovação deste
CSV é o teste da correção. O S1 será repetido depois do S2, com a mesma semente, a mesma capacidade e um sufixo novo.

| | |
|---|---|
| Cluster | kind, trilha b1 (Istio), overlay `soak`, db no Dell (`192.168.68.50:5433`), `ADMISSION_X_RPM=56000` |
| Perfil | `perfil-S1.json`: semente 4, teto 35000 RPM, 7200 s, 200 vagas por evento |
| Seed | `Soak-370785`, com 2000 usuários × 2000 eventos, sobre o banco limpo (`limpar-banco` em modo cluster) |
| k6 | v2.2.0 no gerador (192.168.68.54), commit c37925a, `MAX_VUS=2000`. Partiu às 05:24:59Z e terminou às 07:25:51Z, com exit 0 |
| Relógios | maior diferença de 0,79 s (host, nó e gerador) |

## Números

| Tipo | Requisições | 2xx | 400 | 5xx | status 0 | Descartadas |
|---|---|---|---|---|---|---|
| leitura | 1.029.829 | 1.029.829 | 0 | 0 | 0 | 0 |
| reserva | 1.165.522 | 1.128.960 | 36.562 (todos "lotado") | 0 | 0 | 0 |
| login | 161.349 | 161.349 | 0 | 0 | 0 | 0 |
| cadastro | 37.253 | **0** | **37.253** (telefone recusado) | 0 | 0 | 0 |
| escrita | 30.932 | 30.932 | 0 | 0 | 0 | 0 |

- **Reservas** (`verificar-rpm`, código 0):
  - overselling 0 em 17.466 eventos;
  - 398.329 confirmadas, 199.648 expiradas e 66.327 canceladas;
  - sagas convergidas (0 velhas, 0 órfãs, 0 devoluções pendentes);
  - os eventos lotaram, como a semente 4 previa: `reserva_400{motivo}` com lotado = 36.562, vínculo = 0 e outro = 0;
  - fila: 0 respostas 202 e 0 de 429 (com X = 56k a fila não liga).
- **Critério 2** (subidas/descidas avaliadas, sem violação):
  - admission 5/5, backend 1/2, catalog 2/6 e identity 4/2, sempre com 0 violações;
  - reservation fica fora no S1 (alvo do KEDA de 28k, que não é alcançado de propósito);
  - réplicas no máximo: 3 em admission, backend, catalog e identity.
- **Critério 3:** 0 respostas 5xx e 0 com status 0 em ~2,4 M requisições. Nenhum pod reiniciou, e o Mailpit também teve 0 restarts.
- **Critério 4 (gerador):**
  - 0 descartadas e nenhum "Insufficient VUs";
  - RAM livre mínima de 3,21 GB e CPU no máximo em 17%;
  - k6 (WorkingSet64) entre 5,3 e 7,4 GB, sem crescer com o tempo. O custo é por VU **alocado** (`vus_max` = 1000, os preAllocated), não por VU ativo (2 a 98). Cada VU leva uma cópia dos dados do setup (2000 sessões e 2000 eventos), ≈ 7,4 MB por VU (`soak-S1-vus-mem-por-minuto.csv` e `telemetria-gerador-soak-S1.csv`).
- **Fail-open do admission:** 2 ocorrências (05:34 e 07:02Z), por `Read timed out` no `POST /internal/admissions`; as reservas seguiram síncronas, sem 5xx (`failopen-admission-S1.log`).
- **DB** (Dell, ref `K8sDB-dell-soak-S1`, commit 356f22c):
  - 0 deadlocks e 0 conflitos;
  - pico de 90 conexões, CPU no pico em 27% e na média em 8,2%;
  - ~930 commits por segundo;
  - `meu_banco` foi de 14 MB para 1.042 MB.

## Ressalvas

- O 1º `preparar-soak` abortou depois do seed (`SUFIXO 369890`) por um redirecionamento no PowerShell (NativeCommandError). Rodei o
  `limpar-banco` de novo e repeti o seed. O bloco rodou sobre o seed `Soak-370785`, num banco limpo.
- A credencial nova do Mailpit foi passada ao gerador por mensagem entre sessões, por decisão do usuário. O valor não aparece em nenhum arquivo.
- Os horários dos `*-verificacoes.log` e `registro-*.txt` estão em UTC−3. O `inicio_s` do por-minuto está em epoch.
- `soak-S1-replicas.csv` e `soak-S1-pods.csv` (amostras do `amostrar`) ficam fora do git (`.gitignore`). O CSV bruto do k6 (99 MB) ficou no gerador.
