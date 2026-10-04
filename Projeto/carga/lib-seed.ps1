# Seed unico dos testes de carga: chama docs/carga/harness/setup.sh (fonte unica, a mesma do
# harness da issue #11) via Git Bash. Uso: . .\lib-seed.ps1 ; Invoke-Seed ...
#
# Cria 1 host + N usuarios + M eventos (POST /bff/seed), sem logar os usuarios (o k6 faz o
# login no setup()). Devolve @{ EventIds = int[]; Prefixo = string }.
# Precisa do clone completo do repo (usa docs\carga\harness\setup.sh) e do Git for Windows.

function Invoke-Seed {
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][int]$Usuarios,
        [Parameter(Mandatory)][int]$Eventos,
        [Parameter(Mandatory)][int]$Capacidade,
        [Parameter(Mandatory)][string]$Prefixo,    # eventos viram "<Prefixo>-E<k>"
        [Parameter(Mandatory)][string]$Sufixo,     # unico por rodada (e-mails dos usuarios)
        [Parameter(Mandatory)][string]$UsersFile,  # saida: [{email,password}] (k6)
        [Parameter(Mandatory)][string]$EventIdsFile # saida: [event_id,...] (k6)
    )
    # Nao usar o bash do PATH: no Windows costuma ser o do WSL, nao o Git Bash.
    # ProgramW6432: sob PowerShell 32 bits (ex.: make do ezwinports), ProgramFiles aponta pro (x86).
    $pf = if ($env:ProgramW6432) { $env:ProgramW6432 } else { $env:ProgramFiles }
    $bash = Join-Path $pf 'Git\bin\bash.exe'
    if (-not (Test-Path $bash)) { throw "Git Bash nao encontrado em $bash. Instale o Git for Windows." }
    $setup = Join-Path $PSScriptRoot '..\..\docs\carga\harness\setup.sh'
    if (-not (Test-Path $setup)) { throw "setup.sh nao encontrado ($setup). Use o clone completo do repositorio." }
    $setup = (Resolve-Path $setup).Path

    $vars = @{
        BASE_URL = $BaseUrl; N = "$Usuarios"; EVENTS = "$Eventos"; CAPACITY = "$Capacidade"
        EVENT_NAME_PREFIX = $Prefixo; SUFIXO = $Sufixo; SEM_TOKENS = '1'
        USERS_FILE = $UsersFile; EVENT_IDS_FILE = $EventIdsFile
    }
    foreach ($k in $vars.Keys) { Set-Item -Path "Env:$k" -Value $vars[$k] }
    try {
        & $bash $setup | Out-Null    # progresso vai pelo stderr, direto no console
        if ($LASTEXITCODE -ne 0) { throw "setup.sh falhou (codigo $LASTEXITCODE)." }
    } finally {
        foreach ($k in $vars.Keys) { Remove-Item -Path "Env:$k" -ErrorAction SilentlyContinue }
    }
    $ids = @((Get-Content $EventIdsFile -Raw | ConvertFrom-Json) | ForEach-Object { [int]$_ })
    if ($ids.Count -ne $Eventos) { throw "setup.sh devolveu $($ids.Count) event_id (esperado $Eventos)." }
    return @{ EventIds = $ids; Prefixo = $Prefixo }
}
