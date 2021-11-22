local cachedSet = KEYS[1]
local cachePrefix = ARGV[1]
local clearAll = ARGV[2]

local entriesToClear = redis.call('smembers',cachedSet)

local count = 0

if clearAll == "true" then
    for i, key_name in ipairs(entriesToClear) do
        redis.call('del',cachePrefix..key_name)
        redis.call('srem',cachedSet, key_name)
        count = count + 1
    end
else
    for i, key_name in ipairs(entriesToClear) do
        if redis.call('exists',cachePrefix..key_name) == 0 then
            redis.call('srem',cachedSet, key_name)
            count = count + 1
        end
    end
end

return count
