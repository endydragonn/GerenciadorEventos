-- Contador de tentativas por evento em janela deslizante de buckets (issue #41). Um script = UMA ida ao Redis, atômico.
-- KEYS[1] = prefixo do evento (adm:cnt:<eventId>:); a chave de cada bucket é o prefixo + o índice do bucket (o script lê chaves fora de
--           KEYS, o que não serve a Redis Cluster; o admission usa um Redis único).
-- ARGV[1] = agora (ms, relógio do admission); ARGV[2] = tamanho do bucket (ms); ARGV[3] = janela (ms, múltiplo do bucket);
-- ARGV[4] = "1" conta esta tentativa, "0" só lê.
-- Devolve o RPM = soma dos buckets da janela x 60000 / tempo coberto (inteiro). O bucket corrente está PARCIALMENTE cheio, então o tempo
-- coberto não é a janela inteira: é (janela - bucket) dos buckets completos + o que já passou do corrente (resto + 1 ms, inclusive).
-- Dividir pela janela cheia leria 75-100% da taxa real (com 50/200) e ficaria abaixo do limiar nos primeiros ms de cada bucket.
local agora = tonumber(ARGV[1])
local bucketMs = tonumber(ARGV[2])
local janelaMs = tonumber(ARGV[3])
local atual = math.floor(agora / bucketMs)
local resto = agora - atual * bucketMs

if ARGV[4] == '1' then
  -- toda chave tem TTL: a janela mais um bucket (o suficiente para o bucket sair da janela e ainda ser lido na borda)
  redis.call('INCR', KEYS[1] .. atual)
  redis.call('PEXPIRE', KEYS[1] .. atual, janelaMs + bucketMs)
end

local soma = 0
for i = 0, (janelaMs / bucketMs) - 1 do
  local v = redis.call('GET', KEYS[1] .. (atual - i))
  if v then soma = soma + tonumber(v) end
end
local cobertoMs = janelaMs - bucketMs + resto + 1
return math.floor(soma * 60000 / cobertoMs)
