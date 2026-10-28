<#
.SYNOPSIS
  Roda o teste de carga a partir do GERADOR: semeia usuarios + evento, dispara o k6 e registra o resultado.

.DESCRIPTION
  Para cada valor de -Vus (clientes concorrentes):
    1. cria N usuarios e 1 evento com -Capacidade vagas (POST /bff/seed, endpoint publico de dev)
    3. roda carga.js (k6): N clientes disputam as vagas ao mesmo tempo
    4. acrescenta uma linha em results\resultados.csv e mostra o resumo

  O que o gerador mede (cliente): reservas aceitas, "lotado", erros, latencia p50/p95/p99.
  A verdade do banco (overselling real) se confere NO HOST: .\setup-host.ps1 -Verificar -EventId <id>

  IMPORTANTE: -Rotulo precisa bater com o que o host esta rodando
  (setup-host.ps1 -ReadPath ...). O gerador nao tem como descobrir isso. -RefreshMs e obsoleto (#29: o refresh
  por timer saiu, o lag da projecao e o do consumer, medido), aceito so por compatibilidade.

.EXAMPLE
  .\rodar-teste.ps1 -HostIp 192.168.0.10 -Vus 50,100,200 -Capacidade 20 -Rotulo cas
  .\rodar-teste.ps1 -HostIp 192.168.0.10 -Vus 100 -Capacidade 20 -Rotulo naive
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$HostIp,
    [int]$Porta = 8081,
    [int[]]$Vus = @(50),
    [int]$Capacidade = 20,
    [ValidateSet('cas', 'naive')][string]$Rotulo = 'cas',
    [int]$RefreshMs = 0,
    [ValidateRange(0, 100)][int]$PctConfirma = 100
)

$ErrorActionPreference = 'Stop'
$Results = Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Force -Path $Results | Out-Null
$Base = "http://${HostIp}:${Porta}"
$Csv = Join-Path $Results 'resultados.csv'

function Info($m) { Write-Host "[teste] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { Fail 'k6 nao encontrado. Rode .\setup-gerador.ps1 primeiro.' }
try { Invoke-WebRequest -Uri "$Base/actuator/health" -UseBasicParsing -TimeoutSec 5 | Out-Null }
catch { Fail "Backend inacessivel em $Base. Confira o host (setup-host.ps1) e a rede." }

. (Join-Path $PSScriptRoot 'lib-seed.ps1')   # Invoke-Seed (seed unico: docs\carga\harness\setup.sh)

if (-not (Test-Path $Csv)) {
    'data,rotulo,refresh_ms,clientes,capacidade,aceitas,lotado,outros,sem_login,overselling_cliente,p50_ms,p95_ms,p99_ms,max_ms,duracao_s,reservas_por_s,event_id,pct_confirma,confirmadas,confirma_4xx_esperado,confirma_5xx,confirma_outros,confirma_p50_ms,confirma_p95_ms,confirma_p99_ms' |
        Set-Content -Path $Csv -Encoding UTF8
} elseif ((Get-Content $Csv -TotalCount 1) -notmatch 'confirmadas') {
    # CSV criado antes das colunas de confirma: acrescenta os nomes ao cabecalho (linhas antigas ficam curtas).
    $linhas = Get-Content $Csv
    $linhas[0] += ',pct_confirma,confirmadas,confirma_4xx_esperado,confirma_5xx,confirma_outros,confirma_p50_ms,confirma_p95_ms,confirma_p99_ms'
    $linhas | Set-Content -Path $Csv -Encoding UTF8
}

foreach ($n in $Vus) {
    $runId = [int]((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalSeconds % 1000000
    Write-Host ''
    Info "=== Rodada: $n clientes, capacidade $Capacidade, rotulo=$Rotulo (run $runId) ==="

    # ---- 1-3. usuarios + evento (seed unico)
    $tag = "{0}-n{1}-{2}" -f $Rotulo, $n, $runId
    $uf = (Join-Path $Results "users-$tag.json").Replace('\', '/')
    $ef = (Join-Path $Results "events-$tag.json").Replace('\', '/')
    $sf = (Join-Path $Results "summary-$tag.json").Replace('\', '/')
    if (Test-Path $sf) { Fail "Ja existe $sf (runId repetido); nao sobrescrevo. Renomeie o arquivo antigo e rode de novo." }
    Info "Semeando $n usuarios + 1 evento (bcrypt no host: pode levar alguns segundos)..."
    $seed = Invoke-Seed -BaseUrl $Base -Usuarios $n -Eventos 1 -Capacidade $Capacidade `
        -Prefixo "Carga-$runId-N$n" -Sufixo "$runId" -UsersFile $uf -EventIdsFile $ef
    $eventId = $seed.EventIds[0]
    Ok "Evento '$($seed.Prefixo)-E1' (id $eventId) criado com $Capacidade vagas."

    # ---- 5. k6
    $env:BASE_URL = $Base
    $env:EVENT_ID = "$eventId"
    $env:CAPACITY = "$Capacidade"
    $env:PCT_CONFIRMA = "$PctConfirma"
    $env:USERS_FILE = $uf
    $env:SUMMARY_FILE = $sf
    Info 'Disparando k6...'
    try {
        k6 run --quiet (Join-Path $PSScriptRoot 'carga.js')
        if ($LASTEXITCODE -ne 0) { Warn "k6 terminou com codigo $LASTEXITCODE." }
    } finally {
        # credenciais de teste: apagadas mesmo se o k6 falhar ou o script for interrompido (Ctrl+C)
        Remove-Item $uf, $ef -ErrorAction SilentlyContinue
    }

    # ---- 6. resultado
    if (-not (Test-Path $sf)) { Fail 'k6 nao gerou o resumo; veja a saida acima.' }
    $s = Get-Content $sf -Raw | ConvertFrom-Json
    $inv = [System.Globalization.CultureInfo]::InvariantCulture   # ponto decimal, mesmo em pt-BR
    [string]::Format($inv, '{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10:F1},{11:F1},{12:F1},{13:F1},{14:F2},{15:F1},{16},{17},{18},{19},{20},{21},{22:F1},{23:F1},{24:F1}',
        (Get-Date -Format s), $Rotulo, $RefreshMs, $s.clientes, $s.capacidade, $s.aceitas, $s.lotado,
        $s.outros, $s.sem_login, $s.overselling_cliente, $s.p50_ms, $s.p95_ms, $s.p99_ms, $s.max_ms,
        $s.duracao_s, $s.reservas_por_s, $eventId, $PctConfirma, $s.confirmadas, $s.confirma_4xx_esperado, $s.confirma_5xx,
        $s.confirma_outros, $s.confirma_p50_ms, $s.confirma_p95_ms, $s.confirma_p99_ms) |
        Add-Content -Path $Csv -Encoding UTF8

    Write-Host ''
    Write-Host "  clientes ............ $($s.clientes)"
    Write-Host "  capacidade .......... $($s.capacidade)"
    Write-Host "  aceitas (201) ....... $($s.aceitas)"
    Write-Host "  lotado (400) ........ $($s.lotado)"
    Write-Host "  confirmadas (200) ... $($s.confirmadas) de $($s.aceitas) aceitas (PCT_CONFIRMA=$PctConfirma)   esperado 4xx: $($s.confirma_4xx_esperado)   5xx: $($s.confirma_5xx)   outros: $($s.confirma_outros)"
    Write-Host ('  confirma p50/p95/p99 . {0:N1} / {1:N1} / {2:N1} ms' -f $s.confirma_p50_ms, $s.confirma_p95_ms, $s.confirma_p99_ms)
    Write-Host "  erros inesperados ... $($s.outros)"
    $cor = 'Green'
    if ($s.overselling_cliente -gt 0) { $cor = 'Red' }
    Write-Host "  overselling (cliente) $($s.overselling_cliente)" -ForegroundColor $cor
    Write-Host ('  latencia p50/p95/p99 . {0:N1} / {1:N1} / {2:N1} ms (descontar a base de rede)' -f $s.p50_ms, $s.p95_ms, $s.p99_ms)
    Write-Host ('  vazao ............... {0:N1} reservas/s' -f $s.reservas_por_s)
    Write-Host "  conferir no HOST .... .\setup-host.ps1 -Verificar -EventId $eventId" -ForegroundColor Yellow

    if ($s.outros -gt 0) { Warn 'Houve respostas inesperadas (nem 201 nem "lotado"): a rodada pode nao ser valida.' }
    if ($PctConfirma -eq 100 -and $s.confirmadas -lt $s.aceitas) { Warn 'Nem toda reserva aceita foi confirmada: veja confirma_5xx/outros e o -Verificar.' }
    if ($s.aceitas -lt $Capacidade -and $n -ge $Capacidade) { Warn 'Aceitas abaixo da capacidade com clientes suficientes: investigue erros/lotado.' }

    Start-Sleep -Seconds 5
}

Write-Host ''
Ok "Resultados acumulados em $Csv"
if ($PctConfirma -eq 100) {
    Write-Host 'Reservas confirmadas nao expiram; rode -Verificar quando quiser (esperado: todas confirmed).'
} else {
    Write-Host 'As nao confirmadas (pending) expiram em ~120 s e devolvem a vaga; rode -Verificar logo apos a rodada para ver o pending, ou depois para ver o expired.'
}
