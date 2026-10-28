<#
.SYNOPSIS
  Escada de carga (issue #14) a partir do GERADOR: semeia usuarios + eventos, dispara o k6 com um degrau
  de taxa fixa (RPM) por vez, numa unica execucao, e grava UM CSV por escada (uma linha por degrau).

.DESCRIPTION
  Degraus: RpmInicio, RpmInicio + RpmPasso, ... <= RpmMax (12k, 16k, 20k, ... por padrao), cada um por
  -Duracao segundos (>= 60 e multiplo de 30: janelas de 30 s inteiras), separados por 31 s ociosos (o
  gracefulStop do k6, 30 s, mais 1 s: as iteracoes em voo de um degrau terminam antes do proximo comecar,
  e os planos de VUs dos cenarios nao se tocam). A escada vai
  ate o maximo configurado, mesmo depois de o host degradar: pare-a no Ctrl+C se preferir (sem CSV) ou
  configure -RpmMax perto do esperado. O limiar X NAO e decidido aqui: make report-degraus (ver
  docs\carga\harness\report-degraus.sh) aplica a regra sobre o CSV. O 5xx do criterio e so o da RESERVA
  (taxa_5xx = erros_5xx / reservas); o da confirmacao vai em erros_5xx_confirma, so informativo.

  Pares (usuario, evento) unicos: cada degrau usa uma faixa propria de indices, de tamanho
  ceil(RPM * Duracao / 60) + 1; o total (soma dos degraus) tem de caber em Usuarios x Eventos. Eventos
  e calculado automaticamente (5% de folga, Get-PlanoEscada) e o k6 aborta no setup() se nao couber.

  p95 sustentado por 30 s: o proprio k6 tagueia cada reserva com a janela de 30 s do instante de ENVIO e emite
  o p95 de cada janela no resumo; o CSV leva o MAIOR (p95_max_janela_30s). Sem serie temporal em disco.

  VUs: -MaxVus VUs sao PRE-alocados (preAllocatedVUs = maxVUs): o k6 descarta a iteracao quando nao ha VU livre
  e so aloca VU extra depois, o que pesaria no gerador e viraria "gerador saturado" no report. Com tudo
  pre-alocado so ha descarte quando uma iteracao (reserva + confirma) passa de MaxVus / (RPM/60) s, isto e,
  quando o host ja degradou. Dimensione -MaxVus pela memoria do gerador (~1-2 MB por VU, estimativa).

  -MaxConnections e -PoolHikari so REGISTRAM no CSV o que o HOST esta rodando (o gerador nao os enxerga):
  host: make host-rpm MAX_CONN=<n> (pool do SERVICO reservation, o caminho quente: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE, padrao 1000).
  Confira no host: docker exec gerenciador-reservation printenv SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE

  -Mapeamento escolhe como a iteracao vira o par (usuario, evento) (degraus\lib-mapeamento.js; vai na ultima coluna do CSV):
    sequencial (padrao) = usuario i % U, evento floor(i / U): U reservas seguidas no MESMO evento (evento disputado, linha
                          quente do eventcap);
    intercalado         = usuario floor(i / E), evento i % E: E reservas seguidas em eventos DIFERENTES (capacidade do host,
                          sem a linha quente de um evento).
  Nos dois modos cada par e unico dentro da escada e a conta Usuarios x Eventos >= soma das cotas vale igual.
  Capacidade por evento (-Capacidade 0, o padrao): sequencial = Usuarios/2 (como o teste 2); intercalado = Usuarios. No
  intercalado o evento recebe a u-esima reserva na "rodada" u, entao Usuarios/2 lotaria todos os eventos juntos no meio da
  escada (segunda metade 100% "lotado", X inflado); com Usuarios cada evento aceita as U reservas (uma por usuario). A
  capacidade usada aparece numa linha Info. Um -Capacidade explicito vale nos dois modos (no intercalado, < Usuarios avisa).

  Sem limite de duracao de 110 s do teste 2: aquele limite evita que reservas pending expirem e falseiem a
  conta de overselling; aqui nao se mede overselling e, com -PctConfirma 100 (padrao), nada fica pending.

  A verdade do banco (overselling real): no HOST,
    .\rpm\subir-host-rpm.ps1 -Verificar -Prefixo Deg-<id impresso aqui>

.EXAMPLE
  .\degraus\rodar-degraus.ps1 -HostIp 192.168.0.10 -MaxConnections 300 -PoolHikari 1000
  .\degraus\rodar-degraus.ps1 -HostIp 192.168.0.10 -MaxConnections 300 -PoolHikari 1000 -RpmInicio 12000 -RpmPasso 2000 -RpmMax 24000 -Duracao 90
  .\degraus\rodar-degraus.ps1 -HostIp 192.168.0.10 -MaxConnections 300 -PoolHikari 1000 -Mapeamento intercalado
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$HostIp,
    [int]$Porta = 8081,
    [ValidateRange(1, 1000000)][int]$RpmInicio = 12000,
    [ValidateRange(1, 1000000)][int]$RpmPasso = 4000,
    [ValidateRange(1, 1000000)][int]$RpmMax = 40000,
    [int]$Duracao = 60,
    [ValidateRange(1, 100000)][int]$Usuarios = 200,
    [int]$Capacidade = 0,          # 0 = padrao por mapeamento (Get-CapacidadePadrao): sequencial Usuarios/2, intercalado Usuarios
    [ValidateSet('cas', 'naive')][string]$Rotulo = 'cas',
    [Parameter(Mandatory)][ValidateRange(1, 100000)][int]$MaxConnections,
    [Parameter(Mandatory)][ValidateRange(1, 100000)][int]$PoolHikari,
    [int]$RefreshMs = 0,   # obsoleto (#29): so rotula a coluna refresh_ms
    [ValidateRange(0, 100)][int]$PctConfirma = 100,
    [ValidateRange(10, 20000)][int]$MaxVus = 2000,
    [ValidateSet('sequencial', 'intercalado')][string]$Mapeamento = 'sequencial'   # ver .DESCRIPTION
)

$ErrorActionPreference = 'Stop'
$Results = Join-Path (Split-Path $PSScriptRoot -Parent) 'results'
New-Item -ItemType Directory -Force -Path $Results | Out-Null
$Base = "http://${HostIp}:${Porta}"
$Mapeamento = $Mapeamento.ToLowerInvariant()   # ValidateSet ignora maiusculas; o k6 e o CSV exigem minusculas

function Info($m) { Write-Host "[degraus] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { Fail 'k6 nao encontrado. Rode ..\setup-gerador.ps1 primeiro.' }
try { Invoke-WebRequest -Uri "$Base/actuator/health" -UseBasicParsing -TimeoutSec 5 | Out-Null }
catch { Fail "Backend inacessivel em $Base." }

. (Join-Path (Split-Path $PSScriptRoot -Parent) 'lib-seed.ps1')   # Invoke-Seed (seed unico: docs\carga\harness\setup.sh)
. (Join-Path $PSScriptRoot 'lib-degraus.ps1')                      # plano da escada e linha do CSV
if ($Capacidade -le 0) { $Capacidade = Get-CapacidadePadrao -Usuarios $Usuarios -Mapeamento $Mapeamento }
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'lib-telemetria.ps1')   # CPU/RAM do gerador durante a escada

try { $plano = Get-PlanoEscada -RpmInicio $RpmInicio -RpmPasso $RpmPasso -RpmMax $RpmMax -DuracaoS $Duracao -Usuarios $Usuarios }
catch { Fail $_.Exception.Message }
$n = $plano.Rpms.Count
$eventos = $plano.Eventos

# commit curto do checkout do GERADOR (host e gerador devem estar no mesmo commit)
$commit = 'desconhecido'
if (Get-Command git -ErrorAction SilentlyContinue) {
    $ea = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # git fora de um repo escreve no stderr, e 'Stop' o transformaria em excecao
    try { $h = (& git -C $PSScriptRoot rev-parse --short HEAD 2>$null); if ($LASTEXITCODE -eq 0 -and $h) { $commit = "$h".Trim() } }
    finally { $ErrorActionPreference = $ea }
}
if ($commit -eq 'desconhecido') { Warn 'Nao consegui ler o commit do checkout do gerador (coluna commit = desconhecido).' }

$runId = [int]((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalSeconds % 1000000
$escada = "Deg-$runId"
$tag = "degraus-$runId"
$uf = (Join-Path $Results "users-$tag.json").Replace('\', '/')
$ef = (Join-Path $Results "events-$tag.json").Replace('\', '/')
$sf = (Join-Path $Results "summary-$tag.json").Replace('\', '/')
$Csv = Join-Path $Results "$tag.csv"
foreach ($f in $sf, $Csv) { if (Test-Path $f) { Fail "Ja existe $f (runId repetido); nao sobrescrevo. Renomeie o arquivo antigo e rode de novo." } }

$TelCsv = Join-Path $Results "telemetria-gerador-$tag.csv"
$tel = $null
$totalS = $n * ($Duracao + 31) - 31
Info ("Escada: {0} degraus de {1} s ({2}), ~{3} min no k6 + seed." -f $n, $Duracao, ($plano.Rpms -join ', '), [math]::Ceiling($totalS / 60.0))
Info "Mapeamento: $Mapeamento ($(if ($Mapeamento -eq 'intercalado') { 'E reservas seguidas em eventos diferentes: capacidade do host' } else { 'U reservas seguidas no mesmo evento: evento disputado' }))."
Info "Pares: $($plano.TotalPares) (soma das cotas ceil(RPM*$Duracao/60)+1); $Usuarios usuarios x $eventos eventos = $($Usuarios * $eventos) (capacidade $Capacidade/evento)."
if ($Mapeamento -eq 'intercalado' -and $Capacidade -lt $Usuarios) { Warn "Mapeamento intercalado com capacidade $Capacidade < $Usuarios usuarios: cada evento recebe ate $Usuarios reservas (uma por usuario) e os eventos lotam juntos no meio da escada (segunda metade 'lotado', X inflado). Use -Capacidade 0 (padrao) ou >= $Usuarios." }
$tolS = $MaxVus / ($plano.Rpms[-1] / 60.0)
Info ("VUs: {0} pre-alocados (= maximo; ~1-2 MB cada: confira a RAM do gerador). So ha descarte se uma iteracao (reserva + confirma) passar de {1:N1} s no degrau de {2} RPM." -f $MaxVus, $tolS, $plano.Rpms[-1])
if ($tolS -lt 2.5) { Warn "MaxVus cobre iteracoes de ate $([math]::Round($tolS, 1)) s no maior degrau: com p95 de 1 s por requisicao (reserva + confirma) o k6 pode descartar iteracoes antes de o p95 chegar a 1 s, e o report leria isso como saturacao do gerador. Aumente -MaxVus ou reduza -RpmMax." }
Info "Registrando do host: braco=$Rotulo max_connections=$MaxConnections pool_hikari=$PoolHikari (confira: docker exec gerenciador-reservation printenv SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE)."

try {
    Info "Semeando $Usuarios usuarios + $eventos eventos (bcrypt no host: pode levar um bom tempo)..."
    $seed = Invoke-Seed -BaseUrl $Base -Usuarios $Usuarios -Eventos $eventos -Capacidade $Capacidade `
        -Prefixo $escada -Sufixo "$runId" -UsersFile $uf -EventIdsFile $ef
    Ok "Prefixo '$escada': $eventos eventos prontos."

    $env:BASE_URL = $Base; $env:RPMS = ($plano.Rpms -join ','); $env:DURACAO_S = "$Duracao"
    $env:USERS_FILE = $uf; $env:EVENTS_FILE = $ef; $env:SUMMARY_FILE = $sf
    $env:PCT_CONFIRMA = "$PctConfirma"; $env:MAX_VUS = "$MaxVus"
    $env:MAPEAMENTO = $Mapeamento   # sempre setado (inclusive no padrao): nao herda valor de uma execucao anterior da sessao
    $tel = Start-Telemetria -Saida $TelCsv
    Info 'Disparando k6...'
    k6 run --quiet (Join-Path $PSScriptRoot 'carga-degraus.js')
    # Os thresholds do carga-degraus.js sao so declarativos (sempre verdadeiros, so materializam submetricas): qualquer
    # codigo != 0 e anormal (script com erro, setup abortado, interrupcao) e NAO gera CSV. Excecao: 99 (thresholds
    # cruzados) so avisa, porque nenhum threshold deveria reprovar; a conferencia abaixo protege os dados.
    if ($LASTEXITCODE -eq 99) { Warn 'k6 terminou com codigo 99 (thresholds cruzados; sao so declarativos e nao deveriam reprovar): confira o resumo.' }
    elseif ($LASTEXITCODE -ne 0) { Fail "k6 terminou com codigo ${LASTEXITCODE}: o CSV nao foi gerado (resumo parcial, se houver, em $sf)." }

    if (-not (Test-Path $sf)) { Fail 'k6 nao gerou o resumo.' }
    $s = Get-Content $sf -Raw | ConvertFrom-Json
    # a UNICA trava: o global de cada metrica tem de fechar com a soma dos degraus, e mais de 1% das reservas de um degrau
    # nao pode ficar fora das janelas de 30 s (senao 5xx/descartadas ou o p95 por janela podem estar errados)
    if (@($s.inconsistencias).Count -gt 0) {
        Fail ("Resumo do k6 inconsistente ($(@($s.inconsistencias) -join '; ')): as submetricas por degrau nao fecham com o total ou as janelas de 30 s nao representam o degrau, entao 5xx/descartadas ou o p95 por janela podem estar errados e o CSV nao foi gerado. Resumo em $sf.")
    }
    # submetrica sem amostras (ex.: nenhuma confirma com -PctConfirma 0, ou degrau em que toda reserva deu 5xx) vale 0: so informa
    if (@($s.faltando).Count -gt 0) { Info "Submetricas sem amostras (tratadas como 0): $(@($s.faltando) -join ', ')" }

    $linhas = New-Object System.Collections.Generic.List[string]
    foreach ($d in $s.degraus) {
        $linhas.Add((Format-LinhaDegrau -Escada $escada -Degrau $d -DuracaoS $Duracao -Braco $Rotulo `
            -MaxConnections $MaxConnections -PoolHikari $PoolHikari -Commit $commit -Mapeamento $Mapeamento))
    }
    # UTF-8 sem BOM e LF: o report em bash le sem saneamento
    [System.IO.File]::WriteAllText($Csv, ($DegrausCabecalho + "`n" + ($linhas -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
} finally {
    Stop-Telemetria $tel
    # credenciais de teste: apagadas mesmo se o k6 falhar ou o script for interrompido (Ctrl+C)
    Remove-Item $uf, $ef -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host ('  {0,8} {1,8} {2,9} {3,9} {4,9} {5,9} {6,11} {7,7}' -f 'RPM alvo', 'efetivo', '5xx res %', 'p50 ms', 'p95 ms', 'p99 ms', 'p95 jan. ms', 'descart.')
foreach ($l in $linhas) {
    $c = $l -split ','   # posicoes = $DegrausCabecalho (lib-degraus.selftest.ps1 confere)
    Write-Host ('  {0,8} {1,8} {2,9:N2} {3,9} {4,9} {5,9} {6,11} {7,7}' -f $c[1], $c[2], ([double]::Parse($c[6], [System.Globalization.CultureInfo]::InvariantCulture) * 100), $c[9], $c[10], $c[11], $c[12], $c[14])
}
Write-Host ''
$sl = ($s.degraus | Measure-Object -Property sem_login -Sum).Sum
$ap = ($s.degraus | Measure-Object -Property alem_dos_pares -Sum).Sum
$ou = ($s.degraus | Measure-Object -Property outros -Sum).Sum
$ds = ($s.degraus | Measure-Object -Property descartadas -Sum).Sum
$c5 = ($s.degraus | Measure-Object -Property confirma_5xx -Sum).Sum
$fj = 0
foreach ($d in $s.degraus) { $fj += [int]$d.fora_das_janelas }
if ($fj -gt 0) { Warn "$fj reservas ($(@($s.degraus | Where-Object { $_.fora_das_janelas -gt 0 } | ForEach-Object { "$($_.nome): $($_.fora_das_janelas)" }) -join ', ')) ficaram fora das janelas de 30 s (<= 1% do degrau: atraso de agendamento com o gerador saturado?): contam no p50/p95/p99 do degrau, mas nao no p95 por janela." }
if ($sl -gt 0) { Warn "$sl iteracoes sem login (usuarios cujo login falhou): reduzem o RPM efetivo e podem aparecer como saturacao do gerador." }
if ($ap -gt 0) { Warn "$ap iteracoes alem da cota de pares nao foram enviadas (RPM efetivo abaixo do alvo)." }
if ($ou -gt 0) { Warn "$ou respostas inesperadas ou falhas de conexao (outros): nao entram no criterio de X, mas confira." }
if ($c5 -gt 0) { Warn "$c5 respostas 5xx na confirmacao (erros_5xx_confirma): nao entram no criterio de X (so os 5xx da reserva), mas confira." }
if ($ds -gt 0) { Warn 'k6 descartou iteracoes: o alvo nao foi atingido em algum degrau (gerador ou host saturado); o report trata como saturacao do gerador.' }
Info ('Gerador durante a escada: ' + (Get-ResumoTelemetria $TelCsv))
Info "  telemetria bruta: $TelCsv   (host: make telemetria-host, ligado ANTES da escada)"
Write-Host "CSV da escada: $Csv"
Write-Host "  gerar o grafico e o limiar X ... make report-degraus CSV=results\$tag.csv" -ForegroundColor Yellow
Write-Host "  conferir no HOST ................ .\rpm\subir-host-rpm.ps1 -Verificar -Prefixo $escada" -ForegroundColor Yellow
Write-Host "  versionar ....................... results\$tag.csv (e o summary-$tag.json)" -ForegroundColor Yellow
