local stateField = "state"

local circuitInfoKey = KEYS[1]
local halfOpenCircuitsKey = KEYS[2]
local openCircuitsKey = KEYS[3]

local endpointHash = ARGV[1]
local requestTS = tonumber(ARGV[2])

-- reset circuit infos
redis.call('hset',circuitInfoKey,stateField,"open")

-- remove circuit from half-open-circuits set
redis.call('zrem',halfOpenCircuitsKey,endpointHash)

-- add circuit to open-circuits set
redis.call('zadd',openCircuitsKey,requestTS,endpointHash)

return "OK"