# Containers dos servicos por LABEL do compose e a lista de -f do braco (issue #178). Uso: . .\lib-servicos.ps1
#
# Com a autoescala (docker-compose.autoescala.yml) as replicas de backend, reservation, identity, catalog, admission e notification nao tem
# container_name: chamam-se <project>-<servico>-<n>. O kit (docker exec) nao pode mais presumir gerenciador-<servico>: acha por label
# (com.docker.compose.project + com.docker.compose.service). db, redpanda, redis e rabbitmq seguem com o nome fixo (nao escalam).
# O project padrao e gerenciador-eventos (name: do compose); CARGA_PROJETO muda (dry-run em project isolado).

# Container do Postgres e URL da entrada do gateway no HOST: nomes fixos da stack real; CARGA_DB / CARGA_HOST_URL mudam (dry-run em project isolado,
# onde nada e publicado no host e o db nao tem container_name)
function Get-ContainerDb { if ($env:CARGA_DB) { return $env:CARGA_DB }; 'gerenciador-db' }
function Get-UrlSaudeHost { if ($env:CARGA_HOST_URL) { return $env:CARGA_HOST_URL.TrimEnd('/') }; 'http://localhost:8081' }

function Get-ProjetoCompose {
    if ($env:CARGA_PROJETO) { return $env:CARGA_PROJETO }
    'gerenciador-eventos'
}

# Nomes dos containers EM EXECUCAO do servico no project (todas as replicas, stand-by inclusive), ordenados. O servico sai do --filter, NAO de um template
# com aspas (`{{.Label "..."}}`): o Windows PowerShell 5.1 tira as aspas duplas do argumento de um .exe e o docker falha com `function "com" not defined`
# (achado no dry-run da #178). Mantenha o --format sem aspas duplas.
function Get-ContainersDoServico {
    param([Parameter(Mandatory)][string]$Servico, [string]$Projeto = (Get-ProjetoCompose))
    $linhas = & docker ps --filter "label=com.docker.compose.project=$Projeto" --filter "label=com.docker.compose.service=$Servico" --format '{{.Names}}'
    if ($LASTEXITCODE -ne 0) { return @() }
    @($linhas | ForEach-Object { "$_".Trim() } | Where-Object { $_ } | Sort-Object)
}

# Primeiro container do servico ($null se nao ha nenhum no ar)
function Get-ContainerDoServico {
    param([Parameter(Mandatory)][string]$Servico, [string]$Projeto = (Get-ProjetoCompose))
    $c = @(Get-ContainersDoServico -Servico $Servico -Projeto $Projeto)
    if ($c.Count -eq 0) { return $null }
    $c[0]
}

# Pura: lista de -f do braco. Ordem: base, carga, [rpm], extras na ordem dada (completa e autoescala mexem em chaves disjuntas: a ordem entre elas nao importa). Os extras
# vem DEPOIS do override de carga de proposito: o carga poe o Hikari do reservation em 1000 e a autoescala em 300 por replica (3 x 1000 estoura o
# Supavisor, 1500); com a autoescala por ultimo vale 300, e sem ela (braco base) vale 1000. Caminhos relativos a Projeto/ viram absolutos.
function Get-ArquivosCompose {
    param([Parameter(Mandatory)][string]$Projeto, [Parameter(Mandatory)][string]$Carga, [string]$Rpm = '', [string[]]$Extras = @())
    $l = @((Join-Path $Projeto 'docker-compose.yml'), $Carga)
    if ($Rpm) { $l += $Rpm }
    foreach ($e in $Extras) {
        if (-not $e) { continue }
        if ([System.IO.Path]::IsPathRooted($e)) { $l += $e } else { $l += (Join-Path $Projeto $e) }
    }
    , $l
}

# Pura: os -f do `docker compose` (-f a -f b ...)
function ConvertTo-ArgsCompose {
    param([Parameter(Mandatory)][string[]]$Arquivos)
    $a = @()
    foreach ($f in $Arquivos) { $a += '-f'; $a += $f }
    , $a
}

# Pura: valor esperado do Hikari POR REPLICA do reservation: 300 com a autoescala (AUTOESCALA_RESERVATION_HIKARI), senao o do override de
# carga (SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE, padrao 1000). Nao e trava: so avisa quando o container sobe com outro.
function Get-HikariEsperado {
    param([string[]]$Arquivos, [string]$AutoescalaEnv = '', [string]$CargaEnv = '')
    if (@($Arquivos | Where-Object { $_ -match 'docker-compose\.autoescala\.yml$' }).Count -gt 0) { return $(if ($AutoescalaEnv) { [int]$AutoescalaEnv } else { 300 }) }
    if ($CargaEnv) { return [int]$CargaEnv }
    1000
}

# Pos-up: imprime e confere o Hikari do reservation em CADA replica contra o esperado do braco (a soma no Supavisor depende disso)
function Test-HikariDoReservation {
    param([string[]]$Arquivos, [string]$Projeto = (Get-ProjetoCompose))
    $esperado = Get-HikariEsperado -Arquivos $Arquivos -AutoescalaEnv $env:AUTOESCALA_RESERVATION_HIKARI -CargaEnv $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
    foreach ($c in (Get-ContainersDoServico -Servico reservation -Projeto $Projeto)) {
        $v = (& docker exec $c printenv SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE 2>$null)
        if ("$v" -eq "$esperado") { Write-Host "[ ok ] Hikari do reservation em $c = $v (esperado $esperado)" -ForegroundColor Green }
        else { Write-Host "[aviso] Hikari do reservation em $c = '$v' (esperado $esperado para este braco): confira a ordem dos -f e as variaveis (3 x 1000 estoura o Supavisor)." -ForegroundColor Yellow }
    }
}

# Pura: a linha para o operador copiar para o shell do scaler (mesmos -f do up, relativos a Projeto/ quando estao la dentro)
function Get-ArquivosDoScaler {
    param([Parameter(Mandatory)][string]$Projeto, [Parameter(Mandatory)][string[]]$Arquivos)
    $base = ($Projeto.TrimEnd('\', '/') -replace '\\', '/') + '/'
    ($Arquivos | ForEach-Object {
        $f = $_ -replace '\\', '/'
        if ($f.StartsWith($base, [System.StringComparison]::OrdinalIgnoreCase)) { $f.Substring($base.Length) } else { $f }
    }) -join ' '
}

# Pura: mensagens (prontas + sem ack) de uma fila na saida de `rabbitmqctl -q list_queues name messages_ready messages_unacknowledged`; $null se a fila nao aparece
function Get-MensagensDaFila {
    param([string[]]$Saida, [Parameter(Mandatory)][string]$Fila)
    foreach ($l in $Saida) {
        $p = "$l".Trim() -split '\s+'
        if ($p.Count -ge 3 -and $p[0] -eq $Fila -and $p[1] -match '^\d+$' -and $p[2] -match '^\d+$') { return [int]$p[1] + [int]$p[2] }
    }
    $null
}

# Espera a fila admission.requests esvaziar (mensagens que ainda viram reserva depois do fim do bloco falseariam a contagem de overselling).
# Devolve o que restou ($null = nao deu para ler a fila: sem rabbitmq/admission; 0 = drenada). O TTL da fila e 30 s: o teto padrao cobre uma rodada.
function Wait-FilaDeAdmissao {
    param([int]$TetoS = 90, [string]$Container = $(if ($env:CARGA_RABBITMQ) { $env:CARGA_RABBITMQ } else { 'gerenciador-rabbitmq' }))
    # PS 5.1 + EAP=Stop (topo do subir-host-rpm.ps1): `2>$null` num exe que escreve em stderr LANCA NativeCommandError; aqui o erro tem que virar $null (sem leitura)
    $ErrorActionPreference = 'Continue'
    $fim = (Get-Date).AddSeconds($TetoS)
    $n = $null
    do {
        $saida = & docker exec $Container rabbitmqctl -q list_queues name messages_ready messages_unacknowledged 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        $n = Get-MensagensDaFila -Saida @($saida) -Fila 'admission.requests'
        if ($null -eq $n -or $n -eq 0) { return $n }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $fim)
    $n
}

# ---- Rodada 5 (#204): saga stock + wallet por bloco (subir-host-rpm.ps1 -Verificar) ----

# Pura: limite (s) para uma orfa se resolver = ORPHAN_MS + 3 x SCAN_MS (spec da saga, "Parada da R5"), arredondado para cima ao segundo. Valor ausente ou
# nao numerico (container sem a variavel) cai no default do servico (30000 / 5000 = 45 s).
function Get-OrfaLimiteS {
    param([string]$OrphanMs = '', [string]$ScanMs = '')
    $o = 30000; $s = 5000
    if ($OrphanMs -match '^\d+$') { $o = [int64]$OrphanMs }
    if ($ScanMs -match '^\d+$') { $s = [int64]$ScanMs }
    [int][math]::Ceiling(($o + 3 * $s) / 1000.0)
}

# Pura: a DECISAO da saga no fim do bloco a partir das contagens (o SQL e o polling ficam no subir-host-rpm.ps1). Codigo 4 = alguma invariante da saga
# nao convergiu dentro do limite (orfa que nao se resolve). NAO decide overselling: o codigo 3 (CAS) continua sendo so overselling e tem precedencia no script.
function Get-DecisaoSaga {
    param([int]$LimiteS = 45, [int64]$SagasVelhas = 0, [int64]$HoldHeldSagaCompensada = 0, [int64]$DevolucaoPendente = 0, [int64]$OrfaInversa = 0, [int64]$QuantDivergente = 0)
    $falhas = @()
    if ($SagasVelhas -gt 0) { $falhas += "sagas reserving/compensating mais velhas que ${LimiteS}s: $SagasVelhas" }
    if ($HoldHeldSagaCompensada -gt 0) { $falhas += "hold held com saga compensated: $HoldHeldSagaCompensada" }
    if ($DevolucaoPendente -gt 0) { $falhas += "hold held com ReservationCancelled/Expired no event_store ha mais de ${LimiteS}s (devolucao pendente): $DevolucaoPendente" }
    if ($OrfaInversa -gt 0) { $falhas += "walletevent pending/confirmed sem hold held (orfa inversa): $OrfaInversa" }
    if ($QuantDivergente -gt 0) { $falhas += "eventos limitados com quant <> capacity - holds held debitados: $QuantDivergente" }
    if ($falhas.Count -eq 0) {
        return [pscustomobject]@{ Codigo = 0; Mensagem = "sagas convergidas (velhas $SagasVelhas, hold held com saga compensated $HoldHeldSagaCompensada, devolucao pendente $DevolucaoPendente, orfa inversa $OrfaInversa, quant divergente $QuantDivergente)." }
    }
    [pscustomobject]@{ Codigo = 4; Mensagem = "invariante(s) da saga que nao convergiram: " + ($falhas -join '; ') + '.' }
}

# Pura: TOTAL-LAG na saida de `rpk group describe <group>`; $null se nao ha (group ausente / erro)
function Get-TotalLag {
    param([string[]]$Saida)
    foreach ($l in $Saida) {
        $p = "$l".Trim() -split '\s+'
        if ($p.Count -ge 2 -and $p[0] -eq 'TOTAL-LAG' -and $p[1] -match '^\d+$') { return [int64]$p[1] }
    }
    $null
}

# Espera o lag do group chegar a 0 (devolucao da vaga: o StockReleaseConsumer do stock consome ReservationCancelled/Expired do bloco que acabou; devolucao
# ainda no topico seria falsa "devolucao pendente"). Devolve o lag que restou ($null = nao deu para ler: sem redpanda/group; 0 = drenado). Teto curto.
function Wait-LagDoGroup {
    param([string]$Group = 'stock', [int]$TetoS = 30, [string]$Container = $(if ($env:CARGA_REDPANDA) { $env:CARGA_REDPANDA } else { 'gerenciador-redpanda' }))
    $ErrorActionPreference = 'Continue'   # ver Wait-FilaDeAdmissao: stderr do docker/rpk nao pode lancar
    $fim = (Get-Date).AddSeconds($TetoS)
    $n = $null
    do {
        $saida = & docker exec $Container rpk group describe $Group 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        $n = Get-TotalLag -Saida @($saida)
        if ($null -eq $n -or $n -eq 0) { return $n }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $fim)
    $n
}
