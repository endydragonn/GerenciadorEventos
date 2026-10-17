<#
.SYNOPSIS
  Auto-teste de lib-matriz.ps1 (issue #11): linha do CSV da matriz e lag observado da rajada.
  Sem rede, sem k6, sem banco. Sai com codigo != 0 se alguma assercao falhar.

.EXAMPLE
  .\lib-matriz.selftest.ps1
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-matriz.ps1')

# pt-BR: se algum formato usar a cultura corrente, o decimal viraria virgula e quebraria o CSV.
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::GetCultureInfo('pt-BR')

$script:falhas = 0
function Confere($nome, $obtido, $esperado) {
    if ($obtido -ceq $esperado) { Write-Host "   [ok]    $nome" }
    else { Write-Host "   [FALHA] $nome`n           esperado: $esperado`n           obtido:   $obtido"; $script:falhas++ }
}
function Amostra($ts, $lag) { [pscustomobject]@{ Ts = [long]$ts; Lag = [int]$lag } }

Write-Host ''
Write-Host '==> Colunas e linha do CSV'
Confere 'cabecalho = colunas da spec, na ordem' $MatrizCabecalho `
    'braco,lag_ms,N,capacity,rep,confirmed,pending,oversell,p50,p95,p99,throughput,lag_obs_disparo,lag_obs_max,commit'
Confere 'linha com overselling (confirmed 23 > capacity 20); ponto decimal mesmo em pt-BR' `
    (Format-LinhaMatriz -Braco naive -LagMs 5000 -N 100 -Capacity 20 -Rep 3 -Confirmed 23 -Pending 0 `
        -P50 12.54 -P95 45.06 -P99 80.04 -Throughput 99.94 -LagDisparo 1200 -LagMax 2300 -Commit abc1234) `
    'naive,5000,100,20,3,23,0,3,12.5,45.1,80.0,99.9,1200,2300,abc1234'
Confere 'oversell nunca negativo (confirmed 15 < capacity 20)' `
    (Format-LinhaMatriz -Braco cas -LagMs 1000 -N 50 -Capacity 20 -Rep 1 -Confirmed 15 -Pending 5 `
        -P50 1 -P95 2 -P99 3 -Throughput 4 -LagDisparo -1 -LagMax -1 -Commit abc1234) `
    'cas,1000,50,20,1,15,5,0,1.0,2.0,3.0,4.0,-1,-1,abc1234'
Confere 'confirmed = capacity nao e overselling' `
    ((Format-LinhaMatriz -Braco cas -LagMs 1000 -N 50 -Capacity 20 -Rep 1 -Confirmed 20 -Pending 0 `
        -P50 1 -P95 2 -P99 3 -Throughput 4 -LagDisparo 10 -LagMax 20 -Commit x) -split ',')[7] '0'

Write-Host ''
Write-Host '==> Lag observado da rajada (amostras com relogio local, mesma base do k6)'
$base = @((Amostra 1000 800), (Amostra 1500 1300), (Amostra 2000 1800), (Amostra 2500 100), (Amostra 3000 600))
$r = Get-LagRajada -Amostras $base -DisparoMs 2100 -FimMs 2600
Confere 'disparo = ultima amostra ANTES do disparo' $r.Disparo 1800
Confere 'max = maior entre essa amostra e as da rajada (refresh no meio nao reduz o max)' $r.Max 1800

$r = Get-LagRajada -Amostras $base -DisparoMs 2000 -FimMs 2000
Confere 'amostra exatamente no instante do disparo conta como "antes"' $r.Disparo 1800

$r = Get-LagRajada -Amostras @((Amostra 1000 -1), (Amostra 1500 300), (Amostra 2000 -1)) -DisparoMs 2100 -FimMs 2300
Confere 'sentinela -1 (sem leitura) e ignorado: disparo' $r.Disparo 300
Confere 'sentinela -1 (sem leitura) e ignorado: max' $r.Max 300

$r = Get-LagRajada -Amostras @((Amostra 1000 -1), (Amostra 2000 -1)) -DisparoMs 2100 -FimMs 2300
Confere 'so sentinelas: disparo = -1' $r.Disparo -1
Confere 'so sentinelas: max = -1' $r.Max -1

$r = Get-LagRajada -Amostras @() -DisparoMs 2100 -FimMs 2300
Confere 'sem amostras: disparo = -1' $r.Disparo -1
Confere 'sem amostras: max = -1' $r.Max -1

$r = Get-LagRajada -Amostras @((Amostra 3000 500)) -DisparoMs 2900 -FimMs 3100
Confere 'sem amostra antes do disparo: disparo = -1' $r.Disparo -1
Confere 'sem amostra antes do disparo, mas com uma na rajada: max' $r.Max 500

Write-Host ''
Write-Host '==> Vazao da rajada (so a janela [disparo, fim]; o duracao_s do k6 inclui os logins do setup())'
Confere 'tentativas / janela: 200 em 250 ms = 800 reservas/s' (Get-VazaoRajada -Tentativas 200 -DisparoMs 1000 -FimMs 1250) 800
Confere 'janela de 2 s: 50 tentativas = 25 reservas/s' (Get-VazaoRajada -Tentativas 50 -DisparoMs 1000 -FimMs 3000) 25
Confere 'janela vazia (fim = disparo) -> 0, sem dividir por zero' (Get-VazaoRajada -Tentativas 200 -DisparoMs 1000 -FimMs 1000) 0
Confere 'sem disparo no resumo (0) -> 0' (Get-VazaoRajada -Tentativas 200 -DisparoMs 0 -FimMs 0) 0

Write-Host ''
Write-Host '==> Validade da repeticao (resumo do k6 + codigo de saida): so repeticao valida vira linha do CSV'
# resumo no formato do carga.js (lido de JSON como o rodar-matriz.ps1 faz)
function Resumo($ajustes) {
    $o = [ordered]@{ disparo_ms = 1000; fim_ms = 1250; sem_login = 0; outros = 0; aceitas = 20; lotado = 180
        confirmadas = 20; confirma_4xx_esperado = 0; confirma_5xx = 0; confirma_outros = 0 }
    foreach ($k in $ajustes.Keys) { $o[$k] = $ajustes[$k] }
    ($o | ConvertTo-Json) | ConvertFrom-Json
}
$v = Test-RepeticaoValida -Resumo (Resumo @{}) -CodigoK6 0
Confere 'resumo limpo e k6 saiu 0 -> valida' $v.Valida $true
Confere 'resumo limpo: sem motivos' @($v.Motivos).Count 0
$v = Test-RepeticaoValida -Resumo (Resumo @{}) -CodigoK6 107
Confere 'k6 com codigo != 0 -> invalida' $v.Valida $false
Confere 'k6 com codigo != 0: motivo cita o codigo' ((@($v.Motivos) -join ';') -match '107') $true
$v = Test-RepeticaoValida -Resumo (Resumo @{ disparo_ms = 0; fim_ms = 0; aceitas = 0; lotado = 0; confirmadas = 0 }) -CodigoK6 0
Confere 'sem disparo_ms (setup() abortou, nenhuma iteracao) -> invalida' $v.Valida $false
Confere 'sem disparo_ms: motivo cita disparo_ms' ((@($v.Motivos) -join ';') -match 'disparo_ms') $true
$v = Test-RepeticaoValida -Resumo (Resumo @{ sem_login = 3 }) -CodigoK6 0
Confere 'usuarios sem login -> invalida' $v.Valida $false
$v = Test-RepeticaoValida -Resumo (Resumo @{ outros = 105 }) -CodigoK6 0
Confere 'respostas inesperadas/5xx na reserva (outros > 0) -> invalida' $v.Valida $false
Confere 'outros > 0: motivo cita a contagem' ((@($v.Motivos) -join ';') -match '105') $true
$v = Test-RepeticaoValida -Resumo (Resumo @{ confirma_5xx = 2 }) -CodigoK6 0
Confere '5xx no confirma -> invalida' $v.Valida $false
$v = Test-RepeticaoValida -Resumo (Resumo @{ confirma_outros = 1 }) -CodigoK6 0
Confere 'resposta inesperada no confirma -> invalida' $v.Valida $false
$v = Test-RepeticaoValida -Resumo (Resumo @{ confirmadas = 19 }) -CodigoK6 0
Confere 'confirmadas < aceitas (PCT_CONFIRMA=100) -> invalida' $v.Valida $false
$v = Test-RepeticaoValida -Resumo (Resumo @{ outros = 4; sem_login = 1 }) -CodigoK6 2
Confere 'varios defeitos: todos os motivos acumulam (k6, sem_login, outros)' @($v.Motivos).Count 3

Write-Host ''
Write-Host '==> Tolerancia 0 do braco CAS (issue #12): confirmed > capacity OU pending + confirmed > capacity = falha'
$t = Test-ToleranciaCas -Confirmed 20 -Pending 0 -Capacity 20
Confere 'confirmed = capacity (20/0): passa' $t.Ok $true
Confere 'confirmed = capacity: sem motivos' @($t.Motivos).Count 0
$t = Test-ToleranciaCas -Confirmed 15 -Pending 5 -Capacity 20
Confere 'pending + confirmed = capacity (15+5): passa' $t.Ok $true
$t = Test-ToleranciaCas -Confirmed 0 -Pending 0 -Capacity 20
Confere 'evento vazio (0/0): passa' $t.Ok $true
$t = Test-ToleranciaCas -Confirmed 21 -Pending 0 -Capacity 20
Confere 'confirmed = capacity + 1 (21/0): falha' $t.Ok $false
Confere 'confirmed = capacity + 1: 2 motivos (primaria e secundaria)' @($t.Motivos).Count 2
Confere 'confirmed = capacity + 1: motivo da primaria (confirmed > capacity), 1o da lista' (@($t.Motivos)[0] -match '^confirmed \(21\) > capacity \(20\)$') $true
Confere 'confirmed = capacity + 1: a secundaria tambem estoura (pending + confirmed > capacity), 2o da lista' (@($t.Motivos)[1] -match '^pending \+ confirmed \(21\) > capacity \(20\)$') $true
$t = Test-ToleranciaCas -Confirmed 20 -Pending 1 -Capacity 20
Confere 'pending + confirmed = capacity + 1 (20/1): falha' $t.Ok $false
Confere 'so a secundaria estoura: 1 motivo' @($t.Motivos).Count 1
Confere 'so a secundaria estoura: o motivo e o de pending + confirmed' ((@($t.Motivos) -join ';') -match 'pending \+ confirmed \(21\) > capacity \(20\)') $true
Confere 'so a secundaria estoura: confirmed = capacity nao dispara a primaria' ((@($t.Motivos) -join ';') -match '^confirmed') $false
$t = Test-ToleranciaCas -Confirmed 19 -Pending 2 -Capacity 20
Confere 'confirmed < capacity mas pending + confirmed = capacity + 1 (19/2): falha' $t.Ok $false

Write-Host ''
Write-Host '==> CSV acumulado (cabecalho 1 vez, UTF-8 sem BOM, LF)'
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("matriz-selftest-{0}.csv" -f [guid]::NewGuid().ToString('N'))
try {
    Add-LinhaMatriz -Csv $tmp -Linha 'cas,1000,50,20,1,20,0,0,1.0,2.0,3.0,4.0,-1,-1,abc'
    Add-LinhaMatriz -Csv $tmp -Linha 'cas,1000,50,20,2,20,0,0,1.0,2.0,3.0,4.0,-1,-1,abc'
    $bytes = [System.IO.File]::ReadAllBytes($tmp)
    $texto = [System.Text.Encoding]::UTF8.GetString($bytes)
    Confere 'sem BOM' ($bytes[0] -ne 0xEF) $true
    Confere 'sem CR' ($texto.Contains("`r")) $false
    $linhas = $texto.TrimEnd("`n").Split("`n")
    Confere '3 linhas (cabecalho + 2)' $linhas.Count 3
    Confere 'cabecalho na primeira' $linhas[0] $MatrizCabecalho
    $msg = ''
    try {
        Set-Content -Path $tmp -Value 'braco,outra_coisa' -Encoding ASCII
        Add-LinhaMatriz -Csv $tmp -Linha 'x'
    } catch { $msg = $_.Exception.Message }
    Confere 'cabecalho diferente recusa o append' ($msg -match 'cabecalho') $true
} finally {
    Remove-Item $tmp -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host '=================================================='
if ($script:falhas -eq 0) { Write-Host '   RESULTADO: OK -- lib-matriz.ps1 passou em todas as assercoes.' }
else { Write-Host "   RESULTADO: $($script:falhas) ASSERCAO(OES) FALHARAM." }
Write-Host '=================================================='
exit $(if ($script:falhas -eq 0) { 0 } else { 1 })
