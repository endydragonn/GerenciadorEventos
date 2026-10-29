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
confere "cabecalho: ultima coluna = duracao da amostra" "${CABECALHO##*,}" "duracao_amostra_ms"
# 1 (epoch) + 1 (lag_projecao) + 3 (lags) + 3 (outbox) + 5 (pg) + 1 (walletevent_ins) + 4 (rabbit) + 1 (delta) + 5 (redis) + 6 (replicas) + 1 (duracao)
confere "cabecalho: 31 colunas" "$(echo "$CABECALHO" | awk -F, '{print NF}')" "31"

# --- o laco inteiro com um `docker` falso no PATH (sem Docker de verdade): cada linha do CSV tem as 31 colunas, na ordem do cabecalho
fake="$(mktemp -d)"
cat > "$fake/docker" <<'FAKE'
#!/usr/bin/env bash
args="$*"
# FAKE_TRAVA="rabbit redis ps": o servico trava. rabbitmqctl e Redis rodam sob `timeout N` no container (estouro = codigo 124); sem o `timeout` o
# fake dorme (o laco congelaria: e o que o teste existe para pegar). O `ps` e coberto pelo `timeout` REAL do host.
alvo=""; case "$args" in *rabbitmqctl*) alvo=rabbit ;; *redis-cli*) alvo=redis ;; esac
if [ -n "$alvo" ]; then case " ${FAKE_TRAVA:-} " in *" $alvo "*) case "$args" in *"timeout "*) exit 124 ;; *) sleep 20 ;; esac ;; esac; fi
case "$args" in
  ps*{{.Status}}*) case " ${FAKE_TRAVA:-} " in *" ps "*) exec sleep 20 ;; esac ;;&
  *psql*"CREATE EXTENSION"*) exit 0 ;;
  *psql*pg_stat_statements*) exit 0 ;;
  *psql*) echo "12,3,4,5,40,2,30,1,0,777" ;;
  *"rpk group describe"*) echo "TOTAL-LAG    9" ;;
  *rabbitmqctl*) printf 'admission.requests\t5\t1\t10\nadmission.timed_out\t0\t0\t1\n' ;;
  *redis*) printf 'H 41000 2 99\nS\nC adm:cnt:7:1 30\nM adm:mode:7 4\n' ;;
  ps*) printf 'backend Up 1 minute\nreservation Up 1 minute\nreservation Up 1 minute\n' ;;
  *) exit 0 ;;
esac
FAKE
chmod +x "$fake/docker"
rod="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rod/"
( cd "$rod" && PATH="$fake:$PATH" INTERVALO_S=1 EVENTOS_CADA_S=1 bash ./coleta-host.sh selftest 3 >/dev/null 2>&1 )
csv="$(ls "$rod"/results/coleta-host-selftest-*.csv 2>/dev/null | head -1)"
confere "laco: o CSV tem cabecalho + amostras" "$([ -n "$csv" ] && [ "$(wc -l < "$csv")" -ge 3 ] && echo sim || echo nao)" "sim"
confere "laco: toda linha tem 31 colunas" "$(awk -F, '{print NF}' "$csv" | sort -u | tr '\n' ' ')" "31 "
confere "laco: walletevent_ins, fila, delta (0 na 2a amostra), redis e replicas, na ordem" "$(sed -n 3p "$csv" | cut -d, -f14-30)" "777,5,1,10,0,0,41000,2,99,1,4,1,2,0,0,0,0"
confere "laco: delta -1 na 1a amostra" "$(sed -n 2p "$csv" | cut -d, -f19)" "-1"
confere "laco: eventos em formato longo (rpm_aprox = 30 x 60000 / 200)" "$(tail -n +2 "$(ls "$rod"/results/eventos-selftest-*.csv | head -1)" | head -1 | cut -d, -f2-)" "7,30,9000,1,4"
confere "laco: registro da rodada sem segredo" "$(grep -c 'PASSWORD' "$(ls "$rod"/results/registro-selftest-*.txt | head -1)")" "0"

# --- coletas lentas travadas (rabbitmqctl, Redis, docker ps): o laco NAO congela, as colunas viram -1 e o resto da linha segue
rod2="$(mktemp -d)"; cp "$(dirname "$0")/coleta-host.sh" "$rod2/"
( cd "$rod2" && PATH="$fake:$PATH" FAKE_TRAVA="rabbit redis ps" TIMEOUT_S=1 INTERVALO_S=1 EVENTOS_CADA_S=1 timeout 60 bash ./coleta-host.sh travado 3 >/dev/null 2>&1 )
csv2="$(ls "$rod2"/results/coleta-host-travado-*.csv 2>/dev/null | head -1)"
confere "travado: o laco terminou e gravou amostras (nao congelou)" "$([ -n "$csv2" ] && [ "$(wc -l < "$csv2")" -ge 3 ] && echo sim || echo nao)" "sim"
confere "travado: toda linha segue com 31 colunas" "$(awk -F, '{print NF}' "$csv2" | sort -u | tr '
' ' ')" "31 "
confere "travado: rabbit_*, redis_* e repl_* em -1; banco (walletevent_ins) e delta seguem" "$(sed -n 3p "$csv2" | cut -d, -f14-30)" "777,-1,-1,-1,-1,0,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1"
confere "travado: nenhuma linha de evento (Redis estourou)" "$(tail -n +2 "$(ls "$rod2"/results/eventos-travado-*.csv | head -1)" | wc -l | tr -d ' ')" "0"
rm -rf "$fake" "$rod" "$rod2"
[ "$falhas" -eq 0 ] && echo "selftest coleta-host: OK" || { echo "selftest coleta-host: $falhas falha(s)"; exit 1; }
