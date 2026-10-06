<#
.SYNOPSIS
  Auto-teste de lib-degraus.ps1 (issue #14): plano da escada e linha do CSV. Sem rede, sem k6, sem banco.
  Sai com codigo != 0 se alguma assercao falhar. (O p95 por janela de 30 s sai pronto do resumo do k6:
  carga-degraus.js; seu teste e o stub Node do k6, nao este arquivo.)

.EXAMPLE
  .\degraus\lib-degraus.selftest.ps1
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-degraus.ps1')

# pt-BR: se algum formato usar a cultura corrente, o decimal viraria virgula e quebraria o CSV.
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::GetCultureInfo('pt-BR')

$script:falhas = 0
function Confere($nome, $obtido, $esperado) {
    if ($obtido -ceq $esperado) { Write-Host "   [ok]    $nome" }
    else { Write-Host "   [FALHA] $nome`n           esperado: $esperado`n           obtido:   $obtido"; $script:falhas++ }
}
function Lanca($nome, [scriptblock]$bloco, $trecho) {
    $msg = ''
    try { & $bloco | Out-Null } catch { $msg = $_.Exception.Message }
    if ($msg -and $msg -match $trecho) { Write-Host "   [ok]    $nome" }
    else { Write-Host "   [FALHA] $nome`n           esperava excecao com /$trecho/, obtido: '$msg'"; $script:falhas++ }
}

Write-Host ''
Write-Host '==> Cabecalho do CSV'
Confere 'colunas da spec (rpm_alvo, rpm_efetivo, taxa_5xx, p50/p95/p99, throughput, descartadas) + as do criterio' $DegrausCabecalho `
    'escada,rpm_alvo,rpm_efetivo,duracao_s,requisicoes,erros_5xx,taxa_5xx,outros,p50,p95,p99,p95_max_janela_30s,throughput,iteracoes_descartadas,braco,max_connections,pool_hikari,commit'

Write-Host ''
Write-Host '==> Plano da escada: degraus, cota de pares por degrau e tamanho do seed'
$p = Get-PlanoEscada -RpmInicio 12000 -RpmPasso 4000 -RpmMax 20000 -DuracaoS 60 -Usuarios 200
Confere 'degraus 12k, 16k, 20k' ($p.Rpms -join ',') '12000,16000,20000'
Confere 'cota = ceil(rpm*D/60) + 1 (a iteracao extra do k6)' ($p.Cotas -join ',') '12001,16001,20001'
Confere 'total de pares = soma das cotas' $p.TotalPares 48003
Confere 'eventos = ceil(total * 1.05 / usuarios)' $p.Eventos 253
Confere 'a conta cobre os pares (usuarios x eventos >= total)' (200 * $p.Eventos -ge $p.TotalPares) $true

$p = Get-PlanoEscada -RpmInicio 12000 -RpmPasso 4000 -RpmMax 21000 -DuracaoS 60 -Usuarios 200
Confere 'maximo fora da grade: o ultimo degrau e o ultimo <= maximo' ($p.Rpms -join ',') '12000,16000,20000'
$p = Get-PlanoEscada -RpmInicio 12000 -RpmPasso 4000 -RpmMax 12000 -DuracaoS 60 -Usuarios 200
Confere 'inicio = maximo: um degrau so' ($p.Rpms -join ',') '12000'
$p = Get-PlanoEscada -RpmInicio 12001 -RpmPasso 1 -RpmMax 12001 -DuracaoS 90 -Usuarios 200
Confere 'rpm*D/60 fracionario: arredonda para cima (12001*90/60 = 18001,5 -> 18002) + 1' ($p.Cotas -join ',') '18003'

Lanca 'passo 0 e recusado' { Get-PlanoEscada -RpmInicio 12000 -RpmPasso 0 -RpmMax 20000 -DuracaoS 60 -Usuarios 200 } 'passo'
Lanca 'inicio > maximo e recusado' { Get-PlanoEscada -RpmInicio 20000 -RpmPasso 4000 -RpmMax 12000 -DuracaoS 60 -Usuarios 200 } 'maximo'
Lanca 'duracao < 60 s e recusada (spec: >= 60 s)' { Get-PlanoEscada -RpmInicio 12000 -RpmPasso 4000 -RpmMax 20000 -DuracaoS 30 -Usuarios 200 } 'DuracaoS'
Lanca 'duracao que nao e multipla de 30 e recusada (janelas de 30 s inteiras)' { Get-PlanoEscada -RpmInicio 12000 -RpmPasso 4000 -RpmMax 20000 -DuracaoS 75 -Usuarios 200 } 'DuracaoS'
Lanca 'mais de 40 degraus e recusado (passo digitado errado)' { Get-PlanoEscada -RpmInicio 1 -RpmPasso 1 -RpmMax 1000 -DuracaoS 60 -Usuarios 200 } 'degraus'

Write-Host ''
Write-Host '==> O p95 por janela sai do resumo do k6, nao de uma serie: as funcoes da serie nao existem mais'
Confere 'Get-P95Janelas removida' ($null -eq (Get-Command Get-P95Janelas -ErrorAction SilentlyContinue)) $true
Confere 'Get-Percentil removida' ($null -eq (Get-Command Get-Percentil -ErrorAction SilentlyContinue)) $true

Write-Host ''
Write-Host '==> Linha do CSV de um degrau (resumo do k6 -> CSV)'
function Degrau($ajustes) {
    $o = [ordered]@{ nome = 'degrau_20000'; rpm_alvo = 20000; reservas = 19000; confirmas = 9000; erros_5xx = 380
        confirma_5xx = 20; outros = 5; descartadas = 1000; p50_ms = 12.34; p95_ms = 456.789; p99_ms = 1234.5
        p95_max_janela_ms = 1100.04 }
    foreach ($k in $ajustes.Keys) { $o[$k] = $ajustes[$k] }
    ($o | ConvertTo-Json) | ConvertFrom-Json   # como o rodar-degraus.ps1 le o resumo do k6
}
$f = { param($d) Format-LinhaDegrau -Escada Deg-1 -Degrau $d -DuracaoS 60 -Braco cas -MaxConnections 300 -PoolHikari 1000 -Commit abc1234 }
Confere 'linha completa; ponto decimal mesmo em pt-BR; p95_max_janela_30s = p95_max_janela_ms do resumo; taxa = (5xx reserva + confirma) / (reservas + confirmas); throughput = (reservas - 5xx da reserva) / D' `
    (& $f (Degrau @{})) `
    'Deg-1,20000,19000,60,28000,400,0.014286,5,12.3,456.8,1234.5,1100.0,310.3,1000,cas,300,1000,abc1234'
Confere 'rpm_efetivo = reservas / D * 60 (9500 reservas em 60 s = 9500 RPM)' `
    ((& $f (Degrau @{ reservas = 9500 })) -split ',')[2] '9500'
Confere 'sem janela (-1 no resumo): vai como -1.0 (o report trata como n/d)' ((& $f (Degrau @{ p95_max_janela_ms = -1 })) -split ',')[11] '-1.0'
Confere 'zero requisicoes: taxa 0, sem dividir por zero' `
    ((& $f (Degrau @{ reservas = 0; confirmas = 0; erros_5xx = 0; confirma_5xx = 0; p95_max_janela_ms = -1 })) -split ',')[6] '0.000000'
Confere 'exatamente 1% (100 de 10000): 0.010000' `
    ((& $f (Degrau @{ reservas = 8000; confirmas = 2000; erros_5xx = 80; confirma_5xx = 20 })) -split ',')[6] '0.010000'
Lanca 'resumo sem p95_max_janela_ms (resumo de outra versao) falha alto, em vez de gravar 0' {
    $d = Degrau @{}; $d.PSObject.Properties.Remove('p95_max_janela_ms'); Format-LinhaDegrau -Escada Deg-1 -Degrau $d -DuracaoS 60 -Braco cas -MaxConnections 300 -PoolHikari 1000 -Commit abc1234 } 'p95_max_janela_ms'

Write-Host ''
Write-Host '=================================================='
if ($script:falhas -eq 0) { Write-Host '   RESULTADO: OK -- lib-degraus.ps1 passou em todas as assercoes.' }
else { Write-Host "   RESULTADO: $($script:falhas) ASSERCAO(OES) FALHARAM." }
Write-Host '=================================================='
exit $(if ($script:falhas -eq 0) { 0 } else { 1 })
