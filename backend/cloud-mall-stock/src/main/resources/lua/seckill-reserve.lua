-- Preserve response codes; initialize the settlement receipt atomically with the debit.
local acceptedType = redis.call('TYPE', KEYS[3]).ok
if acceptedType == 'string' then return 3 end
if acceptedType ~= 'none' then return -1 end
local now = tonumber(ARGV[3])
local startAt = tonumber(ARGV[4])
local endAt = tonumber(ARGV[5])
if now < startAt then return 4 end
if now >= endAt then return 5 end
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
local stockLimit = tonumber(ARGV[7])
if not stockLimit or stockLimit < 1 or available > stockLimit
    or revision >= 1000000000000 or used > 1000000000000 then return -1 end
if used >= tonumber(ARGV[2]) then return 2 end
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
