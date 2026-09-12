# 菜单缓存治理：实现与验证

## 完成范围

- 菜品、套餐分类查询共用 MenuCache，实现 Cache Aside、获锁后二次检查及有限等待。未获锁请求最多等待约 1 秒，超时返回业务错误，不直接回源；预算不含 Redis 网络阻塞和持锁数据库查询耗时。
- Redis 锁默认租期 10 秒，每约 3.3 秒续期。Lua 校验持有者后原子回填或解锁；旧持有者不能覆盖新结果或删除新锁。
- 普通结果有效期 1800–2100 秒，空列表 60–90 秒，带随机偏移。Redis 返回 null 才是未命中。
- ApplicationReadyEvent 后顺序预热启用分类的在售菜品/套餐。复用查询的 Key、序列化和重建锁，多实例可复用已有缓存。分类预热失败记录日志并继续，后续查询按需重建。
- 菜品停售的缓存失效在事务提交后执行；关联套餐被停售时同步清理套餐缓存。菜品修改、套餐状态修改补齐事务；管理接口在服务成功返回后失效缓存。

## Key 与兼容性

| 数据 | Key | 载荷 |
| --- | --- | --- |
| 菜品 | dish_<categoryId> | List<DishVO> |
| 套餐 | userSetmealCache::<categoryId> | Result<List<Setmeal>> |
| 锁 | lock:cache: + 缓存 Key | UUID |

套餐控制器移除 @Cacheable，改用统一互斥查询；管理端 @CacheEvict 和停售监听器保持兼容。键采用字符串序列化，值采用项目既有 JDK 序列化，Lua TTL 单独编码为十进制字节。构造器明确选择 redisTemplate，避免误注入 StringRedisTemplate。

## 配置

默认开启预热，无需新增依赖。可在本地配置中覆盖：

```yaml
sky:
  cache:
    warmup-enabled: true
    lock-lease-millis: 10000
```

锁租期最低 300ms。当前预热所有启用分类，没有动态热点排行榜；秒杀库存预热是独立功能。

## 统计入口

使用管理端 JWT 请求 GET /admin/cache/stats，返回 dish、setmeal 两组计数。由现有 /admin/** 拦截器保护。

- requests：在线查询次数；hits/misses：请求首次读缓存的结果，重试不会重复计入 hits。
- hitRate：hits / (hits + misses)，无读数时为 0。
- loads：在线菜单加载尝试次数，一次加载可能执行多条 SQL，并非 SQL 条数。
- contentions：遇到锁竞争的请求数；timeouts：等待超时次数；errors：失败次数（含超时、中断）。
- warmups、warmupLoads、warmupErrors：预热尝试、回源和失败，独立于在线命中率。读取分类列表失败只记录日志。

计数为本实例启动以来的累计值，重启归零，查询为近似并发快照。多实例应合并计数再计算命中率，不直接平均比例。

## 验证

缓存相关 21 个测试通过：16 个独立真实 Redis 测试，以及 5 个 Spring 事务回归测试。覆盖并发重建、续期、锁丢失、二次检查、中断、超时、数据库异常、空结果 TTL、预热命中/失败隔离、Spring Cache 失效兼容及 Bean 注入。

在独立的本地 Redis 上复现（示例端口 16479）：

```text
mvn -pl sky-server -am test -Dtest=DishCacheMutexTest,DishStatusCacheTest -Dsurefire.failIfNoSpecifiedTests=false -Dstep2.redis.port=16479
```

未传 step2.redis.port 时真实 Redis 测试会跳过。测试创建并清理自身 Key，不执行 FLUSHDB。

### 冷缓存与预热对比

固定 10 个分类、100 次顺序请求；真实本地 Redis，数据库加载器模拟 5ms 耗时。预热成本独立统计。

| 场景 | 在线命中率 | 在线回源 | 预热回源 |
| --- | --- | --- | --- |
| 冷缓存 | 90% | 10 | 0 |
| 预热完成后 | 100% | 0 | 10 |

原始结果见同目录 menu-cache-comparison.json，测试再次运行会写 sky-server/target/menu-cache-comparison.json。延迟仅供本地复现实验参考；该实验不代表线上命中率、QPS 或真实接口延迟。预热是把首次查询成本前移，不能声称总数据库工作量消失。

## 边界

- 进程长暂停或 Redis 故障仍可能导致失锁后重复查询；锁归属检查阻止旧请求回填，不承诺所有故障下绝无重复 SQL。
- Cache Aside 删除与并发读取仍有竞态，没有数据库版本 fencing，不承诺强一致性。
- 删除失败尚无可靠重试队列。清理沿用项目现有方式，规模扩大后需评估 SCAN 或版本化失效。
- 新 TTL 在重建后生效，旧缓存可能沿用旧 TTL；部署时可在维护窗口通过现有管理操作失效相关缓存。

## 项目描述

缓存治理：针对菜品、套餐分类查询实现 Cache Aside 与启动预热，采用空结果短期缓存和随机过期策略；通过 Redis 分布式互斥锁、自动续期、二次检查及 Lua 原子回填/解锁缓解热点缓存击穿，并提供命中率与回源统计。

不将本地模拟实验收益写成线上收益。
## 本轮全量回归状态

缓存专项测试已通过：DishCacheMutexTest 16/16、DishStatusCacheTest 5/5，均无跳过。本轮随后执行全量 test 时，工作区同步变化的秒杀代码出现 adjustStock 方法和 StockAdjustment 内部类重复定义，导致编译中止。该次全量回归未通过，不能用专项结果替代整个项目的成功状态；需待这些独立改动稳定后重新运行全量测试。