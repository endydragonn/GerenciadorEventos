<#
.SYNOPSIS
  Sobe a stack no HOST para o teste de taxa (RPM), com max_connections do Postgres configuravel.
  Com -Verificar, soma o resultado real do banco para os eventos de uma rodada (prefixo do nome).

.EXAMPLE
  .\subir-host-rpm.ps1                              # max_connections=300, caminho CAS
  .\subir-host-rpm.ps1 -MaxConnections 100          # padrao do Postgres (pool do pooler = 45)
  .\subir-host-rpm.ps1 -Verificar -Prefixo Rpm-123456     # sai com codigo 3 se houver overselling no braco CAS (criterio de parada do bloco, #178)
  .\subir-host-rpm.ps1 -ComposeExtras docker-compose.autoescala.yml   # braco C: overrides extras DEPOIS dos de carga e rpm (Hikari 300 por replica); a R4 roda o braco sem autoescala antes
#>
[CmdletBinding()]
param(
    [ValidateSet('cas', 'naive')][string]$ReadPath = 'cas',
    [int]$MaxConnections = 300,
    [switch]$SemBuild,
    [switch]$Verificar,
    [string]$Prefixo = '',
    [string[]]$ComposeExtras = @()   # issue #178: mais overrides (paths relativos a Projeto\), ex. docker-compose.slim.yml e docker-compose.autoescala.yml
)

$ErrorActionPreference = 'Stop'
$Projeto = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Compose = Join-Path $Projeto 'docker-compose.yml'
$OvCarga = Join-Path (Split-Path $PSScriptRoot -Parent) 'docker-compose.carga.yml'
$OvRpm = Join-Path $PSScriptRoot 'docker-compose.rpm.yml'
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'lib-servicos.ps1')   # containers por label e lista de -f

function Info($m) { Write-Host "[host-rpm] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

# Anexa a saida da verificacao em resultserificacoes.log (config ativa + timestamp), pra documentar a rodada.
function Registrar($alvo, $saida) {
    $dir = Join-Path (Split-Path $PSScriptRoot -Parent) 'results'
    New-Item -ItemType Directory -Force $dir | Out-Null
    # RESERVATION_READPATH e do backend (gateway); relay/consumer sao do servico reservation (#33)
    # containers por label (issue #178): com a autoescala as replicas nao se chamam gerenciador-<servico>
    $cb = Get-ContainerDoServico backend; $cr = Get-ContainerDoServico reservation
    $cfg = ($(if ($cb) { docker exec $cb printenv RESERVATION_READPATH 2>$null }) + ' / ' + (@($(if ($cr) { docker exec $cr printenv EVENTS_RELAY_ENABLED EVENTS_CONSUMER_ENABLED 2>$null })) -join ' / '))
    $cab = "===== {0} | {1} | config: {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $alvo, $cfg
    Add-Content -Path (Join-Path $dir 'verificacoes.log') -Value (@($cab, $saida, '') -join "`n") -Encoding utf8
}

if ($Verificar) {
    if (-not $Prefixo) { Fail 'Informe -Prefixo (nome impresso pelo rodar-rpm.ps1, ex.: Rpm-123456).' }
    if ($Prefixo -notmatch '^[A-Za-z0-9-]+$') { Fail '-Prefixo so aceita letras, numeros e hifen.' }
    $sql = @"
-- O prefixo e atributo do CATALOG (catalog.event, #35); capacidade e reservas sao do servico (schema reservation). Consulta de
-- verificacao do OPERADOR (psql como admin), nao leitura de um servico em tabela de outro.
WITH ev AS (SELECT e.event_id, c.capacity FROM catalog.event e JOIN reservation.eventcap c USING (event_id)
            WHERE e.event_name LIKE '$Prefixo-%'),
ativas AS (SELECT ev.event_id, ev.capacity,
                  count(w.*) FILTER (WHERE w.status IN ('pending','confirmed')) AS n
           FROM ev LEFT JOIN reservation.walletevent w ON w.event_id = ev.event_id GROUP BY ev.event_id, ev.capacity)
SELECT count(*) AS eventos, sum(n) AS reservas_ativas, sum(capacity) AS capacidade_total,
       count(*) FILTER (WHERE n > capacity) AS eventos_com_overselling,
       coalesce(sum(n - capacity) FILTER (WHERE n > capacity), 0) AS overselling_total
FROM ativas;
SELECT status, count(*) FROM reservation.walletevent
 WHERE event_id IN (SELECT event_id FROM catalog.event WHERE event_name LIKE '$Prefixo-%') GROUP BY status ORDER BY status;
"@
    # Mensagens ainda na fila de admissao viram reserva DEPOIS do fim do bloco: espera drenar antes de contar (so se ha RabbitMQ/admission)
    $resto = Wait-FilaDeAdmissao
    if ($null -ne $resto -and $resto -gt 0) { Write-Host "[aviso] admission.requests ainda com $resto mensagens depois da espera: a contagem abaixo pode estar incompleta; rode de novo." -ForegroundColor Yellow }
    $saida = $sql | docker exec -i (Get-ContainerDb) sh -c 'psql -v ON_ERROR_STOP=1 -U $POSTGRES_USER -d $POSTGRES_DB' | Out-String
    Write-Host $saida
    $rc = $LASTEXITCODE
    Registrar "prefixo $Prefixo" $saida.TrimEnd()
    if ($rc -ne 0) { exit $rc }
    # Overselling > 0 no CAS e o criterio de parada do bloco (issue #178): codigo 3. No braco naive o overselling e esperado (e o que a Rodada mede): so informa.
    $qOver = @"
WITH ev AS (SELECT e.event_id, c.capacity FROM catalog.event e JOIN reservation.eventcap c USING (event_id) WHERE e.event_name LIKE '$Prefixo-%'),
ativas AS (SELECT ev.capacity, count(w.*) FILTER (WHERE w.status IN ('pending','confirmed')) AS n FROM ev LEFT JOIN reservation.walletevent w ON w.event_id = ev.event_id GROUP BY ev.event_id, ev.capacity)
SELECT coalesce(sum(n - capacity) FILTER (WHERE n > capacity), 0) FROM ativas;
"@
    $over = ($qOver | docker exec -i (Get-ContainerDb) sh -c 'psql -v ON_ERROR_STOP=1 -At -U $POSTGRES_USER -d $POSTGRES_DB' | Out-String).Trim()
    $cb = Get-ContainerDoServico backend
    $readpath = if ($cb) { ((docker exec $cb printenv RESERVATION_READPATH 2>$null) | Out-String).Trim() } else { '' }
    if ($over -match '^\d+$' -and [int64]$over -gt 0) {
        if ($readpath -eq 'naive') { Write-Host "[info] overselling $over no braco naive (esperado: e o que a rodada mede)." -ForegroundColor Cyan }
        else { Write-Host "[erro] OVERSELLING $over no braco '$(if ($readpath) { $readpath } else { 'cas?' })': criterio de parada do bloco. NAO prossiga." -ForegroundColor Red; exit 3 }
    } elseif ($over -notmatch '^\d+$') { Write-Host "[erro] nao consegui ler o overselling ('$over')." -ForegroundColor Red; exit 1 }
    else { Write-Host '[ ok ] overselling 0.' -ForegroundColor Green }
    exit 0
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail 'Docker nao encontrado.' }
docker info *> $null
if ($LASTEXITCODE -ne 0) { Fail 'Daemon do Docker nao responde.' }
if (-not (Test-Path (Join-Path $Projeto '.env'))) { Fail 'Projeto\.env nao existe.' }

$env:RESERVATION_READPATH = $ReadPath
$env:PG_MAX_CONNECTIONS = "$MaxConnections"
if ($MaxConnections -le 55) { Fail 'MaxConnections precisa ser > 55 (o pool do pooler e MaxConnections - 55).' }
# Pool do Supavisor (conexoes reais ao Postgres) acompanha o max_connections, com a mesma folga do
# padrao (100 -> 45): sem isso o pooler limitaria a 45 e a rodada mediria o pooler, nao o Postgres.
$env:PG_POOLER_POOL_SIZE = "$($MaxConnections - 55)"
Info "Subindo (readpath=$ReadPath, max_connections=$MaxConnections, pool do pooler=$($MaxConnections - 55))..."
$arquivos = Get-ArquivosCompose -Projeto $Projeto -Carga $OvCarga -Rpm $OvRpm -Extras $ComposeExtras
$a = @('compose') + (ConvertTo-ArgsCompose -Arquivos $arquivos) + @('up', '-d')
if (-not $SemBuild) { $a += '--build' }
& docker @a
if ($LASTEXITCODE -ne 0) { Fail 'docker compose falhou.' }

Info 'Aguardando o backend (ate 180 s; o compose so o sobe com o servico reservation saudavel)...'
$ok = $false
for ($i = 0; $i -lt 90; $i++) {
    try { if ((Invoke-WebRequest -Uri "$(Get-UrlSaudeHost)/actuator/health" -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200) { $ok = $true; break } } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ok) { Fail 'Backend nao ficou saudavel. Veja: docker logs <container do backend> --tail 100 (docker ps --filter label=com.docker.compose.service=backend)' }
Ok 'Backend respondendo.'
Test-HikariDoReservation -Arquivos $arquivos
if (@($ComposeExtras | Where-Object { $_ -match 'autoescala' }).Count -gt 0) {
    Write-Host 'Braco C: rode o scaler no MESMO ambiente e com os MESMOS -f (Git Bash; PARE-O antes de limpar o banco):' -ForegroundColor Cyan
    Write-Host ("  AUTOESCALA_COMPOSE_ARQUIVOS='{0}' Projeto/autoescala/scaler.sh" -f (Get-ArquivosDoScaler -Projeto $Projeto -Arquivos $arquivos))
}
# Via stdin: aspas embutidas em argumento nativo se perdem no PowerShell 5.1.
$mc = 'SHOW max_connections;' | docker exec -i (Get-ContainerDb) sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -At'
Write-Host "max_connections efetivo: $mc" -ForegroundColor Cyan
Write-Host ''
Write-Host '=== Use um destes IPs no gerador de carga (-HostIp) ===' -ForegroundColor Cyan
Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    ForEach-Object { Write-Host ("  {0,-16} ({1})" -f $_.IPAddress, $_.InterfaceAlias) }
Write-Host ''
Write-Host 'Proximo passo (no gerador): .\rpm\rodar-rpm.ps1 -HostIp <ip> -Rpm 10000'
