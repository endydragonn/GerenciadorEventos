# Atalhos dos testes de carga (Windows: usa PowerShell). Rode de Projeto/carga:  make help
# Requer GNU make (ex.: winget install ezwinports.make  ou  choco install make).
SHELL := powershell.exe
.SHELLFLAGS := -NoProfile -ExecutionPolicy Bypass -Command

# ---- parametros (sobrescreva: make test-rpm HOST_IP=192.168.0.10 RPM=2000)
HOST_IP        ?=
VUS            ?= 50,100,200
CAPACIDADE     ?= 20
READPATH       ?= cas
RPM            ?= 10000
DURACAO        ?= 90
USUARIOS       ?= 200
MAX_CONN       ?= 300
EVENT_ID       ?=
PREFIXO        ?=
TEMPO          ?= 10m
RPM_MIN        ?= 1000
RPM_MAX        ?= 12000
PASSO          ?= 30
PCT_LEITURA    ?= 30
PCT_CONFIRMA   ?= 100
PCT_CONFIRMA_REAL ?= 60
ROTULO         ?=
SEMENTE        ?= 0
# matriz do experimento (test-matriz): a senha do Postgres NUNCA entra aqui (o make ecoa a linha de
# comando): defina PGPASSWORD no ambiente do PowerShell do gerador (ver README).
REPETICOES     ?= 10
PG_PORTA       ?= 5433
PGUSER         ?= admin
PGDATABASE     ?= meu_banco
GERADOR_IP     ?=
# escada de carga (test-degraus, issue #14). POOL_HIKARI/MAX_CONN so REGISTRAM no CSV o que o host roda (o padrao do
# pool do SERVICO reservation, o caminho quente desde a #33, e 1000: se o host usar outro, informe). DEG_DURACAO: >= 60 e multiplo de 30. MAX_VUS = VUs do k6
# todos PRE-alocados (~1-2 MB cada): dimensione pela RAM do gerador.
DEG_INICIO     ?= 12000
DEG_PASSO      ?= 4000
DEG_MAX        ?= 40000
DEG_DURACAO    ?= 60
MAX_VUS        ?= 2000
# MAPEAMENTO: sequencial (padrao; U reservas seguidas no mesmo evento = evento disputado) | intercalado (E reservas seguidas em eventos diferentes = capacidade do host)
MAPEAMENTO     ?= sequencial
POOL_HIKARI    ?= 1000
CSV            ?=
# harness definitivo (#38): CENARIO do test-rpm (escrita | leitura); COLETA_S = duracao da coleta do host (0 = ate o Ctrl+C)
CENARIO        ?= escrita
COLETA_S       ?= 0
# Rodada 4 (#178). ESCADA ACIMA DO X com o admission ligado (test-degraus): PCT_ACOMPANHA = % das 202 que acompanham o estado (as demais so contam o 202; baixe
# acima do X: uma iteracao acompanhada dura ate o TTL da fila de 30 s e esgota os VUs), CONSULTA_MS/TETO_CONSULTA_S = intervalo e teto da consulta, PARADA_P95_MS
# (0 = sem parada) e PARADA_JANELA_S = parar a escada quando o p95 da reserva numa janela passa do limite (latencia que nao se recupera).
# ARRANJO e PARAMS_AUTOESCALA so RASTREIAM o que o host roda (vao para o CSV): sem-autoescala | autoescala (+completa se as imagens forem as completas; slim e o padrao desde a #197); PARAMS_AUTOESCALA="AUTOESCALA_GATEWAY_RPS=500;..." (sem espaco).
# COMPOSE_EXTRAS = overrides do HOST depois do de carga (setup-host/host-rpm), lista separada por virgula: docker-compose.completa.yml,docker-compose.autoescala.yml (as imagens slim sao o padrao desde a #197; o completa as troca pelas completas)
PCT_ACOMPANHA  ?= 100
CONSULTA_MS    ?= 1000
TETO_CONSULTA_S ?= 35
PARADA_P95_MS  ?= 0
PARADA_JANELA_S ?= 60
SETUP_S        ?= 0
ARRANJO        ?= sem-autoescala
PARAMS_AUTOESCALA ?=
COM_CHAVE      ?=
COMPOSE_EXTRAS ?=
# Rodada 5 (#204, saga stock + wallet): limite (s) para uma orfa da saga se resolver no verificar-rpm (codigo 4 se nao convergir); vazio = ORPHAN_MS + 3 x SCAN_MS lidos do container reservation (45 s se nao achar)
ORFA_LIMITE_S  ?=

need_ip = $(if $(HOST_IP),,$(error Informe HOST_IP=<ip do host>))

.PHONY: limpar-banco selftests help setup-host setup-gerador host-rpm test-disputa test-rpm test-aleatorio test-disputa-real test-rpm-real test-aleatorio-real test-matriz test-matriz-cas test-matriz-naive report-matriz test-degraus report-degraus verificar-disputa verificar-rpm telemetria-host coleta-host
.DEFAULT_GOAL := help

help:
	@Write-Host 'SETUP'
	@Write-Host '  make setup-host      [READPATH=cas|naive GERADOR_IP=<ip>]   (no HOST: sobe a stack com override de carga; firewall 8081; a 5433 so abre COM GERADOR_IP, restrita a ele, p/ a matriz)'
	@Write-Host '  make setup-gerador   HOST_IP=<ip>                           (no GERADOR: k6 + latencia base)'
	@Write-Host 'TESTE 1 - disputa de vagas (corretude, 1 evento, rajada)'
	@Write-Host '  make test-disputa    HOST_IP=<ip> [VUS=50,100,200 CAPACIDADE=20 READPATH=cas PCT_CONFIRMA=100]'
	@Write-Host '  make coleta-host     [ROTULO=<nome> COLETA_S=0]              (no HOST: lags, outbox, pooler, pg_stat_statements; #38)'
	@Write-Host '  make verificar-disputa EVENT_ID=<id>                        (no HOST)'
	@Write-Host 'TESTE 2 - taxa fixa (RPM, varios eventos, saturacao)'
	@Write-Host '  make host-rpm        [MAX_CONN=300 READPATH=cas]            (no HOST: stack + max_connections)'
	@Write-Host '  make test-rpm        HOST_IP=<ip> [RPM=10000 DURACAO=90 USUARIOS=200 PCT_CONFIRMA=100 CENARIO=escrita|leitura]'
	@Write-Host '  make verificar-rpm   PREFIXO=Rpm-<id>                       (no HOST)'
	@Write-Host 'TESTE 3 - carga aleatoria (taxa sorteada a cada PASSO s, reservas + leituras, por TEMPO)'
	@Write-Host '  make test-aleatorio  HOST_IP=<ip> [TEMPO=10m RPM_MIN=1000 RPM_MAX=12000 PASSO=30 PCT_LEITURA=30 PCT_CONFIRMA=100 SEMENTE=0]'
	@Write-Host '  make verificar-rpm   PREFIXO=Ale-<id>                       (no HOST, logo apos a rodada)'
	@Write-Host 'VERSAO REALISTA (cada aceita confirma ou nao ao acaso; as demais expiram em ~120 s)'
	@Write-Host '  make test-disputa-real | test-rpm-real | test-aleatorio-real   (mesmos parametros dos testes 1-3; PCT_CONFIRMA_REAL=60)'
	@Write-Host 'TELEMETRIA  make telemetria-host [ROTULO=escada-x]   (no HOST, ANTES de rodar a escada/matriz no gerador; Ctrl+C ao fim; o gerador grava a dele sozinho)'
	@Write-Host 'MATRIZ DO EXPERIMENTO (oficial: 1 braco por vez; o lag e MEDIDO, nao configurado (#29); lista de N x repeticoes; PCT_CONFIRMA=100; precisa de PGPASSWORD e psql)'
	@Write-Host '  make test-matriz     HOST_IP=<ip> [VUS=50,100,200 REPETICOES=10 CAPACIDADE=20 READPATH=cas]   (no GERADOR; READPATH = o que o host roda)'
	@Write-Host '  make test-matriz-cas HOST_IP=<ip>                           (no GERADOR: rodada 1a, braco CAS: N=100,200 x 5 rep, capacity 20; host com READPATH=cas; "Error 3" na linha make[1] = overselling no CAS)'
	@Write-Host '  make test-matriz-naive HOST_IP=<ip>   (no GERADOR: rodada 1b, braco ingenuo: N=100,200 x 5 rep, capacity 20; host com READPATH=naive; lag_obs_* = lag real do consumer)'
	@Write-Host '  make report-matriz                                          (no GERADOR: results\matriz.csv -> results\matriz.html, via Git Bash)'
	@Write-Host 'ESCADA DE CARGA - limiar X preliminar (issue #14; degraus de taxa fixa a partir de 12k RPM; host com "make host-rpm MAX_CONN=<n>")'
	@Write-Host '  make test-degraus    HOST_IP=<ip> [DEG_INICIO=12000 DEG_PASSO=4000 DEG_MAX=40000 DEG_DURACAO=60 MAX_CONN=300 POOL_HIKARI=1000 USUARIOS=200 MAX_VUS=2000 MAPEAMENTO=sequencial|intercalado]   (no GERADOR: results\degraus-<id>.csv; MAX_CONN/POOL_HIKARI = o que o host roda)'
	@Write-Host '  make report-degraus  CSV=results\degraus-<id>.csv           (no GERADOR: curva latencia/throughput x carga + degrau X, via Git Bash -> results\degraus-<id>.html)'
	@Write-Host 'RODADA 4 (#178) - escada ACIMA do X com o admission (202/fila) e a autoescala; a R4 roda o braco SEM autoescala ANTES do braco C (o override sobe topicos para 3 particoes, irreversivel)'
	@Write-Host '  make test-degraus    ... [PCT_ACOMPANHA=100 CONSULTA_MS=1000 TETO_CONSULTA_S=35 PARADA_P95_MS=0 PARADA_JANELA_S=60 SETUP_S=0 ARRANJO=sem-autoescala PARAMS_AUTOESCALA=k=v;k=v]   (no GERADOR; colunas novas no fim do CSV)'
	@Write-Host '  make setup-host | host-rpm  COMPOSE_EXTRAS=docker-compose.autoescala.yml   (no HOST: braco C; imagens completas: docker-compose.completa.yml,docker-compose.autoescala.yml; replicas achadas por label)'
	@Write-Host '  make limpar-banco    (no HOST, entre blocos; scaler PARADO: trunca, FLUSHDB, purge das filas, apaga a chave do JWT de TODAS as replicas do identity, sobe de volta e prova o JWKS)'
	@Write-Host '  make verificar-rpm   PREFIXO=Deg-<id>   (no HOST, ao fim de CADA bloco: codigo 3 se houver overselling no braco CAS = criterio de parada)'
	@Write-Host 'RODADA 5 (#204) - saga stock + wallet; protocolo igual ao da R4, so a saga muda (README, secao Rodada 5)'
	@Write-Host '  make coleta-host     (no HOST: +5 colunas no fim do CSV -- lag_stock, lag_stock_catalog, saga_reservando, saga_orfas, saga_compensando --. ORDEM POR BLOCO: ao fim do k6 a coleta CONTINUA ate saga_reservando e saga_compensando = 0 no CSV ou ate passar ORFA_LIMITE_S; so entao Ctrl+C (gera saga-*.csv e resumo-saga-*.txt), depois verificar-rpm, depois limpar-banco)'
	@Write-Host '  make verificar-rpm   PREFIXO=Deg-<id> [ORFA_LIMITE_S=45]   (no HOST: overselling (codigo 3), depois o lag do group stock e as sagas do prefixo; codigo 4 = orfa/invariante da saga que nao convergiu no limite)'
	@Write-Host '  LOGGING_LEVEL_SAGA_TIMING=DEBUG no reservation (linha comentada no carga/docker-compose.carga.yml): latencia dos passos da saga no resumo, SO no bloco que mede'
	@Write-Host '  make selftests       (sem Docker/stack: lib-degraus, lib-servicos, lib-admissao, carga-degraus (k6 stub), coleta-host, limpar-banco)'

setup-host:
	.\setup-host.ps1 -ReadPath $(READPATH) $(if $(GERADOR_IP),-GeradorIp $(GERADOR_IP)) $(if $(COMPOSE_EXTRAS),-ComposeExtras $(COMPOSE_EXTRAS))

setup-gerador:
	$(need_ip)
	.\setup-gerador.ps1 -HostIp $(HOST_IP)

test-disputa:
	$(need_ip)
	.\rodar-teste.ps1 -HostIp $(HOST_IP) -Vus $(VUS) -Capacidade $(CAPACIDADE) -Rotulo $(READPATH) -PctConfirma $(PCT_CONFIRMA)

verificar-disputa:
	$(if $(EVENT_ID),,$(error Informe EVENT_ID=<id>))
	.\setup-host.ps1 -Verificar -EventId $(EVENT_ID)

host-rpm:
	.\rpm\subir-host-rpm.ps1 -ReadPath $(READPATH) -MaxConnections $(MAX_CONN) $(if $(COMPOSE_EXTRAS),-ComposeExtras $(COMPOSE_EXTRAS))

test-rpm:
	$(need_ip)
	.\rpm\rodar-rpm.ps1 -HostIp $(HOST_IP) -Rpm $(RPM) -Duracao $(DURACAO) -Usuarios $(USUARIOS) -Rotulo $(READPATH) -MaxConnections $(MAX_CONN) -PctConfirma $(PCT_CONFIRMA) -Cenario $(CENARIO)

# '; exit $$LASTEXITCODE': o codigo 3 (overselling no braco CAS) chega ao make (ver o comentario do test-matriz)
verificar-rpm:
	$(if $(PREFIXO),,$(error Informe PREFIXO=Rpm-<id>))
	.\rpm\subir-host-rpm.ps1 -Verificar -Prefixo $(PREFIXO) $(if $(ORFA_LIMITE_S),-OrfaLimiteS $(ORFA_LIMITE_S)); exit $$LASTEXITCODE

test-aleatorio:
	$(need_ip)
	.\aleatorio\rodar-aleatorio.ps1 -HostIp $(HOST_IP) -Tempo $(TEMPO) -RpmMin $(RPM_MIN) -RpmMax $(RPM_MAX) -Passo $(PASSO) -PctLeitura $(PCT_LEITURA) -PctConfirma $(PCT_CONFIRMA) -Semente $(SEMENTE) -Usuarios $(USUARIOS) -Rotulo $(READPATH) -MaxConnections $(MAX_CONN)

# ---- matriz do experimento (issue #11): o runner oficial; acumula linhas em results\matriz.csv
# '; exit $$LASTEXITCODE': com powershell -Command o 'exit N' do script vira 1; assim o make mostra o codigo
# real do runner em "Error N" (2 = matriz incompleta, 3 = overselling no CAS). O status do proprio make e sempre 2.
test-matriz:
	$(need_ip)
	.\rodar-matriz.ps1 -HostIp $(HOST_IP) -Vus $(VUS) -Repeticoes $(REPETICOES) -Capacidade $(CAPACIDADE) -Rotulo $(READPATH) -PgPorta $(PG_PORTA) -PgUsuario $(PGUSER) -PgBanco $(PGDATABASE); exit $$LASTEXITCODE

# preset da rodada 1a (issue #12), braco CAS: N=100,200, 5 repeticoes, capacity 20, PCT_CONFIRMA=100 (fixo no
# runner). O host deve rodar READPATH=cas.
test-matriz-cas:
	$(MAKE) test-matriz READPATH=cas VUS=100,200 REPETICOES=10 CAPACIDADE=20

# preset da rodada 1b (issue #13), braco ingenuo: N=100,200, 5 repeticoes, capacity 20, PCT_CONFIRMA=100 (fixo no
# runner). O lag do naive deixou de ser um knob (#29): e o do consumer real, medido (lag_obs_*), entao nao ha mais
# um host por lag. 2 N x 10 rep = 20 linhas no results\matriz.csv. Pinado como o preset CAS: VUS/REPETICOES/etc. da
# linha de comando nao valem.
test-matriz-naive:
	$(need_ip)
	$(MAKE) test-matriz READPATH=naive VUS=100,200 REPETICOES=10 CAPACIDADE=20

# o report e bash+awk (Git Bash, ja exigido pelo seed); ProgramW6432: o make pode abrir um PowerShell de 32 bits
report-matriz:
	& "$$env:ProgramW6432\Git\bin\bash.exe" ../../docs/carga/harness/report-matriz.sh results/matriz.csv results/matriz.html

# ---- escada de carga (issue #14): um CSV por escada em results\degraus-<id>.csv; o limiar X sai do report
test-degraus:
	$(need_ip)
	.\degraus\rodar-degraus.ps1 -HostIp $(HOST_IP) -RpmInicio $(DEG_INICIO) -RpmPasso $(DEG_PASSO) -RpmMax $(DEG_MAX) -Duracao $(DEG_DURACAO) -Usuarios $(USUARIOS) -Rotulo $(READPATH) -MaxConnections $(MAX_CONN) -PoolHikari $(POOL_HIKARI) -PctConfirma $(PCT_CONFIRMA) -MaxVus $(MAX_VUS) -Mapeamento $(MAPEAMENTO) -PctAcompanha $(PCT_ACOMPANHA) -ConsultaMs $(CONSULTA_MS) -TetoConsultaS $(TETO_CONSULTA_S) -ParadaP95Ms $(PARADA_P95_MS) -ParadaJanelaS $(PARADA_JANELA_S) -SetupS $(SETUP_S) -Arranjo $(ARRANJO) $(if $(filter 1,$(COM_CHAVE)),-ComChave) $(if $(PARAMS_AUTOESCALA),-ParamsAutoescala '$(PARAMS_AUTOESCALA)')

# telemetria (CPU/freq/RAM a cada 1 s) do HOST durante a escada/matriz; o gerador grava a dele sozinho. Ctrl+C encerra.
telemetria-host:
	.\telemetria-host.ps1 $(if $(ROTULO),-Rotulo $(ROTULO))

# metricas do banco/broker no HOST (lag projecao/consumer, outbox, conexoes do pooler, pg_stat_statements); Ctrl+C encerra
coleta-host:
	& "$$env:ProgramW6432\Git\bin\bash.exe" ./coleta-host.sh $(if $(ROTULO),$(ROTULO),rodada) $(COLETA_S)

# limpeza do HOST entre blocos (versionada na #178; Git Bash, como o coleta-host): PARE o scaler antes
limpar-banco:
	& "$$env:ProgramW6432\Git\bin\bash.exe" ./limpar-banco.sh

# auto-testes sem Docker nem stack (os .ps1 em PowerShell, os .sh em Git Bash, os .mjs em node >= 22.7)
selftests:
	.\degraus\lib-degraus.selftest.ps1
	.\lib-servicos.selftest.ps1
	node degraus/lib-admissao.selftest.mjs
	node degraus/carga-degraus.selftest.mjs
	node degraus/lib-mapeamento.selftest.mjs
	& "$$env:ProgramW6432\Git\bin\bash.exe" ./coleta-host.selftest.sh
	& "$$env:ProgramW6432\Git\bin\bash.exe" ./limpar-banco.selftest.sh

report-degraus:
	$(if $(CSV),,$(error Informe CSV=results\degraus-<id>.csv))
	& "$$env:ProgramW6432\Git\bin\bash.exe" ../../docs/carga/harness/report-degraus.sh $(subst \,/,$(CSV)) $(subst \,/,$(CSV:.csv=.html))

# ---- versao realista: mesmos testes, mas so PCT_CONFIRMA_REAL % das aceitas confirmam
test-disputa-real:
	$(MAKE) test-disputa PCT_CONFIRMA=$(PCT_CONFIRMA_REAL)

test-rpm-real:
	$(MAKE) test-rpm PCT_CONFIRMA=$(PCT_CONFIRMA_REAL)

test-aleatorio-real:
	$(MAKE) test-aleatorio PCT_CONFIRMA=$(PCT_CONFIRMA_REAL)
