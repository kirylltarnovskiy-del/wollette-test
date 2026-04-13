-- KEYS[1] = bucket key
-- ARGV[1] = max requests (refill amount per full window)
-- ARGV[2] = burst capacity
-- ARGV[3] = now milliseconds
-- ARGV[4] = refill interval milliseconds per token

local key = KEYS[1]
local maxRequests = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local nowMs = tonumber(ARGV[3])
local refillIntervalMs = tonumber(ARGV[4])

local data = redis.call("HMGET", key, "tokens", "last_refill_ms")
local tokens = tonumber(data[1])
local lastRefillMs = tonumber(data[2])

if tokens == nil then
    tokens = capacity
    lastRefillMs = nowMs
end

local elapsed = math.max(0, nowMs - lastRefillMs)
local refillTokens = math.floor(elapsed / refillIntervalMs)
if refillTokens > 0 then
    tokens = math.min(capacity, tokens + refillTokens)
    lastRefillMs = lastRefillMs + (refillTokens * refillIntervalMs)
end

if tokens < 1 then
    redis.call("HMSET", key, "tokens", tokens, "last_refill_ms", lastRefillMs)
    redis.call("PEXPIRE", key, refillIntervalMs * maxRequests * 2)
    return 0
end

tokens = tokens - 1
redis.call("HMSET", key, "tokens", tokens, "last_refill_ms", lastRefillMs)
redis.call("PEXPIRE", key, refillIntervalMs * maxRequests * 2)
return 1
