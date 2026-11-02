#!/usr/bin/env bash
# =============================================================================
#  verificar-soak.sh -- pre e pos-bloco do teste longo (soak, issue #232). Roda no HOST (Git Bash), contra o cluster Kubernetes (kubectl com --context).
# =============================================================================
#  Subcomandos:
#    pre      --id ID --perfil perfil-ID.json --bloco S1|S2 --gerador-ip IP [--gerador-offset-s S]   (IP: ou GERADOR_IP no ambiente, o mesmo do kit, o IP ATUAL do gerador (DHCP); S = offset do relogio do gerador
#             medido la com `w32tm /stripchart /computer:time.windows.com /samples:1 /dataonly`; o pre GRAVA o offset de host, no do kind e gerador no log e AVISA se a diferenca passar de 5 s: nao aborta nem sincroniza)
#             ABORTA (codigo 1, nada de carga) se: o `notification` VIVO nao tem os seis valores do Mailpit (MAIL_HOST=mailpit ... MAIL_PASSWORD vazio: a entrada TEM de existir,
#             sem valueFrom; a API omite `value` vazio, entao "ausente" e "vazio" sao distinguidos pelo nome) ou traz SPRING_MAIL_* / SPRING_APPLICATION_JSON / JAVA_TOOL_OPTIONS /
#             JDK_JAVA_OPTIONS com spring.mail (no env vivo ou como CHAVE do Secret env-notification); existe ScaledObject do stock ou o Hikari do stock != 245 (a #231 nao foi
#             aplicada); algum Deployment autoescalado tem replicas != o spec.minReplicaCount do ScaledObject vivo (1 se ausente; admission 2 desde a #235) ou nao esta pronto; 2x o total de cadastros esperado do perfil passa do MP_MAX_MESSAGES do Mailpit
#             APLICADO (o do render do overlay soak); o gatilho nao e o do bloco (decisao C: ADMISSION_X_RPM do Secret env-admission = 56000 no S1 e 4000 no S2, nenhum pod do
#             admission anterior ao Secret, targetValue do ScaledObject do reservation = 0,5 x X); a API do Mailpit responde SEM credencial com algo diferente de 401 (auth desligada);
#             as DUAS regras PERMANENTES de firewall da 8025 nao estao como devem (soak/conferir-firewall.ps1, pelo powershell.exe, SEM administrador: existem, habilitadas, entrada, TCP, LocalPort 8025,
#             perfil Any; Block com RemoteAddress = exatamente o complemento do IP do gerador INFORMADO AGORA + todo o IPv6; Allow com RemoteAddress = so o IP do gerador; IP novo do gerador ou
#             Allow ausente = aborta com a instrucao de rodar o Criar de novo); o Mailpit nao esta vazio. Registra "mailpit_inicio=0".
#    limpar-mailpit
#             DELETE /api/v1/messages: esvazia o Mailpit no inicio do bloco (depois do limpar-banco e do seed; o `pre` confere que esta vazio).
#    amostrar --id ID [--intervalo 5] [--vezes N]
#             a cada `intervalo` s grava results/soak-ID-replicas.csv (replicas e a metrica/alvo do HPA de cada servico) e soak-ID-pods.csv (restarts, pods saindo). Ctrl+C encerra.
#             Rode em segundo plano durante o bloco: e a fonte dos criterios 1 (Mailpit nao reiniciou, saida do notification) e 2 (autoescala).
#    pos      --id ID --perfil perfil-ID.json --bloco S1|S2 --sufixo SUFIXO --prefixo Soak-SUFIXO [--por-minuto soak-ID-por-minuto.csv]
#             NESTA ORDEM: (1) espera o lag do group `notification` chegar a 0 e registra; (2) conta o Mailpit filtrado pelo sufixo; (3) confere o From de TODAS as mensagens;
#             (4) roda o verificar-rpm (overselling e sagas; codigos 3 e 4 sobem como estao); (5) os criterios 1 a 3 pelo por-minuto, pelo perfil e pelas amostras (criterio 3 reprovado = lista os minutos com 5xx/status 0 ao lado dos pods que sairam neles; a fila, S2: 202 obrigatorio, 429 so registrado;
#             o criterio 2 le o spec.minReplicaCount de cada ScaledObject do cluster vivo e a descida espera voltar a ele; perfil de um tipo so (perfil.py --so): tipo ausente nao e cobrado, nem a fila sem reserva);
#             (6) so entao o bloco seguinte roda o limpar-banco. Codigo: 0 ok | 1 criterio reprovado | 3 overselling | 4 saga nao convergiu.
#  Ambiente: CARGA_K8S (contexto kubectl; padrao kind-gerenciador), MAILPIT_URL (http://localhost:8025), MAILPIT_AUTH (<usuario>:<senha>, SO do ambiente; vai ao curl por stdin
#  (-K -), nunca em argumento nem em log), RESULTS (Projeto/carga/results), LAG_ESPERA_S (600), VERIFICAR_RPM (comando do verificar-rpm; padrao: powershell -File rpm/subir-host-rpm.ps1 -Verificar).
#  Registro: results/soak-ID-verificacoes.log (acrescenta; sem segredo).
# =============================================================================
set -uo pipefail
unset MSYS_NO_PATHCONV
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CTX="${CARGA_K8S:-kind-gerenciador}"
NS=gerenciador
RESULTS="${RESULTS:-$AQUI/../results}"
MAILPIT_URL="${MAILPIT_URL:-http://localhost:8025}"
LAG_ESPERA_S="${LAG_ESPERA_S:-600}"
PY=""; for c in python3 python; do "$c" -c 'import json' >/dev/null 2>&1 && { PY="$c"; break; }; done
ID=""; LOG=""

kc() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' kubectl --context "$CTX" -n "$NS" "$@"; }
reg() { printf '%s [soak %s] %s\n' "$(date '+%F %T')" "${ID:-?}" "$*" | tee -a "${LOG:-/dev/null}"; }
abortar() { reg "ABORTA: $*"; exit 1; }
crit() { "$PY" "$AQUI/criterios.py" "$@"; }

# curl ao Mailpit: a credencial vai por stdin (-K -), nunca em argumento (apareceria na lista de processos). Caracteres validados (o config do curl usa aspas).
# mp <caminho> [args do curl]: GET por padrao (ex.: mp /api/v1/messages -X DELETE). A mesma regra de MAILPIT_AUTH do cluster.sh e do lib-soak.js (senha >= 16).
mp() {
    local caminho="$1"; shift
    [[ "${MAILPIT_AUTH:-}" =~ ^[A-Za-z0-9._-]+:[A-Za-z0-9._~+-]{16,}$ ]] || { echo "MAILPIT_AUTH ausente ou fora do formato <usuario>:<senha de >= 16 caracteres>" >&2; return 2; }
    printf 'user = "%s"\n' "$MAILPIT_AUTH" | curl -sS -f -m "${MP_TIMEOUT:-30}" -K - "$@" "$MAILPIT_URL$caminho"
}

# lag do group notification ("TOTAL-LAG"); -1 se o group nao existe/Dead ou o kubectl falhou
lag_notification() {
    kc exec -i sts/redpanda -c redpanda -- rpk group describe notification 2>/dev/null | tr -d '\r' \
        | awk '/^STATE/ {st = $2} /^TOTAL-LAG/ {lag = $2} END { if (st == "Dead" || lag == "" || lag !~ /^[0-9]+$/) print -1; else print lag }'
}

args() { # --chave valor ... -> variaveis A_<CHAVE>
    while [ $# -gt 0 ]; do
        case "$1" in --*) [ $# -ge 2 ] || abortar "falta o valor de $1"; v="${1#--}"; printf -v "A_${v//-/_}" '%s' "$2"; shift 2 ;; *) abortar "argumento desconhecido: $1" ;; esac
    done
}
json_campo() { "$PY" -c 'import json,sys; d=json.load(open(sys.argv[1], encoding="utf-8")); print(d[sys.argv[2]])' "$1" "$2"; }

bloco_valido() { [ "$1" = S1 ] || [ "$1" = S2 ]; }

# Relogios (so GRAVA, nao sincroniza nada): adiantamento (local - referencia, + = relogio ADIANTADO) do host, do no do kind e do gerador contra uma referencia comum (REF_NTP, padrao time.windows.com).
# ATENCAO ao sinal: o w32tm /stripchart imprime ref - local (conferido contra um SNTP proprio: local 1,3 s adiantado imprime -01.30s); aqui o numero do w32tm e INVERTIDO. Virgula decimal (locale) aceita.
#  host     w32tm /stripchart /computer:<ref> /samples:1 /dataonly
#  no       `date -u` dentro do no (docker exec <contexto sem "kind-">-control-plane; os timestamps do kubelet vem da VM do Docker) contra o do host, no ponto medio da chamada, + o offset do host
#  gerador  informado na partida (--gerador-offset-s), o numero que o w32tm imprime LA (mesmo comando: w32tm /stripchart /computer:time.windows.com /samples:1 /dataonly), "-01.3043740s" ou "-1,3"
# Diferenca relativa > 5 s so AVISA (o criterio 3 liga minutos a pods por horario: com deriva maior o "nenhum pod saiu neste minuto" pode estar errado). Tudo vai ao log do bloco.
registrar_relogios() {
    local ger="${1:-}" ref="${REF_NTP:-time.windows.com}" no="${CTX#kind-}-control-plane" host_off t0 tn t1 linha
    host_off="$(MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' w32tm /stripchart /computer:"$ref" /samples:1 /dataonly 2>/dev/null </dev/null | tr -d '\r' | sed -n 's/.*, *\([-+][0-9.,]*\)s.*/\1/p' | tail -1)"
    t0="$(date -u +%s.%N)"; tn="$(docker exec "$no" date -u +%s.%N 2>/dev/null </dev/null | tr -d '\r')"; t1="$(date -u +%s.%N)"
    while IFS= read -r linha; do reg "$linha"; done < <("$PY" -c '
import sys
ref, h, t0, tn, t1, g = sys.argv[1:7]
def num(x):
    try: return float(x.strip().rstrip("sS").replace(",", "."))
    except ValueError: return None
h, t0, tn, t1, g = num(h), num(t0), num(tn), num(t1), num(g)
if h is not None: h = -h   # w32tm imprime ref - local; aqui + = relogio adiantado
if g is not None: g = -g
delta = tn - (t0 + t1) / 2 if None not in (t0, tn, t1) else None   # no - host (relogio do no adiantado = +)
vals = {}
if h is not None: vals["host"] = h
if delta is not None: vals["no"] = (h + delta) if h is not None else delta
if g is not None: vals["gerador"] = g
fmt = lambda k: ("%s=%+.3f s" % (k, vals[k])) if k in vals else "%s=n/d" % k
if h is None and delta is not None:
    print("relogios: sem %s (w32tm falhou): referencia = o host; no=%+.3f s contra o host; gerador %s" % (ref, delta, "informado mas sem referencia comum" if g is not None else "nao informado"))
else:
    print("relogios (adiantamento contra %s, + = relogio adiantado): %s, %s, %s" % (ref, fmt("host"), fmt("no"), fmt("gerador")))
if h is None and delta is None: print("AVISO: nao li nenhum offset (w32tm e docker exec indisponiveis); os horarios dos pods que saem nao foram conferidos contra os do k6.")
if g is None: print("AVISO: o relogio do gerador nao foi informado (--gerador-offset-s): a ligacao minuto do k6 x pod que saiu depende dele.")
if len(vals) >= 2 and h is not None:
    maior = max(vals.values()) - min(vals.values())
    print("relogios: maior diferenca entre os relogios = %.3f s" % maior)
    if maior > 5: print("AVISO: relogios com diferenca > 5 s (%.3f s): o criterio 3 pode ligar o minuto ao pod errado. Nada foi sincronizado; se quiser, w32tm /resync nos tres e rode o pre de novo." % maior)
elif delta is not None and abs(delta) > 5:
    print("AVISO: no do kind %.3f s diferente do host (> 5 s)." % delta)
' "$ref" "$host_off" "$t0" "$tn" "$t1" "$ger")
}

cmd_pre() {
    args "$@"; ID="${A_id:-}"; local gip="${A_gerador_ip:-${GERADOR_IP:-}}"
    [ -n "$ID" ] && [ -f "${A_perfil:-}" ] && bloco_valido "${A_bloco:-}" && [[ "$gip" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "uso: verificar-soak.sh pre --id ID --perfil perfil-ID.json --bloco S1|S2 --gerador-ip <ip do gerador> (ou GERADOR_IP no ambiente) [--gerador-offset-s <offset do relogio do gerador>]" >&2; exit 2; }
    mkdir -p "$RESULTS"; LOG="$RESULTS/soak-$ID-verificacoes.log"
    local tmp total lista n x_vivo t_secret t_pod antigo=0 sem_auth; tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
    total="$(json_campo "$A_perfil" total_cadastros_esperado)" || abortar "nao li total_cadastros_esperado de $A_perfil"
    kc get deploy,scaledobject -o json > "$tmp/lista.json" 2>"$tmp/kc.err" || abortar "kubectl get deploy,scaledobject falhou no contexto $CTX (cluster no ar?)"
    reg "pre-check no cluster $CTX: perfil $A_perfil, bloco $A_bloco, cadastros esperados $total"
    # o gatilho vivo: o X do Secret env-admission (ADMISSION_X_RPM nao e segredo: o jsonpath le SO essa chave) e se algum pod do admission subiu ANTES do Secret ser aplicado
    # (o envFrom nao recarrega: o X dele seria o do bloco anterior). As CHAVES do Secret env-notification (nunca os valores) vao ao criterio dos SPRING_MAIL.
    x_vivo="$(kc get secret env-admission -o jsonpath='{.data.ADMISSION_X_RPM}' 2>/dev/null | base64 -d 2>/dev/null | tr -d '[:space:]')"
    t_secret="$(kc get secret env-admission -o jsonpath='{.metadata.managedFields[*].time}' 2>/dev/null | tr ' ' '\n' | sort | tail -1)"
    while read -r t_pod; do [ -n "$t_pod" ] && [ -n "$t_secret" ] && [[ "$t_pod" < "$t_secret" ]] && antigo=1; done < <(kc get pods -l app=admission -o jsonpath='{range .items[*]}{.status.startTime}{"\n"}{end}' 2>/dev/null)
    kc get secret env-notification -o go-template='{{range $k, $v := .data}}{{$k}}{{"\n"}}{{end}}' > "$tmp/chaves-notification.txt" 2>/dev/null || abortar "nao li as chaves do Secret env-notification (cluster no ar?)"
    if ! crit pre --lista "$tmp/lista.json" --total "$total" --bloco "$A_bloco" ${x_vivo:+--x-vivo "$x_vivo"} --admission-antigo "$antigo" --chaves-secret "$tmp/chaves-notification.txt" | tee -a "$LOG"; then abortar "pre-check reprovou: NAO rode o k6 neste cluster (e-mail real, #231 ausente, replicas != minReplicaCount ou Mailpit pequeno)."; fi
    reg "ok: notification -> Mailpit (MAIL_HOST=mailpit, MAIL_PASSWORD vazio, sem spring.mail), sem ScaledObject do stock, Hikari do stock 245, autoescalados no minReplicaCount (1 por padrao) e prontos, MP_MAX_MESSAGES >= 2x $total, bloco $A_bloco com ADMISSION_X_RPM=${x_vivo:-?} e alvo do KEDA = 0,5 x X"
    # a regra PERMANENTE de firewall (decisao 5): criada uma vez pelo usuario como administrador; aqui so e CONFERIDA (powershell.exe le Get-NetFirewallRule/PortFilter/AddressFilter, sem administrador)
    local fw_ps1="$AQUI/conferir-firewall.ps1"; command -v cygpath >/dev/null 2>&1 && fw_ps1="$(cygpath -w "$fw_ps1")"
    "${POWERSHELL:-powershell.exe}" -NoProfile -ExecutionPolicy Bypass -File "$fw_ps1" -GeradorIp "$gip" </dev/null | tee -a "$LOG" || abortar "as regras de firewall da 8025 (Block e Allow do gerador) nao estao como devem (acima). Se o IP do gerador mudou (DHCP), falta a Allow ou a regra e outra, rode o Criar de novo com o IP novo, como administrador (o gerador fica bloqueado ate la, a porta nao abre): powershell -File soak\\firewall-mailpit.ps1 -Acao Criar -GeradorIp $gip"
    reg "ok: regra de firewall da 8025 confere (Block de todo o IPv4 menos o gerador $gip e Allow so do gerador)"
    registrar_relogios "${A_gerador_offset_s:-}"
    # a basic auth do Mailpit esta ligada: um GET SEM credencial tem de dar 401 (nenhum -K: a credencial nem e lida)
    sem_auth="$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$MAILPIT_URL/api/v1/messages?limit=1" </dev/null 2>/dev/null)"
    [ "$sem_auth" = 401 ] || abortar "o Mailpit respondeu HTTP ${sem_auth:-000} a um GET SEM credencial em $MAILPIT_URL (esperado 401: a basic auth esta desligada ou o Mailpit esta fora; firewall? NodePort 30825/host 8025?)"
    reg "ok: GET sem credencial = 401 (basic auth do Mailpit ligada)"
    mp "/api/v1/messages?limit=1" > "$tmp/mp.json" 2>"$tmp/mp.err" || abortar "o Mailpit nao respondeu em $MAILPIT_URL (firewall? MAILPIT_AUTH? NodePort 30825/host 8025 do kind.yaml?)"
    n="$("$PY" -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("total", -1))' "$tmp/mp.json")"
    [ "$n" = 0 ] || abortar "o Mailpit tem $n mensagens (esperado 0 no inicio do bloco: DELETE /api/v1/messages)"
    reg "mailpit_inicio=0"
}

cmd_limpar_mailpit() {
    MP_TIMEOUT=60 mp /api/v1/messages -X DELETE >/dev/null || { echo "DELETE /api/v1/messages falhou em $MAILPIT_URL (ou MAILPIT_AUTH invalida)" >&2; exit 1; }
    echo "Mailpit esvaziado ($MAILPIT_URL)"
}

cmd_amostrar() {
    args "$@"; ID="${A_id:-}"; [ -n "$ID" ] || { echo "uso: verificar-soak.sh amostrar --id ID [--intervalo 5] [--vezes N]" >&2; exit 2; }
    mkdir -p "$RESULTS"; local n=0 max="${A_vezes:-0}" passo="${A_intervalo:-5}"
    while :; do
        kc get hpa,deploy,pods -o json 2>/dev/null | "$PY" "$AQUI/amostra.py" --prefixo "$RESULTS/soak-$ID" || echo "aviso: amostra falhou (kubectl?)" >&2
        n=$((n + 1)); [ "$max" -gt 0 ] && [ "$n" -ge "$max" ] && break
        sleep "$passo"
    done
}

cmd_pos() {
    args "$@"; ID="${A_id:-}"
    [ -n "$ID" ] && [ -f "${A_perfil:-}" ] && bloco_valido "${A_bloco:-}" && [ -n "${A_sufixo:-}" ] && [ -n "${A_prefixo:-}" ] || { echo "uso: verificar-soak.sh pos --id ID --perfil F --bloco S1|S2 --sufixo S --prefixo P [--por-minuto F]" >&2; exit 2; }
    mkdir -p "$RESULTS"; LOG="$RESULTS/soak-$ID-verificacoes.log"
    local pm="${A_por_minuto:-$RESULTS/soak-$ID-por-minuto.csv}" rep="$RESULTS/soak-$ID-replicas.csv" pods="$RESULTS/soak-$ID-pods.csv"
    local tmp falhas=0 lag fim esperados total pag i rc_rpm=0 minimos; tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
    [ -f "$pm" ] || abortar "sem o por-minuto ($pm): rode agregar.py antes"
    # 1. lag do group notification ate 0 (o e-mail do ultimo cadastro so existe depois dele)
    fim=$((SECONDS + LAG_ESPERA_S)); lag="$(lag_notification)"
    while [ "$lag" != 0 ] && [ "$SECONDS" -lt "$fim" ]; do sleep 5; lag="$(lag_notification)"; done
    if [ "$lag" = 0 ]; then reg "1. lag_notification=0 (group notification drenado)"; else reg "1. FALHA: lag do group notification = $lag depois de ${LAG_ESPERA_S}s (e-mails ainda por sair; a contagem do Mailpit abaixo pode estar incompleta)"; falhas=$((falhas + 1)); fi
    # 2 e 3. Mailpit: todas as mensagens (paginas de 500); filtra pelo sufixo na contagem; o From de TODAS
    esperados="$("$PY" -c '
import csv, sys
print(sum(int(l["verificacoes_2xx"] or 0) for l in csv.DictReader(open(sys.argv[1], encoding="utf-8", newline=""))))' "$pm")" || abortar "nao li verificacoes_2xx de $pm"
    mp "/api/v1/messages?start=0&limit=500" > "$tmp/pag-0.json" 2>"$tmp/mp.err" || abortar "o Mailpit nao respondeu em $MAILPIT_URL"
    total="$("$PY" -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("total", 0))' "$tmp/pag-0.json")"
    i=500; while [ "$i" -lt "$total" ]; do mp "/api/v1/messages?start=$i&limit=500" > "$tmp/pag-$i.json" 2>>"$tmp/mp.err" || abortar "o Mailpit falhou na pagina start=$i"; i=$((i + 500)); done
    [ -f "$pods" ] || { reg "2. FALHA: sem $pods (o 'amostrar' nao rodou no bloco): sem as saidas do notification nao ha como tolerar duplicados"; : > "$tmp/pods.csv"; echo "epoch_s,pod,app,pronto,restarts,terminando" > "$tmp/pods.csv"; falhas=$((falhas + 1)); }
    [ -f "$pods" ] && cp "$pods" "$tmp/pods.csv"
    reg "2/3. Mailpit com $total mensagens no total; cadastros 2xx esperados: $esperados"
    if crit emails --paginas "$tmp"/pag-*.json --esperados "$esperados" --sufixo "$A_sufixo" --pods "$tmp/pods.csv" | tee -a "$LOG"; then reg "criterio 1 (e-mail): ok"; else reg "criterio 1 (e-mail): REPROVOU (perdido, duplicado fora de +-60 s de um pod do notification saindo, ou From fora de @example.com)"; falhas=$((falhas + 1)); fi
    # 3b. a carga entregue nao pode passar por vacuo: cada tipo com >= 90% das iteracoes que o perfil esperava (S1 de 2026-11-02: 37253 cadastros com 400 e "criterio 1: ok")
    if crit entregue --por-minuto "$pm" --perfil "$A_perfil" --bloco "$A_bloco" | tee -a "$LOG"; then reg "carga entregue: ok"; else reg "criterio 1 (carga entregue): REPROVOU (tipo com menos de 90% das iteracoes do perfil: o fluxo falhou; veja c400/c5xx do tipo no por-minuto)"; falhas=$((falhas + 1)); fi
    # 4. overselling e sagas (o verificar-rpm do kit, em modo cluster); 3 e 4 sobem como estao
    if [ -n "${VERIFICAR_RPM:-}" ]; then CARGA_K8S="$CTX" $VERIFICAR_RPM "$A_prefixo"; rc_rpm=$?
    else CARGA_K8S="$CTX" powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$AQUI/../rpm/subir-host-rpm.ps1" 2>/dev/null || echo "$AQUI/../rpm/subir-host-rpm.ps1")" -Verificar -Prefixo "$A_prefixo"; rc_rpm=$?; fi
    reg "4. verificar-rpm (prefixo $A_prefixo): codigo $rc_rpm"
    [ "$rc_rpm" = 0 ] || falhas=$((falhas + 1))
    # 5. criterios 1 (Mailpit nao reiniciou), 2 (autoescala) e 3 (0 respostas 5xx)
    if [ -f "$pods" ] && crit restarts --pods "$pods" --app mailpit | tee -a "$LOG"; then :; else reg "criterio 1 (Mailpit): REPROVOU ou sem amostras de pods"; falhas=$((falhas + 1)); fi
    # o minimo de cada servico (spec.minReplicaCount do ScaledObject VIVO: o admission sobe com 2 desde a #235): a descida do criterio 2 espera voltar a ele; sem o dado vale 1
    minimos="$(kc get scaledobject -o jsonpath='{range .items[*]}{.spec.scaleTargetRef.name}={.spec.minReplicaCount},{end}' 2>/dev/null)" || minimos=""
    reg "minReplicaCount dos ScaledObjects no cluster vivo: ${minimos:-nao lido (criterio 2 com minimo 1)}"
    if [ -f "$rep" ] && crit crit2 --replicas "$rep" --bloco "$A_bloco" --minimos "$minimos" | tee -a "$LOG"; then reg "criterio 2 (autoescala acompanha a carga): ok"; else reg "criterio 2: REPROVOU ou sem amostras de replicas ($rep)"; falhas=$((falhas + 1)); fi
    if crit crit3 --por-minuto "$pm" --pods "$pods" | tee -a "$LOG"; then reg "criterio 3 (troca de replica sem erro): ok"; else reg "criterio 3: REPROVOU (5xx ou status 0 fora de tipo=mailpit; os minutos e os pods que sairam neles estao acima)"; falhas=$((falhas + 1)); fi
    if crit fila --por-minuto "$pm" --bloco "$A_bloco" --perfil "$A_perfil" | tee -a "$LOG"; then :; else reg "fila ($A_bloco): REPROVOU (202 obrigatorio no S2)"; falhas=$((falhas + 1)); fi
    reg "criterio 4 (gerador valido: RAM livre >= 1 GB, sem Insufficient VUs fora do S2, memoria do k6 estavel) e a verificacao do gerador: nao sai deste script; confira descartadas no por-minuto"
    if [ "$rc_rpm" = 3 ] || [ "$rc_rpm" = 4 ]; then reg "RESULTADO: bloco REPROVADO (verificar-rpm codigo $rc_rpm)"; exit "$rc_rpm"; fi
    if [ "$falhas" -gt 0 ]; then reg "RESULTADO: bloco REPROVADO ($falhas verificacao(oes))"; exit 1; fi
    reg "RESULTADO: bloco APROVADO nos criterios 1 a 3 (rode o limpar-banco antes do proximo bloco)"
}

[ "${SOAK_LIB:-0}" = 1 ] && return 0 2>/dev/null
[ -n "$PY" ] || { echo "verificar-soak: preciso de python" >&2; exit 2; }
sub="${1:-}"; [ $# -gt 0 ] && shift
case "$sub" in
    pre) cmd_pre "$@" ;;
    amostrar) cmd_amostrar "$@" ;;
    limpar-mailpit) cmd_limpar_mailpit ;;
    pos) cmd_pos "$@" ;;
    *) awk 'NR >= 2 && /^# =+$/ {n++} NR >= 2 && n < 2 {print}' "$0" | head -30; exit 2 ;;
esac
