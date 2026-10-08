# Testes de carga

Duas máquinas: o **host** (roda a stack, o alvo do teste) e o **gerador** (roda o k6).
O k6 **nunca** roda no host: competiria por CPU/rede com o backend e invalidaria as latências.

| Máquina | Precisa de | Não precisa de |
|---|---|---|
| Host | Docker Desktop, `Projeto/.env` | k6 |
| Gerador | k6 (o `setup-gerador` instala via winget), PowerShell, Git for Windows (Git Bash, usado pelo seed), clone **completo** do repo | Docker, banco, `.env` |

> Nunca copie o `Projeto/.env` para o gerador (credenciais reais).

Atalhos via `make` (GNU make; `winget install ezwinports.make`). Rode em `Projeto/carga`:
`make help`. Sem make, chame os `.ps1` direto (exemplos abaixo).

## Preparação

**No host** (PowerShell como Administrador, para criar a regra de firewall da 8081; rede como Privada):

```powershell
make setup-host            # ou: .\setup-host.ps1 [-ReadPath cas|naive] [-RefreshMs 5000]
```

Anote o IP impresso (`HOST_IP`).

**No gerador:**

```powershell
make setup-gerador HOST_IP=<ip>    # ou: .\setup-gerador.ps1 -HostIp <ip>
```

Instala o k6, testa a conexão e salva a latência base de rede em `results\baseline-*.txt`
(o valor a descontar das medições). Registre também em `docs/carga/setup.md`, seção 5.

## Teste 1 — disputa de vagas (corretude)

N clientes disputam as vagas de **um** evento, todos de uma vez. Verifica se o CAS impede
overselling (`aceitas <= capacidade`) e compara com o caminho `naive`. Toda reserva aceita (201) é
finalizada na mesma iteração com `POST /bff/event-wallets/confirm`: ao fim, as vagas do evento
ficam `confirmed` (conferir no `verificar-disputa`).

```powershell
# gerador
make test-disputa HOST_IP=<ip> VUS=50,100,200 CAPACIDADE=20
# host
make verificar-disputa EVENT_ID=<id impresso>
```

Resultado: `results\resultados.csv`. Para o braço `naive`, suba o host com
`make setup-host READPATH=naive REFRESH_MS=5000` e use o mesmo `READPATH`/`REFRESH_MS` no gerador
(o gerador não descobre isso sozinho).

Scripts: `carga.js`, `rodar-teste.ps1`.

## Teste 2 — taxa fixa (RPM / saturação)

`RPM` reservas por minuto, constantes, espalhadas em vários eventos. Procura o ponto em que o
sistema satura (erros 5xx, iterações descartadas, p95/p99).

```powershell
# host
make host-rpm MAX_CONN=100        # padrão do Postgres: o teto é o `max_connections`; depois repita com 300
# gerador
make test-rpm HOST_IP=<ip> RPM=2000 DURACAO=60     # aquecimento
make test-rpm HOST_IP=<ip>                         # 10k RPM por 90 s
# host
make verificar-rpm PREFIXO=Rpm-<id impresso>
```

Resultado: `results\resultados-rpm.csv`. Scripts: `rpm/carga-rpm.js`, `rpm/rodar-rpm.ps1`,
`rpm/subir-host-rpm.ps1`, `rpm/docker-compose.rpm.yml`.

Cada reserva aceita é confirmada (`POST /bff/event-wallets/confirm`) com `PCT_CONFIRMA` % de
chance (padrão 100, sorteio determinístico por iteração); as não confirmadas ficam `pending` e
expiram. Ex.: `make test-rpm HOST_IP=<ip> PCT_CONFIRMA=50`. Atalho: `make test-rpm-real` (ver "Versão realista").

Como funciona: cada requisição é um par (usuário, evento) único, então poucos usuários (login e
seed usam bcrypt, caro) geram milhares de reservas sem duplicidade.
`requisições = RPM × DURACAO / 60 <= USUARIOS × eventos` (eventos é calculado, com 5% de folga).

### Por que `MAX_CONN` importa

O backend usa pool HikariCP (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`, default 1000; o pool
cresce só sob demanda a partir de `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE`, default 5). Com
`MAX_CONN=100` (padrão do Postgres) o pool de 1000 pode pedir mais conexões do que o Postgres
aceita → `too many clients` → HTTP 500; para esse caso reduza o pool por env (ex.: 50). Com
`MAX_CONN` >= pool não há esse erro. Compare 100 × 300 para documentar o teto.

## Teste 3 — carga aleatória (estabilidade por tempo)

Durante `TEMPO`, a taxa muda a cada `PASSO` segundos para um alvo sorteado entre `RPM_MIN` e
`RPM_MAX` (rampa de 5 s entre alvos). `PCT_LEITURA` % das iterações são leituras sorteadas
(evento por id, carteira do usuário, busca); o resto são reservas com pares (usuário, evento) únicos,
espalhados entre os eventos. A mesma `SEMENTE` repete o mesmo perfil (`SEMENTE=0` sorteia e imprime).
Cada reserva aceita é confirmada com `PCT_CONFIRMA` % de chance (padrão 100), sorteada de forma
determinística por (`SEMENTE`, par) sem tocar no `Math.random` do perfil: a taxa e a ordem dos
sorteios não mudam com o `PCT_CONFIRMA`.

```powershell
# gerador
make test-aleatorio HOST_IP=<ip>                                   # 10 min, 1k-12k RPM, 30% leituras
make test-aleatorio HOST_IP=<ip> TEMPO=30m RPM_MAX=20000 SEMENTE=42
# host, logo após a rodada
make verificar-rpm PREFIXO=Ale-<id impresso>
```

`TEMPO` aceita `600`, `600s`, `10m`, `1h` e **pode** passar de 110 s: aqui o foco é estabilidade e
saturação ao longo do tempo. Como reservas `pending` expiram e devolvem a vaga, `aceitas` pode passar
da capacidade sem ser overselling; a invariante (ativas <= capacidade) vale no `-Verificar`.

Leitura por endpoint: o `summary-<tag>.json` e a última linha do stdout trazem p50/p95/p99 de
`leitura_evento_*`, `leitura_carteira_*` e `leitura_busca_*` (o `leitura_*` agregado continua). Se
`leitura_busca_p95_ms` ficar muito acima de evento/carteira, confirma a suspeita de varredura em
memória em `/bff/events/search`. Não entram no CSV.

Resultado: `results\resultados-aleatorio.csv` e o perfil sorteado em `results\perfil-ale-<id>.json`.
Scripts: `aleatorio/carga-aleatoria.js`, `aleatorio/rodar-aleatorio.ps1`.

## Versão realista (confirmação aleatória)

Nos testes 1–3 padrão toda reserva aceita é confirmada (`PCT_CONFIRMA=100`). Na versão realista cada
aceita é confirmada ou não **ao acaso** (sorteio determinístico por iteração/semente, como já era nos testes 2 e 3);
as não confirmadas ficam `pending` e expiram sozinhas (~120 s). O teste 1 ganhou `PCT_CONFIRMA`
(padrão 100, comportamento anterior). Os scripts são os mesmos; só muda o percentual.

```powershell
# gerador (PCT_CONFIRMA_REAL=60 por padrão; sobrescreva para outro percentual)
make test-disputa-real    HOST_IP=<ip>
make test-rpm-real        HOST_IP=<ip>
make test-aleatorio-real  HOST_IP=<ip> PCT_CONFIRMA_REAL=40
```

Como ler: `confirmadas < aceitas` é esperado (≈ `aceitas × PCT_CONFIRMA / 100`); o `-Verificar` mostra
`confirmed`, `pending` e `expired` misturados e a invariante continua `(pending + confirmed) <= capacidade`.
As vagas das não confirmadas só voltam após a expiração: rode o `-Verificar` logo após a rodada para ver
`pending`, ou depois de ~120 s para ver `expired`. No teste 3 (dezenas de minutos) as vagas liberadas são
**revendidas**, então `aceitas` pode passar da capacidade sem ser overselling. O rótulo da rodada não muda
(`-Rotulo` precisa bater com o host); distinga as rodadas pela coluna `pct_confirma` do CSV.

## Reserva e confirmação (métricas)

As duas etapas têm métricas k6 separadas (tags `reserva` e `confirma`):

| Etapa | Contadores | Latência (Trend) |
|---|---|---|
| reserva (`POST /bff/event-wallets`) | `reservas_aceitas`, `reservas_lotado`, `reservas_5xx`, `reservas_outros` | `reserva_duration` |
| confirma (`POST /bff/event-wallets/confirm`) | `confirma_aceitas` (200), `confirma_4xx_esperado`, `confirma_5xx`, `confirma_outros` | `confirma_duration` |

`confirma_4xx_esperado` = HTTP 400 "já confirmada" / "nenhuma reserva pendente" (a reserva expirou ou
já estava confirmada): não é erro. Qualquer outra resposta cai em `confirma_outros` (4xx inesperado) ou
`confirma_5xx`. No teste 1 não há contador `reservas_5xx` (5xx da reserva continua em `outros`).

No summary JSON e nos CSVs `resultados*.csv` as colunas novas ficam no **fim**: `confirmadas`,
`confirma_4xx_esperado`, `confirma_5xx`, `confirma_outros`, `confirma_p50_ms`, `confirma_p95_ms`,
`confirma_p99_ms` (e `pct_confirma` antes delas nos testes 2 e 3). Um CSV antigo ganha os nomes no
cabeçalho na próxima rodada; as linhas antigas ficam curtas. `rpm_real`/`rpm_medio`/`reservas_por_s`
seguem contando iterações (reservas), não requisições de confirma; como a iteração agora dura reserva
+ confirma, cada VU fica ocupado mais tempo (atenção a `descartadas`/`vus_max`).

## Seed único

O seed (host + usuários + eventos via `POST /bff/seed`) mora só em `docs/carga/harness/setup.sh`,
o mesmo do harness da issue #11. Os testes deste kit o chamam por `lib-seed.ps1` com
`EVENTS`, `EVENT_NAME_PREFIX`, `SEM_TOKENS=1`, `USERS_FILE` e `EVENT_IDS_FILE` (ver o cabeçalho
do script); o k6 faz o login dos usuários no `setup()`. Usuários: `carga-att<i>-<sufixo>@example.com`;
eventos: `Carga-<run>-N<n>-E1` (teste 1) e `Rpm-<run>-E<k>` (teste 2).

## Como ler o resultado

- **`aceitas` + `lotado`** devem somar quase tudo. `erros_5xx` ou `outros` > 0 invalida a rodada.
- **`confirmadas`** deve ser `aceitas × PCT_CONFIRMA / 100`; `confirma_5xx` ou `confirma_outros` > 0
  invalida a rodada. No `-Verificar`, o status `confirmed` aparece na contagem por status e o
  overselling é `(pending + confirmed) - capacidade` (deve ser <= 0).
- **`descartadas` > 0** (teste 2): o k6 não manteve a taxa; gerador ou backend saturou.
- **Overselling** vale só no `-Verificar` (banco). O número do cliente é indicativo.
- Cada `-Verificar` (`verificar-disputa` / `verificar-rpm`) anexa a saída em `Projeto/carga/results/verificacoes.log`, com data/hora, alvo (evento ou prefixo) e config ativa (read path / refresh). Rode logo após a rodada e cite esse arquivo na documentação.
- **Latência:** desconte a base de rede medida no `setup-gerador`.
- No `naive`, o overselling varia com o ponto do ciclo do read model em que a rajada cai:
  repita a rodada e reporte média e dispersão.

## Limites e cuidados

- Mantenha `DURACAO` <= 110 s: reservas `pending` expiram em ~120 s e devolvem a vaga,
  o que falsearia a conta de overselling. Reservas confirmadas não expiram (`expires_at` zera);
  com `PCT_CONFIRMA < 100` as demais continuam sujeitas a essa expiração.
- O seed cria usuários com bcrypt: dezenas de segundos para centenas de usuários.
- O host precisa estar na tomada, no plano "Alto desempenho", sem apps pesados.
- Os scripts criam usuários/eventos de teste no banco (`carga-*@example.com`, `Carga-*`, `Rpm-*`).
  Não rode contra um banco com dados que você quer preservar.

## Arquivos

| Arquivo | Onde roda | Função |
|---|---|---|
| `setup-host.ps1` | host | sobe a stack com o override; `-Verificar -EventId N` confere o banco |
| `setup-gerador.ps1` | gerador | instala o k6, mede a latência base |
| `docker-compose.carga.yml` | host | override: read path, lag do read model, log de SQL desligado |
| `rodar-teste.ps1` / `carga.js` | gerador | teste 1 (confirma `PCT_CONFIRMA` % das aceitas, padrão 100) |
| `lib-seed.ps1` | gerador | `Invoke-Seed`: chama o seed único `docs/carga/harness/setup.sh` via Git Bash |
| `rpm/*` | ambos | teste 2 |
| `aleatorio/*` | gerador | teste 3 |
| `Makefile` | ambos | atalhos |
| `results/` | gerador | CSVs e baselines (gerado) |

Guia manual e tabela de registro do ambiente: `docs/carga/setup.md`.
