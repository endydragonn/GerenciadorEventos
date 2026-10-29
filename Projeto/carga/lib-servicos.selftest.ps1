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
$co = Get-ArquivosCompose -Projeto $P -Carga $C -Extras @('docker-compose.completa.yml', 'docker-compose.autoescala.yml')
Confere 'completa e autoescala (chaves disjuntas): a ordem informada e mantida' ($co -join '|') "$P\docker-compose.yml|$C|$P\docker-compose.completa.yml|$P\docker-compose.autoescala.yml"
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
Write-Host '==> Decisao da saga por bloco (R5, #204): codigo 4 = invariante que nao convergiu no limite (o 3, overselling, NAO e decidido aqui)'
$d = Get-DecisaoSaga -LimiteS 45
Confere 'tudo zero: codigo 0' $d.Codigo 0
Confere 'tudo zero: mensagem de convergencia' $d.Mensagem 'sagas convergidas (velhas 0, hold held com saga compensated 0, devolucao pendente 0, orfa inversa 0, quant divergente 0).'
$casos = @(
    @{ Nome = 'saga reserving/compensating mais velha que o limite'; Arg = @{ SagasVelhas = 2 };            Trecho = 'sagas reserving/compensating mais velhas que 45s: 2' },
    @{ Nome = 'hold held cuja saga esta compensated';                Arg = @{ HoldHeldSagaCompensada = 3 }; Trecho = 'hold held com saga compensated: 3' },
    @{ Nome = 'devolucao pendente depois do lag drenar';             Arg = @{ DevolucaoPendente = 4 };      Trecho = 'hold held com ReservationCancelled/Expired no event_store ha mais de 45s (devolucao pendente): 4' },
    @{ Nome = 'orfa inversa';                                        Arg = @{ OrfaInversa = 5 };            Trecho = 'walletevent pending/confirmed sem hold held (orfa inversa): 5' },
    @{ Nome = 'quant diferente de capacity - holds debitados';       Arg = @{ QuantDivergente = 6 };        Trecho = 'eventos limitados com quant <> capacity - holds held debitados: 6' }
)
foreach ($c in $casos) {
    $a = $c.Arg
    $d = Get-DecisaoSaga -LimiteS 45 @a
    Confere "isolada, $($c.Nome): codigo 4" $d.Codigo 4
    Confere "isolada, $($c.Nome): mensagem com a contagem" ([bool]$d.Mensagem.Contains($c.Trecho)) $true
}
$d = Get-DecisaoSaga -LimiteS 30 -SagasVelhas 1 -OrfaInversa 2
Confere 'varias: codigo 4 e todas na mensagem' ("$($d.Codigo)|" + [bool]$d.Mensagem.Contains('mais velhas que 30s: 1') + '|' + [bool]$d.Mensagem.Contains('orfa inversa): 2')) '4|True|True'
Confere 'a mensagem de falha nao diz "convergidas"' ([bool]$d.Mensagem.Contains('convergidas')) $false

Write-Host ''
Write-Host '==> Limite da orfa: ORPHAN_MS + 3 x SCAN_MS (spec, "Parada da R5")'
Confere 'defaults do servico (30000 / 5000): 45 s' (Get-OrfaLimiteS -OrphanMs 30000 -ScanMs 5000) 45
Confere 'dry-run com limiar reduzido (4000 / 1000): 7 s' (Get-OrfaLimiteS -OrphanMs 4000 -ScanMs 1000) 7
Confere 'sem leitura do container: 45 s (default)' (Get-OrfaLimiteS) 45
Confere 'valor nao numerico cai no default' (Get-OrfaLimiteS -OrphanMs 'abc' -ScanMs '') 45
Confere 'arredonda para cima ao segundo' (Get-OrfaLimiteS -OrphanMs 1500 -ScanMs 100) 2

Write-Host ''
Write-Host '==> Lag do group do stock (saida simulada do rpk group describe)'
Confere 'TOTAL-LAG do group' (Get-TotalLag -Saida @('GROUP        stock', 'STATE        Stable', 'TOTAL-LAG    17', '', 'TOPIC PARTITION LAG')) 17
Confere 'lag 0: 0' (Get-TotalLag -Saida @('TOTAL-LAG 0')) 0
Confere 'group ausente (sem TOTAL-LAG numerico): nulo, nao 0' ($null -eq (Get-TotalLag -Saida @('Error: group not found', 'TOTAL-LAG x'))) $true

Write-Host ''
Write-Host '==> docker que escreve em stderr (PS 5.1 + EAP=Stop: `2>$null` num exe lanca NativeCommandError; a espera tem que cair no nulo, nao morrer)'
# docker.cmd falso no PATH (nao e o Docker): escreve em stderr e sai com 1, como "No such container" com o project errado
$dirFake = Join-Path ([IO.Path]::GetTempPath()) ("fake-docker-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dirFake | Out-Null
[IO.File]::WriteAllText((Join-Path $dirFake 'docker.cmd'), "@echo off`r`necho Error: No such container: x 1>&2`r`nexit /b 1`r`n")
$pathAntes = $env:PATH; $env:PATH = "$dirFake;$env:PATH"
try {
    $r = 'nao-lancou'; try { $r = Wait-LagDoGroup -Group stock -TetoS 1 } catch { $r = "lancou: $($_.Exception.Message)" }
    Confere 'Wait-LagDoGroup: docker com erro em stderr = nulo (sem excecao)' ($null -eq $r) $true
    $r = 'nao-lancou'; try { $r = Wait-FilaDeAdmissao -TetoS 1 } catch { $r = "lancou: $($_.Exception.Message)" }
    Confere 'Wait-FilaDeAdmissao: docker com erro em stderr = nulo (sem excecao)' ($null -eq $r) $true
} finally { $env:PATH = $pathAntes; Remove-Item -Recurse -Force $dirFake -ErrorAction SilentlyContinue }
Confere 'o EAP do chamador nao foi alterado' $ErrorActionPreference 'Stop'
Write-Host ''
Write-Host '=================================================='
if ($script:falhas -eq 0) { Write-Host '   RESULTADO: OK -- lib-servicos.ps1 passou em todas as assercoes.' }
else { Write-Host "   RESULTADO: $($script:falhas) ASSERCAO(OES) FALHARAM." }
Write-Host '=================================================='
exit $(if ($script:falhas -eq 0) { 0 } else { 1 })
