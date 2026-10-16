# supabase/ — Supabase self-hosted (vendorizado)

Origem: repositório `supabase/supabase`, pasta `docker/`, commit
`bc27d2661edd09a5d0b8d76ce33da6d4071a756e` (2026-10-16).
Os arquivos vendorizados são cópia fiel do upstream; não os edite aqui.

Nesse commit o gateway padrão do upstream é o Envoy; o Kong vem do overlay
`docker-compose.kong.yml`, que é a referência de ligação para o `kong.yml` e o
`kong-entrypoint.sh` daqui.

## O que está aqui

- `volumes/db/` — `roles.sql`, `jwt.sql`, `_supabase.sql`, `pooler.sql` (init do Postgres).
- `volumes/pooler/pooler.exs` — configuração do Supavisor.
- `volumes/api/` — `kong.yml` e `kong-entrypoint.sh` (gateway Kong do Studio).
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

Ainda não existe: vem nas issues seguintes (#18 db, #19 pooler, #20 Studio).
