local cachedSet = KEYS[1]
local cachePrefix = ARGV[1]
local resourceName = ARGV[2]
local resourceValue = ARGV[3]
local expireMillis = tonumber(ARGV[4])

local resourceKey = cachePrefix..resourceName

redis.call('sadd', cachedSet, resourceName)
redis.call('set', resourceKey, resourceValue, 'px', expireMillis)

return "OK"

