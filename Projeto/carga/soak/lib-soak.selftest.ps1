# Auto-teste do lib-soak.ps1 (issue #232): o modo de reserva por bloco. Uso:  powershell -NoProfile -ExecutionPolicy Bypass -File soak\lib-soak.selftest.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-soak.ps1')
$falhas = 0
function Confere($nome, $obtido, $esperado) {
    if ("$obtido" -eq "$esperado") { Write-Host "   [ok]    $nome" }
    else { Write-Host "   [FALHA] $nome`n           esperado '$esperado', veio '$obtido'"; $script:falhas++ }
}
Confere 'S1: sem eventos quentes (reserva espalhada, 60/10/30 com confirmacao)' (Get-QuentesDoBloco 'S1') 0
Confere 'S2: 1 evento quente (a fila e o KEDA do reservation)' (Get-QuentesDoBloco 'S2') 1
Confere 'S1: 200 vagas por evento (a fronteira de capacidade e exercitada)' (Get-CapacidadeDoBloco 'S1') 200
Confere 'S2: 500 vagas por evento comum (a reserva vai ao quente)' (Get-CapacidadeDoBloco 'S2') 500

# o preparar-soak.ps1 usa o padrao do bloco so quando -Quentes nao foi dado, e so semeia/exporta QUENTES_IDS com Quentes > 0 (conferido no texto: o resto exige cluster)
$fonte = Get-Content -Raw (Join-Path $PSScriptRoot 'preparar-soak.ps1')
Confere 'preparar-soak: -Quentes -1 (padrao) vira Get-QuentesDoBloco $Bloco' ([bool]($fonte -match 'if \(\$Quentes -lt 0\) \{ \$Quentes = Get-QuentesDoBloco \$Bloco \}')) $true
Confere 'preparar-soak: o 2o seed (quentes) so roda com $Quentes -gt 0' ([bool]($fonte -match 'if \(\$Quentes -gt 0\) \{\s+\$uq')) $true
Confere 'preparar-soak: QUENTES_IDS so entra na linha do k6 quando houve seed de quentes' ([bool]($fonte -match 'if \(\$seedQ\) \{ \$envs\[''QUENTES_IDS''\]')) $true
Confere 'preparar-soak: -Capacidade -1 (padrao) vira Get-CapacidadeDoBloco $Bloco' ([bool]($fonte -match 'if \(\$Capacidade -lt 0\) \{ \$Capacidade = Get-CapacidadeDoBloco \$Bloco \}')) $true
Confere 'preparar-soak: o seed dos eventos comuns usa $Capacidade (nao um literal)' ([bool]($fonte -match 'Invoke-Seed -BaseUrl \$SeedUrl -Usuarios \$Usuarios -Eventos \$Eventos -Capacidade \$Capacidade ')) $true
Confere 'preparar-soak: MAILPIT_AUTH digitada sem eco (Read-Host -AsSecureString)' ([bool]($fonte -match 'Read-Host ''MAILPIT_AUTH[^'']*'' -AsSecureString')) $true
Confere 'preparar-soak: o BSTR da credencial e zerado (ZeroFreeBSTR) depois do PtrToStringBSTR' ([bool]($fonte -match 'PtrToStringBSTR\(`\$b\); \[Runtime\.InteropServices\.Marshal\]::ZeroFreeBSTR\(`\$b\)')) $true
Confere 'preparar-soak: -Fixo recusa -Semente (nao troque de semente)' ($fonte.Contains('ContainsKey(''Semente'')) { Fail ''Com -Fixo nao existe semente')) $true
Confere 'preparar-soak: -Fixo so vale no bloco S2' ($fonte.Contains('if ($Bloco -ne ''S2'') { Fail "-Fixo')) $true
Confere 'preparar-soak: com -Fixo o perfil.py recebe --fixo e nenhuma --semente' ($fonte.Contains('if ($Fixo) { @(''--fixo'', $Fixo) } else { @(''--semente''')) $true

# Get-SufixoQuentes: o setup.sh gera os telefones so dos digitos do sufixo (host: ultimos 8; attendees: ultimos 6), entao os quentes nao podem repetir os do runId (bug do dry-run S2)
function UltimosDigitos([string]$s, [int]$n) { $d = $s -replace '\D', ''; if ($d.Length -gt $n) { $d.Substring($d.Length - $n) } else { $d } }
foreach ($r in '367578', '364888', '12345', '100000', '999999', '007890', '000001') {
    $q = Get-SufixoQuentes $r
    Confere "quentes($r)='$q': ultimos 8 digitos (fone do host) diferem do runId" ((UltimosDigitos $q 8) -ne (UltimosDigitos $r 8)) $true
    Confere "quentes($r)='$q': ultimos 6 digitos (fone dos attendees) diferem do runId" ((UltimosDigitos $q 6) -ne (UltimosDigitos $r 6)) $true
    Confere "quentes($r): reconhecivel (comeca com q) e so [A-Za-z0-9]" ([bool]($q -match '^q\d+$')) $true
    Confere "quentes($r): ultimos 6 digitos comecam em 0 (nenhum runId real, int, colide)" ((UltimosDigitos $q 6)[0]) '0'
}
Confere 'quentes: deterministico (mesmo runId, mesmo sufixo)' (Get-SufixoQuentes '367578') (Get-SufixoQuentes '367578')
Confere 'quentes: runIds diferentes (mod 100000) dao sufixos diferentes' ((Get-SufixoQuentes '367578') -ne (Get-SufixoQuentes '364888')) $true
Confere 'preparar-soak: o seed dos quentes usa Get-SufixoQuentes $runId (nao "${runId}q")' ($fonte.Contains('-Sufixo (Get-SufixoQuentes $runId)')) $true

if ($falhas -gt 0) { Write-Host "`nselftest lib-soak: $falhas falha(s)"; exit 1 }
Write-Host "`nselftest lib-soak: OK"
