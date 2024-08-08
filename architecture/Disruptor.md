# Disruptor

## 关于程序无法响应请求
AccountDispatcher消费订单请求,余额足够就会把请求发送到MatchDispatcher，如果成交MatchDispatcher有会把清算请求发送给AccountDispatcher,
如果这时候AccountDispatcher当前已满，无法在发布，整体程序就阻塞在发布，下单请求无法完结，新请求无法进入
需要合理的设置RingBuffer的大小

``` java
Disruptor<Message> accountDisruptor = new Disruptor<>(Message.FACTORY, 4, DaemonThreadFactory.INSTANCE, ProducerType.MULTI, new LiteBlockingWaitStrategy());
```