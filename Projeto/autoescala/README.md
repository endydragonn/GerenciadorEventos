# autoescala (issue #111)

Autoescala de `backend` (gateway) e `reservation` pela regra 50/80, opção A da ADR [`docs/adr/Architecture-Decision-Autoescala.md`](../../docs/adr/Architecture-Decision-Autoescala.md):
um **override do compose** (`--scale` + HAProxy) e um **scaler em bash no host**. O compose base não muda: `run-tests.sh`, o kit de carga, os smokes e o braço "sem autoescala"
seguem iguais.

| Peça | Arquivo |
|---|---|
| Override (réplicas sem `container_name`/porta, `lb`, Hikari 300 do reservation) | [`../docker-compose.autoescala.yml`](../docker-compose.autoescala.yml) |
| HAProxy (entrada `:8081`, reservation interno `:8082`, `server-template` de 3 slots) | [`haproxy.cfg`](haproxy.cfg) |
| Scaler (laço de 2 s, decisão em funções puras) | [`scaler.sh`](scaler.sh) · selftest sem Docker: [`scaler.selftest.sh`](scaler.selftest.sh) |
| CSV `ts,servico,ativas,standby,uso,acao` | `saida/replicas-<ts>.csv` (gitignored) |

## Subir com o override

```bash
cd Projeto
docker compose -f docker-compose.yml -f docker-compose.autoescala.yml up -d --build --wait
# com as imagens enxutas (#177): ... -f docker-compose.yml -f docker-compose.slim.yml -f docker-compose.autoescala.yml
```

Sobe 1 réplica de `backend` e de `reservation` atrás do `lb` (HAProxy 3.2.25, publica a **8081** como sempre; o nginx do frontend chama `lb:8081`; `backend`, `admission` e
`catalog` chamam o reservation por `lb:8082`). Sem o scaler a stack funciona igual ao base (1 réplica de cada). Nenhuma variável nova no `.env`; todas as do override têm default
(`AUTOESCALA_RESERVATION_HIKARI`, 300).

## Rodar o scaler

No host (Git Bash no Windows ou Linux; **nunca** em container: ele não monta o `docker.sock`). Precisa do `docker` e dos containers `lb` e `redis` no ar:

```bash
Projeto/autoescala/scaler.sh                 # laço de 2 s (Ctrl+C encerra)
Projeto/autoescala/scaler.sh --seco          # só lê e loga o que faria
Projeto/autoescala/scaler.sh --uma-vez       # um ciclo e sai
```

**Os `-f` do scaler (`AUTOESCALA_COMPOSE_ARQUIVOS`) e o ambiente (ex.: `AUTOESCALA_RESERVATION_HIKARI`) têm de ser os MESMOS do `up`** (inclusive o `docker-compose.slim.yml`, se usado): senão a réplica nova nasce de outra imagem/config ou o `up` dispara um build.

Tudo por variável (defaults na ADR): `AUTOESCALA_PROJETO` (`gerenciador-eventos`), `AUTOESCALA_X_RPM` (= `ADMISSION_X_RPM`, 56000), `AUTOESCALA_GATEWAY_RPS` (500,
**provisório**: a capacidade por réplica do gateway não foi medida; a Rodada 4 calibra), `AUTOESCALA_{RESERVATION,GATEWAY}_{MIN,MAX}` (1/3; o máx. vai até 3, os slots do
`haproxy.cfg`), `AUTOESCALA_SOBE_PCT`/`PROMOVE_PCT`/`DESCARTA_PCT`/`DESCE_PCT` (50/80/30/50), `AUTOESCALA_DESCE_SEG`/`DESCARTA_SEG` (60), `AUTOESCALA_COOLDOWN_SEG` (30),
`AUTOESCALA_DRAIN_TETO_SEG` (30), `AUTOESCALA_PERIODO` (2), `AUTOESCALA_CSV`, `AUTOESCALA_COMPOSE_ARQUIVOS` (os `-f` do `up`, relativos a `Projeto/`). Lista completa no cabeçalho
do script. `X` do scaler e `ADMISSION_X_RPM` do `admission` têm de ser o mesmo valor (o default já os une).

O que ele faz (a aritmética está na ADR): lê o RPM do evento mais quente em `adm:hot` (publicado pelo `admission` no mesmo script Lua do contador) e o req/s da entrada nas
estatísticas do HAProxy; `u > 0,50` sobe uma réplica em **stand-by** (peso 0 no `lb`, por `docker exec` na Runtime API, que só escuta em `127.0.0.1:9999` dentro do container);
`u >= 0,80` promove a stand-by (peso 100) e, no reservation, grava `A+1` em `adm:replicas`; para descer, `state drain`, espera as sessões (teto 30 s) e `--scale N-1` (o compose
remove o container de maior número, que é o que ele escoou). `adm:replicas` (réplicas **ativas** do reservation, TTL 15 s, renovada a cada laço) é lida pelo script do
`admission` na conta `X × réplicas` do gatilho de 0,80; com o scaler parado a chave expira e vale `ADMISSION_RESERVATION_REPLICAS` (1: entra em fila mais cedo).

## Ler o CSV

Uma linha por serviço por laço: `ts` (UTC), `servico` (`reservation` ou `backend`), `ativas`, `standby`, `uso` (u da regra, 1,000 = 100%) e a `acao` do laço (`nada`, `subir`,
`promover`, `descartar`, `descer`, `subir_min`). Réplicas × tempo = as colunas `ativas` e `standby` ao longo de `ts`.

## Voltar ao normal

Pare o scaler (Ctrl+C) e suba o compose base: `docker compose -f docker-compose.yml up -d --build` (recria `backend` e `reservation` com nome e porta fixos; o `lb` fica órfão:
`--remove-orphans`). O compose base nunca foi alterado.

## O que muda no kit de carga e nos smokes

Com o override as réplicas se chamam `<project>-backend-<n>` / `<project>-reservation-<n>`, **sem** os nomes `gerenciador-backend` / `gerenciador-reservation` que o kit
(`setup-host.ps1`, `rpm/subir-host-rpm.ps1`, `degraus/rodar-degraus.ps1`) e os smokes de `docs/testes-manuais/` usam em `docker exec`. Adaptar o kit para a autoescala é da
Rodada 4 (S3/#178); esta issue só entrega o mecanismo e o dry-run. O `run-tests.sh` já enxerga as réplicas e o `lb` (pelo label do compose) e aborta com eles no ar: pare o scaler (Ctrl+C) e os containers listados antes.

## Pool (Supavisor)

No override o Hikari do `reservation` é **300 por réplica** (= threads do Tomcat). Com o máximo de 3 + 3 réplicas, contando a stand-by: `3 × 300 + 3 × 10 + 3 (notification) +
100 (catalog) + 100 (identity) = 1133 <= POOLER_MAX_CLIENT_CONN 1500`. O pool real ao Postgres (45/245) não cresce. Mudar `AUTOESCALA_RESERVATION_HIKARI` ou o máximo exige
refazer a conta.

## Limites conhecidos

- Se o Redis, o lb ou o `docker` não respondem direito, o scaler não age naquele laço (loga `AVISO`, o CSV marca `sem_leitura` no reservation) e o período cego não conta como condição sustentada.
- Com o `admission` fora (e o Redis no ar) a chave `adm:hot` expira e a carga do reservation lê 0: o scaler não a vê e pode descer.
- Máximo de 3 réplicas por serviço (os slots do `haproxy.cfg`; mais exige editar o `server-template`).
- Container que nunca fica saudável bloqueia novas subidas daquele serviço (o scaler só avisa no log após `AUTOESCALA_BOOT_TETO_SEG`).
- Reiniciar o `backend` (cache de JWKS) ou o `lb` é manual; a limpeza de banco do kit continua exigindo reiniciar **todas** as réplicas do gateway.
- A hipótese de que X não cresce com réplicas (todas disputam a linha do `eventcap`) é da Rodada 4 medir.
