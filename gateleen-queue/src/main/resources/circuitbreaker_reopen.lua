local stateField = "state"

local circuitInfoKey = KEYS[1]
local halfOpenCircuitsKey = KEYS[2]
local openCircuitsKey = KEYS[3]

local circuitHash = ARGV[1]

-- reset circuit infos
redis.call('hset',circuitInfoKey,stateField,"open")

-- remove circuit from half-open-circuits set
redis.call('srem',halfOpenCircuitsKey,circuitHash)

-- add circuit to open-circuits set
redis.call('sadd',openCircuitsKey,circuitHash)

return "OK"