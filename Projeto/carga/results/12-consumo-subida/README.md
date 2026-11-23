# 12 — Consumo de subida: microsserviços × monólito

Quanto custa subir cada container, o bloco inicial inteiro e cada instância a mais, na stack atual de
microsserviços e no monólito pré-#32 (commit `2980675`). Medido em 2026-11-22. Mediana de 3 repetições por
fase, sem carga (só subida e repouso).

## Resultado

| | microsserviços (12 containers) | monólito (4 containers) | razão |
|---|---:|---:|---:|
| Memória privada (anon), bloco em repouso | 2.966 MB | 774 MB | 3,8× |
| Memória total do cgroup (mem), bloco | 4.511 MB | 1.012 MB | 4,5× |
| CPU gasta para subir o bloco | 103 CPU-s | 29 CPU-s | 3,5× |
| CPU ociosa do bloco | 61 % de 1 núcleo (15 % sem o RabbitMQ) | 6 % | 10× (2,5× sem o RabbitMQ) |
| Subida sequencial (soma dos "pronto"; ≈ nº de containers × intervalo do healthcheck) | 63 s | 24,5 s | 2,6× |

**+1 instância de um serviço:**

| | microsserviços | monólito |
|---|---|---|
| Memória (anon própria) | 220–429 MB, ou 7–15 % do bloco | 438 MB, ou 57 % do bloco |
| CPU de boot | 6,6–17 CPU-s, ou 6–17 % do bloco | 16,8 CPU-s, ou 58 % do bloco |
| CPU ociosa | 0,7–2,5 % de núcleo | 2 % de núcleo |
| Todas as 6 réplicas juntas | +2.087 MB anon (+70 % do bloco); pelo Δ do total de anon, +2.336 MB (+79 %) | — |

O custo absoluto de uma instância é parecido nos dois lados: uma JVM Spring de 330 a 440 MB, que gasta de
12 a 17 CPU-s para subir (o `notification`, só consumidor, fica em 220 MB e 6,6 CPU-s). O percentual muda porque o bloco de microsserviços já começa 3,8× maior. No monólito,
uma instância a mais repete o app inteiro, a 57 % do bloco. Nos microsserviços, a réplica é de um serviço só,
de 7 a 15 % do bloco. A soma das 6 réplicas fica perto do custo de uma réplica do monólito × 5.

Na infra, Redis + RabbitMQ (a camada de admissão, #41/#42) somam só 68 MB anon. O RabbitMQ, porém, aparece com
45 a 55 % de um núcleo parado, e **quem gasta é o healthcheck do compose, não o broker**: o
`rabbitmq-diagnostics -q ping` a cada 5 s sobe uma VM Erlang inteira dentro do cgroup do container. Teste à parte
(mesma imagem, 30 s de janela): 46 % de núcleo com esse healthcheck, 0,3 % sem ele. Isso é custo real da stack como
está configurada, mas barato de cortar (healthcheck por TCP na 5672, como os Java fazem na porta HTTP). Por isso a CPU
ociosa aparece também sem o RabbitMQ.

Quanto pesa cada container do bloco inicial, em % do bloco: coluna "% do bloco" em [`resumo.md`](resumo.md). Os
Java ficam entre 7 % (notification) e 15 % (reservation), o supabase-pooler em 7 %, e o resto da infra abaixo de 4 %.

Tabelas completas (por container, bloco e réplicas): [`resumo.md`](resumo.md), gerado por
`carga/consumo/analisar-consumo.py`.

## Método

- **Script.** O `carga/consumo/medir-consumo.sh` roda num project compose isolado (`gerenciador-consumo*`, sem
  porta no host, volumes novos, `down -v` no começo e no fim). Ele sobe **um container por vez**, na ordem das
  dependências:
  - **microsserviços:** db → supabase-pooler → redpanda → redis → rabbitmq → mailpit → identity → catalog → stock →
    reservation → admission → notification → backend (gateway) → frontend → +1 de identity, catalog, reservation,
    admission, notification e backend (o `stock` é 1 réplica fixa, #231);
  - **monólito:** db → supabase-pooler → redpanda → backend → +1 backend.
- **Medidas de cada passo.**
  - O container novo: tempo até pronto; `JVM start` (o "Started … in N s" do Spring); CPU-s gastos até pronto.
  - Depois de 60 s de assentamento, numa janela de 30 s (amostra a cada 2 s, mediana), todos os containers do
    project: CPU ociosa (Δ`usage_usec`) e memória.
  - As leituras vêm do cgroup v2 de cada container (`cpu.stat`, `memory.current`, `anon` de `memory.stat`), por um
    container amostrador com `/sys/fs/cgroup` só-leitura.
- **Bloco.** É a infra mais os serviços com 1 instância cada, medido no passo do último Java (`backend`). O mailpit
  (captador de e-mail, só de teste) e o frontend (estático, igual nos dois lados) ficam fora de todo total. Os dois
  aparecem só na tabela por container.
- **Imagens.**
  - Os serviços usam as `*.slim:consumo`, geradas pelo `slim/gerar-imagens-slim.sh` da `main` (`c09abf0`).
  - O monólito usa a imagem completa do `2980675`.
  - Por isso o tamanho de imagem não é comparável, e não entra na tabela. Memória e CPU são.
- **Host.** PC host, Windows 11, Docker Desktop 29.8.2 (WSL2, 16 vCPU, 31 GB para a VM). Medido sem nada mais
  rodando no Docker.
- **Rastreabilidade.** IDs das imagens, commits e sha256 do script estão no [`manifesto.txt`](manifesto.txt).

## Ressalvas

- **`anon` × `mem`.**
  - `anon` é a memória privada (heap e nativa) e é a base dos percentuais.
  - `mem` (`memory.current`) inclui o cache de arquivo e a memória compartilhada. Nos microsserviços, a réplica
    tem `mem ≈ anon` e o original tem `mem` maior, coerente com o cache das páginas da imagem cobrado no 1º
    container que as leu. No monólito, ao contrário, a réplica tem `mem` maior que o original (560 × 472 MB).
    Por isso os percentuais usam `anon`.
  - O Postgres guarda o `shared_buffers` em memória compartilhada, então o db aparece com ~7 MB de anon e
    136–190 MB de mem.
- **"Repouso" = 60–90 s depois de pronto, não regime.** As JVMs crescem com o tempo. O `identity`, por exemplo,
  vai de ~359 MB anon no próprio passo para ~515 MB 13 passos depois. O Δtotal de cada réplica inclui essa
  deriva dos vizinhos durante ~95 s, por isso fica um pouco acima do anon próprio.
- **Variação entre repetições.** As tabelas mostram a mediana. Uma JVM pode variar bastante de uma rep para outra:
  o `identity`, no próprio passo, deu 351, 429 e 359 MB anon. Os valores por rep estão nos CSVs.
- **CPU ociosa inclui os healthchecks.** O caso do RabbitMQ está descrito acima. Nos outros containers, os
  healthchecks são baratos: TCP via bash nos Java, `pg_isready` e afins na infra.
- **Primeira subida × réplica.** A primeira subida paga trabalho que a réplica não paga: init do banco e a
  chave RSA do identity no volume.
- **Tempo até pronto.**
  - O "pronto" da infra é o 1º healthcheck `healthy`, quantizado pelo intervalo do healthcheck (~6,5 s).
  - Nos Java, o polling no Windows dá degraus de ~1,5 s. Para o boot de um Java, o `JVM start` é a medida fina.
- **Slim do `admission`.** Foi gerada com a sonda incompleta (#210: o worker AMQP não foi exercitado). Ela sobe e
  fica pronta sem erro de classe, o que basta para esta medição. Não está validada sob uso.
- **Bug corrigido durante a série.** O prazo de espera do script arredondava o epoch (awk `%.6g`) e abortava
  algumas repetições na hora. A correção mudou só o prazo, não a medição. As duas tentativas abortadas estão em
  `brutos/descartados/`, e o histórico está no manifesto.

## Reproduzir

Em `Projeto/`, com as imagens do manifesto já construídas:

```
for r in 1 2 3; do ./carga/consumo/medir-consumo.sh servicos $r carga/results/12-consumo-subida/brutos; sleep 60; done
MONO_DIR=<worktree em 2980675, formato C:/…>/Projeto; export MONO_DIR
for r in 1 2 3; do ./carga/consumo/medir-consumo.sh monolito $r carga/results/12-consumo-subida/brutos; sleep 60; done
python carga/consumo/analisar-consumo.py carga/results/12-consumo-subida/brutos > carga/results/12-consumo-subida/resumo.md
```

Cada repetição de `servicos` leva ~36 min, e cada uma de `monolito`, ~10 min.

## Arquivos

- `brutos/servicos-rep{1,2,3}.csv` e `brutos/monolito-rep{1,2,3}.csv`: formato longo, uma linha por container por
  passo. A linha `VM` é a memória usada pela VM do Docker.
- `brutos/*.log`: logs das execuções.
- `brutos/descartados/`: as tentativas abortadas pelo bug do prazo.
- `resumo.md`: as tabelas, geradas pelo script.
- `manifesto.txt`: imagens, commits e hashes.
