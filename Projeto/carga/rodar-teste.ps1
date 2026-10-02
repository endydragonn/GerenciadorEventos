<#
.SYNOPSIS
  Roda o teste de carga a partir do GERADOR: semeia usuarios + evento, dispara o k6 e registra o resultado.

.DESCRIPTION
  Para cada valor de -Vus (clientes concorrentes):
    1. cria N usuarios e 1 evento com -Capacidade vagas (POST /bff/seed, endpoint publico de dev)
    2. espera o read model atualizar (relevante no caminho naive)
    3. roda carga.js (k6): N clientes disputam as vagas ao mesmo tempo
    4. acrescenta uma linha em results\resultados.csv e mostra o resumo

  O que o gerador mede (cliente): reservas aceitas, "lotado", erros, latencia p50/p95/p99.
  A verdade do banco (overselling real) se confere NO HOST: .\setup-host.ps1 -Verificar -EventId <id>

  IMPORTANTE: -Rotulo e -RefreshMs precisam bater com o que o host esta rodando
  (setup-host.ps1 -ReadPath ... -RefreshMs ...). O gerador nao tem como descobrir isso.

.EXAMPLE
  .\rodar-teste.ps1 -HostIp 192.168.0.10 -Vus 50,100,200 -Capacidade 20 -Rotulo cas
  .\rodar-teste.ps1 -HostIp 192.168.0.10 -Vus 100 -Capacidade 20 -Rotulo naive -RefreshMs 5000
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$HostIp,
    [int]$Porta = 8081,
    [int[]]$Vus = @(50),
    [int]$Capacidade = 20,
    [ValidateSet('cas', 'naive')][string]$Rotulo = 'cas',
    [int]$RefreshMs = 5000,
    [string]$SenhaTeste = 'senha123'
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

function Post-Json($url, $obj) {
    $json = $obj | ConvertTo-Json -Depth 10
    return Invoke-RestMethod -Uri $url -Method Post -Body $json -ContentType 'application/json' -TimeoutSec 120
}

function Resolve-EventId($nome, $criadorId) {
    # GET /bff/events?creator_id=N e publico e devolve List<Event> (campos event_id / event_name).
    # Se falhar por qualquer motivo, o script pede o id (consulta no host).
    try { $lista = @(Invoke-RestMethod -Uri "$Base/bff/events?creator_id=$criadorId" -Method Get -TimeoutSec 30) } catch { return 0 }
    foreach ($e in $lista) {
        if ($e.event_name -eq $nome -and $e.event_id) { return [int]$e.event_id }
    }
    return 0
}

if (-not (Test-Path $Csv)) {
    'data,rotulo,refresh_ms,clientes,capacidade,aceitas,lotado,outros,sem_login,overselling_cliente,p50_ms,p95_ms,p99_ms,max_ms,duracao_s,reservas_por_s,event_id' |
        Set-Content -Path $Csv -Encoding UTF8
}

foreach ($n in $Vus) {
    $runId = [int]((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalSeconds % 1000000
    Write-Host ''
    Info "=== Rodada: $n clientes, capacidade $Capacidade, rotulo=$Rotulo (run $runId) ==="

    # ---- 1. usuarios
    $users = @()
    $usersFile = @()
    for ($i = 1; $i -le $n; $i++) {
        $email = 'carga{0}u{1}@teste.local' -f $runId, $i
        $users += @{
            name      = "Carga $runId U$i"
            email     = $email
            password  = $SenhaTeste
            fone      = '11{0:D6}{1:D3}' -f $runId, $i
            birthdate = '1990-01-15'   # chave JSON real de User (@JsonProperty("birthdate"))
            isAdmin   = $false
        }
        $usersFile += @{ email = $email; password = $SenhaTeste }
    }
    Info "Criando $n usuarios (bcrypt no host: pode levar alguns segundos)..."
    $r = Post-Json "$Base/bff/seed" @{ users = $users }
    if ($r.usersCreated -ne $n) {
        Warn "Criados $($r.usersCreated) de $n usuarios."
        if ($r.warnings) { $r.warnings | Select-Object -First 3 | ForEach-Object { Warn $_ } }
        if ($r.errors)   { $r.errors   | Select-Object -First 3 | ForEach-Object { Warn $_ } }
        if ($r.usersCreated -eq 0) { Fail 'Nenhum usuario criado; abortando.' }
    }

    # ---- 2. criador do evento = primeiro usuario criado
    $login = Post-Json "$Base/bff/users/login" @{ email = $users[0].email; password = $SenhaTeste }
    $criador = [int]$login.user.user_id   # User serializa o id como "user_id"

    # ---- 3. evento
    $nomeEvento = "Carga-$runId-N$n"
    $dataEvento = (Get-Date).AddYears(1).ToString('yyyy-MM-ddTHH:mm:ss')
    $r = Post-Json "$Base/bff/seed" @{
        events = @(@{
            creator_id   = $criador
            event_name   = $nomeEvento
            is_EAD       = $true
            address      = 'Online'
            event_date   = $dataEvento
            lot_quantity = $Capacidade
            quantity     = $Capacidade
            description  = 'Evento criado pelo teste de carga'
            presenters   = @()
        })
    }
    if ($r.eventsCreated -ne 1) { Fail "Evento nao criado: $($r | ConvertTo-Json -Compress)" }

    $eventId = Resolve-EventId $nomeEvento $criador
    if ($eventId -le 0) {
        Warn 'Nao consegui descobrir o event_id pela API. No HOST, rode:'
        Write-Host ("  docker exec gerenciador-db sh -c 'psql -U `"`$POSTGRES_USER`" -d `"`$POSTGRES_DB`" -At -c `"select event_id from event where event_name=''{0}''`"'" -f $nomeEvento)
        $eventId = [int](Read-Host 'Informe o event_id')
    }
    Ok "Evento '$nomeEvento' (id $eventId) criado com $Capacidade vagas."

    # ---- 4. esperar o read model (so importa no caminho naive)
    $espera = [math]::Ceiling($RefreshMs / 1000) + 2
    Info "Aguardando $espera s para o read model refletir o evento..."
    Start-Sleep -Seconds $espera

    # ---- 5. k6
    $tag = "{0}-n{1}-{2}" -f $Rotulo, $n, $runId
    $uf = (Join-Path $Results "users-$tag.json").Replace('\', '/')
    $sf = (Join-Path $Results "summary-$tag.json").Replace('\', '/')
    (ConvertTo-Json -InputObject $usersFile -Depth 5) | Set-Content -Path $uf -Encoding UTF8
    $env:BASE_URL = $Base
    $env:EVENT_ID = "$eventId"
    $env:CAPACITY = "$Capacidade"
    $env:USERS_FILE = $uf
    $env:SUMMARY_FILE = $sf
    Info 'Disparando k6...'
    k6 run --quiet (Join-Path $PSScriptRoot 'carga.js')
    if ($LASTEXITCODE -ne 0) { Warn "k6 terminou com codigo $LASTEXITCODE." }
    Remove-Item $uf -ErrorAction SilentlyContinue   # contem so credenciais de teste, mas nao precisa ficar

    # ---- 6. resultado
    if (-not (Test-Path $sf)) { Fail 'k6 nao gerou o resumo; veja a saida acima.' }
    $s = Get-Content $sf -Raw | ConvertFrom-Json
    $inv = [System.Globalization.CultureInfo]::InvariantCulture   # ponto decimal, mesmo em pt-BR
    [string]::Format($inv, '{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10:N1},{11:N1},{12:N1},{13:N1},{14:N2},{15:N1},{16}',
        (Get-Date -Format s), $Rotulo, $RefreshMs, $s.clientes, $s.capacidade, $s.aceitas, $s.lotado,
        $s.outros, $s.sem_login, $s.overselling_cliente, $s.p50_ms, $s.p95_ms, $s.p99_ms, $s.max_ms,
        $s.duracao_s, $s.reservas_por_s, $eventId) |
        Add-Content -Path $Csv -Encoding UTF8

    Write-Host ''
    Write-Host "  clientes ............ $($s.clientes)"
    Write-Host "  capacidade .......... $($s.capacidade)"
    Write-Host "  aceitas (201) ....... $($s.aceitas)"
    Write-Host "  lotado (400) ........ $($s.lotado)"
    Write-Host "  erros inesperados ... $($s.outros)"
    $cor = 'Green'
    if ($s.overselling_cliente -gt 0) { $cor = 'Red' }
    Write-Host "  overselling (cliente) $($s.overselling_cliente)" -ForegroundColor $cor
    Write-Host ('  latencia p50/p95/p99 . {0:N1} / {1:N1} / {2:N1} ms (descontar a base de rede)' -f $s.p50_ms, $s.p95_ms, $s.p99_ms)
    Write-Host ('  vazao ............... {0:N1} reservas/s' -f $s.reservas_por_s)
    Write-Host "  conferir no HOST .... .\setup-host.ps1 -Verificar -EventId $eventId" -ForegroundColor Yellow

    if ($s.outros -gt 0) { Warn 'Houve respostas inesperadas (nem 201 nem "lotado"): a rodada pode nao ser valida.' }
    if ($s.aceitas -lt $Capacidade -and $n -ge $Capacidade) { Warn 'Aceitas abaixo da capacidade com clientes suficientes: investigue erros/lotado.' }

    Start-Sleep -Seconds 5
}

Write-Host ''
Ok "Resultados acumulados em $Csv"
Write-Host 'Reservas pending expiram em ~120 s (devolvem a vaga); rode -Verificar logo apos a rodada.'
