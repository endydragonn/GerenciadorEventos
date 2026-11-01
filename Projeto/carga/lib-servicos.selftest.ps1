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
Write-Host '==> MODO CLUSTER (#228): CARGA_K8S=<contexto>; kubectl/docker/bash simulados (funcoes do PowerShell), nenhuma chamada real'
$script:dockerChamado = 0; $script:kArgs = $null; $script:bArgs = $null; $script:bStdin = $null
function docker { $script:dockerChamado++; $script:dArgs = ($args -join ' '); $global:LASTEXITCODE = 0 }
function kubectl { $script:kArgs = @($args); $global:LASTEXITCODE = 0 }
function fakebash { $script:bArgs = @($args); $script:bStdin = @($input); $global:LASTEXITCODE = 0 }
$env:CARGA_K8S = ''
Confere 'sem CARGA_K8S: modo compose (contexto nulo)' ($null -eq (Get-ContextoK8s)) $true
Confere 'sem CARGA_K8S: o container do servico vem do docker ps (nao deploy/)' ([bool](Get-ContainerDoServico -Servico reservation)) $false
Invoke-ExecAlvo 'gerenciador-reservation' printenv RESERVATION_READPATH
Confere 'sem CARGA_K8S: Invoke-ExecAlvo chama o docker (e nunca o kubectl)' "$($script:dockerChamado)|$($null -eq $script:kArgs)" '2|True'
'SELECT 1;' | Invoke-PsqlDb -Flags '-v ON_ERROR_STOP=1 -At'
Confere 'sem CARGA_K8S: Invoke-PsqlDb e o docker exec -i <db> sh -c psql de sempre (mesmos argumentos)' $script:dArgs 'exec -i gerenciador-db sh -c psql -v ON_ERROR_STOP=1 -At -U $POSTGRES_USER -d $POSTGRES_DB'
$env:CARGA_K8S = 'kind-gerenciador'; $script:dockerChamado = 0
Confere 'com CARGA_K8S: o contexto' (Get-ContextoK8s) 'kind-gerenciador'
Confere 'argumentos do kubectl: --context primeiro, namespace gerenciador' ((Get-ArgsKubectl -Contexto 'kind-x' -Resto @('get', 'pods')) -join ' ') '--context kind-x -n gerenciador get pods'
Confere 'o servico vira deploy/<servico> (sem docker ps)' ((Get-ContainerDoServico -Servico reservation) + '|' + $script:dockerChamado) 'deploy/reservation|0'
Invoke-ExecAlvo 'deploy/reservation' printenv RESERVATION_SAGA_ORPHAN_MS
Confere 'Invoke-ExecAlvo em cluster: kubectl --context .. -n gerenciador exec <alvo> -- cmd; docker intocado' (($script:kArgs -join ' ') + '|' + $script:dockerChamado) '--context kind-gerenciador -n gerenciador exec deploy/reservation -- printenv RESERVATION_SAGA_ORPHAN_MS|0'
$env:CARGA_BASH = 'fakebash'; $env:APP_DB_PASSWORD = 'CANARIO-APP'; $env:POSTGRES_PASSWORD = 'CANARIO-PG'
'SELECT 1;' | Invoke-PsqlDb -Flags '-v ON_ERROR_STOP=1 -At'
Confere 'psql em cluster: o SQL vai por stdin' ($script:bStdin -join '|') 'SELECT 1;'
Confere 'psql em cluster: lib (k8s/lib-db-cliente.sh), contexto e flags nos argumentos do bash, via db_psql' ([bool]($script:bArgs[-1] -eq '-At' -and $script:bArgs -contains 'kind-gerenciador' -and ($script:bArgs -join ' ') -match 'lib-db-cliente\.sh' -and ($script:bArgs -join ' ') -match 'db_psql')) $true
Confere 'psql em cluster: a senha do ambiente do shell nunca vira argumento' ([bool](($script:bArgs -join ' ') -match 'CANARIO')) $false
Confere 'psql em cluster: o one-liner do bash cita a lib e o contexto com \" (o PowerShell 5.1 tira aspas duplas SEM barra de argumento de .exe)' ([bool](($script:bArgs -join ' ') -match [regex]::Escape('. \"$1\"'))) $true
Confere 'psql em cluster: nenhuma aspa dupla sem a barra nos argumentos do bash' ([bool](($script:bArgs -join ' ') -match '(?<!\\)"')) $false
Confere 'psql em cluster: decide e prepara o pod UMA vez (db_psql_preparar) e so entao db_psql' ([bool](($script:bArgs -join ' ') -match 'db_psql_preparar .* && db_psql ')) $true
Confere 'psql em cluster: nao chama o docker' $script:dockerChamado 0
$script:dockerChamado = 0
$null = Wait-LagDoGroup -Group stock -TetoS 1
Confere 'lag do group em cluster: kubectl exec sts/redpanda -- rpk group describe stock' (($script:kArgs -join ' ') + '|' + $script:dockerChamado) '--context kind-gerenciador -n gerenciador exec sts/redpanda -- rpk group describe stock|0'
$null = Wait-FilaDeAdmissao -TetoS 1
Confere 'fila de admissao em cluster: kubectl exec deploy/rabbitmq -- rabbitmqctl' (($script:kArgs -join ' ') + '|' + $script:dockerChamado) '--context kind-gerenciador -n gerenciador exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages_ready messages_unacknowledged|0'
$env:CARGA_K8S = ''; $env:CARGA_BASH = ''; $env:APP_DB_PASSWORD = ''; $env:POSTGRES_PASSWORD = ''
Remove-Item function:docker, function:kubectl, function:fakebash

# sem Git Bash: aborta com mensagem clara (nunca cai para o `bash` do PATH, que no Windows costuma ser o do WSL)
$pw = $env:ProgramW6432; $env:CARGA_BASH = ''
$semGit = Join-Path ([IO.Path]::GetTempPath()) ('sem-git-' + [guid]::NewGuid().ToString('N')); New-Item -ItemType Directory -Path $semGit | Out-Null
try {
    $env:ProgramW6432 = $semGit
    $msg = 'nao-lancou'; try { $null = Get-BashExe } catch { $msg = $_.Exception.Message }
    Confere 'sem Git Bash: Get-BashExe lanca erro que cita o Git Bash e o CARGA_BASH (nao devolve "bash")' ([bool]($msg -match 'Git Bash' -and $msg -match 'CARGA_BASH')) $true
} finally { $env:ProgramW6432 = $pw; Remove-Item -Recurse -Force $semGit -ErrorAction SilentlyContinue }

# ponta a ponta com o Git Bash de verdade e uma lib num caminho COM ESPACO (o kubectl e falso, no PATH): o one-liner tem de citar o caminho
$gitBash = Join-Path $env:ProgramW6432 'Git\bin\bash.exe'
if (Test-Path $gitBash) {
    $raiz = Join-Path ([IO.Path]::GetTempPath()) ('lib com espaco ' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force (Join-Path $raiz 'carga'), (Join-Path $raiz 'k8s'), (Join-Path $raiz 'bin') | Out-Null
    Copy-Item (Join-Path $PSScriptRoot 'lib-servicos.ps1') (Join-Path $raiz 'carga')
    Copy-Item (Join-Path $PSScriptRoot '..\k8s\lib-db-cliente.sh'), (Join-Path $PSScriptRoot '..\k8s\db-cliente.yaml') (Join-Path $raiz 'k8s')
    # kubectl falso (script bash achado pelo PATH do Git Bash): registra os argumentos e o stdin; `get sts/db` responde que o db esta no cluster
    $fake = "#!/usr/bin/env bash`n" + 'printf "%s\n" "kubectl $*" >> "$FAKE_K_LOG"' + "`n" + 'case "$*" in *"exec -i"*) cat >> "$FAKE_K_LOG.stdin" ;; *"get sts/db"*) echo statefulset.apps/db ;; esac' + "`nexit 0`n"
    [IO.File]::WriteAllText((Join-Path $raiz 'bin\kubectl'), $fake.Replace("`r`n", "`n"))
    $logk = Join-Path $raiz 'k.log'
    $pathAntes = $env:PATH; $env:CARGA_K8S = 'kind-gerenciador'; $env:FAKE_K_LOG = ($logk -replace '\\', '/')
    $env:PATH = (Join-Path $raiz 'bin') + ';' + $env:PATH
    try {
        . (Join-Path $raiz 'carga\lib-servicos.ps1')
        'SELECT 1;' | Invoke-PsqlDb -Flags '-v ON_ERROR_STOP=1 -At'
        $linhas = @(Get-Content $logk -ErrorAction SilentlyContinue)
        Confere 'Git Bash real + lib em caminho com espaco: o psql chega ao kubectl (1 get sts/db e 1 exec -i sts/db, ambos com --context)' ("$(@($linhas | Where-Object { $_ -match 'get sts/db' }).Count)|$(@($linhas | Where-Object { $_ -match '^kubectl --context kind-gerenciador -n gerenciador exec -i sts/db -- sh -c ' }).Count)") '1|1'
        Confere 'Git Bash real + lib em caminho com espaco: o SQL chega por stdin' ((Get-Content ($logk + '.stdin') -ErrorAction SilentlyContinue) -join '|') 'SELECT 1;'
    } finally { $env:PATH = $pathAntes; $env:CARGA_K8S = ''; $env:FAKE_K_LOG = ''; Remove-Item -Recurse -Force $raiz -ErrorAction SilentlyContinue }
} else { Write-Host '   [pulado] sem Git Bash neste host: o teste ponta a ponta do psql em cluster nao rodou' }

Write-Host ''
Write-Host '=================================================='
if ($script:falhas -eq 0) { Write-Host '   RESULTADO: OK -- lib-servicos.ps1 passou em todas as assercoes.' }
else { Write-Host "   RESULTADO: $($script:falhas) ASSERCAO(OES) FALHARAM." }
Write-Host '=================================================='
exit $(if ($script:falhas -eq 0) { 0 } else { 1 })
