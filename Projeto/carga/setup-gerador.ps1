<#
.SYNOPSIS
  Prepara a MAQUINA GERADORA de carga (nao precisa de Docker, banco ou .env).

.DESCRIPTION
  - instala o k6 (winget) se faltar
  - plano de energia "Alto desempenho"
  - confere o psql (so a matriz do experimento, `make test-matriz`, precisa) e a porta 5433 do host
  - testa conectividade com o host-alvo (porta 8081)
  - mede a latencia base de rede (ping x100 + HTTP x30) e salva em results\baseline-<data>.txt
    (valores a descontar nas rodadas da Fase 2 - criterio de aceite da issue #3)

.EXAMPLE
  .\setup-gerador.ps1 -HostIp 192.168.0.10
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$HostIp,
    [int]$Porta = 8081,
    [int]$PgPorta = 5433
)

$ErrorActionPreference = 'Stop'
$Results = Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Force -Path $Results | Out-Null
$Base = "http://${HostIp}:${Porta}"

function Info($m) { Write-Host "[gerador] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- k6
if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
    Info 'k6 nao encontrado. Instalando via winget...'
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Fail 'winget indisponivel. Instale o k6 manualmente (https://k6.io/docs/get-started/installation/) e rode de novo.'
    }
    winget install k6 --source winget --accept-package-agreements --accept-source-agreements
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
        Fail 'k6 instalado, mas ainda fora do PATH. Feche e reabra o PowerShell e rode de novo.'
    }
}
$k6v = (k6 version) -join ' '
Ok "k6 pronto: $k6v"

# ---------------------------------------------------------------- psql (matriz do experimento)
. (Join-Path $PSScriptRoot 'lib-matriz.ps1')   # Find-Psql
$psql = Find-Psql
if ($psql) {
    Ok "psql pronto: $((& $psql --version) -join ' ')"
} else {
    Warn 'psql nao encontrado (so o `make test-matriz` precisa dele; os testes 1-3 nao).'
    Write-Host '  Instale so o cliente: winget install PostgreSQL.PostgreSQL.16 --interactive'
    Write-Host '  e, no instalador, deixe marcado apenas "Command Line Tools" (desmarque PostgreSQL Server, pgAdmin e Stack Builder).'
    Write-Host '  Depois abra um novo PowerShell (o instalador nao poe o psql no PATH; o rodar-matriz.ps1 acha em Program Files\PostgreSQL).'
}

# ---------------------------------------------------------------- energia
try {
    powercfg /setactive 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c 2>$null
    if ($LASTEXITCODE -eq 0) { Ok 'Plano de energia: Alto desempenho.' } else { Warn 'Plano Alto desempenho indisponivel; ajuste manualmente.' }
} catch { Warn 'Nao foi possivel ajustar o plano de energia.' }

# ---------------------------------------------------------------- conectividade
Info "Testando conexao com $Base ..."
$tnc = Test-NetConnection -ComputerName $HostIp -Port $Porta -WarningAction SilentlyContinue
if (-not $tnc.TcpTestSucceeded) {
    Fail "Sem conexao TCP com ${HostIp}:${Porta}. Confira: mesma rede, stack no ar (setup-host.ps1), firewall (regra 8081, rede Privada)."
}
try {
    $h = Invoke-WebRequest -Uri "$Base/actuator/health" -UseBasicParsing -TimeoutSec 5
    Ok "Backend respondeu: HTTP $($h.StatusCode)."
} catch { Fail "Porta aberta, mas /actuator/health falhou: $($_.Exception.Message)" }
$tncPg = Test-NetConnection -ComputerName $HostIp -Port $PgPorta -WarningAction SilentlyContinue
if ($tncPg.TcpTestSucceeded) {
    Ok "Postgres do host acessivel em ${HostIp}:$PgPorta (a senha fica em PGPASSWORD, so na sessao)."
} else {
    Warn "Sem conexao TCP com ${HostIp}:$PgPorta (so a matriz precisa): rode no host, como Administrador, make setup-host GERADOR_IP=<ip deste gerador> (regra de firewall do Postgres, restrita a este IP) e confira o container db."
}

# ---------------------------------------------------------------- latencia base: ping
Info 'Medindo ping (100 pacotes)...'
$ping = New-Object System.Net.NetworkInformation.Ping
$rtts = New-Object System.Collections.Generic.List[double]
$perdidos = 0
for ($i = 0; $i -lt 100; $i++) {
    try {
        $r = $ping.Send($HostIp, 1000)
        if ($r.Status -eq 'Success') { $rtts.Add([double]$r.RoundtripTime) } else { $perdidos++ }
    } catch { $perdidos++ }
    Start-Sleep -Milliseconds 100
}
if ($rtts.Count -gt 0) {
    $m = $rtts | Measure-Object -Minimum -Maximum -Average
    $pingTxt = "ping: min={0}ms media={1:N2}ms max={2}ms perdidos={3}/100" -f $m.Minimum, $m.Average, $m.Maximum, $perdidos
} else {
    $pingTxt = 'ping: sem resposta (ICMP pode estar bloqueado no host; use so a medicao HTTP)'
}
Ok $pingTxt

# ---------------------------------------------------------------- latencia base: HTTP
Info 'Medindo latencia HTTP (30 requisicoes a /actuator/health)...'
$tempos = New-Object System.Collections.Generic.List[double]
for ($i = 0; $i -lt 30; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try { Invoke-WebRequest -Uri "$Base/actuator/health" -UseBasicParsing -TimeoutSec 5 | Out-Null; $sw.Stop(); $tempos.Add($sw.Elapsed.TotalMilliseconds) } catch { }
}
$ordenado = $tempos | Sort-Object
if ($ordenado.Count -gt 0) {
    $p50 = $ordenado[[math]::Floor(($ordenado.Count - 1) * 0.50)]
    $p95 = $ordenado[[math]::Floor(($ordenado.Count - 1) * 0.95)]
    $httpTxt = "http: p50={0:N1}ms p95={1:N1}ms max={2:N1}ms (n={3})" -f $p50, $p95, $ordenado[-1], $ordenado.Count
} else { $httpTxt = 'http: sem amostras' }
Ok $httpTxt

# ---------------------------------------------------------------- registro
$arq = Join-Path $Results ("baseline-{0}.txt" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
@(
    "data: $(Get-Date -Format s)"
# Arquivo versionavel: sem IP do alvo nem nome da maquina (censurados).
    "alvo: http://<HOST_IP>:${Porta}"
    "gerador: <gerador>  ($((Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name)))"
    "rede: $((Get-NetAdapter | Where-Object Status -eq 'Up' | ForEach-Object { $_.LinkSpeed }) -join '; ')"
    "k6: $k6v"
    $pingTxt
    $httpTxt
) | Set-Content -Path $arq -Encoding UTF8
Write-Host ''
Ok "Baseline salvo em $arq"
Write-Host 'Copie esses valores para docs\carga\setup.md (secao 5) e rode a medicao 3x em horarios diferentes.'
Write-Host 'Proximo passo: .\rodar-teste.ps1 -HostIp <ip> -Vus 50,100,200'
