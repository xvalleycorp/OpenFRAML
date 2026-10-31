-- Returns an account's movements at or after a time, oldest first.
-- KEYS[1]  the account's movements: event ids scored by time in milliseconds
-- KEYS[2]  the account's movements: event id -> movement
-- ARGV[1]  the time in milliseconds
local ids = redis.call('ZRANGEBYSCORE', KEYS[1], ARGV[1], '+inf')
if #ids == 0 then
  return {}
end
return redis.call('HMGET', KEYS[2], unpack(ids))
