-- Atomic, fail-closed release of one accepted seckill quota.
-- KEYS: stock, metadata, accepted marker, order operation marker, activity revision
-- ARGV: orderNo, operationKey, settlementRevision, quantity
local orderNo = ARGV[1]
local operationKey = ARGV[2]
local settlementRevision = tonumber(ARGV[3])
local quantity = tonumber(ARGV[4])
if not orderNo or not operationKey or settlementRevision ~= 2 or quantity ~= 1 then return 0 end
if operationKey ~= orderNo .. ':ROLLBACK' then return 0 end

local operationMarkerKey = KEYS[4]
if redis.call('TYPE', operationMarkerKey).ok ~= 'string' then return 0 end
local marker = redis.call('GET', operationMarkerKey)
local appliedPrefix = 'APPLIED|' .. operationKey .. '|2|'
if marker and string.sub(marker, 1, string.len(appliedPrefix)) == appliedPrefix then
  local savedRevisionText = string.sub(marker, string.len(appliedPrefix) + 1)
  if not string.match(savedRevisionText, '^%d+$') then return 0 end
  local savedRevision = tonumber(savedRevisionText)
  if savedRevision and savedRevision > 0 and savedRevision <= 1000000000000 then return savedRevision end
  return 0
end
local reservedPrefix = 'RESERVED|' .. orderNo .. '|'
if not marker or string.sub(marker, 1, string.len(reservedPrefix)) ~= reservedPrefix then return 0 end
local acceptanceRevisionText = string.sub(marker, string.len(reservedPrefix) + 1)
if not string.match(acceptanceRevisionText, '^%d+$') then return 0 end
local acceptanceRevision = tonumber(acceptanceRevisionText)
if not acceptanceRevision or acceptanceRevision <= 0 or acceptanceRevision > 1000000000000 then return 0 end

local stockKey = KEYS[1]
local metaKey = KEYS[2]
local acceptedKey = KEYS[3]
local activityRevisionKey = KEYS[5]
if redis.call('TYPE', stockKey).ok ~= 'string'
   or redis.call('TYPE', metaKey).ok ~= 'hash'
   or redis.call('TYPE', acceptedKey).ok ~= 'string'
   or redis.call('TYPE', activityRevisionKey).ok ~= 'string' then return 0 end
local acceptedOrderNo = redis.call('GET', acceptedKey)
if acceptedOrderNo ~= orderNo then return 0 end
local stockText = redis.call('GET', stockKey)
local revisionText = redis.call('GET', activityRevisionKey)
local stockLimitText = redis.call('HGET', metaKey, 'stockLimit')
local startAtText = redis.call('HGET', metaKey, 'startAt')
local endAtText = redis.call('HGET', metaKey, 'endAt')
local perUserLimitText = redis.call('HGET', metaKey, 'perUserLimit')
if not stockText or not revisionText or not stockLimitText or not startAtText or not endAtText or not perUserLimitText
   or not string.match(stockText, '^%d+$') or not string.match(revisionText, '^%d+$')
   or not string.match(stockLimitText, '^%d+$') or startAtText == ''
   or endAtText == '' or not string.match(perUserLimitText, '^%d+$') then return 0 end
local stock = tonumber(stockText)
local currentRevision = tonumber(revisionText)
local stockLimit = tonumber(stockLimitText)
local perUserLimit = tonumber(perUserLimitText)
-- Activity timestamps are ISO-8601 strings in the existing publication contract.
-- Settlement validates their presence, and does not reopen the acceptance time window.
if not stock or not currentRevision or not stockLimit or not perUserLimit
   or stock > 1000000000000 or currentRevision >= 1000000000000 or stockLimit <= 0
   or stockLimit > 1000000000000
   or perUserLimit <= 0 or perUserLimit > 1000000000000 or currentRevision < acceptanceRevision
   or stock > stockLimit - quantity then return 0 end

-- All types, markers and numeric bounds are checked before either write.
local newRevision = redis.call('INCRBY', activityRevisionKey, 1)
redis.call('INCRBY', stockKey, quantity)
redis.call('SET', operationMarkerKey, 'APPLIED|' .. operationKey .. '|2|' .. tostring(newRevision))
return newRevision
