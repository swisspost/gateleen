local lockKey = KEYS[1]
local token = ARGV[1]
local expireMs = tonumber(ARGV[2])

return redis.call('set',lockKey,token,"NX","PX",expireMs)