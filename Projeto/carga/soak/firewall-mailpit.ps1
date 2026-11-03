<#
.SYNOPSIS
  Regras PERMANENTES do firewall do Windows (no HOST, como Administrador, UMA vez) que fecham a API do Mailpit do cluster soak (NodePort 30825 -> host 8025, kind.yaml) para todo mundo
  MENOS o IP do gerador e TODO o IPv6. Ficam ate o fim dos testes (issue #232); `Remover` roda no fim.

.DESCRIPTION
  Por que existe: o kind publica a 8025 em 0.0.0.0 (extraPortMappings) e o Kubernetes nao filtra por origem no kind (loadBalancerSourceRanges nao vale). A restricao
  OBRIGATORIA sao estas regras; somam-se a basic auth do Mailpit (MAILPIT_AUTH efemera) e o fato de a porta so ter Service por tras no overlay soak.
  DUAS regras:
  1) 'TCC soak mailpit 8025 - bloqueio': Block, entrada, TCP, LocalPort 8025, perfil Any, RemoteAddress = TODAS as faixas do IPv4 MENOS o gerador (0.0.0.0-<ip-1>, <ip+1>-255.255.255.255)
     MAIS todo o IPv6 (::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff; o gerador tem IPv6 global, mas o k6 usa a URL IPv4, entao o IPv6 inteiro fica bloqueado).
     No Windows o Block vence o Allow, entao ele NAO pode incluir o gerador: por isso as faixas complementares, nao 'Any'. Continua barrando o resto da LAN.
  2) 'TCC soak mailpit 8025 - gerador': Allow, entrada, TCP, LocalPort 8025, perfil Any, RemoteAddress = SO o IP do gerador. Existe porque as Allow que o host ja tem NAO liberam o gerador: as do
     Docker Desktop (com.docker.backend.exe) sao so do perfil Public e as redes do host (Ethernet, Tailscale) sao Private, entao o Windows barra a 8025 por padrao (o gerador leva timeout). A Allow
     nao alarga nada: o escopo dela e so o gerador, que ja fica fora do Block.
  O IP do gerador pode mudar (DHCP): ele NUNCA e fixo aqui, vem de -GeradorIp (use a variavel de ambiente GERADOR_IP). O `verificar-soak pre` de cada bloco confere AS DUAS regras contra o IP ATUAL do gerador; se
  mudou, aborta pedindo o `Criar` de novo com o IP novo (fail-safe: o gerador fica bloqueado/sem Allow, a porta nao abre).
  Acoes:
    Criar    -GeradorIp <ip>   RECRIA as duas regras (apaga e cria de novo; nada de Set-NetFirewallRule parcial). Recusa 'Any', faixa, mascara, 0.0.0.0 e 255.255.255.255. Uma vez so, ate o fim dos testes;
                               o `verificar-soak pre` de cada bloco as confere sem Administrador (soak\conferir-firewall.ps1).
    Remover                    apaga as duas regras e CONFIRMA: Get-NetFirewallRule nao acha nenhuma (codigo 1 se sobrar). No fim dos testes.
    Status                     mostra as duas regras (e, com -GeradorIp, se estao EXATAMENTE como deveriam) e as outras regras Allow de entrada habilitadas que cobrem a porta (porta Any/faixa que a contem,
                               origem Any/*/LocalSubnet/Intranet/faixa, programa do Docker ou sem programa, perfil de uma rede ATIVA, fora as de app UWP), so para informar que o Block e o que protege.
                               Nao exige Administrador.
  -WhatIf (nativo: SupportsShouldProcess) mostra o que seria feito SEM mexer no firewall nem exigir Administrador. O selftest (firewall-mailpit.selftest.ps1) cobre as faixas, a Allow e o -WhatIf.

.EXAMPLE
  $env:GERADOR_IP = '192.168.68.54'                                          # o IP ATUAL do gerador (DHCP: pode mudar; mudou = Criar de novo)
  .\soak\firewall-mailpit.ps1 -Acao Criar -GeradorIp $env:GERADOR_IP          # UMA vez, antes do 1o bloco (Administrador)
  .\soak\firewall-mailpit.ps1 -Acao Criar -GeradorIp $env:GERADOR_IP -WhatIf  # so mostra
  .\soak\firewall-mailpit.ps1 -Acao Status -GeradorIp $env:GERADOR_IP         # as duas regras conferem? + outras Allow largas
  .\soak\firewall-mailpit.ps1 -Acao Remover                                  # no FIM dos testes (Administrador); confirma que sumiram
  # prova (criterio de aceite): da LAN, de OUTRA maquina que nao o gerador:   curl.exe -m 5 http://<host>:8025/api/v1/messages        -> timeout
  #                              do gerador, sem credencial:                    curl.exe -m 5 -i http://<host>:8025/api/v1/messages      -> 401
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][ValidateSet('Criar', 'Remover', 'Status')][string]$Acao,
    [string]$GeradorIp = '',
    [ValidateRange(1, 65535)][int]$Porta = 8025,
    [string]$Regra = 'TCC soak mailpit 8025 - bloqueio',
    [string]$RegraAllow = 'TCC soak mailpit 8025 - gerador'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib-firewall.ps1')

function Ok($m)   { Write-Host "[ ok ] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[erro] $m" -ForegroundColor Red; exit 1 }

function Show-AllowsLargos {
    $largos = @(Get-AllowsLargos $Porta @($Regra, $RegraAllow))
    if ($largos.Count -eq 0) { Write-Host "Nenhuma outra Allow de entrada de rede ativa cobre a porta $Porta para origem larga: so a Allow do gerador ('$RegraAllow') a libera."; return }
    Write-Host "Outras regras Allow de ENTRADA habilitadas, de uma rede ativa, que cobrem a porta $Porta para origem larga (deixariam a LAN inteira passar sem o Block):"
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
            Ok "Regra '$Regra' criada: Block, entrada, TCP $Porta, todo o IPv4 menos o gerador $GeradorIp e TODO o IPv6 ($($faixas -join ', ')). Fica ate o fim dos testes (Remover)."
        }
        if ($PSCmdlet.ShouldProcess($RegraAllow, "recriar: Allow TCP $Porta de entrada para $GeradorIp (so o gerador, todos os perfis)")) {
            if (Get-NetFirewallRule -DisplayName $RegraAllow -ErrorAction SilentlyContinue) { Remove-NetFirewallRule -DisplayName $RegraAllow }
            New-NetFirewallRule -DisplayName $RegraAllow -Direction Inbound -Protocol TCP -LocalPort $Porta -Action Allow -Profile Any -RemoteAddress $GeradorIp | Out-Null
            Ok "Regra '$RegraAllow' criada: Allow, entrada, TCP $Porta, so o gerador $GeradorIp, perfil Any. Fica ate o fim dos testes (Remover). Confira o formato real: -Acao Status -GeradorIp $GeradorIp."
        }
        if ($admin) { Show-AllowsLargos }
        Get-NetConnectionProfile -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_.NetworkCategory -eq 'Public') { Warn "A rede '$($_.Name)' esta como Publica: confira que o gerador alcanca a porta (depende das regras Allow desse perfil)." }
        }
        Write-Host "Prova: de OUTRA maquina da LAN, curl -m 5 http://<host>:$Porta/api/v1/messages = timeout; do gerador, sem credencial = 401. No fim dos testes:  .\soak\firewall-mailpit.ps1 -Acao Remover"
    }
    'Remover' {
        if ($PSCmdlet.ShouldProcess("$Regra e $RegraAllow", 'remover as duas regras de firewall')) {
            foreach ($n in $Regra, $RegraAllow) {
                if (Get-NetFirewallRule -DisplayName $n -ErrorAction SilentlyContinue) { Remove-NetFirewallRule -DisplayName $n }
                if (Get-NetFirewallRule -DisplayName $n -ErrorAction SilentlyContinue) { Fail "Continua no firewall: '$n'. Remova a mao: Remove-NetFirewallRule -DisplayName '$n'." }
            }
            Ok "Confirmado: Get-NetFirewallRule nao acha '$Regra' nem '$RegraAllow'."
        }
    }
    'Status' {
        foreach ($n in $Regra, $RegraAllow) {
            $r = Get-NetFirewallRule -DisplayName $n -ErrorAction SilentlyContinue
            if (-not $r) { Write-Host "regra '$n': nao existe."; continue }
            $af = $r | Get-NetFirewallAddressFilter; $pf = $r | Get-NetFirewallPortFilter
            Write-Host "regra '$n': habilitada=$($r.Enabled) acao=$($r.Action) perfil=$($r.Profile) protocolo=$($pf.Protocol) porta=$($pf.LocalPort) origem=$($af.RemoteAddress -join ',')"
            if ($GeradorIp) {
                $erros = @(if ($n -eq $Regra) { Get-ProblemasDaRegra $r.Enabled $r.Direction $r.Action $r.Profile $pf.Protocol $pf.LocalPort $af.RemoteAddress $GeradorIp $Porta }
                           else { Get-ProblemasDaAllow $r.Enabled $r.Direction $r.Action $r.Profile $pf.Protocol $pf.LocalPort $af.RemoteAddress $GeradorIp $Porta })
                if ($erros.Count -eq 0) { Ok "'$n' esta exatamente como deveria para o gerador $GeradorIp." } else { foreach ($e in $erros) { Warn $e } }
            }
        }
        Show-AllowsLargos
    }
}
