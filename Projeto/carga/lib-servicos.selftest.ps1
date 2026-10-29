<#
.SYNOPSIS
  Auto-teste de lib-servicos.ps1 (issue #178): containers por label, lista de -f do braco e Hikari esperado. Sem Docker.
.EXAMPLE
  .\lib-servicos.selftest.ps1
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-servicos.ps1')

$script:falhas = 0
function Confere($nome, $obtido, $esperado) {
    if ($obtido -ceq $esperado) { Write-Host "   [ok]    $nome" }
    else { Write-Host "   [FALHA] $nome`n           esperado: $esperado`n           obtido:   $obtido"; $script:falhas++ }
}

Write-Host '==> Containers por label (docker simulado; confere os argumentos que o PowerShell 5.1 entregaria ao .exe)'
$script:psSaida = @('gerenciador-eventos-reservation-2', '', 'gerenciador-eventos-reservation-1'); $script:psArgs = ''
function docker { $script:psArgs = ($args -join ' '); $global:LASTEXITCODE = 0; $script:psSaida }
Confere 'todas as replicas do servico, ordenadas, sem linhas vazias' ((Get-ContainersDoServico -Servico reservation -Projeto p1) -join ',') 'gerenciador-eventos-reservation-1,gerenciador-eventos-reservation-2'
Confere 'o servico sai de um --filter por label (o --format nao tem aspas duplas: o PowerShell 5.1 as tira de argumento de .exe)' $script:psArgs `
    'ps --filter label=com.docker.compose.project=p1 --filter label=com.docker.compose.service=reservation --format {{.Names}}'
Confere 'nenhum argumento do docker ps leva aspas duplas' ([bool]($script:psArgs -match '"')) $false
$script:psSaida = @()
Confere 'servico ausente: vazio' (@(Get-ContainersDoServico -Servico identity -Projeto p1).Count) 0
Remove-Item function:docker
$env:CARGA_PROJETO = ''
Confere 'project padrao' (Get-ProjetoCompose) 'gerenciador-eventos'
$env:CARGA_PROJETO = 'gerenciador-e2e-x'
Confere 'CARGA_PROJETO muda o project' (Get-ProjetoCompose) 'gerenciador-e2e-x'
$env:CARGA_PROJETO = ''

Write-Host ''
Write-Host '==> Lista de -f do braco (ordem dos overrides)'
$P = 'E:\Proj\Projeto'; $C = "$P\carga\docker-compose.carga.yml"; $R = "$P\carga\rpm\docker-compose.rpm.yml"
$base = Get-ArquivosCompose -Projeto $P -Carga $C -Rpm $R
Confere 'braco sem autoescala: base, carga, rpm' ($base -join '|') "$P\docker-compose.yml|$C|$R"
$ae = Get-ArquivosCompose -Projeto $P -Carga $C -Rpm $R -Extras @('docker-compose.autoescala.yml')
Confere 'braco C: a autoescala vem DEPOIS da carga (Hikari 300 por replica vence o 1000)' ($ae -join '|') "$P\docker-compose.yml|$C|$R|$P\docker-compose.autoescala.yml"
$sl = Get-ArquivosCompose -Projeto $P -Carga $C -Extras @('docker-compose.autoescala.yml', 'docker-compose.slim.yml')
Confere 'slim sempre antes da autoescala, mesmo informado depois' ($sl -join '|') "$P\docker-compose.yml|$C|$P\docker-compose.slim.yml|$P\docker-compose.autoescala.yml"
Confere 'caminho absoluto no extra e mantido' ((Get-ArquivosCompose -Projeto $P -Carga $C -Extras @('D:\x\y.yml'))[-1]) 'D:\x\y.yml'
Confere '-f para o docker compose' ((ConvertTo-ArgsCompose -Arquivos @('a', 'b')) -join ' ') '-f a -f b'
Confere 'linha do scaler: relativa a Projeto/ quando esta la dentro' (Get-ArquivosDoScaler -Projeto $P -Arquivos $ae) `
    'docker-compose.yml carga/docker-compose.carga.yml carga/rpm/docker-compose.rpm.yml docker-compose.autoescala.yml'

Write-Host ''
Write-Host '==> Hikari esperado do reservation'
Confere 'sem autoescala: 1000 (override de carga)' (Get-HikariEsperado -Arquivos $base) 1000
Confere 'sem autoescala com variavel: o valor dela' (Get-HikariEsperado -Arquivos $base -CargaEnv '500') 500
Confere 'braco C: 300 por replica' (Get-HikariEsperado -Arquivos $ae) 300
Confere 'braco C com AUTOESCALA_RESERVATION_HIKARI' (Get-HikariEsperado -Arquivos $ae -AutoescalaEnv '200' -CargaEnv '1000') 200

Write-Host ''
Write-Host '==> Fila de admissao (saida simulada do rabbitmqctl)'
$rq = @('admission.requests 12 3', 'admission.timed_out 0 0')
Confere 'prontas + sem ack' (Get-MensagensDaFila -Saida $rq -Fila 'admission.requests') 15
Confere 'fila vazia: 0' (Get-MensagensDaFila -Saida @('admission.requests 0 0') -Fila 'admission.requests') 0
Confere 'fila que nao aparece: nulo (nao e "vazia")' ($null -eq (Get-MensagensDaFila -Saida @('outra 1 1') -Fila 'admission.requests')) $true
Confere 'linha de erro/titulo nao numerica e ignorada' ($null -eq (Get-MensagensDaFila -Saida @('Timeout: 60.0 seconds', 'admission.requests x y') -Fila 'admission.requests')) $true

Write-Host ''
Write-Host '=================================================='
if ($script:falhas -eq 0) { Write-Host '   RESULTADO: OK -- lib-servicos.ps1 passou em todas as assercoes.' }
else { Write-Host "   RESULTADO: $($script:falhas) ASSERCAO(OES) FALHARAM." }
Write-Host '=================================================='
exit $(if ($script:falhas -eq 0) { 0 } else { 1 })
