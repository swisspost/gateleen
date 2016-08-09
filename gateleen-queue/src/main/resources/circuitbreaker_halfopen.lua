local stateField = "state"

local halfOpenCircuitsKey = KEYS[1]
local openCircuitsKey = KEYS[2]

local circuitInfoKeyPrefix = ARGV[1]
local circuitInfoKeySuffix = ARGV[2]

-- move circuits from open to half_open and update state
local counter = 0;
local openCircuits = redis.call('smembers',openCircuitsKey)
for k, circuit in ipairs(openCircuits) do
    redis.call('sadd',halfOpenCircuitsKey,circuit)
    redis.call('srem',openCircuitsKey, circuit)
    redis.call('hset',circuitInfoKeyPrefix..circuit..circuitInfoKeySuffix,stateField,"half_open")
    counter = counter + 1
end

return counter