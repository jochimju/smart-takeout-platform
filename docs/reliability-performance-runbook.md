# 故障恢复与性能验收操作手册

本轮源码实现四步：故障测试与固定 TTL 分档、积压治理、分阶段诊断、统一验收工具。所有压测必须使用独立 `sky_arch_*` 库与 RabbitMQ vhost，不修改业务库，也不降低数据库持久化配置来换取 TPS。

## 故障语义

Redis AOF `appendfsync always` 下的强制进程重启测试保留预扣、命令和去重结果。无持久化重启后，已初始化活动拒绝自动重建并暂停受理；此测试证明安全停机，不意味着丢失的预扣命令已经恢复。生产仍需制定持久化、复制和人工核账方案。

桥接事务失败保留 Redis 命令；数据库提交后、Redis 删除前中断，允许重放同一命令，数据库唯一约束保证受理账及 Outbox 不重复。MQ 真实 nack/requeue 验证再次投递，MySQL 并发重复消费验证订单和库存只生效一次。

新的延迟消息按剩余时间向上选择固定 TTL 队列：1–9 秒各一档，10–900 秒每 10 秒一档，共 99 个队列。不同剩余期限不会在同一队列通过不同 message expiration 互相阻塞。未到期分档增加不足 10 秒；这不是全系统取消延迟保证，Broker 故障、消费者积压仍会造成额外延迟。已过期直接投关闭路由。保留旧队列用于存量消息排空，保留持久化事件补偿。消费者仍以订单绝对截止时间和支付状态为准。

## 积压与恢复

仅 SECURITY_ADMIN 默认拥有新接口权限；请求身份经过鉴权和精确 RBAC 校验。

1. `GET /admin/order/runtime/backlog` 查看发布失败、过期租约、超时受理、回补积压、可靠性与支付恢复失败、未退役活动数量。
2. `POST /admin/order/runtime/replay` 正文示例：`{"source":"MQ","key":"消息ID","reason":"故障已排除的具体原因"}`。仅重放发布失败、原受理仍 ACCEPTED 且未超时的建单消息。FAILED/取消/过期受理不能复活，用户需新请求。`source=JOB` 恢复失败的超时或退款任务，由原业务处理器重新校验状态。
3. 重放与 `trade_replay_audit` 在同一事务提交，记录操作人和原因；重复操作不会重复重置已恢复消息。原可靠性重放入口也接入审计。
4. `POST /admin/order/runtime/archive?retentionDays=30` 每次最多归档 200 条已完成消息；保留期必须 7–365 天。归档副本保存在 `trade_runtime_archive`，未完成、失败、未确认关闭的超时消息保留；请求幂等凭证、订单和库存账不删除。后台每小时归档一批，需按实际事件产生量配置归档频率与容量。
5. 停用活动、回收库存额度、排空受理/回补、待支付订单终结后，调用 `POST /admin/order/runtime/retire/{activityId}`。退役活动退出桥接和预热扫描，不能再启用。历史订单、资格与 Redis 结果保留供迟到查询及取消使用；此处不是无限制删除历史键。

每 30 秒采样低基数指标 `trade.runtime.backlog{kind=...}` 与 `trade.runtime.scan.available`。异常积压产生 WARN 日志，重复告警限频。没有配置外部通知接收方；运维系统可接入这些指标设置通知，脚本不会擅自发消息。

## 分阶段诊断

`ARCH_PROFILE=1` 启用现有 SeckillTiming 和 JDBC 诊断。报告保存桥接、认领、确认结算、消费者准备、订单事务、SQL 与提交平均耗时；平均值使用压测前后计数差。保存 CPU、可用内存、发布状态及行锁等待采样。诊断会增加开销，容量对照另用 `ARCH_PROFILE=0`。

`order.creation.wait` 在事务成功提交后记录普通/秒杀受理到建单提交的等待时间，回滚不计成功。报告平均等待用计数差；P95 标为 Meter 滚动窗口值，可能含预热，不能伪装成该轮独立分位数。

## 本机与远端运行

先构建服务，再运行 `python tools/architecture-benchmark.py setup`。它创建随机测试库/vhost、独立 Redis 和三个测试服务；需要本地 Python 测试依赖、Java 和 k6。输出本轮目录。

`python tools/architecture-benchmark.py run <本轮目录> 800 60` 测试秒杀 800 请求/秒并混入普通下单。库存、资格、真实订单和受理账会交叉校验。压测丢迭代、未知请求、未完成受理或库存不一致时不能作为达标证据。

远端使用环境变量 `ARCH_REMOTE=1`、`ARCH_BASE_URL=http://服务器可达地址:18181` 执行同一命令。服务端输出 ZIP，包含短期测试用户令牌、k6 脚本、配置与 Python runner，**没有数据库或内部管理凭证**。将 ZIP 复制到压测机，执行 `python architecture-remote-runner.py config.json --k6 <k6路径>`；把生成的报告和 `.client.json` 回传到服务端本轮目录。服务端观察窗口保留约五分钟回传时间。

用户令牌有有效期，包只用于隔离测试，不公开发布。两台机器应同步时间；固定硬件、服务实例数、JVM、连接池、Redis/MQ 配置、持久化策略及脚本/JAR 哈希。主机名不构成物理隔离证明，报告不会自动把远端协议运行标为独立机器验证通过。

## 同步基线与缓存混合对照

普通对照设置 `ARCH_NORMAL_ONLY=1`、`ARCH_NORMAL_RATE=50`，各预热后执行 30 秒。异步组 `ARCH_NORMAL_MODE=async`；同步组设为 `sync` 后重启 trade。同步入口使用相同快照、服务端校验、库存和订单事务，在 HTTP 内等待建单完成；并暂停普通后台发布，避免抢先消费。只在 `benchmark` profile、显式启用开关和 `sky_arch_*` 库三重条件下提供入口。

这是当前业务逻辑的受控同步/异步对照，不是重建历史 350 ms 基线。两组都以 50 请求/秒投递，只能比较耗时与正确性，不能由此推导最大吞吐提升 3 倍。

`python tools/architecture-benchmark.py cache-mixed <本轮目录>` 固定随机种子，混合菜品、套餐及参考库的分类，80% 选择热点分类，其他分类开始时为冷缓存。冷读与缓存组使用相同序列，记录 SQL digest 查询次数；这不是全平台数据库压力对照。

最后执行 `python tools/architecture-benchmark.py cleanup <本轮目录>`，停止本轮进程、删除本轮库/vhost和凭证文件，保留报告。远端包及客户端目录中的令牌副本也应删除。
