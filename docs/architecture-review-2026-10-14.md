# 智餐外卖整体架构评估与目标设计

日期：2026-10-14。本文保留实施前的评估与目标设计；后续源码修改、验证结果及尚未达到的指标见 [架构实施与验收](architecture-implementation-2026-10-14.md)。下文“当前关键发现”描述实施前状态，不代表修改后仍存在。

## 结论

保留 gateway/account/catalog/trade/notification 服务边界，以及 Redis、RabbitMQ、MySQL。优先调整交易语义和可靠性，再优化实际建单吞吐。无证据要求引入 Kafka、分布式事务框架或数据库分片。

验收目标：秒杀数据库实际成功建单至少 800 TPS；同用户同活动最多一份有效资格，取消释放后允许重抢；普通与秒杀共享订单生命周期，但受理和库存策略独立。350ms→65ms、吞吐3倍、SQL减少90%均为待验证目标，不能人为制造基线。

## 当前关键发现

|问题|源码证据|影响与调整|
|---|---|---|
|普通消费时读取购物车|OrderServiceImpl.submitOrder/createOrderFromMessage|提交后编辑购物车会改变订单；受理保存不可变商品/数量/选项快照和参数指纹，成功只清理对应行及版本|
|普通状态不完整|OrderSubmitRequestMapper、findSubmitResult|结果null无法区分处理中与失败；采用ACCEPTED/SUCCEEDED/FAILED状态，失败保留原请求而非删除绑定|
|普通HTTP提交后等待确认|OrderMessagePublisher.enqueue/publish|afterCommit仍在调用线程等待最多5秒；改为独立后台发布，HTTP只等待本地受理事务|
|商品版本覆盖交易库存|TradeInventoryService.deduct、CatalogInternalController.version|updateTime是商品元数据版本，较新版本将stock覆盖；商品编辑可能放回已售数量，应独立库存权威与库存调整命令|
|普通RPC位于写事务内|OrderServiceImpl.createOrderFromMessage、TradeInventoryService.deduct|重复报价及远程等待拉长事务；准备阶段批量查商品/地址，写事务只重判本地状态并写库|
|秒杀发布共享大锁|MqFailMessageRetryTask.publishClaimed/flushSeckillConfirmations|领取、网络发送与确认落库串行竞争；拆分执行通道，锁仅保护在途额度预留与结算|
|秒杀预桥接受理仅在Redis|SeckillCache、SeckillAsyncTask|完整Redis数据丢失无法恢复未桥接请求；明确持久性窗口，验证AOF/复制/驱逐配置及恢复流程|
|超时方案不同|timeoutAfterCommit/timeoutDeferred|普通TTL死信，秒杀到期Outbox直达关闭队列；可靠性优先，若统一死信须保留事件恢复|
|到账按处理时刻判断期限|PaymentSettlement.accept、OrderLifecycleService.paid|channel_paid_at在paid之后写入；期限前付款、期限后通知可能被取消退款，需要明确延迟通知业务策略|

## 服务与数据所有权

- account：用户、地址、OpenID、会话与权限。交易保存订单地址快照，地址远程服务不可参与本地事务。
- catalog：商品描述、分类、上下架、菜单价格；菜单缓存属于展示读模型。
- trade：受理记录、订单、订单明细、实际可售库存、库存调整账本、优惠占用、秒杀活动/桶/资格、支付流水、退款及Outbox。
- notification：订单/支付事件通知；通知故障不得回滚订单或支付。
- MySQL是成交、库存及到账的权威；Redis是菜单缓存与秒杀准入预留；RabbitMQ负责传递事件，不负责判定成交。

## 普通下单目标链路

1. 校验用户及requestId，以(userId,requestId)唯一约束绑定参数指纹。
2. 保存商品ID、数量、口味、购物车行版本、地址ID、优惠选择、结算版本/期望金额等不可变提交快照。服务端权威报价不能信任客户端金额。
3. 同一短事务保存ACCEPTED和建单Outbox，响应ACCEPTED；RPC准备成本单独测量，不能为了低延迟省略快照或身份校验。
4. 后台发布，消费者读数据库权威命令，在写事务外获取商品及归属校验后的地址。价格变化采取明确失败重结算，或采用有时限的服务端报价快照；两者选择需明确。
5. 写事务锁受理，重判状态/截止；按稳定商品顺序条件扣库存，写订单/明细/优惠占用/成功状态/超时事件。仅清理提交时对应的购物车行及版本。
6. 提交后ACK。临时失败持久化退避，永久错误写FAILED；保存恢复依据失败才保留Broker副本。重复终态消息只ACK。

并发重复消息不能只靠先查orders不存在：锁受理记录和数据库订单号唯一约束共同兜底。新购物车内容不能被旧消费者删除。

## 库存设计

普通库存由trade管理；商品初始化库存仅在首次建账使用。商品名称、价格或停售更新不能重置库存。增减库存用唯一adjustmentId和独立stockRevision，以事务记录调整和余额；下调不得侵占已预留数量。

秒杀保留Redis Lua预扣、数据库条件扣桶、全活动用户资格唯一约束、预约releaseOnce及Redis订单级补偿。Redis可受理库存和MySQL未成交库存是不同账，不直接互相覆盖。

必须明确普通套餐库存与秒杀活动库存关系。建议活动启用时从套餐可售库存划拨专属额度，关闭后核对待受理、有效订单和回补事件，再归还未使用额度。若两者独立库存池，则必须有真实独立供应额度，不能各自使用同一份库存。套餐库存是否还要扣组成菜品/原料是额外业务模型；不能默认独立SKU防超卖等于原料总量防超卖。

库存释放在订单取消事务中仅执行一次；秒杀Redis回补通过持久化事件重放。取消成功但Redis回补未完成时暂不能重抢，接口应展示释放处理中，不主动提前放开资格。

## MQ与幂等

统一可靠语义，不强行统一所有热路径实现。普通和秒杀独立队列、并发预算、发布通道与重试预算；单活动分桶继续保持用户资格活动级唯一。共享数据库资源必须留给普通下单和支付处理。

业务事务写Outbox；后台用租约和claim token领取，持久消息、mandatory return、publisher confirm判断发送结果；Broker确认不等于业务完成。消费者事务提交后ACK，ACK丢失允许重投。毒消息/孤立消息进入失败队列，告警并提供可审计重放。业务错误使用错误码或异常类型，不通过中文文案判断是否重试。

|边界|幂等键与约束|
|---|---|
|普通受理|(userId,requestId)+requestHash|
|秒杀受理|(userId,activityId,requestId)+requestHash|
|建单|orderNumber唯一+受理锁/终态|
|有效秒杀资格|(userId,activityId)唯一，取消删除本订单资格|
|库存调整|adjustmentId唯一|
|取消释放|订单状态条件更新+reservation.releaseOnce|
|Redis补偿|orderNumber释放标记+当前用户预留归属|
|支付|订单流水唯一+channel transactionId唯一+金额/身份校验|
|退款|稳定退款号，查询确认结果再更新本地状态|
|通知|eventId，消费端对重复业务副作用去重|

## 超时关闭与死信目标

订单建成时持久化expireTime=createdAt+15min及TIMEOUT事件。主路径后台尽快把消息发到延迟队列，TTL按剩余时间计算，过期经DLX进入关闭队列；如果发布时已到期，直接进入关闭队列。

关闭消费者锁订单，重判待支付、未付款和真实expireTime，事务取消/释放库存与优惠/登记渠道关单及缓存事件。超时事件不能在Broker ACK后就被当作业务完成，应保留到关闭、已支付等终态确认，持久任务到期补投，避免死信转移丢失或TTL队头阻塞。

不同剩余TTL可能队头阻塞，死信转移可靠性依赖队列类型与配置；依据部署版本评估quorum的at-least-once dead-lettering。RabbitMQ TTL不保证严格第900秒执行，验收应测关闭延迟分布及故障恢复。后台扫描的是带索引到期事件，替代扫描orders找待支付订单，并不等于完全无定时任务。

当前秒杀到期Outbox直接发关闭队列本身合理，不应删除这条恢复路径只为了死信表述。MQ发布顺序必须从建单事务外完成，不允许afterCommit阻塞建单吞吐。

参考：https://www.rabbitmq.com/docs/ttl 、https://www.rabbitmq.com/docs/quorum-queues 。

## 缓存与一致性

保留MenuCache：Cache Aside、启动预热、空值短TTL、随机TTL、互斥锁、续期、获锁二次检查和持有者校验回填。

保留MenuCacheInvalidationOutbox：商品写操作同事务登记失效任务，提交后提升版本，失败可租约接管重试。旧查询只回填旧版本，后续查询读新版本。这是最终一致性：提交至失效完成仍可能读旧值，进行中的请求也可能返回旧快照。订单成交不信任菜单缓存的价格、上下架和库存。

当前菜品/套餐全局版本失效简单可靠，规模变大可改分类级或商品依赖版本；必须同时失效旧/新分类以及关联套餐。优化粒度不是800TPS验收的首要项。

缓存90%目标：对固定数据和相同查询分布比较无缓存/缓存两组实际SELECT次数，单列预热SQL、在线SQL、管理失效与混合流量，合并多实例统计。loads计数不是SQL数量。实际订单内部报价走另一读取路径，菜单命中率不能代表建单数据库压力下降。

## 支付目标与现状

保留微信API v3/JSAPI：服务端金额与归属校验、OpenID、time_expire、预支付签名；回调原文验签后解密，核对商户/AppID/币种/金额/交易状态；到账流水、订单状态和成功事件同事务。前端支付返回仅是提示，后端到账才是结果。

当前已有支付恢复查询/渠道关单、共享到账处理、晚到账退款任务及对账组件。重点增加回调/查询/超时/取消的并发与延迟通知验证，不宜另建重复支付框架。

明确延迟通知策略：若渠道在截止前付款但本地尚未取消，可按经验证channel_paid_at结算；若本地已取消并释放库存，不自动复活，应退款或进入明确核对流程。截止后付款按取消退款处理。必须将渠道时间传入事务判断，不能只存档而按回调到达时刻判断。查单与关单均在本地锁外，通过任务重试处理关闭与付款竞争。

真实商户扣款/外部通知/退款联调尚未验证，不能以本地替身测试代表真实交易。微信指引：https://pay.wechatpay.cn/doc/v3/merchant/4012791870 。

## 800TPS性能设计与验收

现有诊断报告指出发布共享锁与MySQL提交耗时，不能据此认定Broker或物理磁盘达到上限。先保留持久性配置，拆分发送和确认写库、批量领取/落库、短事务批量兑现、库存桶及事务外RPC，完善HTTP连接池和资源预算；再测瓶颈是否迁移。

均值估算：P个独立写通道、平均每批B单、每批占用T秒，写阶段粗略速率约P×B/T。P=8、B=8、T=0.08时得800TPS，但仅是理想均值模型，不含上游受限、批次不满、CPU/IO共享、重试和尾时延，不是达标证据。全链路容量取各阶段最慢能力。

500在途额度在800TPS下仅对应平均0.625秒的在途时间预算（Little定律的稳定均值关系）；应根据完整端到端等待及资源预算设置，不能调大额度掩盖消费不足。

正式验收建议：隔离测试库/vhost/Redis键，固定构建和持久化参数，独立压测机；预热后800成功建单TPS持续至少60秒并做更长稳定轮，重复3轮。计划请求需高于目标以留余量，无漏发；用实际数据库提交/成功状态增量统计事务内有效订单，窗口外排空不计稳态TPS。同时记录受理率、FAILED、排队P95/P99、最老任务、MQ ready/unacked、数据库提交/锁等待、普通下单及支付延迟，不能只看总订单数。

停止后核对Redis/MySQL库存、桶账、有效资格、订单、受理终态、Outbox及补偿全部闭环，验证取消后重抢、重复投递、ACK丢失、重启与短暂中间件故障。满负载故障不承诺仍保持800TPS。

350→65ms必须同负载、同环境明确平均或P95及HTTP测量范围；65ms更适合作为上限目标，非必须恰好65ms。3倍比较相同SLO下同步/异步最大稳定实际建单TPS，而非输入速率或受理QPS比。若同步本已高于约267TPS，800TPS未必能满足3倍，需要独立验证。

## 实施顺序

1. P0：库存权威与普通/秒杀额度划拨；普通提交快照、统一受理状态、参数指纹和并发幂等。
2. P0：普通发布移出HTTP及消费者提交回调；临时/永久错误分类和可靠恢复。
3. P1：死信主触发+持久超时事件恢复，支付渠道时间策略与并发取消验收。
4. P1：发布锁拆分、在途额度安全预留、独立发送/确认通道、事务外批量RPC、资源隔离与连接复用。
5. P2：固定版本重复压测，实际800TPS、同步对照3倍、受理65ms、实际SQL90%分别验收；未达标输出瓶颈，不改低口径。

源文件索引：trade-service/src/main/java/com/sky/service/impl/{OrderServiceImpl,TradeInventoryService,SeckillServiceImpl,SeckillAsyncStore,SeckillReservationService,OrderLifecycleService}.java；trade-service/src/main/java/com/sky/service/mq/{OrderMessagePublisher,SeckillPublishStore}.java；trade-service/src/main/java/com/sky/task/MqFailMessageRetryTask.java；catalog-service/src/main/java/com/sky/cache/{MenuCache,MenuCacheWarmup,MenuCacheInvalidationOutbox}.java；trade-service/src/main/java/com/sky/payment/{PaymentSettlement,PaymentRecoveryTask}.java。
