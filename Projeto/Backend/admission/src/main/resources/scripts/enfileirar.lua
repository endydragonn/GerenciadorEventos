-- Entrada na fila de admissão (issue #42): grava o resultado `queued` e a espera do evento e do usuário, depois da repetição da chave e do
-- limite por usuário. UM script = uma ida ao Redis, atômico.
-- KEYS[1] = adm:res:<userId>:<chave>  KEYS[2] = adm:wait:<eventId>  KEYS[3] = adm:uwait:<userId>
-- ARGV[1] = agora (ms)  ARGV[2] = eventId  ARGV[3] = JSON do resultado `queued` desta tentativa  ARGV[4] = TTL do resultado (s)
-- ARGV[5] = máximo de mensagens em espera por usuário  ARGV[6] = membro da espera (userId:chave:tentativa)
-- ARGV[7] = deadline da mensagem (ms; é o score da espera)  ARGV[8] = TTL das chaves de espera (s)  ARGV[9] = carência do queued (ms)
-- Devolve {código, JSON}: 0 = enfileirada (publicar); 1 = repetição, o JSON é o estado atual (não publica de novo); 2 = a chave é de OUTRO
-- evento (422); 3 = limite por usuário (429).
local agora = tonumber(ARGV[1])
local atual = redis.call('GET', KEYS[1])
if atual then
  local r = cjson.decode(atual)
  if tonumber(r.eventId) ~= tonumber(ARGV[2]) then
    return {2, ''}
  end
  -- um `queued` cujo deadline + a carência (60 s) já passou vale como `timed_out` (a mensagem se perdeu; ex.: o broker reiniciou)
  local status = r.status
  if status == 'queued' and tonumber(r.deadline) + tonumber(ARGV[9]) < agora then
    status = 'timed_out'
  end
  if status == 'queued' or status == 'pending' then
    return {1, atual}
  end
  -- `rejected` ou `timed_out` do mesmo evento: sobrescreve com uma tentativa nova (como no síncrono, uma falha não consome a chave)
end
-- o que já passou do deadline sai da espera (mensagem perdida não prende o usuário nem o modo fila)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', '(' .. agora)
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', '(' .. agora)
if redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[5]) then
  return {3, ''}
end
redis.call('SET', KEYS[1], ARGV[3], 'EX', ARGV[4])
redis.call('ZADD', KEYS[2], ARGV[7], ARGV[6])
redis.call('EXPIRE', KEYS[2], ARGV[8])
redis.call('ZADD', KEYS[3], ARGV[7], ARGV[6])
redis.call('EXPIRE', KEYS[3], ARGV[8])
return {0, ''}
