#!/usr/bin/env bash
# Selftest do coleta-host.sh (sem Docker): parse do rpk, resumo do CSV e (issue #178) fila do RabbitMQ, Redis por evento, replicas e o laco inteiro
# com um `docker` falso no PATH.
set -u
COLETA_LIB=1 . "$(dirname "$0")/coleta-host.sh"
falhas=0
confere() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FALHA $1: esperado '$3', veio '$2'"; falhas=$((falhas+1)); fi; }
confere "rpk TOTAL-LAG" "$(printf 'GROUP        reservation\nSTATE        Stable\nTOTAL-LAG    42\n\nTOPIC PARTITION LAG\nreservation.wallet 0 42\n' | lag_do_rpk)" "42"
confere "rpk sem grupo" "$(printf 'erro: group not found\n' | lag_do_rpk)" "-1"
csv="$(mktemp)"
{ echo "epoch_ms,lag_projecao_ms,lag_reservation"; for i in $(seq 1 20); do echo "$i,$((i*10)),-1"; done; } > "$csv"
r="$(resumo_csv "$csv")"; rm -f "$csv"
confere "resumo max/p95" "$(echo "$r" | grep lag_projecao_ms | awk '{print $3, $5}')" "200 190"
confere "resumo sem amostra" "$(echo "$r" | grep -c 'lag_reservation .*sem amostra')" "1"

# --- Rodada 4 (#178): fila, Redis por evento, replicas
confere "rabbit: admission.requests + dead-letter" "$(printf 'admission.requests\t12\t3\t32\nadmission.timed_out\t0\t2\t1\n' | rabbit_do_list_queues)" "12 3 32 2"
confere "rabbit: separado por espaco (saida do alpine) e fila ausente = -1" "$(printf 'outra 1 1 1\nadmission.requests 0 0 0\n' | rabbit_do_list_queues)" "0 0 0 -1"
confere "rabbit: container fora (entrada vazia) = tudo -1" "$(printf '' | rabbit_do_list_queues)" "-1 -1 -1 -1"
confere "rabbit: erro do CLI (nao numerico) nao vira numero" "$(printf 'Error: unable to perform an operation on node\nadmission.requests x y z\n' | rabbit_do_list_queues)" "-1 -1 -1 -1"
saida_redis="$(printf 'H 41000 2 1500\nS\nC adm:cnt:7:100 30\nC adm:cnt:7:101 12\nC adm:cnt:9:100 5\nC adm:cnt:9:101 \nM adm:mode:7 4\nM adm:mode:12 0\n')"
r="$(printf '%s\n' "$saida_redis" | redis_resumo 200)"
confere "redis: agregado (hot replicas dbsize eventos_em_fila espera_total)" "$(echo "$r" | head -1)" "41000 2 1500 2 4"
confere "redis: por evento (soma dos buckets vivos, rpm_aprox = soma x 60000 / janela, modo, espera)" "$(echo "$r" | tail -n +2 | sort -t, -k1,1n | tr '\n' ' ')" "7,42,12600,1,4 9,5,1500,0,0 12,0,0,1,0 "
confere "redis: amostra sem SCAN: -1 em fila/espera e nenhum evento" "$(printf 'H 0 0 20\n' | redis_resumo 200 | tr '\n' '|')" "0 0 20 -1 -1|"
confere "redis: SCAN sem nenhuma chave = 0 eventos em fila (nao -1)" "$(printf 'H 5 1 20\nS\n' | redis_resumo 200 | tr '\n' '|')" "5 1 20 0 0|"
confere "redis: container fora ou NOAUTH = tudo -1" "$(printf 'NOAUTH Authentication required.\n' | redis_resumo 200)" "-1 -1 -1 -1 -1"
confere "redis: H com erro no meio (NOAUTH) nao vira numero" "$(printf 'H NOAUTH Authentication required. 3\n' | redis_resumo 200)" "-1 -1 -1 -1 -1"
SERVICOS_REPL="backend reservation identity catalog admission notification"
confere "replicas: conta so 'Up' por servico, na ordem" "$(printf 'reservation Up 2 minutes (healthy)\nreservation Up 1 minute\nbackend Up 2 minutes\nlb Up 2 minutes\nadmission Exited (0) 1 minute ago\n' | contar_replicas)" "1,2,0,0,0,0"
confere "replicas: project sem container (docker falhou) = -1, nao 0" "$(printf '' | contar_replicas)" "-1,-1,-1,-1,-1,-1"
confere "env publico: so AUTOESCALA_* e ADMISSION_*" "$(AUTOESCALA_GATEWAY_RPS=500 ADMISSION_X_RPM=56000 REDIS_PASSWORD=nao env_publico | tr '\n' ' ')" "ADMISSION_X_RPM=56000 AUTOESCALA_GATEWAY_RPS=500 "
confere "cabecalho: colunas novas SO no fim (a #38 nao muda de posicao)" "$(echo "$CABECALHO" | cut -d, -f1-13)" "epoch_ms,lag_projecao_ms,lag_reservation,lag_reservation_catalog,lag_notification,outbox_reservation,outbox_catalog,outbox_identity,pg_conexoes,pg_ativas,pg_idle,pg_idle_tx,pg_espera_lock"
# Rodada 5 (#204): as colunas da R4 NAO mudam (nem de posicao nem de nome): os 31 primeiros campos sao o cabecalho da R4, literal; as da saga vem DEPOIS
confere "cabecalho: os 31 primeiros campos = o cabecalho da R4 (literal)" "$(echo "$CABECALHO" | cut -d, -f1-31)" "epoch_ms,lag_projecao_ms,lag_reservation,lag_reservation_catalog,lag_notification,outbox_reservation,outbox_catalog,outbox_identity,pg_conexoes,pg_ativas,pg_idle,pg_idle_tx,pg_espera_lock,walletevent_ins,rabbit_requests,rabbit_requests_unacked,rabbit_consumidores,rabbit_timed_out,walletevent_ins_delta,redis_hot,redis_replicas,redis_dbsize,redis_eventos_em_fila,redis_espera_total,repl_backend,repl_reservation,repl_identity,repl_catalog,repl_admission,repl_notification,duracao_amostra_ms"
confere "cabecalho: 5 colunas novas da saga no fim" "$(echo "$CABECALHO" | cut -d, -f32-)" "lag_stock,lag_stock_catalog,saga_reservando,saga_orfas,saga_compensando"
confere "cabecalho: 36 colunas" "$(echo "$CABECALHO" | awk -F, '{print NF}')" "36"

# --- Rodada 5 (#204): lag dos groups do stock num unico `rpk group describe stock stock-catalog` (um bloco GROUP por group)
dois_groups='GROUP        stock
COORDINATOR  0
STATE        Stable
TOTAL-LAG    7

TOPIC               PARTITION  CURRENT-OFFSET  LOG-START-OFFSET  LOG-END-OFFSET  LAG  MEMBER-ID
reservation.wallet  0          10              0                 17              7    m-1

GROUP        stock-catalog
COORDINATOR  0
STATE        Stable
TOTAL-LAG    0
'
confere "rpk multi-group: um lag por group, na ordem pedida" "$(printf '%s\n' "$dois_groups" | lags_do_rpk_multi stock stock-catalog)" "7 0"
confere "rpk multi-group: ordem pedida manda (nao a da saida)" "$(printf '%s\n' "$dois_groups" | lags_do_rpk_multi stock-catalog stock)" "0 7"
confere "rpk multi-group: group ausente = -1" "$(printf 'GROUP        stock\nSTATE        Stable\nTOTAL-LAG    3\n' | lags_do_rpk_multi stock stock-catalog)" "3 -1"
confere "rpk multi-group: group Dead (nunca existiu) = -1" "$(printf 'GROUP        stock\nSTATE        Dead\nTOTAL-LAG    0\n\nGROUP        stock-catalog\nSTATE        Stable\nTOTAL-LAG    2\n' | lags_do_rpk_multi stock stock-catalog)" "-1 2"
confere "rpk multi-group: erro / container fora = -1 -1" "$(printf 'Error: no such container\n' | lags_do_rpk_multi stock stock-catalog)" "-1 -1"

# --- Rodada 5 (#204): percentis da latencia dos passos (logger saga.timing) e formatacao dos percentis do banco
tim='2026-10-29 12:00:01 DEBUG saga.timing - tx1_ms=10 stock_ms=20 tx2_ms=5 total_ms=40
2026-10-29 12:00:02 INFO  outra linha qualquer
2026-10-29 12:00:02 DEBUG saga.timing - tx1_ms=30 stock_ms=40 tx2_ms=7 total_ms=80
2026-10-29 12:00:03 DEBUG saga.timing - tx1_ms=20 stock_ms=30 tx2_ms=6 total_ms=60'
confere "saga.timing: n/p50/p95/max de cada passo (a linha que nao e do logger e ignorada)" "$(printf '%s\n' "$tim" | percentis_timing | tr '\n' '|')" \
    "tx1_ms: n 3 p50 20 p95 30 max 30 (ms)|stock_ms: n 3 p50 30 p95 40 max 40 (ms)|tx2_ms: n 3 p50 6 p95 7 max 7 (ms)|total_ms: n 3 p50 60 p95 80 max 80 (ms)|"
confere "saga.timing: sem nenhuma linha = saida vazia (o logger esta desligado)" "$(printf 'so INFO\n' | percentis_timing | wc -l | tr -d ' ')" "0"
confere "percentis do banco: formata, e o que nao veio (ou n=0) vira 'sem amostra'" "$(printf 'resolver_reaper,2,100,200,300\ndevolucao,0,,,\n' | formata_percentis "resolver_inline resolver_reaper devolucao" | tr '\n' '|')" \
    "resolver_inline: sem amostra|resolver_reaper: n 2 p50 100 p95 200 max 300 (ms)|devolucao: sem amostra|"

# --- o laco inteiro com um `docker` falso no PATH (sem Docker de verdade): cada linha do CSV tem as 36 colunas, na ordem do cabecalho
fake="$(mktemp -d)"
cat > "$fake/docker" <<'FAKE'
#!/usr/bin/env bash
args="$*"
# FAKE_TRAVA="rabbit redis ps": o servico trava. rabbitmqctl e Redis rodam sob `timeout N` no container (estouro = codigo 124); sem o `timeout` o
# fake dorme (o laco congelaria: e o que o teste existe para pegar). O `ps` e coberto pelo `timeout` REAL do host. FAKE_DURO=1: o `timeout` de DENTRO do
# container nao resolve (um filho preso segurando a saida do exec): so o `timeout` do host em volta do `docker exec` salva o laco.
alvo=""; case "$args" in *rabbitmqctl*) alvo=rabbit ;; *redis-cli*) alvo=redis ;; esac
if [ -n "$alvo" ]; then case " ${FAKE_TRAVA:-} " in *" $alvo "*) case "$args" in *"timeout "*) [ -n "${FAKE_DURO:-}" ] && exec sleep 20; exit 124 ;; *) sleep 20 ;; esac ;; esac; fi
case "$args" in
  ps*{{.Status}}*) case " ${FAKE_TRAVA:-} " in *" ps "*) exec sleep 20 ;; esac ;;&
  *psql*"CREATE EXTENSION"*) echo "2026-10-29T12:00:00.000000Z" ;;
  *psql*pg_stat_statements*) exit 0 ;;
  *psql*"date_trunc('minute'"*) [ -n "${FAKE_RESUMO_FALHA:-}" ] && exit 1; printf '2026-10-29 12:01,3,1\n2026-10-29 12:02,0,2\n' ;;
  *psql*percentile_cont*) [ -n "${FAKE_RESUMO_FALHA:-}" ] && exit 1; printf 'resolver_inline,3,10,20,25\nresolver_reaper,1,35000,35000,35000\ndevolucao,4,5,9,12\n' ;;
  *psql*devolucao_pendente*) [ -n "${FAKE_RESUMO_FALHA:-}" ] && exit 1; [ -n "${FAKE_PEND_SQL:-}" ] && printf '%s\n' "$args" > "$FAKE_PEND_SQL"; echo "0,0,2,1" ;;
  # amostra: -c 1 (colunas da R4) e -c 2 (saga) NO MESMO psql. FAKE_SAGA_FALHA=1: o 2o -c falha (ON_ERROR_STOP: saida da 1a, codigo 3).
  # O limiar tem de chegar ao SQL da saga: so com o ORPHAN_MS lido do container (12345) a linha 2 e "7,3,2".
  *psql*pg_stat_activity*) echo "12,3,4,5,40,2,30,1,0,777"; [ -n "${FAKE_SAGA_FALHA:-}" ] && exit 3
      case "$args" in *"12345 milliseconds"*) echo "7,3,2" ;; *) echo "99,99,99" ;; esac ;;
  *psql*) echo "12,3,4,5,40,2,30,1,0,777" ;;
  *"rpk group describe stock"*) printf 'GROUP        stock\nSTATE        Stable\nTOTAL-LAG    4\n\nTOPIC  PARTITION  LAG\nreservation.wallet  0  4\n\nGROUP        stock-catalog\nSTATE        Stable\nTOTAL-LAG    2\n' ;;
  *"rpk group describe"*) echo "TOTAL-LAG    9" ;;
  *rabbitmqctl*) printf 'admission.requests\t5\t1\t10\nadmission.timed_out\t0\t0\t1\n' ;;
  *redis*) printf 'H 41000 2 99\nS\nC adm:cnt:7:1 30\nM adm:mode:7 4\n' ;;
  *printenv*RESERVATION_SAGA_ORPHAN_MS*) echo 12345 ;;
  "ps -q"*) printf 'res-1\nres-2\n' ;;
  # -aq (logs saga.timing): inclui a replica parada pelo scaler durante o bloco (res-3); o `ps -q` do registro/ORPHAN_MS so ve as que rodam
  "ps -aq"*) printf 'res-1\nres-2\nres-3\n' ;;
  logs*) [ -n "${FAKE_SEM_TIMING:-}" ] && { echo "INFO nada de saga.timing"; exit 0; }
      printf '2026-10-29 12:00:01 DEBUG saga.timing - tx1_ms=10 stock_ms=20 tx2_ms=5 total_ms=40\n2026-10-29 12:00:02 DEBUG saga.timing - tx1_ms=30 stock_ms=40 tx2_ms=7 total_ms=80\n2026-10-29 12:00:03 DEBUG saga.timing - tx1_ms=20 stock_ms=30 tx2_ms=6 total_ms=60\n' ;;
  ps*) printf 'backend Up 1 minute\nreservation Up 1 minute\nreservation Up 1 minute\n' ;;
  *) exit 0 ;;
esac
FAKE
chmod +x "$fake/docker"
rod="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rod/"
( cd "$rod" && FAKE_PEND_SQL="$rod/pend.sql" PATH="$fake:$PATH" INTERVALO_S=1 EVENTOS_CADA_S=1 bash ./coleta-host.sh selftest 5 >/dev/null 2>&1 )
csv="$(ls "$rod"/results/coleta-host-selftest-*.csv 2>/dev/null | head -1)"
confere "laco: o CSV tem cabecalho + amostras" "$([ -n "$csv" ] && [ "$(wc -l < "$csv")" -ge 3 ] && echo sim || echo nao)" "sim"
confere "laco: toda linha tem 36 colunas" "$(awk -F, '{print NF}' "$csv" | sort -u | tr '\n' ' ')" "36 "
confere "laco: walletevent_ins, fila, delta (0 depois da 1a amostra; ultima linha), redis e replicas, na ordem" "$(tail -n 1 "$csv" | cut -d, -f14-30)" "777,5,1,10,0,0,41000,2,99,1,4,1,2,0,0,0,0"
confere "laco: delta -1 na 1a amostra" "$(sed -n 2p "$csv" | cut -d, -f19)" "-1"
confere "laco: eventos em formato longo (rpm_aprox = 30 x 60000 / 200)" "$(tail -n +2 "$(ls "$rod"/results/eventos-selftest-*.csv | head -1)" | head -1 | cut -d, -f2-)" "7,30,9000,1,4"
confere "laco: registro da rodada sem segredo" "$(grep -c 'PASSWORD' "$(ls "$rod"/results/registro-selftest-*.txt | head -1)")" "0"

# --- coletas lentas travadas (rabbitmqctl, Redis, docker ps): o laco NAO congela, as colunas viram -1 e o resto da linha segue
rod2="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rod2/"
( cd "$rod2" && PATH="$fake:$PATH" FAKE_TRAVA="rabbit redis ps" TIMEOUT_S=1 INTERVALO_S=1 EVENTOS_CADA_S=1 timeout 90 bash ./coleta-host.sh travado 5 >/dev/null 2>&1 )
csv2="$(ls "$rod2"/results/coleta-host-travado-*.csv 2>/dev/null | head -1)"
confere "travado: o laco terminou e gravou amostras (nao congelou)" "$([ -n "$csv2" ] && [ "$(wc -l < "$csv2")" -ge 3 ] && echo sim || echo nao)" "sim"
confere "travado: toda linha segue com 36 colunas" "$(awk -F, '{print NF}' "$csv2" | sort -u | tr '
' ' ')" "36 "
confere "travado: rabbit_*, redis_* e repl_* em -1; banco (walletevent_ins) e delta seguem" "$(tail -n 1 "$csv2" | cut -d, -f14-30)" "777,-1,-1,-1,-1,0,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1"
confere "travado: nenhuma linha de evento (Redis estourou)" "$(tail -n +2 "$(ls "$rod2"/results/eventos-travado-*.csv | head -1)" | wc -l | tr -d ' ')" "0"
rod3="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rod3/"
( cd "$rod3" && PATH="$fake:$PATH" FAKE_DURO=1 FAKE_TRAVA="rabbit redis" TIMEOUT_S=1 INTERVALO_S=1 EVENTOS_CADA_S=1 timeout 90 bash ./coleta-host.sh duro 7 >/dev/null 2>&1 )
csv3="$(ls "$rod3"/results/coleta-host-duro-*.csv 2>/dev/null | head -1)"
confere "duro: o timeout de dentro do container nao resolve, o do host fecha o docker exec: o laco termina" "$([ -n "$csv3" ] && [ "$(wc -l < "$csv3")" -ge 3 ] && echo sim || echo nao)" "sim"
confere "duro: rabbit_* e redis_* em -1 (banco e delta seguem)" "$(tail -n 1 "$csv3" | cut -d, -f14-24)" "777,-1,-1,-1,-1,0,-1,-1,-1,-1,-1"
confere "duro: toda linha com 36 colunas" "$(awk -F, '{print NF}' "$csv3" | sort -u | tr '
' ' ')" "36 "

# --- Rodada 5 (#204): colunas da saga no laco (lag dos groups do stock num unico rpk; contagens da saga no MESMO psql, limiar lido do container)
confere "saga: lag_stock, lag_stock_catalog (1 rpk multi-group), reservando/orfas/compensando (ORPHAN_MS=12345 lido do reservation)" "$(tail -n 1 "$csv" | cut -d, -f32-36)" "4,2,7,3,2"
confere "saga: as colunas da R4 seguem nas mesmas posicoes (lags antigos 9,9,9)" "$(tail -n 1 "$csv" | cut -d, -f3-5)" "9,9,9"
reg="$(ls "$rod"/results/registro-selftest-*.txt | head -1)"
confere "registro: ORPHAN_MS usado e as variaveis publicas do reaper" "$(grep -c 'ORPHAN_MS=12345' "$reg")" "2"
confere "registro: Hikari do stock (container achado por label)" "$(grep -c '^stock (container res-1)' "$reg")" "1"
sg="$(ls "$rod"/results/saga-selftest-*.csv 2>/dev/null | head -1)"
confere "saga-<rotulo>.csv: compensacoes por minuto (inline e reaper)" "$(tr '\n' '|' < "${sg:-/dev/null}")" "minuto,compensadas_inline,compensadas_reaper|2026-10-29 12:01,3,1|2026-10-29 12:02,0,2|"
rs="$(ls "$rod"/results/resumo-saga-selftest-*.txt 2>/dev/null | head -1)"
confere "resumo da saga: tempo ate resolver (inline e reaper)" "$(grep -E '^  resolver_' "${rs:-/dev/null}" | tr '\n' '|')" "  resolver_inline: n 3 p50 10 p95 20 max 25 (ms)|  resolver_reaper: n 1 p50 35000 p95 35000 max 35000 (ms)|"
confere "resumo da saga: lag de devolucao" "$(grep -E '^  devolucao:' "${rs:-/dev/null}")" "  devolucao: n 4 p50 5 p95 9 max 12 (ms)"
confere "resumo da saga: estado no fim do bloco" "$(grep '^fim do bloco' "${rs:-/dev/null}")" "fim do bloco: devolucao_pendente 0, orfa_inversa 0, sagas_reserving 2, sagas_compensating 1"
confere "resumo da saga: latencia dos passos de TODAS as replicas do reservation, ate a parada (ps -aq: 3 replicas x 3 linhas)" "$(grep -E '^  (tx1|stock|tx2|total)_ms:' "${rs:-/dev/null}" | tr '\n' '|')" \
    "  tx1_ms: n 9 p50 20 p95 30 max 30 (ms)|  stock_ms: n 9 p50 30 p95 40 max 40 (ms)|  tx2_ms: n 9 p50 6 p95 7 max 7 (ms)|  total_ms: n 9 p50 60 p95 80 max 80 (ms)|"
confere "resumo da saga: devolucao pendente parte do event_store (holdId), sem JOIN com walletevent (o UPSERT que revive troca o hold_id)" \
    "$(grep -c 'ReservationCancelled' "$rod/pend.sql")|$(grep -c 'JOIN reservation.walletevent' "$rod/pend.sql")" "1|0"
confere "resumo da saga: o inicio da janela vem do relogio do BANCO (now() do psql inicial), nao do host" "$(grep -c 'janela >= 2026-10-29T12:00:00.000000Z' "${rs:-/dev/null}")" "1"
confere "pgss e resumo antigo seguem (o resumo da saga e extra)" "$([ -n "$(ls "$rod"/results/pgss-selftest-*.csv 2>/dev/null)" ] && echo sim || echo nao)" "sim"

# --- logger saga.timing desligado: o resumo diz que a latencia dos passos esta fora
rod5="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rod5/"
( cd "$rod5" && PATH="$fake:$PATH" FAKE_SEM_TIMING=1 INTERVALO_S=1 bash ./coleta-host.sh semtiming 1 >/dev/null 2>&1 )
confere "sem saga.timing: o resumo avisa (LOGGING_LEVEL_SAGA_TIMING=DEBUG so no bloco que mede)" "$(grep -c 'logger saga.timing desligado' "$(ls "$rod5"/results/resumo-saga-semtiming-*.txt | head -1)")" "1"

# --- SQL da saga falhando (stack da R4, sem a coluna saga_status): as colunas antigas sobrevivem, so a saga fica em -1
rod4="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rod4/"
( cd "$rod4" && PATH="$fake:$PATH" FAKE_SAGA_FALHA=1 INTERVALO_S=1 EVENTOS_CADA_S=1 bash ./coleta-host.sh sagafalha 5 >/dev/null 2>&1 )
csv4="$(ls "$rod4"/results/coleta-host-sagafalha-*.csv 2>/dev/null | head -1)"
confere "saga falhou: o CSV tem cabecalho + >= 2 amostras (o delta 0 abaixo precisa de 2)" "$([ -n "$csv4" ] && [ "$(wc -l < "$csv4")" -ge 3 ] && echo sim || echo nao)" "sim"
confere "saga falhou: toda linha com 36 colunas" "$(awk -F, '{print NF}' "$csv4" | sort -u | tr '\n' ' ')" "36 "
confere "saga falhou: banco da R4 intacto (walletevent_ins e delta 0 na 2a amostra)" "$(tail -n 1 "$csv4" | cut -d, -f14,19)" "777,0"
confere "saga falhou: so reservando/orfas/compensando em -1 (lags do stock seguem)" "$(tail -n 1 "$csv4" | cut -d, -f32-36)" "4,2,-1,-1,-1"

# --- resumo falhando (consulta que quebra): o pgss e o resumo antigo (CSV) saem mesmo assim, e o resumo diz o que faltou
rod6="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rod6/"
( cd "$rod6" && PATH="$fake:$PATH" FAKE_RESUMO_FALHA=1 INTERVALO_S=1 bash ./coleta-host.sh resumofalha 1 > "$rod6/saida.txt" 2>&1 )
confere "resumo falhou: o pgss sai" "$([ -n "$(ls "$rod6"/results/pgss-resumofalha-*.csv 2>/dev/null)" ] && echo sim || echo nao)" "sim"
confere "resumo falhou: o resumo antigo do CSV sai" "$(grep -c 'lag_projecao_ms' "$rod6/saida.txt")" "1"
confere "resumo falhou: o resumo da saga registra as 3 consultas que falharam" "$(grep -c 'falhou' "$(ls "$rod6"/results/resumo-saga-resumofalha-*.txt | head -1)")" "3"
# --- MODO CLUSTER (#228): CARGA_K8S=<contexto>, um `kubectl` falso no PATH (e um `docker` falso que so registra se alguem o chamar). As mesmas 36 colunas, todas com equivalente.
fk="$(mktemp -d)"; export FAKE_K_LOG="$fk/kubectl.log" FAKE_D_LOG="$fk/docker.log"; : > "$FAKE_K_LOG"; : > "$FAKE_D_LOG"
printf '#!/usr/bin/env bash\necho "docker $*" >> "$FAKE_D_LOG"\nexit 1\n' > "$fk/docker"
cat > "$fk/kubectl" <<'FAKEK'
#!/usr/bin/env bash
args="$*"
printf '%s\n' "kubectl $(printf '%s ' "$@" | tr '\n' ' ')" | cut -c1-900 >> "$FAKE_K_LOG"   # uma escrita por chamada, linha curta (as coletas rodam em paralelo: uma linha longa podia ser partida no meio)
case "$args" in
  *"get sts/db"*) [ "${FAKE_DB_ERRO:-0}" = 1 ] && exit 1; [ "${FAKE_DB_REMOTO:-0}" = 1 ] && exit 0; echo "statefulset.apps/db" ;;
  *"get deploy -o jsonpath"*) printf 'backend 1\nreservation 2\nidentity 1\ncatalog 1\nadmission 0\nnotification 1\nstock 1\nfrontend 1\n' ;;
  *"get pods -l app=reservation"*) printf 'reservation-aaa\nreservation-bbb\n' ;;
  *"get pods -l app="*) ;;
  *"logs -l app=reservation"*) printf '2026-10-29 12:00:01 DEBUG saga.timing - tx1_ms=10 stock_ms=20 tx2_ms=5 total_ms=40\n2026-10-29 12:00:03 DEBUG saga.timing - tx1_ms=20 stock_ms=30 tx2_ms=6 total_ms=60\n' ;;
  *psql*"CREATE EXTENSION"*) echo "2026-10-29T12:00:00.000000Z" ;;
  *psql*pg_stat_statements*) exit 0 ;;
  *psql*"date_trunc('minute'"*) printf '2026-10-29 12:01,3,1\n' ;;
  *psql*percentile_cont*) printf 'resolver_inline,3,10,20,25\ndevolucao,4,5,9,12\n' ;;
  *psql*devolucao_pendente*) echo "0,0,2,1" ;;
  *psql*pg_stat_activity*) echo "12,3,4,5,40,2,30,1,0,777"; case "$args" in *"12345 milliseconds"*) echo "7,3,2" ;; *) echo "99,99,99" ;; esac ;;
  *psql*) echo "12,3,4,5,40,2,30,1,0,777" ;;
  *"rpk group describe stock"*) printf 'GROUP        stock\nSTATE        Stable\nTOTAL-LAG    4\n\nGROUP        stock-catalog\nSTATE        Stable\nTOTAL-LAG    2\n' ;;
  *"rpk group describe"*) echo "TOTAL-LAG    9" ;;
  *rabbitmqctl*) printf 'admission.requests\t5\t1\t10\nadmission.timed_out\t0\t0\t1\n' ;;
  *redis-cli*) printf 'H 41000 2 99\nS\nC adm:cnt:7:1 30\nM adm:mode:7 4\n' ;;
  # env do spec do Deployment (as imagens slim nao tem printenv nem sh): "<literal>@@@<valueFrom>". Literal, valueFrom (Secret) e ausente.
  *"get deploy/reservation -o jsonpath"*RESERVATION_SAGA_ORPHAN_MS*) echo '12345@@@' ;;
  *"get deploy/"*"-o jsonpath"*SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE*) echo '1000@@@' ;;
  *"get deploy/"*"-o jsonpath"*SERVER_TOMCAT_THREADS_MAX*) echo '@@@{"secretKeyRef":{"key":"X","name":"env-reservation"}}' ;;
  *"get deploy/"*"-o jsonpath"*) echo '@@@' ;;
  *) exit 0 ;;
esac
FAKEK
chmod +x "$fk/docker" "$fk/kubectl"
K8S_LIB_REAL="$(cd "$(dirname "$0")" && pwd)/../k8s/lib-db-cliente.sh"
rodk="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rodk/"
( cd "$rodk" && PATH="$fk:$PATH" CARGA_K8S=kind-gerenciador K8S_LIB="$K8S_LIB_REAL" APP_DB_PASSWORD=CANARIO-APP POSTGRES_PASSWORD=CANARIO-PG REDIS_PASSWORD=CANARIO-REDIS \
    INTERVALO_S=1 EVENTOS_CADA_S=1 bash ./coleta-host.sh k8s 5 >/dev/null 2>&1 )
csvk="$(ls "$rodk"/results/coleta-host-k8s-*.csv 2>/dev/null | head -1)"
confere "k8s: o CSV tem cabecalho + amostras" "$([ -n "$csvk" ] && [ "$(wc -l < "$csvk")" -ge 3 ] && echo sim || echo nao)" "sim"
confere "k8s: toda linha tem 36 colunas (o cabecalho e o mesmo)" "$(awk -F, '{print NF}' "$csvk" | sort -u | tr '\n' ' ')" "36 "
confere "k8s: walletevent_ins, fila, delta, redis (inclusive adm:replicas, que o admission grava no cluster) e replicas por Deployment (readyReplicas)" "$(tail -n 1 "$csvk" | cut -d, -f14-30)" "777,5,1,10,0,0,41000,2,99,1,4,1,2,1,1,0,1"
confere "k8s: lags dos groups (stock/stock-catalog) e a saga (limiar ORPHAN_MS=12345 lido do Deployment reservation)" "$(tail -n 1 "$csvk" | cut -d, -f32-36)" "4,2,7,3,2"
confere "k8s: nenhuma chamada ao docker" "$(grep -c '' "$FAKE_D_LOG")" "0"
confere "k8s: todo kubectl exec leva -c <container> (pod com init container nao imprime 'Defaulted container' no stderr)" "$(grep -c ' exec ' "$FAKE_K_LOG" | awk '{print ($1>=8)?"sim":"nao"}')$(grep ' exec ' "$FAKE_K_LOG" | grep -vc ' -c ')" "sim0"
confere "k8s: db no cluster: local x remoto decidido UMA vez por processo (1 get sts/db em N amostras), sem apply nem wait" "$(grep -c 'get sts/db' "$FAKE_K_LOG")$(grep -c 'apply -f' "$FAKE_K_LOG")$(grep -c 'wait --for' "$FAKE_K_LOG")" "100"
confere "k8s: depois da decisao, cada psql da coleta e UM kubectl (o exec): todas as outras chamadas ao banco somam 1 get" "$(grep -c 'exec -i sts/db' "$FAKE_K_LOG" | awk '{print ($1>=4)?"sim":"nao"}')$(grep -c 'sts/db' "$FAKE_K_LOG" | awk -v n="$(grep -c 'exec -i sts/db' "$FAKE_K_LOG")" '{print ($1==n+1)?"sim":"nao"}')" "simsim"
confere "k8s: todo kubectl leva --context kind-gerenciador -n gerenciador (log nao vazio)" "$([ -s "$FAKE_K_LOG" ] && grep -vc '^kubectl --context kind-gerenciador -n gerenciador ' "$FAKE_K_LOG")" "0"
confere "k8s: a senha do ambiente do shell nunca vira argumento" "$(grep -c 'CANARIO' "$FAKE_K_LOG")" "0"
confere "k8s: db no cluster: psql pelo sts/db, nunca pelo pod cliente" "$(grep -c 'exec -i sts/db' "$FAKE_K_LOG" | awk '{print ($1>=3)?"sim":"nao"}')$(grep -c 'db-cliente' "$FAKE_K_LOG")" "sim0"
confere "k8s: o psql de admin (supabase_admin) usa a POSTGRES_PASSWORD DENTRO do pod" "$(grep -c 'sh supabase_admin localhost' "$FAKE_K_LOG" | awk '{print ($1>=3)?"sim":"nao"}')" "sim"
confere "k8s: rabbit e redis por kubectl exec (deploy/rabbitmq e deploy/redis) sob timeout" "$(grep -c 'exec deploy/rabbitmq -c rabbitmq -- timeout 5 rabbitmqctl' "$FAKE_K_LOG" | awk '{print ($1>=1)?"sim":"nao"}')$(grep -c 'exec deploy/redis -c redis -- timeout 5 sh -c' "$FAKE_K_LOG" | awk '{print ($1>=1)?"sim":"nao"}')" "simsim"
confere "k8s: rpk por kubectl exec no sts/redpanda" "$(grep -c 'exec sts/redpanda -c redpanda -- timeout 3 rpk group describe reservation ' "$FAKE_K_LOG" | awk '{print ($1>=1)?"sim":"nao"}')" "sim"
rsk="$(ls "$rodk"/results/resumo-saga-k8s-*.txt 2>/dev/null | head -1)"
confere "k8s: latencia dos passos pelo kubectl logs -l app=reservation --since-time (2 linhas)" "$(grep -E '^  tx1_ms:' "${rsk:-/dev/null}")|$(grep -c 'logs -l app=reservation --since-time 2026-10-29T12:00:00.000000Z' "$FAKE_K_LOG" | awk '{print ($1>=1)?"sim":"nao"}')" "  tx1_ms: n 2 p50 10 p95 20 max 20 (ms)|sim"
rgk="$(ls "$rodk"/results/registro-k8s-*.txt | head -1)"
confere "k8s: registro le o env do SPEC do Deployment (literal), sem exec/printenv (a imagem slim nao tem)" "$(grep -c 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=1000' "$rgk" | awk '{print ($1>=1)?"sim":"nao"}')$(grep -c 'printenv' "$FAKE_K_LOG")" "sim0"
confere "k8s: variavel que vem de valueFrom/Secret sai como <de Secret/ConfigMap> (o valor do Secret nunca e lido: nenhum get secret)" "$(grep -c 'SERVER_TOMCAT_THREADS_MAX=<de Secret/ConfigMap>' "$rgk" | awk '{print ($1>=1)?"sim":"nao"}')$(grep -ci 'get secret' "$FAKE_K_LOG")" "sim0"
confere "k8s: variavel ausente do spec vira valor vazio no registro" "$(grep -c '^  RESERVATION_READPATH=$' "$rgk")" "1"
confere "k8s: registro da rodada sem segredo e com o limiar" "$(grep -c 'PASSWORD' "$(ls "$rodk"/results/registro-k8s-*.txt | head -1)")$(grep -c 'ORPHAN_MS=12345' "$(ls "$rodk"/results/registro-k8s-*.txt | head -1)" | awk '{print ($1>=1)?"sim":"nao"}')" "0sim"

: > "$FAKE_K_LOG"; rodk2="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rodk2/"
( cd "$rodk2" && PATH="$fk:$PATH" FAKE_DB_REMOTO=1 CARGA_K8S=kind-gerenciador K8S_LIB="$K8S_LIB_REAL" INTERVALO_S=1 EVENTOS_CADA_S=1 bash ./coleta-host.sh k8sremoto 3 >/dev/null 2>&1 )
csvk2="$(ls "$rodk2"/results/coleta-host-k8sremoto-*.csv 2>/dev/null | head -1)"
confere "k8s db remoto: o CSV segue com 36 colunas e o banco lido (walletevent_ins 777)" "$(awk -F, '{print NF}' "$csvk2" | sort -u | tr '\n' ' ')$(tail -n 1 "$csvk2" | cut -d, -f14)" "36 777"
confere "k8s db remoto: 1 get sts/db, o pod cliente aplicado e esperado UMA vez (nao por amostra)" "$(grep -c 'get sts/db' "$FAKE_K_LOG")$(grep -c 'apply -f' "$FAKE_K_LOG")$(grep -c 'wait --for=condition=Ready pod/db-cliente' "$FAKE_K_LOG")" "111"
confere "k8s db remoto: psql pelo pod db-cliente (aplicado e Ready), nunca pelo sts/db; todo kubectl com --context" "$(grep -c 'exec -i pod/db-cliente' "$FAKE_K_LOG" | awk '{print ($1>=3)?"sim":"nao"}')$(grep -c 'exec -i sts/db' "$FAKE_K_LOG")$(grep -vc '^kubectl --context kind-gerenciador ' "$FAKE_K_LOG")" "sim00"
# erro ao ler o sts/db (nao e "nao existe"): a coleta NAO comeca (nada de cair para "remoto")
: > "$FAKE_K_LOG"; rodk3="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rodk3/"
( cd "$rodk3" && PATH="$fk:$PATH" FAKE_DB_ERRO=1 CARGA_K8S=kind-gerenciador K8S_LIB="$K8S_LIB_REAL" INTERVALO_S=1 bash ./coleta-host.sh k8serro 2 >/dev/null 2>&1 ); rc=$?
confere "k8s: erro no get sts/db: a coleta aborta antes do 1o CSV e sem criar o pod cliente" "$([ "$rc" -ne 0 ] && echo sim || echo nao)$(ls "$rodk3"/results/coleta-host-k8serro-*.csv 2>/dev/null | wc -l | tr -d ' ')$(grep -c 'apply -f\|db-cliente' "$FAKE_K_LOG")" "sim00"
rm -rf "$fk" "$rodk" "$rodk2" "$rodk3"
unset FAKE_K_LOG FAKE_D_LOG
rm -rf "$fake" "$rod" "$rod2" "$rod3" "$rod4" "$rod5" "$rod6"
[ "$falhas" -eq 0 ] && echo "selftest coleta-host: OK" || { echo "selftest coleta-host: $falhas falha(s)"; exit 1; }
