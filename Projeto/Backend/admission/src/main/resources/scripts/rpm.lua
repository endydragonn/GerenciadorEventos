-- Contador de tentativas por evento em janela deslizante de buckets (issue #41) + gatilho e histerese do modo fila (issue #43). Um script = UMA
-- ida ao Redis, atômico.
-- KEYS[1] = prefixo do evento (adm:cnt:<eventId>:); a chave de cada bucket é o prefixo + o índice do bucket (o script lê chaves fora de
--           KEYS, o que não serve a Redis Cluster; o admission usa um Redis único).
-- KEYS[2] = adm:mode:<eventId> (presente = o evento está em modo fila)  KEYS[3] = adm:wait:<eventId> (ZSET da espera, score = deadline)
-- ARGV[1] = agora (ms, relógio do admission); ARGV[2] = tamanho do bucket (ms); ARGV[3] = janela (ms, múltiplo do bucket);
-- ARGV[4] = "1" conta esta tentativa, "0" só lê.
-- ARGV[5] = "1" decide o modo (braço cas), "0" não toca no modo; ARGV[6] = capacidade (X x réplicas, RPM); ARGV[7] = uso que liga a fila (0,80);
-- ARGV[8] = uso abaixo do qual a fila pode desligar (0,70); ARGV[9] = TTL da chave do modo (s).
-- Devolve {RPM, fila} (fila = 1 se o evento está em modo fila DEPOIS desta decisão). O RPM = soma dos buckets da janela x 60000 / tempo
-- coberto (inteiro). O bucket corrente está PARCIALMENTE cheio, então o tempo coberto não é a janela inteira: é (janela - bucket) dos buckets
-- completos + o que já passou do corrente (resto + 1 ms, inclusive). Dividir pela janela cheia leria 75-100% da taxa real (com 50/200) e ficaria
-- abaixo do limiar nos primeiros ms de cada bucket.
-- Histerese (só com ARGV[5] = "1"): a espera do evento é lida ANTES de contar esta tentativa (o que já passou do deadline sai dela). Em
-- `sync`, uso >= entrar liga a fila; em fila, uso < sair E espera vazia desliga; senão mantém (e renova o TTL).
local agora = tonumber(ARGV[1])
local bucketMs = tonumber(ARGV[2])
local janelaMs = tonumber(ARGV[3])
local atual = math.floor(agora / bucketMs)
local resto = agora - atual * bucketMs
local decidir = ARGV[5] == '1'

local espera = 0
if decidir then
  redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', '(' .. agora)
  espera = redis.call('ZCARD', KEYS[3])
end

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
local rpm = math.floor(soma * 60000 / cobertoMs)

local fila = 0
if decidir then
  local uso = rpm / tonumber(ARGV[6])
  local emFila = redis.call('EXISTS', KEYS[2]) == 1
  if emFila then
    if uso < tonumber(ARGV[8]) and espera == 0 then
      redis.call('DEL', KEYS[2])
    else
      redis.call('EXPIRE', KEYS[2], ARGV[9])
      fila = 1
    end
  elseif uso >= tonumber(ARGV[7]) then
    redis.call('SET', KEYS[2], '1', 'EX', ARGV[9])
    fila = 1
  end
end
return {rpm, fila}
