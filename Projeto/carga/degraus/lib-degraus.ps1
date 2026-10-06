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

$DegrausCabecalho = 'escada,rpm_alvo,rpm_efetivo,duracao_s,reservas,erros_5xx,taxa_5xx,erros_5xx_confirma,outros,p50,p95,p99,p95_max_janela_30s,throughput,iteracoes_descartadas,braco,max_connections,pool_hikari,commit'

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
        [Parameter(Mandatory)][string]$Commit
    )
    if ($null -eq $Degrau.p95_max_janela_ms) { throw 'Resumo do degrau sem p95_max_janela_ms: resumo de outra versao do carga-degraus.js?' }
    $reservas = [double]$Degrau.reservas
    $erros = [double]$Degrau.erros_5xx   # so os 5xx da reserva (os da confirma vao em erros_5xx_confirma, fora da taxa)
    $taxa = 0.0
    if ($reservas -gt 0) { $taxa = $erros / $reservas }
    [string]::Format([System.Globalization.CultureInfo]::InvariantCulture,
        '{0},{1},{2:F0},{3},{4},{5},{6:F6},{7},{8},{9:F1},{10:F1},{11:F1},{12:F1},{13:F1},{14},{15},{16},{17},{18}',
        $Escada, $Degrau.rpm_alvo, ($reservas / $DuracaoS * 60), $DuracaoS, [long]$reservas, [long]$erros, $taxa,
        [long]$Degrau.confirma_5xx, $Degrau.outros, $Degrau.p50_ms, $Degrau.p95_ms, $Degrau.p99_ms,
        [double]$Degrau.p95_max_janela_ms, (($reservas - $erros) / $DuracaoS), $Degrau.descartadas,
        $Braco, $MaxConnections, $PoolHikari, $Commit)
}
