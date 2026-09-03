---
name: implement-issue
description: Implementa uma issue do BACKLOG.md / GitHub (endydragonn/GerenciadorEventos) pelo pipeline de 4 papéis do TCC — orquestrador (esta sessão) + subagents code-generator, code-reviewer, spec-validator. Use SEMPRE que a tarefa for "implementar a issue #N", "fazer o item N do backlog", "resolver a issue X", "implementar a próxima issue", "pega a próxima do backlog" ou equivalente. Invocação: /implement-issue [N] — com número (issue do GitHub / item do BACKLOG.md) ou SEM número para o pipeline escolher sozinho a issue aberta de menor nº ainda sem branch nem PR (anuncia e pede "ok" antes de começar).
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

### 1. Resolver a issue

- **Veio um número** (`/implement-issue N`): é ele. **Issue do GitHub** (`#2`–`#47`) ou
  **item do `BACKLOG.md`** (lembre: item N do backlog = issue #N+1). Pula para o `gh issue
  view` abaixo.
- **NÃO veio número** (`/implement-issue` puro): selecione sozinho a issue aberta de menor
  nº ainda não iniciada.
  1. `gh issue list --repo endydragonn/GerenciadorEventos --state open --json number,title,labels --limit 100 --jq 'sort_by(.number)'`
  2. Branches: `gh api repos/endydragonn/GerenciadorEventos/branches --paginate --jq '.[].name'`.
     PRs abertos: `gh pr list --repo endydragonn/GerenciadorEventos --state open --json number,headRefName,title,body`.
  3. Percorra as issues do menor nº para o maior. **Pule** `#n` se existe branch com nome
     exato `Issue-n`, **ou** se um PR aberto tem `headRefName == "Issue-n"` ou cita `#n` /
     `Issue n` no título/corpo. A 1ª que sobrar é a candidata.
  4. Nenhuma sobrou → relate "todas as issues abertas já têm branch ou PR" e pare.
  5. **Anuncie e confirme ANTES de qualquer coisa:** mostre `#N`, título, labels e o
     **Depende de** (do corpo). Se for issue de Fase 0 puramente de ambiente, sem diff (ver
     *Quando NÃO usar*), diga isso. **Espere o "ok" do usuário** antes de ir ao passo 3
     (branch/PR). Se ele apontar outra issue, use a que ele deu.
- Com o N definido: `gh issue view <N> -R endydragonn/GerenciadorEventos` → capture título,
  corpo (contexto + **Aceite** + **Depende de**), labels. Se `gh` falhar, leia o item
  correspondente no `BACKLOG.md`.
- Se a issue referencia uma ADR, tenha o caminho no vault à mão:
  `D:\Claude\Brain\01-Projects\Event-Management-System\`.

### 1.5. Ativar as skills do contexto da issue

Olhe as **labels** e o **corpo** da issue e monte a lista de skills a usar. Invoque as
"sempre" agora; passe a lista inteira em cada despacho de subagent (passo 4) para que o
gerador/revisor/validador também as carreguem.

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

### 3. Branch `Issue-N` + PR draft — abertos na hora

- Se a issue foi auto-selecionada no passo 1, só chegue aqui **depois** do "ok" do usuário.
- `git status` primeiro. Se houver trabalho não commitado que não é seu, pare e alinhe.
- Branch a partir de `main` com o nome **exato `Issue-N`** (ex.: `Issue-5`). Uma por issue.
- **Abra o PR draft imediatamente**, antes de qualquer código de feature:
  ```
  git checkout -b Issue-N
  git commit --allow-empty -m "chore: inicia Issue-N (Refs #N)"
  git push -u origin Issue-N
  gh pr create --draft --base main --head Issue-N --title "Issue N: <título>" --body-file <arquivo>
  ```
  O corpo do PR (via `--body-file`, num arquivo temporário): 1 linha dizendo que é a
  implementação da issue #N via `/implement-issue`, depois a seção **Aceite** copiada da
  issue, e "WIP — atualizado ao final do pipeline". Use `Refs #N` no corpo (não `Closes #N`
  ainda — só ao marcar ready, se a issue for 100% coberta).
- **Vault ≠ git — não cite o vault no PR nem no commit.** O vault Obsidian
  (`D:\Claude\Brain\...`) e o repositório são controles de versão **independentes**. Em corpo
  de PR e mensagem de commit: nunca liste caminhos do vault, nem trate uma mudança no vault
  como parte do changeset/diff — descreva só o que mudou **no repo**. Vale inclusive quando a
  issue vendoriza conteúdo do vault (ex.: #3): o conteúdo vendorizado entra como arquivo do
  repo e pronto; **não** insira "blocos de proveniência" apontando pro vault dentro dos
  arquivos versionados. Se a issue pedir registrar a origem, é uma linha em prosa num README
  versionado — sem path absoluto, sem tratar como diff.
- Durante o loop, **não commite código** — trabalhe no working tree.
- Guarde o número/URL do PR para o passo 7.

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

- Se tocou backend: `Projeto/Backend/run-tests.sh` (precisa do container `db`; precisa de
  Docker). Se não há Docker, diga explicitamente que os testes não rodaram e liste o que
  rodaria.
- Se tocou frontend: `docker compose ... up` e checar no browser, ou dizer que não deu.
- Relate o **resultado real** — nunca afirme "passa" sem ter visto passar
  (`superpowers:verification-before-completion`).

### 6. Commit + push + atualizar o PR — automático

Quando o loop passa (revisor APROVADO + validador CONFORME) e a verificação do passo 5 está
feita:

- **Commite na `Issue-N`** — mensagem Conventional Commits em PT-BR (`feat:`/`fix:`/…),
  fechando com os trailers do `CLAUDE.md`. Uma mudança lógica coesa por commit. Referencie a
  issue: `Closes #N` se cobre 100%, senão `Refs #N`. **Não mencione o vault** na mensagem
  (ver passo 3, "Vault ≠ git").
- **`git push` na hora** — o PR draft atualiza sozinho.
- **Atualize o corpo do PR** (`gh pr edit`) com a tabela critério→PASS/FAIL/evidência do
  validador e o resumo do gerador. **Sem citar o vault** como alteração (ver passo 3).
- Se cobriu 100% (validador CONFORME, sem pendências) → **`gh pr ready <N>`** (tira do
  draft). Senão, deixa draft e diz no relatório o que falta.

**O que ainda exige pedido explícito do usuário:** **mergear** o PR `Issue-N` → `main`, e
qualquer commit/push direto em `main`. Isso o `CLAUDE.md` continua proibindo sem ordem.
Abrir e atualizar o PR (draft ou ready) é automático.

### 7. Relatório final ao usuário

- **Link do PR** `Issue-N` (draft ou ready) + hash do commit.
- Resumo do que mudou (do relatório do gerador).
- **Tabela critério de aceite → PASS/FAIL/não verificável + evidência** (do validador).
- Findings não-bloqueantes do revisor (como follow-ups).
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
  atualize o vault + `BACKLOG.md` primeiro).
- Issues de Fase 0 puramente de ambiente (#2 instalar Docker, #4 preparar VM de carga) —
  são passos manuais do usuário, não têm diff.

## Skills complementares

- O mapeamento label→skill do **passo 1.5** é a forma canônica de ativar skills; mantenha-o
  atualizado quando surgirem novas labels ou skills.
- `superpowers:requesting-code-review` — disciplina que o `code-reviewer` encarna.
- `superpowers:using-git-worktrees` / `superpowers:finishing-a-development-branch` — se o
  usuário quiser isolar a branch num worktree, ou ao fechar o PR.
- `spec-driven-implementation` (Warp) — **só** para quando quiser um `PRODUCT.md`/`TECH.md`
  de uma FASE inteira (ex.: toda a conversão CQRS/ES da Fase 6), não por issue. Por issue, o
  corpo da issue + a ADR do vault já são a spec.
