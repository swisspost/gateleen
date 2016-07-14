local stateField = "state"
local failRatioField = "failRatio"
local circuitField = "circuit"
local circuitInfoKey = KEYS[1]
local circuitSuccessKey = KEYS[2]
local circuitFailureKey = KEYS[3]
local circuitKeyToUpdate = KEYS[4]
local openCircuitsKey = KEYS[5]

local requestID = ARGV[1]
local circuit = ARGV[2]
local circuitHash = ARGV[3]
local requestTS = tonumber(ARGV[4])
local errorThresholdPercentage = tonumber(ARGV[5])
local entriesMaxAgeMS = tonumber(ARGV[6])
local minQueueSampleCount = tonumber(ARGV[7])
local maxQueueSampleCount = tonumber(ARGV[8])

local return_value = "OK"

-- add request to circuit to update
redis.call('zadd',circuitKeyToUpdate,requestTS,requestID)
-- write circuit pattern to infos
redis.call('hsetnx',circuitInfoKey, circuitField, circuit)

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
    return totalSamples >= minQueueSampleCount
end

local function preserveSetSize(setToCleanup)
    local cardinality = redis.call('zcard',setToCleanup)
    if cardinality > maxQueueSampleCount then
        local entriesToRemove = cardinality - maxQueueSampleCount
        redis.call('zremrangebyrank',setToCleanup,0, entriesToRemove-1)
    end
end

local function updateFailurePercentage()
    local failPercentage = calculateFailurePercentage()
    redis.call('hset',circuitInfoKey,failRatioField,failPercentage)
    return failPercentage
end

local failPercentage = updateFailurePercentage()

local function openCircuit()
    setCircuitState("open")
    redis.call('sadd',openCircuitsKey,circuitHash)
    return_value = "OPENED"
end

-- update state
if getCircuitState() == "closed" and sampleCountThresholdReached() and failPercentage >= errorThresholdPercentage then
    openCircuit()
end

-- cleanup set when too much entries
preserveSetSize(circuitSuccessKey)
preserveSetSize(circuitFailureKey)

return return_value