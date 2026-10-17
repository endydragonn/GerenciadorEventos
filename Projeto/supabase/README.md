# supabase/ — Supabase self-hosted (vendorizado)

Origem: repositório `supabase/supabase`, pasta `docker/`, commit
`bc27d2661edd09a5d0b8d76ce33da6d4071a756e` (2026-10-16).
Os arquivos vendorizados são cópia fiel do upstream; não os edite aqui (a única exceção local é
`functions-role.sql`, abaixo).

Nesse commit o gateway padrão do upstream é o Envoy; o Kong vem do overlay
`docker-compose.kong.yml`, que é a referência de ligação para o `kong.yml` e o
`kong-entrypoint.sh` daqui.

## O que está aqui

- `volumes/db/` — `roles.sql`, `jwt.sql`, `_supabase.sql`, `pooler.sql` (init do Postgres) e
  `functions-role.sql` (adição local, não vem do upstream: cria a role `supabase_functions_admin`
  que o `roles.sql` altera, já que o `webhooks.sql` do upstream que a cria não foi trazido).
  Hoje o compose monta só `roles.sql` e `functions-role.sql`; `_supabase.sql`/`pooler.sql` são da #19.
- `volumes/pooler/pooler.exs` — configuração do Supavisor.
- `volumes/api/` — `kong.yml` e `kong-entrypoint.sh` (gateway Kong do Studio).
- `docker-compose.studio.yml` — serviços `studio`, `meta` e `kong` (adaptação local do `studio`/`meta` do
  `docker-compose.yml` e do `docker-compose.kong.yml` do upstream; não é cópia fiel). Incluído por
  `Projeto/docker-compose.yml`. Ver "Studio" abaixo.
- `utils/generate-keys.sh` — gera os segredos.
- `.env.example` — versão reduzida do upstream, só com o que db, Supavisor, Studio, meta e kong usam.

## O que ficou de fora

Só sobe a cadeia enxuta: db + Supavisor + Studio/meta/kong. GoTrue, PostgREST, Realtime,
Storage, imgproxy e edge functions não sobem (o backend Spring faz auth e API), então seus
arquivos (`realtime.sql`, `logs.sql`, `webhooks.sql`, `functions/`, `storage/`, `envoy`, o
`docker-compose.yml` do upstream) não foram trazidos.

## Gerar os segredos

Rode SEMPRE a partir de `Projeto/supabase/`: o script edita o `.env` do diretório atual e,
rodado em `Projeto/`, sobrescreveria os segredos do backend.

```
cd Projeto/supabase && git check-ignore -q .env && [ ! -e .env ] && command -v openssl >/dev/null && cp .env.example .env && sh utils/generate-keys.sh --update-env > /dev/null && rm -f .env.old && [ "$(grep -c '<gerado' .env)" = 0 ]
```

- `git check-ignore -q .env` aborta se o `.env` não estiver ignorado (evita criar um arquivo versionável com segredos).
- `[ ! -e .env ]` aborta se já existe um `.env`, para não sobrescrevê-lo.
- `command -v openssl` aborta se o openssl não existe (o script depende dele).
- `> /dev/null` evita imprimir as chaves no terminal.
- `.env.old` é o backup que o `sed -i.old` do script cria; é removido e também é ignorado pelo git.
- O último teste falha se sobrou algum placeholder `<gerado...>` no `.env`.

Atenção: regerar os segredos com um volume do db já inicializado quebra a autenticação (a
senha e os roles ficam gravados no volume). Para regerar, apague o volume do db junto.

O `.env` gerado nunca é commitado. Estes segredos são independentes do `Projeto/.env` do backend.

## Ligação no compose

O `db` já está ligado (issue #18): o serviço `db` de `Projeto/docker-compose.yml` usa a imagem
`supabase/postgres` com `env_file: ./supabase/.env`, então **`Projeto/supabase/.env` precisa
existir antes do `up`** (gere-o como acima). O banco é `meu_banco` (ver `Projeto/Backend/db/CLAUDE.md`),
montado com `volumes/db/roles.sql` e `volumes/db/functions-role.sql`. O pooler (#19) ainda não está ligado.

## Studio (issue #20)

`Projeto/docker-compose.yml` inclui `supabase/docker-compose.studio.yml`, que sobe **só** `studio`
(`supabase/studio`), `meta` (`supabase/postgres-meta`) e `kong` (gateway do Studio), na rede
`gerenciador-net`. GoTrue, PostgREST, Realtime, Storage, imgproxy e edge functions não sobem: as rotas
`/auth`, `/rest`, `/realtime`, `/storage` e `/functions` do `kong.yml` vendorizado apontam para eles e
respondem erro de gateway se chamadas; o Kong só resolve o DNS na requisição, então isso não impede o start.

- URL: `http://localhost:8000` (porta do Kong; o Studio e o `meta` não publicam porta). A porta é publicada só
  em `127.0.0.1` (atrás dela há um console SQL de superuser). Login (basic auth):
  `DASHBOARD_USERNAME` e `DASHBOARD_PASSWORD` do `Projeto/supabase/.env`.
- Inspeção das tabelas do app (`eventcap`, `walletevent`, ...): Table Editor, schema `public`. Studio e `meta`
  conectam ao banco `meu_banco` como `supabase_admin` (superuser; senha = `POSTGRES_PASSWORD` do Supabase),
  e não como `postgres` (default do upstream): as tabelas do app pertencem à role `admin` e o `postgres`
  não tem grant nelas. Também não usam o próprio `admin`: a senha dele mora no `Projeto/.env` e não deve
  chegar aos serviços do Supabase.
- Não há variável nova no `.env`. `SUPABASE_PUBLIC_URL` (`http://localhost:8000`) e `SUPABASE_PUBLISHABLE_KEY`/
  `SUPABASE_SECRET_KEY` (vazias, mas presentes) já estão no `.env.example`.
- Os snippets SQL do Studio ficam em `volumes/snippets/` (ignorado pelo git).

### Segredos: por que `env_file` no serviço e um shim no entrypoint

A interpolação `${...}` do `docker-compose.studio.yml` lê o `.env` da pasta dele (`Projeto/supabase/.env`) e,
depois, o ambiente do compose principal (`Projeto/.env`, do backend), que **vence** em nomes iguais (testado).
Só `POSTGRES_PASSWORD` e `JWT_SECRET` colidem: o backend os define com outro sentido, então interpolá-los
entregaria os segredos do backend ao Supabase. Por isso `studio` e `meta` carregam `./.env` (este diretório)
pelo `env_file` do serviço, que é literal, e um shim no `entrypoint` mapeia só esses dois para o nome que a
imagem espera (`POSTGRES_PASSWORD` -> `PG_META_DB_PASSWORD` no `meta`; `JWT_SECRET` -> `AUTH_JWT_SECRET` no
`studio`). O resto (`ANON_KEY`, `SERVICE_ROLE_KEY`, `PG_META_CRYPTO_KEY`, `STUDIO_DEFAULT_*`) não colide e
interpola com `${...}`. Regras ao mexer nesse arquivo:

- nunca ponha `POSTGRES_PASSWORD`/`JWT_SECRET` em `environment:` (sobrescreveria o `env_file` com o do backend);
- o shim repete o `entrypoint`/`cmd` originais da imagem (`docker-entrypoint.sh` + `node ...`); ao trocar a
  versão da imagem, confira com `docker image inspect`;
- o `kong` só usa nomes sem colisão (`ANON_KEY`, `SERVICE_ROLE_KEY`, `DASHBOARD_*`) e interpola normalmente;
  assim não recebe `POSTGRES_PASSWORD`/`JWT_SECRET`;
- `studio` e `meta` recebem o `.env` inteiro (inclui `SECRET_KEY_BASE`, `VAULT_ENC_KEY`, `DASHBOARD_PASSWORD`),
  já que `env_file` não filtra.
