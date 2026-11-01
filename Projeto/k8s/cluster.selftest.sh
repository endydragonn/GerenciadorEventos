#!/usr/bin/env bash
# Selftest do cluster.sh no que a #228 acrescentou (db remoto, overlay rodada, psql pelo pod cliente, db-teste), SEM Docker, kind nem cluster: um projeto
# temporário (cópia de cluster.sh, base, overlays e lib) com .env vazios, um `docker` falso (só o JSON do `compose config`), `kind`/`helm` falsos e, nos testes
# de psql/db-teste, um `kubectl` falso que registra as chamadas. O render é o `kubectl kustomize` de verdade (offline). Confere: a validação de K8S_DB_REMOTO
# (IP:porta), o render do componente com as 3 trilhas (sem StatefulSet db, Service db sem selector, EndpointSlice com o IP e a porta), que sem a variável o
# render não muda, a recusa do overlay rodada no cluster e2e e do db remoto com Secrets efêmeros, a escolha local x remoto do psql, a senha fora dos
# argumentos, o --context em todo kubectl e os códigos de saída do db-teste.
set -u
AQUI="$(cd "$(dirname "$0")" && pwd)"
falhas=0
confere() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FALHA $1: esperado '$3', veio '$2'"; falhas=$((falhas+1)); fi; }
PY=""; for c in python3 python; do "$c" -c 'import json' >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || { echo "selftest cluster: preciso de python"; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "selftest cluster: preciso do kubectl (o render é offline)"; exit 1; }
export KREAL="$(command -v kubectl)"   # o kubectl de verdade (so para o render offline); os falsos entram depois no PATH

T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
P="$T/Projeto"; mkdir -p "$P/k8s" "$P/supabase" "$T/bin-falso"
cp -r "$AQUI/base" "$AQUI/overlays" "$AQUI/cluster.sh" "$P/k8s/"
cp "$AQUI/lib-db-cliente.sh" "$AQUI/db-cliente.yaml" "$P/k8s/" 2>/dev/null
: > "$P/.env"; : > "$P/supabase/.env"
JSON="$T/compose.json"
cat > "$JSON" <<'EOF'
{"services":{"db":{"environment":{"POSTGRES_PASSWORD":"x"}},"supabase-pooler":{"environment":{}},"redis":{"environment":{}},"rabbitmq":{"environment":{}},
"backend":{"environment":{}},"identity":{"environment":{}},"catalog":{"environment":{}},"reservation":{"environment":{}},"stock":{"environment":{}},
"notification":{"environment":{}},"admission":{"environment":{"ADMISSION_X_RPM":"56000"}}}}
EOF
for f in docker kind helm; do printf '#!/usr/bin/env bash\ncase "$*" in *"config --format json"*) cat "$FAKE_COMPOSE_JSON" ;; esac\nexit 0\n' > "$T/bin-falso/$f"; done
chmod +x "$T/bin-falso/"*
export FAKE_COMPOSE_JSON="$JSON"
SH="$P/k8s/cluster.sh"
rodar() { ( cd "$P" && PATH="$T/bin-falso:$PATH" "$@" 2>&1 ); }   # rodar ENV=.. bash cluster.sh ...

# --- 1. validação de K8S_DB_REMOTO: IP:porta, antes de qualquer outra coisa
for v in "192.168.68.50" "db.local:5433" "192.168.68.50:0" "192.168.68.50:70000" "300.1.1.1:5433" "192.168.68.50:abc" ":5433" "192.168.68.50:5433:1" "::1:5433"; do
    saida="$(rodar env K8S_DB_REMOTO="$v" K8S_OVERLAY=rodada bash "$SH" guarda --trilha nenhuma)"; rc=$?
    confere "K8S_DB_REMOTO='$v' recusado (codigo != 0 e mensagem)" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'K8S_DB_REMOTO' && echo sim || echo nao)" "sim"
done
saida="$(rodar env K8S_DB_REMOTO=192.168.68.50:5433 K8S_OVERLAY=rodada bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "K8S_DB_REMOTO=192.168.68.50:5433 aceito" "$rc" "0"
confere "guarda mostra o db remoto aplicado" "$(echo "$saida" | grep -c 'db remoto.*192.168.68.50:5433')" "1"
saida="$(rodar env K8S_DB_REMOTO=192.168.68.50:5433 K8S_OVERLAY=e2e bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "db remoto com overlay e2e (Secrets efemeros) e recusado" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'Secrets' && echo sim || echo nao)" "sim"
saida="$(rodar env K8S_OVERLAY=rodada K8S_NOME=gerenciador-e2e-x bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "overlay rodada (Secrets REAIS) nao vai para o cluster gerenciador-e2e*" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q "rodada" && echo sim || echo nao)" "sim"

# --- 2. render: o componente com as 3 trilhas; sem a variavel o render nao muda
docs_de() { "$PY" -c '
import re, sys
for d in sys.stdin.read().split("\n---\n"):
    k = re.search(r"^kind: (\S+)", d, re.M); n = re.search(r"^metadata:\n(?:  .*\n)*?  name: (\S+)", d, re.M)
    print((k.group(1) if k else "?") + "/" + (n.group(1) if n else "?"))
'; }
servico_db() { "$PY" -c '
import re, sys
for d in sys.stdin.read().split("\n---\n"):
    if re.search(r"^kind: Service$", d, re.M) and re.search(r"^  name: db$", d, re.M): print(d)
'; }
for tr in nenhuma c b1; do
    r="$(rodar env K8S_DB_REMOTO=192.168.68.50:5433 K8S_OVERLAY=rodada bash "$SH" renderizar --trilha "$tr")"
    confere "render remoto/$tr: sem StatefulSet db (o redpanda fica)" "$(echo "$r" | docs_de | grep -c '^StatefulSet/db$')$(echo "$r" | docs_de | grep -c '^StatefulSet/redpanda$')" "01"
    confere "render remoto/$tr: Service db sem selector, porta 5432" "$(echo "$r" | servico_db | grep -c 'selector')$(echo "$r" | servico_db | grep -c 'port: 5432')" "01"
    confere "render remoto/$tr: EndpointSlice com o IP e a porta dados" "$(echo "$r" | grep -c -- '- 192.168.68.50$')$(echo "$r" | grep -c 'port: 5433$')" "11"
    confere "render remoto/$tr: Supavisor intacto (espera nc db 5432)" "$(echo "$r" | grep -c 'nc -w 2 db 5432')" "1"
done
base="$(rodar env K8S_OVERLAY=rodada bash "$SH" renderizar --trilha nenhuma)"
confere "render sem a variavel: StatefulSet db e Service db com selector, nenhuma EndpointSlice" "$(echo "$base" | docs_de | grep -c '^StatefulSet/db$')$(echo "$base" | servico_db | grep -c 'selector')$(echo "$base" | grep -c 'kind: EndpointSlice')" "110"
confere "overlay rodada: NodePort 30081/30088 como no base e SEED_ENABLED=true no gateway" "$(echo "$base" | grep -c 'nodePort: 30081')$(echo "$base" | grep -c 'nodePort: 30088')$(echo "$base" | grep -c 'name: SEED_ENABLED')" "111"
conf_base="$(rodar env K8S_OVERLAY=base bash "$SH" renderizar --trilha nenhuma)"
confere "overlay base: SEED_ENABLED continua ausente" "$(echo "$conf_base" | grep -c 'SEED_ENABLED')" "0"

# --- 2b. pools da rodada (#228): o overlay rodada leva o pool real do Supavisor em 245 e, com o db no cluster, max_connections=300; K8S_POOLS=r5 liga os pools de replica unica da R5
valor_env() { "$PY" -c '
import re, sys
svc, var = sys.argv[1:3]
for d in sys.stdin.read().split("\n---\n"):
    if re.search(r"^kind: Deployment$", d, re.M) and re.search(r"^metadata:\n(?:  .*\n)*?  name: " + svc + "$", d, re.M):
        m = re.search(r"- name: " + var + r"\n\s+value: \"?([^\"\n]*)\"?", d)
        print(m.group(1) if m else "")
' "$1" "$2"; }
doc_db() { "$PY" -c '
import re, sys
for d in sys.stdin.read().split("\n---\n"):
    if re.search(r"^kind: StatefulSet$", d, re.M) and re.search(r"^metadata:\n(?:  .*\n)*?  name: db$", d, re.M): print(d)
'; }
r_rod="$(rodar env K8S_OVERLAY=rodada bash "$SH" renderizar --trilha nenhuma)"
confere "rodada (db no cluster): pool real do Supavisor 245 e max_connections=300 no db" "$(echo "$r_rod" | valor_env supabase-pooler POOLER_DEFAULT_POOL_SIZE)|$(echo "$r_rod" | doc_db | grep -c 'max_connections=300')" "245|1"
r_rod_r="$(rodar env K8S_OVERLAY=rodada K8S_DB_REMOTO=192.168.68.50:5433 bash "$SH" renderizar --trilha b1)"
confere "rodada (db remoto, trilha b1): pool real 245 (o max_connections e do banco remoto, sem StatefulSet db)" "$(echo "$r_rod_r" | valor_env supabase-pooler POOLER_DEFAULT_POOL_SIZE)|$(echo "$r_rod_r" | docs_de | grep -c '^StatefulSet/db$')" "245|0"
r_bas="$(rodar env K8S_OVERLAY=base bash "$SH" renderizar --trilha nenhuma)"
confere "base: sem POOLER_DEFAULT_POOL_SIZE no manifest (45 vem do Secret) e sem max_connections" "$(echo "$r_bas" | valor_env supabase-pooler POOLER_DEFAULT_POOL_SIZE)|$(echo "$r_bas" | doc_db | grep -c 'max_connections')" "|0"
confere "K2 (rodada sem K8S_POOLS): pools do cluster (reservation 200, stock 245 fixo na #231, identity/catalog 50) e autoescala ate 3 nos 6 servicos escalaveis (stock fora)" "$(echo "$r_rod" | valor_env reservation SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE),$(echo "$r_rod" | valor_env stock SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE),$(echo "$r_rod" | valor_env identity SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE),$(echo "$r_rod" | valor_env catalog SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE)|$(echo "$r_rod" | grep -c 'maxReplicaCount: 3')" "200,245,50,50|6"
# #231: o stock e UMA replica fixa (Recreate, sem ScaledObject): sem HPA e sem pod velho sobreposto ao novo
doc_deploy() { "$PY" -c '
import re, sys
for d in sys.stdin.read().split("\n---\n"):
    if re.search(r"^kind: Deployment$", d, re.M) and re.search(r"^metadata:\n(?:  .*\n)*?  name: " + sys.argv[1] + "$", d, re.M): print(d)
' "$1"; }
confere "stock fixo (#231): Deployment com replicas 1 e strategy Recreate, sem rollingUpdate" "$(echo "$r_rod" | doc_deploy stock | grep -c '^  replicas: 1$')$(echo "$r_rod" | doc_deploy stock | grep -c 'type: Recreate')$(echo "$r_rod" | doc_deploy stock | grep -c 'rollingUpdate')" "110"
confere "stock fixo (#231): nenhum ScaledObject do stock (6 ScaledObjects no total)" "$(echo "$r_rod" | docs_de | grep -c '^ScaledObject/stock$')|$(echo "$r_rod" | docs_de | grep -c '^ScaledObject/')" "0|6"
confere "os outros 6 servicos seguem sem replicas no Deployment (o KEDA e dono da contagem)" "$(for s in backend identity catalog reservation notification admission; do echo "$r_rod" | doc_deploy $s | grep -c '^  replicas:'; done | tr -d '\r' | tr '\n' ',')" "0,0,0,0,0,0,"
# a regra de pools do cluster (#231), RECALCULADA do render: servico escalavel conta (maxReplicaCount + $1 em termino) x Hikari ($1 = 1 no K2; 0 no r5, que e replica unica com maxSurge 0); o stock, 1 x Hikari (Recreate, sem sobreposicao)
soma_pools() { "$PY" -c '
import re, sys
docs = sys.stdin.read().split("\n---\n")
def nome(d): return (re.search(r"^metadata:\n(?:  .*\n)*?  name: (\S+)", d, re.M) or [None, "?"])[1]
mx = {nome(d): int(re.search(r"maxReplicaCount: (\d+)", d).group(1)) for d in docs if re.search(r"^kind: ScaledObject$", d, re.M)}
tot = 0
for d in docs:
    if not re.search(r"^kind: Deployment$", d, re.M): continue
    m = re.search(r"- name: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE\n\s+value: \"?(\d+)", d)
    if m: tot += int(m.group(1)) * ((mx[nome(d)] + int(sys.argv[1])) if nome(d) in mx else 1)
print(tot)
' "${1:-1}"; }
confere "regra de pools do cluster (#231): 4x200 + 4x10 + 4x3 + 4x50 + 4x50 + 245 = 1497 <= 1500 clientes no Supavisor (somada do render)" "$(echo "$r_rod" | soma_pools | tr -d '\r')" "1497"
r_r5="$(rodar env K8S_OVERLAY=rodada K8S_POOLS=r5 bash "$SH" renderizar --trilha nenhuma)"
confere "K8S_POOLS=r5: Hikari do compose (reservation 1000, stock 250, identity 100, catalog 100, backend 10, notification 3)" "$(for s in reservation stock identity catalog backend notification; do echo "$r_r5" | valor_env $s SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE; done | tr -d '\r' | tr '\n' ',')" "1000,250,100,100,10,3,"
confere "K8S_POOLS=r5: workers do admission 32 e pool real 245" "$(echo "$r_r5" | valor_env admission ADMISSION_WORKER_CONCURRENCY)|$(echo "$r_r5" | valor_env supabase-pooler POOLER_DEFAULT_POOL_SIZE)" "32|245"
confere "K8S_POOLS=r5: autoescala travada em 1 por padrao (os 6 ScaledObjects com maxReplicaCount 1; o stock e fixo, sem ScaledObject) e stock com Recreate" "$(echo "$r_r5" | grep -c 'maxReplicaCount: 1')$(echo "$r_r5" | grep -c 'maxReplicaCount: 3')$(echo "$r_r5" | doc_deploy stock | grep -c 'type: Recreate')" "601"
confere "K8S_POOLS=r5: soma dos Hikari no Supavisor 1463 (1000 + 250 + 100 + 100 + 10 + 3, 1 replica de cada)" "$(echo "$r_r5" | soma_pools 0 | tr -d '\r')" "1463"
confere "K8S_POOLS=r5 + db remoto + b1: os pools entram junto com o componente db-remoto" "$(rodar env K8S_OVERLAY=rodada K8S_POOLS=r5 K8S_DB_REMOTO=192.168.68.50:5433 bash "$SH" renderizar --trilha b1 | valor_env reservation SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE)" "1000"
saida="$(rodar env K8S_OVERLAY=rodada K8S_POOLS=r5 K8S_RESERVATION_MAX=2 bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "K8S_POOLS=r5 com K8S_RESERVATION_MAX=2: recusado (estouraria o Supavisor com replicas)" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'K8S_POOLS=r5' && echo "$saida" | grep -q 'K8S_RESERVATION_MAX' && echo sim || echo nao)" "sim"
saida="$(rodar env K8S_OVERLAY=rodada K8S_POOLS=r5 K8S_RESERVATION_MAX=1 K8S_BACKEND_MAX=1 bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "K8S_POOLS=r5 com MAX=1 explicito: aceito, e o guarda mostra os pools" "$rc$(echo "$saida" | grep -c 'pools R5')" "01"
# o stock saiu da autoescala (#231): K8S_STOCK_MIN/MAX nao viram mais ScaledObject, entao um valor > 1 seria ignorado em silencio; o cluster.sh recusa
for v in K8S_STOCK_MAX=2 K8S_STOCK_MAX=3 K8S_STOCK_MIN=2; do
    saida="$(rodar env K8S_OVERLAY=rodada "$v" bash "$SH" guarda --trilha nenhuma)"; rc=$?
    confere "$v: recusado (o stock e fixo em 1 replica, #231)" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'K8S_STOCK' && echo sim || echo nao)" "sim"
done
saida="$(rodar env K8S_OVERLAY=rodada K8S_STOCK_MIN=1 K8S_STOCK_MAX=1 bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "K8S_STOCK_MIN=1 e K8S_STOCK_MAX=1 (o valor fixo): aceitos" "$rc" "0"
saida="$(rodar env K8S_OVERLAY=rodada K8S_POOLS=r6 bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "K8S_POOLS invalido (so r5 ou vazio)" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'K8S_POOLS' && echo sim || echo nao)" "sim"

# --- 2c. o pod cliente so recebe as chaves que le (secretKeyRef), nunca o Secret inteiro (#228, volta 2)
confere "db-cliente.yaml: sem envFrom (nao monta o env-db inteiro)" "$(grep -c 'envFrom' "$AQUI/db-cliente.yaml")" "0"
confere "db-cliente.yaml: secretKeyRef so de APP_DB_USER, APP_DB_PASSWORD, POSTGRES_PASSWORD, POSTGRES_DB (env-db) e POOLER_TENANT_ID (env-supabase-pooler)" "$(grep -c 'secretKeyRef: {' "$AQUI/db-cliente.yaml")|$(grep -o 'key: [A-Z_]*' "$AQUI/db-cliente.yaml" | sort | tr '\n' ' ')" "5|key: APP_DB_PASSWORD key: APP_DB_USER key: POOLER_TENANT_ID key: POSTGRES_DB key: POSTGRES_PASSWORD "

# --- 2d. as linhas [ok] do guarda leem os valores do render (nao imprimem valores fixos): muda o componente na copia temporaria e o guarda acompanha
sed -i 's/value: "1000"/value: "777"/' "$P/k8s/overlays/pools-r5/kustomization.yaml"
saida="$(rodar env K8S_OVERLAY=rodada K8S_POOLS=r5 bash "$SH" guarda --trilha nenhuma)"
confere "guarda: a linha dos pools R5 mostra o valor LIDO do render (reservation=777 depois de mudar o componente na copia)" "$(echo "$saida" | grep 'pools R5' | grep -c 'reservation=777')" "1"
sed -i 's/value: "777"/value: "1000"/' "$P/k8s/overlays/pools-r5/kustomization.yaml"
saida="$(rodar env K8S_OVERLAY=rodada K8S_POOLS=r5 bash "$SH" guarda --trilha nenhuma)"
confere "guarda: pools R5 lidos do render (Hikari, workers 32, pool real 245, ScaledObjects com max 1, x6: o stock nao tem)" "$(echo "$saida" | grep 'pools R5' | grep -c 'reservation=1000 stock=250 identity=100 catalog=100 backend=10 notification=3.*workers=32.*maxReplicaCount=1 x6')|$(echo "$saida" | grep 'overlay rodada' | grep -c 'pool real=245')" "1|1"
saida="$(rodar env K8S_OVERLAY=rodada bash "$SH" guarda --trilha nenhuma)"
confere "guarda: rodada com o db no cluster mostra o max_connections lido do render" "$(echo "$saida" | grep 'overlay rodada' | grep -c 'max_connections=300')" "1"

# --- 3. kubectl falso: psql (local x remoto), senha fora dos argumentos, --context em todo comando, db-teste
cat > "$T/bin-falso/kubectl" <<'EOF'
#!/usr/bin/env bash
echo "kubectl $(printf '%s ' "$@" | tr '\n' ' ')" >> "$FAKE_LOG"   # uma linha por chamada (o script do pod tem quebras de linha)
case "$*" in
    *"get sts/db"*) [ "${FAKE_DB_ERRO:-0}" = 1 ] && exit 1; [ "${FAKE_DB_REMOTO:-0}" = 1 ] && exit 0; echo "statefulset.apps/db" ;;
    *"get endpointslice db-remoto"*) [ -n "${FAKE_SEM_SLICE:-}" ] && exit 1; echo "192.168.68.50:5433" ;;
    *"exec"*"db-cliente"*"bash -c"*) echo "saida do teste"; exit "${FAKE_TESTE_RC:-0}" ;;
    *"apply -f -"*) cat > /dev/null ;;
esac
exit 0
EOF
chmod +x "$T/bin-falso/kubectl"
export FAKE_LOG="$T/kubectl.log"
SEGREDOS=(APP_DB_PASSWORD=CANARIO-APP POSTGRES_PASSWORD=CANARIO-PG REDIS_PASSWORD=CANARIO-REDIS)

: > "$FAKE_LOG"; rodar env "${SEGREDOS[@]}" bash "$SH" psql -c 'select 1' >/dev/null; rc=$?
confere "psql com o db no cluster: exec no sts/db (o de hoje, agora com -c db), sem pod cliente, e UM get sts/db" "$(grep -c 'exec -i sts/db -c db ' "$FAKE_LOG")$(grep -c 'db-cliente' "$FAKE_LOG")$(grep -c 'get sts/db' "$FAKE_LOG")" "101"
: > "$FAKE_LOG"; rodar env FAKE_DB_REMOTO=1 "${SEGREDOS[@]}" bash "$SH" psql -c 'select 1' >/dev/null
confere "psql com o db remoto: UM get sts/db, pod cliente aplicado e esperado Ready UMA vez, exec -i no pod" "$(grep -c 'get sts/db' "$FAKE_LOG")$(grep -c 'apply -f - ' "$FAKE_LOG")$(grep -c 'wait --for=condition=Ready pod/db-cliente' "$FAKE_LOG")$(grep -c 'exec -i pod/db-cliente' "$FAKE_LOG")" "1111"
: > "$FAKE_LOG"; saida="$(rodar env FAKE_DB_ERRO=1 bash "$SH" psql -c 'select 1')"; rc=$?
confere "psql com erro ao ler o sts/db (nao e 'nao existe'): aborta, sem criar o pod cliente nem cair para remoto" "$([ "$rc" -ne 0 ] && echo sim || echo nao)$(grep -c 'db-cliente\|apply' "$FAKE_LOG")" "sim0"
: > "$FAKE_LOG"; rodar env FAKE_DB_REMOTO=1 "${SEGREDOS[@]}" bash "$SH" psql -c 'select 1' >/dev/null
confere "psql remoto: a senha nunca aparece em argumento (a do ambiente do shell nao vaza)" "$(grep -c 'CANARIO' "$FAKE_LOG")" "0"
confere "psql remoto: kubectl exec com -c db-cliente" "$(grep -c 'exec -i pod/db-cliente -c db-cliente ' "$FAKE_LOG")" "1"
confere "psql remoto: a senha so e lida DENTRO do pod (\$APP_DB_PASSWORD num sh -c de aspas simples)" "$(grep -c 'PGPASSWORD="\$APP_DB_PASSWORD"' "$FAKE_LOG")" "1"
confere "todo kubectl do psql leva --context kind-gerenciador" "$(grep -vc '^kubectl --context kind-gerenciador ' "$FAKE_LOG")" "0"
: > "$FAKE_LOG"; rodar env K8S_NOME=gerenciador-e2e-x FAKE_DB_REMOTO=1 bash "$SH" psql -c 'select 1' >/dev/null
confere "o contexto segue o K8S_NOME (kind-gerenciador-e2e-x)" "$(grep -vc '^kubectl --context kind-gerenciador-e2e-x ' "$FAKE_LOG")" "0"

for rc_teste in 0 10 11 1; do
    : > "$FAKE_LOG"; saida="$(rodar env FAKE_TESTE_RC=$rc_teste "${SEGREDOS[@]}" bash "$SH" db-teste)"; rc=$?
    esperado="$rc_teste"; [ "$rc_teste" = 1 ] && esperado=12
    confere "db-teste: o codigo do pod ($rc_teste) vira o de saida ($esperado)" "$rc" "$esperado"
done
confere "db-teste: lê o IP:porta da EndpointSlice e passa ao pod (192.168.68.50 5433), sem senha, com -c db-cliente" "$(grep -c 'exec pod/db-cliente -c db-cliente -- bash -c.* bash 192.168.68.50 5433' "$FAKE_LOG")$(grep -c 'CANARIO' "$FAKE_LOG")" "10"
confere "db-teste: todo kubectl leva --context" "$(grep -vc '^kubectl --context kind-gerenciador ' "$FAKE_LOG")" "0"
: > "$FAKE_LOG"; saida="$(rodar env FAKE_SEM_SLICE=1 bash "$SH" db-teste)"; rc=$?
confere "db-teste: cluster sem db remoto (sem EndpointSlice) recusa com codigo 12" "$rc" "12"

# --- 4. esperar e configmaps_e_segredos, com um `kubectl` FUNCAO (o script carregado como biblioteca: CLUSTER_LIB=1)
lib_cmd() { # lib_cmd <K8S_DB_REMOTO ou vazio> <funcao a chamar>
    ( cd "$P" && PATH="$T/bin-falso:$PATH" CLUSTER_LIB=1 K8S_DB_REMOTO="$1" bash -c '
        . k8s/cluster.sh
        kubectl() { echo "kubectl $(printf "%s " "$@" | tr "\n" " ")" >> "$FAKE_LOG"; case "$*" in *"get deploy mailpit"*) return 1 ;; esac; return 0; }
        gerar_env() { for c in $COMPONENTES; do : > "$TMP/env-$c"; done; }
        ESPERA=1
        '"$2"' ' >/dev/null 2>&1 )
}
: > "$FAKE_LOG"; lib_cmd "" esperar
confere "esperar, db no cluster: espera o sts/db (como hoje)" "$(grep -c 'rollout status sts/db ' "$FAKE_LOG")" "1"
: > "$FAKE_LOG"; lib_cmd "192.168.68.50:5433" esperar
confere "esperar, db remoto: NAO espera o sts/db; o resto segue" "$(grep -c 'sts/db' "$FAKE_LOG")$(grep -c 'rollout status deploy/supabase-pooler' "$FAKE_LOG")" "01"
: > "$FAKE_LOG"; lib_cmd "" configmaps_e_segredos
confere "configmaps, db no cluster: db-sql e db-app-init criados (como hoje)" "$(grep -c 'create configmap db-sql' "$FAKE_LOG")$(grep -c 'create configmap db-app-init' "$FAKE_LOG")" "11"
: > "$FAKE_LOG"; lib_cmd "192.168.68.50:5433" configmaps_e_segredos
confere "configmaps, db remoto: sem db-sql nem db-app-init; pooler-exs e os Secrets seguem" "$(grep -c 'configmap db-' "$FAKE_LOG")$(grep -c 'create configmap pooler-exs' "$FAKE_LOG")$(grep -c 'create secret generic env-db ' "$FAKE_LOG")" "011"

# #231: o `up` apaga o ScaledObject stock de um cluster de antes (o apply nao poda), sempre com --context e namespace
: > "$FAKE_LOG"; lib_cmd "" podar_stock_antigo
confere "podar_stock_antigo: delete scaledobject stock --ignore-not-found, com --context kind-gerenciador e -n gerenciador" "$(grep -c '^kubectl --context kind-gerenciador -n gerenciador delete scaledobject stock --ignore-not-found ' "$FAKE_LOG")$(grep -vc '^kubectl --context kind-gerenciador ' "$FAKE_LOG")" "10"
confere "cmd_up chama a poda do stock antes do apply do render" "$(awk '/^cmd_up\(\)/ {d=1} d && /podar_stock_antigo$/ {p=NR} d && /render \| kn apply/ {a=NR} END {print (p && a && p < a) ? "sim" : "nao"}' "$P/k8s/cluster.sh")" "sim"

# imagens a carregar no no e o guarda do modo do db no namespace (#228), pelas mesmas funcoes do cluster.sh
lib_run() { # lib_run <K8S_DB_REMOTO> <label db-remoto do namespace> <comandos...>: imprime a saida; o codigo fica em $?
    local dbr="$1" lab="$2"; shift 2
    ( cd "$P" && PATH="$T/bin-falso:$PATH" CLUSTER_LIB=1 K8S_DB_REMOTO="$dbr" FAKE_LABEL_DB="$lab" bash -c '
        . k8s/cluster.sh
        kubectl() { echo "kubectl $(printf "%s " "$@" | tr "\n" " ")" >> "$FAKE_LOG"
            case "$*" in kustomize*) "$KREAL" "$@"; return $? ;; *jsonpath*trilha-balanceamento*) echo nenhuma ;; *jsonpath*db-remoto*) echo "$FAKE_LABEL_DB" ;; esac; return 0; }
        '"$*"' ' 2>&1 )
}
saida="$(lib_run "192.168.68.50:5433" "" 'validar_trilha nenhuma; SLIM_TAG=k8s; montar_selecao; imagens_do_cluster')"
confere "imagens, db remoto: a imagem do db (psql) entra na lista pelo pod cliente, o render nao a tem" "$(echo "$saida" | tr -d '\r' | grep -c '^supabase/postgres:17.6.1.136$')" "1"
saida="$(lib_run "" "" 'validar_trilha nenhuma; SLIM_TAG=k8s; montar_selecao; imagens_do_cluster')"
confere "imagens, db no cluster: a do db vem do render (uma vez)" "$(echo "$saida" | tr -d '\r' | grep -c '^supabase/postgres:17.6.1.136$')" "1"
saida="$(lib_run "192.168.68.50:5433" "" 'TRILHA=nenhuma; guardar_trilha')"; rc=$?
confere "namespace subiu com o db no cluster (sem label) e este up e remoto: aborta (misturaria os modos)" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q "db-remoto='nao'" && echo sim || echo nao)" "sim"
saida="$(lib_run "" "sim" 'TRILHA=nenhuma; guardar_trilha')"; rc=$?
confere "namespace subiu com db remoto e este up e com o db no cluster: aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q "db-remoto='sim'" && echo sim || echo nao)" "sim"
saida="$(lib_run "192.168.68.50:5433" "sim" 'TRILHA=nenhuma; guardar_trilha')"; rc=$?
confere "mesmo modo (remoto, remoto): segue" "$rc" "0"
saida="$(lib_run "" "" 'TRILHA=nenhuma; guardar_trilha')"; rc=$?
confere "mesmo modo (db no cluster, cluster de antes sem o label): segue" "$rc" "0"

[ "$falhas" -eq 0 ] && echo "selftest cluster: OK" || { echo "selftest cluster: $falhas falha(s)"; exit 1; }
