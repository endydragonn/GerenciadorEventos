# Telemetria da maquina (gerador ou host) durante uma rodada: 1 amostra/s num CSV com epoch em ms (UTC), para alinhar com
# os CSVs do k6 e documentar, com dado e nao com observacao, que o gerador nao saturou e que o host nao sofreu throttling.
# So WMI: os NOMES DE CLASSE nao mudam com o idioma do Windows (os de contador do Get-Counter mudam).
#   cpu_pct       uso de CPU (PercentProcessorUtility: na escala da frequencia, como o Gerenciador de Tarefas)
#   freq_mhz      frequencia atual; pct_freq_max = % da frequencia maxima (cai sob carga = throttling de energia/termico)
#   ram_livre_mb  memoria fisica livre
#   temp_c        maior zona termica ACPI (MSAcpi_ThermalZoneTemperature). MUITOS desktops nao a expoem: fica vazio.
#                 Para temperatura de CPU de verdade use os sensores do RESET EXPLODE / HWiNFO ANTES e DEPOIS da rodada
#                 (nao durante: eles tambem consomem CPU).
$TelemetriaCabecalho = 'epoch_ms,cpu_pct,freq_mhz,pct_freq_max,ram_livre_mb,temp_c'

# Devolve o job de fundo; passe-o a Stop-Telemetria. O CSV e sobrescrito.
function Start-Telemetria([Parameter(Mandatory)][string]$Saida, [int]$IntervaloS = 1) {
    [System.IO.File]::WriteAllText($Saida, "$TelemetriaCabecalho`n", (New-Object System.Text.UTF8Encoding($false)))
    Start-Job -ArgumentList $Saida, $IntervaloS -ScriptBlock {
        param($saida, $s)
        $ci = [System.Globalization.CultureInfo]::InvariantCulture
        while ($true) {
            $ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            $p = Get-CimInstance Win32_PerfFormattedData_Counters_ProcessorInformation -Filter "Name='_Total'" -ErrorAction SilentlyContinue
            $os = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
            $t = Get-CimInstance -Namespace root/wmi MSAcpi_ThermalZoneTemperature -ErrorAction SilentlyContinue |
                Measure-Object -Property CurrentTemperature -Maximum
            $temp = if ($t -and $t.Count -gt 0) { ($t.Maximum / 10.0 - 273.15).ToString('0.0', $ci) } else { '' }
            $ram = if ($os) { [math]::Round($os.FreePhysicalMemory / 1024.0).ToString($ci) } else { '' }
            $linha = '{0},{1},{2},{3},{4},{5}' -f $ms, $p.PercentProcessorUtility, $p.ProcessorFrequency, $p.PercentofMaximumFrequency, $ram, $temp
            [System.IO.File]::AppendAllText($saida, $linha + "`n")
            Start-Sleep -Seconds $s
        }
    }
}

function Stop-Telemetria($Job) {
    if (-not $Job) { return }
    Stop-Job $Job -ErrorAction SilentlyContinue
    Remove-Job $Job -Force -ErrorAction SilentlyContinue
}

# Uma linha com o pior caso da rodada. Sem amostras (ex.: o job nao subiu) devolve o aviso, nunca falha a rodada.
function Get-ResumoTelemetria([Parameter(Mandatory)][string]$Csv) {
    $ci = [System.Globalization.CultureInfo]::InvariantCulture
    $d = @(Import-Csv $Csv)
    if ($d.Count -eq 0) { return 'sem amostras de telemetria (confira se o job de fundo subiu)' }
    function Col($nome) { @($d | Where-Object { $_.$nome -ne '' } | ForEach-Object { [double]::Parse($_.$nome, $ci) }) }
    $cpu = Col 'cpu_pct'; $pf = Col 'pct_freq_max'; $ram = Col 'ram_livre_mb'; $tmp = Col 'temp_c'
    $texto = '{0} amostras | CPU media {1:N0}% max {2:N0}% | freq min {3:N0}% do maximo | RAM livre min {4:N0} MB | temp max {5}' -f `
        $d.Count, ($cpu | Measure-Object -Average).Average, ($cpu | Measure-Object -Maximum).Maximum,
        ($pf | Measure-Object -Minimum).Minimum, ($ram | Measure-Object -Minimum).Minimum,
        $(if ($tmp.Count) { '{0:N1} C' -f ($tmp | Measure-Object -Maximum).Maximum } else { 'n/d (a maquina nao expoe zona termica)' })
    return $texto
}
