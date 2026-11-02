#!/usr/bin/env bash
# Selftest do verificar-soak.sh (issue #232), SEM Docker, cluster nem Mailpit: `kubectl`, `curl` e o verificar-rpm FALSOS no PATH (o kubectl devolve JSON de um cluster
# soak ou de variantes quebradas; o curl devolve paginas do Mailpit). Confere: o pre-check aborta num cluster base (notification sem Mailpit), sem a #231 (ScaledObject do stock ou
# Hikari != 245), com replica != 1, com o total de cadastros acima do MP_MAX_MESSAGES e com o Mailpit nao vazio, e registra mailpit_inicio=0; o pos-bloco registra o lag 0 ANTES
# da contagem do Mailpit, propaga os codigos 3 e 4 do verificar-rpm, reprova e-mail perdido; a credencial do Mailpit nunca aparece em argumento (canario).
set -u
AQUI="$(cd "$(dirname "$0")" && pwd)"
falhas=0
confere() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FALHA $1: esperado '$3', veio '$2'"; falhas=$((falhas+1)); fi; }
PY=""; for c in python3 python; do "$c" -c 'import json' >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || { echo "selftest verificar-soak: preciso de python"; exit 1; }

T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/results" "$T/fx"
TW="$T"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) TW="$(cygpath -m "$T")" ;; esac

# ---- fixtures (JSON do kubectl, perfil, por-minuto, amostras, paginas do Mailpit)
"$PY" - "$TW/fx" <<'EOF'
import json, os, sys
d = sys.argv[1]
def deploy(nome, replicas=1, prontas=1, env=None):
    return {"kind": "Deployment", "metadata": {"name": nome}, "spec": {"replicas": replicas, "template": {"spec": {"containers": [{"name": nome, "env": env or []}]}}}, "status": {"readyReplicas": prontas}}
def so(nome, alvo=None):
    spec = {"scaleTargetRef": {"name": nome}}
    if alvo: spec["triggers"] = [{"type": "metrics-api", "metadata": {"targetValue": alvo}}]
    return {"kind": "ScaledObject", "metadata": {"name": nome}, "spec": spec}
seis = [{"name": "MAIL_HOST", "value": "mailpit"}, {"name": "MAIL_PORT", "value": "1025"}, {"name": "MAIL_SMTP_AUTH", "value": "false"},
        {"name": "MAIL_STARTTLS", "value": "false"}, {"name": "MAIL_USERNAME", "value": "soak@example.com"}, {"name": "MAIL_PASSWORD"}]
hk = lambda v: [{"name": "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "value": v}]
def cluster(**k):
    itens = [deploy("notification", env=k.get("notif", seis)), deploy("stock", env=hk(k.get("hikari", "245"))), deploy("mailpit", env=[{"name": "MP_MAX_MESSAGES", "value": "250000"}]),
             deploy("backend", replicas=k.get("rep", 1), prontas=k.get("rep", 1)), deploy("reservation"), so("backend"), so("reservation", k.get("alvo", "28000")), so("notification")]
    if k.get("so_stock"): itens.append(so("stock"))
    return {"kind": "List", "items": itens}
def grava(nome, obj):
    with open(os.path.join(d, nome), "w", encoding="utf-8", newline="\n") as f: json.dump(obj, f)
grava("cluster-soak.json", cluster())
grava("cluster-soak-s2.json", cluster(alvo="2000"))
grava("cluster-notif-springmail.json", cluster(notif=seis + [{"name": "SPRING_MAIL_HOST", "value": "smtp.gmail.com"}]))
open(os.path.join(d, "chaves-ok.txt"), "w", newline="\n").write("MAIL_HOST\nMAIL_PASSWORD\n")
open(os.path.join(d, "chaves-springmail.txt"), "w", newline="\n").write("MAIL_HOST\nspring.mail.host\n")
grava("cluster-base.json", cluster(notif=[{"name": "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "value": "3"}]))
grava("cluster-sem231-so.json", cluster(so_stock=True))
grava("cluster-sem231-hikari.json", cluster(hikari="60"))
grava("cluster-replica2.json", cluster(rep=2))
grava("perfil.json", {"total_cadastros_esperado": 3})
grava("perfil-grande.json", {"total_cadastros_esperado": 125001})
def msg(i, de="soak@example.com"): return {"ID": "m%d" % i, "Created": "2026-11-01T12:00:%02dZ" % i, "From": {"Address": de}, "To": [{"Address": "soak-T1-%d@example.com" % i}]}
grava("pag-ok.json", {"total": 3, "messages": [msg(1), msg(2), msg(3)]})
grava("pag-perdido.json", {"total": 2, "messages": [msg(1), msg(2)]})
grava("pag-vazia.json", {"total": 0, "messages": []})
grava("pag-cheia.json", {"total": 5, "messages": [msg(1)]})
CAB = "minuto,tipo,taxa,reqs,p50_ms,p95_ms,p99_ms,c2xx,c202,c400,c429,c5xx,c0,outros,descartadas,verificacoes_2xx,inicio_s\n"
open(os.path.join(d, "por-minuto.csv"), "w", encoding="utf-8", newline="\n").write(
    CAB + "0,cadastro,3,6,10.000,20.000,30.000,6,0,0,0,0,0,0,0,3,1000\n0,leitura,9,9,10.000,20.000,30.000,9,0,0,0,0,0,0,0,0,1000\n")
# S2: reserva com 202 (a fila ligou) e 429
open(os.path.join(d, "por-minuto-s2.csv"), "w", encoding="utf-8", newline="\n").write(
    CAB + "0,cadastro,3,6,10.000,20.000,30.000,6,0,0,0,0,0,0,0,3,1000\n0,reserva,9,12,10.000,20.000,30.000,6,4,0,2,0,0,0,0,0,1000\n")
open(os.path.join(d, "por-minuto-5xx.csv"), "w", encoding="utf-8", newline="\n").write(
    CAB + "0,leitura,9,9,10.000,20.000,30.000,8,0,0,0,1,0,0,0,0,1000\n")
# status 0 (erro de transporte) no minuto 3 (inicio 1180) de reserva; no pods-saida.csv o pod reservation-b comeca a terminar as 1190
open(os.path.join(d, "por-minuto-c0.csv"), "w", encoding="utf-8", newline="\n").write(
    CAB + "0,leitura,9,9,10.000,20.000,30.000,9,0,0,0,0,0,0,0,0,1000\n3,reserva,9,9,10.000,20.000,30.000,7,0,0,0,0,2,0,0,0,1180\n")
open(os.path.join(d, "pods-saida.csv"), "w", encoding="utf-8", newline="\n").write(
    "epoch_s,pod,app,pronto,restarts,terminando\n1000,mailpit-a,mailpit,1,0,0\n1185,reservation-b,reservation,1,0,0\n1190,reservation-b,reservation,1,0,1\n1195,mailpit-a,mailpit,1,0,0\n")
open(os.path.join(d, "replicas.csv"), "w", encoding="utf-8", newline="\n").write("epoch_s,servico,metrica,alvo,replicas,prontas\n1000,stock,,,1,1\n1005,stock,,,1,1\n")
open(os.path.join(d, "pods.csv"), "w", encoding="utf-8", newline="\n").write(
    "epoch_s,pod,app,pronto,restarts,terminando\n1000,mailpit-a,mailpit,1,0,0\n1005,mailpit-a,mailpit,1,0,0\n1000,notification-a,notification,1,0,0\n")
# JSON de `get hpa,deploy,pods` para o amostrar
grava("amostra.json", {"kind": "List", "items": [deploy("catalog", replicas=2), {"kind": "Pod", "metadata": {"name": "mailpit-a", "labels": {"app": "mailpit"}}, "status": {"conditions": [{"type": "Ready", "status": "True"}], "containerStatuses": [{"restartCount": 0}]}}]})
EOF
FX="$TW/fx"

# ---- binarios falsos
cat > "$T/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
echo "kubectl $*" >> "$FAKE_LOG"
case "$*" in
    *"get deploy,scaledobject -o json"*) cat "$FX/${FAKE_CLUSTER:-cluster-soak.json}" ;;
    *"get hpa,deploy,pods -o json"*) cat "$FX/amostra.json" ;;
    *"get secret env-admission"*"ADMISSION_X_RPM"*) printf '%s' "${FAKE_X:-56000}" | base64 ;;
    *"get secret env-admission"*"managedFields"*) printf '%s' "${FAKE_SECRET_T:-2026-11-01T10:00:00Z}" ;;
    *"get pods -l app=admission"*) printf '%s\n' "${FAKE_POD_T:-2026-11-01T11:00:00Z}" ;;
    *"get secret env-notification"*) cat "$FX/${FAKE_CHAVES:-chaves-ok.txt}" ;;
    *"rpk group describe notification"*)
        n="$(cat "$FAKE_LAGS" 2>/dev/null | head -1)"; [ -n "$n" ] && tail -n +2 "$FAKE_LAGS" > "$FAKE_LAGS.t" && mv "$FAKE_LAGS.t" "$FAKE_LAGS"
        printf 'GROUP        notification\nSTATE        Stable\nTOTAL-LAG    %s\n' "${n:-0}" ;;
esac
exit 0
EOF
cat > "$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl $*" >> "$FAKE_LOG"
case "$*" in *"-w "*) echo "${FAKE_SEM_AUTH:-401}"; exit 0 ;; esac   # GET SEM credencial (sem -K): devolve so o codigo
cat > /dev/null   # o -K - le a credencial do stdin; consome para nao dar SIGPIPE
for a in "$@"; do url="$a"; done
[ "${FAKE_MP_DOWN:-0}" = 1 ] && exit 22
case "$url" in
    *"limit=1") cat "$FX/${FAKE_MP_VAZIO:-pag-vazia.json}" ;;
    *) cat "$FX/${FAKE_MP_PAG:-pag-ok.json}" ;;
esac
EOF
# powershell.exe FALSO: a conferencia da regra de firewall (conferir-firewall.ps1) sem Windows Firewall; FAKE_FW escolhe o resultado
cat > "$T/bin/powershell.exe" <<'EOF'
#!/usr/bin/env bash
echo "powershell $*" >> "$FAKE_LOG"
case "${FAKE_FW:-ok}" in
    ok) echo "[ ok ] regra 'TCC soak mailpit 8025 - bloqueio': Block, entrada, TCP 8025, todo o IPv4 menos o gerador"; echo "[ ok ] regra 'TCC soak mailpit 8025 - gerador': Allow, entrada, TCP 8025, so o gerador"; exit 0 ;;
    semallow) echo "[ ok ] regra 'TCC soak mailpit 8025 - bloqueio': Block, entrada, TCP 8025, todo o IPv4 menos o gerador"; echo "[erro] esperada exatamente 1 regra 'TCC soak mailpit 8025 - gerador' no firewall, achei 0: sem ela o gerador nao alcanca a 8025. Rode o Criar de novo como administrador"; exit 1 ;;
    falta) echo "[erro] esperada exatamente 1 regra 'TCC soak mailpit 8025 - bloqueio' no firewall, achei 0."; exit 1 ;;
    remoto) echo "[erro] regra 'TCC soak mailpit 8025 - bloqueio': RemoteAddress (Any) nao e exatamente o complemento do gerador"; exit 1 ;;
esac
EOF
# w32tm e docker FALSOS: o offset do host contra a referencia e o `date -u` dentro do no do kind (com a deriva FAKE_NODE_SKEW em segundos)
cat > "$T/bin/w32tm" <<'EOF'
#!/usr/bin/env bash
echo "w32tm $*" >> "$FAKE_LOG"
[ "${FAKE_W32:-ok}" = fail ] && { echo "erro: 0x80072746"; exit 1; }
printf 'Tracking time.windows.com [20.101.57.9:123].\r\nCollecting 1 samples.\r\nThe current time is 2026-11-01 12:00:00.\r\n12:00:00, %s\r\n' "${FAKE_HOST_OFF:-+00.0100000s}"
EOF
cat > "$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$FAKE_LOG"
[ "${FAKE_NODE:-ok}" = fail ] && exit 1
awk -v s="${FAKE_NODE_SKEW:-0}" -v t="$(date -u +%s.%N)" 'BEGIN { printf "%.6f\n", t + s }'
EOF
cat > "$T/bin/verificar-rpm-falso" <<'EOF'
#!/usr/bin/env bash
echo "verificar-rpm $* (CARGA_K8S=$CARGA_K8S)" >> "$FAKE_LOG"
echo "[ ok ] overselling 0."
exit "${FAKE_RPM_RC:-0}"
EOF
chmod +x "$T/bin/"*
export FX FAKE_LOG="$T/fake.log" FAKE_LAGS="$T/lags"
export GERADOR_IP="192.168.68.54"
export RESULTS="$T/results" MAILPIT_URL="http://mp.falso:8025" MAILPIT_AUTH="soak:CANARIOsenhaSecreta123" VERIFICAR_RPM="$TW/bin/verificar-rpm-falso" LAG_ESPERA_S=3
SH="$AQUI/verificar-soak.sh"
rodar() { PATH="$T/bin:$PATH" bash "$SH" "$@" 2>&1; }

echo "==> pre"
: > "$FAKE_LOG"; saida="$(rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: cluster soak valido sai 0 e registra mailpit_inicio=0" "$rc|$(echo "$saida" | grep -c 'mailpit_inicio=0')" "0|1"
confere "pre: o registro vai para results/soak-T-verificacoes.log" "$(grep -c 'mailpit_inicio=0' "$T/results/soak-T-verificacoes.log")" "1"
confere "pre: a credencial do Mailpit nunca aparece em argumento do curl nem na saida (canario)" "$(grep -c CANARIO "$FAKE_LOG")$(echo "$saida" | grep -c CANARIO)" "00"
confere "pre: o curl usa -K - (credencial por stdin)" "$(grep -c "curl .*-K -" "$FAKE_LOG")" "1"
saida="$(FAKE_CLUSTER=cluster-base.json rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: cluster BASE (notification sem Mailpit) aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'MAIL_HOST' && echo "$saida" | grep -q 'ABORTA' && echo sim || echo nao)" "sim"
saida="$(FAKE_CLUSTER=cluster-sem231-so.json rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: sem a #231 (ScaledObject do stock) aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'ScaledObject do stock' && echo sim || echo nao)" "sim"
saida="$(FAKE_CLUSTER=cluster-sem231-hikari.json rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: sem a #231 (Hikari do stock 60 != 245) aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'Hikari' && echo sim || echo nao)" "sim"
saida="$(FAKE_CLUSTER=cluster-replica2.json rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: Deployment autoescalado com replicas=2 aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'backend' && echo "$saida" | grep -q 'replicas=2' && echo sim || echo nao)" "sim"
saida="$(rodar pre --id T --perfil "$FX/perfil-grande.json" --bloco S1)"; rc=$?
confere "pre: total de cadastros do perfil acima do MP_MAX_MESSAGES aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'MP_MAX_MESSAGES' && echo sim || echo nao)" "sim"
saida="$(FAKE_MP_VAZIO=pag-cheia.json rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: Mailpit com mensagens (nao vazio) aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'Mailpit tem 5' && echo sim || echo nao)" "sim"
saida="$(FAKE_MP_DOWN=1 rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: Mailpit fora do ar aborta" "$([ "$rc" -ne 0 ] && echo sim || echo nao)" "sim"
saida="$(MAILPIT_AUTH="" rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: sem MAILPIT_AUTH aborta" "$([ "$rc" -ne 0 ] && echo sim || echo nao)" "sim"

saida="$(FAKE_CLUSTER=cluster-soak-s2.json FAKE_X=4000 rodar pre --id T --perfil "$FX/perfil.json" --bloco S2)"; rc=$?
confere "pre S2: ADMISSION_X_RPM=4000 e alvo do KEDA 2000 passam" "$rc" "0"
saida="$(rodar pre --id T --perfil "$FX/perfil.json" --bloco S2)"; rc=$?
confere "pre S2 num cluster subido para o S1 (X=56000, alvo 28000) aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'ADMISSION_X_RPM=56000' && echo "$saida" | grep -q 'targetValue' && echo sim || echo nao)" "sim"
saida="$(FAKE_CLUSTER=cluster-soak-s2.json FAKE_X=4000 rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre S1 num cluster subido para o S2 (X=4000, alvo 2000) aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'ADMISSION_X_RPM=4000' && echo sim || echo nao)" "sim"
saida="$(FAKE_POD_T=2026-11-01T09:00:00Z rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: pod do admission que subiu ANTES do Secret env-admission aborta (rollout restart)" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'rollout restart deploy/admission' && echo sim || echo nao)" "sim"
saida="$(FAKE_CLUSTER=cluster-notif-springmail.json rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: SPRING_MAIL_HOST no env vivo do notification aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'SPRING_MAIL_HOST' && echo sim || echo nao)" "sim"
saida="$(FAKE_CHAVES=chaves-springmail.txt rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: chave spring.mail.host no Secret env-notification aborta (so as chaves sao lidas)" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'spring.mail.host' && echo sim || echo nao)" "sim"
saida="$(FAKE_SEM_AUTH=200 rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: GET sem credencial no Mailpit com 200 (auth desligada) aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'SEM credencial' && echo sim || echo nao)" "sim"
saida="$(rodar pre --id T --perfil "$FX/perfil.json")"; rc=$?
confere "pre: sem --bloco e erro de uso (codigo 2)" "$rc" "2"
saida="$(GERADOR_IP="" rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: sem o IP do gerador (GERADOR_IP nem --gerador-ip) e erro de uso (codigo 2)" "$rc" "2"
: > "$FAKE_LOG"; saida="$(GERADOR_IP="" rodar pre --id T --perfil "$FX/perfil.json" --bloco S1 --gerador-ip 10.1.2.3)"; rc=$?
confere "pre: --gerador-ip vale no lugar da variavel e chega ao powershell.exe" "$rc|$(grep -c 'powershell .*conferir-firewall.ps1 -GeradorIp 10.1.2.3' "$FAKE_LOG")" "0|1"
: > "$FAKE_LOG"; saida="$(rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: confere a regra de firewall pelo powershell.exe (-NoProfile, conferir-firewall.ps1, IP do gerador do kit) ANTES de falar com o Mailpit" "$rc|$(grep -c 'powershell -NoProfile -ExecutionPolicy Bypass -File .*conferir-firewall.ps1 -GeradorIp 192.168.68.54' "$FAKE_LOG")|$(grep -n 'powershell\|^curl' "$FAKE_LOG" | head -1 | grep -c powershell)" "0|1|1"
confere "pre: registra que a regra confere" "$(echo "$saida" | grep -c 'regra de firewall da 8025 confere')" "1"
saida="$(FAKE_FW=falta rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: regra de firewall ausente aborta (e diz como criar)" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'achei 0' && echo "$saida" | grep -q 'ABORTA' && echo "$saida" | grep -q 'Acao Criar -GeradorIp 192.168.68.54' && echo sim || echo nao)" "sim"
saida="$(FAKE_FW=semallow rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: sem a Allow do gerador aborta (e diz para rodar o Criar de novo)" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q "gerador' no firewall, achei 0" && echo "$saida" | grep -q 'ABORTA' && echo "$saida" | grep -q 'Criar de novo' && echo sim || echo nao)" "sim"
: > "$FAKE_LOG"; saida="$(FAKE_FW=remoto rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "pre: regra com RemoteAddress que nao e o complemento do gerador aborta, sem chegar ao Mailpit" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'complemento' && [ "$(grep -c '^curl' "$FAKE_LOG")" = 0 ] && echo sim || echo nao)" "sim"
: > "$FAKE_LOG"; rodar pre --id T --perfil "$FX/perfil.json" --bloco S1 >/dev/null
confere "pre: so o jsonpath da chave ADMISSION_X_RPM e as CHAVES do env-notification sao lidos (nenhum 'get secret -o json/yaml')" "$(grep -c 'get secret .* -o \(json\|yaml\)\( \|$\)' "$FAKE_LOG")" "0"

# relogios (item 4): o pre GRAVA o offset dos tres relogios e AVISA se a diferenca passar de 5 s; nunca aborta nem sincroniza
: > "$FAKE_LOG"; saida="$(rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "relogios: sem o offset do gerador, grava host/no, avisa que o gerador nao foi informado e NAO aborta" "$rc|$(echo "$saida" | grep -c 'relogios (adiantamento contra time.windows.com.*host=-0.010 s, no=[-+][0-9.]* s, gerador=n/d')|$(echo "$saida" | grep -c 'AVISO: o relogio do gerador nao foi informado')|$(grep -c 'docker exec gerenciador-control-plane date -u' "$FAKE_LOG")|$(grep -c 'w32tm /stripchart /computer:time.windows.com /samples:1 /dataonly' "$FAKE_LOG")" "0|1|1|1|1"
confere "relogios: o offset vai para o log do bloco" "$([ "$(grep -c 'relogios (adiantamento contra' "$T/results/soak-T-verificacoes.log")" -ge 1 ] && echo sim)" "sim"
saida="$(rodar pre --id T --perfil "$FX/perfil.json" --bloco S1 --gerador-offset-s +00.0123456s)"; rc=$?
confere "relogios: gerador no formato do w32tm (+00.0123456s = ref - local: o gerador esta 0,012 s ATRASADO) entra na linha com o sinal invertido e a diferenca < 5 s nao avisa" "$rc|$(echo "$saida" | grep -c 'gerador=-0.012 s')|$(echo "$saida" | grep -c 'maior diferenca entre os relogios')|$(echo "$saida" | grep -c 'AVISO')" "0|1|1|0"
saida="$(rodar pre --id T --perfil "$FX/perfil.json" --bloco S1 --gerador-offset-s -6.5)"; rc=$?
confere "relogios: w32tm do gerador -6.5 = relogio 6,5 s ADIANTADO (> 5 s do host) so AVISA (pre sai 0)" "$rc|$(echo "$saida" | grep -c 'AVISO: relogios com diferenca > 5 s')" "0|1"
saida="$(FAKE_NODE_SKEW=9 rodar pre --id T --perfil "$FX/perfil.json" --bloco S1 --gerador-offset-s 0.1)"; rc=$?
confere "relogios: no do kind 9 s adiantado (VM do Docker) so AVISA e a linha mostra no=+9" "$rc|$(echo "$saida" | grep -c 'AVISO: relogios com diferenca > 5 s')|$(echo "$saida" | grep -c 'no=+[89]\.')" "0|1|1"
saida="$(FAKE_HOST_OFF='-01,3043740s' rodar pre --id T --perfil "$FX/perfil.json" --bloco S1 --gerador-offset-s '-01,2000000s')"; rc=$?
confere "relogios: virgula decimal (locale) no host e no gerador: host=+1.304 s (w32tm -1,30 = adiantado), gerador=+1.200 s, sem aviso" "$rc|$(echo "$saida" | grep -c 'host=+1.304 s')|$(echo "$saida" | grep -c 'gerador=+1.200 s')|$(echo "$saida" | grep -c 'AVISO')" "0|1|1|0"
saida="$(FAKE_W32=fail FAKE_NODE_SKEW=7 rodar pre --id T --perfil "$FX/perfil.json" --bloco S1 --gerador-offset-s 0.1)"; rc=$?
confere "relogios: w32tm indisponivel -> referencia = host, no 7 s adiante avisa, nao aborta" "$rc|$(echo "$saida" | grep -c 'relogios: sem time.windows.com')|$(echo "$saida" | grep -c 'AVISO: no do kind')" "0|1|1"
saida="$(FAKE_W32=fail FAKE_NODE=fail rodar pre --id T --perfil "$FX/perfil.json" --bloco S1)"; rc=$?
confere "relogios: nada legivel (w32tm e docker fora) so avisa, nao aborta" "$rc|$(echo "$saida" | grep -c 'AVISO: nao li nenhum offset')" "0|1"
# IP do gerador por DHCP (item 3): a regra com outro IP aborta com a instrucao de rodar o Criar de novo
saida="$(FAKE_FW=remoto rodar pre --id T --perfil "$FX/perfil.json" --bloco S1 --gerador-ip 192.168.68.77)"; rc=$?
confere "pre: IP do gerador diferente do da regra aborta com 'rode o Criar de novo com o IP novo' e o IP novo no comando" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'rode o Criar de novo com o IP novo' && echo "$saida" | grep -q 'Acao Criar -GeradorIp 192.168.68.77' && echo sim || echo nao)" "sim"

echo "==> limpar-mailpit"
: > "$FAKE_LOG"; saida="$(rodar limpar-mailpit)"; rc=$?
confere "limpar-mailpit: DELETE /api/v1/messages com a credencial por stdin (canario fora do argv)" "$rc|$(grep -c 'curl .*-K - -X DELETE http://mp.falso:8025/api/v1/messages' "$FAKE_LOG")|$(grep -c CANARIO "$FAKE_LOG")" "0|1|0"

echo "==> amostrar"
: > "$FAKE_LOG"; rm -f "$T/results/soak-T-replicas.csv" "$T/results/soak-T-pods.csv"
rodar amostrar --id T --intervalo 1 --vezes 2 >/dev/null
confere "amostrar: 2 amostras gravam replicas e pods (cabecalho uma vez)" "$(grep -c '^' "$T/results/soak-T-replicas.csv")|$(grep -c 'get hpa,deploy,pods' "$FAKE_LOG")" "3|2"

echo "==> pos"
cp "$FX/por-minuto.csv" "$T/results/soak-T-por-minuto.csv"; cp "$FX/replicas.csv" "$T/results/soak-T-replicas.csv"; cp "$FX/pods.csv" "$T/results/soak-T-pods.csv"
POS=(pos --id T --perfil "$FX/perfil.json" --bloco S1 --sufixo T1 --prefixo Soak-T1)
: > "$FAKE_LOG"; printf '7\n0\n' > "$FAKE_LAGS"; saida="$(rodar "${POS[@]}")"; rc=$?
[ -n "${DEBUG_SELFTEST:-}" ] && echo "$saida"
confere "pos: bloco bom sai 0 (APROVADO nos criterios 1 a 3)" "$rc|$(echo "$saida" | grep -c 'APROVADO')" "0|1"
confere "pos: o lag 0 do notification e registrado ANTES da contagem do Mailpit e do verificar-rpm" "$(echo "$saida" | grep -n 'lag_notification=0\|Mailpit com\|verificar-rpm (prefixo' | cut -d: -f1 | tr '\n' ' ' | awk '{print ($1 < $2 && $2 < $3) ? "ordem-ok" : "ordem-errada"}')" "ordem-ok"
confere "pos: espera o lag (7 e depois 0): o kubectl exec rodou 2 vezes" "$(grep -c 'rpk group describe notification' "$FAKE_LOG")" "2"
confere "pos: o verificar-rpm roda com o prefixo e o contexto do cluster" "$(grep -c 'verificar-rpm Soak-T1 (CARGA_K8S=kind-gerenciador)' "$FAKE_LOG")" "1"
confere "pos: a credencial nunca aparece (canario)" "$(grep -c CANARIO "$FAKE_LOG")$(echo "$saida" | grep -c CANARIO)" "00"
: > "$FAKE_LOG"; printf '0\n' > "$FAKE_LAGS"; saida="$(FAKE_RPM_RC=3 rodar "${POS[@]}")"; rc=$?
confere "pos: overselling no verificar-rpm (codigo 3) sobe como 3" "$rc" "3"
printf '0\n' > "$FAKE_LAGS"; saida="$(FAKE_RPM_RC=4 rodar "${POS[@]}")"; rc=$?
confere "pos: saga nao convergiu (codigo 4) sobe como 4" "$rc" "4"
printf '0\n' > "$FAKE_LAGS"; saida="$(FAKE_MP_PAG=pag-perdido.json rodar "${POS[@]}")"; rc=$?
confere "pos: e-mail perdido (2 mensagens para 3 cadastros) reprova (codigo 1)" "$rc|$(echo "$saida" | grep -c 'perdidas 1')" "1|1"
cp "$FX/por-minuto-5xx.csv" "$T/results/soak-T-por-minuto.csv"; printf '0\n' > "$FAKE_LAGS"
saida="$(rodar "${POS[@]}")"; rc=$?
confere "pos: 5xx fora de tipo=mailpit reprova o criterio 3 (codigo 1)" "$([ "$rc" = 1 ] && echo "$saida" | grep -q 'criterio 3: REPROVOU' && echo sim || echo nao)" "sim"
cp "$FX/por-minuto.csv" "$T/results/soak-T-por-minuto.csv"
# status 0 (c0) reprova o criterio 3 por padrao e o relatorio liga o minuto ao pod que saiu (horario, servico, nome)
cp "$FX/por-minuto-c0.csv" "$T/results/soak-T-por-minuto.csv"; cp "$FX/pods-saida.csv" "$T/results/soak-T-pods.csv"; printf '0\n' > "$FAKE_LAGS"
saida="$(rodar "${POS[@]}")"; rc=$?
confere "pos: status 0 (c0) reprova o criterio 3 (codigo 1) e lista o minuto 3 de reserva" "$([ "$rc" = 1 ] && echo "$saida" | grep -q 'criterio 3: REPROVOU' && echo "$saida" | grep -q 'minuto 3 .* reserva .*status 0 2' && echo sim || echo nao)" "sim"
confere "pos: o relatorio mostra o pod que saiu nesse minuto, com horario, servico e nome" "$(echo "$saida" | grep -c 'pod saiu as .*: servico reservation, pod reservation-b (terminando)')" "1"
cp "$FX/pods.csv" "$T/results/soak-T-pods.csv"
# fila: no S2 o 202 e obrigatorio (429 so registrado); no S1 so registra
cp "$FX/por-minuto-s2.csv" "$T/results/soak-T-por-minuto.csv"; printf '0\n' > "$FAKE_LAGS"
saida="$(rodar pos --id T --perfil "$FX/perfil.json" --bloco S2 --sufixo T1 --prefixo Soak-T1)"; rc=$?
confere "pos S2 com 202 na reserva: ok e registra 202/429" "$rc|$(echo "$saida" | grep -c 'fila (S2): reserva com 4 respostas 202 e 2 respostas 429')" "0|1"
cp "$FX/por-minuto.csv" "$T/results/soak-T-por-minuto.csv"; printf '0\n' > "$FAKE_LAGS"
saida="$(rodar pos --id T --perfil "$FX/perfil.json" --bloco S2 --sufixo T1 --prefixo Soak-T1)"; rc=$?
confere "pos S2 sem nenhum 202 reprova (a fila nao ligou)" "$([ "$rc" = 1 ] && echo "$saida" | grep -q 'fila (S2): REPROVOU' && echo sim || echo nao)" "sim"
: > "$FAKE_LAGS"; printf '9\n9\n9\n9\n9\n9\n9\n9\n' > "$FAKE_LAGS"; LAG_ESPERA_S=2 saida="$(LAG_ESPERA_S=2 rodar "${POS[@]}")"; rc=$?
confere "pos: lag que nao chega a 0 no prazo reprova (codigo 1), mas o resto roda" "$([ "$rc" = 1 ] && echo "$saida" | grep -q 'FALHA: lag do group notification' && echo "$saida" | grep -q 'verificar-rpm (prefixo' && echo sim || echo nao)" "sim"
rm -f "$T/results/soak-T-pods.csv"; printf '0\n' > "$FAKE_LAGS"; saida="$(rodar "${POS[@]}")"; rc=$?
confere "pos: sem as amostras de pods (amostrar nao rodou) reprova" "$rc" "1"

[ "$falhas" -eq 0 ] && echo "selftest verificar-soak: OK" || { echo "selftest verificar-soak: $falhas falha(s)"; exit 1; }
