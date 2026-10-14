# 菜单缓存一致性验证（2026-10-14）

## 范围与结果

当前工作区源码；独立本机 Redis 3.2，端口 16581；真实 RedisTemplate、JDK 序列化、Lua 和 Spring 缓存代理。
数据库内容与提交完成由内存快照模拟，事务事件测试使用 Spring 真实事务同步与模拟事务管理器。没有运行真实 MySQL 事务、完整 HTTP 下单链路或线上负载。

运行 DishCacheMutexTest（16）、DishStatusCacheTest（5）、MenuCacheConsistencyTest（新增 3）：共 24 个用例，失败 0、错误 0、跳过 0。
新增测试属于现状验证：部分用例断言缺陷可以复现，因此通过不代表系统没有一致性问题。

| 场景 | 观察结果 | 结论 |
|---|---|---|
| 菜品旧查询取得快照后阻塞；修改完成并提升版本；旧查询再回填 | 原查询返回 old；之后 20 次查询全部 new，只回源 1 次 | 版本号隔离旧回填，已经开始的查询仍可能返回旧数据 |
| 套餐旧查询取得快照后阻塞；模拟提交完成，执行 invalidateAll 以及真实 Controller @CacheEvict；旧查询再回填 | 之后 20 次全部 old，回源 0 次，旧缓存剩余 TTL 2059 秒 | 固定套餐键可被旧回填重新污染，互斥锁没有防止读写之间的该竞争 |
| 已缓存菜品；模拟数据库更新完成；在 invalidateDishes 边界注入 RedisConnectionFailureException | 后续 20 次仍 old；手动再次成功失效后读取 new | 失效失败后旧缓存仍有效；此测试是边界异常注入，没有实际断开 Redis 网络 |
| 同分类 6 个并发缓存未命中 | 回源 1 次 | 并发重建互斥有效 |
| 锁过期后被新持有者接管 | 旧持有者不能发布、不能删除新锁 | Lua 所有权校验有效 |
| 菜品停售关联套餐；事务提交与回滚 | 提交后失效，回滚保留缓存 | Spring 事务事件时序符合设计；数据库操作是 mock |

2059 秒约 34.3 分钟，是本次读出的剩余 TTL，没有等待 34 分钟观测全过程。代码设置的正常列表 TTL 为 1800～2100 秒。指定时序可复现缺陷，不能推算线上发生频率或故障率。

## 复现方式

启动专用 Redis，禁用持久化，避免连接开发业务 Redis，因为版本号与套餐全区域清理是全局操作。

```text
mvn -pl catalog-service -am test -Dtest=DishCacheMutexTest,DishStatusCacheTest,MenuCacheConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false -Dstep2.redis.port=16581
```

本次日志：工作区 `.runtime/cache-consistency-tests.log`。JUnit 报告：`catalog-service/target/surefire-reports`。

## 判断与后续

菜品正常失效路径的并发保护有效；套餐仍有可复现的旧回填问题；失效失败没有从本次读请求中自动修复。
如果业务要求停售/调价后菜单及时更新，当前实现不足以保证这一要求。
是否影响交易正确性，还要另外验证下单实时校验与跨服务数据读取，不能把本报告视为完整上线验收。
建议套餐也使用版本键，并把失效统一放到提交后；持久化失效任务需要与数据库变更同事务记录并重试。

本次仅新增测试与报告，未修改业务实现。
