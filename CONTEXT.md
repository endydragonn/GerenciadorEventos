# Gerenciador de Eventos — Experimento de reserva

Glossário do experimento da Fase 2: reservas concorrentes de vagas em eventos, comparando o
caminho de reserva proposto (CAS) com um caminho ingênuo baseado em read model defasado.

## Language

**Reserva** (`walletevent`):
Vínculo de um usuário a um evento, com estados `pending`, `confirmed`, `expired` e `cancelled`.
_Avoid_: ingresso, inscrição

**Capacidade** (`capacity`):
Número máximo de reservas ativas (`pending` + `confirmed`) de um evento; `-1` é ilimitada.
_Avoid_: lotação, estoque

**Reserva por CAS** (caminho `cas`):
Caminho de reserva que decrementa a vaga com um `UPDATE` condicional atômico em `eventcap`. É o design proposto e o padrão.
_Avoid_: caminho seguro, caminho novo

**Caminho ingênuo** (`naive`):
Caminho de reserva que lê `event_availability` e depois insere, sem CAS. É o braço de controle do experimento.
_Avoid_: caminho antigo, caminho legado

**Braço**:
Cada um dos caminhos de reserva comparados no experimento: o braço CAS (design proposto) e o braço ingênuo (controle).
_Avoid_: variante, modo

**Disputa**:
Rajada de N clientes concorrentes tentando reservar o mesmo evento de `capacity` finita. É a unidade de execução do experimento de corretude.
_Avoid_: corrida, stress

**Read model defasado** (`event_availability`):
Tabela desnormalizada de vagas disponíveis, recalculada por job periódico e, portanto, atrasada em relação à verdade.
_Avoid_: cache

**Lag**:
Intervalo de refresh do read model (`READMODEL_AVAILABILITY_REFRESH_MS`); o atraso máximo da leitura de vagas.
_Avoid_: latência, delay

**Overselling**:
Evento com `confirmed` maior que `capacity`. É a métrica primária do experimento; `pending + confirmed` maior que `capacity` é reportado como métrica secundária. A tolerância no caminho CAS é 0.
_Avoid_: estouro, overbooking

**Limiar X**:
Primeiro degrau de carga em que o fluxo síncrono degrada, isto é, 5xx acima de 1% das reservas (só as reservas, não as confirmações) ou p95 acima de 1 s por 30 s.
_Avoid_: ponto de saturação

**Ponto da série**:
Medição do experimento em um marco da arquitetura (monólito CRUD, monólito ES, distribuído). Os números da Fase 2 são "monólito + HikariCP", não o ponto 1 definitivo.
_Avoid_: rodada
