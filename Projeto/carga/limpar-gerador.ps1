<#
.SYNOPSIS
  Desfaz o que o kit de carga deixou na MAQUINA GERADORA.

.DESCRIPTION
  - apaga results\ (CSVs, baselines, summaries e os users-*.json/events-*.json com credenciais de teste)
  - desinstala o k6 (winget)
  - volta o plano de energia para "Equilibrado" (o setup-gerador ativou "Alto desempenho")
  Nao mexe no clone do repositorio, no Git for Windows nem em nada no host.
  Use -MantemResultados para guardar CSVs/baselines/summaries (so os users-/events-*.json sao apagados).

.EXAMPLE
  .\limpar-gerador.ps1
  .\limpar-gerador.ps1 -MantemResultados -MantemK6 -Force
#>
[CmdletBinding()]
param(
    [switch]$MantemResultados,
    [switch]$MantemK6,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Results = Join-Path $PSScriptRoot 'results'

function Info($m) { Write-Host "[limpeza] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }

if (-not $Force) {
    $o = @()
    if ($MantemResultados) { $o += 'apagar users-/events-*.json' } else { $o += 'apagar results\ inteira' }
    if (-not $MantemK6) { $o += 'desinstalar o k6' }
    $o += 'plano de energia -> Equilibrado'
    Write-Host ('Vai: ' + ($o -join '; ') + '.')
    if ((Read-Host 'Continuar? (s/N)') -notmatch '^[sSyY]') { Write-Host 'Cancelado.'; exit 0 }
}

# ---------------------------------------------------------------- results
if (Test-Path $Results) {
    if ($MantemResultados) {
        Get-ChildItem $Results -Include 'users-*.json', 'events-*.json' -Recurse | Remove-Item -Force
        Ok 'Credenciais de teste (users-/events-*.json) apagadas; resultados mantidos.'
    } else {
        Remove-Item $Results -Recurse -Force
        Ok 'results\ removida.'
    }
} else { Info 'results\ nao existe; nada a apagar.' }

# ---------------------------------------------------------------- k6
if (-not $MantemK6) {
    if (Get-Command k6 -ErrorAction SilentlyContinue) {
        winget uninstall k6 --silent
        if ($LASTEXITCODE -eq 0) { Ok 'k6 desinstalado (reabra o terminal para sair do PATH).' }
        else { Warn 'winget nao conseguiu desinstalar o k6; remova manualmente.' }
    } else { Info 'k6 nao esta instalado.' }
}

# ---------------------------------------------------------------- energia
powercfg /setactive 381b4222-f694-41f0-9685-ff5b864218cf 2>$null
if ($LASTEXITCODE -eq 0) { Ok 'Plano de energia: Equilibrado.' } else { Warn 'Nao foi possivel voltar o plano de energia; ajuste manualmente.' }

Ok 'Gerador limpo.'
