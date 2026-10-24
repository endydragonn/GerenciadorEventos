<#
.SYNOPSIS
  Telemetria do HOST (a maquina que roda a stack) enquanto o GERADOR roda a escada/matriz: 1 amostra/s em results\telemetria-host-*.csv.

.DESCRIPTION
  O gerador ja grava a propria telemetria sozinho (rodar-degraus.ps1 / rodar-matriz.ps1). O host e outra maquina, entao
  voce a liga aqui: rode ANTES de disparar o teste no gerador e encerre com Ctrl+C (ou use -DuracaoS) depois que ele
  terminar. Os dois CSVs tem epoch em ms (UTC) para cruzar os instantes: mantenha os relogios sincronizados nas duas
  maquinas (w32tm /resync). Colunas e limites (temperatura): ver lib-telemetria.ps1.

.EXAMPLE
  .\telemetria-host.ps1 -Rotulo escada-pooler
  .\telemetria-host.ps1 -Rotulo matriz-cas -DuracaoS 1800
#>
[CmdletBinding()]
param(
    [string]$Rotulo = 'rodada',
    [int]$DuracaoS = 0   # 0 = ate o Ctrl+C
)

$ErrorActionPreference = 'Stop'
$Results = Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Force -Path $Results | Out-Null
. (Join-Path $PSScriptRoot 'lib-telemetria.ps1')

$csv = Join-Path $Results ("telemetria-host-{0}-{1:yyyyMMdd-HHmmss}.csv" -f $Rotulo, (Get-Date))
$job = Start-Telemetria -Saida $csv
Write-Host "[telemetria] host -> $csv"
Write-Host $(if ($DuracaoS -gt 0) { "[telemetria] $DuracaoS s; Ctrl+C encerra antes." } else { '[telemetria] Ctrl+C encerra (depois que o teste no gerador terminar).' })
try {
    if ($DuracaoS -gt 0) { Start-Sleep -Seconds $DuracaoS } else { while ($true) { Start-Sleep -Seconds 1 } }
} finally {
    Stop-Telemetria $job
    Write-Host ''
    Write-Host ('[telemetria] host: ' + (Get-ResumoTelemetria $csv))
    Write-Host "[telemetria] CSV: $csv"
}
