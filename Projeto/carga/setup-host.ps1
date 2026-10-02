<#
.SYNOPSIS
  Prepara a MAQUINA-ALVO (host do sistema) para o teste de carga.

.DESCRIPTION
  - confere Docker e Projeto\.env (sem imprimir o conteudo)
  - plano de energia "Alto desempenho" (se existir)
  - regra de firewall para a porta 8081 (somente rede Privada) - exige PowerShell como Administrador
  - sobe a stack com o override de carga (read path cas|naive, lag do read model, log de SQL desligado)
  - espera o backend ficar saudavel e mostra os IPs para usar no gerador

  Com -Verificar -EventId N: consulta o Postgres (docker exec) e mostra o resultado real da
  disputa (reservas por status, vagas restantes e overselling). Use depois de cada rodada.

.EXAMPLE
  .\setup-host.ps1                       # caminho CAS (default)
  .\setup-host.ps1 -ReadPath naive -RefreshMs 5000
  .\setup-host.ps1 -Verificar -EventId 12
#>
[CmdletBinding()]
param(
    [ValidateSet('cas', 'naive')][string]$ReadPath = 'cas',
    [int]$RefreshMs = 5000,
    [switch]$SemBuild,
    [switch]$Verificar,
    [int]$EventId = 0
)

$ErrorActionPreference = 'Stop'
$Projeto = Split-Path $PSScriptRoot -Parent
$Compose = Join-Path $Projeto 'docker-compose.yml'
$Override = Join-Path $PSScriptRoot 'docker-compose.carga.yml'

function Info($m) { Write-Host "[host] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- modo verificacao
if ($Verificar) {
    if ($EventId -le 0) { Fail 'Informe -EventId com o id do evento da rodada.' }
    $sql = @"
SELECT 'eventcap' AS origem, capacity, quant AS vagas_restantes FROM eventcap WHERE event_id = $EventId;
SELECT status, count(*) AS qtd FROM walletevent WHERE event_id = $EventId GROUP BY status ORDER BY status;
SELECT count(*) FILTER (WHERE status IN ('pending','confirmed')) AS ativas,
       (SELECT capacity FROM eventcap WHERE event_id = $EventId) AS capacity,
       count(*) FILTER (WHERE status IN ('pending','confirmed'))
         - (SELECT capacity FROM eventcap WHERE event_id = $EventId) AS overselling
FROM walletevent WHERE event_id = $EventId;
"@
    $sql | docker exec -i gerenciador-db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
    exit $LASTEXITCODE
}

# ---------------------------------------------------------------- pre-requisitos
Info 'Conferindo pre-requisitos...'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail 'Docker nao encontrado. Instale o Docker Desktop (issue #1).' }
docker info *> $null
if ($LASTEXITCODE -ne 0) { Fail 'Docker instalado, mas o daemon nao responde. Abra o Docker Desktop e tente de novo.' }
if (-not (Test-Path $Compose)) { Fail "docker-compose.yml nao encontrado em $Projeto" }
if (-not (Test-Path (Join-Path $Projeto '.env'))) { Fail 'Projeto\.env nao existe. Copie da maquina atual (nunca versione nem cole em chat).' }
Ok 'Docker e .env presentes.'

# ---------------------------------------------------------------- energia
try {
    powercfg /setactive 8c5e7fda-e8bf-4a96-9a85-a6e9fa6c1f2d 2>$null
    if ($LASTEXITCODE -eq 0) { Ok 'Plano de energia: Alto desempenho.' } else { Warn 'Plano Alto desempenho indisponivel; ajuste manualmente.' }
} catch { Warn 'Nao foi possivel ajustar o plano de energia.' }

# ---------------------------------------------------------------- rede / firewall
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($isAdmin) {
    $regra = 'TCC backend 8081'
    if (-not (Get-NetFirewallRule -DisplayName $regra -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $regra -Direction Inbound -Protocol TCP -LocalPort 8081 `
            -Action Allow -Profile Private | Out-Null
        Ok "Regra de firewall criada: $regra (somente perfil Privado)."
    } else { Ok "Regra de firewall ja existe: $regra." }
} else {
    Warn 'Sem Administrador: regra de firewall NAO criada. Rode este script como Administrador ou execute:'
    Write-Host '  New-NetFirewallRule -DisplayName "TCC backend 8081" -Direction Inbound -Protocol TCP -LocalPort 8081 -Action Allow -Profile Private'
}
Get-NetConnectionProfile | ForEach-Object {
    if ($_.NetworkCategory -eq 'Public') {
        Warn "A rede '$($_.Name)' esta como Publica: a regra (perfil Privado) nao vale nela. Mude para Privada."
    }
}

# ---------------------------------------------------------------- subir a stack
$env:RESERVATION_READPATH = $ReadPath
$env:READMODEL_AVAILABILITY_REFRESH_MS = "$RefreshMs"
Info "Subindo a stack (readpath=$ReadPath, refresh=${RefreshMs}ms)..."
$composeArgs = @('compose', '-f', $Compose, '-f', $Override, 'up', '-d')
if (-not $SemBuild) { $composeArgs += '--build' }
& docker @composeArgs
if ($LASTEXITCODE -ne 0) { Fail 'docker compose falhou.' }

# ---------------------------------------------------------------- esperar saude
Info 'Aguardando o backend (ate 180 s)...'
$ok = $false
for ($i = 0; $i -lt 90; $i++) {
    try {
        $r = Invoke-WebRequest -Uri 'http://localhost:8081/actuator/health' -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ok) { Fail 'Backend nao ficou saudavel. Veja: docker logs gerenciador-backend --tail 100' }
Ok 'Backend respondendo em /actuator/health.'

# ---------------------------------------------------------------- resumo
Write-Host ''
Write-Host '=== Use um destes IPs no gerador de carga (-HostIp) ===' -ForegroundColor Cyan
Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    ForEach-Object { Write-Host ("  {0,-16} ({1})" -f $_.IPAddress, $_.InterfaceAlias) }
Write-Host ''
Write-Host "Config ativa: readpath=$ReadPath  refresh=${RefreshMs}ms  SQL log=desligado" -ForegroundColor Cyan
Write-Host 'Registre CPU/RAM/disco desta maquina em docs\carga\setup.md.'
Write-Host 'Depois de cada rodada: .\setup-host.ps1 -Verificar -EventId <id>'
