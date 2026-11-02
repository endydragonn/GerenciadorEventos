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
