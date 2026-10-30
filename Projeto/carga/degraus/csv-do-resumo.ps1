<#
.SYNOPSIS
  HOST: transforma o resumo que o k6 gravou no GERADOR (rodado so com `k6 run`, ver preparar-degraus.ps1) no CSV da escada.

.DESCRIPTION
  Recusa o resumo cujo run_id (o RUN_ID da linha do k6) nao bata com o do plano. Faz o que o rodar-degraus.ps1 faz depois do k6, a partir do plano que o preparar-degraus.ps1 gravou (braco, pool, arranjo, commit...): a trava do resumo
  (Get-FalhaResumo: contadores que nao fecham, k6 interrompido sem parada registrada), as linhas (omitidos pela parada nao viram linha; o parcial usa a
  duracao efetiva) e results\degraus-<id>.csv. Nao ha codigo de saida do k6 aqui: parada vem do campo `parada` do resumo.

.EXAMPLE
  .\degraus\csv-do-resumo.ps1 -Plano results\plano-degraus-123456.json -Resumo C:\temp\summary-degraus-123456.json
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Plano,
    [Parameter(Mandatory)][string]$Resumo,
    [string]$Csv = ''   # padrao: results\degraus-<id>.csv
)

$ErrorActionPreference = 'Stop'
$Results = Join-Path (Split-Path $PSScriptRoot -Parent) 'results'
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }
. (Join-Path $PSScriptRoot 'lib-degraus.ps1')

if (-not (Test-Path $Plano)) { Fail "Plano nao encontrado: $Plano" }
if (-not (Test-Path $Resumo)) { Fail "Resumo do k6 nao encontrado: $Resumo (copie o summary-degraus-<id>.json do gerador)." }
$p = Get-Content $Plano -Raw | ConvertFrom-Json
$s = Get-Content $Resumo -Raw | ConvertFrom-Json
if ($null -eq $s.degraus) { Fail 'O arquivo nao e um resumo do carga-degraus.js (sem `degraus`).' }
$falhaId = Get-FalhaRunId -Resumo $s -RunId $p.run_id
if ($falhaId) { Fail $falhaId }
if ([int]$s.duracao_degrau_s -ne [int]$p.duracao_s) { Fail "O resumo e de outra escada: duracao_degrau_s $($s.duracao_degrau_s) != $($p.duracao_s) do plano." }
if ([int]$s.usuarios -ne [int]$p.usuarios -or [int]$s.eventos -ne [int]$p.eventos) { Fail "O resumo e de outra escada: $($s.usuarios) usuarios x $($s.eventos) eventos != $($p.usuarios) x $($p.eventos) do plano." }

$falha = Get-FalhaResumo -Resumo $s
if ($falha) { Fail "$falha O CSV nao foi gerado." }
if ($null -ne $s.parada) { Write-Host "[aviso] PARADA antecipada: $($s.parada_texto) (p95 da janela $([math]::Round($s.parada.p95_ms)) ms). Os degraus completos e o parcial viram CSV; os seguintes foram omitidos." -ForegroundColor Yellow }
if (@($s.faltando).Count -gt 0) { Write-Host "[csv] Submetricas sem amostras (tratadas como 0): $(@($s.faltando) -join ', ')" -ForegroundColor Cyan }

if (-not $Csv) { $Csv = Join-Path $Results "degraus-$($p.run_id).csv" }
if (Test-Path $Csv) { Fail "Ja existe $Csv; nao sobrescrevo." }
$linhas = Get-LinhasCsvDegraus -Resumo $s -Escada $p.escada -DuracaoS ([int]$p.duracao_s) -Braco $p.braco -MaxConnections ([int]$p.max_connections) -PoolHikari ([int]$p.pool_hikari) `
    -Commit $p.commit -Mapeamento $p.mapeamento -Arranjo $p.arranjo -PctAcompanha ([double]$p.pct_acompanha) -ConsultaMs ([int]$p.consulta_ms) -ParamsAutoescala $p.params_autoescala
Write-CsvDegraus -Csv $Csv -Linhas $linhas

Show-ResumoDegraus -Linhas $linhas -Resumo $s -TetoConsultaS ([int]$p.teto_consulta_s)
Write-Host '[csv] Telemetria do gerador: nao existe neste modo; a saturacao do gerador sai do iteracoes_descartadas e do rpm_efetivo.' -ForegroundColor Cyan
Write-Host "CSV da escada: $Csv"
Write-Host "  gerar o grafico e o limiar X ... make report-degraus CSV=$Csv" -ForegroundColor Yellow
Write-Host "  conferir no HOST ................ .\rpm\subir-host-rpm.ps1 -Verificar -Prefixo $($p.escada)" -ForegroundColor Yellow
