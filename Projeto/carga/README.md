# Testes de carga

Duas máquinas: o **host** (roda a stack, o alvo do teste) e o **gerador** (roda o k6).
O k6 **nunca** roda no host: competiria por CPU/rede com o backend e invalidaria as latências.

| Máquina | Precisa de | Não precisa de |
|---|---|---|
| Host | Docker Desktop, `Projeto/.env` | k6 |
| Gerador | k6 (o `setup-gerador` instala via winget), PowerShell, clone do repo | Docker, banco, `.env` |

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
overselling (`aceitas <= capacidade`) e compara com o caminho `naive`.

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
make host-rpm MAX_CONN=100        # padrão do Postgres: deve estourar antes; depois repita com 300
# gerador
make test-rpm HOST_IP=<ip> RPM=2000 DURACAO=60     # aquecimento
make test-rpm HOST_IP=<ip>                         # 10k RPM por 90 s
# host
make verificar-rpm PREFIXO=Rpm-<id impresso>
```

Resultado: `results\resultados-rpm.csv`. Scripts: `rpm/carga-rpm.js`, `rpm/rodar-rpm.ps1`,
`rpm/subir-host-rpm.ps1`, `rpm/docker-compose.rpm.yml`.

Como funciona: cada requisição é um par (usuário, evento) único, então poucos usuários (login e
seed usam bcrypt, caro) geram milhares de reservas sem duplicidade.
`requisições = RPM × DURACAO / 60 <= USUARIOS × eventos` (eventos é calculado, com 5% de folga).

### Por que `MAX_CONN` importa

O backend abre uma conexão nova por requisição (`DriverManager`, sem pool). O padrão do Postgres
é 100 conexões; os 200 threads do Tomcat podem passar disso → `too many clients` → HTTP 500.
Compare 100 × 300 para documentar esse teto.

## Como ler o resultado

- **`aceitas` + `lotado`** devem somar quase tudo. `erros_5xx` ou `outros` > 0 invalida a rodada.
- **`descartadas` > 0** (teste 2): o k6 não manteve a taxa; gerador ou backend saturou.
- **Overselling** vale só no `-Verificar` (banco). O número do cliente é indicativo.
- **Latência:** desconte a base de rede medida no `setup-gerador`.
- No `naive`, o overselling varia com o ponto do ciclo do read model em que a rajada cai:
  repita a rodada e reporte média e dispersão.

## Limites e cuidados

- Mantenha `DURACAO` <= 110 s: reservas `pending` expiram em ~120 s e devolvem a vaga,
  o que falsearia a conta de overselling.
- O seed cria usuários com bcrypt: dezenas de segundos para centenas de usuários.
- O host precisa estar na tomada, no plano "Alto desempenho", sem apps pesados.
- Os scripts criam usuários/eventos de teste no banco (`carga*@teste.local`, `Carga-*`, `Rpm-*`).
  Não rode contra um banco com dados que você quer preservar.

## Arquivos

| Arquivo | Onde roda | Função |
|---|---|---|
| `setup-host.ps1` | host | sobe a stack com o override; `-Verificar -EventId N` confere o banco |
| `setup-gerador.ps1` | gerador | instala o k6, mede a latência base |
| `docker-compose.carga.yml` | host | override: read path, lag do read model, log de SQL desligado |
| `rodar-teste.ps1` / `carga.js` | gerador | teste 1 |
| `rpm/*` | ambos | teste 2 |
| `Makefile` | ambos | atalhos |
| `results/` | gerador | CSVs e baselines (gerado) |

Guia manual e tabela de registro do ambiente: `docs/carga/setup.md`.
