<#
.SYNOPSIS
  HOST: prepara uma escada que o GERADOR roda SO com `k6 run` (issue #178, Rodada 4 na madrugada, sem ninguem para aprovar): semeia usuarios + eventos, grava o
  plano da escada e imprime a linha EXATA do gerador. Depois, csv-do-resumo.ps1 (tambem no host) transforma o resumo que o k6 gravou no CSV.

.DESCRIPTION
  Por que existe: o rodar-degraus.ps1 faz no gerador o que o gerador da madrugada nao pode rodar (seed via Git Bash/curl, arquivo de credenciais, telemetria
  por job/CIM, health check, git, CSV). Aqui o que e de host fica no host; o gerador so executa `git checkout <commit>` e `k6 run -e ... carga-degraus.js`.
    - o k6 monta os usuarios na memoria (USUARIOS + SUFIXO, mesmo padrao de e-mail e senha do setup.sh): nenhum arquivo de credencial no gerador;
    - os ids dos eventos vao em EVENT_IDS (faixas: 101-350,360);
    - o resumo vai para Projeto/carga/results/summary-degraus-<id>.json NO GERADOR (unico por escada) e precisa chegar ao host (fora do kit);
    - a decisao de parada/parcial sai do resumo (campo `parada`), nao do codigo de saida do k6.
  O braco (sem autoescala | autoescala, +slim) e do HOST: -Arranjo/-ParamsAutoescala/-MaxConnections/-PoolHikari so rotulam o CSV; a linha do gerador e a mesma.
  A telemetria do gerador nao existe neste modo (job/CIM): a saturacao do gerador fica pelo `iteracoes_descartadas` e pelo `rpm_efetivo` do CSV.

.EXAMPLE
  .\degraus\preparar-degraus.ps1 -HostIp 192.168.0.64 -MaxConnections 300 -PoolHikari 1000 -RpmInicio 28000 -RpmPasso 14000 -RpmMax 112000 -PctAcompanha 10 -ParadaP95Ms 5000 -Arranjo sem-autoescala
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$HostIp,             # como o GERADOR enxerga o gateway (BASE_URL = http://HostIp:Porta)
    [int]$Porta = 8081,
    [string]$SeedUrl = '',                             # como o HOST enxerga o gateway para o seed (padrao http://localhost:Porta)
    [ValidateRange(1, 1000000)][int]$RpmInicio = 12000,
    [ValidateRange(1, 1000000)][int]$RpmPasso = 4000,
    [ValidateRange(1, 1000000)][int]$RpmMax = 40000,
    [int]$Duracao = 60,
    [ValidateRange(1, 100000)][int]$Usuarios = 200,
    [int]$Capacidade = 0,
    [ValidateSet('cas', 'naive')][string]$Rotulo = 'cas',
    [Parameter(Mandatory)][ValidateRange(1, 100000)][int]$MaxConnections,
    [Parameter(Mandatory)][ValidateRange(1, 100000)][int]$PoolHikari,
    [ValidateRange(0, 100)][int]$PctConfirma = 100,
    [ValidateRange(10, 20000)][int]$MaxVus = 2000,
    [ValidateSet('sequencial', 'intercalado')][string]$Mapeamento = 'sequencial',
    [ValidateRange(0, 100)][double]$PctAcompanha = 100,
    [ValidateRange(50, 60000)][int]$ConsultaMs = 1000,
    [ValidateRange(1, 600)][int]$TetoConsultaS = 35,
    [ValidateRange(0, 600000)][int]$ParadaP95Ms = 0,
    [int]$ParadaJanelaS = 60,
    [ValidateRange(0, 3600)][int]$SetupS = 0,
    [ValidatePattern('^[A-Za-z0-9._+-]+$')][string]$Arranjo = 'sem-autoescala',
    [string]$ParamsAutoescala = '',
    [switch]$ComChave
)

$ErrorActionPreference = 'Stop'
$Results = Join-Path (Split-Path $PSScriptRoot -Parent) 'results'
New-Item -ItemType Directory -Force -Path $Results | Out-Null
$Mapeamento = $Mapeamento.ToLowerInvariant()
if (-not $SeedUrl) { $SeedUrl = "http://localhost:${Porta}" }

function Info($m) { Write-Host "[preparar] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

if ($ParadaP95Ms -gt 0 -and ($ParadaJanelaS -lt 30 -or $ParadaJanelaS % 30 -ne 0 -or $Duracao % $ParadaJanelaS -ne 0)) { Fail "-ParadaJanelaS $ParadaJanelaS invalido: multiplo de 30 e divisor de -Duracao ($Duracao)." }
# alcance, nao saude: QUALQUER resposta HTTP conta (no cluster o /actuator/health da 8081 responde 401: as probes estao na porta de management 8090); so falha de conexao ou timeout aborta
try { Invoke-WebRequest -Uri "$SeedUrl/actuator/health" -UseBasicParsing -TimeoutSec 5 | Out-Null }
catch { if (-not $_.Exception.Response) { Fail "Gateway inacessivel em $SeedUrl (o seed roda no host)." } }

. (Join-Path (Split-Path $PSScriptRoot -Parent) 'lib-seed.ps1')
. (Join-Path $PSScriptRoot 'lib-degraus.ps1')
if ($Capacidade -le 0) { $Capacidade = Get-CapacidadePadrao -Usuarios $Usuarios -Mapeamento $Mapeamento }

try { $plano = Get-PlanoEscada -RpmInicio $RpmInicio -RpmPasso $RpmPasso -RpmMax $RpmMax -DuracaoS $Duracao -Usuarios $Usuarios }
catch { Fail $_.Exception.Message }
$eventos = $plano.Eventos

# o commit que o gerador faz checkout (o kit do gerador e o do host tem de ser o mesmo)
$ea = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
try { $commit = "$(& git -C $PSScriptRoot rev-parse HEAD 2>$null)".Trim(); $dirty = "$(& git -C $PSScriptRoot status --porcelain --untracked-files=no 2>$null)".Trim() }
finally { $ErrorActionPreference = $ea }
if (-not $commit) { Fail 'Nao consegui ler o commit do checkout do host (git).' }
if ($dirty) { Warn 'O checkout do host tem alteracoes nao commitadas: o gerador roda o commit, nao elas.' }

$runId = [int]((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalSeconds % 1000000
$escada = "Deg-$runId"
$tag = "degraus-$runId"
$planoArq = Join-Path $Results "plano-$tag.json"
$resumoGerador = "Projeto/carga/results/summary-$tag.json"
if (Test-Path $planoArq) { Fail "Ja existe $planoArq (runId repetido); tente de novo em 1 s." }

if ($PctAcompanha -gt 0) {
    $vusFila = [math]::Ceiling(($plano.Rpms[-1] / 60.0) * ($PctAcompanha / 100.0 * 30 + (1 - $PctAcompanha / 100.0) * 0.2))
    if ($vusFila -gt $MaxVus) { Warn "No maior degrau ($($plano.Rpms[-1]) RPM) uma espera de 30 s na fila pede ~$vusFila VUs (> MaxVus $MaxVus): baixe -PctAcompanha ou suba -MaxVus (RAM do gerador)." }
}
Info "Escada $escada ($($plano.Rpms -join ', ') RPM x $Duracao s, $Mapeamento): $Usuarios usuarios x $eventos eventos (capacidade $Capacidade/evento), $($plano.TotalPares) pares."

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("preparar-{0}" -f [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    $uf = (Join-Path $tmp 'users.json').Replace('\', '/'); $ef = (Join-Path $tmp 'events.json').Replace('\', '/')
    Info "Semeando $Usuarios usuarios + $eventos eventos em $SeedUrl (bcrypt: pode levar um bom tempo)..."
    $seed = Invoke-Seed -BaseUrl $SeedUrl -Usuarios $Usuarios -Eventos $eventos -Capacidade $Capacidade -Prefixo $escada -Sufixo "$runId" -UsersFile $uf -EventIdsFile $ef
    Ok "Prefixo '$escada': $eventos eventos prontos."
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue   # o arquivo de credenciais nunca sai do host (e o k6 as monta pelo SUFIXO)
}

$setupS = if ($ParadaP95Ms -le 0) { 0 } elseif ($SetupS -gt 0) { $SetupS } else { Get-SetupS -Usuarios $Usuarios }
$envs = [ordered]@{
    BASE_URL = "http://${HostIp}:${Porta}"; RPMS = ($plano.Rpms -join ','); DURACAO_S = "$Duracao"
    USUARIOS = "$Usuarios"; SUFIXO = "$runId"; EVENT_IDS = (Compress-Ids $seed.EventIds); SUMMARY_FILE = $resumoGerador
    PCT_CONFIRMA = "$PctConfirma"; MAX_VUS = "$MaxVus"; MAPEAMENTO = $Mapeamento
    PCT_ACOMPANHA = "$PctAcompanha"; CONSULTA_MS = "$ConsultaMs"; TETO_CONSULTA_S = "$TetoConsultaS"
    PARADA_P95_MS = "$ParadaP95Ms"; PARADA_JANELA_S = "$ParadaJanelaS"; SETUP_S = "$setupS"
    IDEMPOTENCY_KEY = $(if ($ComChave) { '1' } else { '0' }); RUN_ID = "$runId"
}
$linha = Get-LinhaK6 -Envs $envs -Script 'Projeto/carga/degraus/carga-degraus.js'

$obj = [ordered]@{
    escada = $escada; run_id = $runId; commit = $commit.Substring(0, 7); commit_completo = $commit; duracao_s = $Duracao; mapeamento = $Mapeamento
    braco = $Rotulo; max_connections = $MaxConnections; pool_hikari = $PoolHikari; arranjo = $Arranjo; pct_acompanha = $PctAcompanha
    consulta_ms = $ConsultaMs; teto_consulta_s = $TetoConsultaS; params_autoescala = $ParamsAutoescala; usuarios = $Usuarios; eventos = $eventos
    resumo_no_gerador = $resumoGerador; linha_k6 = $linha
}
[System.IO.File]::WriteAllText($planoArq, (($obj | ConvertTo-Json) + "`n"), (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host 'NO GERADOR (Windows PowerShell, na raiz do clone; so estes dois comandos):' -ForegroundColor Yellow
Write-Host "  git checkout $commit"
Write-Host "  $linha"
Write-Host ''
Write-Host "Depois, no HOST, com o resumo do gerador ($resumoGerador) copiado para esta maquina (so e aceito o resumo com run_id $runId, o RUN_ID da linha):" -ForegroundColor Yellow
Write-Host "  .\degraus\csv-do-resumo.ps1 -Plano $planoArq -Resumo <caminho do summary-$tag.json>"
Write-Host "  .\rpm\subir-host-rpm.ps1 -Verificar -Prefixo $escada          (overselling; codigo 3 no CAS = criterio de parada)"
Write-Host "Plano: $planoArq"
