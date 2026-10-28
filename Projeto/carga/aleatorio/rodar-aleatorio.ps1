<#
.SYNOPSIS
  Teste de CARGA ALEATORIA a partir do GERADOR: durante -Tempo, a taxa muda a cada -Passo
  segundos para um alvo sorteado entre -RpmMin e -RpmMax, misturando reservas e leituras.

.DESCRIPTION
  - Perfil sorteado com -Semente (mesma semente = mesmo perfil); salvo em results\perfil-<tag>.json.
  - Cada passo: rampa de ate 5 s ate o novo alvo e mantem ate o fim do passo.
  - -PctLeitura % das iteracoes sao leituras (evento por id, carteira do usuario, busca);
    o resto sao reservas com pares (usuario, evento) unicos.
  - Cada reserva aceita e confirmada (POST /bff/event-wallets/confirm) com -PctConfirma % de chance
    (padrao 100), sorteada de forma deterministica pela semente (nao muda o perfil de taxa).
  - Eventos e calculado pelo total esperado de iteracoes (com 5% de folga).

  Diferente do teste 2, aqui -Tempo pode passar de 110 s: o objetivo e estabilidade/saturacao.
  Como reservas pending expiram (~120 s) e devolvem a vaga, "aceitas" pode passar da capacidade
  sem ser overselling. A invariante (ativas <= capacidade) e conferida no HOST, logo apos a rodada:
    .\rpm\subir-host-rpm.ps1 -Verificar -Prefixo <Prefixo impresso aqui>

.EXAMPLE
  .\aleatorio\rodar-aleatorio.ps1 -HostIp 192.168.0.10                              # 10 min, 1k-12k RPM
  .\aleatorio\rodar-aleatorio.ps1 -HostIp 192.168.0.10 -Tempo 30m -RpmMax 20000 -Semente 42
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$HostIp,
    [int]$Porta = 8081,
    [string]$Tempo = '10m',        # 600, 600s, 10m, 1h
    [int]$RpmMin = 1000,
    [int]$RpmMax = 12000,
    [ValidateRange(10, 3600)][int]$Passo = 30,
    [ValidateRange(0, 100)][int]$PctLeitura = 30,
    [int]$Semente = 0,             # 0 = sorteia (e imprime, para repetir a rodada)
    [int]$Usuarios = 200,
    [int]$Capacidade = 0,          # 0 = Usuarios/2
    [int]$MaxVus = 500,
    [ValidateSet('cas', 'naive')][string]$Rotulo = 'cas',
    [int]$MaxConnections = 0,      # so para registrar no CSV (o gerador nao enxerga o host)
    [int]$RefreshMs = 0,   # obsoleto (#29): so rotula a coluna refresh_ms
    [ValidateRange(0, 100)][int]$PctConfirma = 100
)

$ErrorActionPreference = 'Stop'
$Results = Join-Path (Split-Path $PSScriptRoot -Parent) 'results'
New-Item -ItemType Directory -Force -Path $Results | Out-Null
$Base = "http://${HostIp}:${Porta}"
$Csv = Join-Path $Results 'resultados-aleatorio.csv'
if ($Capacidade -le 0) { $Capacidade = [math]::Max(1, [int]($Usuarios / 2)) }

function Info($m) { Write-Host "[aleatorio] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

# ---- tempo: "600", "600s", "10m", "1h"
if ($Tempo -notmatch '^(\d+)\s*([smh]?)$') { Fail "-Tempo invalido: '$Tempo' (use 600, 600s, 10m ou 1h)." }
$mult = @{ '' = 1; 's' = 1; 'm' = 60; 'h' = 3600 }[$Matches[2]]
$Duracao = [int]$Matches[1] * $mult
if ($Duracao -lt 30) { Fail '-Tempo minimo: 30 s.' }
if ($RpmMin -lt 1 -or $RpmMax -lt $RpmMin) { Fail '-RpmMin deve ser >= 1 e <= -RpmMax.' }

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { Fail 'k6 nao encontrado. Rode ..\setup-gerador.ps1 primeiro.' }
try { Invoke-WebRequest -Uri "$Base/actuator/health" -UseBasicParsing -TimeoutSec 5 | Out-Null }
catch { Fail "Backend inacessivel em $Base." }

. (Join-Path (Split-Path $PSScriptRoot -Parent) 'lib-seed.ps1')   # Invoke-Seed (seed unico: docs\carga\harness\setup.sh)

# ---- perfil sorteado: [{target (RPM), duration}] para o ramping-arrival-rate
if ($Semente -eq 0) { $Semente = Get-Random -Minimum 1 -Maximum 1000000 }
$rng = New-Object System.Random $Semente
$stages = New-Object System.Collections.Generic.List[object]
$alvos = New-Object System.Collections.Generic.List[int]
$iterEsperadas = 0.0
$anterior = 0
$restante = $Duracao
while ($restante -gt 0) {
    $dur = [math]::Min($Passo, $restante)
    $alvo = $rng.Next($RpmMin, $RpmMax + 1)
    $rampa = [math]::Min(5, $dur)
    $stages.Add([pscustomobject]@{ target = $alvo; duration = "${rampa}s" })
    if ($dur -gt $rampa) { $stages.Add([pscustomobject]@{ target = $alvo; duration = "$($dur - $rampa)s" }) }
    # iteracoes = area sob a curva (RPM/60 por segundo): trapezio da rampa + retangulo
    $iterEsperadas += (($anterior + $alvo) / 2 * $rampa + $alvo * ($dur - $rampa)) / 60
    $alvos.Add($alvo)
    $anterior = $alvo
    $restante -= $dur
}
$total = [int][math]::Ceiling($iterEsperadas)
$eventos = [int][math]::Ceiling($total * 1.05 / $Usuarios)
$rpmMedio = [int]($iterEsperadas / $Duracao * 60)

$runId = [int]((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalSeconds % 1000000
$prefixo = "Ale-$runId"
$tag = "ale-{0}" -f $runId
Info "Tempo $Duracao s em $($alvos.Count) passos de $Passo s; RPM sorteado entre $RpmMin e $RpmMax (semente $Semente)."
Info "Alvos: $($alvos -join ', ')"
Info "Esperado: ~$total iteracoes (media ~$rpmMedio RPM), $PctLeitura% leituras; $Usuarios usuarios x $eventos eventos (capacidade $Capacidade/evento)."

$pf = (Join-Path $Results "perfil-$tag.json").Replace('\', '/')
$uf = (Join-Path $Results "users-$tag.json").Replace('\', '/')
$ef = (Join-Path $Results "events-$tag.json").Replace('\', '/')
$sf = (Join-Path $Results "summary-$tag.json").Replace('\', '/')
foreach ($f in $pf, $sf) { if (Test-Path $f) { Fail "Ja existe $f (runId repetido); nao sobrescrevo. Renomeie o arquivo antigo e rode de novo." } }
ConvertTo-Json -InputObject $stages.ToArray() -Depth 3 | Set-Content -Path $pf -Encoding UTF8

# ---- seed unico (usuarios + eventos)
Info "Semeando $Usuarios usuarios + $eventos eventos (bcrypt no host: pode levar um bom tempo)..."
$seed = Invoke-Seed -BaseUrl $Base -Usuarios $Usuarios -Eventos $eventos -Capacidade $Capacidade `
    -Prefixo $prefixo -Sufixo "$runId" -UsersFile $uf -EventIdsFile $ef
Ok "Prefixo '$prefixo': $eventos eventos prontos."

# ---- k6
$env:BASE_URL = $Base; $env:STAGES_FILE = $pf; $env:USERS_FILE = $uf; $env:EVENTS_FILE = $ef
$env:SUMMARY_FILE = $sf; $env:PCT_LEITURA = "$PctLeitura"; $env:SEMENTE = "$Semente"
$env:PREFIXO = $prefixo; $env:MAX_VUS = "$MaxVus"; $env:PCT_CONFIRMA = "$PctConfirma"
Info "Disparando k6 (termina por volta de $((Get-Date).AddSeconds($Duracao).ToString('HH:mm:ss')))..."
try {
    k6 run --quiet (Join-Path $PSScriptRoot 'carga-aleatoria.js')
    if ($LASTEXITCODE -ne 0) { Warn "k6 terminou com codigo $LASTEXITCODE." }
} finally {
    # credenciais de teste: apagadas mesmo se o k6 falhar ou o script for interrompido (Ctrl+C)
    Remove-Item $uf, $ef -ErrorAction SilentlyContinue
}

if (-not (Test-Path $sf)) { Fail 'k6 nao gerou o resumo.' }
$s = Get-Content $sf -Raw | ConvertFrom-Json
$inv = [System.Globalization.CultureInfo]::InvariantCulture
if (-not (Test-Path $Csv)) {
    'data,rotulo,refresh_ms,max_connections,semente,passo_s,rpm_min,rpm_max,pct_leitura,rpm_medio,duracao_s,usuarios,eventos,capacidade,aceitas,lotado,erros_5xx,outros,leituras_ok,leituras_5xx,leituras_outros,sem_login,descartadas,vus_max,reserva_p50_ms,reserva_p95_ms,reserva_p99_ms,leitura_p50_ms,leitura_p95_ms,leitura_p99_ms,max_ms,prefixo,pct_confirma,confirmadas,confirma_4xx_esperado,confirma_5xx,confirma_outros,confirma_p50_ms,confirma_p95_ms,confirma_p99_ms' |
        Set-Content -Path $Csv -Encoding UTF8
} elseif ((Get-Content $Csv -TotalCount 1) -notmatch 'confirmadas') {
    # CSV criado antes das colunas de confirma: acrescenta os nomes ao cabecalho (linhas antigas ficam curtas).
    $linhas = Get-Content $Csv
    $linhas[0] += ',pct_confirma,confirmadas,confirma_4xx_esperado,confirma_5xx,confirma_outros,confirma_p50_ms,confirma_p95_ms,confirma_p99_ms'
    $linhas | Set-Content -Path $Csv -Encoding UTF8
}
[string]::Format($inv, '{0},{1},{2},{3},{4},{5},{6},{7},{8},{9:F0},{10:F1},{11},{12},{13},{14},{15},{16},{17},{18},{19},{20},{21},{22},{23},{24:F1},{25:F1},{26:F1},{27:F1},{28:F1},{29:F1},{30:F1},{31},{32},{33},{34},{35},{36},{37:F1},{38:F1},{39:F1}',
    (Get-Date -Format s), $Rotulo, $RefreshMs, $MaxConnections, $Semente, $Passo, $RpmMin, $RpmMax, $PctLeitura,
    $s.rpm_medio, $s.duracao_s, $s.usuarios, $s.eventos, $Capacidade, $s.aceitas, $s.lotado, $s.erros_5xx,
    $s.outros, $s.leituras_ok, $s.leituras_5xx, $s.leituras_outros, $s.sem_login, $s.descartadas, $s.vus_max,
    $s.reserva_p50_ms, $s.reserva_p95_ms, $s.reserva_p99_ms, $s.leitura_p50_ms, $s.leitura_p95_ms,
    $s.leitura_p99_ms, $s.max_ms, $prefixo, $PctConfirma, $s.confirmadas, $s.confirma_4xx_esperado,
    $s.confirma_5xx, $s.confirma_outros, $s.confirma_p50_ms, $s.confirma_p95_ms, $s.confirma_p99_ms) |
    Add-Content -Path $Csv -Encoding UTF8

$e5 = $s.erros_5xx + $s.leituras_5xx + $s.confirma_5xx
Write-Host ''
Write-Host ('  RPM medio / pico alvo  {0:N0} / {1}' -f $s.rpm_medio, $s.rpm_alvo_max)
Write-Host "  reservas: aceitas .... $($s.aceitas)   lotado: $($s.lotado)   outros: $($s.outros)"
Write-Host "  confirma: ok ......... $($s.confirmadas) (PCT_CONFIRMA=$PctConfirma)   esperado 4xx: $($s.confirma_4xx_esperado)   outros: $($s.confirma_outros)"
Write-Host ('  confirma p50/p95/p99 . {0:N1} / {1:N1} / {2:N1} ms' -f $s.confirma_p50_ms, $s.confirma_p95_ms, $s.confirma_p99_ms)
Write-Host "  leituras ok .......... $($s.leituras_ok)   outros: $($s.leituras_outros)"
$cor = 'Green'; if ($e5 -gt 0) { $cor = 'Red' }
Write-Host "  erros 5xx ............ $e5 (reservas $($s.erros_5xx), leituras $($s.leituras_5xx), confirma $($s.confirma_5xx))" -ForegroundColor $cor
Write-Host "  descartadas (k6) ..... $($s.descartadas)   VUs max: $($s.vus_max)"
Write-Host ('  reserva p50/p95/p99 .. {0:N1} / {1:N1} / {2:N1} ms' -f $s.reserva_p50_ms, $s.reserva_p95_ms, $s.reserva_p99_ms)
Write-Host ('  leitura p50/p95/p99 .. {0:N1} / {1:N1} / {2:N1} ms' -f $s.leitura_p50_ms, $s.leitura_p95_ms, $s.leitura_p99_ms)
Write-Host "  conferir no HOST ..... make verificar-rpm PREFIXO=$prefixo" -ForegroundColor Yellow
Write-Host "  repetir o perfil ..... make test-aleatorio HOST_IP=$HostIp TEMPO=$Tempo SEMENTE=$Semente RPM_MIN=$RpmMin RPM_MAX=$RpmMax PASSO=$Passo PCT_LEITURA=$PctLeitura PCT_CONFIRMA=$PctConfirma"
if ($s.descartadas -gt 0) { Warn 'k6 descartou iteracoes: o alvo nao foi atingido (gerador ou backend saturado).' }
if ($s.alem_dos_pares -gt 0) { Warn "$($s.alem_dos_pares) reservas alem dos pares unicos nao foram enviadas." }
Write-Host "Resultados em $Csv (perfil em $pf)"
