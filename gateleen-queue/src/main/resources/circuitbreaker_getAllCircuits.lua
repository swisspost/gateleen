local stateField = "state"
local failRatioField = "failRatio"
local circuitField = "circuit"

local allCircuitsKey = KEYS[1]

local circuitInfoKeyPrefix = ARGV[1]
local circuitInfoKeySuffix = ARGV[2]

local function getCircuitInfos(circuit)
    local fields = redis.call('hmget',circuitInfoKeyPrefix..circuit..circuitInfoKeySuffix,stateField,circuitField,failRatioField)
    return fields
end

local function not_empty(x)
    return (type(x) == "table") and (not x.err) and (#x ~= 0)
end

local result = {}

local allCircuits = redis.call('smembers',allCircuitsKey)
for k, circuit in ipairs(allCircuits) do
    local fields = getCircuitInfos(circuit)
    result[circuit] = {}
    if not_empty(fields[1]) then
        result[circuit].status = fields[1]
    end
    result[circuit].infos = {}
    if not_empty(fields[2]) then
        result[circuit].infos.circuit = fields[2]
    end
    if not_empty(fields[3]) then
        result[circuit].infos.failRatio = tonumber(fields[3])
    end
end

local resEncoded = cjson.encode(result)
return resEncoded