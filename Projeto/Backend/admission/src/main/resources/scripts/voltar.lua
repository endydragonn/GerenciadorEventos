-- O worker devolveu a mensagem à fila (nack com requeue), issue #42: ela volta às duas esperas COM TTL, atomicamente (um ZADD seguido de um
-- EXPIRE separados deixariam, se o processo caísse no meio, um ZSET sem TTL, e toda chave do Redis do admission tem TTL).
-- KEYS[1] = adm:wait:<eventId>  KEYS[2] = adm:uwait:<userId>
-- ARGV[1] = membro (userId:chave:tentativa)  ARGV[2] = deadline da mensagem (ms; o score)  ARGV[3] = TTL das chaves de espera (s)
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
redis.call('EXPIRE', KEYS[1], ARGV[3])
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
redis.call('EXPIRE', KEYS[2], ARGV[3])
return 1
