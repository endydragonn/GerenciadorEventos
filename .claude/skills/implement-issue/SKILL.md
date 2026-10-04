---
name: implement-issue
description: Implementa uma issue de um BACKLOG.md / do GitHub do repositório atual pelo pipeline de 4 papéis — orquestrador (esta sessão) + subagents code-generator, code-reviewer, spec-validator. Use SEMPRE que a tarefa for "implementar a issue #N", "fazer o item N do backlog", "resolver a issue X", "implementar a próxima issue", "pega a próxima do backlog" ou equivalente. Invocação: /implement-issue [N] — com número (issue do GitHub / item do BACKLOG.md) ou SEM número para o pipeline escolher sozinho a issue aberta de menor nº ainda sem branch nem PR (anuncia e pede "ok" antes de começar).
---

# implement-issue — pipeline de implementação guiado por spec

> **Comece com contexto limpo.** Este pipeline gera muito contexto (subagents, `git diff`,
> logs de teste). Se a conversa atual já tem história que não interessa, **rode `/clear`
> primeiro e depois `/implement-issue`** — sem número, a auto-seleção (passo 1) pega a
> próxima issue sozinha, então não há nada a carregar pela limpeza. O skill não consegue
> disparar `/clear` sozinho: é comando do cliente e apagaria as próprias instruções + o
> argumento no meio da execução. Se você invocou sem limpar e a conversa está pesada, o
> orquestrador deve **avisar isso agora** e sugerir `/clear` + reinvocar antes de criar a branch.

Estrutura obrigatória para implementar qualquer item do `BACKLOG.md` / issue do repo.
Quatro papéis:

| Papel | Quem | Faz |
|---|---|---|
| **Orquestrador** | esta sessão | conduz o loop, decide, roda testes, relata; **não escreve código de feature** |
| **Gerador** | subagent `code-generator` | escreve código + testes |
| **Revisor** | subagent `code-reviewer` | acha bugs / violações de convenção (read-only) |
| **Validador de spec** | subagent `spec-validator` | confere contra cada critério de aceite (read-only) |

> Antes de qualquer coisa, invoque `superpowers:using-superpowers` (regra do `CLAUDE.md` da
> raiz) e `superpowers:subagent-driven-development` para a disciplina de despacho de subagents.

## Passos

### 0. Regras fixas vs. peculiaridades do projeto

- **Fixo (igual em qualquer repo):** os 4 papéis, o loop de até 3 voltas, branch `Issue-N`,
  PR sempre `--draft` até o gate do passo 7.5, o gate Copilot+Gemini, e o `CLAUDE.md` do
  projeto como fonte de convenções.
- **Dinâmico (vem do repo atual — nunca hardcode):**
  - **Repo do GitHub:** todo `gh issue`/`gh pr` abaixo roda **sem** `-R`/`--repo` — o `gh` já
    resolve o repo pelo remote da pasta atual (rode com o cwd dentro do projeto). Em `gh api`,
    use os placeholders literais `{owner}/{repo}` no path — o `gh` os substitui pelo repo do
    cwd.
  - **Stack, idioma, comando de teste, política de segredo, o que é legado intocável:** vêm do
    `CLAUDE.md` do projeto (raiz + área tocada) — leia-o no passo 1 antes de decidir qualquer
    coisa. Se o repo ainda não tem `CLAUDE.md`, rode a skill `generating-claude-md` primeiro
    (ou avise o usuário e pergunte como proceder).
  - **Numeração do backlog, labels reais, e o mapeamento label→skill do passo 1.5:** os
    exemplos citados neste documento (`area/banco`, "item N = issue #N+1", etc.) são os do
    projeto GerenciadorEventos — servem de modelo, não de regra fixa. Para o repo atual,
    confira `gh label list` e a convenção de numeração que o `BACKLOG.md`/issues do próprio
    repo documentarem.
  - **Documentação de arquitetura externa** (vault, ADRs, `docs/`): onde ela mora é o que o
    `CLAUDE.md` do projeto, seção "Documentação de arquitetura", disser — nunca assuma um
    caminho fixo.

### 1. Resolver a issue

- **Veio um número** (`/implement-issue N`): é ele. **Issue do GitHub** ou **item do
  `BACKLOG.md`** — se o projeto documentar uma fórmula de conversão item↔issue, use-a; não
  assuma "item N = issue #N+1" (isso é uma particularidade do GerenciadorEventos, de um import
  em lote específico de 2026-09-02). Pula para o `gh issue view` abaixo.
- **NÃO veio número** (`/implement-issue` puro): selecione sozinho a issue aberta de menor
  nº ainda não iniciada.
  1. `gh issue list --state open --json number,title,labels --limit 100 --jq 'sort_by(.number)'`
  2. Branches: `gh api repos/{owner}/{repo}/branches --paginate --jq '.[].name'`.
     PRs abertos: `gh pr list --state open --json number,headRefName,title,body`.
  3. Percorra as issues do menor nº para o maior. **Pule** `#n` se existe branch com nome
     exato `Issue-n`, **ou** se um PR aberto tem `headRefName == "Issue-n"` ou cita `#n` /
     `Issue n` no título/corpo. A 1ª que sobrar é a candidata.
  4. Nenhuma sobrou → relate "todas as issues abertas já têm branch ou PR" e pare.
  5. **Anuncie e confirme ANTES de qualquer coisa:** mostre `#N`, título, labels e o
     **Depende de** (do corpo). Se for issue de Fase 0 puramente de ambiente, sem diff (ver
     *Quando NÃO usar*), diga isso. **Espere o "ok" do usuário** antes de ir ao passo 3
     (criar a branch). Se ele apontar outra issue, use a que ele deu.
- Com o N definido: `gh issue view <N>` → capture título, corpo (contexto + **Aceite** +
  **Depende de**), labels. Se `gh` falhar, leia o item correspondente no `BACKLOG.md`.
- Se a issue referencia uma ADR ou outra doc de arquitetura externa, confira no `CLAUDE.md`
  do projeto (seção "Documentação de arquitetura") onde ela mora antes de prosseguir.

### 1.5. Ativar as skills do contexto da issue

Olhe as **labels** e o **corpo** da issue e monte a lista de skills a usar. Invoque as
"sempre" agora; passe a lista inteira em cada despacho de subagent (passo 4) para que o
gerador/revisor/validador também as carreguem.

> A tabela abaixo é o mapeamento usado no **GerenciadorEventos** — adapte label/skill ao
> vocabulário e à stack real do repo atual (confira `gh label list` e o `CLAUDE.md` do
> projeto). A linha **sempre** é universal, o resto é exemplo.

| Sinal na issue | Skills a ativar |
|---|---|
| **sempre** | `superpowers:test-driven-development` (teste antes do código), `superpowers:verification-before-completion` (evidência antes de "pronto") |
| label `area/banco`, ou o diff toca `*.sql` / schema / migração / índice / trigger / função / `pg_cron` / RLS / tipos de coluna | **`supabase:supabase-postgres-best-practices` — carregar ANTES de escrever qualquer coisa que vive no Postgres.** Também `supabase:supabase` se envolver Supabase, pooler (Supavisor), CLI, ou leitura de logs |
| label `area/frontend` (telas Flutter) | `impeccable` — UX, hierarquia visual, estados de erro/vazio/carregando, i18n, contagem regressiva |
| label `area/carga` ou `artigo` **com gráfico / relatório / dashboard** (#12–#14, #21, #28–#30, #33, #38, #44) | `dataviz` — ler ANTES de gerar qualquer chart/tabela de stat/comparação. `artifact-design` se o entregável for uma página HTML |
| label `area/eventos` ou `area/infra`, ou o diff mexe com lib/framework externo (Spring Boot, PgJDBC, Kafka/Redpanda client, Supavisor, Flutter, Docker Compose) | `context7` (MCP) — buscar doc atual da lib antes de escrever config/integração; nunca de memória |
| label `seguranca`, ou o diff mexe em auth / JWT / segredo / `.env` / CORS / reCAPTCHA (#16, #21, #35) | rodar `/security-review` no diff no passo 5; se for mudança grande de auth, considerar `find-security-vulnerabilities-in-code` |
| a issue **é uma ADR / decisão** a registrar, não código (#25 escolher backbone; partes de #17) | `superpowers:brainstorming` primeiro, e escrever/atualizar a ADR no vault + `BACKLOG.md` antes de tocar código |
| durante o loop: bug ou teste falhando | `superpowers:systematic-debugging` antes de propor conserto |

Anuncie ao usuário: **"Skills ativas para a issue #N: ..."**.

### 2. Checar dependências

- Para cada `#X` em "Depende de": confira se está fechada (`gh issue view X --json state`)
  ou se o trabalho existe no repo. Se **não**, avise o usuário e pergunte se segue mesmo
  assim ou faz a dependência antes. Não prossiga silenciosamente.

### 3. Branch `Issue-N` — criada e pushada na hora (o PR só no passo 7)

- Se a issue foi auto-selecionada no passo 1, só chegue aqui **depois** do "ok" do usuário.
- `git status` primeiro. Se houver trabalho não commitado que não é seu, pare e alinhe.
- Branch a partir de `main` com o nome **exato `Issue-N`** (ex.: `Issue-5`). Uma por issue.
- Crie e pushe a branch com um commit vazio (marca o início, dá backup remoto e faz a
  auto-seleção do passo 1 pular esta issue). **NÃO abra o PR agora** — ele entra só no passo 7
  (draft), depois de o loop passar e a verificação estar feita; o passo 7.5 (avaliar as
  reviews do Copilot e do Gemini) fecha o fluxo:
  ```
  git checkout -b Issue-N
  git commit --allow-empty -m "chore: inicia Issue-N (Refs #N)"
  git push -u origin Issue-N
  ```
- **Doc externa ≠ git — não cite vault/ADR/wiki no PR nem no commit.** Documentação externa
  (Obsidian, Notion, etc. — onde ela mora é o que o `CLAUDE.md` do projeto disser, se houver)
  e o repositório são controles de versão **independentes**. Em corpo de PR e mensagem de
  commit: nunca liste caminhos absolutos de doc externa, nem trate uma mudança nela como parte
  do changeset/diff — descreva só o que mudou **no repo**. Vale inclusive quando a issue
  vendoriza conteúdo externo: o conteúdo vendorizado entra como arquivo do repo e pronto;
  **não** insira "blocos de proveniência" apontando pro caminho externo dentro dos arquivos
  versionados. Se a issue pedir registrar a origem, é uma linha em prosa num README versionado
  — sem path absoluto, sem tratar como diff.
- Durante o loop, **não commite código** — trabalhe no working tree.

### 4. Loop de implementação (máx. 3 voltas; se estourar, pare e relate o impasse)

1. **Gerador** — despache `code-generator` com: corpo da issue + critérios de aceite + **a
   lista de skills do passo 1.5** (instrua: "carregue estas skills antes de escrever") +
   (nas voltas 2+) os findings bloqueantes da volta anterior.
2. **Revisor** — despache `code-reviewer` com o `git diff` da branch + a lista de skills do
   passo 1.5 (o revisor usa `supabase:supabase-postgres-best-practices` / `dataviz` / etc.
   como checklist do que cobrar).
3. Se o veredito do revisor for **MUDANÇAS NECESSÁRIAS** → volte ao passo 1 com os
   bloqueantes. Não chame o validador ainda.
4. **Validador de spec** — despache `spec-validator` com o corpo da issue + working tree
   (+ a lista de skills, para saber quais critérios de arquitetura/qualidade checar).
5. Se **NÃO CONFORME** → volte ao passo 1 com a lista de faltas.
6. Revisor **APROVADO** + validador **CONFORME** (ou CONFORME com itens não verificáveis sem
   Docker) → sai do loop.

Despache um subagent por vez; leia o relatório antes do próximo. Não rode os três em paralelo
(o revisor e o validador dependem do que o gerador produziu).

### 5. Verificação do orquestrador

- Rode o comando de teste que o `CLAUDE.md` do projeto documenta (seção "Rodar e testar"),
  para cada área tocada — inclusive pré-requisitos como containers/serviços que ele mencione.
  Se o `CLAUDE.md` não existir ou não documentar isso, **pergunte ao usuário** ou diga
  explicitamente que não sabe como testar — não invente um comando.
- Se não for possível rodar (ex.: falta uma dependência do ambiente), diga isso explicitamente
  e liste o que rodaria.
- Relate o **resultado real** — nunca afirme "passa" sem ter visto passar
  (`superpowers:verification-before-completion`).

### 6. Commit + push — automático (ainda sem PR)

Quando o loop passa (revisor APROVADO + validador CONFORME) e a verificação do passo 5 está
feita:

- **Commite na `Issue-N`** — mensagem no estilo/idioma que o `CLAUDE.md` do projeto define
  para commits (Conventional Commits é o default se o projeto não especificar outro),
  fechando com os trailers que o `CLAUDE.md` pedir. Uma mudança lógica coesa por commit.
  Referencie a issue: `Closes #N` se cobre 100%, senão `Refs #N`. **Não mencione doc externa**
  na mensagem (ver passo 3, "Doc externa ≠ git").
- **`git push` na hora** — só atualiza a branch remota; o PR ainda não existe.

### 7. Abrir o PR (draft) + marcar a review do Copilot

Só agora, com o loop fechado, a verificação feita e os commits pushados, **abra o PR** — nunca
antes. **O PR nasce sempre `--draft`**, com `Refs #N` no corpo; ele só sai do draft no passo
7.5, depois de as reviews do Copilot **e do Gemini** serem avaliadas e tratadas.

```
gh pr create --base main --head Issue-N --title "Issue N: <título>" --body-file <arquivo> --draft
```

- Corpo do PR (via `--body-file`): 1 linha dizendo que é a implementação da issue #N via
  `/implement-issue`; a tabela **critério de aceite → PASS/FAIL/não verificável + evidência**
  (do validador); o resumo do gerador; os findings não-bloqueantes do revisor como
  follow-ups (ou, se o usuário preferir tratar já, issue nova via `create-issue` — ver passo
  7.5). **Sem citar doc externa** como alteração (ver passo 3).
- **Logo depois de criar o PR, marque a review do Copilot** (obrigatório, versão lite):
  ```
  gh pr edit <N> --add-reviewer "@copilot"
  ```
  Se falhar, fallback via API:
  `gh api --method POST repos/{owner}/{repo}/pulls/<N>/requested_reviewers -f 'reviewers[]=copilot-pull-request-reviewer[bot]'`.
  Se nem assim entrar (Copilot review não habilitado no repo), diga isso no relatório e vá
  direto ao passo 7.5 tratando como "sem review do Copilot".
- **A review do Gemini dispara sozinha** — nada a marcar. O workflow
  `.github/workflows/gemini-pr-reviewer.yml` (evento `pull_request` `[opened, synchronize]`)
  roda a cada abertura de PR **e a cada push novo** e posta **um comentário de issue** (autor
  `github-actions[bot]`) começando com `### 🤖 Gemini Code Review`. Se o workflow não existir,
  falhar, ou faltar `GEMINI_API_KEY`, trate como "sem review do Gemini" no passo 7.5.

### 7.5. Avaliar as reviews automáticas (Copilot + Gemini) — gate antes de sair do draft

- **Aguarde as duas reviews** (espera limitada: cheque a cada ~30–60 s por até ~10 min; as
  duas costumam responder em 2–5 min). Para cada uma que não vier no teto, registre no
  relatório e siga como "sem review de \<bot\>".
- Colete os findings das duas:
  - **Copilot:** `gh api repos/{owner}/{repo}/pulls/<N>/reviews` e
    `gh api repos/{owner}/{repo}/pulls/<N>/comments`.
  - **Gemini:** `gh api repos/{owner}/{repo}/issues/<N>/comments --jq '[.[] | select(.user.login=="github-actions[bot]" and (.body | startswith("### 🤖 Gemini Code Review")))] | last | .body'`
    (`last` = o comentário mais recente; o workflow posta um novo a cada push do fix).
- **Avalie cada finding das duas reviews** com a mesma lente do `code-reviewer`: procede? é correção real,
  violação de convenção do projeto (`CLAUDE.md`), ou melhoria de qualidade de teste? Bate com
  as escolhas que o `CLAUDE.md` documenta como deliberadas, ou pede uma "migração"/troca de
  stack que ele proíbe? Se o finding tocar as skills do passo 1.5, use-as pra julgar.
- Classifique cada um:
  - **Não procede / irrelevante / contra uma escolha deliberada** → registre no relatório com
    o motivo e descarte.
  - **Procede e é relevante ao projeto** → **PARE e pergunte ao usuário o que fazer.**
    Apresente: o finding, sua avaliação, e a ação recomendada — incluindo a opção de **adiar
    como issue nova** (ver abaixo) quando o fix não é desta PR. Não aplique sozinho.
- Aplique o que o usuário decidir: nova rodada do gerador (disciplina do passo 4 — gerador,
  depois revisor scoped no diff do fix) ou, se for trivial (1–3 linhas óbvias), edição
  direta; rode o comando de teste do projeto (`CLAUDE.md` → Rodar e testar); **commite + pushe
  na `Issue-N`** com `Refs #N`.
- **Se o usuário decidir adiar em vez de mexer nesta branch:** invoque a skill `create-issue`
  (Caminho B, oportunista) — não crie a issue "na mão" fora dela. Preencha a origem com o que
  já está em mãos: `Refs #N` (issue atual), `PR #M` (esta), o finding (Copilot/Gemini/revisor
  do passo 4) e a data. Depois de criada, anote o número da issue nova no relatório final em
  vez de deixar o item como TODO solto. (Exemplo do GerenciadorEventos: #57 e #58 nasceram
  assim, de findings do `code-reviewer` na review da #5/PR #55 — antes de existir
  `create-issue`, foram criadas na mão; agora é sempre via a skill.)
- Se aplicou mudança, **re-solicite a review do Copilot** (`gh pr edit ... --add-reviewer`); a
  do Gemini **re-roda sozinha** no push do fix (evento `synchronize`) — pegue o comentário
  mais recente. Repita o 7.5 com os findings novos das duas (teto: 2 rodadas de fix; se
  estourar, relate o que ficou em aberto e siga).
- **Sair do draft — automático, sem pedir confirmação:** assim que **todos os findings
  pendentes de Copilot e Gemini** tiverem sido tratados (aplicados, descartados com motivo, ou
  adiados como issue nova via `create-issue`) — ou as reviews não vierem no teto de espera —
  **e** o validador tiver dado CONFORME sem
  pendências → `gh pr ready <N>` na hora e troque `Refs #N` → `Closes #N` no corpo. Não
  pergunte ao usuário pra marcar ready — só o **merge** segue sendo pedido dele.
  - Se Copilot **ou** Gemini ainda tem finding pendente que procede e é relevante, o PR
    **continua draft** até o usuário decidir (o gate do parágrafo acima).
  - Se o validador deixou pendências (cobertura < 100%), o PR **continua draft** — diga no
    relatório o que falta.
  - Se uma das reviews não veio no teto de espera, trate como "sem review de \<bot\>": aplica
    o mesmo gate (validador CONFORME + findings da outra tratados → ready).

**O que ainda exige pedido explícito do usuário:** decidir o que fazer com um finding do
Copilot **ou do Gemini** que procede e é relevante (o pipeline pergunta, não aplica sozinho); **mergear** o PR
`Issue-N` → `main`; e qualquer commit/push direto em `main`. Isso o `CLAUDE.md` continua
proibindo sem ordem. Abrir o PR draft, marcar o Copilot, aguardar o Gemini, descartar finding
que não procede, e sair do draft quando o gate fecha são automáticos.

Relatório final ao usuário:

- **Link do PR** `Issue-N` (draft ou ready) + hash do commit + confirmação de que a review do
  Copilot foi marcada e de que a do Gemini rodou (ou, para cada uma, por que não deu).
- Resumo do que mudou (do relatório do gerador).
- **Tabela critério de aceite → PASS/FAIL/não verificável + evidência** (do validador).
- Findings não-bloqueantes do revisor (como follow-ups).
- **Reviews automáticas (Copilot + Gemini):** por review, cada finding → procede/não procede
  + relevante/irrelevante + o que foi feito (descartado com motivo / aplicado no commit X /
  adiado como issue nova #Y via `create-issue` / decisão do usuário).
- Resultado dos testes (real).
- Suposições feitas.
- Se está ready: ofereça **mergear** (não mergeie sem o usuário pedir). Se draft: diga o que
  falta pra sair do draft.

### 8. Depois do merge — sincronizar o `main` local

Assim que o PR `Issue-N` for **aprovado/mergeado** (pelo usuário), sincronize na hora:

- `git checkout main && git pull` — traz o merge do `origin`.
- Se sobrou algum commit local em `main` (ex.: follow-up de skill/config fora do fluxo de
  issue), **`git push` na hora** dele também.
- Apague a branch da issue (`git branch -d Issue-N`; `git push origin --delete Issue-N`).

Não deixar o `main` local divergindo do `origin` entre uma issue e a próxima.

## Quando NÃO usar este skill

- Tarefa de exploração/pergunta (use as ferramentas direto).
- Mudança de arquitetura que ainda não é uma issue (use `superpowers:brainstorming` e
  atualize a doc de arquitetura do projeto — vault/ADR/docs, se existir — + `BACKLOG.md`
  primeiro).
- Issues de Fase 0 puramente de ambiente (#2 instalar Docker, #4 preparar VM de carga) —
  são passos manuais do usuário, não têm diff.

## Skills complementares

- O mapeamento label→skill do **passo 1.5** é a forma canônica de ativar skills; mantenha-o
  atualizado quando surgirem novas labels ou skills.
- `create-issue` — sempre que um finding (revisor do passo 4, Copilot/Gemini do passo 7.5) for
  adiado em vez de entrar nesta PR, é essa skill que cria a issue de follow-up, não `gh issue
  create` direto.
- `superpowers:requesting-code-review` — disciplina que o `code-reviewer` encarna.
- `superpowers:using-git-worktrees` / `superpowers:finishing-a-development-branch` — se o
  usuário quiser isolar a branch num worktree, ou ao fechar o PR.
- `spec-driven-implementation` (Warp) — **só** para quando quiser um `PRODUCT.md`/`TECH.md`
  de uma FASE inteira, não por issue. Por issue, o corpo da issue + a doc de arquitetura do
  projeto (se existir) já são a spec.
- `generating-claude-md` — rode antes deste skill se o repo ainda não tem `CLAUDE.md`, ou se
  ele está defasado em relação ao código atual; este pipeline depende do `CLAUDE.md` pra saber
  as peculiaridades do projeto (ver passo 0).
