local stateField = "state"
local currentFailurePercentageField = "currFailurePercentage"
local circuitInfoKey = KEYS[1]
local circuitSuccessKey = KEYS[2]
local circuitFailureKey = KEYS[3]
local circuitKeyToUpdate = KEYS[4]

local requestID = ARGV[1]
local requestTS = tonumber(ARGV[2])
local errorThresholdPercentage = tonumber(ARGV[3])
local entriesMaxAgeMS = tonumber(ARGV[4])
local minSampleCount = tonumber(ARGV[5])
local maxSetSize = tonumber(ARGV[6])

redis.log(redis.LOG_NOTICE, "********************")
redis.log(redis.LOG_NOTICE, "INPUT circuitInfoKey: "..circuitInfoKey)
redis.log(redis.LOG_NOTICE, "INPUT circuitSuccessKey: "..circuitSuccessKey)
redis.log(redis.LOG_NOTICE, "INPUT circuitFailureKey: "..circuitFailureKey)
redis.log(redis.LOG_NOTICE, "INPUT circuitKeyToUpdate: "..circuitKeyToUpdate)
redis.log(redis.LOG_NOTICE, "INPUT requestID: "..requestID)
redis.log(redis.LOG_NOTICE, "INPUT requestTS: "..requestTS)
redis.log(redis.LOG_NOTICE, "INPUT errorThresholdPercentage: "..errorThresholdPercentage)
redis.log(redis.LOG_NOTICE, "INPUT entriesMaxAgeMS: "..entriesMaxAgeMS)
redis.log(redis.LOG_NOTICE, "INPUT minSampleCount: "..minSampleCount)
redis.log(redis.LOG_NOTICE, "INPUT maxSetSize: "..maxSetSize)
redis.log(redis.LOG_NOTICE, "********************")

-- add request to circuit to update
redis.call('zadd',circuitKeyToUpdate,requestTS,requestID)

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
    redis.call('hset',circuitInfoKey,currentFailurePercentageField,failPercentage)
    return failPercentage
end

local failPercentage = updateFailurePercentage()

-- update state
if getCircuitState() == "closed" and sampleCountThresholdReached() and failPercentage >= errorThresholdPercentage then
    setCircuitState("open")
end

-- cleanup set when too much entries
preserveSetSize(circuitSuccessKey)
preserveSetSize(circuitFailureKey)

return "OK"