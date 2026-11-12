-- Adds one payment attempt to a customer's total for a business day and returns the total in its currency.
-- KEYS[1]  the customer's day: field e:<event id> for each attempt counted, c:<currency> for each total
-- ARGV[1]  the event id; ARGV[2] the currency; ARGV[3] the amount in units of 1/10,000; ARGV[4] time to live in ms
-- Adding the same event twice (a retried call) counts it once.
local key = KEYS[1]
local total = 'c:' .. ARGV[2]
if redis.call('HSETNX', key, 'e:' .. ARGV[1], 1) == 1 then
  redis.call('HINCRBY', key, total, ARGV[3])
end
redis.call('PEXPIRE', key, tonumber(ARGV[4]))
return redis.call('HGET', key, total)
