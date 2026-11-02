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
cp "$AQUI/lib-db-cliente.sh" "$AQUI/db-cliente.yaml" "$AQUI/guarda-soak.py" "$P/k8s/" 2>/dev/null
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

# --- 2c2. minimo do admission (#235): default 2 (o 503 da consulta da fila na rampa vinha de 1 pod so), acompanhando o teto quando ele e 1; valor explicito vale, com a validacao de 1 a 3 e MIN <= MAX
min_so() { "$PY" -c '
import re, sys
for d in sys.stdin.read().split("\n---\n"):
    if re.search(r"^kind: ScaledObject$", d, re.M) and re.search(r"^metadata:\n(?:  .*\n)*?  name: " + sys.argv[1] + "$", d, re.M): print(re.search(r"minReplicaCount: (\d+)", d).group(1))
' "$1" | tr -d '\r'; }
mins() { for s in admission backend identity catalog reservation notification; do echo "$1" | min_so $s; done | tr '\n' ','; }
confere "admission (#235): default minReplicaCount 2 no render; os outros 5 seguem em 1" "$(mins "$r_rod")" "2,1,1,1,1,1,"
confere "admission (#235): base/autoescala.yaml com minReplicaCount 2 (o default do cluster.sh e o mesmo)" "$(cat "$P/k8s/base/autoescala.yaml" | min_so admission)" "2"
r_am1="$(rodar env K8S_OVERLAY=rodada K8S_ADMISSION_MAX=1 bash "$SH" renderizar --trilha nenhuma)"
confere "admission (#235): K8S_ADMISSION_MAX=1 sem MIN: o default acompanha o teto (min 1, max 1)" "$(echo "$r_am1" | min_so admission)|$(echo "$r_am1" | grep -c 'maxReplicaCount: 1')" "1|1"
saida="$(rodar env K8S_OVERLAY=rodada K8S_ADMISSION_MAX=1 bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "admission (#235): K8S_ADMISSION_MAX=1 sem MIN: o guarda nao aborta por MIN > MAX" "$rc" "0"
confere "admission (#235): K8S_POOLS=r5 (teto 1 por padrao) sem MIN: min 1" "$(mins "$r_r5")" "1,1,1,1,1,1,"
saida="$(rodar env K8S_OVERLAY=rodada K8S_POOLS=r5 bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "admission (#235): K8S_POOLS=r5 sem nenhuma variavel de MIN/MAX: o guarda aceita" "$rc" "0"
for v in 1 3; do
    confere "admission (#235): K8S_ADMISSION_MIN=$v explicito vale (MAX default 3)" "$(rodar env K8S_OVERLAY=rodada K8S_ADMISSION_MIN=$v bash "$SH" renderizar --trilha nenhuma | min_so admission)" "$v"
done
confere "admission (#235): K8S_ADMISSION_MIN=2 e K8S_ADMISSION_MAX=2 explicitos" "$(rodar env K8S_OVERLAY=rodada K8S_ADMISSION_MIN=2 K8S_ADMISSION_MAX=2 bash "$SH" renderizar --trilha nenhuma | min_so admission)" "2"
for v in "K8S_ADMISSION_MIN=0" "K8S_ADMISSION_MIN=4" "K8S_ADMISSION_MIN=3 K8S_ADMISSION_MAX=2" "K8S_ADMISSION_MIN=2 K8S_ADMISSION_MAX=1" "K8S_POOLS=r5 K8S_ADMISSION_MIN=2"; do
    saida="$(rodar env K8S_OVERLAY=rodada $v bash "$SH" guarda --trilha nenhuma)"; rc=$?
    confere "admission (#235): '$v' recusado (a validacao de 1 a 3 e MIN <= MAX segue valendo)" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'K8S_ADMISSION_MIN' && echo sim || echo nao)" "sim"
done

# --- 2d. as linhas [ok] do guarda leem os valores do render (nao imprimem valores fixos): muda o componente na copia temporaria e o guarda acompanha
sed -i 's/value: "1000"/value: "777"/' "$P/k8s/overlays/pools-r5/kustomization.yaml"
saida="$(rodar env K8S_OVERLAY=rodada K8S_POOLS=r5 bash "$SH" guarda --trilha nenhuma)"
confere "guarda: a linha dos pools R5 mostra o valor LIDO do render (reservation=777 depois de mudar o componente na copia)" "$(echo "$saida" | grep 'pools R5' | grep -c 'reservation=777')" "1"
sed -i 's/value: "777"/value: "1000"/' "$P/k8s/overlays/pools-r5/kustomization.yaml"
saida="$(rodar env K8S_OVERLAY=rodada K8S_POOLS=r5 bash "$SH" guarda --trilha nenhuma)"
confere "guarda: pools R5 lidos do render (Hikari, workers 32, pool real 245, ScaledObjects com max 1, x6: o stock nao tem)" "$(echo "$saida" | grep 'pools R5' | grep -c 'reservation=1000 stock=250 identity=100 catalog=100 backend=10 notification=3.*workers=32.*maxReplicaCount=1 x6')|$(echo "$saida" | grep 'overlay rodada' | grep -c 'pool real=245')" "1|1"
saida="$(rodar env K8S_OVERLAY=rodada bash "$SH" guarda --trilha nenhuma)"
confere "guarda: rodada com o db no cluster mostra o max_connections lido do render" "$(echo "$saida" | grep 'overlay rodada' | grep -c 'max_connections=300')" "1"

# --- 2e. overlay soak (#232): nenhum e-mail real. Mailpit como componente, os seis valores do SMTP do notification fail-closed no render, MAILPIT_AUTH, SPRING_MAIL_*
export MAILPIT_AUTH="soak:CANARIOsenhaSecreta123"
rodar_soak() { rodar env K8S_OVERLAY=soak MAILPIT_AUTH="${MAILPIT_AUTH_TESTE-$MAILPIT_AUTH}" "$@"; }
r_soak="$(rodar_soak bash "$SH" renderizar --trilha nenhuma)"
confere "soak: exatamente 1 Deployment mailpit no render, com MP_UI_AUTH vindo de Secret (nao literal)" "$(echo "$r_soak" | docs_de | grep -c '^Deployment/mailpit$')|$(echo "$r_soak" | grep -c 'name: MP_UI_AUTH')|$(echo "$r_soak" | grep -A3 'name: MP_UI_AUTH' | grep -c 'secretKeyRef')" "1|1|1"
confere "soak: notification com MAIL_PASSWORD de valor VAZIO literal (value: \"\")" "$(echo "$r_soak" | grep -A1 'name: MAIL_PASSWORD' | grep -c 'value: \"\"')" "1"
confere "soak: NodePort 30825 so no Service mailpit-api (a 1025 do SMTP nao sai); 30081/30088 como no rodada" "$(echo "$r_soak" | grep -c 'nodePort: 30825')$(echo "$r_soak" | grep -c 'nodePort: 30081')$(echo "$r_soak" | grep -c 'nodePort: 30088')$(echo "$r_soak" | docs_de | grep -c '^Service/mailpit-api$')" "1111"
confere "soak: rodada continua sem Mailpit e o e2e tem o Mailpit sem auth nem NodePort (componente compartilhado)" "$(echo "$r_rod" | docs_de | grep -c '^Deployment/mailpit$')|$(rodar env K8S_OVERLAY=e2e bash "$SH" renderizar --trilha nenhuma | docs_de | grep -c '^Deployment/mailpit$')|$(rodar env K8S_OVERLAY=e2e bash "$SH" renderizar --trilha nenhuma | grep -c 'MP_UI_AUTH\|nodePort')" "0|1|0"
# gatilho por bloco (decisao C): o alvo do KEDA do reservation sai do ADMISSION_X_RPM do ambiente (a MESMA variavel que o admission recebe): 0,5 x X
confere "soak: X=56000 (S1, o padrao) -> targetValue do KEDA do reservation 28000" "$(echo "$r_soak" | grep -c 'targetValue: "28000"')" "1"
sed 's/"ADMISSION_X_RPM":"56000"/"ADMISSION_X_RPM":"4000"/' "$JSON" > "$T/compose-x4000.json"
r_s2="$(rodar env FAKE_COMPOSE_JSON="$T/compose-x4000.json" K8S_OVERLAY=soak MAILPIT_AUTH="$MAILPIT_AUTH" bash "$SH" renderizar --trilha nenhuma)"
confere "soak: X=4000 (S2) -> targetValue do KEDA do reservation 2000 (e nao 28000)" "$(echo "$r_s2" | grep -c 'targetValue: "2000"')$(echo "$r_s2" | grep -c 'targetValue: "28000"')" "10"
saida="$(rodar env FAKE_COMPOSE_JSON="$T/compose-x4000.json" K8S_OVERLAY=soak MAILPIT_AUTH="$MAILPIT_AUTH" bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "soak: a guarda aceita o gatilho do S2 (X=4000)" "$rc" "0"
saida="$(rodar_soak bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "soak: guarda aceita o render certo (codigo 0) e mostra a linha do soak" "$rc|$(echo "$saida" | grep -c 'soak: notification -> mailpit:1025')" "0|1"
confere "soak: a credencial do Mailpit nunca aparece na saida da guarda (canario)" "$(echo "$saida" | grep -c CANARIO)" "0"
for tr in c b1; do
    saida="$(rodar_soak bash "$SH" guarda --trilha $tr)"; rc=$?
    confere "soak: guarda aceita com a trilha $tr" "$rc" "0"
done
# os SEIS valores, cada um ausente e errado: o guarda sai != 0 com "nada foi criado" (a copia temporaria do overlay e restaurada a cada caso)
cp "$P/k8s/overlays/soak/kustomization.yaml" "$T/soak-kustomization.orig"
errado_de() { case "$1" in MAIL_HOST) echo smtp.gmail.com ;; MAIL_PORT) echo '"587"' ;; MAIL_SMTP_AUTH) echo '"true"' ;; MAIL_STARTTLS) echo '"true"' ;; MAIL_USERNAME) echo conta.real@gmail.com ;; MAIL_PASSWORD) echo '"senha-real"' ;; esac; }
for var in MAIL_HOST MAIL_PORT MAIL_SMTP_AUTH MAIL_STARTTLS MAIL_USERNAME MAIL_PASSWORD; do
    sed -i "/{name: $var,/d" "$P/k8s/overlays/soak/kustomization.yaml"
    saida="$(rodar_soak bash "$SH" guarda --trilha nenhuma)"; rc=$?
    confere "soak: $var AUSENTE aborta (codigo != 0, cita a variavel, 'nada foi criado')" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q "$var" && echo "$saida" | grep -q 'nada foi criado' && echo sim || echo nao)" "sim"
    cp "$T/soak-kustomization.orig" "$P/k8s/overlays/soak/kustomization.yaml"
    sed -i "s|^\( *- {name: $var, value: \).*|\1$(errado_de $var)}|" "$P/k8s/overlays/soak/kustomization.yaml"
    saida="$(rodar_soak bash "$SH" guarda --trilha nenhuma)"; rc=$?
    confere "soak: $var ERRADO aborta (codigo != 0, cita a variavel, 'nada foi criado')" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q "$var" && echo "$saida" | grep -q 'nada foi criado' && echo sim || echo nao)" "sim"
    cp "$T/soak-kustomization.orig" "$P/k8s/overlays/soak/kustomization.yaml"
done
saida="$(rodar_soak bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "soak: overlay restaurado depois dos 12 casos (guarda volta a aceitar)" "$rc" "0"
# MAIL_PASSWORD vindo de valueFrom (Secret) e MAIL_HOST duplicado tambem abortam
sed -i 's|^\( *- {name: MAIL_PASSWORD, value: ""}\)|                  - name: MAIL_PASSWORD\n                    valueFrom: {secretKeyRef: {name: env-notification, key: MAIL_PASSWORD}}|' "$P/k8s/overlays/soak/kustomization.yaml"
saida="$(rodar_soak bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "soak: MAIL_PASSWORD de valueFrom (a senha real do Secret) aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'MAIL_PASSWORD' && echo sim || echo nao)" "sim"
cp "$T/soak-kustomization.orig" "$P/k8s/overlays/soak/kustomization.yaml"
# o Mailpit sem basic auth no render
sed -i '/name: MP_UI_AUTH/,/secretKeyRef/d' "$P/k8s/overlays/soak/kustomization.yaml"
saida="$(rodar_soak bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "soak: Mailpit sem MP_UI_AUTH no render aborta" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'MP_UI_AUTH' && echo sim || echo nao)" "sim"
cp "$T/soak-kustomization.orig" "$P/k8s/overlays/soak/kustomization.yaml"
# Secret env-notification com SPRING_MAIL_*: o relaxed binding venceria o MAIL_HOST
sed 's/"notification":{"environment":{}}/"notification":{"environment":{"SPRING_MAIL_HOST":"smtp.gmail.com"}}/' "$JSON" > "$T/compose-springmail.json"
saida="$(rodar env FAKE_COMPOSE_JSON="$T/compose-springmail.json" K8S_OVERLAY=soak MAILPIT_AUTH="$MAILPIT_AUTH" bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "soak: SPRING_MAIL_* no Secret env-notification aborta ('nada foi criado')" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'SPRING_MAIL' && echo "$saida" | grep -q 'nada foi criado' && echo sim || echo nao)" "sim"
# a mesma familia por outros caminhos: chave spring.mail.* (com ponto) no Secret, variaveis de opcoes da JVM no Secret, e no env do render (SPRING_MAIL_* literal, SPRING_APPLICATION_JSON e JAVA_TOOL_OPTIONS com spring.mail)
for chave in '"spring.mail.host":"smtp.gmail.com"' '"JAVA_TOOL_OPTIONS":"-Dspring.mail.host=smtp.gmail.com"' '"SPRING_APPLICATION_JSON":"{}"'; do
    sed "s/\"notification\":{\"environment\":{}}/\"notification\":{\"environment\":{$chave}}/" "$JSON" > "$T/compose-sm2.json"
    saida="$(rodar env FAKE_COMPOSE_JSON="$T/compose-sm2.json" K8S_OVERLAY=soak MAILPIT_AUTH="$MAILPIT_AUTH" bash "$SH" guarda --trilha nenhuma)"; rc=$?
    confere "soak: chave $(echo "$chave" | cut -d'"' -f2) no Secret env-notification aborta ('nada foi criado')" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q "$(echo "$chave" | cut -d'"' -f2)" && echo "$saida" | grep -q 'nada foi criado' && echo sim || echo nao)" "sim"
done
for linha in '{name: SPRING_MAIL_PORT, value: "25"}' '{name: SPRING_APPLICATION_JSON, value: "{\"spring.mail.host\":\"smtp.gmail.com\"}"}' '{name: JAVA_TOOL_OPTIONS, value: "-Dspring.mail.host=smtp.gmail.com"}' '{name: JDK_JAVA_OPTIONS, value: "-Dspring.mail.host=smtp.gmail.com"}'; do
    cp "$T/soak-kustomization.orig" "$P/k8s/overlays/soak/kustomization.yaml"
    nome="$(echo "$linha" | sed 's/{name: \([A-Z_]*\),.*/\1/')"
    awk -v nova="                  - $linha" '{print} /\{name: MAIL_PORT,/ && !f {print nova; f=1}' "$T/soak-kustomization.orig" > "$P/k8s/overlays/soak/kustomization.yaml"
    saida="$(rodar_soak bash "$SH" guarda --trilha nenhuma)"; rc=$?
    confere "soak: $nome no env do notification (render) aborta ('nada foi criado')" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q "$nome" && echo "$saida" | grep -q 'nada foi criado' && echo sim || echo nao)" "sim"
done
cp "$T/soak-kustomization.orig" "$P/k8s/overlays/soak/kustomization.yaml"
# JAVA_TOOL_OPTIONS legitimo (trilha C: java.security) continua passando
awk -v nova='                  - {name: JAVA_TOOL_OPTIONS, value: "-Djava.security.properties=/etc/java-security/java.security"}' '{print} /\{name: MAIL_PORT,/ && !f {print nova; f=1}' "$T/soak-kustomization.orig" > "$P/k8s/overlays/soak/kustomization.yaml"
saida="$(rodar_soak bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "soak: JAVA_TOOL_OPTIONS sem spring.mail (o da trilha C) passa" "$rc" "0"
cp "$T/soak-kustomization.orig" "$P/k8s/overlays/soak/kustomization.yaml"
# MAILPIT_AUTH ausente, com senha curta ou com espaco; soak no cluster do e2e
for ruim in "" "soak:curta" "soak:com espaco na senha 12345678"; do
    saida="$(rodar env K8S_OVERLAY=soak MAILPIT_AUTH_TESTE="$ruim" MAILPIT_AUTH="$ruim" bash "$SH" guarda --trilha nenhuma)"; rc=$?
    confere "soak: MAILPIT_AUTH invalida (${ruim:+com valor ruim}${ruim:-vazia}) aborta sem imprimir o valor" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'MAILPIT_AUTH' && ! echo "$saida" | grep -q 'senha 12345678' && echo sim || echo nao)" "sim"
done
saida="$(rodar_soak env K8S_NOME=gerenciador-e2e-x bash "$SH" guarda --trilha nenhuma)"; rc=$?
confere "soak: overlay soak (Secrets REAIS) nao vai para o cluster gerenciador-e2e*" "$([ "$rc" -ne 0 ] && echo "$saida" | grep -q 'soak' && echo sim || echo nao)" "sim"

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
        kubectl() { echo "kubectl $(printf "%s " "$@" | tr "\n" " ")" >> "$FAKE_LOG"; case "$*" in *"get deploy mailpit --ignore-not-found"*) return 0 ;; *"get deploy mailpit"*) return 1 ;; esac; return 0; }
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
# soak (#232): o Secret mailpit-auth sai de MAILPIT_AUTH por arquivo temporario; a credencial nunca vai em argumento (o kubectl falso registra os argumentos)
: > "$FAKE_LOG"; K8S_OVERLAY=soak lib_cmd "" configmaps_e_segredos
confere "configmaps, soak: cria o Secret mailpit-auth por --from-env-file, sem a credencial em nenhum argumento (canario)" "$(grep -c 'create secret generic mailpit-auth --from-env-file=' "$FAKE_LOG")$(grep -c 'CANARIO' "$FAKE_LOG")" "10"
: > "$FAKE_LOG"; lib_cmd "" configmaps_e_segredos
confere "configmaps, sem soak: nenhum Secret mailpit-auth" "$(grep -c 'mailpit-auth' "$FAKE_LOG")" "0"

# #231: o `up` apaga o ScaledObject stock de um cluster de antes (o apply nao poda), sempre com --context e namespace
: > "$FAKE_LOG"; lib_cmd "" podar_stock_antigo
confere "podar_stock_antigo: delete scaledobject stock --ignore-not-found, com --context kind-gerenciador e -n gerenciador" "$(grep -c '^kubectl --context kind-gerenciador -n gerenciador delete scaledobject stock --ignore-not-found ' "$FAKE_LOG")$(grep -vc '^kubectl --context kind-gerenciador ' "$FAKE_LOG")" "10"
confere "cmd_up chama a poda do stock antes do apply do render" "$(awk '/^cmd_up\(\)/ {d=1} d && /podar_stock_antigo$/ {p=NR} d && /render \| kn apply/ {a=NR} END {print (p && a && p < a) ? "sim" : "nao"}' "$P/k8s/cluster.sh")" "sim"

# #232: o up com overlay SEM Mailpit apaga o do cluster de antes (o apply nao poda): senao o Mailpit ficaria de pe e, trocando de overlay sem `down`, o notification voltaria ao SMTP real
: > "$FAKE_LOG"; K8S_OVERLAY=rodada lib_cmd "" podar_mailpit_antigo
confere "podar_mailpit_antigo (rodada): delete deploy/mailpit svc/mailpit svc/mailpit-api secret/mailpit-auth --ignore-not-found, com --context e -n" "$(grep -c '^kubectl --context kind-gerenciador -n gerenciador delete deploy/mailpit svc/mailpit svc/mailpit-api secret/mailpit-auth --ignore-not-found ' "$FAKE_LOG")$(grep -vc '^kubectl --context kind-gerenciador ' "$FAKE_LOG")" "10"
for ov in base completa; do
    : > "$FAKE_LOG"; K8S_OVERLAY=$ov lib_cmd "" podar_mailpit_antigo
    confere "podar_mailpit_antigo ($ov): tambem apaga" "$(grep -c 'delete deploy/mailpit' "$FAKE_LOG")" "1"
done
for ov in soak e2e carga; do
    : > "$FAKE_LOG"; K8S_OVERLAY=$ov lib_cmd "" podar_mailpit_antigo
    confere "podar_mailpit_antigo ($ov): overlay COM Mailpit nao apaga nada" "$(grep -c 'delete' "$FAKE_LOG")" "0"
done
confere "cmd_up chama a poda do Mailpit antes do apply do render" "$(awk '/^cmd_up\(\)/ {d=1} d && /podar_mailpit_antigo$/ {p=NR} d && /render \| kn apply/ {a=NR} END {print (p && a && p < a) ? "sim" : "nao"}' "$P/k8s/cluster.sh")" "sim"

# #232, fail-closed: Mailpit ainda de pe (ou o env VIVO do notification ainda em MAIL_HOST=mailpit) + backlog no group notification (bloco soak abortado) -> sem `down`, o up com overlay sem Mailpit
# tiraria o patch de SMTP e o notification consumiria o backlog soak-*@example.com pelo Gmail. Backlog > 0 ou lag ilegivel ABORTA (checar_poda_mailpit, antes de qualquer efeito); lag 0 segue.
# A checagem nunca apaga nada (o delete e do podar_mailpit_antigo, testado acima).
poda_com_lag() { # poda_com_lag <saida do rpk | FALHA> <mailpit: sim|nao|ERRO> <MAIL_HOST vivo do notification: texto|ERRO>: imprime "<codigo>|<deletes>|<leituras do lag>"
    : > "$FAKE_LOG"
    ( cd "$P" && PATH="$T/bin-falso:$PATH" CLUSTER_LIB=1 K8S_OVERLAY=rodada FAKE_RPK="$1" FAKE_MAILPIT="$2" FAKE_MAILHOST="$3" bash -c '
        . k8s/cluster.sh
        kubectl() { echo "kubectl $(printf "%s " "$@" | tr "
" " ")" >> "$FAKE_LOG"
            case "$*" in
                *"get deploy mailpit --ignore-not-found"*) [ "$FAKE_MAILPIT" = ERRO ] && return 1; [ "$FAKE_MAILPIT" = sim ] && echo deployment.apps/mailpit; return 0 ;;
                *"get deploy notification --ignore-not-found"*"MAIL_HOST"*) [ "$FAKE_MAILHOST" = ERRO ] && return 1; printf "%s" "$FAKE_MAILHOST"; return 0 ;;
                *"rpk group describe notification"*) [ "$FAKE_RPK" = FALHA ] && return 1; printf "%b" "$FAKE_RPK"; return 0 ;;
            esac; return 0; }
        checar_poda_mailpit' >/dev/null 2>&1 ); echo "$?|$(grep -c ' delete ' "$FAKE_LOG")|$(grep -c 'rpk group describe notification' "$FAKE_LOG")"
}
LAG7='GROUP notification
STATE Stable
TOTAL-LAG 7
'
LAG0='GROUP notification
STATE Stable
TOTAL-LAG 0
'
confere "checagem: Mailpit de pe e lag 7 -> aborta (1)" "$(poda_com_lag "$LAG7" sim smtp.gmail.com)" "1|0|1"
confere "checagem: Mailpit de pe e lag ilegivel (kubectl/rpk falhou) -> aborta (1)" "$(poda_com_lag FALHA sim smtp.gmail.com)" "1|0|1"
confere "checagem: Mailpit de pe e group Dead -> aborta (1)" "$(poda_com_lag 'STATE Dead
TOTAL-LAG 0
' sim smtp.gmail.com)" "1|0|1"
confere "checagem: Mailpit de pe e saida sem TOTAL-LAG -> aborta (1)" "$(poda_com_lag 'nada
' sim smtp.gmail.com)" "1|0|1"
confere "checagem: Mailpit de pe e lag 0 -> segue (0)" "$(poda_com_lag "$LAG0" sim smtp.gmail.com)" "0|0|1"
confere "checagem: sem Mailpit e notification em smtp.gmail.com nem le o lag (0)" "$(poda_com_lag "$LAG7" nao smtp.gmail.com)" "0|0|0"
confere "checagem: sem Mailpit e sem o env MAIL_HOST no notification (default Gmail) nem le o lag (0)" "$(poda_com_lag "$LAG7" nao '')" "0|0|0"
# o env VIVO do notification ainda em mailpit decide, mesmo sem o Mailpit (apagado a mao)
confere "checagem: SEM Mailpit mas notification com MAIL_HOST=mailpit e lag 7 -> aborta (1)" "$(poda_com_lag "$LAG7" nao mailpit)" "1|0|1"
confere "checagem: SEM Mailpit mas notification com MAIL_HOST=mailpit e lag ilegivel -> aborta (1)" "$(poda_com_lag FALHA nao mailpit)" "1|0|1"
confere "checagem: SEM Mailpit mas notification com MAIL_HOST=mailpit e lag 0 -> segue (0)" "$(poda_com_lag "$LAG0" nao mailpit)" "0|0|1"
confere "checagem: MAIL_HOST=mailpit entre varios containers (valores separados por espaco) tambem conta" "$(poda_com_lag "$LAG7" nao 'smtp.gmail.com mailpit')" "1|0|1"
confere "checagem: MAIL_HOST so parecido (mailpit2) nao conta" "$(poda_com_lag "$LAG7" nao mailpit2)" "0|0|0"
confere "checagem: erro de API ao consultar se o Mailpit existe aborta (1) e nao le o lag (fail-closed)" "$(poda_com_lag "$LAG0" ERRO smtp.gmail.com)" "1|0|0"
confere "checagem: erro de API ao ler o env vivo do notification aborta (1) e nao le o lag (fail-closed)" "$(poda_com_lag "$LAG0" nao ERRO)" "1|0|0"
for ov in e2e carga soak; do
    : > "$FAKE_LOG"; ( cd "$P" && PATH="$T/bin-falso:$PATH" CLUSTER_LIB=1 K8S_OVERLAY=$ov bash -c '. k8s/cluster.sh; kubectl() { echo "kubectl $*" >> "$FAKE_LOG"; return 1; }; checar_poda_mailpit' >/dev/null 2>&1 ); rc=$?
    confere "checagem ($ov): overlay COM Mailpit nem consulta o cluster (0)" "$rc|$(wc -l < "$FAKE_LOG" | tr -d ' ')" "0|0"
done
# ordem no cmd_up: a checagem vem logo depois do criar_cluster e ANTES de tudo que cria algo (guardar_trilha, imagens, helm, configmaps, Secrets, label, poda, apply); o delete continua depois dos Secrets
confere "cmd_up: criar_cluster < checar_poda_mailpit < (guardar_trilha, instalar_autoescala, configmaps_e_segredos, autoescala_segredos, label istio, podar_mailpit_antigo, apply)" "$(awk '
    /^cmd_up\(\)/ {d=1} d && /^    criar_cluster$/ {c=NR} d && /^    checar_poda_mailpit/ {k=NR} d && /^    guardar_trilha$/ {g=NR} d && /^    instalar_autoescala/ {h=NR} d && /^    configmaps_e_segredos$/ {cs=NR}
    d && /^    autoescala_segredos/ {as=NR} d && /istio-injection=enabled/ {l=NR} d && /^    podar_mailpit_antigo$/ {p=NR} d && /render \| kn apply/ {a=NR}
    END {print (c && k && c < k && k < g && k < h && k < cs && k < as && k < l && k < p && k < a) ? "sim" : "nao"}' "$P/k8s/cluster.sh")" "sim"
confere "cmd_up: o delete (podar_mailpit_antigo) continua depois dos Secrets e antes do apply" "$(awk '/^cmd_up\(\)/ {d=1} d && /^    autoescala_segredos/ {as=NR} d && /^    podar_mailpit_antigo$/ {p=NR} d && /render \| kn apply/ {a=NR} END {print (as && p && a && as < p && p < a) ? "sim" : "nao"}' "$P/k8s/cluster.sh")" "sim"

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
