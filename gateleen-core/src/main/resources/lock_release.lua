local lockKey = KEYS[1]
local token = ARGV[1]

if redis.call("get", lockKey) == token then
    return redis.call("del", lockKey)
else
    return 0
end