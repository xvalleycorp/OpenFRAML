-- Rejects a pending proposal.
-- KEYS[1] the proposal; KEYS[2] the pending set
-- ARGV[1] the proposal id; ARGV[2] the checker; ARGV[3] when, ISO-8601; ARGV[4] why
local status = redis.call('HGET', KEYS[1], 'status')
if not status then
  return redis.error_reply('NOTFOUND no proposal ' .. ARGV[1])
end
if status ~= 'PENDING' then
  return redis.error_reply('NOTPENDING proposal ' .. ARGV[1] .. ' is ' .. status)
end
redis.call('HSET', KEYS[1], 'status', 'REJECTED', 'decidedBy', ARGV[2], 'decidedAt', ARGV[3], 'decisionReason',
    ARGV[4])
redis.call('ZREM', KEYS[2], ARGV[1])
return 1
