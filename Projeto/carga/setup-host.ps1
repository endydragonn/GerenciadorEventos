<#
.SYNOPSIS
  Prepara a MAQUINA-ALVO (host do sistema) para o teste de carga.

.DESCRIPTION
  - confere Docker e Projeto\.env (sem imprimir o conteudo)
  - plano de energia "Alto desempenho" (se existir)
  - regra de firewall para a 8081 (API), somente rede Privada - exige PowerShell como Administrador
  - com -GeradorIp <ip>: regra para a 5433 (Postgres, psql remoto da matriz do experimento), Privada e
    SEMPRE restrita a esse IP; sem -GeradorIp a 5433 nao e aberta (so aviso). Desfazer ao fim:
    Remove-NetFirewallRule -DisplayName 'TCC postgres 5433'
  - sobe a stack com o override de carga (read path cas|naive, lag do read model, log de SQL desligado)
  - espera o backend ficar saudavel e mostra os IPs para usar no gerador

  Com -Verificar -EventId N: consulta o Postgres (docker exec) e mostra o resultado real da
  disputa (reservas por status, vagas restantes e overselling). Use depois de cada rodada.

.EXAMPLE
  .\setup-host.ps1                       # caminho CAS (default)
  .\setup-host.ps1 -ReadPath naive
  .\setup-host.ps1 -ReadPath naive -GeradorIp 192.168.0.20   # para a matriz (abre a 5433 so p/ o gerador)
  .\setup-host.ps1 -Verificar -EventId 12
#>
[CmdletBinding()]
param(
    [ValidateSet('cas', 'naive')][string]$ReadPath = 'cas',
    [switch]$SemBuild,
    [switch]$Verificar,
    [int]$EventId = 0,
    [string]$GeradorIp = ''
)

$ErrorActionPreference = 'Stop'
$Projeto = Split-Path $PSScriptRoot -Parent
$Compose = Join-Path $Projeto 'docker-compose.yml'
$Override = Join-Path $PSScriptRoot 'docker-compose.carga.yml'

function Info($m) { Write-Host "[host] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

# Anexa a saida da verificacao em resultserificacoes.log (config ativa + timestamp), pra documentar a rodada.
function Registrar($alvo, $saida) {
    $dir = Join-Path $PSScriptRoot 'results'
    New-Item -ItemType Directory -Force $dir | Out-Null
    $cfg = (docker exec gerenciador-backend printenv RESERVATION_READPATH EVENTS_RELAY_ENABLED EVENTS_CONSUMER_ENABLED 2>$null) -join ' / '
    $cab = "===== {0} | {1} | config: {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $alvo, $cfg
    Add-Content -Path (Join-Path $dir 'verificacoes.log') -Value (@($cab, $saida, '') -join "`n") -Encoding utf8
}

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
    $saida = $sql | docker exec -i gerenciador-db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | Out-String
    Write-Host $saida
    $rc = $LASTEXITCODE
    Registrar "evento $EventId" $saida.TrimEnd()
    exit $rc
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
    powercfg /setactive 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c 2>$null
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
    # Postgres (5433): so a matriz do experimento usa (o gerador consulta confirmed/pending e o lag por
    # psql remoto). O Postgres tem a senha real do .env, entao a regra SO existe restrita ao IP do
    # gerador (-GeradorIp): sem ele nao se cria regra nenhuma, so se avisa. Desfazer ao fim:
    #   Remove-NetFirewallRule -DisplayName 'TCC postgres 5433'
    $regraPg = 'TCC postgres 5433'
    if ($GeradorIp) {
        $ip = $null
        if (-not [System.Net.IPAddress]::TryParse($GeradorIp, [ref]$ip)) { Fail "-GeradorIp '$GeradorIp' nao e um IP valido (use o IP do gerador, nao 'Any' nem faixa)." }
        if (-not (Get-NetFirewallRule -DisplayName $regraPg -ErrorAction SilentlyContinue)) {
            New-NetFirewallRule -DisplayName $regraPg -Direction Inbound -Protocol TCP -LocalPort 5433 `
                -Action Allow -Profile Private -RemoteAddress $GeradorIp | Out-Null
            Ok "Regra de firewall criada: $regraPg (perfil Privado, so o gerador $GeradorIp)."
        } else {
            Set-NetFirewallRule -DisplayName $regraPg -RemoteAddress $GeradorIp
            Ok "Regra de firewall $regraPg atualizada: so o gerador $GeradorIp."
        }
    } else {
        Warn 'Regra do Postgres (5433) NAO criada: so a matriz do experimento precisa dela. Para a matriz rode com -GeradorIp <ip do gerador> (make setup-host GERADOR_IP=<ip>).'
    }
} else {
    Warn 'Sem Administrador: regras de firewall NAO criadas. Rode este script como Administrador ou execute:'
    Write-Host '  New-NetFirewallRule -DisplayName "TCC backend 8081" -Direction Inbound -Protocol TCP -LocalPort 8081 -Action Allow -Profile Private'
    Write-Host '  (so para a matriz) New-NetFirewallRule -DisplayName "TCC postgres 5433" -Direction Inbound -Protocol TCP -LocalPort 5433 -Action Allow -Profile Private -RemoteAddress <ip do gerador>'
}
Get-NetConnectionProfile | ForEach-Object {
    if ($_.NetworkCategory -eq 'Public') {
        Warn "A rede '$($_.Name)' esta como Publica: a regra (perfil Privado) nao vale nela. Mude para Privada."
    }
}

# ---------------------------------------------------------------- subir a stack
$env:RESERVATION_READPATH = $ReadPath
Info "Subindo a stack (readpath=$ReadPath; relay e consumer de eventos ligados pelo compose)..."
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
Write-Host "Config ativa: readpath=$ReadPath  SQL log=desligado" -ForegroundColor Cyan
Write-Host 'Registre CPU/RAM/disco desta maquina em docs\carga\setup.md.'
Write-Host 'Depois de cada rodada: .\setup-host.ps1 -Verificar -EventId <id>'
