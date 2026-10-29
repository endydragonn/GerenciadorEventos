<#
.SYNOPSIS
  Matriz do experimento (issue #11), a partir do GERADOR: disputa de vagas para uma lista de N,
  com repeticoes, para UM braco (o que o host esta rodando).

.DESCRIPTION
  O braco (RESERVATION_READPATH) e lido no startup do backend e NAO muda em runtime: suba o host com
  `make setup-host READPATH=<..>` e passe aqui o MESMO valor em -Rotulo (so rotula a coluna braco).
  Para outro braco: troque no host e rode de novo; as linhas se acumulam em results\matriz.csv.

  O LAG DEIXOU DE SER VARIAVEL CONTROLADA (issue #29): o refresh por timer
  (READMODEL_AVAILABILITY_REFRESH_MS) saiu, e a disponibilidade do braco naive e uma projecao assincrona
  (append -> relay -> Redpanda -> consumer). O lag e o do pipeline REAL, MEDIDO sob a carga (variavel
  independente = a carga: N concorrentes / RPM). A coluna lag_ms do CSV e mantida so por compatibilidade do
  formato e vale sempre 0 ("nao controlado"); -RefreshMs e aceito e ignorado. Medicao: ver lag_obs_* abaixo.

  ATENCAO: applied_at - last_occurred_at (lag_obs_*) e o lag do ULTIMO evento aplicado e so anda quando o consumer
  aplica algo: e um LIMITE INFERIOR do atraso (um consumer atrasado ou parado nao o atualiza). A metrica principal da
  Rodada 2 (#31) e o log `events.lag` do container gerenciador-reservation (relay_ms / consumer_ms / total_ms por evento; ligue na rodada com
  LOGGING_LEVEL_EVENTS_LAG=DEBUG no servico reservation do host) MAIS o offset lag do consumer group (`docker exec gerenciador-redpanda rpk
  group describe reservation`, no host, durante a carga).

  Para cada N em -Vus:
    1. semeia N usuarios + -Repeticoes eventos novos, todos com a mesma -Capacidade (seed unico)
    2. antes da 1a rodada do script inteiro: AQUECIMENTO descartado (rajada pequena, evento
       proprio, sem linha no CSV)
    3. para cada repeticao (um evento novo cada): espera o pipeline de eventos do SERVICO reservation ficar quieto (reservation.event_outbox vazio e a
       projecao sem aplicar evento novo: o backlog de uma repeticao nao vaza para a seguinte), dispara o k6
       (PCT_CONFIRMA=100) e, enquanto ele roda, amostra a cada ~-PollMs, no Postgres do host, o lag REAL da
       projecao de disponibilidade do evento: applied_at - last_occurred_at de reservation.event_availability (idade, no
       relogio do Postgres, do ultimo evento aplicado pelo consumer: do append ao commit do consumer, relay
       incluido). Complemento por rodada (nao automatizado aqui): o offset lag do consumer group, com
       `docker exec gerenciador-redpanda rpk group describe reservation`; e o lag por evento
       (relay_ms / consumer_ms / total_ms) no log `events.lag` do container gerenciador-reservation. LIMITE da amostra: ela so anda quando o
       consumer aplica um evento; consumer parado nao a atualiza, entao confira tambem o offset lag.
    4. apos a rajada le confirmed/pending do evento no Postgres e acrescenta UMA linha ao CSV

  O Postgres do host e consultado por psql remoto (-PgPorta, padrao 5433). A senha vem SO da
  variavel de ambiente PGPASSWORD (nunca argumento, nunca impressa, nunca gravada).

  So repeticao VALIDA vira linha do CSV (k6 com codigo 0, resumo com disparo_ms, nenhum usuario sem
  login, nenhuma resposta inesperada/5xx, toda reserva aceita confirmada: Test-RepeticaoValida). A
  invalida e pulada, contada e listada no fim (exit code 2 = matriz incompleta) e o resumo do k6 dela
  fica em results\matriz-invalida-*.json (sem credenciais).

  Braco cas (issue #12): tolerancia 0. Se confirmed > capacity OU pending + confirmed > capacity
  (Test-ToleranciaCas), a execucao FALHA: e um resultado (bug do CAS), entra no CSV normalmente
  (nao e repeticao invalida) e o script termina com exit code 3 (prevalece sobre o 2).
  Esperado no CAS: oversell = 0 em todas as linhas. No braco naive o overselling e o resultado
  medido e nao reprova nada. Atalho do preset: make test-matriz-cas.

  throughput = reservas/s so na janela da rajada (primeira iteracao iniciada .. ultima terminada);
  nao e o reservas_por_s do k6, cujo tempo inclui os logins do setup().

  lag_obs_disparo / lag_obs_max: -1 = sem leitura (a projecao ainda nao tem linha do evento, ou nenhuma
  amostra valida na janela). Resolucao = intervalo do polling; ver lib-matriz.ps1.

.EXAMPLE
  $s = Read-Host 'Senha do Postgres do host' -AsSecureString
  $env:PGPASSWORD = [System.Net.NetworkCredential]::new('', $s).Password
  .\rodar-matriz.ps1 -HostIp 192.168.0.10 -Vus 50,100,200 -Repeticoes 10 -Capacidade 20 -Rotulo naive
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$HostIp,
    [int]$Porta = 8081,
    [int]$PgPorta = 5433,
    [string]$PgUsuario = $(if ($env:PGUSER) { $env:PGUSER } else { 'admin' }),
    [string]$PgBanco = $(if ($env:PGDATABASE) { $env:PGDATABASE } else { 'meu_banco' }),
    [ValidateRange(1, 5000)][int[]]$Vus = @(50, 100, 200),
    [ValidateRange(1, 1000)][int]$Repeticoes = 10,
    [ValidateRange(1, 100000)][int]$Capacidade = 20,
    [ValidateSet('cas', 'naive')][string]$Rotulo = 'cas',
    [ValidateRange(0, 600000)][int]$RefreshMs = 0,   # obsoleto (#29): aceito e ignorado; o lag e medido, nao configurado
    [ValidateRange(1, 5000)][int]$VusAquecimento = 20,
    [ValidateRange(100, 5000)][int]$PollMs = 250
)

$ErrorActionPreference = 'Stop'
$Results = Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Force -Path $Results | Out-Null
$Base = "http://${HostIp}:${Porta}"
$Csv = Join-Path $Results 'matriz.csv'
$CargaJs = Join-Path $PSScriptRoot 'carga.js'

function Info($m) { Write-Host "[matriz] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

. (Join-Path $PSScriptRoot 'lib-seed.ps1')     # Invoke-Seed (seed unico: docs\carga\harness\setup.sh)
. (Join-Path $PSScriptRoot 'lib-matriz.ps1')   # linha do CSV, lag da rajada, Find-Psql
. (Join-Path $PSScriptRoot 'lib-telemetria.ps1') # CPU/RAM do gerador durante a matriz

# ---------------------------------------------------------------- pre-requisitos
$K6 = (Get-Command k6 -ErrorAction SilentlyContinue).Source
if (-not $K6) { Fail 'k6 nao encontrado. Rode .\setup-gerador.ps1 primeiro.' }
$Psql = Find-Psql
if (-not $Psql) { Fail 'psql nao encontrado. Rode .\setup-gerador.ps1 (instala/orienta) e abra um novo PowerShell.' }
if (-not $env:PGPASSWORD) {
    Fail ("PGPASSWORD nao definida. Defina so na sessao, sem ecoar a senha:`n" +
        "  `$s = Read-Host 'Senha do Postgres do host' -AsSecureString`n" +
        "  `$env:PGPASSWORD = [System.Net.NetworkCredential]::new('', `$s).Password")
}
try { Invoke-WebRequest -Uri "$Base/actuator/health" -UseBasicParsing -TimeoutSec 5 | Out-Null }
catch { Fail "Backend inacessivel em $Base. Confira o host (make setup-host) e a rede." }

# commit curto do checkout do GERADOR (host e gerador devem estar no mesmo commit)
$commit = 'desconhecido'
if (Get-Command git -ErrorAction SilentlyContinue) {
    $ea = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # git fora de um repo escreve no stderr (ver Invoke-Psql)
    try {
        $h = (& git -C $PSScriptRoot rev-parse --short HEAD 2>$null)
        if ($LASTEXITCODE -eq 0 -and $h) {
            $commit = "$h".Trim()
            # results\ fora da conta: os CSVs de rodadas anteriores sao versionados e sujariam o checkout
        if (& git -C $PSScriptRoot status --porcelain --untracked-files=no -- . ':(exclude)results' 2>$null) { Warn "Checkout do gerador com alteracoes locais: o commit $commit nao representa o codigo testado." }
        }
    } finally { $ErrorActionPreference = $ea }
}
if ($commit -eq 'desconhecido') { Warn 'Nao consegui ler o commit do checkout do gerador (coluna commit = desconhecido).' }

function Get-EpochMs { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }

# psql remoto: a conexao vem das variaveis padrao do libpq (PGHOST/PGPORT/PGUSER/PGDATABASE; a
# senha ja esta em PGPASSWORD). O SQL vai por stdin (aspas embutidas se perdem em argumento nativo
# no PowerShell 5.1). Com -Silencioso devolve $null em vez de falhar (usado no polling).
# Com $ErrorActionPreference = 'Stop', o PowerShell 5.1 transforma QUALQUER escrita de um comando
# nativo no stderr (mesmo com 2>$null) em excecao terminante: por isso 'Continue' so nesta chamada.
function Invoke-Psql([string]$Sql, [switch]$Silencioso) {
    $ea = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Silencioso) { $saida = $Sql | & $Psql -X -w -At -F ',' -v ON_ERROR_STOP=1 2>$null }
        else { $saida = $Sql | & $Psql -X -w -At -F ',' -v ON_ERROR_STOP=1 }
    } finally { $ErrorActionPreference = $ea }
    if ($LASTEXITCODE -ne 0) {
        if ($Silencioso) { return $null }
        throw "psql falhou (codigo $LASTEXITCODE). Confira PGPASSWORD, usuario/banco, a porta $PgPorta no firewall do host e se o container db esta no ar."
    }
    return (($saida | Out-String).Trim())
}

# Amostra do lag REAL da projecao assincrona (ADR Event-Backbone-Event-Store, secao 4): applied_at - last_occurred_at
# do evento = idade, no relogio do Postgres, do ultimo evento aplicado pelo consumer (o antigo now() - refreshed_at
# nao serve: sob carga continua ficaria perto de 0 com eventos atrasados). Ts no relogio local (o mesmo do k6) =
# ponto medio entre antes e depois da chamada.
function Get-AmostraLag([int]$EventId) {
    $t0 = Get-EpochMs
    $r = Invoke-Psql -Silencioso -Sql "SELECT (EXTRACT(EPOCH FROM (applied_at - last_occurred_at)) * 1000)::bigint FROM reservation.event_availability WHERE event_id = $EventId;"
    if ($null -eq $r) { return $null }
    $lag = -1
    if ($r -match '^\d+$') { $lag = [int]$r }
    [pscustomobject]@{ Ts = [long](($t0 + (Get-EpochMs)) / 2); Lag = $lag }
}

# A disponibilidade do naive e eventcap LEFT JOIN a projecao: basta o evento ter eventcap (o seed ja o cria).
# A linha da projecao so nasce com a 1a reserva (e a 1a amostra de lag vem depois dela).
function Wait-Evento([int]$EventId) {
    $limite = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $limite) {
        if ((Invoke-Psql -Silencioso -Sql "SELECT 1 FROM stock.eventcap WHERE event_id = $EventId;") -eq '1') { return }
        Start-Sleep -Milliseconds 250
    }
    Fail "stock.eventcap sem linha para o evento $EventId apos 30 s (o catalog cria o eventcap por chamada sincrona ao servico reservation; ele esta no ar?)."
}

# Desde a #33 o pipeline e o do servico reservation (relay/outbox/consumer no schema reservation; consumer group `reservation`).
# Antes de cada rajada: o pipeline de eventos tem de estar QUIETO, senao o backlog da repeticao anterior vaza para esta
# (o consumer ainda aplicando eventos antigos infla o lag e o oversell da rajada seguinte). Quieto = event_outbox vazio
# (o relay do reservation publicou tudo) E o max(applied_at) da projecao parado entre duas leituras (o consumer parou de aplicar).
# Limite: um consumer TRAVADO tambem parece quieto; confirme o offset lag (rpk group describe reservation).
function Wait-Estavel([int]$EventId, [int]$TimeoutS = 120) {
    Wait-Evento $EventId
    $limite = (Get-Date).AddSeconds($TimeoutS)
    $anterior = $null
    while ((Get-Date) -lt $limite) {
        $outbox = Invoke-Psql -Silencioso -Sql "SELECT count(*) FROM reservation.event_outbox;"
        $aplicado = Invoke-Psql -Silencioso -Sql "SELECT COALESCE(max(applied_at)::text, 'nenhum') FROM reservation.event_availability;"
        if ($outbox -eq '0' -and $aplicado -eq $anterior) { return }
        $anterior = $aplicado
        Start-Sleep -Milliseconds 1500
    }
    Warn "Pipeline de eventos nao ficou quieto em $TimeoutS s (outbox=$outbox): o backlog pode vazar para esta rajada."
}

# Dispara o k6 contra o evento. Com -ComPolling, amostra o lag em loop ate o k6 terminar.
# Devolve as amostras (vazio sem polling).
function Invoke-Rajada([int]$EventId, [string]$UsersFile, [string]$SummaryFile, [switch]$ComPolling) {
    $env:BASE_URL = $Base
    $env:EVENT_ID = "$EventId"
    $env:CAPACITY = "$Capacidade"
    $env:PCT_CONFIRMA = '100'
    $env:USERS_FILE = $UsersFile
    $env:SUMMARY_FILE = $SummaryFile
    $amostras = New-Object System.Collections.Generic.List[object]
    $p = Start-Process -FilePath $K6 -ArgumentList @('run', '--quiet', ('"{0}"' -f $CargaJs)) -NoNewWindow -PassThru
    $null = $p.Handle   # sem isso o ExitCode volta $null no PowerShell 5.1
    if ($ComPolling) {
        while (-not $p.HasExited) {
            $a = Get-AmostraLag $EventId
            if ($a) { $amostras.Add($a) }
            if (-not $p.HasExited) { Start-Sleep -Milliseconds $PollMs }
        }
        $a = Get-AmostraLag $EventId
        if ($a) { $amostras.Add($a) }
    } else {
        $p.WaitForExit()
    }
    $script:CodigoK6 = [int]$p.ExitCode   # lido pelo chamador (Test-RepeticaoValida)
    if ($script:CodigoK6 -ne 0) { Warn "k6 terminou com codigo $($script:CodigoK6)." }
    return , $amostras.ToArray()
}

# ---------------------------------------------------------------- conexao
$envAnterior = @{}
foreach ($v in 'PGHOST', 'PGPORT', 'PGUSER', 'PGDATABASE', 'PGCONNECT_TIMEOUT') { $envAnterior[$v] = [Environment]::GetEnvironmentVariable($v) }
$env:PGHOST = $HostIp; $env:PGPORT = "$PgPorta"; $env:PGUSER = $PgUsuario; $env:PGDATABASE = $PgBanco; $env:PGCONNECT_TIMEOUT = '5'
$tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ("matriz-{0}" -f [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$gravadas = 0
$invalidas = @()
$falhasCas = @()
$codigoSaida = 0
$script:CodigoK6 = 0
$TelCsv = Join-Path $Results ("telemetria-gerador-matriz-{0:yyyyMMdd-HHmmss}.csv" -f (Get-Date))
$tel = $null

try {
    $tel = Start-Telemetria -Saida $TelCsv
    Info "Conferindo o Postgres do host (${HostIp}:$PgPorta, usuario $PgUsuario, banco $PgBanco)..."
    Invoke-Psql 'SELECT 1;' | Out-Null
    Ok 'psql remoto respondeu.'
    Info "Matriz: braco=$Rotulo lag=medido N=$($Vus -join ',') repeticoes=$Repeticoes capacity=$Capacidade commit=$commit"
    foreach ($n in $Vus) { if ($n -le $Capacidade) { Warn "N=$n <= capacity=${Capacidade}: sem disputa, nao ha overselling possivel." } }

    $runId = [int]((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalSeconds % 1000000

    # ---- aquecimento (descartado: nao gera linha no CSV)
    Info "Aquecimento descartado: $VusAquecimento clientes em um evento proprio..."
    $uf = (Join-Path $tmpDir 'users-aq.json').Replace('\', '/')
    $ef = (Join-Path $tmpDir 'events-aq.json').Replace('\', '/')
    $sf = (Join-Path $tmpDir 'summary-aq.json').Replace('\', '/')
    $seed = Invoke-Seed -BaseUrl $Base -Usuarios $VusAquecimento -Eventos 1 -Capacidade $Capacidade `
        -Prefixo "Matriz-$runId-Aq" -Sufixo "$runId-aq" -UsersFile $uf -EventIdsFile $ef
    Wait-Estavel $seed.EventIds[0]
    Invoke-Rajada -EventId $seed.EventIds[0] -UsersFile $uf -SummaryFile $sf | Out-Null
    Remove-Item $uf, $ef -ErrorAction SilentlyContinue

    foreach ($n in $Vus) {
        $runId = [int]((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalSeconds % 1000000
        Write-Host ''
        Info "=== N=$n clientes, $Repeticoes repeticoes, capacity $Capacidade, braco=$Rotulo, lag=medido (run $runId) ==="
        $uf = (Join-Path $tmpDir "users-$n.json").Replace('\', '/')
        $ef = (Join-Path $tmpDir "events-$n.json").Replace('\', '/')
        Info "Semeando $n usuarios + $Repeticoes eventos novos (bcrypt no host: pode levar um tempo)..."
        $seed = Invoke-Seed -BaseUrl $Base -Usuarios $n -Eventos $Repeticoes -Capacidade $Capacidade `
            -Prefixo "Matriz-$runId-N$n" -Sufixo "$runId" -UsersFile $uf -EventIdsFile $ef

        for ($rep = 1; $rep -le $Repeticoes; $rep++) {
            $eventId = $seed.EventIds[$rep - 1]
            $sf = (Join-Path $tmpDir "summary-$n-$rep.json").Replace('\', '/')
            Wait-Estavel $eventId
            Info "rep $rep/${Repeticoes}: evento $eventId"
            $amostras = Invoke-Rajada -EventId $eventId -UsersFile $uf -SummaryFile $sf -ComPolling

            # So repeticao VALIDA vira linha do CSV: uma invalida enviesaria a media/desvio do overselling
            # sem rastro. A invalida e pulada (contada e listada no fim) e o resumo do k6 (sem credenciais)
            # fica em results\ para diagnostico.
            $motivos = @()
            if (Test-Path $sf) {
                $s = Get-Content $sf -Raw | ConvertFrom-Json
                $motivos = @((Test-RepeticaoValida -Resumo $s -CodigoK6 $script:CodigoK6).Motivos)
            } else { $motivos = @('k6 nao gerou o resumo') }

            # confirmed/pending/capacity do evento, no Postgres do host (fonte de verdade). Lido ANTES do
            # descarte por invalidez, mas so no braco CAS (um overselling nao pode ficar escondido atras de
            # uma repeticao invalida, exit 2); no naive a invalida continua nao dependendo do psql.
            if ($Rotulo -eq 'cas' -or $motivos.Count -eq 0) {
                $linhaDb = Invoke-Psql -Sql ("SELECT count(*) FILTER (WHERE status = 'confirmed'), count(*) FILTER (WHERE status = 'pending'), " +
                    "(SELECT capacity FROM stock.eventcap WHERE event_id = $eventId) FROM reservation.walletevent WHERE event_id = $eventId;")
                $c = $linhaDb -split ','
                if ($c.Count -ne 3) { Fail "Resposta inesperada do Postgres para o evento ${eventId}: '$linhaDb'" }
                $confirmed = [int]$c[0]; $pending = [int]$c[1]
                if ($c[2] -ne "$Capacidade") { Warn "capacity no banco ($($c[2])) difere de -Capacidade ($Capacidade): o oversell usa -Capacidade." }
            }

            # Braco CAS: tolerancia 0 (issue #12, Test-ToleranciaCas). Overselling aqui e RESULTADO (bug do
            # CAS), nao invalidez: a linha entra no CSV normalmente (se a repeticao for valida) e a falha
            # fica visivel ate o fim da matriz (exit 3).
            $tol = $null
            if ($Rotulo -eq 'cas') {
                $tol = Test-ToleranciaCas -Confirmed $confirmed -Pending $pending -Capacity $Capacidade
                if (-not $tol.Ok) {
                    $falhasCas += [pscustomobject]@{ N = $n; Rep = $rep; Evento = $eventId; Motivos = ($tol.Motivos -join '; '); Gravada = ($motivos.Count -eq 0) }
                    Write-Host "[FALHA CAS] rep ${rep}: tolerancia 0 violada no braco CAS: $($tol.Motivos -join '; ')" -ForegroundColor Red
                }
            }

            if ($motivos.Count -gt 0) {
                $invalidas += [pscustomobject]@{ N = $n; Rep = $rep; Evento = $eventId; Motivos = ($motivos -join '; ') }
                Warn "rep ${rep}: INVALIDA, linha NAO gravada em matriz.csv: $($motivos -join '; ')"
                if (Test-Path $sf) { Copy-Item $sf (Join-Path $Results "matriz-invalida-$runId-n$n-rep$rep.json") -Force }
                continue
            }
            $lag = Get-LagRajada -Amostras $amostras -DisparoMs ([long]$s.disparo_ms) -FimMs ([long]$s.fim_ms)
            # reservas/s so na janela da rajada (o duracao_s do k6 inclui os logins do setup())
            $vazao = Get-VazaoRajada -Tentativas ($s.aceitas + $s.lotado + $s.outros) -DisparoMs ([long]$s.disparo_ms) -FimMs ([long]$s.fim_ms)

            $linha = Format-LinhaMatriz -Braco $Rotulo -LagMs 0 -N $n -Capacity $Capacidade -Rep $rep `
                -Confirmed $confirmed -Pending $pending -P50 $s.p50_ms -P95 $s.p95_ms -P99 $s.p99_ms `
                -Throughput $vazao -LagDisparo $lag.Disparo -LagMax $lag.Max -Commit $commit
            Add-LinhaMatriz -Csv $Csv -Linha $linha
            $gravadas++

            $oversell = [math]::Max(0, $confirmed - $Capacidade)
            $cor = 'Green'
            if ($oversell -gt 0) { $cor = 'Yellow' }
            if ($tol -and -not $tol.Ok) { $cor = 'Red' }   # braco CAS fora da tolerancia 0 (inclui a secundaria)
            Write-Host ("  rep {0}: confirmed={1} pending={2} oversell={3}  p95={4:N1} ms  {7:N0} reservas/s  lag obs. disparo/max={5}/{6} ms" -f `
                $rep, $confirmed, $pending, $oversell, $s.p95_ms, $lag.Disparo, $lag.Max, $vazao) -ForegroundColor $cor
        }
        Remove-Item $uf, $ef -ErrorAction SilentlyContinue   # credenciais de teste
    }

    Write-Host ''
    Ok "$gravadas linha(s) gravada(s) em $Csv"
    if ($invalidas.Count -gt 0) {
        Warn "$($invalidas.Count) repeticao(oes) INVALIDA(S), NAO gravada(s) (a matriz esta incompleta; resumos do k6 em results\matriz-invalida-*.json):"
        foreach ($i in $invalidas) { Write-Host ("  N={0} rep={1} evento={2}: {3}" -f $i.N, $i.Rep, $i.Evento, $i.Motivos) -ForegroundColor Yellow }
        Write-Host 'Confira o host (max_connections, backend) e repita; o rep de cada grupo no report mostra quantas repeticoes valem.'
        $codigoSaida = 2
    }
    if ($falhasCas.Count -gt 0) {
        Write-Host ("[FALHA CAS] {0} execucao(oes) fora da tolerancia 0 no braco CAS (overselling primario ou secundario): e bug do CAS, nao ruido (as execucoes validas ja estao no CSV):" -f $falhasCas.Count) -ForegroundColor Red
        foreach ($f in $falhasCas) {
            $nota = ''
            if (-not $f.Gravada) { $nota = ' (repeticao invalida: linha NAO gravada)' }
            Write-Host ("  N={0} rep={1} evento={2}: {3}{4}" -f $f.N, $f.Rep, $f.Evento, $f.Motivos, $nota) -ForegroundColor Red
        }
        $codigoSaida = 3   # prevalece sobre 2: a falha do CAS e o achado; o 'incompleta' ja foi listado acima
    }
    Stop-Telemetria $tel; $tel = $null
    Info ('Gerador durante a matriz: ' + (Get-ResumoTelemetria $TelCsv) + "  [$TelCsv; host: make telemetria-host]")
    Write-Host 'Grafico: make report-matriz   (gera results\matriz.html)'
} finally {
    Stop-Telemetria $tel
    Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue   # inclui os users-*.json (credenciais de teste)
    foreach ($v in $envAnterior.Keys) { [Environment]::SetEnvironmentVariable($v, $envAnterior[$v]) }
    foreach ($v in 'BASE_URL', 'EVENT_ID', 'CAPACITY', 'PCT_CONFIRMA', 'USERS_FILE', 'SUMMARY_FILE') { Remove-Item "Env:$v" -ErrorAction SilentlyContinue }
}
exit $codigoSaida   # 0 = ok; 1 = erro (Fail); 2 = houve repeticao invalida (matriz incompleta); 3 = overselling no braco CAS (prevalece sobre 2)
