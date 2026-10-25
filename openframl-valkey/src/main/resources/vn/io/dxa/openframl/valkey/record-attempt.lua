-- Counts one payment attempt of an account and returns its attempts of the last 24 hours.
-- KEYS[1]  the account's attempts, scored by time in milliseconds
-- ARGV[1]  this attempt's time; ARGV[2] the attempt; ARGV[3] the window in milliseconds; ARGV[4] most attempts kept
-- Adding the same attempt twice (a retried call) counts it once.
local key = KEYS[1]
local at = tonumber(ARGV[1])
local window = tonumber(ARGV[3])
redis.call('ZADD', key, at, ARGV[2])
local latest = tonumber(redis.call('ZRANGE', key, -1, -1, 'WITHSCORES')[2])
redis.call('ZREMRANGEBYSCORE', key, '-inf', latest - window)
redis.call('ZREMRANGEBYRANK', key, 0, -(tonumber(ARGV[4]) + 1))
redis.call('PEXPIRE', key, window)
return redis.call('ZRANGEBYSCORE', key, '(' .. (at - window), '+inf', 'WITHSCORES')
