# Funcoes puras do preparar-soak.ps1 (issue #232), em arquivo a parte para o lib-soak.selftest.ps1 testa-las sem semear nada.

# Quantos eventos QUENTES o bloco tem por padrao. S2 (X = 4000, precisa da fila e do KEDA do reservation): 1. S1 (X = 56000, a fila nunca liga): 0 = a reserva e ESPALHADA pelos
# eventos do seed (sem QUENTES_IDS o carga-soak.js usa o mapeamento par-unico do kit) e confirma de verdade. Um -Quentes explicito do operador vale mais que este padrao.
function Get-QuentesDoBloco([string]$bloco) {
    if ($bloco -eq 'S2') { return 1 }
    return 0
}

# Capacidade por evento COMUM do seed, por bloco (decisao do usuario). S1: 200 (a reserva espalhada, 60/10/30, enche os eventos nas ultimas dezenas de minutos de uma semente
# alta: a fronteira de capacidade e exercitada e o "lotado" e 400 de negocio; conta na spec, "Decisoes" 2). S2: 500 (a reserva vai ao quente; os 5% comuns nao enchem).
# Um -Capacidade explicito do operador vale mais que este padrao.
function Get-CapacidadeDoBloco([string]$bloco) {
    if ($bloco -eq 'S1') { return 200 }
    return 500
}

# Os telefones do seed saem so dos DIGITOS do sufixo (setup.sh: host = 119 + ultimos 8 digitos, attendees = 11<i> + ultimos 6), entao "${runId}q" repetia os telefones do seed principal e o
# identity recusava ("Telefone ja existente"). O sufixo dos quentes: "q0" + os 5 ultimos digitos do runId com cada digito deslocado de 5 (mod 10). O "0" a frente: um runId real (int, sem zero a
# esquerda) nunca da 6 digitos iniciados em 0, entao nenhum seed principal de outra rodada colide; o deslocamento: nem um runId com zeros a esquerda colide com o proprio. Os quentes de 2 rodadas
# so colidem se os runIds forem iguais mod 100000 (~28 h), com o banco ainda sem limpar.
function Get-SufixoQuentes([string]$runId) {
    $d = ($runId -replace '\D', '').PadLeft(5, '0'); $d = $d.Substring($d.Length - 5)
    'q0' + (-join ($d.ToCharArray() | ForEach-Object { ([int][string]$_ + 5) % 10 }))
}
