-- Approves a pending proposal and makes its policy the one every node must run, in one atomic step.
-- KEYS[1] the proposal; KEYS[2] the pending set; KEYS[3] the active change; KEYS[4] the history
-- ARGV[1] the proposal id; ARGV[2] the checker; ARGV[3] when, ISO-8601
local p = redis.call('HMGET', KEYS[1], 'status', 'fromId', 'toId', 'kind', 'maker', 'reason')
if not p[1] then
  return redis.error_reply('NOTFOUND no proposal ' .. ARGV[1])
end
if p[1] ~= 'PENDING' then
  return redis.error_reply('NOTPENDING proposal ' .. ARGV[1] .. ' is ' .. p[1])
end
if string.lower(p[5]) == string.lower(ARGV[2]) then
  return redis.error_reply('SAMEPERSON the checker must not be the maker')
end
local current = redis.call('HGET', KEYS[3], 'toId')
if current and current ~= p[2] then
  return redis.error_reply('STALE proposal ' .. ARGV[1] .. ' was made against ' .. p[2] .. ' but ' .. current
      .. ' is now in force')
end
local seq = redis.call('HINCRBY', KEYS[3], 'seq', 1)
redis.call('HSET', KEYS[3], 'kind', p[4], 'fromId', p[2], 'toId', p[3], 'maker', p[5], 'checker', ARGV[2],
    'reason', p[6], 'approvedAt', ARGV[3])
redis.call('HSET', KEYS[1], 'status', 'APPROVED', 'decidedBy', ARGV[2], 'decidedAt', ARGV[3], 'seq', seq)
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('RPUSH', KEYS[4], ARGV[1])
return seq
