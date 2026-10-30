-- O worker devolveu a mensagem à fila (nack com requeue), issue #42: ela volta às duas esperas COM TTL, atomicamente (um ZADD seguido de um
-- EXPIRE separados deixariam, se o processo caísse no meio, um ZSET sem TTL, e toda chave do Redis do admission tem TTL).
-- KEYS[1] = adm:wait:<eventId>  KEYS[2] = adm:uwait:<userId>  KEYS[3] = adm:mode:<eventId>
-- ARGV[1] = membro (userId:chave:tentativa)  ARGV[2] = deadline da mensagem (ms; o score)  ARGV[3] = TTL das chaves de espera (s)
-- ARGV[4] = TTL da chave do modo (s). Issue #43: enquanto o worker segurava a mensagem (reservation fora, backoff) a espera ficou vazia e uma
-- decisão com uso < 0,70 pode ter desligado o modo fila; a mensagem que volta o religa (mensagem esperando = modo fila).
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
redis.call('EXPIRE', KEYS[1], ARGV[3])
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
redis.call('EXPIRE', KEYS[2], ARGV[3])
redis.call('SET', KEYS[3], '1', 'EX', ARGV[4])
return 1
