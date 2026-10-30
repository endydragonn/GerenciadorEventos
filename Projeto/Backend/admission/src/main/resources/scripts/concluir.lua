-- Estado final do resultado (issue #42), compare-and-set: só sobrescreve um `queued` DA MESMA TENTATIVA (uma reentrega antiga nunca mexe no
-- resultado de uma tentativa nova da mesma chave, nem refaz um estado já final). Toda escrita tem TTL.
-- KEYS[1] = adm:res:<userId>:<chave>  ARGV[1] = attemptId  ARGV[2] = JSON do estado final  ARGV[3] = TTL do resultado (s)
-- Devolve 1 se gravou, 0 se não (ausente, de outra tentativa ou já final).
local atual = redis.call('GET', KEYS[1])
if not atual then
  return 0
end
local r = cjson.decode(atual)
if r.status ~= 'queued' or r.attemptId ~= ARGV[1] then
  return 0
end
redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
return 1
