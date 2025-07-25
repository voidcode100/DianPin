--1.参数列表
--1.1 优惠券id
local voucherId = ARGV[1]
--1.2 用户id
local userId = ARGV[2]
--订单id
local orderId = ARGV[3]


--2.数据key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

--3.判断库存是否充足
if (tonumber(redis.call('get',stockKey)) <= 0) then
    return 1
end

--4.判断用户是否下单
if(redis.call('sismember',orderKey,userId) == 1) then
    return 2
end
--5.扣减库存
redis.call('incrby',stockKey,-1)
--6.保存用户订单
redis.call('sadd',orderKey,userId)

--发送消息队列到队列中 xadd stream.orders * k1 v1 k2 v2
redis.call('xadd','stream.orders','*','voucherId',voucherId,'userId',userId,'id',orderId)
return 0