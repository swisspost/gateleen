local queuesTimersSet = KEYS[1]
local currentTS = tonumber(ARGV[1])

-- get expired queues to return
local expiredQueues = redis.call('zrangebyscore',queuesTimersSet,'-inf',currentTS)

-- remove expired queues
redis.call('zremrangebyscore',queuesTimersSet,'-inf',currentTS)

return expiredQueues