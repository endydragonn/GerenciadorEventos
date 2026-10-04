<#
.SYNOPSIS
  Teste de taxa fixa (RPM) a partir do GERADOR: semeia poucos usuarios + varios eventos,
  dispara o k6 em constant-arrival-rate e registra o resultado.

.DESCRIPTION
  Pares (usuario, evento) unicos: total de requisicoes = Rpm * Duracao/60 <= Usuarios * Eventos.
  Eventos e calculado automaticamente (com 5% de folga). Cada evento tem -Capacidade vagas;
  com Capacidade < Usuarios parte das respostas sera "lotado" (esperado).

  Mantenha -Duracao abaixo de ~110 s: reservas pending expiram em ~120 s e devolvem a vaga,
  o que falsearia a conta de overselling.

  A verdade do banco (overselling real): no HOST,
    .\rpm\subir-host-rpm.ps1 -Verificar -Prefixo <Prefixo impresso aqui>

.EXAMPLE
  .\rpm\rodar-rpm.ps1 -HostIp 192.168.0.10                         # 10k RPM por 90 s
  .\rpm\rodar-rpm.ps1 -HostIp 192.168.0.10 -Rpm 2000 -Duracao 60   # aquecimento
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$HostIp,
    [int]$Porta = 8081,
    [int]$Rpm = 10000,
    [ValidateRange(10, 110)][int]$Duracao = 90,
    [int]$Usuarios = 200,
    [int]$Capacidade = 0,          # 0 = Usuarios/2
    [ValidateSet('cas', 'naive')][string]$Rotulo = 'cas',
    [int]$MaxConnections = 0,      # so para registrar no CSV (o gerador nao enxerga o host)
    [int]$RefreshMs = 5000
)

$ErrorActionPreference = 'Stop'
$Results = Join-Path (Split-Path $PSScriptRoot -Parent) 'results'
New-Item -ItemType Directory -Force -Path $Results | Out-Null
$Base = "http://${HostIp}:${Porta}"
$Csv = Join-Path $Results 'resultados-rpm.csv'
if ($Capacidade -le 0) { $Capacidade = [math]::Max(1, [int]($Usuarios / 2)) }

function Info($m) { Write-Host "[rpm] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { Fail 'k6 nao encontrado. Rode ..\setup-gerador.ps1 primeiro.' }
try { Invoke-WebRequest -Uri "$Base/actuator/health" -UseBasicParsing -TimeoutSec 5 | Out-Null }
catch { Fail "Backend inacessivel em $Base." }

. (Join-Path (Split-Path $PSScriptRoot -Parent) 'lib-seed.ps1')   # Invoke-Seed (seed unico: docs\carga\harness\setup.sh)

$total = [int]($Rpm * $Duracao / 60)
$eventos = [int][math]::Ceiling($total * 1.05 / $Usuarios)
$runId = [int]((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalSeconds % 1000000
$prefixo = "Rpm-$runId"
Info "Alvo: $Rpm RPM por $Duracao s = $total reservas; $Usuarios usuarios x $eventos eventos (capacidade $Capacidade/evento)."

# ---- seed unico (usuarios + eventos)
$tag = "rpm{0}-{1}" -f $Rpm, $runId
$uf = (Join-Path $Results "users-$tag.json").Replace('\', '/')
$ef = (Join-Path $Results "events-$tag.json").Replace('\', '/')
$sf = (Join-Path $Results "summary-$tag.json").Replace('\', '/')
Info "Semeando $Usuarios usuarios + $eventos eventos (bcrypt no host: pode levar um bom tempo)..."
$seed = Invoke-Seed -BaseUrl $Base -Usuarios $Usuarios -Eventos $eventos -Capacidade $Capacidade `
    -Prefixo $prefixo -Sufixo "$runId" -UsersFile $uf -EventIdsFile $ef
Ok "Prefixo '$prefixo': $eventos eventos prontos."

$espera = [math]::Ceiling($RefreshMs / 1000) + 2
Info "Aguardando $espera s (read model)..."
Start-Sleep -Seconds $espera

# ---- k6
$env:BASE_URL = $Base; $env:RPM = "$Rpm"; $env:DURACAO_S = "$Duracao"
$env:USERS_FILE = $uf; $env:EVENTS_FILE = $ef; $env:SUMMARY_FILE = $sf
Info 'Disparando k6...'
try {
    k6 run --quiet (Join-Path $PSScriptRoot 'carga-rpm.js')
    if ($LASTEXITCODE -ne 0) { Warn "k6 terminou com codigo $LASTEXITCODE." }
} finally {
    # credenciais de teste: apagadas mesmo se o k6 falhar ou o script for interrompido (Ctrl+C)
    Remove-Item $uf, $ef -ErrorAction SilentlyContinue
}

if (-not (Test-Path $sf)) { Fail 'k6 nao gerou o resumo.' }
$s = Get-Content $sf -Raw | ConvertFrom-Json
$inv = [System.Globalization.CultureInfo]::InvariantCulture
if (-not (Test-Path $Csv)) {
    'data,rotulo,refresh_ms,max_connections,rpm_alvo,rpm_real,duracao_s,usuarios,eventos,capacidade,aceitas,lotado,erros_5xx,outros,sem_login,descartadas,vus_max,p50_ms,p95_ms,p99_ms,max_ms,prefixo' |
        Set-Content -Path $Csv -Encoding UTF8
}
[string]::Format($inv, '{0},{1},{2},{3},{4},{5:F0},{6:F1},{7},{8},{9},{10},{11},{12},{13},{14},{15},{16},{17:F1},{18:F1},{19:F1},{20:F1},{21}',
    (Get-Date -Format s), $Rotulo, $RefreshMs, $MaxConnections, $s.rpm_alvo, $s.rpm_real, $s.duracao_s,
    $s.usuarios, $s.eventos, $Capacidade, $s.aceitas, $s.lotado, $s.erros_5xx, $s.outros, $s.sem_login,
    $s.descartadas, $s.vus_max, $s.p50_ms, $s.p95_ms, $s.p99_ms, $s.max_ms, $prefixo) |
    Add-Content -Path $Csv -Encoding UTF8

Write-Host ''
Write-Host ('  RPM alvo / real ..... {0} / {1:N0}' -f $s.rpm_alvo, $s.rpm_real)
Write-Host "  aceitas (201) ....... $($s.aceitas)"
Write-Host "  lotado (400) ........ $($s.lotado)"
$cor = 'Green'; if ($s.erros_5xx -gt 0) { $cor = 'Red' }
Write-Host "  erros 5xx ........... $($s.erros_5xx)" -ForegroundColor $cor
Write-Host "  outros .............. $($s.outros)"
Write-Host "  descartadas (k6) .... $($s.descartadas)   VUs max: $($s.vus_max)"
Write-Host ('  latencia p50/p95/p99 . {0:N1} / {1:N1} / {2:N1} ms' -f $s.p50_ms, $s.p95_ms, $s.p99_ms)
Write-Host "  conferir no HOST .... .\rpm\subir-host-rpm.ps1 -Verificar -Prefixo $prefixo" -ForegroundColor Yellow
if ($s.descartadas -gt 0) { Warn 'k6 descartou iteracoes: o alvo nao foi atingido (gerador ou backend saturado).' }
if ($s.alem_dos_pares -gt 0) { Warn "$($s.alem_dos_pares) iteracoes alem dos pares unicos nao foram enviadas." }
Write-Host "Resultados em $Csv"
