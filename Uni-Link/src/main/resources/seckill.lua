--秒杀券的lua脚本
--1.参数id
--1,1.优惠券id
local voucherId=ARGV[1]
--1.2.用户id
local userId=ARGV[2]
--1.3订单id
local orderId=ARGV[3]

--2.数据key
--2.1库存key
local stockKey='seckill:stock:' .. voucherId
--2.2订单key
local orderKey='seckill:order:' .. voucherId

--3.脚本业务
--判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <=0) then
    --否 返回1 结束
    return 1
end
 --是 判断用户是否已经下单
if(redis.call('sismember',orderKey,userId)==1) then
    --是 返回2 结束
    return 2
end
  --否 扣减库存 将UserId存入当前优惠券set集合 返回0 结束
redis.call('incrby',stockKey,-1)
redis.call('sadd',orderKey,userId)

--发送消息到队列当中
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0