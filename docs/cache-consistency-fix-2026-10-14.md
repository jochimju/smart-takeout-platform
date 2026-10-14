# 套餐缓存旧回填修复

## 问题与实现

原套餐缓存采用固定键。查询读到旧数据库快照后阻塞，写请求提交并删除缓存，查询再回填，会使后续请求持续读取旧菜单，直到过期。

套餐改为 `setmeal:v<version>:<categoryId>`，版本存储在 `setmeal:cache:version`，通过 Redis 原生 INCR 提升。写入旧版本的缓存不影响后续查询；旧版本使用原 TTL 自然过期。旧固定键不再参与读取。

SetmealServiceImpl 的新增、修改、删除、起停售使用事务同步，只有最外层事务提交成功后才提升版本，回滚保留原版本。直接调用 Service 同样有效，不依赖 Controller。菜品停售联动套餐时，原 AFTER_COMMIT 监听器也提升套餐版本。

## 本次验证

使用独立本机 Redis 3.2（16581），真实序列化、Lua、Spring 缓存代理及事务同步。数据库快照、提交与回滚由测试替身控制，没有做真实 MySQL/完整 HTTP 交易链路验收。

- catalog-service 全部 35 个测试通过，失败 0、错误 0、跳过 0；其中缓存相关 33 个，权限相关 2 个。
- 套餐旧查询在版本失效和 Controller 清理后才回填：后续 20 次全部读取 new，回源 1 次；修复前相同场景 20 次全部 old、回源 0 次。
- 新增/修改/删除/起停售各自验证提交前不失效、提交后失效，共 4 个用例。
- 上述四条写路径各自验证外层事务回滚不失效，共 4 个用例。
- 旧固定键保留 old，新的菜单查询仍加载并返回 new。
- 原并发重建、锁续期与失锁、菜品停售关联套餐、事务回滚测试均通过。
- 缓存失效异常注入仍能复现旧缓存继续被读取，保留此测试明确剩余边界。

```text
mvn -pl catalog-service -am test -Dstep2.redis.port=16581
```

日志：工作区 `.runtime/cache-consistency-fix-tests.log`；JUnit 报告：`catalog-service/target/surefire-reports`。

## 边界与部署

这个修复解决成功失效后的旧回填污染，并非强一致性方案。已经开始的查询可能返回旧快照；Redis INCR 失败或提交后进程退出仍需持久化补偿，这次没有实现该补偿。

所有 catalog-service 实例需要统一更新；新旧版本混跑时，旧实例写数据库不会提升新套餐版本。只改变 Redis 中的菜单键，不修改数据库结构。

修复前的复现记录保留在 `cache-consistency-verification-2026-10-14.md`，属于历史结果，不代表修复后表现。
