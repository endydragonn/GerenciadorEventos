<#
.SYNOPSIS
  HOST: prepara um bloco do teste longo (soak, issue #232) que o GERADOR roda SO com `k6 run`: gera o perfil (perfil.py), semeia 2000 usuarios x 2000 eventos (200 vagas no S1, 500 no S2),
  promove o host do seed por SQL e imprime a linha EXATA do gerador. Mesmo modelo do degraus\preparar-degraus.ps1 (o gerador nao semeia nem recebe credenciais).

.DESCRIPTION
  Ordem do bloco (nada daqui cria os pre-requisitos): (0) cluster `soak` no ar COM o gatilho do bloco (k8s\README.md, "Teste longo (soak)": ADMISSION_X_RPM=56000 no S1, 4000 no S2, no
  ambiente do `cluster.sh up`) e o firewall do Mailpit criado (soak\firewall-mailpit.ps1); (1) `limpar-banco` (CARGA_K8S=<contexto>); (2) ESTE script (preparar: perfil + seed);
  (3) `limpar-mailpit`; (4) `verificar-soak.sh pre` (confere o notification, a #231, o gatilho do bloco e que o Mailpit esta vazio). O seed e o de sempre (lib-seed.ps1 ->
  docs\carga\harness\setup.sh, POST /bff/seed com SEED_ENABLED do overlay rodada), so maior: -Usuarios 2000, -Eventos 2000, -Capacidade por BLOCO (padrao S1: 200 = 400 mil vagas, parte dos eventos lota nas ultimas dezenas de minutos; S2: 500 = 1 M; 4 M de pares; a expiracao devolve vagas),
  O parametro POR BLOCO e -Quentes (padrao: 1 no S2, 0 no S1; Get-QuentesDoBloco em lib-soak.ps1; um valor explicito vale):
    S2  -Quentes 1: um 2o seed cria o(s) evento(s) QUENTE(S) (nome <PREFIXO>-Q-E<k>) de -CapacidadeQuente vagas (finita, 20000: holds em aberto = taxa quente x atraso da devolucao; a saga e conferida pelo
        verificar-rpm) e o k6 recebe QUENTES_IDS: PCT_QUENTE % das reservas miram o quente (que so cancela) e fazem o RPM do evento mais quente ligar a fila e o KEDA do reservation (X = 4000).
    S1  -Quentes 0: SEM 2o seed e SEM QUENTES_IDS; a reserva fica ESPALHADA pelos 2000 eventos do seed (mapeamento intercalado do kit, pares unicos), com o mix realista 60% confirma / 10% cancela /
        30% expira e confirmacao de verdade (overselling e confirmacao estressados; X = 56000, a fila nunca liga). Spec, "Decisoes" 2.
  O host do seed (carga-host-<sufixo>@example.com) NAO e host de verdade: nenhuma rota promove usuario, entao o UPDATE em identity.users vai pelo `cluster.sh psql` ANTES do
  login do k6 (que acontece no setup(); sem isso a escrita de catalogo recebe 400).
  -Seco: so gera o perfil e imprime o plano (sem seed, sem SQL, sem gerador): confere parametros e o arquivo do perfil.

.EXAMPLE
  .\soak\preparar-soak.ps1 -Id S1 -Bloco S1 -Teto 35000 -HostIp 192.168.68.64 -MailpitUrl http://192.168.68.64:8025
  .\soak\preparar-soak.ps1 -Id S2 -Bloco S2 -Teto 50000 -HostIp 192.168.68.64 -MailpitUrl http://192.168.68.64:8025
  # dry-runs (spec, criterio 10): S1 = 10 min, teto baixo, X = 56000; S2 = 10 min, teto ~20000, X = 4000 (abaixo de ~17k a fila nao liga no S2), com o perfil FIXO -Fixo dry-s2
  .\soak\preparar-soak.ps1 -Id dryS1 -Bloco S1 -Teto 3000 -Duracao 600 -HostIp 192.168.68.64 -MailpitUrl http://192.168.68.64:8025
  .\soak\preparar-soak.ps1 -Id dryS2 -Bloco S2 -Teto 20000 -Fixo dry-s2 -HostIp 192.168.68.64 -MailpitUrl http://192.168.68.64:8025   # perfil FIXO: nao troque de semente (nao ha)
  # dry longo do Mailpit (portao antes do S2): SO o cadastro, nos primeiros 40 min do S2 (mesma semente/teto do perfil-S2.json), com a caixa pre-enchida; os outros tipos nao rodam
  .\soak\preparar-soak.ps1 -Id dryMailpit -Bloco S2 -Semente 257867 -Teto 50000 -Duracao 2400 -So cadastro -HostIp 192.168.68.64 -MailpitUrl http://192.168.68.64:8025
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9-]+$')][string]$Id,    # S1, S2, dry
    [Parameter(Mandatory)][ValidateSet('S1', 'S2')][string]$Bloco,            # o gatilho do bloco: S1 = ADMISSION_X_RPM 56000, S2 = 4000 (o dry ensaia um dos dois)
    [Parameter(Mandatory)][ValidateRange(100, 1000000)][int]$Teto,            # teto do bloco em RPM (soma dos picos coincidentes)
    [Parameter(Mandatory)][string]$HostIp,                                     # como o GERADOR enxerga o gateway (BASE_URL = http://HostIp:Porta)
    [Parameter(Mandatory)][string]$MailpitUrl,                                 # como o GERADOR enxerga a API do Mailpit (http://<host>:8025)
    [ValidateRange(60, 86400)][int]$Duracao = 7200,
    [int]$Semente = 0,                                                         # 0 = sorteia (e imprime, para repetir o bloco); com -Fixo nao existe semente
    [ValidateSet('', 'cadastro')][string]$So = '',                             # perfil de UM tipo so (perfil.py --so): o dry do Mailpit roda so o cadastro; '' = os 5 tipos
    [ValidateSet('', 'dry-s2')][string]$Fixo = '',                             # perfil FIXO do perfil.py (dry-run do S2: 10 min, 3 subidas vale -> pico na reserva); '' = o perfil sorteado por semente
    [int]$Porta = 8081,
    [string]$SeedUrl = '',                                                     # como o HOST enxerga o gateway (padrao http://localhost:Porta)
    [ValidateRange(1, 100000)][int]$Usuarios = 2000,
    [ValidateRange(1, 100000)][int]$Eventos = 2000,
    [ValidateScript({ $_ -eq -1 -or ($_ -ge 1 -and $_ -le 100000) })][int]$Capacidade = -1,   # vagas por evento comum; -1 = o do bloco (S1: 200, S2: 500; Get-CapacidadeDoBloco)
    [ValidateRange(-1, 10)][int]$Quentes = -1,                                 # eventos quentes (k pequeno, de preferencia 1: hot = taxa de reserva / k); -1 = o do bloco (S2: 1, S1: 0 = reserva espalhada)
    [ValidateRange(2, 1000000)][int]$CapacidadeQuente = 20000,                 # vagas de cada quente: finita, ~84 s do pico de reservas quentes do S2 (spec, "Decisoes" 2: holds em aberto = taxa x atraso da devolucao)
    [ValidateRange(20, 20000)][int]$MaxVus = 2000,
    [ValidateRange(0, 100)][double]$PctAcompanha = 10,
    [string]$Contexto = 'kind-gerenciador',                                    # contexto kubectl do cluster soak (CARGA_K8S)
    [switch]$Seco
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-soak.ps1')
if ($Quentes -lt 0) { $Quentes = if ($So) { 0 } else { Get-QuentesDoBloco $Bloco } }   # perfil de um tipo so (cadastro): sem reserva, sem quentes
if ($Capacidade -lt 0) { $Capacidade = Get-CapacidadeDoBloco $Bloco }
$Results = Join-Path (Split-Path $PSScriptRoot -Parent) 'results'
New-Item -ItemType Directory -Force -Path $Results | Out-Null
if (-not $SeedUrl) { $SeedUrl = "http://localhost:$Porta" }

function Info($m) { Write-Host "[soak] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

if ($MailpitUrl -notmatch '^https?://[^/@\s]+$') { Fail "-MailpitUrl invalido ('$MailpitUrl'): http://<host>:<porta>, SEM usuario:senha na URL (a credencial vai por MAILPIT_AUTH)." }
if ($Usuarios * $Eventos -lt 1000) { Fail 'Poucos pares (usuarios x eventos).' }
$py = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $py) { Fail 'python nao encontrado (o perfil.py e do kit).' }
if ($So -and $Fixo) { Fail "-So $So nao vale com -Fixo (o perfil fixo e sempre o mesmo)." }
if ($Fixo) {
    if ($Bloco -ne 'S2') { Fail "-Fixo $Fixo e do dry-run do S2 (-Bloco S2)." }
    if ($PSBoundParameters.ContainsKey('Semente')) { Fail 'Com -Fixo nao existe semente do perfil (ele e sempre o mesmo): nao troque de semente; se o dry-run der 0 de 202, e defeito do mecanismo, pare e relate.' }
    if ($PSBoundParameters.ContainsKey('Duracao') -and $Duracao -ne 600) { Fail "-Fixo $Fixo dura 600 s (nao passe -Duracao $Duracao)." }
    $Duracao = 600
    $Semente = 1   # so o sorteio do mix do k6 (confirma/cancela/expira); o perfil nao usa semente
} elseif ($Semente -eq 0) { $Semente = Get-Random -Minimum 1 -Maximum 1000000 }

# ---- perfil (deterministico: mesma semente = mesmos bytes)
$argsPerfil = if ($Fixo) { @('--fixo', $Fixo) } else { @('--semente', $Semente, '--duracao', $Duracao) }
if ($So) { $argsPerfil += @('--so', $So) }
& $py.Source (Join-Path $PSScriptRoot 'perfil.py') --id $Id --teto $Teto @argsPerfil --saida $Results
if ($LASTEXITCODE -ne 0) { Fail 'perfil.py falhou.' }
$perfilArq = Join-Path $Results "perfil-$Id.json"
$perfil = Get-Content $perfilArq -Raw | ConvertFrom-Json
Info "Bloco ${Id} (gatilho do $Bloco): teto $Teto RPM, $Duracao s, $(if ($Fixo) { "perfil FIXO $Fixo (sem semente)" } else { "semente $Semente" }); $Capacidade vagas por evento comum; cadastros esperados $($perfil.total_cadastros_esperado) (MP_MAX_MESSAGES do overlay: 250000)."
if ($So) { Info "Perfil de um tipo so ($So): os outros tipos nao rodam (sem reserva, sem quentes, sem fila)." } else { Info "Reserva do bloco: $(if ($Quentes -gt 0) { "$Quentes evento(s) quente(s) (so cancelam; PCT_QUENTE % das reservas) + o resto espalhado" } else { 'ESPALHADA pelos eventos do seed (60% confirma, 10% cancela, 30% expira), sem quentes' })." }
if ($Seco) { Ok "Modo seco: perfil em $perfilArq (nada semeado)."; return }

. (Join-Path (Split-Path $PSScriptRoot -Parent) 'lib-seed.ps1')
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'degraus\lib-degraus.ps1')    # Compress-Ids, Get-LinhaK6

try { Invoke-WebRequest -Uri "$SeedUrl/actuator/health" -UseBasicParsing -TimeoutSec 5 | Out-Null }
catch { if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ge 500) { Fail "Gateway inacessivel ou com erro (>= 500) em $SeedUrl (o seed roda no host)." } }

$runId = [int]((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalSeconds % 1000000
$prefixo = "Soak-$runId"
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("soak-{0}" -f [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    $uf = (Join-Path $tmp 'users.json').Replace('\', '/'); $ef = (Join-Path $tmp 'events.json').Replace('\', '/')
    Info "Semeando $Usuarios usuarios + $Eventos eventos de $Capacidade vagas em $SeedUrl (bcrypt: pode levar varios minutos; uma chamada so)..."
    $seed = Invoke-Seed -BaseUrl $SeedUrl -Usuarios $Usuarios -Eventos $Eventos -Capacidade $Capacidade -Prefixo $prefixo -Sufixo "$runId" -UsersFile $uf -EventIdsFile $ef
    Ok "Prefixo '$prefixo': $Eventos eventos prontos."
    # eventos QUENTES (so no S2): 2o seed (1 usuario so serve de host/criador), nome <prefixo>-Q-E<k>, capacidade finita; ficam sob o prefixo do bloco (o verificar-rpm os cobre)
    $seedQ = $null
    if ($Quentes -gt 0) {
        $uq = (Join-Path $tmp 'users-q.json').Replace('\', '/'); $eq = (Join-Path $tmp 'events-q.json').Replace('\', '/')
        Info "Semeando $Quentes evento(s) quente(s) de $CapacidadeQuente vagas ($prefixo-Q-E1...)."
        $seedQ = Invoke-Seed -BaseUrl $SeedUrl -Usuarios 1 -Eventos $Quentes -Capacidade $CapacidadeQuente -Prefixo "$prefixo-Q" -Sufixo (Get-SufixoQuentes $runId) -UsersFile $uq -EventIdsFile $eq
        Ok "Quentes: $(($seedQ.EventIds) -join ',') ($CapacidadeQuente vagas cada)."
    }
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue   # o arquivo de credenciais nunca sai do host (o k6 as monta pelo SUFIXO)
}

# ---- o host do seed vira host de verdade (nenhuma rota faz isso), pelo banco do cluster, antes do login do k6
$pf = if ($env:ProgramW6432) { $env:ProgramW6432 } else { $env:ProgramFiles }
$bash = Join-Path $pf 'Git\bin\bash.exe'
$clusterSh = (Resolve-Path (Join-Path (Split-Path $PSScriptRoot -Parent) '..\k8s\cluster.sh')).Path
$sql = "UPDATE identity.users SET host = true WHERE email = 'carga-host-$runId@example.com'"
$env:K8S_NOME = $Contexto -replace '^kind-', ''
$saida = & $bash $clusterSh psql -At -c "$sql RETURNING 1" 2>&1
if ($LASTEXITCODE -ne 0 -or (($saida | Out-String) -notmatch '(?m)^1\s*$')) { Fail "Nao consegui promover o host do seed por SQL (cluster.sh psql): $saida" }
Remove-Item Env:K8S_NOME -ErrorAction SilentlyContinue
Ok "Host carga-host-$runId@example.com promovido (identity.users.host = true)."

$envs = [ordered]@{
    BASE_URL = "http://${HostIp}:${Porta}"; SUFIXO = "$runId"; PREFIXO = $prefixo; USUARIOS = "$Usuarios"; EVENT_IDS = (Compress-Ids $seed.EventIds)
    MAILPIT_URL = $MailpitUrl; MAX_VUS = "$MaxVus"; PCT_ACOMPANHA = "$PctAcompanha"; SEMENTE = "$Semente"
}
if ($seedQ) { $envs['QUENTES_IDS'] = (Compress-Ids $seedQ.EventIds) }   # sem a variavel o carga-soak.js espalha a reserva (S1)
$resto = (Get-LinhaK6 -Envs $envs -Script 'Projeto/carga/soak/carga-soak.js') -replace '^k6 run --quiet ', ''
# k6 >= 1.3 (o gerador tem a v2.2.0): --summary-mode=disabled (--no-summary e K6_NO_SUMMARY foram REMOVIDOS na v2.0.0); o CSV bruto sai em .gz (vai a varios GB por bloco; o agregar.py le o .gz)
$linha = "k6 run --quiet --no-thresholds --summary-mode=disabled --out csv=soak-$Id-k6.csv.gz -e `"PERFIL_FILE=`$perfil`" $resto"

Write-Host ''
Write-Host 'COMMIT O PERFIL (ou o gerador o regenera: python Projeto/carga/soak/perfil.py --id ' -NoNewline -ForegroundColor Yellow
Write-Host "$Id $(if ($Fixo) { "--fixo $Fixo --teto $Teto" } else { "--semente $Semente --teto $Teto --duracao $Duracao" })$(if ($So) { " --so $So" }) --saida Projeto/carga/results)" -ForegroundColor Yellow
Write-Host "  $perfilArq"
Write-Host ''
Write-Host 'NO GERADOR (Windows PowerShell, na raiz do clone, depois do firewall e do cluster no ar; a credencial e DIGITADA, nunca em argumento):' -ForegroundColor Yellow
Write-Host "  `$s = Read-Host 'MAILPIT_AUTH (usuario:senha do cluster.sh up)' -AsSecureString   # a MESMA do MAILPIT_AUTH do cluster.sh up; sem eco na tela"
Write-Host "  `$b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR(`$s); `$env:MAILPIT_AUTH = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(`$b); [Runtime.InteropServices.Marshal]::ZeroFreeBSTR(`$b); Remove-Variable s, b"
Write-Host "  `$perfil = (Resolve-Path 'Projeto/carga/results/perfil-$Id.json').Path.Replace('\','/')"
Write-Host "  $linha"
Write-Host ''
Write-Host "O IP do gerador muda por DHCP: antes de cada bloco rode no GERADOR  w32tm /stripchart /computer:time.windows.com /samples:1 /dataonly  (cole o numero apos a virgula como o w32tm imprime; o pre cuida do sinal) e ipconfig (o IP atual); se o IP mudou, rode o firewall-mailpit.ps1 -Acao Criar de novo com o IP novo (Administrador) ANTES do pre." -ForegroundColor Yellow
Write-Host "No HOST, antes do k6: make limpar-mailpit ; make verificar-soak-pre SOAK_ID=$Id SOAK_BLOCO=$Bloco GERADOR_IP=<ip atual do gerador> GERADOR_OFFSET_S=<offset do w32tm no gerador> ; e, em um terminal proprio durante o bloco, make amostrar-soak SOAK_ID=$Id (e make coleta-host ROTULO=soak-$Id), tudo com CARGA_K8S=$Contexto e MAILPIT_AUTH no ambiente." -ForegroundColor Yellow
Write-Host "Ao fim, no GERADOR (Python 3.14.7 do proprio gerador; o agregar.py le o .gz por streaming): python Projeto/carga/soak/agregar.py soak-$Id-k6.csv.gz --id $Id ; copie o por-minuto para o host e rode: make verificar-soak-pos SOAK_ID=$Id SOAK_BLOCO=$Bloco SOAK_SUFIXO=$runId SOAK_PREFIXO=$prefixo" -ForegroundColor Yellow
Write-Host "SUFIXO=$runId  PREFIXO=$prefixo  SEMENTE=$Semente"
