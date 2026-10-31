-- Records one movement of an account, once, and drops the movements older than the retention.
-- KEYS[1]  the account's movements: event ids scored by time in milliseconds
-- KEYS[2]  the account's movements: event id -> movement
-- ARGV[1]  the movement's time; ARGV[2] its event id; ARGV[3] the movement; ARGV[4] the retention in milliseconds
-- Returns 1, or 0 if the event was already recorded.
local times, movements = KEYS[1], KEYS[2]
local at, id, retention = tonumber(ARGV[1]), ARGV[2], tonumber(ARGV[4])
if redis.call('HEXISTS', movements, id) == 1 then
  return 0
end
redis.call('ZADD', times, at, id)
redis.call('HSET', movements, id, ARGV[3])
local latest = tonumber(redis.call('ZRANGE', times, -1, -1, 'WITHSCORES')[2])
local old = redis.call('ZRANGEBYSCORE', times, '-inf', '(' .. (latest - retention))
for _, expired in ipairs(old) do
  redis.call('HDEL', movements, expired)
end
redis.call('ZREMRANGEBYSCORE', times, '-inf', '(' .. (latest - retention))
redis.call('PEXPIRE', times, retention)
redis.call('PEXPIRE', movements, retention)
return 1
