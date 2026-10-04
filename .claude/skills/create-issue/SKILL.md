---
name: create-issue
description: Use quando um item novo de backlog, bug, achado de review, ou follow-up do repositório atual precisa virar issue no GitHub com spec (critérios de Aceite) antes de implementar — ex.: "cria uma issue pra isso", "registra isso como issue", "isso vira issue depois", "adiciona no BACKLOG.md". NÃO cobre implementação (use implement-issue) nem decisão de arquitetura ainda sem ADR (use brainstorming primeiro).
---

# create-issue — spec (Aceite) + issue no GitHub

Formaliza como uma necessidade nova (bug, hardening, follow-up de review, item de roadmap)
vira uma issue no **repositório atual** cuja spec — o corpo com `## Aceite` — é consumível
pelo pipeline `implement-issue` (que lê título/corpo/labels no passo 1 e usa as labels pra
ativar skills no passo 1.5). Todo `gh issue`/`gh label` abaixo roda **sem** `-R`/`--repo` — o
`gh` resolve o repo pelo remote local; nunca hardcode `owner/repo`.

## Dois caminhos — escolha antes de escrever

```dot
digraph path {
  "É decisão de arquitetura, ainda sem ADR?" [shape=diamond];
  "Já é item numerado de um roadmap\n(tipo BACKLOG.md, dentro do plano)?" [shape=diamond];
  "brainstorming + ADR na doc de arquitetura\nprimeiro, depois volte aqui" [shape=box];
  "Caminho A: item de roadmap" [shape=box];
  "Caminho B: oportunista" [shape=box];

  "É decisão de arquitetura, ainda sem ADR?" -> "brainstorming + ADR na doc de arquitetura\nprimeiro, depois volte aqui" [label="sim"];
  "É decisão de arquitetura, ainda sem ADR?" -> "Já é item numerado de um roadmap\n(tipo BACKLOG.md, dentro do plano)?" [label="não"];
  "Já é item numerado de um roadmap\n(tipo BACKLOG.md, dentro do plano)?" -> "Caminho A: item de roadmap" [label="sim"];
  "Já é item numerado de um roadmap\n(tipo BACKLOG.md, dentro do plano)?" -> "Caminho B: oportunista" [label="não"];
}
```

- **Caminho A (roadmap):** estende a numeração do roadmap do projeto, se ele tiver um
  (`BACKLOG.md` ou equivalente). Só se o usuário está mesmo expandindo o plano de fases.
  (Exemplo do GerenciadorEventos: numeração 1–46 do `BACKLOG.md`, virada issues #2–#47 num
  import em lote de 2026-09-02 — raro, não é o caso comum.)
- **Caminho B (oportunista):** o caso comum. Bug achado numa review, hardening não-bloqueante,
  débito técnico, follow-up. **Sem linha no roadmap.** (Exemplo do GerenciadorEventos: #78,
  #57, #58 — todas fora da numeração 1–46, criadas durante o trabalho de outra issue.)
- **Não é nenhum dos dois** (é uma decisão de arquitetura — trade-off, escolha de tecnologia,
  mudança de escopo do sistema): pare aqui, use `superpowers:brainstorming` e registre a ADR
  na doc de arquitetura do projeto primeiro (onde ela mora é o que o `CLAUDE.md` do projeto,
  seção "Documentação de arquitetura", disser). Issue referencia a ADR pelo nome; nunca pelo
  caminho absoluto da doc externa (mesma regra de "doc externa ≠ git" do `implement-issue`,
  vale pra issue tanto quanto pra PR/commit).

## Antes de escrever: confira o vocabulário real

Labels são **case-sensitive e fixas** — `gh issue create --label` falha numa label que não
existe. Confira a lista atual do repo atual antes de usar qualquer label:

```
gh label list
```

> A tabela abaixo é o vocabulário do **GerenciadorEventos** — só um exemplo de como um projeto
> pode organizar labels (fase / área / tipo / projeto). Para o repo atual, use o que `gh label
> list` devolver, não esta tabela.

| Categoria | Labels existentes (exemplo GerenciadorEventos) |
|---|---|
| Fase | `fase/0` … `fase/11` |
| Área | `area/backend`, `area/banco`, `area/infra`, `area/frontend`, `area/eventos`, `area/carga` |
| Tipo | `bug`, `enhancement`, `documentation`, `question`, `good first issue`, `help wanted` |
| Projeto | `artigo` (entregável do TCC), `seguranca`, `bloqueado/docker` |

Se o repo categoriza issues por área (confira `gh label list`), toda issue precisa de **ao
menos uma label de área** — é o que o passo 1.5 do `implement-issue` usa pra decidir quais
skills o gerador/revisor/validador carregam. Issue sem label de área faz o pipeline perder
essas skills silenciosamente.

## Escrever a spec (corpo da issue)

**Obrigatório: `## Aceite` com critérios em bullets, um por linha, verificáveis.** Não um
parágrafo único — o `spec-validator` do `implement-issue` monta uma tabela critério→PASS/FAIL
e precisa de linhas discretas pra checar (issues antigas em parágrafo único, ex. #10, forçaram
o validador a quebrar o texto sozinho; não repita isso em issue nova).

Seções, na ordem (as duas primeiras e a última são as únicas obrigatórias):

1. **Contexto/origem** (1 parágrafo ou frase) — de onde isso veio: "Achado na revisão da #5
   (PR #55)", "Fase 1 — pré-requisito estrutural da Fase 2 (fora da numeração 1–46)", etc.
2. `## Contexto` **ou** `## Problema` — prosa: o que está errado / o que falta, e por quê
   importa agora.
3. `## Aceite` (**obrigatório**) — bullets verificáveis.
4. `## Fora de escopo — NÃO fazer aqui` (opcional) — quando há risco real de escopo vazar pra
   uma issue vizinha (ex. #78 deixou explícito que auth não muda ali, é a #57).
5. `## Depende de` — `#N` (uma por linha) ou `—`. Confira que cada `#N` existe:
   `gh issue view N --json state`.
6. `## Precede` (opcional) — o que essa issue destrava.
7. Rodapé em itálico citando a origem real: `_Refs #N, PR #M. Origem: revisão code-reviewer
   AAAA-MM-DD._` — nunca um caminho de doc externa, nunca tratar nota externa como parte do
   diff.

**Caminho A (roadmap) muda dois pontos:** título e rodapé seguem o formato que o roadmap do
projeto já usa (confira as entradas existentes do `BACKLOG.md`/equivalente antes de escrever a
nova). **Nunca prometa `issue #(N+1)`** a partir do número do item do roadmap — no
GerenciadorEventos essa fórmula só valeu pros itens 1–46 (import em lote de 2026-09-02, onde os
números do GitHub calharam de ser sequenciais); em qualquer outro caso, issue nova pode cair em
qualquer número. Anote o número real que o GitHub deu na linha do roadmap, não calcule.

## Mostrar antes de criar

`gh issue create` é uma ação visível pra fora — **mostre o título, corpo e labels ao usuário e
espere confirmação** antes de rodar. Depois de aprovado:

```
gh issue create --title "<título>" --body-file <arquivo> \
  --label "<labels reais do repo, confirmadas via gh label list>"
```

**Caminho A:** depois de criada, adicione a entrada no roadmap (`BACKLOG.md` ou equivalente) na
seção certa, no formato das entradas já existentes. Isso é uma edição de arquivo versionado
fora do fluxo de issue — **só commit/push se o usuário pedir** (regra do `CLAUDE.md`); diga que
a edição está pronta pra revisão.

## Depois de criada

Não implemente na hora. Se o usuário quiser seguir direto, use `/implement-issue <N>` com o
número recém-criado — esta skill só cobre até a issue existir com spec completa.

## Erros comuns

| Erro | Correção |
|---|---|
| Label que não existe no repo | `gh label list` antes de criar; nunca invente uma nova sem avisar o usuário |
| `## Aceite` em parágrafo único | Quebre em bullets — cada um é uma linha da tabela do `spec-validator` |
| Issue sem label de área (se o repo usa esse esquema) | Pipeline não ativa as skills certas no passo 1.5 — sempre inclua |
| Assumir `issue #(N+1)` pra item novo do roadmap | Fórmula específica do import 1–46 do GerenciadorEventos; anote o número real |
| Citar caminho absoluto de doc externa (vault/wiki) no corpo | Referencie a ADR pelo nome; doc externa ≠ git |
| Hardcode `owner/repo` num comando `gh` | Deixe o `gh` resolver pelo remote local — nunca fixe o repo |
| Criar a issue sem mostrar o rascunho antes | `gh issue create` é visível pra fora — sempre confirme antes |

## Skills complementares

- `superpowers:brainstorming` — antes desta, se a necessidade ainda é uma decisão de
  arquitetura sem ADR.
- `implement-issue` (`.claude/skills/implement-issue/SKILL.md`) — consome o que esta skill
  produz; passo 1.5 mapeia as labels aqui escolhidas para as skills do gerador/revisor.
- `generating-claude-md` — se o repo ainda não tem `CLAUDE.md` documentando onde a doc de
  arquitetura mora, rode-a antes de precisar referenciar uma ADR.
