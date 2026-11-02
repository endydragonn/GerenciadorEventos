# Soak — dry-runs de 10 min (issue #232), 2026-11-02

Ensaio dos dois blocos do teste longo (`Projeto/carga/soak/`, spec `docs/superpowers/specs/2026-11-01-teste-longo-soak-design.md`)
antes dos blocos reais de 2 h, no cluster kind (trilha b1, Istio com sidecar, overlay `soak`) com o `db` no Dell
(`K8S_DB_REMOTO=192.168.68.50:5433`) e o k6 v2.2.0 no gerador (192.168.68.54). A coleta do lado do Dell fica na branch
`K8sDB-dell-soak-dry`.

| Bloco | Perfil | Sufixo | k6 (UTC) | Resultado |
|---|---|---|---|---|
| dry S1 | `perfil-dryS1.json` (X 56000, teto baixo) | 364888 | 04:04–04:15 | APROVADO nos critérios 1 a 3 |
| dry S2 | `perfil-dryS2.json` (teto 20000, X 4000) | 367775 (quente 2142) | 04:26:32–04:36:39 | APROVADO nos critérios 1 a 3 |

## Números

| | dry S1 | dry S2 |
|---|---|---|
| Critério 1: e-mails / cadastros 2xx (perdidos, duplicados, From fora de @example.com) | 513 / 513 (0, 0, 0) | 2460 / 2460 (0, 0, 0) |
| Overselling, sagas, `verificar-rpm` | 0, convergidas, código 0 | 0, convergidas, código 0 |
| Critério 2: subidas avaliadas (violações) | vácuo (teto baixo não escala) | admission 1 (CPU), backend 1 (CPU), reservation 1 (adm:hot); 0 violações; 0 descidas (o bloco curto não permite) |
| Critério 3: 5xx / status 0 | 0 / 0 | 0 / 0 |
| Fila: 202 / 429 | 0 / 0 | 949 / 0 |
| Reserva 400 | 0 | 2179, só nos minutos de pico |
| Mailpit: restarts, RSS máximo | 0 | 0, 30 Mi |
| Gerador: RAM livre mínima, descartadas | 9,28 GB, 0 | 9,06 GB, 0 (k6 com pico de 604 MB) |
| Diferença entre os relógios (host, nó, gerador) | 0,69 s | 0,76 s |

## Ressalvas

- **Banco não limpo.** Os dois dry-runs rodaram sobre o banco como estava, sem `limpar-banco`, e o S2 rodou em cima do S1.
  Foi isso que expôs o telefone do cadastro repetido entre blocos: 513 respostas `verify-email` 400 no S2, por `Users_fone_key`.
  O número bate exatamente com os 513 cadastros do S1. A correção está no commit 2b3e8e8: o telefone agora depende do sufixo.
  Os blocos reais rodam o `limpar-banco` antes de cada bloco. Com a guarda contra vácuo do commit 98ccad6, o `entregue` aplicado
  a este CSV **reprova** o dry S2 no cadastro: só 1947 dos 2460 cadastros (79%) concluíram o `verify-email`. Na época, o
  critério 1 contava os 2xx do `request-verification` e por isso aprovou. O dry S1 continua aprovado, com 513 de 514.
- **Os 400 da reserva no S2 não têm motivo registrado.** O k6 não gravava o corpo da resposta. Pelo backend, as causas possíveis
  são "Evento lotado" e "Vínculo já existente". Os blocos reais contam por motivo (`reserva_400{motivo}`).
- **Fail-open do admission no S2.** Houve 13 `Read timed out` no `POST /internal/admissions`, entre 04:28 e 04:32Z, pelo log do
  `backend`. As reservas seguiram síncronas e não houve nenhum 5xx. Não reprova.
- **Critério 2 do S1 vazio por desenho.** O teto do dry S1 é baixo e não liga a autoescala. No S2, a amostra do HPA de CPU
  (`ContainerResource`, por causa do sidecar) só foi lida depois do commit 357a89c.
- **Tentativas abortadas do S1** (`S1-tentativas-abortadas/`). Houve duas, às 00:37 e às 00:48 no horário local, e as duas
  pararam no setup do k6: a 8025 do Mailpit não era alcançável a partir do gerador, porque as regras Allow do Docker só valem
  no perfil Public. Foram corrigidas com a Allow própria do gerador (commit 82f2296).
- **Credencial do Mailpit.** Foi passada ao gerador por mensagem entre sessões, por decisão do usuário. O valor não aparece em
  nenhum arquivo.
- **Horários.** Os `*-verificacoes.log`, os `registro-*.txt` e o `verificar-rpm-dry.log` estão no horário local (UTC−3).
  `soak-dry*-por-minuto.csv` tem `inicio_s` em epoch.

## Arquivos

`soak-dry*-por-minuto.csv` (agregado do k6, uma linha por minuto e tipo), `soak-dry*-verificacoes.log` (pós-bloco), `coleta-host-*`, `eventos-*` e `registro-*` (coleta do host),
`verificar-rpm-dry.log` (overselling e sagas) e `perfil-dry*.json`. As amostras do `amostrar-soak` (`soak-*-replicas.csv`
e `-pods.csv`) são diagnóstico e ficam fora do git (`.gitignore`), como o CSV bruto do k6, que fica no gerador.
