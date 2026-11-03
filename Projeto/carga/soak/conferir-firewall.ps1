<#
.SYNOPSIS
  Confere (SEM administrador) as DUAS regras permanentes de firewall da API do Mailpit (issue #232). Allow do gerador ('TCC soak mailpit 8025 - gerador'): existe, habilitada, entrada, Allow, TCP, LocalPort 8025,
  perfil Any, RemoteAddress exatamente o IP ATUAL do gerador (um item so); sem ela o gerador nao alcanca a porta (rode o Criar de novo como administrador). Block: existe, habilitada, entrada, TCP, LocalPort 8025, Block, perfil Any, e o RemoteAddress e EXATAMENTE
  o complemento do IP do gerador (o gerador fora, o resto do IPv4 dentro) MAIS todo o IPv6 (::/0 ou ::-ffff:...:ffff; qualquer outra forma IPv6 reprova). O IP do gerador e o ATUAL (DHCP), informado na
  partida; se mudou, reprova pedindo o Criar de novo com o IP novo. Chamado pelo verificar-soak.sh pre (powershell.exe). Codigo 0 = ok, 1 = problema (imprime cada um). So le o firewall.
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File soak\conferir-firewall.ps1 -GeradorIp $env:GERADOR_IP
#>
param(
    [Parameter(Mandatory)][string]$GeradorIp,
    [ValidateRange(1, 65535)][int]$Porta = 8025,
    [string]$Regra = 'TCC soak mailpit 8025 - bloqueio',
    [string]$RegraAllow = 'TCC soak mailpit 8025 - gerador'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-firewall.ps1')
if (-not (Test-IpUnico $GeradorIp)) { Write-Host "[erro] -GeradorIp '$GeradorIp' invalido: use o IPv4 unico do gerador."; exit 1 }
$regras = @(Get-NetFirewallRule -DisplayName $Regra -ErrorAction SilentlyContinue)
if ($regras.Count -ne 1) {
    Write-Host "[erro] esperada exatamente 1 regra '$Regra' no firewall, achei $($regras.Count). Crie-a (Administrador): .\soak\firewall-mailpit.ps1 -Acao Criar -GeradorIp $GeradorIp"
    exit 1
}
$r = $regras[0]
$pf = $r | Get-NetFirewallPortFilter
$af = $r | Get-NetFirewallAddressFilter
$erros = @(Get-ProblemasDaRegra $r.Enabled $r.Direction $r.Action $r.Profile $pf.Protocol $pf.LocalPort $af.RemoteAddress $GeradorIp $Porta)
if ($erros.Count -gt 0) { foreach ($e in $erros) { Write-Host "[erro] regra '$Regra': $e" }; exit 1 }
$alvos = @(Get-NetFirewallRule -DisplayName $RegraAllow -ErrorAction SilentlyContinue)
if ($alvos.Count -ne 1) {
    Write-Host "[erro] esperada exatamente 1 regra '$RegraAllow' no firewall, achei $($alvos.Count): sem ela o gerador nao alcanca a $Porta. Rode o Criar de novo como administrador: .\soak\firewall-mailpit.ps1 -Acao Criar -GeradorIp $GeradorIp"
    exit 1
}
$a = $alvos[0]
$apf = $a | Get-NetFirewallPortFilter
$aaf = $a | Get-NetFirewallAddressFilter
$erros = @(Get-ProblemasDaAllow $a.Enabled $a.Direction $a.Action $a.Profile $apf.Protocol $apf.LocalPort $aaf.RemoteAddress $GeradorIp $Porta)
if ($erros.Count -gt 0) { foreach ($e in $erros) { Write-Host "[erro] regra '$RegraAllow': $e" }; exit 1 }
Write-Host "[ ok ] regra '$Regra': Block, entrada, TCP $Porta, todo o IPv4 menos o gerador $GeradorIp, todo o IPv6"
Write-Host "[ ok ] regra '$RegraAllow': Allow, entrada, TCP $Porta, so o gerador $GeradorIp, perfil Any"
exit 0
