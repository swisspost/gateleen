local stateField = "state"
local failRatioField = "failRatio"
local circuitField = "circuit"
local metricNameField = "metricName"

local allCircuitsKey = KEYS[1]

local circuitInfoKeyPrefix = ARGV[1]
local circuitInfoKeySuffix = ARGV[2]

local function getCircuitInfos(circuit)
    return redis.call('hmget',circuitInfoKeyPrefix..circuit..circuitInfoKeySuffix,stateField,circuitField,metricNameField,failRatioField)
end

local function string_not_empty(s)
    return s ~= nil and s ~= '' and s ~= false
end

local result = {}

local allCircuits = redis.call('smembers',allCircuitsKey)
for k, circuit in ipairs(allCircuits) do
    local fields = getCircuitInfos(circuit)
    result[circuit] = {}
    if string_not_empty(fields[1]) then
        result[circuit].status = fields[1]
    end
    result[circuit].infos = {}
    if string_not_empty(fields[2]) then
        result[circuit].infos.circuit = fields[2]
    end
    if string_not_empty(fields[3]) then
        result[circuit].infos.metricName = fields[3]
    end
    if string_not_empty(fields[4]) then
        result[circuit].infos.failRatio = tonumber(fields[4])
    end
end

return cjson.encode(result)