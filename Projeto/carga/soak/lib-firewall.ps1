# Funcoes PURAS do firewall-mailpit.ps1 (issue #232), em arquivo a parte para o firewall-mailpit.selftest.ps1 testa-las sem tocar no firewall.

# IPv4 -> inteiro de 32 bits (uint64, sem estouro)
function ConvertTo-IpInteiro([string]$ip) {
    $b = [System.Net.IPAddress]::Parse($ip).GetAddressBytes()
    return ([uint64]$b[0] * 16777216) + ([uint64]$b[1] * 65536) + ([uint64]$b[2] * 256) + [uint64]$b[3]
}

function ConvertFrom-IpInteiro([uint64]$n) {
    return '{0}.{1}.{2}.{3}' -f ([math]::Floor($n / 16777216) % 256), ([math]::Floor($n / 65536) % 256), ([math]::Floor($n / 256) % 256), ($n % 256)
}

# IP valido para a regra: um IPv4 unico (nada de faixa, mascara, 'Any', lista nem os extremos 0.0.0.0 e 255.255.255.255, que nao tem faixa complementar de um dos lados)
function Test-IpUnico([string]$ip) {
    $a = $null
    if ($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { return $false }
    if (-not [System.Net.IPAddress]::TryParse($ip, [ref]$a)) { return $false }
    return $ip -ne '0.0.0.0' -and $ip -ne '255.255.255.255'
}

# Todo o IPv6, na forma que o Criar grava (faixa; o Windows pode devolver tambem '::/0': Test-Ipv6Total aceita as duas). O gerador tem IPv6 global, mas o k6 usa a URL IPv4: o Block cobre TODO o IPv6
# sem excecao (o gerador nao precisa dele), em vez de deixar o IPv6 da LAN passar.
$Ipv6Total = '::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff'

# Faixas que cobrem TODO o IPv4 MENOS o IP dado (para a regra Block: no Windows o Block vence o Allow, entao a Block NAO pode incluir o gerador).
# -> ['0.0.0.0-<ip-1>', '<ip+1>-255.255.255.255']. Os dois lados existem sempre (Test-IpUnico recusa 0.0.0.0 e 255.255.255.255): com 0.0.0.1 o lado de baixo e so o endereco 0.0.0.0, com 255.255.255.254 o de cima e so o 255.255.255.255.
function Get-FaixasComplementares([string]$ip) {
    $n = ConvertTo-IpInteiro $ip
    $faixas = @()
    if ($n -gt 0) { $faixas += ('0.0.0.0-' + (ConvertFrom-IpInteiro ($n - 1))) }
    if ($n -lt 4294967295) { $faixas += ((ConvertFrom-IpInteiro ($n + 1)) + '-255.255.255.255') }
    return $faixas
}

# O item de RemoteAddress e "todo o IPv6"? Aceita '::/0' (prefixo 0) e a faixa '::-ffff:...:ffff' (do endereco zero ao maior). Qualquer outra forma (um /64, um endereco, uma faixa parcial, 'Any') -> $false.
function Test-Ipv6Total([string]$item) {
    $i = $item.Trim(); $a = $null; $b = $null
    if ($i -match '^([0-9a-fA-F:.]+)/(\d{1,3})$') {
        return ([int]$Matches[2] -eq 0 -and [System.Net.IPAddress]::TryParse($Matches[1], [ref]$a) -and $a.AddressFamily -eq 'InterNetworkV6')
    }
    if ($i -match '^([0-9a-fA-F:.]+)-([0-9a-fA-F:.]+)$') {
        if (-not ([System.Net.IPAddress]::TryParse($Matches[1], [ref]$a) -and [System.Net.IPAddress]::TryParse($Matches[2], [ref]$b))) { return $false }
        if ($a.AddressFamily -ne 'InterNetworkV6' -or $b.AddressFamily -ne 'InterNetworkV6') { return $false }
        return (@($a.GetAddressBytes() | Where-Object { $_ -ne 0 }).Count -eq 0) -and (@($b.GetAddressBytes() | Where-Object { $_ -ne 255 }).Count -eq 0)
    }
    return $false
}

# A porta esta coberta por um LocalPort de Get-NetFirewallPortFilter? ('Any', '8025', '8000-9000', ou uma lista desses)
function Test-PortaCoberta($localPort, [int]$porta) {
    foreach ($p in @($localPort)) {
        if ($null -eq $p) { continue }
        if ("$p" -eq 'Any') { return $true }
        if ("$p" -match '^(\d+)-(\d+)$') { if ($porta -ge [int]$Matches[1] -and $porta -le [int]$Matches[2]) { return $true } }
        elseif ("$p" -match '^\d+$') { if ($porta -eq [int]$p) { return $true } }
    }
    return $false
}

# A origem da regra e "larga" (qualquer um alcanca)? Qualquer coisa que nao seja um IPv4 unico: Any, *, LocalSubnet, Intranet, Internet, faixa, CIDR...
function Test-OrigemLarga($remote) {
    foreach ($r in @($remote)) {
        if ($null -eq $r) { return $true }
        if ("$r" -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { return $true }
    }
    return $false
}

# O programa da regra pode estar escutando na 8025? Sem restricao de programa (Any/vazio) ou o Docker Desktop (com.docker.backend, vpnkit, wsl: quem publica a porta do kind). Uma regra presa a outro
# programa (spoolsv, svchost, msra...) nao libera a 8025: o Allow vale so para o trafego daquele programa.
function Test-ProgramaRelevante($programa, $pacote = 'Any') {
    $p = "$programa"; $k = "$pacote"
    if (-not ($k -eq '' -or $k -eq 'Any')) { return $false }   # regra de app UWP (Teams, To Do...): vale so para o pacote
    return ($p -eq '' -or $p -eq 'Any' -or $p -match '(?i)docker|vpnkit|wsl')
}

# Regras Allow de ENTRADA habilitadas que cobrem a porta (porta Any ou faixa/lista que a contem) com origem larga e programa relevante (Test-ProgramaRelevante). Fora as do proprio kit.
# So le o firewall. -> objetos { Nome, Programa, Porta, Origem }
function Get-AllowsLargos([int]$porta, [string[]]$ignorar) {
    $out = @()
    foreach ($r in @(Get-NetFirewallRule -Direction Inbound -Action Allow -Enabled True -ErrorAction SilentlyContinue)) {
        if ($ignorar -contains $r.DisplayName) { continue }
        $pf = $r | Get-NetFirewallPortFilter; $af = $r | Get-NetFirewallAddressFilter; $ap = $r | Get-NetFirewallApplicationFilter
        $cobre = ($pf.Protocol -in 'TCP', 'Any') -and (Test-PortaCoberta $pf.LocalPort $porta)
        if ($cobre -and (Test-OrigemLarga $af.RemoteAddress) -and (Test-ProgramaRelevante $ap.Program $ap.Package)) {
            $out += [pscustomobject]@{ Nome = $r.DisplayName; Programa = "$($ap.Program)"; Porta = "$($pf.LocalPort)"; Origem = "$($af.RemoteAddress)" }
        }
    }
    return $out
}

# ---- conferencia da regra permanente (decisao 5): a regra Block existe e o RemoteAddress dela e EXATAMENTE o complemento do IP do gerador

# Um item de RemoteAddress -> [inicio, fim] (uint64) ou $null se nao for IPv4 comparavel (Any, LocalSubnet, IPv6...). Aceita 'a.b.c.d', 'a.b.c.d-e.f.g.h', 'a.b.c.d/nn' e 'a.b.c.d/mascara'.
function ConvertTo-IntervaloIp([string]$item) {
    $item = $item.Trim()
    if ($item -match '^(\d{1,3}(?:\.\d{1,3}){3})$') { $n = ConvertTo-IpInteiro $Matches[1]; return , @($n, $n) }
    if ($item -match '^(\d{1,3}(?:\.\d{1,3}){3})-(\d{1,3}(?:\.\d{1,3}){3})$') { return , @((ConvertTo-IpInteiro $Matches[1]), (ConvertTo-IpInteiro $Matches[2])) }
    if ($item -match '^(\d{1,3}(?:\.\d{1,3}){3})/(\d{1,2})$') {
        $bits = [int]$Matches[2]; if ($bits -gt 32) { return $null }
        $tam = [math]::Pow(2, 32 - $bits); $ini = [math]::Floor((ConvertTo-IpInteiro $Matches[1]) / $tam) * $tam
        return , @([uint64]$ini, [uint64]($ini + $tam - 1))
    }
    if ($item -match '^(\d{1,3}(?:\.\d{1,3}){3})/(\d{1,3}(?:\.\d{1,3}){3})$') {
        $m = ConvertTo-IpInteiro $Matches[2]; $tam = 4294967296 - $m; $ini = [math]::Floor((ConvertTo-IpInteiro $Matches[1]) / $tam) * $tam
        return , @([uint64]$ini, [uint64]($ini + $tam - 1))
    }
    return $null
}

# Lista de itens -> intervalos ordenados e fundidos (tocando ou sobrepostos viram um). $null se algum item nao for IPv4 comparavel.
function Get-IntervalosUnidos($itens) {
    $lista = @()
    foreach ($i in @($itens)) {
        $v = ConvertTo-IntervaloIp "$i"
        if ($null -eq $v) { return $null }
        $lista += , $v
    }
    $ord = @($lista | Sort-Object { $_[0] })
    $out = @()
    foreach ($v in $ord) {
        if ($out.Count -gt 0 -and $v[0] -le ($out[-1][1] + 1)) { if ($v[1] -gt $out[-1][1]) { $out[-1][1] = $v[1] } }
        else { $out += , @($v[0], $v[1]) }
    }
    return , $out
}

# Problemas da regra Block lida do firewall (vazio = ok). Os parametros sao os valores crus de Get-NetFirewallRule / PortFilter / AddressFilter.
function Get-ProblemasDaRegra($enabled, $direction, $action, $profile, $protocol, $localPort, $remoteAddress, [string]$geradorIp, [int]$porta) {
    $erros = @()
    if ("$enabled" -ne 'True') { $erros += "a regra nao esta habilitada (Enabled=$enabled)" }
    if ("$direction" -ne 'Inbound') { $erros += "a regra nao e de entrada (Direction=$direction)" }
    if ("$action" -ne 'Block') { $erros += "a acao nao e Block (Action=$action)" }
    if ("$profile" -ne 'Any') { $erros += "o perfil nao e Any (Profile=$profile): o Block tem de valer em Privado, Publico e Dominio" }
    if ("$protocol" -ne 'TCP') { $erros += "o protocolo nao e TCP (Protocol=$protocol)" }
    if (@($localPort).Count -ne 1 -or "$($localPort)" -ne "$porta") { $erros += "LocalPort nao e exatamente $porta (LocalPort=$(@($localPort) -join ','))" }
    $itens = @($remoteAddress | Where-Object { $null -ne $_ } | ForEach-Object { "$_".Trim() })
    $v6 = @($itens | Where-Object { $_ -match ':' }); $v4 = @($itens | Where-Object { $_ -notmatch ':' })
    if ($v6.Count -eq 0) { $erros += "falta o item IPv6 total (::/0 ou ::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff): o gerador tem IPv6 e o Block tem de cobrir todo o IPv6 (RemoteAddress=$($itens -join ','))" }
    foreach ($x in $v6) { if (-not (Test-Ipv6Total $x)) { $erros += "item IPv6 '$x' nao reconhecido: so vale todo o IPv6 (::/0 ou ::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff)" } }
    $atual = Get-IntervalosUnidos $v4
    if ($null -eq $atual) { $erros += "RemoteAddress nao e uma lista de IPv4 comparavel (RemoteAddress=$($itens -join ','))" }
    else {
        $esperado = Get-IntervalosUnidos (Get-FaixasComplementares $geradorIp)
        $a = ($atual | ForEach-Object { "$($_[0])-$($_[1])" }) -join ';'
        $e = ($esperado | ForEach-Object { "$($_[0])-$($_[1])" }) -join ';'
        if ($a -ne $e) { $erros += "RemoteAddress ($(@($remoteAddress) -join ',')) nao e exatamente o complemento de $geradorIp (esperado: todo o IPv4 menos ele: $(@(Get-FaixasComplementares $geradorIp) -join ', ')). Se o IP do gerador mudou (DHCP), rode o Criar de novo com o IP novo (como administrador): o gerador fica bloqueado ate la, a porta nao abre." }
    }
    return , $erros
}
