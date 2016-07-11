local stateField = "state"
local failRatioField = "failRatio"
local endpointField = "endpoint"
local circuitInfoKey = KEYS[1]
local circuitSuccessKey = KEYS[2]
local circuitFailureKey = KEYS[3]
local circuitKeyToUpdate = KEYS[4]

local requestID = ARGV[1]
local endpoint = ARGV[2]
local requestTS = tonumber(ARGV[3])
local errorThresholdPercentage = tonumber(ARGV[4])
local entriesMaxAgeMS = tonumber(ARGV[5])
local minSampleCount = tonumber(ARGV[6])
local maxSetSize = tonumber(ARGV[7])

local return_value = "OK"

-- add request to circuit to update
redis.call('zadd',circuitKeyToUpdate,requestTS,requestID)
-- write endpoint pattern to infos
redis.call('hsetnx',circuitInfoKey,endpointField, endpoint)

local function setCircuitState(state)
    redis.call('hset',circuitInfoKey,stateField,state)
end

local function getCircuitState()
    local state = redis.call('hget',circuitInfoKey,stateField)
    if state == nil or state == false then
        setCircuitState("closed")
        return "closed"
    end
    return state
end

local function calculateFailurePercentage()
    local minScore = requestTS - entriesMaxAgeMS
    redis.log(redis.LOG_NOTICE, "minScore: "..minScore)
    local successCount = redis.call('zcount',circuitSuccessKey,minScore,'+inf')
    redis.log(redis.LOG_NOTICE, "successCount: "..successCount)
    local failureCount = redis.call('zcount',circuitFailureKey,minScore,'+inf')
    redis.log(redis.LOG_NOTICE, "failureCount: "..failureCount)
    local total = successCount + failureCount
    local percentage = 0
    if failureCount > 0 then
        percentage = math.floor((failureCount / total)*100)
    end
    redis.log(redis.LOG_NOTICE, "percentage: "..percentage.."%")
    return percentage
end

local function sampleCountThresholdReached()
    local totalSamples = redis.call('zcard',circuitSuccessKey) + redis.call('zcard',circuitFailureKey)
    redis.log(redis.LOG_NOTICE, "total sample count is "..totalSamples)
    return totalSamples >= minSampleCount
end

local function preserveSetSize(setToCleanup)
    local cardinality = redis.call('zcard',setToCleanup)
    if cardinality > maxSetSize then
        local entriesToRemove = cardinality - maxSetSize
        redis.call('zremrangebyrank',setToCleanup,0, entriesToRemove-1)
    end
end

local function updateFailurePercentage()
    local failPercentage = calculateFailurePercentage()
    redis.call('hset',circuitInfoKey,failRatioField,failPercentage)
    return failPercentage
end

local failPercentage = updateFailurePercentage()

-- update state
if getCircuitState() == "closed" and sampleCountThresholdReached() and failPercentage >= errorThresholdPercentage then
    setCircuitState("open")
    return_value = "OPENED"
end

-- cleanup set when too much entries
preserveSetSize(circuitSuccessKey)
preserveSetSize(circuitFailureKey)

return return_value