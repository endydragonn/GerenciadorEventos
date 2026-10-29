# Logica pura da escada de carga (issue #14), usada por rodar-degraus.ps1 e testada por
# lib-degraus.selftest.ps1. Uso: . .\lib-degraus.ps1
#
# CSV (uma linha por degrau, um arquivo por escada). O criterio de X (5xx das reservas > 1% ou p95 > 1 s numa janela
# de 30 s) NAO mora aqui: e decidido so no report (docs\carga\harness\report-degraus.sh), a partir destas colunas.
#   rpm_efetivo           = reservas enviadas no degrau / duracao * 60 (cai abaixo do alvo se o k6 descartou iteracoes)
#   reservas              = reservas respondidas no degrau (o denominador da taxa)
#   erros_5xx             = 5xx da RESERVA; taxa_5xx = erros_5xx / reservas (fracao: 0.01 = 1%). So a reserva: X decide quando
#                           a reserva iria para a fila, e as confirmacoes diluiriam a taxa
#   erros_5xx_confirma    = 5xx da confirma: so informativo, NAO entra em X nem na taxa
#   outros                = respostas inesperadas da reserva (4xx fora do "lotado", timeout, conexao): NAO entram em X
#   p50/p95/p99           = latencia da reserva no degrau inteiro (ms), do k6
#   p95_max_janela_30s    = maior p95 da reserva entre as janelas de 30 s do degrau (ms); -1 = sem janela. Vem pronto do
#                           resumo do k6 (p95_max_janela_ms): cada reserva e tagueada pela janela do instante de ENVIO
#   throughput            = reservas/s respondidas sem 5xx (reservas - 5xx da reserva) / duracao
#   iteracoes_descartadas = dropped_iterations do k6 no degrau
#   max_connections / pool_hikari / braco = do HOST (o gerador nao os enxerga; so registra o que foi informado)
#   mapeamento            = como a iteracao vira o par (usuario, evento): sequencial | intercalado (degraus/lib-mapeamento.js).
#                           Ultima coluna: CSV antigo (sem ela) continua valido para o report, que mostra "n/d"

$DegrausCabecalhoBase = 'escada,rpm_alvo,rpm_efetivo,duracao_s,reservas,erros_5xx,taxa_5xx,erros_5xx_confirma,outros,p50,p95,p99,p95_max_janela_30s,throughput,iteracoes_descartadas,braco,max_connections,pool_hikari,commit,mapeamento'

# Colunas da escada ACIMA do X com o admission ligado (issue #178), SEMPRE no fim (o report le por nome; CSV antigo, sem elas, segue valendo):
#   arranjo               = rotulo do operador: sem-autoescala | autoescala (braco C), com +slim se as imagens forem as slim. Do HOST, so registrado
#   pct_acompanha, consulta_ms = fracao das 202 que acompanham o estado e intervalo da consulta (a resolucao de admitir_*)
#   reservas_202          = POST que responderam 202 (modo fila): contam como ACEITAS, nunca em `outros`
#   acompanhadas          = 202 que acompanharam o estado; admitidas (-> pending) + rejected + timed_out + sem_estado (teto da consulta) = acompanhadas
#   taxa_rejected, taxa_timed_out = fracao sobre as ACOMPANHADAS (0 sem acompanhadas)
#   recusadas_429, conflitos_422  = POST recusados pelo admission (limite por usuario / fila cheia; chave de outro evento): nao entram em outros
#   consultas_estado      = GET de estado feitos (somam carga no gateway)
#   admitir_*             = tempo 202 -> pending (ms); fim_a_fim_* = envio -> resultado final (ms; 201 + confirma, ou estado final + confirma)
#   parada                = 'nenhuma' ou o motivo da parada antecipada (p95 acima do limite numa janela); o degrau da parada tem duracao_s EFETIVA
#   params_autoescala     = parametros provisorios do HOST (AUTOESCALA_*, X) informados pelo operador (; entre pares): a R4 calibra
$AdmissaoCabecalho = 'arranjo,pct_acompanha,consulta_ms,reservas_202,acompanhadas,admitidas,rejected,timed_out,sem_estado,taxa_rejected,taxa_timed_out,recusadas_429,conflitos_422,consultas_estado,admitir_p50,admitir_p95,admitir_p99,fim_a_fim_p50,fim_a_fim_p95,fim_a_fim_p99,parada,params_autoescala'
$DegrausCabecalho = "$DegrausCabecalhoBase,$AdmissaoCabecalho"

# gracefulStop dos degraus (s): a iteracao acompanhada vive ate o teto da consulta. Mesma conta de gracefulS() em degraus/lib-admissao.js
# (os dois selftests conferem a mesma tabela). Sem acompanhamento (PctAcompanha 0) fica o 30 s de sempre. O espaco entre degraus = graceful + 1.
function Get-GracefulS {
    param([Parameter(Mandatory)][double]$PctAcompanha, [Parameter(Mandatory)][int]$TetoConsultaS)
    if ($PctAcompanha -gt 0) { return [int][math]::Max(30, $TetoConsultaS + 10) }
    30
}
function Get-EspacoS {
    param([Parameter(Mandatory)][double]$PctAcompanha, [Parameter(Mandatory)][int]$TetoConsultaS)
    (Get-GracefulS -PctAcompanha $PctAcompanha -TetoConsultaS $TetoConsultaS) + 1
}

# Orcamento do setup() (s) para a parada por latencia: o delayAbortEval do k6 conta o setup (login de todos os usuarios, em lotes de 25), entao o
# setup() espera o relogio do teste chegar nele (SETUP_S) e a parada soma o mesmo valor. Estimativa por lotes de 25 logins (bcrypt): 0,6 s por lote
# (medido no dry-run local da #178: 2000 logins em 20,6 s = 0,26 s por lote, ~2,3x de folga), piso 30 s. Orcamento de mais so atrasa o inicio dos degraus;
# se o setup passar do orcamento o k6 aborta ANTES da carga dizendo quanto levou (rode de novo com -SetupS maior). No host e gerador reais confira o
# `logins levaram X s` do log do k6 e ajuste -SetupS.
$SetupPorLoteS = 0.6
function Get-SetupS {
    param([Parameter(Mandatory)][int]$Usuarios)
    [int][math]::Max(30, [math]::Ceiling($Usuarios / 25.0) * $SetupPorLoteS)
}

# Degraus (RpmInicio, RpmInicio + RpmPasso, ... <= RpmMax), cota de pares (usuario, evento) de cada um e
# tamanho do seed. O k6 reinicia scenario.iterationInTest em 0 a cada cenario: cada degrau usa a faixa
# [soma das cotas anteriores, + cota) dos pares, entao a soma das cotas e o total de pares a semear.
# Cota = ceil(rpm * duracao / 60) + 1 (o executor constant-arrival-rate pode emitir 1 iteracao a mais).
# Eventos = ceil(total * 1.05 / usuarios): 5% de folga, como o teste 2. A mesma conta esta em carga-degraus.js.
function Get-PlanoEscada {
    param(
        [Parameter(Mandatory)][int]$RpmInicio,
        [Parameter(Mandatory)][int]$RpmPasso,
        [Parameter(Mandatory)][int]$RpmMax,
        [Parameter(Mandatory)][int]$DuracaoS,
        [Parameter(Mandatory)][int]$Usuarios
    )
    if ($RpmPasso -lt 1) { throw 'RpmPasso (passo) deve ser >= 1.' }
    if ($RpmInicio -lt 1 -or $RpmInicio -gt $RpmMax) { throw 'RpmInicio deve ser >= 1 e <= RpmMax (o maximo).' }
    if ($DuracaoS -lt 60 -or $DuracaoS % 30 -ne 0) { throw 'DuracaoS deve ser >= 60 e multiplo de 30 (janelas de 30 s inteiras).' }
    $rpms = @()
    for ($r = $RpmInicio; $r -le $RpmMax; $r += $RpmPasso) { $rpms += $r }
    if ($rpms.Count -gt 40) { throw "A escada teria $($rpms.Count) degraus (maximo 40): confira RpmPasso/RpmMax." }
    $cotas = @($rpms | ForEach-Object { [int][math]::Ceiling($_ * $DuracaoS / 60.0) + 1 })
    $total = ($cotas | Measure-Object -Sum).Sum
    [pscustomobject]@{
        Rpms       = [int[]]$rpms
        Cotas      = [int[]]$cotas
        TotalPares = [int]$total
        Eventos    = [int][math]::Ceiling($total * 1.05 / $Usuarios)
    }
}

# Capacidade por evento quando o runner recebe -Capacidade 0 (padrao). sequencial: Usuarios/2, como o teste 2 (cada evento
# recebe U reservas seguidas e metade vira "lotado", de proposito). intercalado: Usuarios, porque o evento recebe a u-esima
# reserva na "rodada" u (u = 0..U-1): com Usuarios/2 todos os eventos lotariam juntos no meio da escada e a segunda metade
# viraria 100% "lotado" (X inflado). Com Usuarios, cada evento aceita no maximo U reservas (uma por usuario): nenhuma lotada.
function Get-CapacidadePadrao {
    param(
        [Parameter(Mandatory)][int]$Usuarios,
        [Parameter(Mandatory)][string]$Mapeamento
    )
    if ($Mapeamento -eq 'intercalado') { return $Usuarios }
    [math]::Max(1, [int]($Usuarios / 2))
}

# Linha do CSV de um degrau. $Degrau = objeto do resumo do k6 (carga-degraus.js: reservas,
# erros_5xx, confirma_5xx, outros, descartadas, p50_ms, p95_ms, p99_ms, p95_max_janela_ms). Numeros pela
# cultura invariante (ponto decimal), mesmo em Windows pt-BR.
function Format-LinhaDegrau {
    param(
        [Parameter(Mandatory)][string]$Escada,
        [Parameter(Mandatory)]$Degrau,
        [Parameter(Mandatory)][int]$DuracaoS,
        [Parameter(Mandatory)][string]$Braco,
        [Parameter(Mandatory)][int]$MaxConnections,
        [Parameter(Mandatory)][int]$PoolHikari,
        [Parameter(Mandatory)][string]$Commit,
        [Parameter(Mandatory)][string]$Mapeamento
    )
    if ($null -eq $Degrau.p95_max_janela_ms) { throw 'Resumo do degrau sem p95_max_janela_ms: resumo de outra versao do carga-degraus.js?' }
    $reservas = [double]$Degrau.reservas
    $erros = [double]$Degrau.erros_5xx   # so os 5xx da reserva (os da confirma vao em erros_5xx_confirma, fora da taxa)
    $taxa = 0.0
    if ($reservas -gt 0) { $taxa = $erros / $reservas }
    [string]::Format([System.Globalization.CultureInfo]::InvariantCulture,
        '{0},{1},{2:F0},{3},{4},{5},{6:F6},{7},{8},{9:F1},{10:F1},{11:F1},{12:F1},{13:F1},{14},{15},{16},{17},{18},{19}',
        $Escada, $Degrau.rpm_alvo, ($reservas / $DuracaoS * 60), $DuracaoS, [long]$reservas, [long]$erros, $taxa,
        [long]$Degrau.confirma_5xx, $Degrau.outros, $Degrau.p50_ms, $Degrau.p95_ms, $Degrau.p99_ms,
        [double]$Degrau.p95_max_janela_ms, (($reservas - $erros) / $DuracaoS), $Degrau.descartadas,
        $Braco, $MaxConnections, $PoolHikari, $Commit, $Mapeamento)
}

# Colunas do modo fila (as de $AdmissaoCabecalho, nesta ordem) de um degrau do resumo do k6. Campo ausente (resumo de um k6 sem o modo fila) vale 0.
# Texto livre (arranjo, parada, params) sem virgula/quebra de linha: so letras, digitos e . _ - + = ; : (o resto vira _).
function Format-ColunasAdmissao {
    param(
        [Parameter(Mandatory)]$Degrau,
        [Parameter(Mandatory)][string]$Arranjo,
        [Parameter(Mandatory)][double]$PctAcompanha,
        [Parameter(Mandatory)][int]$ConsultaMs,
        [Parameter(Mandatory)][string]$Parada,
        [string]$ParamsAutoescala = ''
    )
    function N($x) { if ($null -eq $x) { 0 } else { $x } }
    $limpa = { param($t) ($t -replace '[^A-Za-z0-9._+=;:-]', '_') }
    $ac = [double](N $Degrau.acompanhadas)
    $txRej = 0.0; $txTo = 0.0
    if ($ac -gt 0) { $txRej = (N $Degrau.rejected) / $ac; $txTo = (N $Degrau.timed_out) / $ac }
    [string]::Format([System.Globalization.CultureInfo]::InvariantCulture,
        '{0},{1},{2},{3},{4},{5},{6},{7},{8},{9:F6},{10:F6},{11},{12},{13},{14:F1},{15:F1},{16:F1},{17:F1},{18:F1},{19:F1},{20},{21}',
        (& $limpa $Arranjo), $PctAcompanha, $ConsultaMs, [long](N $Degrau.reservas_202), [long]$ac, [long](N $Degrau.admitidas),
        [long](N $Degrau.rejected), [long](N $Degrau.timed_out), [long](N $Degrau.sem_estado), $txRej, $txTo,
        [long](N $Degrau.recusadas_429), [long](N $Degrau.conflitos_422), [long](N $Degrau.consultas_estado),
        [double](N $Degrau.admitir_p50_ms), [double](N $Degrau.admitir_p95_ms), [double](N $Degrau.admitir_p99_ms),
        [double](N $Degrau.fim_a_fim_p50_ms), [double](N $Degrau.fim_a_fim_p95_ms), [double](N $Degrau.fim_a_fim_p99_ms),
        (& $limpa $Parada), (& $limpa $ParamsAutoescala))
}

# ---------------------------------------------------------------------------------------------------------------------------------------------
# Resumo do k6 -> CSV (compartilhado por rodar-degraus.ps1, que roda tudo no gerador, e por csv-do-resumo.ps1, que roda no HOST sobre o resumo
# que o gerador da madrugada produziu so com `k6 run`; ver README, "Rodada 4 no gerador").

# O resumo tem de ser da escada do plano: o k6 grava o RUN_ID que recebeu na linha (-e RUN_ID=...). Sem isso, duas escadas com os mesmos parametros
# (duracao/usuarios/eventos) se confundiriam e o CSV sairia rotulado com os dados de outra.
function Get-FalhaRunId {
    param([Parameter(Mandatory)]$Resumo, [Parameter(Mandatory)]$RunId)
    if ($null -eq $Resumo.run_id -or "$($Resumo.run_id)" -eq '') { return "O resumo nao traz run_id (k6 rodado sem -e RUN_ID=$RunId?): nao da para provar que e desta escada." }
    if ("$($Resumo.run_id)" -ne "$RunId") { return "O resumo e de outra escada: run_id $($Resumo.run_id) != $RunId do plano." }
    return $null
}

# Motivo de recusar o resumo (texto) ou $null. 1) a trava de consistencia do k6 (global = soma dos degraus; janelas de 30 s); 2) sem `parada`
# registrada, a duracao do teste tem de cobrir os degraus (senao o k6 foi interrompido: o codigo de saida nao chega ao HOST, entao a
# conferencia sai do resumo, que vale com ou sem o codigo).
function Get-FalhaResumo {
    param([Parameter(Mandatory)]$Resumo)
    if (@($Resumo.inconsistencias).Count -gt 0) {
        return "Resumo do k6 inconsistente ($(@($Resumo.inconsistencias) -join '; ')): as submetricas por degrau nao fecham com o total ou as janelas de 30 s nao representam o degrau, entao 5xx/descartadas ou o p95 por janela podem estar errados."
    }
    if ($null -eq $Resumo.parada) {
        $n = @($Resumo.degraus).Count
        $esperado = $n * [double]$Resumo.duracao_degrau_s + ($n - 1) * ([double]$Resumo.graceful_s + 1)
        if ([double]$Resumo.duracao_s -lt 0.95 * $esperado) {
            return ("k6 interrompido: o teste durou {0:N0} s e os {1} degraus pedem >= {2:N0} s (sem parada registrada)." -f [double]$Resumo.duracao_s, $n, $esperado)
        }
    }
    return $null
}

# Linhas do CSV (uma por degrau; os `omitido` pela parada nunca rodaram e nao viram linha). O parcial usa a duracao EFETIVA (senao o rpm_efetivo
# cairia e o report diria "saturou").
function Get-LinhasCsvDegraus {
    param(
        [Parameter(Mandatory)]$Resumo, [Parameter(Mandatory)][string]$Escada, [Parameter(Mandatory)][int]$DuracaoS,
        [Parameter(Mandatory)][string]$Braco, [Parameter(Mandatory)][int]$MaxConnections, [Parameter(Mandatory)][int]$PoolHikari,
        [Parameter(Mandatory)][string]$Commit, [Parameter(Mandatory)][string]$Mapeamento, [Parameter(Mandatory)][string]$Arranjo,
        [Parameter(Mandatory)][double]$PctAcompanha, [Parameter(Mandatory)][int]$ConsultaMs, [string]$ParamsAutoescala = ''
    )
    $paradaTxt = if ($null -ne $Resumo.parada_texto) { [string]$Resumo.parada_texto } else { 'nenhuma' }
    $linhas = New-Object System.Collections.Generic.List[string]
    foreach ($d in $Resumo.degraus) {
        if ($d.situacao -eq 'omitido') { continue }
        $dur = if ($d.situacao -eq 'parcial') { [int]$d.duracao_efetiva_s } else { $DuracaoS }
        $l = Format-LinhaDegrau -Escada $Escada -Degrau $d -DuracaoS $dur -Braco $Braco `
            -MaxConnections $MaxConnections -PoolHikari $PoolHikari -Commit $Commit -Mapeamento $Mapeamento
        $l += ',' + (Format-ColunasAdmissao -Degrau $d -Arranjo $Arranjo -PctAcompanha $PctAcompanha -ConsultaMs $ConsultaMs -Parada $paradaTxt -ParamsAutoescala $ParamsAutoescala)
        $linhas.Add($l)
    }
    return , $linhas.ToArray()
}

# UTF-8 sem BOM e LF: o report em bash le sem saneamento
function Write-CsvDegraus {
    param([Parameter(Mandatory)][string]$Csv, [Parameter(Mandatory)][string[]]$Linhas)
    [System.IO.File]::WriteAllText($Csv, ($DegrausCabecalho + "`n" + ($Linhas -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
}

# Tabela por degrau e os avisos do resumo (o que o rodar-degraus.ps1 imprimia depois do CSV)
function Show-ResumoDegraus {
    param([Parameter(Mandatory)][string[]]$Linhas, [Parameter(Mandatory)]$Resumo, [Parameter(Mandatory)][int]$TetoConsultaS)
    $s = $Resumo
    function Aviso($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
    Write-Host ''
    Write-Host ('  {0,8} {1,8} {2,9} {3,9} {4,9} {5,9} {6,11} {7,7}' -f 'RPM alvo', 'efetivo', '5xx res %', 'p50 ms', 'p95 ms', 'p99 ms', 'p95 jan. ms', 'descart.')
    foreach ($l in $Linhas) {
        $c = $l -split ','   # posicoes = $DegrausCabecalho (lib-degraus.selftest.ps1 confere)
        Write-Host ('  {0,8} {1,8} {2,9:N2} {3,9} {4,9} {5,9} {6,11} {7,7}' -f $c[1], $c[2], ([double]::Parse($c[6], [System.Globalization.CultureInfo]::InvariantCulture) * 100), $c[9], $c[10], $c[11], $c[12], $c[14])
    }
    Write-Host ''
    $s2 = @($s.degraus | Where-Object { $_.situacao -ne 'omitido' })
    $f202 = ($s2 | Measure-Object -Property reservas_202 -Sum).Sum
    if ($f202 -gt 0) {
        $acs = ($s2 | Measure-Object -Property acompanhadas -Sum).Sum; $rj = ($s2 | Measure-Object -Property rejected -Sum).Sum
        $tos = ($s2 | Measure-Object -Property timed_out -Sum).Sum; $ses = ($s2 | Measure-Object -Property sem_estado -Sum).Sum
        $r429 = ($s2 | Measure-Object -Property recusadas_429 -Sum).Sum; $r422 = ($s2 | Measure-Object -Property conflitos_422 -Sum).Sum
        Write-Host "[degraus] Modo fila: $f202 respostas 202; acompanhadas $acs (rejected $rj, timed_out $tos, sem estado no teto $ses); 429: $r429; 422: $r422." -ForegroundColor Cyan
        if ($r429 -gt 0) { Aviso "$r429 respostas 429 (limite por usuario na fila ou fila cheia): contadas a parte, NAO em 'outros'. Se vierem do limite por usuario, aumente -Usuarios." }
        if ($r422 -gt 0) { Aviso "$r422 respostas 422 (chave de idempotencia de outro evento): nao deveria ocorrer com chave unica por iteracao." }
        if ($ses -gt 0) { Aviso "$ses reservas enfileiradas sem estado final dentro do teto da consulta ($TetoConsultaS s)." }
    }
    $sl = ($s.degraus | Measure-Object -Property sem_login -Sum).Sum
    $ap = ($s.degraus | Measure-Object -Property alem_dos_pares -Sum).Sum
    $ou = ($s.degraus | Measure-Object -Property outros -Sum).Sum
    $ds = ($s.degraus | Measure-Object -Property descartadas -Sum).Sum
    $c5 = ($s.degraus | Measure-Object -Property confirma_5xx -Sum).Sum
    $fj = 0
    foreach ($d in $s.degraus) { $fj += [int]$d.fora_das_janelas }
    if ($fj -gt 0) { Aviso "$fj reservas ($(@($s.degraus | Where-Object { $_.fora_das_janelas -gt 0 } | ForEach-Object { "$($_.nome): $($_.fora_das_janelas)" }) -join ', ')) ficaram fora das janelas de 30 s (<= 1% do degrau: atraso de agendamento com o gerador saturado?): contam no p50/p95/p99 do degrau, mas nao no p95 por janela." }
    if ($sl -gt 0) { Aviso "$sl iteracoes sem login (usuarios cujo login falhou): reduzem o RPM efetivo e podem aparecer como saturacao do gerador." }
    if ($ap -gt 0) { Aviso "$ap iteracoes alem da cota de pares nao foram enviadas (RPM efetivo abaixo do alvo)." }
    if ($ou -gt 0) { Aviso "$ou respostas inesperadas ou falhas de conexao (outros): nao entram no criterio de X, mas confira." }
    if ($c5 -gt 0) { Aviso "$c5 respostas 5xx na confirmacao (erros_5xx_confirma): nao entram no criterio de X (so os 5xx da reserva), mas confira." }
    if ($ds -gt 0) { Aviso 'k6 descartou iteracoes: o alvo nao foi atingido em algum degrau (gerador ou host saturado); o report trata como saturacao do gerador.' }
}

# ---------------------------------------------------------------------------------------------------------------------------------------------
# Escada no gerador SO com `k6 run` (README, "Rodada 4 no gerador"): o HOST semeia e imprime a linha pronta.

# [101,102,103,110] -> "101-103,110" (o EVENT_IDS do k6; mantem a linha curta com centenas de eventos)
function Compress-Ids {
    param([Parameter(Mandatory)][int[]]$Ids)
    $s = @($Ids | Sort-Object -Unique)
    $partes = New-Object System.Collections.Generic.List[string]
    $i = 0
    while ($i -lt $s.Count) {
        $j = $i
        while ($j + 1 -lt $s.Count -and $s[$j + 1] -eq $s[$j] + 1) { $j++ }
        $partes.Add($(if ($j -gt $i) { "$($s[$i])-$($s[$j])" } else { "$($s[$i])" }))
        $i = $j + 1
    }
    $partes -join ','
}

# A linha que o gerador digita no Windows PowerShell 5.1: so `k6 run`, um `-e 'CHAVE=valor'` por variavel (aspas SIMPLES: a virgula do RPMS nao vira
# dois argumentos, o `;` do params nao encerra o comando, e o PS 5.1 nao come aspas). Valor com aspa ou quebra de linha e recusado.
function Get-LinhaK6 {
    param([Parameter(Mandatory)][System.Collections.Specialized.OrderedDictionary]$Envs, [Parameter(Mandatory)][string]$Script)
    $partes = @('k6 run --quiet')
    foreach ($k in $Envs.Keys) {
        $v = [string]$Envs[$k]
        if ($v -match "['`"\r\n]") { throw "Valor de $k com aspas ou quebra de linha: nao cabe numa linha digitada." }
        $partes += "-e '$k=$v'"
    }
    $partes += $Script
    $partes -join ' '
}
