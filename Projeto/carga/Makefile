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

need_ip = $(if $(HOST_IP),,$(error Informe HOST_IP=<ip do host>))

.PHONY: help setup-host setup-gerador host-rpm test-disputa test-rpm test-aleatorio test-disputa-real test-rpm-real test-aleatorio-real test-matriz test-matriz-cas test-matriz-naive report-matriz test-degraus report-degraus verificar-disputa verificar-rpm telemetria-host
.DEFAULT_GOAL := help

help:
	@Write-Host 'SETUP'
	@Write-Host '  make setup-host      [READPATH=cas|naive GERADOR_IP=<ip>]   (no HOST: sobe a stack com override de carga; firewall 8081; a 5433 so abre COM GERADOR_IP, restrita a ele, p/ a matriz)'
	@Write-Host '  make setup-gerador   HOST_IP=<ip>                           (no GERADOR: k6 + latencia base)'
	@Write-Host 'TESTE 1 - disputa de vagas (corretude, 1 evento, rajada)'
	@Write-Host '  make test-disputa    HOST_IP=<ip> [VUS=50,100,200 CAPACIDADE=20 READPATH=cas PCT_CONFIRMA=100]'
	@Write-Host '  make verificar-disputa EVENT_ID=<id>                        (no HOST)'
	@Write-Host 'TESTE 2 - taxa fixa (RPM, varios eventos, saturacao)'
	@Write-Host '  make host-rpm        [MAX_CONN=300 READPATH=cas]            (no HOST: stack + max_connections)'
	@Write-Host '  make test-rpm        HOST_IP=<ip> [RPM=10000 DURACAO=90 USUARIOS=200 PCT_CONFIRMA=100]'
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

setup-host:
	.\setup-host.ps1 -ReadPath $(READPATH) $(if $(GERADOR_IP),-GeradorIp $(GERADOR_IP))

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
	.\rpm\subir-host-rpm.ps1 -ReadPath $(READPATH) -MaxConnections $(MAX_CONN)

test-rpm:
	$(need_ip)
	.\rpm\rodar-rpm.ps1 -HostIp $(HOST_IP) -Rpm $(RPM) -Duracao $(DURACAO) -Usuarios $(USUARIOS) -Rotulo $(READPATH) -MaxConnections $(MAX_CONN) -PctConfirma $(PCT_CONFIRMA)

verificar-rpm:
	$(if $(PREFIXO),,$(error Informe PREFIXO=Rpm-<id>))
	.\rpm\subir-host-rpm.ps1 -Verificar -Prefixo $(PREFIXO)

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
	.\degraus\rodar-degraus.ps1 -HostIp $(HOST_IP) -RpmInicio $(DEG_INICIO) -RpmPasso $(DEG_PASSO) -RpmMax $(DEG_MAX) -Duracao $(DEG_DURACAO) -Usuarios $(USUARIOS) -Rotulo $(READPATH) -MaxConnections $(MAX_CONN) -PoolHikari $(POOL_HIKARI) -PctConfirma $(PCT_CONFIRMA) -MaxVus $(MAX_VUS) -Mapeamento $(MAPEAMENTO)

# telemetria (CPU/freq/RAM a cada 1 s) do HOST durante a escada/matriz; o gerador grava a dele sozinho. Ctrl+C encerra.
telemetria-host:
	.\telemetria-host.ps1 $(if $(ROTULO),-Rotulo $(ROTULO))

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
