#!/usr/bin/env bash
# Selftest do scaler.sh (sem Docker): a decisão da regra 50/80 (funções puras), o rastreio das durações e os parsers da Runtime API.
# Rode: Projeto/autoescala/scaler.selftest.sh   (sai com 1 se algo falhar)
. "$(dirname "$0")/scaler.sh"
falhas=0
confere() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FALHA $1: esperado '$3', veio '$2'"; falhas=$((falhas+1)); fi; }

# defaults da ADR: sobe > 500, promove >= 800, descarta < 300, desce < 500; 60 s; mín. 1 / máx. 3
# decidir_acao <u> <A> <S> <B> <min> <max> <seg desce> <seg descarta> <cooldown ok>
confere "uso: 28k RPM de X=56k com 1 ativa" "$(uso_milli 28000 56000 1)" 500
confere "uso: o mesmo RPM com 2 ativas" "$(uso_milli 28000 56000 2)" 250
confere "uso: sem réplica ativa = sem capacidade" "$(uso_milli 100 56000 0)" 999999

confere "abaixo de 0,50: nada" "$(decidir_acao 500 1 0 0 1 3 0 0 1)" nada
confere "acima de 0,50 sem stand-by: sobe" "$(decidir_acao 501 1 0 0 1 3 0 0 1)" subir
confere "acima de 0,50 com stand-by já pronta: não sobe outra" "$(decidir_acao 600 1 1 0 1 3 0 0 1)" nada
confere "acima de 0,50 com réplica ainda subindo: não sobe outra" "$(decidir_acao 600 1 0 1 1 3 0 0 1)" nada
confere "no máximo (A+S+B = máx.): não sobe" "$(decidir_acao 700 2 1 0 1 3 0 0 1)" nada
confere "máx. = 1: nunca sobe" "$(decidir_acao 900 1 0 0 1 1 0 0 1)" nada
confere "u >= 0,80 sem stand-by: só sobe (não há o que promover)" "$(decidir_acao 800 1 0 0 1 3 0 0 1)" subir
confere "u >= 0,80 com stand-by: promove" "$(decidir_acao 800 1 1 0 1 3 0 0 1)" promover
confere "u = 0,799 com stand-by: não promove" "$(decidir_acao 799 1 1 0 1 3 0 0 1)" nada
confere "promover ignora o cooldown" "$(decidir_acao 900 1 1 0 1 3 0 0 0)" promover
confere "cooldown segura o subir" "$(decidir_acao 600 1 0 0 1 3 0 0 0)" nada
confere "cooldown segura o descer" "$(decidir_acao 100 2 0 0 1 3 99 0 0)" nada

# histerese: depois de promover (A 1->2, S 1->0) o mesmo RPM dá u = 0,425 (sobe? não) e RPM/(X x (A-1)) = 0,85 (desce? não)
rpm=45000; x=56000
u1="$(uso_milli $rpm $x 1)"; confere "antes de promover u = 0,803" "$u1" 803
confere "antes de promover: promove" "$(decidir_acao "$u1" 1 1 0 1 3 0 0 1)" promover
u2="$(uso_milli $rpm $x 2)"; um2="$(uso_milli $rpm $x 1)"
confere "depois de promover u = 0,401" "$u2" 401
confere "depois de promover: não sobe de novo" "$(decidir_acao "$u2" 2 0 0 1 3 0 0 1)" nada
confere "depois de promover: a condição de descer (A-1) não vale" "$(condicoes "$u2" "$um2" 1 0)" "0 0"
confere "depois de promover, mesmo com 99 s 'acumulados': não desce" "$(decidir_acao "$u2" 2 0 0 1 3 0 0 1)" nada
confere "descer: RPM/(X x (A-1)) < 0,50 e fila vazia" "$(condicoes 200 $(uso_milli 28000 $x 1) 1 0)" "0 0"
confere "descer: A-1 abaixo de 0,50" "$(condicoes 200 499 1 0)" "1 0"
confere "descer: fila não vazia impede" "$(condicoes 200 499 0 0)" "0 0"
confere "descartar: u < 0,30 com stand-by" "$(condicoes 299 400 1 1)" "1 1"
confere "descartar: u = 0,30 não" "$(condicoes 300 400 1 1)" "1 0"
confere "descartar: sem stand-by não há o que descartar" "$(condicoes 100 400 1 0)" "1 0"

confere "desce só depois de 60 s" "$(decidir_acao 200 2 0 0 1 3 59 0 1)" nada
confere "desce com 60 s" "$(decidir_acao 200 2 0 0 1 3 60 0 1)" descer
confere "desce, mas nunca abaixo do mínimo" "$(decidir_acao 200 2 0 0 2 3 60 0 1)" nada
confere "nunca derruba a última ativa (mín. 1, A = 1)" "$(decidir_acao 0 1 0 0 1 3 600 0 1)" nada
confere "mín. 0 configurado por engano: ainda assim não derruba a última" "$(decidir_acao 0 1 0 0 0 3 600 0 1)" nada
confere "com stand-by, descarta antes de descer" "$(decidir_acao 200 2 1 0 1 3 60 60 1)" descartar
confere "descartar espera 60 s" "$(decidir_acao 200 1 1 0 1 3 60 59 1)" nada
confere "com stand-by e sem a condição de descartar, não desce" "$(decidir_acao 400 2 1 0 1 3 60 0 1)" nada
confere "total abaixo do mínimo: repõe (mesmo em cooldown)" "$(decidir_acao 0 1 0 0 2 3 0 0 0)" subir_min
confere "nada rodando: repõe o mínimo" "$(decidir_acao 999999 0 0 0 1 3 0 0 1)" subir_min
confere "réplica subindo conta no total do mínimo" "$(decidir_acao 0 1 0 1 2 3 0 0 1)" nada

# rastreio das durações (relógio falso)
rastrear reservation 1 0 1000; confere "rastreio: começa em 0 s" "$SEG_DESCE $SEG_DESCARTA" "0 0"
rastrear reservation 1 1 1030; confere "rastreio: 30 s descer, descartar começa" "$SEG_DESCE $SEG_DESCARTA" "30 0"
rastrear reservation 1 1 1060; confere "rastreio: 60 s descer, 30 s descartar" "$SEG_DESCE $SEG_DESCARTA" "60 30"
rastrear reservation 0 1 1062; confere "rastreio: a condição quebrou, zera o descer" "$SEG_DESCE $SEG_DESCARTA" "0 32"
rastrear reservation 1 1 1064; confere "rastreio: recomeça do zero" "$SEG_DESCE $SEG_DESCARTA" "0 34"
rastrear backend 1 0 1064; confere "rastreio: serviços independentes" "$SEG_DESCE" "0"

# o cenário inteiro, passo a passo, sem oscilar: sobe, promove, a carga cai, descarta/desce na ordem, nunca abaixo do mínimo
a=1 s=0 b=0; t=0; ultima=-100; x=56000
passo() { # <rpm> -> ação; aplica o efeito no estado simulado
    local rpm="$1" u um c ok=1
    u="$(uso_milli "$rpm" $x $a)"; um="$(uso_milli "$rpm" $x $((a-1)))"
    c="$(condicoes "$u" "$um" 1 $s)"; rastrear sim "${c% *}" "${c#* }" "$t"
    (( t - ultima < 30 )) && ok=0
    acao="$(decidir_acao "$u" $a $s $b 1 3 "$SEG_DESCE" "$SEG_DESCARTA" $ok)"
    case "$acao" in
        subir) s=1; ultima=$t ;;
        promover) a=$((a+1)); s=$((s-1)); ultima=$t ;;
        descartar) s=0; ultima=$t; DESDE_DESCE[sim]=""; DESDE_DESCARTA[sim]="" ;;
        descer) a=$((a-1)); ultima=$t; DESDE_DESCE[sim]=""; DESDE_DESCARTA[sim]="" ;;
    esac
    t=$((t+2))
}
passo 10000; confere "cenário: ocioso, 1 ativa" "$a $s $acao" "1 0 nada"
passo 30000; confere "cenário: 0,54 sobe stand-by" "$a $s $acao" "1 1 subir"
passo 46000; confere "cenário: 0,82 promove (cooldown não segura)" "$a $s $acao" "2 0 promover"
for i in $(seq 1 20); do passo 46000; done; confere "cenário: carga mantida em 46k: 20 laços sem oscilar (A=2, S=0)" "$a $s" "2 0"
for i in $(seq 1 20); do passo 20000; done; confere "cenário: 20k com 2 ativas (u=0,18, A-1: 0,36) mas só 40 s: ainda 2 ativas" "$a $s" "2 0"
for i in $(seq 1 12); do passo 20000; done; confere "cenário: depois de 60 s desce para 1 ativa" "$a $s" "1 0"
for i in $(seq 1 100); do passo 0; done; confere "cenário: ociosos por muito tempo: fica 1 ativa (mínimo)" "$a $s" "1 0"

# --- leitura do Redis (volta 3): saída inválida NUNCA vira "carga 0 e fila vazia"
confere "leitura: 'hot fila' numéricos" "$(valida_leitura '1800 0')" "1800 0"
confere "leitura: ocioso (0 0) é válido" "$(valida_leitura '0 0')" "0 0"
valida_leitura '' >/dev/null; confere "leitura: vazia é inválida" "$?" 1
valida_leitura 'NOAUTH Authentication required. 0' >/dev/null; confere "leitura: texto de erro é inválido" "$?" 1
valida_leitura '12' >/dev/null; confere "leitura: um campo só é inválido" "$?" 1
valida_leitura '1 2 3' >/dev/null; confere "leitura: três campos são inválidos" "$?" 1
# o descarte do rastreio quando o laço fica cego
rastrear cego 1 1 2000; rastrear cego 1 1 2050
confere "rastreio antes da cegueira" "$SEG_DESCE $SEG_DESCARTA" "50 50"
zera_rastreio cego; rastrear cego 1 1 2052
confere "depois de zera_rastreio recomeça do zero (período cego não conta)" "$SEG_DESCE $SEG_DESCARTA" "0 0"

# --- ler_estado com docker/lb falhando: não age, não inventa E_N=0 (que viraria subir_min -> --scale 1 sem drain)
LB=lb12 PROJETO=p
SHOW_STAT='# pxname,svname,scur,status,weight,req_tot,
entrada,FRONTEND,3,OPEN,,100,
gateway,gw1,0,UP,100,,
reservation,rs1,0,UP,100,,
reservation,rs2,0,UP,0,,'
SHOW_SRV='1
# be_id be_name srv_id srv_name srv_addr srv_op_state srv_admin_state srv_uweight srv_iweight
3 gateway 1 gw1 10.0.0.1 2 0 100 100
4 reservation 1 rs1 10.0.0.2 2 0 100 100
4 reservation 2 rs2 10.0.0.3 2 0 0 100'
LB_FALHA=0; PS_FALHA=0; INSPECT_FALHA=0; LB_VAZIO=0
lb_cmd() { [ "$LB_FALHA" = 1 ] && return 1; [ "$LB_VAZIO" = 1 ] && return 0; echo "$SHOW_STAT"; echo "$SHOW_SRV"; }
docker() {
    case "$1" in
        ps) [ "$PS_FALHA" = 1 ] && return 1
            case "$*" in *service=backend*) echo aaaaaaaaaaaa;; *service=reservation*) printf 'bbbbbbbbbbbb
cccccccccccc
';; esac ;;
        inspect) [ "$INSPECT_FALHA" = 1 ] && return 1
            echo "aaaaaaaaaaaa0000 backend 1 10.0.0.1"; echo "bbbbbbbbbbbb0000 reservation 1 10.0.0.2"; echo "cccccccccccc0000 reservation 2 10.0.0.3" ;;
    esac
}
E_A[reservation]=7 E_S[reservation]=7 E_B[reservation]=7 E_N[reservation]=7
ler_estado; confere "ler_estado ok: retorno" "$?" 0
confere "ler_estado ok: reservation A S B N" "${E_A[reservation]} ${E_S[reservation]} ${E_B[reservation]} ${E_N[reservation]}" "1 1 0 2"
confere "ler_estado ok: backend A S B N" "${E_A[backend]} ${E_S[backend]} ${E_B[backend]} ${E_N[backend]}" "1 0 0 1"
E_N[reservation]=2
for falha in LB_FALHA PS_FALHA INSPECT_FALHA LB_VAZIO; do
    LB_FALHA=0 PS_FALHA=0 INSPECT_FALHA=0 LB_VAZIO=0; eval "$falha=1"
    ler_estado; confere "ler_estado com $falha: devolve 1 (o laço não age)" "$?" 1
    confere "ler_estado com $falha: não mexe nas contagens" "${E_N[reservation]}" "2"
done
LB_FALHA=0 PS_FALHA=0 INSPECT_FALHA=0 LB_VAZIO=0
unset -f docker lb_cmd

# --- ciclo inteiro com o Redis ilegível: com 2 ativas e carga "zero" o scaler DESCERIA; sem leitura, não pode nem tentar
SHOW_SRV2='1
# be_id be_name srv_id srv_name srv_addr srv_op_state srv_admin_state srv_uweight srv_iweight
3 gateway 1 gw1 10.0.0.1 2 0 100 100
4 reservation 1 rs1 10.0.0.2 2 0 100 100
4 reservation 2 rs2 10.0.0.3 2 0 100 100'
lb_cmd() { echo "$SHOW_STAT"; echo "$SHOW_SRV2"; }
docker() {
    case "$1" in
        ps) case "$*" in *service=backend*) echo aaaaaaaaaaaa;; *service=reservation*) printf 'bbbbbbbbbbbb
cccccccccccc
';; esac ;;
        inspect) echo "aaaaaaaaaaaa0000 backend 1 10.0.0.1"; echo "bbbbbbbbbbbb0000 reservation 1 10.0.0.2"; echo "cccccccccccc0000 reservation 2 10.0.0.3" ;;
    esac
}
CHAMADAS=""; CSV_TESTE=""
acao_subir() { CHAMADAS="$CHAMADAS subir:$1"; }
acao_remover() { CHAMADAS="$CHAMADAS remover:$1"; }
acao_promover() { CHAMADAS="$CHAMADAS promover:$1"; }
grava_chave() { :; }
linha_csv() { CSV_TESTE="$CSV_TESTE $1:$5"; }
aplica_pendentes() { :; }
SECO=1; COOLDOWN_SEG=0; DESCE_SEG=0; DESCARTA_SEG=0
redis_ciclo() { return 1; }
DESDE_DESCE[reservation]=1; DESDE_DESCARTA[reservation]=1
ciclo 2>/dev/null
confere "ciclo com Redis falhando: o reservation não chama nenhuma ação" "${CHAMADAS//remover:backend/}" ""
confere "ciclo com Redis falhando: CSV marca sem_leitura" "$CSV_TESTE" " reservation:sem_leitura backend:nada"
confere "ciclo com Redis falhando: zerou o rastreio do reservation" "[${DESDE_DESCE[reservation]}][${DESDE_DESCARTA[reservation]}]" "[][]"
redis_ciclo() { echo "NOAUTH Authentication required. 0"; }
CHAMADAS=""; CSV_TESTE=""
ciclo 2>/dev/null
confere "ciclo com resposta de erro do Redis (texto): também sem leitura" "$CSV_TESTE" " reservation:sem_leitura backend:nada"
redis_ciclo() { echo "0 0"; }
CHAMADAS=""; CSV_TESTE=""
ciclo 2>/dev/null
confere "ciclo com leitura válida (ocioso, 2 ativas): agora o reservation decide descer" "$CSV_TESTE" " reservation:descer backend:nada"
# lb mudo: o laço inteiro não age e zera o rastreio
lb_cmd() { return 1; }
CHAMADAS=""; CSV_TESTE=""; DESDE_DESCE[reservation]=5
ciclo 2>/dev/null; confere "ciclo com lb mudo: devolve 1, sem ação e sem CSV" "$? [$CHAMADAS][$CSV_TESTE][${DESDE_DESCE[reservation]}]" "1 [][][]"
unset -f docker lb_cmd acao_subir acao_remover acao_promover grava_chave linha_csv aplica_pendentes redis_ciclo

# parsers da Runtime API
# cabeçalho reduzido: o parser acha a coluna pelo NOME (o dry-run confere com o `show stat` real)
stat='# pxname,svname,qcur,scur,status,weight,req_tot,
entrada,FRONTEND,,3,OPEN,,12345,
gateway,gw1,0,2,UP,100,,
gateway,gw2,0,0,UP,0,,'
confere "stat: req_tot da entrada" "$(stat_campo entrada FRONTEND req_tot <<<"$stat")" 12345
confere "stat: scur do gw1" "$(stat_campo gateway gw1 scur <<<"$stat")" 2
confere "stat: peso do gw2 (stand-by)" "$(stat_campo gateway gw2 weight <<<"$stat")" 0
confere "stat: servidor inexistente" "$(stat_campo gateway gw9 scur <<<"$stat")" ""
estados='1
# be_id be_name srv_id srv_name srv_addr srv_op_state srv_admin_state srv_uweight srv_iweight srv_time_since_last_change srv_check_status srv_check_result srv_check_health srv_check_state srv_agent_state bk_f_forced_id srv_f_forced_id srv_fqdn srv_port srvrecord
3 gateway 1 gw1 172.18.0.9 2 0 100 100 1000 6 3 4 6 0 0 0 backend 8081 -
3 gateway 2 gw2 172.18.0.10 2 0 0 100 900 6 3 4 6 0 0 0 backend 8081 -
3 gateway 3 gw3 - 0 0 100 100 5 0 0 0 0 0 0 0 backend 8081 -
4 reservation 1 rs1 172.18.0.11 2 0 100 100 1000 6 3 4 6 0 0 0 reservation 8082 -'
confere "state: slots do gateway (sem endereço fora)" "$(slots_do_backend gateway <<<"$estados" | tr '\n' ';')" "gw1 172.18.0.9 2 0 100;gw2 172.18.0.10 2 0 0;"
confere "state: slots do reservation" "$(slots_do_backend reservation <<<"$estados" | tr '\n' ';')" "rs1 172.18.0.11 2 0 100;"

[ "$falhas" -eq 0 ] && echo "selftest scaler: OK" || { echo "selftest scaler: $falhas falha(s)"; exit 1; }
