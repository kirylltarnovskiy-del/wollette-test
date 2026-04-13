-- KEYS[1] = current window key
-- KEYS[2] = previous window key
-- ARGV[1] = max requests
-- ARGV[2] = window size seconds
-- ARGV[3] = elapsed in current window seconds

local maxRequests = tonumber(ARGV[1])
local windowSizeSec = tonumber(ARGV[2])
local elapsedSec = tonumber(ARGV[3])

local prevCount = tonumber(redis.call("GET", KEYS[2])) or 0
local currentCount = tonumber(redis.call("GET", KEYS[1])) or 0

local prevWeight = 1.0 - (elapsedSec / windowSizeSec)
local effectiveCount = (prevCount * prevWeight) + currentCount

if effectiveCount >= maxRequests then
    return 0
end

redis.call("INCR", KEYS[1])
redis.call("EXPIRE", KEYS[1], windowSizeSec * 2)
return 1
