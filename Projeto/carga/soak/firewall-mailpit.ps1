<#
.SYNOPSIS
  Regra PERMANENTE do firewall do Windows (no HOST, como Administrador, UMA vez) que fecha a API do Mailpit do cluster soak (NodePort 30825 -> host 8025, kind.yaml) para todo mundo
  MENOS o IP do gerador e TODO o IPv6. Fica ate o fim dos testes (issue #232); `Remover` roda no fim.

.DESCRIPTION
  Por que existe: o kind publica a 8025 em 0.0.0.0 (extraPortMappings) e o Kubernetes nao filtra por origem no kind (loadBalancerSourceRanges nao vale). A restricao
  OBRIGATORIA e esta regra; somam-se a basic auth do Mailpit (MAILPIT_AUTH efemera) e o fato de a porta so ter Service por tras no overlay soak.
  UMA regra, 'TCC soak mailpit 8025 - bloqueio': Block, entrada, TCP, LocalPort 8025, perfil Any, RemoteAddress = TODAS as faixas do IPv4 MENOS o gerador (0.0.0.0-<ip-1>, <ip+1>-255.255.255.255)
  MAIS todo o IPv6 (::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff; o gerador tem IPv6 global, mas o k6 usa a URL IPv4, entao o IPv6 inteiro fica bloqueado).
  No Windows o Block vence o Allow (o Docker Desktop e varias regras do Windows liberam todas as portas, qualquer origem), entao ele NAO pode incluir o gerador: por isso as faixas complementares,
  nao 'Any'.   NAO ha regra Allow so do gerador (uma peca a menos): quem libera o gerador sao as Allow que o host ja tem (`-Acao Status` as lista); se nao houver nenhuma, o `Criar` avisa.
  O IP do gerador pode mudar (DHCP): ele NUNCA e fixo aqui, vem de -GeradorIp (use a variavel de ambiente GERADOR_IP). O `verificar-soak pre` de cada bloco confere a regra contra o IP ATUAL do gerador; se
  mudou, aborta pedindo o `Criar` de novo com o IP novo (fail-safe: o gerador fica bloqueado, a porta nao abre).
  Acoes:
    Criar    -GeradorIp <ip>   RECRIA a regra (apaga e cria de novo; nada de Set-NetFirewallRule parcial). Recusa 'Any', faixa, mascara, 0.0.0.0 e 255.255.255.255. Uma vez so, ate o fim dos testes;
                               o `verificar-soak pre` de cada bloco a confere sem Administrador (soak\conferir-firewall.ps1).
    Remover                    apaga a regra e CONFIRMA: Get-NetFirewallRule nao a acha (codigo 1 se sobrar). No fim dos testes.
    Status                     mostra a regra (e, com -GeradorIp, se esta EXATAMENTE como deveria) e as regras Allow de entrada habilitadas que cobrem a porta (porta Any/faixa que a contem, origem
                               Any/*/LocalSubnet/Intranet/faixa, programa do Docker ou sem programa), avisando que o Block e o que protege. Nao exige Administrador.
  -WhatIf (nativo: SupportsShouldProcess) mostra o que seria feito SEM mexer no firewall nem exigir Administrador. O selftest (firewall-mailpit.selftest.ps1) cobre as faixas e o -WhatIf.

.EXAMPLE
  $env:GERADOR_IP = '192.168.68.54'                                          # o IP ATUAL do gerador (DHCP: pode mudar; mudou = Criar de novo)
  .\soak\firewall-mailpit.ps1 -Acao Criar -GeradorIp $env:GERADOR_IP          # UMA vez, antes do 1o bloco (Administrador)
  .\soak\firewall-mailpit.ps1 -Acao Criar -GeradorIp $env:GERADOR_IP -WhatIf  # so mostra
  .\soak\firewall-mailpit.ps1 -Acao Status -GeradorIp $env:GERADOR_IP         # a regra confere? + Allow largas
  .\soak\firewall-mailpit.ps1 -Acao Remover                                  # no FIM dos testes (Administrador); confirma que sumiu
  # prova (criterio de aceite): da LAN, de OUTRA maquina que nao o gerador:   curl.exe -m 5 http://<host>:8025/api/v1/messages        -> timeout
  #                              do gerador, sem credencial:                    curl.exe -m 5 -i http://<host>:8025/api/v1/messages      -> 401
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][ValidateSet('Criar', 'Remover', 'Status')][string]$Acao,
    [string]$GeradorIp = '',
    [ValidateRange(1, 65535)][int]$Porta = 8025,
    [string]$Regra = 'TCC soak mailpit 8025 - bloqueio'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-firewall.ps1')

function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

function Show-AllowsLargos {
    $largos = @(Get-AllowsLargos $Porta @($Regra))
    if ($largos.Count -eq 0) {
        Warn "Nenhuma outra regra Allow de entrada cobre a porta $Porta para origem larga: sem ela o gerador NAO alcanca a API (o Windows barra por padrao). Se o curl do gerador der timeout, crie uma Allow do IP dele na $Porta."
        return
    }
    Write-Host "Regras Allow de ENTRADA habilitadas que cobrem a porta $Porta para origem larga (liberam o gerador, e deixariam a LAN inteira passar sem o Block):"
    foreach ($l in $largos) { Write-Host ("   - '{0}'  programa={1}  porta={2}  origem={3}" -f $l.Nome, $(if ($l.Programa) { $l.Programa } else { 'qualquer' }), $l.Porta, $l.Origem) }
    Warn "O que protege e a regra Block '$Regra' (o Block vence o Allow); confirme com o curl da LAN (deve dar timeout)."
}

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin -and -not $WhatIfPreference -and $Acao -ne 'Status') { Fail 'Rode como Administrador (ou use -WhatIf para so ver o que seria feito).' }

switch ($Acao) {
    'Criar' {
        if (-not (Test-IpUnico $GeradorIp)) { Fail "-GeradorIp '$GeradorIp' invalido: use o IPv4 UNICO do gerador (nao 'Any', faixa, mascara, 0.0.0.0 nem 255.255.255.255)." }
        $faixas = @(Get-FaixasComplementares $GeradorIp) + $Ipv6Total   # IPv4 menos o gerador + TODO o IPv6
        if ($PSCmdlet.ShouldProcess($Regra, "recriar: Block TCP $Porta de entrada para $($faixas -join ', ') (todos os perfis); o gerador $GeradorIp fica de fora do IPv4 e o IPv6 inteiro fica bloqueado")) {
            if (Get-NetFirewallRule -DisplayName $Regra -ErrorAction SilentlyContinue) { Remove-NetFirewallRule -DisplayName $Regra }
            New-NetFirewallRule -DisplayName $Regra -Direction Inbound -Protocol TCP -LocalPort $Porta -Action Block -Profile Any -RemoteAddress $faixas | Out-Null
            Ok "Regra '$Regra' criada: Block, entrada, TCP $Porta, todo o IPv4 menos o gerador $GeradorIp e TODO o IPv6 ($($faixas -join ', ')). Fica ate o fim dos testes (Remover). Confira o formato real: -Acao Status -GeradorIp $GeradorIp."
        }
        if ($admin) { Show-AllowsLargos }
        Get-NetConnectionProfile -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_.NetworkCategory -eq 'Public') { Warn "A rede '$($_.Name)' esta como Publica: confira que o gerador alcanca a porta (depende das regras Allow desse perfil)." }
        }
        Write-Host "Prova: de OUTRA maquina da LAN, curl -m 5 http://<host>:$Porta/api/v1/messages = timeout; do gerador, sem credencial = 401. No fim dos testes:  .\soak\firewall-mailpit.ps1 -Acao Remover"
    }
    'Remover' {
        if ($PSCmdlet.ShouldProcess($Regra, 'remover a regra de firewall')) {
            if (Get-NetFirewallRule -DisplayName $Regra -ErrorAction SilentlyContinue) { Remove-NetFirewallRule -DisplayName $Regra }
            if (Get-NetFirewallRule -DisplayName $Regra -ErrorAction SilentlyContinue) { Fail "Continua no firewall: '$Regra'. Remova a mao: Remove-NetFirewallRule -DisplayName '$Regra'." }
            Ok "Confirmado: Get-NetFirewallRule nao acha '$Regra'."
        }
    }
    'Status' {
        $r = Get-NetFirewallRule -DisplayName $Regra -ErrorAction SilentlyContinue
        if ($r) {
            $af = $r | Get-NetFirewallAddressFilter; $pf = $r | Get-NetFirewallPortFilter
            Write-Host "regra '$Regra': habilitada=$($r.Enabled) acao=$($r.Action) perfil=$($r.Profile) protocolo=$($pf.Protocol) porta=$($pf.LocalPort) origem=$($af.RemoteAddress -join ',')"
            if ($GeradorIp) {
                $erros = @(Get-ProblemasDaRegra $r.Enabled $r.Direction $r.Action $r.Profile $pf.Protocol $pf.LocalPort $af.RemoteAddress $GeradorIp $Porta)
                if ($erros.Count -eq 0) { Ok "A regra esta exatamente como deveria para o gerador $GeradorIp." } else { foreach ($e in $erros) { Warn $e } }
            }
        } else { Write-Host "regra '$Regra': nao existe." }
        Show-AllowsLargos
    }
}
