local halfOpenCircuitsKey = KEYS[1]

local circuitQueuesKeyPrefix = ARGV[1]
local circuitQueuesKeySuffix = ARGV[2]
local requestTS = tonumber(ARGV[3])

local function not_empty(x)
    return (type(x) == "table") and (not x.err) and (#x ~= 0)
end

local queuesToUnlock = {}
local halfOpenCircuits = redis.call('smembers',halfOpenCircuitsKey)
for k, circuit in ipairs(halfOpenCircuits) do
    local queue = redis.call('zrange',circuitQueuesKeyPrefix..circuit..circuitQueuesKeySuffix,0,0)
    if not_empty(queue) then
        table.insert(queuesToUnlock, queue[1])
        redis.call('zadd',circuitQueuesKeyPrefix..circuit..circuitQueuesKeySuffix,requestTS,queue[1])
    end
end

return queuesToUnlock