local queuesTimersSet = KEYS[1]
local queue = ARGV[1]
local expireTS = tonumber(ARGV[2])

local count = 0
local score = tonumber(redis.call('zscore',queuesTimersSet,queue))
if score == nil then
    count = tonumber(redis.call('zadd',queuesTimersSet,expireTS,queue))
end
return count