<#
.SYNOPSIS
  Confere (SEM administrador) a regra permanente de firewall da API do Mailpit (issue #232): existe, habilitada, entrada, TCP, LocalPort 8025, Block, perfil Any, e o RemoteAddress e EXATAMENTE
  o complemento do IP do gerador (o gerador fora, o resto do IPv4 dentro) MAIS todo o IPv6 (::/0 ou ::-ffff:...:ffff; qualquer outra forma IPv6 reprova). O IP do gerador e o ATUAL (DHCP), informado na
  partida; se mudou, reprova pedindo o Criar de novo com o IP novo. Chamado pelo verificar-soak.sh pre (powershell.exe). Codigo 0 = ok, 1 = problema (imprime cada um). So le o firewall.
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File soak\conferir-firewall.ps1 -GeradorIp $env:GERADOR_IP
#>
param(
    [Parameter(Mandatory)][string]$GeradorIp,
    [ValidateRange(1, 65535)][int]$Porta = 8025,
    [string]$Regra = 'TCC soak mailpit 8025 - bloqueio'
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
Write-Host "[ ok ] regra '$Regra': Block, entrada, TCP $Porta, todo o IPv4 menos o gerador $GeradorIp, todo o IPv6"
exit 0
