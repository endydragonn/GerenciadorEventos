# Atalhos dos testes de carga (Windows: usa PowerShell). Rode de Projeto/carga:  make help
# Requer GNU make (ex.: winget install ezwinports.make  ou  choco install make).
SHELL := powershell.exe
.SHELLFLAGS := -NoProfile -ExecutionPolicy Bypass -Command

# ---- parametros (sobrescreva: make test-rpm HOST_IP=192.168.0.10 RPM=2000)
HOST_IP        ?=
VUS            ?= 50,100,200
CAPACIDADE     ?= 20
READPATH       ?= cas
REFRESH_MS     ?= 5000
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
SEMENTE        ?= 0

need_ip = $(if $(HOST_IP),,$(error Informe HOST_IP=<ip do host>))

.PHONY: help setup-host setup-gerador host-rpm test-disputa test-rpm test-aleatorio verificar-disputa verificar-rpm
.DEFAULT_GOAL := help

help:
	@Write-Host 'SETUP'
	@Write-Host '  make setup-host      [READPATH=cas|naive REFRESH_MS=5000]   (no HOST: sobe a stack com override de carga)'
	@Write-Host '  make setup-gerador   HOST_IP=<ip>                           (no GERADOR: k6 + latencia base)'
	@Write-Host 'TESTE 1 - disputa de vagas (corretude, 1 evento, rajada)'
	@Write-Host '  make test-disputa    HOST_IP=<ip> [VUS=50,100,200 CAPACIDADE=20 READPATH=cas]'
	@Write-Host '  make verificar-disputa EVENT_ID=<id>                        (no HOST)'
	@Write-Host 'TESTE 2 - taxa fixa (RPM, varios eventos, saturacao)'
	@Write-Host '  make host-rpm        [MAX_CONN=300 READPATH=cas]            (no HOST: stack + max_connections)'
	@Write-Host '  make test-rpm        HOST_IP=<ip> [RPM=10000 DURACAO=90 USUARIOS=200]'
	@Write-Host '  make verificar-rpm   PREFIXO=Rpm-<id>                       (no HOST)'
	@Write-Host 'TESTE 3 - carga aleatoria (taxa sorteada a cada PASSO s, reservas + leituras, por TEMPO)'
	@Write-Host '  make test-aleatorio  HOST_IP=<ip> [TEMPO=10m RPM_MIN=1000 RPM_MAX=12000 PASSO=30 PCT_LEITURA=30 SEMENTE=0]'
	@Write-Host '  make verificar-rpm   PREFIXO=Ale-<id>                       (no HOST, logo apos a rodada)'

setup-host:
	.\setup-host.ps1 -ReadPath $(READPATH) -RefreshMs $(REFRESH_MS)

setup-gerador:
	$(need_ip)
	.\setup-gerador.ps1 -HostIp $(HOST_IP)

test-disputa:
	$(need_ip)
	.\rodar-teste.ps1 -HostIp $(HOST_IP) -Vus $(VUS) -Capacidade $(CAPACIDADE) -Rotulo $(READPATH) -RefreshMs $(REFRESH_MS)

verificar-disputa:
	$(if $(EVENT_ID),,$(error Informe EVENT_ID=<id>))
	.\setup-host.ps1 -Verificar -EventId $(EVENT_ID)

host-rpm:
	.\rpm\subir-host-rpm.ps1 -ReadPath $(READPATH) -RefreshMs $(REFRESH_MS) -MaxConnections $(MAX_CONN)

test-rpm:
	$(need_ip)
	.\rpm\rodar-rpm.ps1 -HostIp $(HOST_IP) -Rpm $(RPM) -Duracao $(DURACAO) -Usuarios $(USUARIOS) -Rotulo $(READPATH) -MaxConnections $(MAX_CONN) -RefreshMs $(REFRESH_MS)

verificar-rpm:
	$(if $(PREFIXO),,$(error Informe PREFIXO=Rpm-<id>))
	.\rpm\subir-host-rpm.ps1 -Verificar -Prefixo $(PREFIXO)

test-aleatorio:
	$(need_ip)
	.\aleatorio\rodar-aleatorio.ps1 -HostIp $(HOST_IP) -Tempo $(TEMPO) -RpmMin $(RPM_MIN) -RpmMax $(RPM_MAX) -Passo $(PASSO) -PctLeitura $(PCT_LEITURA) -Semente $(SEMENTE) -Usuarios $(USUARIOS) -Rotulo $(READPATH) -MaxConnections $(MAX_CONN) -RefreshMs $(REFRESH_MS)
