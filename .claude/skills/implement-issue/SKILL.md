---
name: implement-issue
description: Implementa uma issue do BACKLOG.md / GitHub (endydragonn/GerenciadorEventos) pelo pipeline de 4 papéis do TCC — orquestrador (esta sessão) + subagents code-generator, code-reviewer, spec-validator. Use SEMPRE que a tarefa for "implementar a issue #N", "fazer o item N do backlog", "resolver a issue X" ou equivalente. Invocação: /implement-issue <N> (número da issue do GitHub ou do item do BACKLOG.md).
---

# implement-issue — pipeline de implementação guiado por spec

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

- Argumento é o número. **Issue do GitHub** (`#2`–`#47`) ou **item do `BACKLOG.md`**
  (lembre: item N do backlog = issue #N+1).
- `gh issue view <N> -R endydragonn/GerenciadorEventos` → capture título, corpo (contexto +
  **Aceite** + **Depende de**), labels. Se `gh` falhar, leia o item correspondente no
  `BACKLOG.md`.
- Se a issue referencia uma ADR, tenha o caminho no vault à mão:
  `D:\Claude\Brain\01-Projects\Event-Management-System\`.

### 2. Checar dependências

- Para cada `#X` em "Depende de": confira se está fechada (`gh issue view X --json state`)
  ou se o trabalho existe no repo. Se **não**, avise o usuário e pergunte se segue mesmo
  assim ou faz a dependência antes. Não prossiga silenciosamente.

### 3. Branch

- `git status` primeiro. Se houver trabalho não commitado que não é seu, pare e alinhe.
- Branch a partir de `main`: `feat/issue-<N>-<slug-curto>` (o `CLAUDE.md` proíbe commit
  direto em `main`). **Não commite ainda.**

### 4. Loop de implementação (máx. 3 voltas; se estourar, pare e relate o impasse)

1. **Gerador** — despache `code-generator` com: corpo da issue + critérios de aceite +
   (nas voltas 2+) os findings bloqueantes da volta anterior.
2. **Revisor** — despache `code-reviewer` com o `git diff` da branch. 
3. Se o veredito do revisor for **MUDANÇAS NECESSÁRIAS** → volte ao passo 1 com os
   bloqueantes. Não chame o validador ainda.
4. **Validador de spec** — despache `spec-validator` com o corpo da issue + working tree.
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

### 6. Relatório final ao usuário

- Branch criada.
- Resumo do que mudou (do relatório do gerador).
- **Tabela critério de aceite → PASS/FAIL/não verificável + evidência** (do validador).
- Findings não-bloqueantes do revisor (como follow-ups).
- Resultado dos testes (real).
- Suposições feitas.

### 7. Commit / push — só se o usuário pedir

O `CLAUDE.md` manda: só commita/pusha quando o usuário pedir, e confirma antes do push.
Se pedir: mensagem Conventional Commits em PT-BR (`feat:`/`fix:`/…), fechando com os trailers
do `CLAUDE.md`. Uma mudança lógica por commit.

## Quando NÃO usar este skill

- Tarefa de exploração/pergunta (use as ferramentas direto).
- Mudança de arquitetura que ainda não é uma issue (use `superpowers:brainstorming` e
  atualize o vault + `BACKLOG.md` primeiro).
- Issues de Fase 0 puramente de ambiente (#2 instalar Docker, #4 preparar VM de carga) —
  são passos manuais do usuário, não têm diff.

## Skills complementares

- `superpowers:test-driven-development`, `superpowers:verification-before-completion`,
  `superpowers:requesting-code-review` — disciplina que os subagents e o passo 5 seguem.
- `spec-driven-implementation` (Warp) — **só** para quando quiser um `PRODUCT.md`/`TECH.md`
  de uma FASE inteira (ex.: toda a conversão CQRS/ES da Fase 6), não por issue. Por issue, o
  corpo da issue + a ADR do vault já são a spec.
