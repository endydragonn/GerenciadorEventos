# autoescala (issues #111 e #112)

Autoescala de `backend` (gateway) e `reservation` (#111) e de `identity`, `catalog`, `admission` e `notification` (#112) pela regra 50/80, opção A da ADR [`docs/adr/Architecture-Decision-Autoescala.md`](../../docs/adr/Architecture-Decision-Autoescala.md):
um **override do compose** (`--scale` + HAProxy) e um **scaler em bash no host**. O compose base não muda: `run-tests.sh`, o kit de carga, os smokes e o braço "sem autoescala"
seguem iguais.

| Peça | Arquivo |
|---|---|
| Override (réplicas sem `container_name`/porta, `lb`, Hikari por réplica, one-shot `particoes`) | [`../docker-compose.autoescala.yml`](../docker-compose.autoescala.yml) |
| HAProxy (entrada `:8081`; internos `:8082` reservation, `:8083` catalog, `:8084` identity, `:8085` admission; `server-template` de 3 slots cada) | [`haproxy.cfg`](haproxy.cfg) |
| Scaler (laço nominal de 2 s, decisão em funções puras) | [`scaler.sh`](scaler.sh) · selftest sem Docker: [`scaler.selftest.sh`](scaler.selftest.sh) |
| CSV `ts,servico,ativas,standby,uso,acao` | `saida/replicas-<ts>.csv` (gitignored) |

## Subir com o override

```bash
cd Projeto
docker compose -f docker-compose.yml -f docker-compose.autoescala.yml up -d --build --wait
# com as imagens enxutas (#177): ... -f docker-compose.yml -f docker-compose.slim.yml -f docker-compose.autoescala.yml
```

Sobe 1 réplica de cada serviço atrás do `lb` (HAProxy 3.2.25, publica a **8081** como sempre; o nginx do frontend chama `lb:8081`; `backend`, `admission` e
`catalog` chamam o reservation por `lb:8082`; o `backend` chama `identity`, `catalog` e `admission` por `lb:8084`, `lb:8083` e `lb:8085`, o JWKS inclusive; o `notification` é consumer Kafka e não passa
pelo lb). Sem o scaler a stack funciona igual ao base (1 réplica de cada). Nenhuma variável nova no `.env`; todas as do override têm default (`AUTOESCALA_RESERVATION_HIKARI` 300,
`AUTOESCALA_IDENTITY_HIKARI`/`AUTOESCALA_CATALOG_HIKARI` 50, `AUTOESCALA_ADMISSION_WORKER_CONCURRENCY` 10).

> **ATENÇÃO, irreversível (#112): subir este override na stack REAL aumenta `identity.notification` e `reservation.wallet` para 3 partições PARA SEMPRE.** O serviço one-shot `particoes`
> (`rpk topic add-partitions`, idempotente: só aumenta o que tem menos de 3) roda no `up` e o broker nunca volta a 1 partição. Os consumers precisam disso (um group usa no máximo uma
> réplica por partição); a ordem por stream se mantém (a chave é a mesma). **Ordem da Rodada 4: rode o braço SEM autoescala (compose base, tópicos de 1 partição) ANTES do braço C.**
> Em project isolado (dry-run, e2e) o broker é novo e nada disso afeta o real. Os testes (`run-tests.sh`) usam tópicos próprios e não dependem do número de partições dos reais.

## Rodar o scaler

No host (Git Bash no Windows ou Linux; **nunca** em container: ele não monta o `docker.sock`). Precisa do `docker` e dos containers `lb` e `redis` no ar:

```bash
Projeto/autoescala/scaler.sh                 # laço nominal de 2 s (~4 s medido com 6 serviços; Ctrl+C encerra)
Projeto/autoescala/scaler.sh --seco          # só lê e loga o que faria
Projeto/autoescala/scaler.sh --uma-vez       # um ciclo e sai
```

**Os `-f` do scaler (`AUTOESCALA_COMPOSE_ARQUIVOS`) e o ambiente (ex.: `AUTOESCALA_RESERVATION_HIKARI`) têm de ser os MESMOS do `up`** (inclusive o `docker-compose.slim.yml`, se usado): senão a réplica nova nasce de outra imagem/config ou o `up` dispara um build.

Tudo por variável (defaults na ADR): `AUTOESCALA_PROJETO` (`gerenciador-eventos`), `AUTOESCALA_X_RPM` (= `ADMISSION_X_RPM`, 56000), `AUTOESCALA_GATEWAY_RPS` (500,
**provisório**: a capacidade por réplica do gateway não foi medida; a Rodada 4 calibra), `AUTOESCALA_{RESERVATION,GATEWAY}_{MIN,MAX}` (1/3; o máx. vai até 3, os slots do
`haproxy.cfg`), `AUTOESCALA_SOBE_PCT`/`PROMOVE_PCT`/`DESCARTA_PCT`/`DESCE_PCT` (50/80/30/50), `AUTOESCALA_DESCE_SEG`/`DESCARTA_SEG` (60), `AUTOESCALA_COOLDOWN_SEG` (30),
`AUTOESCALA_DRAIN_TETO_SEG` (30), `AUTOESCALA_PERIODO` (2), `AUTOESCALA_CSV`, `AUTOESCALA_COMPOSE_ARQUIVOS` (os `-f` do `up`, relativos a `Projeto/`). #112: capacidade por réplica em req/s
`AUTOESCALA_IDENTITY_RPS` (100, login com BCrypt), `AUTOESCALA_CATALOG_RPS` (500), `AUTOESCALA_ADMISSION_RPS` (1000), referência de lag do notification `AUTOESCALA_NOTIFICATION_LAG` (100 mensagens),
e `AUTOESCALA_{IDENTITY,CATALOG,ADMISSION,NOTIFICATION}_{MIN,MAX}` (1/3). **Todos os de capacidade são PROVISÓRIOS** (nada foi medido; a Rodada 4 calibra). Lista completa no cabeçalho do script. `X` do scaler e `ADMISSION_X_RPM` do `admission` têm de ser o mesmo valor (o default já os une).

O que ele faz (a aritmética está na ADR): lê o RPM do evento mais quente em `adm:hot` (publicado pelo `admission` no mesmo script Lua do contador) e o req/s da entrada nas
estatísticas do HAProxy; `u > 0,50` sobe uma réplica em **stand-by** (peso 0 no `lb`, por `docker exec` na Runtime API, que só escuta em `127.0.0.1:9999` dentro do container);
`u >= 0,80` promove a stand-by (peso 100) e, no reservation, grava `A+1` em `adm:replicas`; para descer, `state drain`, espera as sessões (teto 30 s) e `--scale N-1` (o compose
remove o container de maior número, que é o que ele escoou). `adm:replicas` (réplicas **ativas** do reservation, TTL 15 s, renovada a cada laço) é lida pelo script do
`admission` na conta `X × réplicas` do gatilho de 0,80; com o scaler parado a chave expira e vale `ADMISSION_RESERVATION_REPLICAS` (1: entra em fila mais cedo).

### Serviços HTTP novos e o consumer (#112)

- **`identity`, `catalog`, `admission`** (HTTP atrás do lb): a mesma regra, com `u = req/s da seção do lb ÷ (AUTOESCALA_<SVC>_RPS × A)`. O req/s é o delta de `req_tot` do frontend do serviço no `show stat`
  (`identity_interno`, `catalog_interno`, `admission_interno`; a entrada `entrada` é o gateway). Stand-by = peso 0, promoção, drain e descarte iguais aos da #111. **A stand-by do `admission` já consome a fila do RabbitMQ**
  (o peso 0 só tira o tráfego HTTP; os workers dela rodam): por isso a concorrência por réplica é `floor(32 / 3) = 10` (teto de CAS no reservation).
- **`notification`** (consumer Kafka, sem lb, **sem stand-by de verdade**: a réplica nova entra no group `notification` e já recebe partições):
  - `u = lag total do group ÷ AUTOESCALA_NOTIFICATION_LAG` (lag é fila, não taxa: não se divide por A). O scaler lê `rpk group describe notification` e `rpk topic list` por `docker exec` no `redpanda`
    (sem segredo) e o **máximo é o menor entre `AUTOESCALA_NOTIFICATION_MAX` e o nº de partições de `identity.notification` lido do broker** (um group usa no máximo uma réplica por partição; com 1 partição nunca sobe).
  - Sobe **uma** com `u > 0,50` (e nenhuma réplica ainda subindo, abaixo do máximo, fora do cooldown); desce **uma** com **lag 0 por 60 s** (e `A > mín.`); `ativa` = container `healthy`.
  - Falha de leitura (rpk, group inexistente, lag não numérico) => não age e zera a contagem de "condição sustentada", como a leitura do Redis.
  - Os consumers do `reservation` (projeção de disponibilidade e reconciliação) escalam junto com as réplicas do reservation (#111): o scaler só os registra, não decide por eles.
  - Janela at-least-once: descer mata o container com `SIGTERM`; envio em curso termina ou o evento é reentregue, e o `processed_event` evita o e-mail em dobro (a janela "e-mail enviado, processo morre antes do commit" é a de sempre).

## Ler o CSV

Uma linha por serviço por laço, 6 serviços: `ts` (UTC), `servico` (`reservation`, `backend`, `identity`, `catalog`, `admission` ou `notification`), `ativas`, `standby`, `uso` (u da regra, 1,000 = 100%; no
`notification` é `lag ÷ referência`) e a `acao` do laço (`nada`, `subir`, `promover`, `descartar`, `descer`, `subir_min`, `sem_leitura`). Réplicas × tempo = as colunas `ativas` e `standby` ao longo de `ts`.

## Voltar ao normal

Pare o scaler (Ctrl+C) e suba o compose base: `docker compose -f docker-compose.yml up -d --build` (recria `backend`, `reservation`, `identity`, `catalog`, `admission` e `notification` com nome fixo; o `lb` e o one-shot `particoes` ficam
órfãos: `--remove-orphans`). O compose base nunca foi alterado. **Os tópicos `identity.notification` e `reservation.wallet` continuam com 3 partições** (irreversível): voltar ao base depois do override NÃO devolve a linha de base de 1 partição,
e por isso a Rodada 4 roda o braço sem autoescala ANTES do braço C.

## Dry-run local com Mailpit (não é medição)

Roda SÓ num project isolado (`gerenciador-e2e-ae*`), nunca na stack real: o override do e2e (`../e2e/docker-compose.e2e.yml`: sem `container_name`, sem porta no host, rede e volumes do project, **Mailpit no lugar do SMTP do Gmail**)
vem ANTES do `docker-compose.autoescala.yml`, e um terceiro arquivo local zera a porta 8081 do `lb`:

```bash
cd Projeto
export REDIS_PASSWORD=$(openssl rand -hex 16) RABBITMQ_USER=e2e$(openssl rand -hex 4) RABBITMQ_PASSWORD=$(openssl rand -hex 16) ADMISSION_X_RPM=600   # efêmeros, só deste project
cat > /tmp/sem-porta-lb.yml <<'EOF'      # o override do e2e não conhece o lb: sem isto ele publicaria a 8081 no host
services:
  lb:
    ports: !reset []
EOF
P=gerenciador-e2e-ae; ARQS="docker-compose.yml e2e/docker-compose.e2e.yml docker-compose.autoescala.yml /tmp/sem-porta-lb.yml"   # a ordem importa: e2e ANTES da autoescala
F=""; for f in $ARQS; do F="$F -f $f"; done
docker compose -p $P $F config --format json | <confira: sem container_name/ports; notification com MAIL_HOST=mailpit>   # ANTES do up (nunca `config` sem filtro: imprime segredos)
docker compose -p $P $F up -d --build --wait lb backend admission notification mailpit    # o backend NÃO depende do admission: liste-o
AUTOESCALA_PROJETO=$P AUTOESCALA_COMPOSE_ARQUIVOS="$ARQS" autoescala/scaler.sh
```

Num checkout normal o `Projeto/.env` e o `Projeto/supabase/.env` existem e nada mais é preciso. Numa **worktree sem esses arquivos**, aponte o compose para os do checkout principal SEM copiá-los nem imprimi-los:
`export COMPOSE_ENV_FILES="<caminho do checkout principal>/Projeto/supabase/.env,<caminho do checkout principal>/Projeto/.env"` (vale para o `up` e para o scaler, que herda o ambiente) e acrescente a `ARQS` um YAML local com
`env_file: !override [<caminho do checkout principal>/Projeto/supabase/.env]` para `db`, `supabase-pooler`, `meta` e `studio` e `env_file: !override [<caminho do checkout principal>/Projeto/.env]` para `backend` (os `env_file` do compose são relativos
à worktree).

O scaler tem de rodar na MESMA shell/ambiente do `up` (as mesmas variáveis efêmeras e os mesmos `-f`): com `--no-recreate` uma réplica nova que nasce de outra configuração (e-mail sem o Mailpit, por exemplo) não dá erro nenhum. Para ver o
`notification` subir, produza uma rajada de eventos válidos direto em `identity.notification` (`rpk topic produce`, destinatários `@example.com` únicos, SMTP no Mailpit) e confira no fim: total de e-mails no Mailpit = destinatários distintos
= eventos produzidos. Use capacidades baixas por variável (`AUTOESCALA_IDENTITY_RPS=5`...) e temporizadores curtos (`AUTOESCALA_DESCE_SEG=20`...). Fim: `docker compose -p $P $F down -v` (só este project) e confira que o `gerenciador-db` real segue com o
`working_dir` de sempre. Resultado do dry-run da #112 na ADR (seção "Como ficou implementado (#112, passada b)").

## O que muda no kit de carga e nos smokes

Com o override as réplicas se chamam `<project>-<serviço>-<n>` (`backend`, `reservation`, `identity`, `catalog`, `admission`, `notification`), **sem** os nomes `gerenciador-*`. Desde a #178 o kit de carga
(`setup-host.ps1`, `rpm/subir-host-rpm.ps1`, `coleta-host.sh`, `limpar-banco.sh`) acha as réplicas por **label** do compose e aceita a lista de `-f` do braço (`COMPOSE_EXTRAS`); ver "Rodada 4" em `../carga/README.md`.
Os smokes de `docs/testes-manuais/` ainda usam `docker exec gerenciador-*` (rodam na stack base). O `run-tests.sh` já enxerga as réplicas e o `lb` (pelo label do compose) e aborta com eles no ar: pare o scaler (Ctrl+C) e os containers listados antes.

## Pool (Supavisor)

No override o Hikari do `reservation` é **300 por réplica** (= threads do Tomcat) e o de `identity` e `catalog` é **50 por réplica** (base: 100). Com o máximo de 3 réplicas de cada, contando a stand-by:
`3 × 300 (reservation) + 3 × 10 (backend) + 3 × 3 (notification) + 3 × 50 (catalog) + 3 × 50 (identity) + 250 (stock, 1 réplica fixa, #47) = 900 + 30 + 9 + 150 + 150 + 250 = 1489 <= POOLER_MAX_CLIENT_CONN 1500` (o `admission` não tem datasource; sem o `stock`, da #47, eram 1239).
(A conta de 1133 era a da #111, com uma réplica de `identity`/`catalog`/`notification`.) O pool real ao Postgres (45/245) não cresce. Mudar `AUTOESCALA_*_HIKARI` ou o máximo exige refazer a conta.

O Hikari 50 de `identity` e `catalog` fica **abaixo dos 300 threads do Tomcat** deles: sob rajada as threads esperam conexão no Hikari (`connection-timeout` de 5 s) antes de falhar. Com 1 réplica (começo do braço C)
o pool é metade do base. Efeito a medir na Rodada 4. **Desde a #47 a folga é de 11:** com o `stock` (Hikari 250), `identity`/`catalog` cabem com no máximo 51 por réplica; acima disso o Hikari do `stock` tem de cair para `1500 − soma` (pré-condição da Rodada 5).

## Limites conhecidos

- Se o Redis, o lb ou o `docker` não respondem direito, o scaler não age naquele laço (loga `AVISO`, o CSV marca `sem_leitura` no reservation) e o período cego não conta como condição sustentada. O mesmo vale para o lag/partições do Kafka (notification)
  e para um `req_tot` ilegível de um serviço HTTP (frontend ausente do `show stat`): `sem_leitura` nesse serviço, nunca "carga 0".
- **Período do laço:** o nominal é 2 s (`AUTOESCALA_PERIODO`), mas cada laço faz várias chamadas ao docker (~0,2 s cada no Windows); com os 6 serviços o laço real levou ~4 s no dry-run (os critérios de 60 s e o cooldown absorvem).
- Com o `admission` fora (e o Redis no ar) a chave `adm:hot` expira e a carga do reservation lê 0: o scaler não a vê e pode descer.
- Máximo de 3 réplicas por serviço (os slots do `haproxy.cfg`; mais exige editar o `server-template`).
- Container que nunca fica saudável bloqueia novas subidas daquele serviço (o scaler só avisa no log após `AUTOESCALA_BOOT_TETO_SEG`).
- Reiniciar o `backend` (cache de JWKS) ou o `lb` é manual; a limpeza de banco do kit continua exigindo reiniciar **todas** as réplicas do gateway.
- **Rotação da chave do JWT com N réplicas do `identity`:** a chave é um arquivo no volume `identity-keys`. Apagar o arquivo e reiniciar SÓ uma réplica deixa chaves diferentes no ar (as outras seguem com a antiga na memória e o JWKS varia
  conforme a réplica). Rotacionar exige **parar TODAS as réplicas do identity**, apagar o arquivo, subir e reiniciar o `backend` (cache de JWKS de 24 h). O `run-tests.sh` já exige todas paradas.
- **Limpeza do banco com N réplicas (#178):** `Projeto/carga/limpar-banco.sh` (versionado) acha as réplicas por label, para TODAS (stand-by inclusive), apaga a chave do volume `<project>_identity-keys`, sobe de volta
  (identity, reservation/catalog, admission, backend), espera os slots UP no lb e prova o JWKS com um login de sonda. **O scaler tem de estar parado:** o script o detecta pela chave `adm:replicas` no Redis e recusa rodar.
- **JWKS no boot:** a 1ª busca do JWKS pelo `lb` pode cair antes de o slot do identity ficar UP (`rise 2`, checks de 2 s) e o gateway só tenta de novo a cada 30 s. Depois do `up`, espere os slots UP e faça um login antes de aplicar carga (o dry-run faz).
- **Stand-by do `admission` consome a fila** (workers rodam; só o HTTP fica sem tráfego) e o one-shot `particoes` não é visto pela guarda do `run-tests.sh` (termina sozinho, `restart: "no"`).
- A hipótese de que X não cresce com réplicas (todas disputam a linha do `eventcap`) é da Rodada 4 medir.
