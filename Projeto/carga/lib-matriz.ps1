# Logica pura da matriz do experimento (issue #11), usada por rodar-matriz.ps1 e testada por
# lib-matriz.selftest.ps1. Uso: . .\lib-matriz.ps1
#
# Colunas do CSV (uma linha por execucao): oversell = max(0, confirmed - capacity) e a metrica
# primaria; pending + confirmed e derivavel (secundaria). lag_obs_* = applied_at - last_occurred_at da
# projecao de disponibilidade do evento (lag real do consumer, issue #29) no Postgres do host, em ms; -1 = sem
# leitura (a linha da projecao ainda nao existia ou nenhuma amostra caiu na janela). lag_ms vale 0: o lag nao e mais
# configurado, e medido.

$MatrizCabecalho = 'braco,lag_ms,N,capacity,rep,confirmed,pending,oversell,p50,p95,p99,throughput,lag_obs_disparo,lag_obs_max,commit'

# Lag observado de uma rajada a partir da serie de amostras do polling.
#   Amostras: @{ Ts = epoch ms do GERADOR (mesmo relogio do k6); Lag = ms, ou -1 = sem leitura }
#   DisparoMs / FimMs: primeira iteracao iniciada e ultima terminada (vem do resumo do k6).
# Disparo = lag da ultima amostra valida em Ts <= DisparoMs. Max = maior lag entre essa amostra
# e as amostras validas em [DisparoMs, FimMs]. Resolucao = intervalo do polling (a amostra pode
# ser anterior ao disparo; uma rajada curta pode nao ter nenhuma amostra propria).
function Get-LagRajada {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Amostras,
        [Parameter(Mandatory)][long]$DisparoMs,
        [Parameter(Mandatory)][long]$FimMs
    )
    $validas = @($Amostras | Where-Object { $_.Lag -ge 0 })
    $antes = $validas | Where-Object { $_.Ts -le $DisparoMs } | Sort-Object Ts | Select-Object -Last 1
    $janela = @($validas | Where-Object { $_.Ts -ge $DisparoMs -and $_.Ts -le $FimMs })
    $disparo = -1
    $max = -1
    if ($antes) { $disparo = [int]$antes.Lag; $max = $disparo }
    foreach ($a in $janela) { if ([int]$a.Lag -gt $max) { $max = [int]$a.Lag } }
    [pscustomobject]@{ Disparo = $disparo; Max = $max }
}

# Validade de uma repeticao: so repeticao valida vira linha de results\matriz.csv (uma invalida
# enviesaria a media/desvio do overselling sem deixar rastro). Resumo = JSON do carga.js; com
# PCT_CONFIRMA=100, toda reserva aceita tem de ser confirmada. Devolve @{ Valida; Motivos[] }.
function Test-RepeticaoValida {
    param([Parameter(Mandatory)]$Resumo, [Parameter(Mandatory)][int]$CodigoK6)
    $m = New-Object System.Collections.Generic.List[string]
    if ($CodigoK6 -ne 0) { $m.Add("k6 terminou com codigo $CodigoK6") }
    if (-not ($Resumo.disparo_ms -gt 0)) { $m.Add('resumo sem disparo_ms (nenhuma iteracao rodou: setup() abortou?)') }
    if ($Resumo.sem_login -gt 0) { $m.Add("usuarios sem login: $($Resumo.sem_login)") }
    if ($Resumo.outros -gt 0) { $m.Add("respostas inesperadas/5xx na reserva: $($Resumo.outros)") }
    if ($Resumo.confirma_5xx -gt 0 -or $Resumo.confirma_outros -gt 0) {
        $m.Add("erros no confirma (5xx: $($Resumo.confirma_5xx), outros: $($Resumo.confirma_outros))")
    }
    if ($Resumo.confirmadas -lt $Resumo.aceitas) { $m.Add("confirmadas ($($Resumo.confirmadas)) < aceitas ($($Resumo.aceitas))") }
    [pscustomobject]@{ Valida = ($m.Count -eq 0); Motivos = $m.ToArray() }
}

# Tolerancia 0 do braco CAS (issue #12): a execucao FALHA se confirmed > capacity (overselling,
# metrica primaria) OU pending + confirmed > capacity (secundaria). Igual a capacity passa. Nao e
# criterio de validade: uma execucao CAS que oversella e um RESULTADO (bug do CAS) e entra no CSV.
# Devolve @{ Ok; Motivos[] }; o runner so aplica quando o braco e cas.
function Test-ToleranciaCas {
    param(
        [Parameter(Mandatory)][int]$Confirmed,
        [Parameter(Mandatory)][int]$Pending,
        [Parameter(Mandatory)][int]$Capacity
    )
    $m = New-Object System.Collections.Generic.List[string]
    if ($Confirmed -gt $Capacity) { $m.Add("confirmed ($Confirmed) > capacity ($Capacity)") }
    if ($Pending + $Confirmed -gt $Capacity) { $m.Add("pending + confirmed ($($Pending + $Confirmed)) > capacity ($Capacity)") }
    [pscustomobject]@{ Ok = ($m.Count -eq 0); Motivos = $m.ToArray() }
}

# Vazao (reservas/s) so da janela da rajada [DisparoMs, FimMs]: tentativas = aceitas + lotado +
# outros. NAO usar o duracao_s/reservas_por_s do k6: o testRunDurationMs inclui o setup() com os
# logins (bcrypt), que dominam o tempo (~10 ms por cliente, contra uma rajada de ~0,1-0,3 s).
# 0 se a janela for vazia ou o resumo nao trouxe disparo_ms/fim_ms.
function Get-VazaoRajada {
    param(
        [Parameter(Mandatory)][double]$Tentativas,
        [Parameter(Mandatory)][long]$DisparoMs,
        [Parameter(Mandatory)][long]$FimMs
    )
    $janelaS = ($FimMs - $DisparoMs) / 1000.0
    if ($DisparoMs -le 0 -or $janelaS -le 0) { return 0.0 }
    return $Tentativas / $janelaS
}

# Linha do CSV. Numeros pela cultura invariante (ponto decimal), mesmo em Windows pt-BR.
function Format-LinhaMatriz {
    param(
        [Parameter(Mandatory)][string]$Braco,
        [Parameter(Mandatory)][int]$LagMs,
        [Parameter(Mandatory)][int]$N,
        [Parameter(Mandatory)][int]$Capacity,
        [Parameter(Mandatory)][int]$Rep,
        [Parameter(Mandatory)][int]$Confirmed,
        [Parameter(Mandatory)][int]$Pending,
        [Parameter(Mandatory)][double]$P50,
        [Parameter(Mandatory)][double]$P95,
        [Parameter(Mandatory)][double]$P99,
        [Parameter(Mandatory)][double]$Throughput,
        [Parameter(Mandatory)][int]$LagDisparo,
        [Parameter(Mandatory)][int]$LagMax,
        [Parameter(Mandatory)][string]$Commit
    )
    $oversell = [math]::Max(0, $Confirmed - $Capacity)
    [string]::Format([System.Globalization.CultureInfo]::InvariantCulture,
        '{0},{1},{2},{3},{4},{5},{6},{7},{8:F1},{9:F1},{10:F1},{11:F1},{12},{13},{14}',
        $Braco, $LagMs, $N, $Capacity, $Rep, $Confirmed, $Pending, $oversell,
        $P50, $P95, $P99, $Throughput, $LagDisparo, $LagMax, $Commit)
}

# Caminho do psql.exe: o do PATH ou, se faltar, o da instalacao padrao do PostgreSQL no Windows
# (o instalador nao poe o psql no PATH). $null se nao achar.
function Find-Psql {
    $c = Get-Command psql -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $pf = if ($env:ProgramW6432) { $env:ProgramW6432 } else { $env:ProgramFiles }
    Get-ChildItem (Join-Path $pf 'PostgreSQL\*\bin\psql.exe') -ErrorAction SilentlyContinue |
        Sort-Object { [int](($_.FullName -split '\\')[-3] -replace '\D.*', '') } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

# Acrescenta uma linha ao CSV da matriz (cria com o cabecalho na 1a vez). UTF-8 sem BOM e LF:
# o report em bash le sem saneamento.
function Add-LinhaMatriz {
    param([Parameter(Mandatory)][string]$Csv, [Parameter(Mandatory)][string]$Linha)
    $texto = $Linha + "`n"
    if (Test-Path $Csv) {
        if ((Get-Content $Csv -TotalCount 1) -ne $MatrizCabecalho) {
            throw "O cabecalho de $Csv difere das colunas esperadas; renomeie o arquivo antigo e rode de novo."
        }
    } else {
        $texto = $MatrizCabecalho + "`n" + $texto
    }
    [System.IO.File]::AppendAllText($Csv, $texto, (New-Object System.Text.UTF8Encoding($false)))
}
