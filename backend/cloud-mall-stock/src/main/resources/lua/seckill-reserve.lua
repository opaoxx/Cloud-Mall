-- Preserve response codes; initialize the settlement receipt atomically with the debit.
local acceptedType = redis.call('TYPE', KEYS[3]).ok
if acceptedType == 'string' then return 3 end
if acceptedType ~= 'none' then return -1 end
-- Parse only canonical bounded integers; invalid configuration is not a business refusal.
local function isNonnegativeInteger(value)
  return value and (value == '0' or string.match(value, '^[1-9]%d*$'))
end
local nowText = ARGV[3]
local startAtText = ARGV[4]
local endAtText = ARGV[5]
if not nowText or (not isNonnegativeInteger(nowText) and not string.match(nowText, '^-[1-9]%d*$'))
    or not isNonnegativeInteger(startAtText)
    or not endAtText or not string.match(endAtText, '^[1-9]%d*$') then return -1 end
local now = tonumber(nowText)
local startAt = tonumber(startAtText)
local endAt = tonumber(endAtText)
if not now or not startAt or not endAt or math.abs(now) > 1000000000000
    or startAt > 1000000000000 or endAt > 1000000000000 or endAt <= startAt then return -1 end
if now < startAt then return 4 end
if now >= endAt then return 5 end
local userLimitText = ARGV[2]
local stockLimitText = ARGV[7]
if not userLimitText or not string.match(userLimitText, '^[1-9]%d*$')
    or not stockLimitText or not string.match(stockLimitText, '^[1-9]%d*$') then return -1 end
local userLimit = tonumber(userLimitText)
local stockLimit = tonumber(stockLimitText)
if not userLimit or not stockLimit or userLimit > 2147483647 or stockLimit > 2147483647 then return -1 end
local stockType = redis.call('TYPE', KEYS[1]).ok
local userType = redis.call('TYPE', KEYS[2]).ok
local operationType = redis.call('TYPE', KEYS[4]).ok
local revisionType = redis.call('TYPE', KEYS[5]).ok
if stockType ~= 'string' or (userType ~= 'none' and userType ~= 'string')
    or operationType ~= 'none' or (revisionType ~= 'none' and revisionType ~= 'string') then
  return -1
end
local stockValue = redis.call('GET', KEYS[1])
local userValue = redis.call('GET', KEYS[2]) or '0'
local revisionValue = redis.call('GET', KEYS[5]) or '0'
-- DECR/INCRBY/INCR require canonical integers, otherwise earlier debit writes may remain.
if (stockValue ~= '0' and not string.match(stockValue, '^[1-9]%d*$'))
    or (userValue ~= '0' and not string.match(userValue, '^[1-9]%d*$'))
    or (revisionValue ~= '0' and not string.match(revisionValue, '^[1-9]%d*$')) then return -1 end
local available = tonumber(stockValue)
local used = tonumber(userValue)
local revision = tonumber(revisionValue)
if not stockLimit or stockLimit < 1 or available > stockLimit
    or revision >= 1000000000000 or used > 1000000000000 then return -1 end
if used >= userLimit then return 2 end
if available < 1 then return 0 end
-- EXPIRE must receive safe positive integer seconds before any debit is written.
local ttlText = ARGV[6]
if not ttlText or not string.match(ttlText, '^[1-9]%d*$') then return -1 end
local ttl = tonumber(ttlText)
if not ttl or ttl <= 0 or ttl > 1000000000000 then return -1 end
-- All checks precede writes: Redis does not roll back a Lua script on runtime error.
redis.call('DECR', KEYS[1])
redis.call('INCRBY', KEYS[2], 1)
redis.call('EXPIRE', KEYS[2], ARGV[6])
-- Retain the original acceptance proof until audited archival, including after activity expiry.
redis.call('SET', KEYS[3], ARGV[1])
local nextRevision = redis.call('INCR', KEYS[5])
redis.call('SET', KEYS[4], 'RESERVED|' .. ARGV[1] .. '|' .. nextRevision)
return 1
