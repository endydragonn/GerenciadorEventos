-- Desfaz uma entrada na fila que NÃO foi publicada (issue #42): apaga o `queued` DESTA tentativa (se ainda for o resultado) e tira o membro
-- das duas esperas. Idempotente.
-- KEYS[1] = adm:res:<userId>:<chave>  KEYS[2] = adm:wait:<eventId>  KEYS[3] = adm:uwait:<userId>
-- ARGV[1] = attemptId  ARGV[2] = membro da espera
local atual = redis.call('GET', KEYS[1])
if atual then
  local r = cjson.decode(atual)
  if r.status == 'queued' and r.attemptId == ARGV[1] then
    redis.call('DEL', KEYS[1])
  end
end
redis.call('ZREM', KEYS[2], ARGV[2])
redis.call('ZREM', KEYS[3], ARGV[2])
return 1
