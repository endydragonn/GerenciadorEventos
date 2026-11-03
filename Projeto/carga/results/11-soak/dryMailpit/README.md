# Soak — dry longo do Mailpit (issue #232), 2026-11-02

**Resultado: APROVADO nos critérios 1 a 3. O portão do Mailpit passou: o p95 da API ficou estável do começo ao fim (7 a 13 ms).**

É o portão antes de repetir o S2. O bloco S2 reprovou com 262 e-mails duplicados, provavelmente porque a busca `to:` do Mailpit, que é linear,
saturou o captador, o SMTP passou dos 5 s do JavaMail e o retry do consumer reenviou o e-mail.

A correção é só no harness (commit 02af3d9): o cadastro pagina as mensagens mais novas, parando pela data do pedido, e consulta
menos vezes. A partir deste dry o cluster também sobe com o `admission` em mínimo 2 (#235).

| | |
|---|---|
| Cluster | kind, trilha b1 (Istio), overlay `soak`, db no Dell (`192.168.68.50:5433`), `ADMISSION_X_RPM=4000`, commit b058a12 |
| Perfil | `perfil-dryMailpit.json` (nesta pasta): só cadastro, semente 257867, teto 50000 RPM, 2400 s. A curva de cadastro é a dos primeiros 40 min do S2: 3 picos de 850 a 900 cadastros/min, 22.539 esperados |
| Mailpit | pré-enchido com **55.000 mensagens antigas** (`prefill-<n>@example.com`, por `kubectl port-forward` em 127.0.0.1, encerrado logo depois), para a caixa ter o tamanho do S2 desde o 1º minuto. Terminou com 77.538 |
| Seed | `Soak-427425` (200 usuários e 200 eventos), sobre o banco limpo (`limpar-banco` em modo cluster) |
| k6 | v2.2.0 no gerador (192.168.68.54), `MAX_VUS=1000`, de 21:06:01Z a 21:46:10Z, com exit 0 |
| Relógios | maior diferença de 0,74 s (host, nó e gerador) |

## Números

| | |
|---|---|
| Cadastro | 45.076 requisições (request-verification + verify-email), todas 2xx, e 22.538 `verify-email` 2xx; p95 por minuto de até 77 ms. 0 descartadas e 0 status 0 |
| Chamadas ao Mailpit (k6) | 45.077, todas 2xx, 0 status 0 (no S2 foram 5 timeouts); **p95 por minuto de 7 a 13 ms**, mediana de 7,1 ms na 1ª metade e de 7,7 ms na 2ª |
| Critério 1 | 22.538 mensagens do bloco para 22.538 cadastros: 0 perdidas, **0 duplicadas** e 0 From fora de `@example.com` |
| Critério 2 | 0 violações; identity com 2 subidas e 2 descidas avaliadas; admission no mínimo 2 o bloco inteiro |
| Critério 3 | 0 respostas 5xx e 0 status 0 |
| Reservas | o perfil não roda reserva: overselling 0 e sagas convergidas no `verificar-rpm` (código 0); fila não avaliada |
| Mailpit (sonda do host, a cada min) | listagem `limit=100` com mediana de 6,3 ms e máximo de 25 ms; CPU de 5 a 138m; RSS de 33 a 39 Mi; 0 restarts |
| Notification | 1 réplica o bloco inteiro (no S2 subiu a 3 com o lag) |
| Gerador | RAM livre mínima de 9,27 GiB; k6 com no máximo 143 MB; CPU no máximo em 12% |

Os números da DB (Dell, ref `K8sDB-dell-soak-dryMailpit`, commit 7944bf5) foram:

- 0 deadlocks e 0 conflitos, com picos de 25 conexões e de 24% de CPU, e média de 3,3% de CPU;
- `meu_banco` foi de 14 MB para 37 MB;
- `pg_waits`: só `IO/WalSync`, com no máximo 1 sessão ao mesmo tempo, e nenhuma espera de Lock nem LWLock.

## Leitura

- **O custo da consulta não cresce mais com a caixa.** Com 55 a 77 mil mensagens guardadas, o p95 ficou em ~7 ms, contra os 17 a 28 s do S2,
  que tinha só 26 mil mensagens no fim. A busca linear foi trocada pela listagem das mais novas.
- **A paginação para pela data.** As 55 mil mensagens antigas ficaram atrás das novas, e nenhum cadastro deixou de achar o seu código.
- **Sem saturação, sem retry.** Com o Mailpit respondendo rápido, o SMTP não passou do timeout e o consumer não reenviou nada: 0 duplicadas.

## Ressalvas

- **Só cadastro.** O dry não roda leitura, login, reserva nem escrita: prova o Mailpit e o notification no ritmo de cadastro do S2, não
  a disputa de CPU do nó com a carga inteira. Isso é o S2.
- **40 min, não 2 h.** O tamanho da caixa do S2 veio do pré-enchimento, não do tempo. Decisão da interface, combinada antes do bloco.
- **Credencial do Mailpit.** Nova para este bloco, passada ao gerador por mensagem entre sessões, por decisão do usuário. No gerador ela
  entrou só como variável de ambiente do processo do k6, porque a sessão de lá não tem terminal interativo para o `Read-Host`. O valor
  não aparece em nenhum arquivo.
- **Fora do git.** As amostras do `amostrar` (`soak-dryMailpit-replicas.csv` e `-pods.csv`) ficam fora pelo `.gitignore`, e o CSV
  bruto do k6 ficou no gerador. A `coleta-host` não gravou o `pgss-*`, porque foi parada por `kill`.
- **Horários.** O `soak-dryMailpit-verificacoes.log`, o `verificar-rpm-dryMailpit.log` e o `registro-*.txt` estão em UTC−3. A sonda e a
  telemetria estão em UTC, e o `inicio_s` do por-minuto em epoch.
- **Depois do bloco.** O `limpar-banco` da preparação do S2 rodou com código 0, e o S2 ficou para outro dia, por decisão do usuário.
