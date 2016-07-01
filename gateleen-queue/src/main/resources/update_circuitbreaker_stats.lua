local key = KEYS[1]

local requestID = ARGV[1]
local requestTS = tonumber(ARGV[2])
local maxSetSize = tonumber(ARGV[3])

-- add request to sorted set
redis.call('zadd',key,requestTS,requestID)

-- cleanup set when too much entries
local removed = 0
local cardinality = redis.call('zcard',key)
if cardinality > maxSetSize then
    local entriesToRemove = cardinality - maxSetSize
    removed = redis.call('zremrangebyrank',key,0, entriesToRemove-1)
end

return "OK - Removed:"..removed