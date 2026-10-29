# Imagens enxutas dos serviços com o Slim (issue #177)

Versões mínimas das imagens de `backend`, `identity`, `catalog`, `reservation`, `notification` e `admission` (esta desde a #178), geradas com o
[Slim](https://github.com/slimtoolkit/slim) (`slim build --target <imagem>`): ele roda o contêiner, observa (ptrace) quais arquivos o
processo realmente usa enquanto um probe exercita os endpoints, e monta uma imagem só com eles. **Os Dockerfiles não mudam** e a imagem
original continua o padrão do compose; as `*.slim` entram por um override opcional.

## Usar

```bash
# 1. gerar as 6 imagens (project isolado gerenciador-e2e-slim; leva ~15-25 min na 1ª vez, o build gordo vem do cache depois)
Projeto/slim/gerar-imagens-slim.sh                # ou só alguns: Projeto/slim/gerar-imagens-slim.sh identity notification admission

# 2. subir a stack com elas (precisa das imagens locais; o `build: !reset null` do override impede o rebuild da gorda com o nome slim)
docker compose -f Projeto/docker-compose.yml -f Projeto/docker-compose.slim.yml up -d --wait

# 3. suíte e2e da stack inteira com as imagens slim (o e2e confere que cada um dos 6 serviços roda o ID da imagem *.slim)
E2E_SLIM=1 Projeto/e2e/run-e2e.sh
```

Imagens geradas: `gerenciador-eventos-<serviço>.slim:latest`. Pré-requisitos: Docker (compose v2), python, `Projeto/.env` e
`Projeto/supabase/.env`; nada é instalado no host (o Slim roda como contêiner `dslim/slim:1.40.11`, versão fixa).
Idempotente: cada execução regenera as mesmas tags a partir do código atual e derruba o project no fim (`MANTER=1` mantém).

## Antes / depois (`docker image ls`)

"Antes" = a imagem gorda construída pelo compose a partir do mesmo código (`Dockerfile` intocado, `eclipse-temurin:25-jre-jammy`).

| Serviço | Antes | Depois (slim) | Redução |
|---|---|---|---|
| `backend` (gateway) | 593 MB | 458 MB | -23% |
| `identity` | 523 MB | 386 MB | -26% |
| `catalog` | 522 MB | 384 MB | -26% |
| `reservation` | 522 MB | 384 MB | -26% |
| `notification` | 509 MB | 371 MB | -27% |
| `admission` (#178) | 503 MB | 365 MB | -27% |

A redução é moderada de propósito: o fat jar e o JRE (`lib/modules`) são um arquivo cada e ficam inteiros, e `lib/` do JRE foi mantida
(abaixo). O ganho vem do que sobra da base Ubuntu (apt/dpkg, locales, utilitários, libs não carregadas).

## O que o probe exercita

Cada alvo sobe como contêiner temporário na rede do project isolado, com o ambiente real do compose (`--env-file`, gerado por
`docker compose config` num arquivo temporário; nunca impresso, e a saída do Slim passa por um filtro que mascara qualquer valor), com
o resto da stack gordo ao redor. O serviço gordo de mesmo nome é parado antes, para que os consumer groups de 1 partição
(`notification`, `reservation`) caiam no contêiner-alvo. `--http-probe-cmd-file` com `protocol: http` (sem tentativa em https):

| Serviço | Chamadas do probe |
|---|---|
| `identity` | JWKS (`/.well-known/jwks.json`); `/internal/**` com `X-Internal-Token`: criar usuário, login (200 e 401), by-email, names, wallets, validate, registrations (+resend, verify), `recaptcha/verify` (HTTPS de saída ao Google); chamada sem token (401). O relay do outbox publica `identity.notification`/`identity.user` no Redpanda. |
| `catalog` | `/internal/events/**`: validate, criar, search, por criador, buscar, exists, creator, image, atualizar; sem token (401). O relay publica `catalog.event`; o `reservation` gordo recebe o `eventcap` por HTTP. |
| `reservation` | `/internal/eventcaps/**` (PUT/GET/batch/PATCH/DELETE), `/internal/reservations` (cas e naive, replay, active, confirm, user, cancelar), disponibilidade; sem token (401). Os consumers (`reservation`, `reservation-catalog`) e o relay sobem junto. |
| `backend` (gateway) | `/actuator/health`; `/bff/**` com um JWT real (usuário de probe criado e promovido a host no identity gordo): login (200/401), `me`, criar/buscar evento, reservar e confirmar, carteira, cadastro em duas fases; token inválido (401). A validação do token busca o JWKS do identity. |
| `admission` (#178) | Sobe com `redis` e `rabbitmq` do project isolado e o `reservation` gordo (o worker pool o chama). O alvo roda com `ADMISSION_X_RPM=60` (imposto no arquivo de ambiente do alvo): `POST /internal/admissions` ×60 (as primeiras ficam `sync`; passando de 80% de X o evento entra em **modo fila**: script Lua do contador, `enfileirar.lua`, publicação no RabbitMQ), `GET .../result` das 60 chaves, `GET .../events/{id}/rpm`, repetição da chave, chave de outro evento (422), 404 e chamada sem token (401). O `eventcap` do evento do probe (910001) é criado antes no `reservation` gordo. **Prova de que o listener AMQP e o worker rodaram na imagem-alvo:** depois do Slim, o script exige reservas do evento 910001 em `reservation.walletevent` (só o worker as cria; o `POST /internal/admissions` nunca reserva) |
| `notification` | Sem HTTP (`--http-probe=false --continue-after 120`): um `VerificationCodeRequested` é produzido em `identity.notification` antes do start; o script exige que o consumer da imagem-alvo o processe (1 e-mail no captador Mailpit). |

Depois de gerar, o script confere que cada imagem slim tem `bash head grep awk` (healthchecks do compose; `printf` é builtin do bash) e que `/var/lib/identity` da `identity.slim` está **vazio** (guarda da chave do JWT, abaixo). O critério final é
`E2E_SLIM=1 Projeto/e2e/run-e2e.sh` verde: cadastro por e-mail, evento, reserva, confirmação, replay e a rajada do modo fila (passo 4b), com as 6 imagens slim.

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
o override `docker-compose.slim.yml` não os usa. Para experimentar um serviço:

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
- Smoke `cas` na stack real slim: fora deste script (o smoke é do orquestrador). Imagem slim gerada antes de uma mudança de código fica velha (o `E2E_SLIM=1` de 2026-10-28 falhou com o `backend.slim` anterior à #41, que não chamava o `admission`): regenere depois de mudar código.
