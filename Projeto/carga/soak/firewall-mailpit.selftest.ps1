# Auto-teste do firewall-mailpit.ps1 (issue #232), SEM tocar no firewall: as funcoes puras (faixas complementares, porta coberta, origem larga) e o -WhatIf (nativo) do script.
# Uso:  powershell -NoProfile -ExecutionPolicy Bypass -File soak\firewall-mailpit.selftest.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-firewall.ps1')
$falhas = 0
function Confere($nome, $obtido, $esperado) {
    if ("$obtido" -eq "$esperado") { Write-Host "   [ok]    $nome" }
    else { Write-Host "   [FALHA] $nome`n           esperado '$esperado', veio '$obtido'"; $script:falhas++ }
}

Write-Host '==> faixas complementares (a regra Block NAO pode incluir o gerador)'
Confere 'ip comum: 192.168.68.54' (@(Get-FaixasComplementares '192.168.68.54') -join ' | ') '0.0.0.0-192.168.68.53 | 192.168.68.55-255.255.255.255'
Confere 'fronteira de octeto: 10.0.1.0' (@(Get-FaixasComplementares '10.0.1.0') -join ' | ') '0.0.0.0-10.0.0.255 | 10.0.1.1-255.255.255.255'
Confere 'fronteira de octeto: 10.0.0.255' (@(Get-FaixasComplementares '10.0.0.255') -join ' | ') '0.0.0.0-10.0.0.254 | 10.0.1.0-255.255.255.255'
Confere 'extremo baixo: 0.0.0.1 (a 1a faixa e so o 0.0.0.0)' (@(Get-FaixasComplementares '0.0.0.1') -join ' | ') '0.0.0.0-0.0.0.0 | 0.0.0.2-255.255.255.255'
Confere 'extremo alto: 255.255.255.254 (a 2a faixa e so o 255.255.255.255)' (@(Get-FaixasComplementares '255.255.255.254') -join ' | ') '0.0.0.0-255.255.255.253 | 255.255.255.255-255.255.255.255'
Confere 'ida e volta do inteiro' (ConvertFrom-IpInteiro (ConvertTo-IpInteiro '203.0.113.7')) '203.0.113.7'
# o gerador nunca esta em nenhuma faixa; qualquer outro IP esta em exatamente uma
foreach ($ip in '192.168.68.54', '10.0.0.255', '0.0.0.1', '255.255.255.254') {
    $g = ConvertTo-IpInteiro $ip
    $emFaixa = @(@(Get-FaixasComplementares $ip) | Where-Object { $a, $b = $_ -split '-'; $g -ge (ConvertTo-IpInteiro $a) -and $g -le (ConvertTo-IpInteiro $b) }).Count
    Confere "o proprio gerador $ip fica FORA de todas as faixas" $emFaixa 0
}
$vizinhos = @('192.168.68.53', '192.168.68.55', '192.168.68.1', '8.8.8.8', '0.0.0.0', '255.255.255.255')
foreach ($v in $vizinhos) {
    $g = ConvertTo-IpInteiro $v
    $n = @(@(Get-FaixasComplementares '192.168.68.54') | Where-Object { $a, $b = $_ -split '-'; $g -ge (ConvertTo-IpInteiro $a) -and $g -le (ConvertTo-IpInteiro $b) }).Count
    Confere "o IP $v esta em exatamente uma faixa (bloqueado)" $n 1
}

Write-Host '==> IP aceito'
foreach ($ok in '192.168.68.54', '10.0.0.1', '0.0.0.1', '255.255.255.254') { Confere "aceita $ok" (Test-IpUnico $ok) $true }
foreach ($ruim in '', 'Any', '192.168.68.0/24', '192.168.68.1-192.168.68.9', '0.0.0.0', '255.255.255.255', '300.1.1.1', '1.2.3', '1.2.3.4,5.6.7.8') { Confere "recusa '$ruim'" (Test-IpUnico $ruim) $false }

Write-Host '==> porta coberta / origem larga (as regras Allow largas que o Status lista)'
Confere "porta Any cobre 8025" (Test-PortaCoberta 'Any' 8025) $true
Confere "porta 8025 cobre 8025" (Test-PortaCoberta '8025' 8025) $true
Confere "faixa 8000-9000 cobre 8025" (Test-PortaCoberta '8000-9000' 8025) $true
Confere "faixa 9000-9100 nao cobre 8025" (Test-PortaCoberta '9000-9100' 8025) $false
Confere "lista 80,8025 cobre 8025" (Test-PortaCoberta @('80', '8025') 8025) $true
Confere "porta 80 nao cobre 8025" (Test-PortaCoberta '80' 8025) $false
foreach ($larga in 'Any', '*', 'LocalSubnet', 'Intranet', 'Internet', '192.168.0.0/16', '10.0.0.1-10.0.0.9') { Confere "origem '$larga' e larga" (Test-OrigemLarga $larga) $true }
Confere "origem de um IP unico nao e larga" (Test-OrigemLarga '192.168.68.54') $false

foreach ($prog in 'Any', '', 'C:\Program Files\Docker\Docker\resources\com.docker.backend.exe', 'C:\Program Files\Docker\Docker\resources\vpnkit.exe', 'C:\x\vpnkit.exe') { Confere "programa '$prog' pode escutar a 8025" (Test-ProgramaRelevante $prog) $true }
foreach ($prog in '%SystemRoot%\system32\spoolsv.exe', 'System', '%systemroot%\system32\WUDFHost.exe') { Confere "programa '$prog' nao libera a 8025" (Test-ProgramaRelevante $prog) $false }

Confere 'regra de pacote UWP (programa Any, pacote S-1-15-2-...) nao libera a 8025' (Test-ProgramaRelevante 'Any' 'S-1-15-2-1') $false
Confere 'regra de app UWP com Owner (Teams, Xbox, To Do: programa Any, pacote vazio, Owner = SID do usuario) nao libera a 8025' (Test-ProgramaRelevante 'Any' 'Any' 'S-1-5-21-1-2-3-1001') $false
Confere 'regra sem Owner nem pacote (Docker) continua relevante' (Test-ProgramaRelevante 'C:\x\com.docker.backend.exe' 'Any' '') $true

Write-Host '==> perfil ativo: so conta a Allow larga cujo Profile inclui uma rede ativa (as do Docker sao Public e as redes do host sao Private)'
Confere 'Public com so Private ativo: nao conta' (Test-PerfilAtivo 'Public' @('Private')) $false
Confere 'Any conta' (Test-PerfilAtivo 'Any' @('Private')) $true
Confere "'Private, Public' com Private ativo conta" (Test-PerfilAtivo 'Private, Public' @('Private')) $true
Confere "'Domain, Private' com Private ativo (duas redes Private) conta" (Test-PerfilAtivo 'Domain, Private' @('Private', 'Private')) $true
Confere "'Domain, Public' com so Private ativo: nao conta" (Test-PerfilAtivo 'Domain, Public' @('Private')) $false
Confere 'Private sem nenhuma rede ativa: nao conta' (Test-PerfilAtivo 'Private' @()) $false
Confere 'Any sem nenhuma rede ativa: conta' (Test-PerfilAtivo 'Any' @()) $true
Confere 'DomainAuthenticated e o perfil Domain' (ConvertTo-PerfilFirewall 'DomainAuthenticated') 'Domain'
Confere 'Private fica Private' (ConvertTo-PerfilFirewall 'Private') 'Private'

Write-Host '==> conferencia da regra permanente (Get-ProblemasDaRegra): so o complemento EXATO do gerador passa'
$G = '192.168.68.54'
$bom = @('0.0.0.0-192.168.68.53', '192.168.68.55-255.255.255.255')
function Problemas($remote = $bom, $enabled = 'True', $dir = 'Inbound', $acao = 'Block', $perfil = 'Any', $proto = 'TCP', $porta = '8025', $v6 = '::/0') { @(Get-ProblemasDaRegra $enabled $dir $acao $perfil $proto $porta (@($remote) + @($v6 | Where-Object { $_ })) $G 8025) }
Confere 'o Criar grava o IPv6 total numa forma que o Test-Ipv6Total reconhece' (Test-Ipv6Total $Ipv6Total) $true
Confere 'regra certa: sem problemas' (Problemas).Count 0
# no formato EXATO dos chamadores (firewall-mailpit Status e conferir-firewall): $x = @(Get-ProblemasDaRegra ...), sem a funcao Problemas no meio (ela desembrulhava e escondia um item vazio)
$chamador = @(Get-ProblemasDaRegra 'True' 'Inbound' 'Block' 'Any' 'TCP' '8025' (@($bom) + '::/0') $G 8025)
Confere 'como o chamador: regra certa da 0 itens' $chamador.Count 0
$chamador = @(Get-ProblemasDaRegra 'False' 'Outbound' 'Block' 'Any' 'TCP' '8025' (@($bom) + '::/0') $G 8025)
Confere 'como o chamador: dois problemas dao 2 itens' $chamador.Count 2
Confere 'regra certa com o IPv6 na forma da faixa que o Criar grava: sem problemas' (Problemas -v6 $Ipv6Total).Count 0
Confere 'ordem invertida dos intervalos: sem problemas' (Problemas @('192.168.68.55-255.255.255.255', '0.0.0.0-192.168.68.53')).Count 0
Confere 'o mesmo complemento em CIDRs e IPs soltos (outra notacao): sem problemas' (Problemas @('0.0.0.0/2', '64.0.0.0/3', '72.0.0.0/5', '80.0.0.0/4', '96.0.0.0/3', '104.0.0.0-192.168.68.53', '192.168.68.55-255.255.255.255')).Count 0
Confere 'desabilitada' (Problemas -enabled 'False').Count 1
Confere 'de saida (Outbound)' (Problemas -dir 'Outbound').Count 1
Confere 'acao Allow' (Problemas -acao 'Allow').Count 1
Confere 'perfil Private' (Problemas -perfil 'Private').Count 1
Confere 'protocolo UDP' (Problemas -proto 'UDP').Count 1
Confere 'protocolo Any' (Problemas -proto 'Any').Count 1
Confere 'LocalPort 80' (Problemas -porta '80').Count 1
Confere 'LocalPort Any' (Problemas -porta 'Any').Count 1
Confere 'LocalPort lista 8025,80' (Problemas -porta @('8025', '80')).Count 1
Confere 'RemoteAddress Any (inclui o gerador)' (Problemas @('Any')).Count 1
Confere 'RemoteAddress * (inclui o gerador)' (Problemas @('*')).Count 1
Confere 'RemoteAddress LocalSubnet (nao comparavel)' (Problemas @('LocalSubnet')).Count 1
Confere 'so a faixa de baixo (falta o resto de cima)' (Problemas @('0.0.0.0-192.168.68.53')).Count 1
Confere 'so a de cima (falta o resto de baixo)' (Problemas @('192.168.68.55-255.255.255.255')).Count 1
Confere 'o gerador DENTRO da faixa (0.0.0.0-192.168.68.54)' (Problemas @('0.0.0.0-192.168.68.54', '192.168.68.55-255.255.255.255')).Count 1
Confere 'um buraco a mais (o vizinho .56 fora)' (Problemas @('0.0.0.0-192.168.68.53', '192.168.68.55', '192.168.68.57-255.255.255.255')).Count 1
Confere 'complemento de OUTRO gerador (192.168.68.66)' (Problemas @('0.0.0.0-192.168.68.65', '192.168.68.67-255.255.255.255')).Count 1
Confere 'um problema por propriedade quando tudo esta errado' (Problemas @('Any') 'False' 'Outbound' 'Allow' 'Private' 'UDP' '80').Count 7
Write-Host '==> conferencia da Allow do gerador (Get-ProblemasDaAllow): so o IP do gerador, TCP 8025, perfil Any'
function ProblemasAllow($remote = $G, $enabled = 'True', $dir = 'Inbound', $acao = 'Allow', $perfil = 'Any', $proto = 'TCP', $porta = '8025') { @(Get-ProblemasDaAllow $enabled $dir $acao $perfil $proto $porta $remote $G 8025) }
$chamador = @(Get-ProblemasDaAllow 'True' 'Inbound' 'Allow' 'Any' 'TCP' '8025' '192.168.68.54' $G 8025)
Confere 'como o chamador: Allow certa da 0 itens' $chamador.Count 0
$chamador = @(Get-ProblemasDaAllow 'False' 'Outbound' 'Allow' 'Any' 'TCP' '8025' '192.168.68.54' $G 8025)
Confere 'como o chamador: dois problemas dao 2 itens' $chamador.Count 2
Confere 'Allow desabilitada' (ProblemasAllow -enabled 'False').Count 1
Confere 'Allow de saida (Outbound)' (ProblemasAllow -dir 'Outbound').Count 1
Confere 'acao Block' (ProblemasAllow -acao 'Block').Count 1
Confere 'perfil Private' (ProblemasAllow -perfil 'Private').Count 1
Confere 'protocolo UDP' (ProblemasAllow -proto 'UDP').Count 1
Confere 'LocalPort 80' (ProblemasAllow -porta '80').Count 1
Confere 'LocalPort Any' (ProblemasAllow -porta 'Any').Count 1
Confere 'RemoteAddress de OUTRO gerador' (ProblemasAllow '192.168.68.66').Count 1
Confere 'RemoteAddress Any' (ProblemasAllow 'Any').Count 1
Confere 'RemoteAddress LocalSubnet' (ProblemasAllow 'LocalSubnet').Count 1
Confere 'RemoteAddress faixa 192.168.68.0/24' (ProblemasAllow '192.168.68.0/24').Count 1
Confere 'RemoteAddress faixa 192.168.68.54-192.168.68.60' (ProblemasAllow '192.168.68.54-192.168.68.60').Count 1
Confere 'RemoteAddress com o gerador e mais um IP (dois itens)' (ProblemasAllow @('192.168.68.54', '192.168.68.66')).Count 1
Confere 'RemoteAddress vazio' (ProblemasAllow $null).Count 1
Confere 'tudo errado: um problema por propriedade' (ProblemasAllow 'Any' 'False' 'Outbound' 'Block' 'Private' 'UDP' '80').Count 7
Confere 'IP de outro: a mensagem manda rodar o Criar de novo' ((@(ProblemasAllow '192.168.68.66') | Where-Object { $_ -match 'Criar de novo' }).Count) 1

Write-Host '==> IPv6: o Block cobre TODO o IPv6 (o gerador tem IPv6 global) e so as formas de "tudo" passam'
foreach ($f in '::/0', '::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff', '0:0:0:0:0:0:0:0-FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF') { Confere "IPv6 total '$f' aceito" (Test-Ipv6Total $f) $true }
foreach ($f in '::/64', '2001:db8::/32', '::1', 'fe80::1-fe80::9', '::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffe', '1::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff', '::-ffff::', 'Any', '192.168.68.55') { Confere "IPv6 '$f' NAO e todo o IPv6" (Test-Ipv6Total $f) $false }
Confere 'sem item IPv6: falta o IPv6 total (1 problema)' (Problemas -v6 @()).Count 1
Confere 'sem item IPv6: a mensagem cita o IPv6' ([bool](@(Problemas -v6 @())[0] -match 'IPv6')) $true
Confere 'IPv6 parcial (2001:db8::/32) e recusado' (Problemas -v6 '2001:db8::/32').Count 1
Confere 'IPv6 /64 e recusado' (Problemas -v6 '::/64').Count 1
Confere 'IPv6 total + um /64 junto: o /64 sobra e e recusado' (Problemas -v6 @('::/0', '2001:db8::/64')).Count 1
Confere '::/0 e a faixa juntos: sem problemas' (Problemas -v6 @('::/0', $Ipv6Total)).Count 0
Confere 'IPv6 total OK mas IPv4 do complemento de OUTRO gerador: 1 problema e a mensagem manda rodar o Criar de novo' ((@(Problemas @('0.0.0.0-192.168.68.65', '192.168.68.67-255.255.255.255')) | Where-Object { $_ -match 'Criar de novo' }).Count) 1
Confere 'CIDR /24 e mascara 255.255.255.0 sao a mesma faixa' ((Get-IntervalosUnidos @('10.1.2.0/24'))[0] -join '-') ((Get-IntervalosUnidos @('10.1.2.0/255.255.255.0'))[0] -join '-')

Write-Host '==> -WhatIf nativo do script (nao toca no firewall, nao exige Administrador)'
$script = Join-Path $PSScriptRoot 'firewall-mailpit.ps1'
$temCmdlet = [bool](Get-Command Get-NetFirewallRule -ErrorAction SilentlyContinue)
if (-not $temCmdlet) { Write-Host '   [pulado] sem o modulo NetSecurity (Get-NetFirewallRule)' }
else {
    $antes = @(Get-NetFirewallRule -DisplayName 'TCC soak mailpit 8025*' -ErrorAction SilentlyContinue).Count
    $saida = (& powershell -NoProfile -ExecutionPolicy Bypass -File $script -Acao Criar -GeradorIp 192.168.68.54 -WhatIf 2>&1 | Out-String)
    Confere 'Criar -WhatIf: mostra o Block com as faixas complementares (e o gerador fora)' ([bool]($saida -match 'What if' -and $saida -match '192\.168\.68\.54' -and $saida -match '0\.0\.0\.0-192\.168\.68\.53' -and $saida -match '192\.168\.68\.55-255\.255\.255\.255')) $true
    Confere 'Criar -WhatIf: cita tambem a Allow so do gerador (regra, TCP 8025, IP do gerador)' ([bool]($saida -match 'TCC soak mailpit 8025 - gerador' -and $saida -match 'Allow TCP 8025 de entrada para 192\.168\.68\.54 ')) $true
    Confere 'Criar -WhatIf: o IPv6 inteiro tambem entra no Block' ([bool]($saida -match 'ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff')) $true
    $saidaRem = (& powershell -NoProfile -ExecutionPolicy Bypass -File $script -Acao Remover -WhatIf 2>&1 | Out-String)
    Confere 'Remover -WhatIf: cita a regra de bloqueio e a Allow do gerador' ([bool]($saidaRem -match 'bloqueio' -and $saidaRem -match 'TCC soak mailpit 8025 - gerador')) $true
    $depois = @(Get-NetFirewallRule -DisplayName 'TCC soak mailpit 8025*' -ErrorAction SilentlyContinue).Count
    Confere '-WhatIf nao criou nem apagou nenhuma regra' $depois $antes
    & powershell -NoProfile -ExecutionPolicy Bypass -File $script -Acao Criar -GeradorIp Any -WhatIf *> $null
    Confere 'Criar com -GeradorIp Any e recusado (codigo != 0)' ($LASTEXITCODE -ne 0) $true
}

if ($falhas -gt 0) { Write-Host "`nselftest firewall-mailpit: $falhas falha(s)"; exit 1 }
Write-Host "`nselftest firewall-mailpit: OK"
