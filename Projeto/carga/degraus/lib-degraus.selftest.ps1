<#
.SYNOPSIS
  Auto-teste de lib-degraus.ps1 (issue #14): plano da escada e linha do CSV. Sem rede, sem k6, sem banco.
  Sai com codigo != 0 se alguma assercao falhar. (O p95 por janela de 30 s sai pronto do resumo do k6:
  carga-degraus.js; seu teste e o stub Node do k6, nao este arquivo.)

.EXAMPLE
  .\degraus\lib-degraus.selftest.ps1
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-degraus.ps1')

# pt-BR: se algum formato usar a cultura corrente, o decimal viraria virgula e quebraria o CSV.
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::GetCultureInfo('pt-BR')

$script:falhas = 0
function Confere($nome, $obtido, $esperado) {
    if ($obtido -ceq $esperado) { Write-Host "   [ok]    $nome" }
    else { Write-Host "   [FALHA] $nome`n           esperado: $esperado`n           obtido:   $obtido"; $script:falhas++ }
}
function Lanca($nome, [scriptblock]$bloco, $trecho) {
    $msg = ''
    try { & $bloco | Out-Null } catch { $msg = $_.Exception.Message }
    if ($msg -and $msg -match $trecho) { Write-Host "   [ok]    $nome" }
    else { Write-Host "   [FALHA] $nome`n           esperava excecao com /$trecho/, obtido: '$msg'"; $script:falhas++ }
}

Write-Host ''
Write-Host '==> Cabecalho do CSV'
Confere 'colunas da spec (rpm_alvo, rpm_efetivo, taxa_5xx, p50/p95/p99, throughput, descartadas) + as do criterio (5xx so da reserva: reservas, erros_5xx, taxa_5xx; erros_5xx_confirma e informativa)' $DegrausCabecalhoBase `
    'escada,rpm_alvo,rpm_efetivo,duracao_s,reservas,erros_5xx,taxa_5xx,erros_5xx_confirma,outros,p50,p95,p99,p95_max_janela_30s,throughput,iteracoes_descartadas,braco,max_connections,pool_hikari,commit,mapeamento'
Confere 'mapeamento e a ultima coluna da parte antiga (colunas antigas nao mudam de posicao)' (($DegrausCabecalhoBase -split ',')[-1]) 'mapeamento'
Confere 'colunas do modo fila (#178) so no FIM: o cabecalho completo = antigo + admissao' $DegrausCabecalho "$DegrausCabecalhoBase,$AdmissaoCabecalho"
Confere 'ultima coluna = params_autoescala; parada logo antes' ((($DegrausCabecalho -split ',')[-2, -1]) -join ',') 'parada,params_autoescala'
Confere 'a linha de um degrau tem tantos campos quanto o cabecalho completo' (((Format-LinhaDegrau -Escada Deg-1 -Degrau ([pscustomobject]@{ rpm_alvo = 1; reservas = 1; erros_5xx = 0; confirma_5xx = 0; outros = 0; descartadas = 0; p50_ms = 1; p95_ms = 1; p99_ms = 1; p95_max_janela_ms = 1 }) -DuracaoS 60 -Braco cas -MaxConnections 1 -PoolHikari 1 -Commit c -Mapeamento sequencial) -split ',').Count) (($DegrausCabecalhoBase -split ',').Count)
# o rodar-degraus.ps1 imprime a tabela final pelas POSICOES das colunas do CSV: se o cabecalho mudar, este teste acusa
Confere 'posicoes que o rodar-degraus.ps1 imprime na tabela (rpm_alvo, rpm_efetivo, taxa_5xx, p50, p95, p99, janela, descartadas)' `
    ((($DegrausCabecalho -split ',')[1, 2, 6, 9, 10, 11, 12, 14]) -join ',') 'rpm_alvo,rpm_efetivo,taxa_5xx,p50,p95,p99,p95_max_janela_30s,iteracoes_descartadas'

Write-Host ''
Write-Host '==> Plano da escada: degraus, cota de pares por degrau e tamanho do seed'
$p = Get-PlanoEscada -RpmInicio 12000 -RpmPasso 4000 -RpmMax 20000 -DuracaoS 60 -Usuarios 200
Confere 'degraus 12k, 16k, 20k' ($p.Rpms -join ',') '12000,16000,20000'
Confere 'cota = ceil(rpm*D/60) + 1 (a iteracao extra do k6)' ($p.Cotas -join ',') '12001,16001,20001'
Confere 'total de pares = soma das cotas' $p.TotalPares 48003
Confere 'eventos = ceil(total * 1.05 / usuarios)' $p.Eventos 253
Confere 'a conta cobre os pares (usuarios x eventos >= total)' (200 * $p.Eventos -ge $p.TotalPares) $true

$p = Get-PlanoEscada -RpmInicio 12000 -RpmPasso 4000 -RpmMax 21000 -DuracaoS 60 -Usuarios 200
Confere 'maximo fora da grade: o ultimo degrau e o ultimo <= maximo' ($p.Rpms -join ',') '12000,16000,20000'
$p = Get-PlanoEscada -RpmInicio 12000 -RpmPasso 4000 -RpmMax 12000 -DuracaoS 60 -Usuarios 200
Confere 'inicio = maximo: um degrau so' ($p.Rpms -join ',') '12000'
$p = Get-PlanoEscada -RpmInicio 12001 -RpmPasso 1 -RpmMax 12001 -DuracaoS 90 -Usuarios 200
Confere 'rpm*D/60 fracionario: arredonda para cima (12001*90/60 = 18001,5 -> 18002) + 1' ($p.Cotas -join ',') '18003'

Lanca 'passo 0 e recusado' { Get-PlanoEscada -RpmInicio 12000 -RpmPasso 0 -RpmMax 20000 -DuracaoS 60 -Usuarios 200 } 'passo'
Lanca 'inicio > maximo e recusado' { Get-PlanoEscada -RpmInicio 20000 -RpmPasso 4000 -RpmMax 12000 -DuracaoS 60 -Usuarios 200 } 'maximo'
Lanca 'duracao < 60 s e recusada (spec: >= 60 s)' { Get-PlanoEscada -RpmInicio 12000 -RpmPasso 4000 -RpmMax 20000 -DuracaoS 30 -Usuarios 200 } 'DuracaoS'
Lanca 'duracao que nao e multipla de 30 e recusada (janelas de 30 s inteiras)' { Get-PlanoEscada -RpmInicio 12000 -RpmPasso 4000 -RpmMax 20000 -DuracaoS 75 -Usuarios 200 } 'DuracaoS'
Lanca 'mais de 40 degraus e recusado (passo digitado errado)' { Get-PlanoEscada -RpmInicio 1 -RpmPasso 1 -RpmMax 1000 -DuracaoS 60 -Usuarios 200 } 'degraus'

Write-Host ''
Write-Host '==> Capacidade padrao por evento (-Capacidade 0): depende do mapeamento'
Confere 'sequencial: Usuarios/2 (como o teste 2; 200 -> 100)' (Get-CapacidadePadrao -Usuarios 200 -Mapeamento sequencial) 100
Confere 'sequencial: nunca < 1 (1 usuario -> 1)' (Get-CapacidadePadrao -Usuarios 1 -Mapeamento sequencial) 1
Confere 'intercalado: Usuarios (cada evento recebe no maximo U reservas, uma por usuario: nenhuma "lotada" na escada)' (Get-CapacidadePadrao -Usuarios 200 -Mapeamento intercalado) 200
Confere 'intercalado: capacidade >= reservas por evento no pior caso (U x E pares / E eventos = U)' ((Get-CapacidadePadrao -Usuarios 37 -Mapeamento intercalado) -ge 37) $true

Write-Host ''
Write-Host '==> O p95 por janela sai do resumo do k6, nao de uma serie: as funcoes da serie nao existem mais'
Confere 'Get-P95Janelas removida' ($null -eq (Get-Command Get-P95Janelas -ErrorAction SilentlyContinue)) $true
Confere 'Get-Percentil removida' ($null -eq (Get-Command Get-Percentil -ErrorAction SilentlyContinue)) $true

Write-Host ''
Write-Host '==> Linha do CSV de um degrau (resumo do k6 -> CSV)'
function Degrau($ajustes) {
    $o = [ordered]@{ nome = 'degrau_20000'; rpm_alvo = 20000; reservas = 19000; confirmas = 9000; erros_5xx = 380
        confirma_5xx = 20; outros = 5; descartadas = 1000; p50_ms = 12.34; p95_ms = 456.789; p99_ms = 1234.5
        p95_max_janela_ms = 1100.04 }
    foreach ($k in $ajustes.Keys) { $o[$k] = $ajustes[$k] }
    ($o | ConvertTo-Json) | ConvertFrom-Json   # como o rodar-degraus.ps1 le o resumo do k6
}
$f = { param($d) Format-LinhaDegrau -Escada Deg-1 -Degrau $d -DuracaoS 60 -Braco cas -MaxConnections 300 -PoolHikari 1000 -Commit abc1234 -Mapeamento sequencial }
Confere 'linha completa; ponto decimal mesmo em pt-BR; p95_max_janela_30s = p95_max_janela_ms do resumo; taxa = 5xx da reserva / reservas (a confirma vai so em erros_5xx_confirma); throughput = (reservas - 5xx da reserva) / D' `
    (& $f (Degrau @{})) `
    'Deg-1,20000,19000,60,19000,380,0.020000,20,5,12.3,456.8,1234.5,1100.0,310.3,1000,cas,300,1000,abc1234,sequencial'
Confere 'mapeamento intercalado vai no fim da linha' `
    ((Format-LinhaDegrau -Escada Deg-1 -Degrau (Degrau @{}) -DuracaoS 60 -Braco cas -MaxConnections 300 -PoolHikari 1000 -Commit abc1234 -Mapeamento intercalado)) `
    'Deg-1,20000,19000,60,19000,380,0.020000,20,5,12.3,456.8,1234.5,1100.0,310.3,1000,cas,300,1000,abc1234,intercalado'
Confere 'rpm_efetivo = reservas / D * 60 (9500 reservas em 60 s = 9500 RPM)' `
    ((& $f (Degrau @{ reservas = 9500 })) -split ',')[2] '9500'
Confere 'sem janela (-1 no resumo): vai como -1.0 (o report trata como n/d)' ((& $f (Degrau @{ p95_max_janela_ms = -1 })) -split ',')[12] '-1.0'
Confere 'zero reservas: taxa 0, sem dividir por zero' `
    ((& $f (Degrau @{ reservas = 0; confirmas = 0; erros_5xx = 0; confirma_5xx = 0; p95_max_janela_ms = -1 })) -split ',')[6] '0.000000'
Confere 'exatamente 1% (100 de 10000 reservas): 0.010000, mesmo com 5xx na confirma (nao entram na taxa)' `
    ((& $f (Degrau @{ reservas = 10000; confirmas = 10000; erros_5xx = 100; confirma_5xx = 50 })) -split ',')[6] '0.010000'
# a mudanca de 2026-10-06: o denominador e so a reserva. Antes a taxa era (5xx reserva + confirma) / (reservas + confirmas),
# o que diluia: com 100% de confirmacao, 120 5xx em 10000 reservas (1,2%) davam 0.006 e nao disparavam o X.
Confere '120 5xx em 10000 reservas (1,2%) com 10000 confirmas: taxa 0.012000 (nao diluida pelas confirmas)' `
    ((& $f (Degrau @{ reservas = 10000; confirmas = 10000; erros_5xx = 120; confirma_5xx = 0 })) -split ',')[6] '0.012000'
$c = (& $f (Degrau @{ reservas = 10000; confirmas = 10000; erros_5xx = 0; confirma_5xx = 500 })) -split ','
Confere '5xx so na confirma (500): taxa 0.000000 (nao entra no X)...' $c[6] '0.000000'
Confere '...mas fica visivel em erros_5xx_confirma (informativa)' $c[7] '500'
Confere 'erros_5xx (coluna 5) = so os 5xx da reserva' ((& $f (Degrau @{ erros_5xx = 7; confirma_5xx = 9 })) -split ',')[5] '7'
Lanca 'resumo sem p95_max_janela_ms (resumo de outra versao) falha alto, em vez de gravar 0' {
    $d = Degrau @{}; $d.PSObject.Properties.Remove('p95_max_janela_ms'); Format-LinhaDegrau -Escada Deg-1 -Degrau $d -DuracaoS 60 -Braco cas -MaxConnections 300 -PoolHikari 1000 -Commit abc1234 -Mapeamento sequencial } 'p95_max_janela_ms'

Write-Host ''
Write-Host '==> Modo fila (#178): graceful, espaco e colunas'
# a mesma tabela esta em degraus/lib-admissao.selftest.mjs (gracefulS)
Confere 'graceful sem acompanhamento: 30 s' (Get-GracefulS -PctAcompanha 0 -TetoConsultaS 120) 30
Confere 'graceful com acompanhamento: teto + 10 s' (Get-GracefulS -PctAcompanha 100 -TetoConsultaS 35) 45
Confere 'graceful nunca abaixo de 30 s' (Get-GracefulS -PctAcompanha 10 -TetoConsultaS 5) 30
Confere 'espaco entre degraus = graceful + 1 (46 s com o teto de 35 s)' (Get-EspacoS -PctAcompanha 100 -TetoConsultaS 35) 46
Confere 'espaco sem acompanhamento = 31 s (como antes da #178)' (Get-EspacoS -PctAcompanha 0 -TetoConsultaS 35) 31
Confere 'orcamento do setup: piso de 30 s e 0,6 s por lote de 25 usuarios' (@((Get-SetupS -Usuarios 200), (Get-SetupS -Usuarios 2000), (Get-SetupS -Usuarios 5000)) -join ',') '30,48,120'
$adm = ([pscustomobject]@{ reservas_202 = 900; acompanhadas = 200; admitidas = 150; rejected = 30; timed_out = 15; sem_estado = 5; recusadas_429 = 7; conflitos_422 = 0
    consultas_estado = 1234; admitir_p50_ms = 1000.04; admitir_p95_ms = 2500; admitir_p99_ms = 3000; fim_a_fim_p50_ms = 1100; fim_a_fim_p95_ms = 2700.55; fim_a_fim_p99_ms = 3300 }) | ConvertTo-Json | ConvertFrom-Json
Confere 'linha do modo fila: taxas sobre as acompanhadas, ponto decimal em pt-BR, parada e params sem virgula' `
    (Format-ColunasAdmissao -Degrau $adm -Arranjo autoescala+slim -PctAcompanha 25 -ConsultaMs 500 -Parada 'p95 > 5000, janela' -ParamsAutoescala 'AUTOESCALA_GATEWAY_RPS=500;AUTOESCALA_X_RPM=56000') `
    'autoescala+slim,25,500,900,200,150,30,15,5,0.150000,0.075000,7,0,1234,1000.0,2500.0,3000.0,1100.0,2700.6,3300.0,p95___5000__janela,AUTOESCALA_GATEWAY_RPS=500;AUTOESCALA_X_RPM=56000'
Confere 'o numero de campos das colunas do modo fila = o cabecalho' ((Format-ColunasAdmissao -Degrau $adm -Arranjo a -PctAcompanha 0 -ConsultaMs 1 -Parada nenhuma) -split ',').Count (($AdmissaoCabecalho -split ',').Count)
$semFila = (Format-ColunasAdmissao -Degrau (Degrau @{}) -Arranjo sem-autoescala -PctAcompanha 100 -ConsultaMs 1000 -Parada nenhuma) -split ','
Confere 'resumo sem campos do modo fila (degrau sincrono puro): zeros, sem dividir por zero' ($semFila[3..13] -join ',') '0,0,0,0,0,0,0.000000,0.000000,0,0,0'
Confere 'params vazio = ultima coluna vazia' $semFila[-1] ''

Write-Host ''
Write-Host '==> Escada no gerador so com k6 run (#178, c3): resumo -> CSV no host, linha do k6, faixas de eventos'
Confere 'Compress-Ids: faixas e isolados (101-103,110), sem duplicata, fora de ordem' (Compress-Ids @(110, 103, 101, 102, 102)) '101-103,110'
Confere 'Compress-Ids: um id so' (Compress-Ids @(7)) '7'
$env6 = [ordered]@{ BASE_URL = 'http://192.168.0.64:8081'; RPMS = '28000,42000'; PARAMS = 'A=1;B=2'; EVENT_IDS = '1-5,9' }
$lk = Get-LinhaK6 -Envs $env6 -Script 'Projeto/carga/degraus/carga-degraus.js'
Confere 'linha do k6: so `k6 run`, cada env em aspas SIMPLES (virgula e ponto-e-virgula nao quebram no PowerShell 5.1), script relativo no fim' $lk `
    "k6 run --quiet -e 'BASE_URL=http://192.168.0.64:8081' -e 'RPMS=28000,42000' -e 'PARAMS=A=1;B=2' -e 'EVENT_IDS=1-5,9' Projeto/carga/degraus/carga-degraus.js"
Confere 'a linha do k6 nao tem aspas duplas nem a senha' (($lk -notmatch '"') -and ($lk -notmatch 'CargaTeste')) $true
Lanca 'valor com aspa simples nao cabe na linha digitada' { Get-LinhaK6 -Envs ([ordered]@{ X = "a'b" }) -Script s.js } 'aspas'

function ResumoFixture([hashtable]$extra = @{}) {
    $d1 = @{ nome = 'degrau_1000'; situacao = 'completo'; duracao_efetiva_s = 60; rpm_alvo = 1000; reservas = 1000; erros_5xx = 0; confirma_5xx = 0; outros = 0; descartadas = 0
        p50_ms = 5; p95_ms = 9; p99_ms = 12; p95_max_janela_ms = 10; reservas_202 = 0 }
    $d2 = @{ nome = 'degrau_2000'; situacao = 'parcial'; duracao_efetiva_s = 30; rpm_alvo = 2000; reservas = 900; erros_5xx = 9; confirma_5xx = 1; outros = 0; descartadas = 0
        p50_ms = 50; p95_ms = 900; p99_ms = 1200; p95_max_janela_ms = 950; reservas_202 = 400; acompanhadas = 40; admitidas = 30; rejected = 6; timed_out = 3; sem_estado = 1
        consultas_estado = 90; admitir_p50_ms = 1000; admitir_p95_ms = 2000; admitir_p99_ms = 2500; fim_a_fim_p50_ms = 1100; fim_a_fim_p95_ms = 2200; fim_a_fim_p99_ms = 2600 }
    $d3 = @{ nome = 'degrau_3000'; situacao = 'omitido'; duracao_efetiva_s = 0; rpm_alvo = 3000; reservas = 0 }
    $r = @{ duracao_degrau_s = 60; duracao_s = 200; graceful_s = 45; usuarios = 100; eventos = 40; parada = @{ degrau = 'degrau_2000'; indice = 1; pw = 0; p95_ms = 9000; limite_ms = 5000; janela_s = 30 }
        parada_texto = 'p95_acima_de_5000ms_em_janela_de_30s_no_degrau_2000'; degraus = @($d1, $d2, $d3); faltando = @(); inconsistencias = @() }
    foreach ($d in $d1, $d2, $d3) { foreach ($c in 'sem_login', 'alem_dos_pares', 'fora_das_janelas', 'recusadas_429', 'conflitos_422') { $d[$c] = 0 } }
    foreach ($k in $extra.Keys) { $r[$k] = $extra[$k] }
    return ($r | ConvertTo-Json -Depth 6 | ConvertFrom-Json)
}
$rs = ResumoFixture
$linhasFx = Get-LinhasCsvDegraus -Resumo $rs -Escada Deg-9 -DuracaoS 60 -Braco cas -MaxConnections 300 -PoolHikari 1000 -Commit abc1234 -Mapeamento sequencial -Arranjo autoescala -PctAcompanha 10 -ConsultaMs 1000 -ParamsAutoescala 'AUTOESCALA_X_RPM=56000'
Confere 'resumo com parada: omitido nao vira linha (2 linhas); o parcial usa a duracao efetiva (30 s)' "$(@($linhasFx).Count)|$((($linhasFx[1]) -split ',')[3])" '2|30'
$esperada1 = (Format-LinhaDegrau -Escada Deg-9 -Degrau $rs.degraus[0] -DuracaoS 60 -Braco cas -MaxConnections 300 -PoolHikari 1000 -Commit abc1234 -Mapeamento sequencial) + ',' +
    (Format-ColunasAdmissao -Degrau $rs.degraus[0] -Arranjo autoescala -PctAcompanha 10 -ConsultaMs 1000 -Parada 'p95_acima_de_5000ms_em_janela_de_30s_no_degrau_2000' -ParamsAutoescala 'AUTOESCALA_X_RPM=56000')
Confere 'a linha do degrau completo e a mesma do caminho antigo (Format-LinhaDegrau + Format-ColunasAdmissao)' $linhasFx[0] $esperada1
Confere 'a coluna parada leva o motivo do resumo em todas as linhas' ((($linhasFx | ForEach-Object { ($_ -split ',')[-2] }) | Select-Object -Unique) -join '|') 'p95_acima_de_5000ms_em_janela_de_30s_no_degrau_2000'
Confere 'linha tem tantos campos quanto o cabecalho completo' (($linhasFx[0] -split ',').Count) (($DegrausCabecalho -split ',').Count)
Confere 'Get-FalhaResumo: resumo consistente com parada = sem falha' ([string](Get-FalhaResumo -Resumo $rs)) ''
Confere 'Get-FalhaResumo: inconsistencia do k6 recusa' ([bool](Get-FalhaResumo -Resumo (ResumoFixture @{ inconsistencias = @('reservas_5xx: global 3 != soma dos degraus 2') }))) $true
Confere 'Get-FalhaResumo: sem parada e teste que durou o previsto (3 x 60 + 2 x 46 = 272 s) = sem falha' ([string](Get-FalhaResumo -Resumo (ResumoFixture @{ parada = $null; duracao_s = 280 }))) ''
Confere 'Get-FalhaResumo: sem parada e teste curto demais = k6 interrompido (o codigo de saida nao chega ao host)' ((Get-FalhaResumo -Resumo (ResumoFixture @{ parada = $null; duracao_s = 120 })) -match 'interrompido') $true

# o script de verdade (csv-do-resumo.ps1), num diretorio temporario: plano + resumo -> CSV identico ao Get-LinhasCsvDegraus
$tmpC = Join-Path ([System.IO.Path]::GetTempPath()) ("csvres-{0}" -f [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmpC | Out-Null
try {
    $plano = [ordered]@{ escada = 'Deg-9'; run_id = 9; commit = 'abc1234'; duracao_s = 60; mapeamento = 'sequencial'; braco = 'cas'; max_connections = 300; pool_hikari = 1000
        arranjo = 'autoescala'; pct_acompanha = 10; consulta_ms = 1000; teto_consulta_s = 35; params_autoescala = 'AUTOESCALA_X_RPM=56000'; usuarios = 100; eventos = 40 }
    $pa = Join-Path $tmpC 'plano.json'; $ra = Join-Path $tmpC 'resumo.json'; $ca = Join-Path $tmpC 'saida.csv'
    ($plano | ConvertTo-Json) | Set-Content $pa -Encoding UTF8
    ($rs | ConvertTo-Json -Depth 6) | Set-Content $ra -Encoding UTF8   # com BOM, como o PowerShell 5.1 grava
    $saida = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'csv-do-resumo.ps1') -Plano $pa -Resumo $ra -Csv $ca 2>&1 | Out-String
    $rc1 = $LASTEXITCODE
    Confere 'csv-do-resumo.ps1: sai com 0 e grava o CSV (cabecalho + 2 linhas, LF, sem BOM) igual ao das funcoes' `
        "$rc1|$([System.IO.File]::ReadAllText($ca) -ceq ($DegrausCabecalho + "`n" + ($linhasFx -join "`n") + "`n"))" '0|True'
    Confere 'csv-do-resumo.ps1: avisa da parada' ($saida -match 'PARADA antecipada') $true
    # resumo de outra escada (usuarios diferentes) e recusado, sem gravar CSV
    $rb = Join-Path $tmpC 'outra.json'; (ResumoFixture @{ usuarios = 7 } | ConvertTo-Json -Depth 6) | Set-Content $rb -Encoding UTF8
    $cb = Join-Path $tmpC 'b.csv'
    $null = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'csv-do-resumo.ps1') -Plano $pa -Resumo $rb -Csv $cb 2>&1 | Out-String
    $rc2 = $LASTEXITCODE
    Confere 'csv-do-resumo.ps1: resumo de outra escada = codigo 1 e nenhum CSV' "$rc2|$(Test-Path $cb)" '1|False'
} finally { Remove-Item $tmpC -Recurse -Force -ErrorAction SilentlyContinue }

Write-Host ''
Write-Host '=================================================='
if ($script:falhas -eq 0) { Write-Host '   RESULTADO: OK -- lib-degraus.ps1 passou em todas as assercoes.' }
else { Write-Host "   RESULTADO: $($script:falhas) ASSERCAO(OES) FALHARAM." }
Write-Host '=================================================='
exit $(if ($script:falhas -eq 0) { 0 } else { 1 })
