# Imagens enxutas dos serviços com o Slim (issue #177)

Versões mínimas das imagens de `backend`, `identity`, `catalog`, `reservation`, `stock` (desde a #47), `notification` e `admission` (esta desde a #178), geradas com o
[Slim](https://github.com/slimtoolkit/slim) (`slim build --target <imagem>`): ele roda o contêiner, observa (ptrace) quais arquivos o
processo realmente usa enquanto um probe exercita os endpoints, e monta uma imagem só com eles. **Os Dockerfiles não mudam**. Desde a #197 (D7) as `*.slim` são o
**padrão** do `docker-compose.yml` (sem `build:`; `pull_policy: never`: sem a imagem local o `up` falha com "image not found" em vez de puxar do Docker Hub) e a imagem
completa (Dockerfile) vem pelo override `docker-compose.completa.yml`, para depurar.

## Usar

```bash
# 1. gerar as 7 imagens (project isolado gerenciador-e2e-slim; leva ~15-25 min na 1ª vez, o build gordo vem do cache depois)
Projeto/slim/gerar-imagens-slim.sh                # ou só alguns: Projeto/slim/gerar-imagens-slim.sh identity notification admission

# 2. subir a stack (as slim são o padrão do compose; precisam existir localmente)
docker compose -f Projeto/docker-compose.yml up -d --wait

# 3. suíte e2e da stack inteira com as imagens slim (o e2e confere que cada um dos 7 serviços roda o ID da imagem *.slim)
Projeto/e2e/run-e2e.sh

# depurar com a imagem COMPLETA (build do Dockerfile; o override zera o `image` slim, senão o build gordo levaria o nome slim)
docker compose -f Projeto/docker-compose.yml -f Projeto/docker-compose.completa.yml up -d --build <serviço>
E2E_COMPLETA=1 Projeto/e2e/run-e2e.sh             # e2e com as completas (também confere o ID da imagem de cada serviço)
```

`up -d --build <serviço>` **não** reconstrói mais os serviços Java: roda a slim que existir (possivelmente velha). Depois de mudar código, rode o passo 1 para o
serviço tocado e então o `up -d`.

**`SLIM_TAG`** (opcional, default `latest`; vale da shell ou do `Projeto/.env`, como qualquer variável do compose): tag das imagens geradas e usadas
(`gerenciador-eventos-<serviço>.slim:$SLIM_TAG`) pelo compose, pelo script e pelo `run-e2e.sh`; os dois scripts perguntam ao compose (`config --images`) qual tag ele resolve e a mostram no log. Com outra tag, o `:latest` existente NÃO é sobrescrito:
`SLIM_TAG=k8s Projeto/slim/gerar-imagens-slim.sh identity` e `SLIM_TAG=k8s docker compose -f Projeto/docker-compose.yml up -d`.

Imagens geradas: `gerenciador-eventos-<serviço>.slim:${SLIM_TAG:-latest}`. Pré-requisitos: Docker (compose v2), python, `Projeto/.env` e
`Projeto/supabase/.env`; nada é instalado no host (o Slim roda como contêiner `dslim/slim:1.40.11`, versão fixa).
Idempotente: cada execução regenera as imagens da tag `$SLIM_TAG` a partir do código atual e derruba o project no fim (`MANTER=1` mantém).

## Antes / depois (`docker image ls`)

"Antes" = a imagem gorda construída pelo compose a partir do mesmo código (`Dockerfile` intocado, `eclipse-temurin:25-jre-jammy`).

| Serviço | Antes | Depois (slim) | Redução |
|---|---|---|---|
| `backend` (gateway) | 593 MB | 458 MB | -23% |
| `identity` | 523 MB | 386 MB | -26% |
| `catalog` | 522 MB | 384 MB | -26% |
| `reservation` | 522 MB | 384 MB | -26% |
| `stock` (#47) | 521 MB | 384 MB | -26% |
| `notification` | 509 MB | 371 MB | -27% |
| `admission` (#178) | 503 MB | 365 MB | -27% |

A redução é moderada de propósito: o fat jar e o JRE (`lib/modules`) são um arquivo cada e ficam inteiros, e `lib/` do JRE foi mantida
(abaixo). O ganho vem do que sobra da base Ubuntu (apt/dpkg, locales, utilitários, libs não carregadas).

## O que o probe exercita

Cada alvo sobe como contêiner temporário na rede do project isolado, com o ambiente real do compose (`--env-file`, gerado por
`docker compose config` num arquivo temporário; nunca impresso, e a saída do Slim passa por um filtro que mascara qualquer valor), com
o resto da stack gordo ao redor. O serviço gordo de mesmo nome é parado antes, para que os consumer groups de 1 partição
(`notification`, `reservation`, `stock`) caiam no contêiner-alvo. `--http-probe-cmd-file` com `protocol: http` (sem tentativa em https):

| Serviço | Chamadas do probe |
|---|---|
| `identity` | JWKS (`/.well-known/jwks.json`); `/internal/**` com `X-Internal-Token`: criar usuário, login (200 e 401), by-email, names, wallets, validate, registrations (+resend, verify), `recaptcha/verify` (HTTPS de saída ao Google); chamada sem token (401). O relay do outbox publica `identity.notification`/`identity.user` no Redpanda. |
| `catalog` | `/internal/events/**`: validate, criar, search, por criador, buscar, exists, creator, image, atualizar; sem token (401). O relay publica `catalog.event`; o `reservation` gordo recebe o `eventcap` por HTTP. |
| `reservation` | `/internal/reservations` (cas e naive, replay, active, confirm, user, cancelar), disponibilidade; sem token (401). Desde a #47 o `eventcap` é do `stock`: o script cria `900001`/`900002` no `stock` GORDO antes do probe e o `reservation`-alvo o chama por HTTP (a saga: um hold por reserva `cas`/`naive`). O consumer (`reservation`) e o relay sobem junto. |
| `stock` (#47) | `/internal/eventcaps/**` (PUT/GET/batch/PATCH/DELETE com 409 por hold), `/internal/holds` (201, repetição 200, `naive`, 409 de lápide, 400 sem capacidade e malformado) e `/internal/holds/{id}/release` (idempotente, lápide); sem token (401). **Prova do consumer:** antes do start o script cria um hold no `stock` gordo e produz um `ReservationCancelled` com o `holdId` em `reservation.wallet`; depois do Slim exige o hold `released` e a vaga devolvida (`quant` 5): o `StockReleaseConsumer` da imagem-alvo rodou. O `CatalogReconciliationConsumer` sobe junto (group `stock-catalog`, `latest`: só o join é exercitado). |
| `backend` (gateway) | `/actuator/health`; `/bff/**` com um JWT real (usuário de probe criado e promovido a host no identity gordo): login (200/401), `me`, criar/buscar evento, reservar e confirmar, carteira, cadastro em duas fases; token inválido (401). A validação do token busca o JWKS do identity. |
| `admission` (#178) | Sobe com `redis` e `rabbitmq` do project isolado e o `reservation` gordo (o worker pool o chama). O alvo roda com `ADMISSION_X_RPM=60` (imposto no arquivo de ambiente do alvo): `POST /internal/admissions` ×60 (as primeiras ficam `sync`; passando de 80% de X o evento entra em **modo fila**: script Lua do contador, `enfileirar.lua`, publicação no RabbitMQ), `GET .../result` das 60 chaves, `GET .../events/{id}/rpm`, repetição da chave, chave de outro evento (422), 404 e chamada sem token (401). O `eventcap` do evento do probe (910001) é criado antes no `reservation` gordo. **Prova de que o listener AMQP e o worker rodaram na imagem-alvo:** depois do Slim, o script exige reservas do evento 910001 em `reservation.walletevent` (só o worker as cria; o `POST /internal/admissions` nunca reserva) |
| `notification` | Sem HTTP (`--http-probe=false --continue-after 120`): um `VerificationCodeRequested` é produzido em `identity.notification` antes do start; o script exige que o consumer da imagem-alvo o processe (1 e-mail no captador Mailpit). |

Depois de gerar, o script confere que cada imagem slim tem `bash head grep awk` (healthchecks do compose; `printf` é builtin do bash) e que `/var/lib/identity` da `identity.slim` está **vazio** (guarda da chave do JWT, abaixo). O critério final é
`Projeto/e2e/run-e2e.sh` verde: cadastro por e-mail, evento, reserva, confirmação, replay e a rajada do modo fila (passo 4b), com as 7 imagens slim.

## Includes e motivo

| Include | Serviços | Motivo |
|---|---|---|
| `--include-shell`, `--include-exe bash head grep awk` | todos | os healthchecks do compose usam `bash` (/dev/tcp, `printf` builtin), `head`, `grep` (backend) e `awk` em `/proc/net/tcp` (notification); o probe não os toca, então o Slim os removeria |
| `--include-exe printenv` | backend, notification | backend: os smokes de `docs/testes-manuais` leem `RESERVATION_READPATH` com `printenv` dentro do contêiner; notification: o `run-e2e.sh` (passo 2) roda `printenv MAIL_HOST` |
| `--include-path /opt/java/openjdk/lib` | todos | libs nativas do JRE são carregadas sob demanda (`libnet`, `libnio`, `libzip`, `libextnet`, `libmanagement`...) e `lib/security/cacerts` só é lido no TLS de saída (reCAPTCHA no identity, STARTTLS do Gmail no notification), que o probe local não exercita. Em vez de caçar arquivo a arquivo, a correção estável mais barata é manter `lib/` inteira (o `lib/modules` já ficava inteiro mesmo) |
| `--include-path /opt/java/openjdk/conf` | todos | `net.properties`, `security/java.security`, logging: lidos de forma preguiçosa |
| `--include-path /tmp` | todos | Tomcat, `hsperfdata` e nativos extraídos pelos libs de compressão do Kafka precisam de `/tmp` gravável |
| `--include-path /var/lib/identity` + `--exclude-pattern '/var/lib/identity/*'` | identity | o diretório existe (o compose monta o volume `identity-keys` nele), **mas vazio**: o alvo do Slim sobe sem volume e gera a chave RSA privada do JWT no próprio filesystem; sem o `exclude`, o `--include-new` (padrão do Slim) a deixaria numa camada da imagem, e todo volume novo nasceria com a MESMA chave (token antigo valendo depois de um `down -v`). O passo 3 do script aborta se `ls -A /var/lib/identity` na `identity.slim` não sair vazio |

Certificados do SO (`/etc/ssl/certs`) entram pelo padrão do Slim (`--include-cert-all`).

## Perfis Seccomp / AppArmor (`perfis/`)

O Slim gera, de graça, um perfil Seccomp (`<serviço>-seccomp.json`, lista de syscalls observadas) e um AppArmor (`<serviço>-apparmor-profile`)
por serviço; ficam versionados aqui (não têm segredo). **Aplicá-los é opcional e não é o padrão** (decisão da issue #177: fora de escopo):
o `docker-compose.yml` não os usa. Para experimentar um serviço:

```yaml
    security_opt:
      - seccomp=./slim/perfis/identity-seccomp.json
```

Risco de aplicar: o perfil só contém o que o probe e o boot exercitaram; uma syscall que só aparece em carga ou em um caminho raro seria
negada (EPERM). Se aplicar em produção, repetir a carga e o e2e com o perfil ligado antes.

## Limites e armadilhas registradas

- **Docker 29 + Slim 1.40.11:** o Slim fala a API 1.24 por padrão e o Docker 29 recusa (mínimo 1.40): `--crt-api-version 1.44` (flag global,
  antes de `build`). Na rede `bridge` padrão o IP do alvo vem vazio e o IPC do sensor falha; por isso o alvo e o contêiner do Slim ficam na
  rede do project (`--network`).
- A saída do Slim mostra `size.original='0 B'`/`output image ID mismatch` com o image store containerd do Docker Desktop: é cosmético, a
  imagem sai correta. Os tamanhos da tabela vêm de `docker image ls`.
- O probe só cobre o que ele chama: um caminho de código que carregue arquivo do SO só em produção (ex.: TLS ao Gmail real) não é testado
  aqui; por isso o JRE `lib/` e `conf/` ficam inteiros. Imagem que quebrar e não tiver correção por `--include-path` fica com a original.
- Só a variante com `lib/` do JRE inteira foi gerada e medida (os tamanhos acima); manter só o que o probe tocou não foi medido.
- Limpeza de disco: o script deixa as gordas `gerenciador-e2e-slim-<serviço>:latest` e, a cada regeneração, as `*.slim` anteriores viram `<none>`:
  `docker image prune` e `docker image rm gerenciador-e2e-slim-{backend,identity,catalog,reservation,notification,admission}`; o volume de cache do sensor,
  `docker volume rm slim-sensor.1.40.11`.
- Só os perfis são versionados: `creport.json`, `Dockerfile.reversed` e o arquivo de ambiente do alvo (segredos) ficam num diretório
  temporário que o script apaga.
- Smoke `cas` na stack real slim: fora deste script (o smoke é do orquestrador). Imagem slim gerada antes de uma mudança de código fica velha (o e2e com as slim de 2026-10-28 falhou com o `backend.slim` anterior à #41, que não chamava o `admission`): regenere depois de mudar código.
