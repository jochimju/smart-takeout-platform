# Spring Security 分阶段验收

本次改造以现有源码为基线，不覆盖工作区已有业务改动。保持管理端 `token`、用户端 `authentication`、JWT 的 `empId`/`userId` 和登录 `Result`/VO 格式。采用无会话的请求头认证，不使用认证 Cookie。

## 1. 协议盘点

- POST `/admin/employee/login`、POST `/user/user/login`：匿名登录。
- GET `/user/shop/status`、GET `/user/seckill/activity/list`：公开业务查询。
- POST `/notify/paySuccess`：无登录令牌，但必须经过原支付签名和交易校验。
- GET `/dishes/**`、GET `/setmeals/**`：公开静态图片。
- `/internal/**`：独立 `X-Internal-Token`，网关禁止外部访问。
- `/admin/**`：员工认证和数据库 RBAC；`/user/**`：用户认证，原有业务数据归属校验保留。
- OPTIONS：跨域预检；CORS 来源必须显式配置。
- 健康检查公开；监控指标仅通过内部令牌访问；开发文档只在开发配置下开放。
- 前端无需修改请求头或登录字段。改密、封禁、退出后旧令牌应失效；部署安全改造后旧版令牌要求重新登录。

## 分阶段验收

| TODO | 验收内容 | 证据 |
| --- | --- | --- |
| 1. 接口约定 | 盘点并验证 109 条 MVC 路由，登录请求方法、回调入口、原请求头与响应字段 | `security-route-inventory.json`、账户真实登录测试、商品/交易 HTTP 测试 |
| 2. 账户迁移 | Spring Security 过滤链；原登录 VO；JWT 身份；请求结束清理上下文 | `ApiSecurityTest`、`AccountSessionTest`、`SecurityMysqlIntegrationTest` |
| 3. 商品与交易迁移 | Feign 会话校验与授权；商品读取、下单身份传递；支付回调保留自身验签；依赖失败拒绝访问 | `CatalogSecurityTest`、`TradeSecurityTest`、原业务回归 |
| 4. 收紧安全规则 | 默认拒绝；59 条管理端/通知 RBAC 路由；动态路径及优先级；Argon2id；退出、改密、禁用失效；生产配置检查 | `PermissionAccessTest`、`EmployeePasswordTest`、`ProductionSecurityGuardTest`、真实 MySQL 集成测试 |
| 5. 最终验收 | 全模块测试和打包，隔离 MySQL/Redis 回归，管理端生产构建，部署与回滚说明 | 最终结果见下方；日志存放 `.runtime/security-*.log` |

账户集成测试使用随机命名的独立数据库，覆盖完整过滤链、Controller、MyBatis 和 Flyway，完成后清理，不修改现有业务库。商品/交易 HTTP 测试使用实际 Controller 和过滤链，业务依赖使用替身；原业务测试另行验证订单、库存、红包等逻辑。真实微信登录与商户支付链未在本地向微信发起交易。

初始基线发现 JWT 工具对不可修改 Map 的处理导致原网关测试失败，已改为复制 claims；商品原缓存测试存在旧方法签名，已更新其调用。账户 `UserMapper.getById` 原来缺少 SQL 映射，真实集成测试暴露后已补齐。前端构建存在可选 fibers 提示，但最终构建成功。

扩大回归时发现原订单可靠性集成测试仍使用已迁移的菜品/套餐库存模型，并将当前库存服务替换为空 mock，已更新测试夹具为真实交易库存服务和交易库存表。Windows 测试 JVM 的默认编码导致 Lua 中文错误码乱码，已固定测试 JVM 为 UTF-8，并增加脚本及 Redis 返回值的编码断言；生产 JVM 同样建议使用 `-Dfile.encoding=UTF-8`。

缓存集成测试的旧键名也已更新为当前版本化缓存键，并修复已中断线程在版本查询前仍访问 Redis 的问题。16 项真实 Redis 缓存测试通过。

## 最终结果（2026-10-13）

- 全模块 Maven `package` 成功，五个服务均生成可运行构建物。
- 汇总 159 个测试：155 个通过，失败 0、错误 0；4 个门控场景未执行（3 个真实 RabbitMQ 场景和 1 个 15 分钟 TTL 场景）。完整数据见 `security-test-results.json`。
- 真实 MySQL：账户安全集成、订单可靠性集成和秒杀持久化集成通过；均使用隔离数据库。
- 真实 Redis：秒杀库存/并发和菜单缓存互斥集成通过；使用独立的本地临时实例，结束后已关闭。
- 管理端生产构建完成，前端请求头与登录返回结构保持兼容。
- 最终构建证据：`.runtime/security-release-acceptance.log`；前端证据：`.runtime/security-frontend.log`。

复现后端验收（需要 MySQL，以及指定端口上的独立 Redis；MySQL 账号需有创建/清理测试库权限）：

```text
mvn package -Dsecurity.mysql.integration=true -Dseckill.mysql.integration=true -Dseckill.integration=true -Dorder.integration=true -Dseckill.redis.port=16389 -Dstep2.redis.port=16389
```

账户测试库密码可用 `SECURITY_TEST_DB_PASSWORD` 环境变量覆盖；原订单/秒杀数据库集成读取开发配置中的数据库连接。常规 `mvn test` 会跳过未显式开启的外部集成场景，不可将跳过视为通过。

## 行为变化与配置

- 业务服务使用共享 `sky-security` 模块。旧 JWT 拦截器保留源码但不注册，认证与授权由 `SecurityFilterChain` 完成。
- 管理端权限按最具体的路由模板匹配，不再对未知接口放行。角色和员工都必须处于启用状态。
- Flyway 创建 `SECURITY_ADMIN` 和 `SECURITY_READONLY` 角色，初始化明确权限。仅为原有用户名 `admin` 绑定管理员角色，其余员工不自动获得权限。上线前确认这个账号的身份；如果原管理员使用其他用户名，需明确绑定角色。
- 密码迁移在账户服务启动时执行：明文转 Argon2id；无法还原的旧 MD5 使用 Argon2id 包裹，首次成功登录再转为原密码的直接 Argon2id。32 位十六进制旧值按 MD5 解释，若实际是此格式明文，需要重置。建议尽快完成旧账号改密。
- 新建员工的初始化密码由 `SKY_EMPLOYEE_INITIAL_PASSWORD` 指定，生产至少 12 字符；不提供时创建接口返回业务错误。开发配置仍兼容原初始化密码。初始化密码需通过受控渠道交付，并在首次使用后修改。
- 新令牌含 `jti`，员工令牌含凭据版本 `av`。退出持久化撤销记录，修改密码/状态递增版本，后续请求立即拒绝旧令牌；重新启用不会恢复旧令牌。部署后旧版令牌需重新登录。
- 默认启动配置为 `prod`；本地开发必须显式使用 `--spring.profiles.active=dev`。生产拒绝弱密钥、相同管理端/用户端密钥、模拟登录/支付、公开文档和通配 CORS。
- 管理端改密成功后前端清理 token 并跳转登录；输入规则与后端的 8–128 字符限制一致。
- 通知服务在 WebSocket 升级前校验会话和角色权限。保留浏览器查询参数 token 协议，并在推送前/每 15 秒复查会话。通知连接使用浏览器自己的查询 token；只带自定义请求头的非浏览器连接不保证长期保留。生产使用 WSS，网关日志不得记录带 token 的查询字符串。
- 账户服务是会话与授权的唯一依据，商品/交易每个受保护请求增加内部校验调用。依赖失败拒绝访问，安全性优先，账户服务故障会影响业务可用性。需要根据真实负载评估容量，不将旧秒杀压测结果视为此次鉴权性能结论。

## 部署与回滚

1. 部署前备份账户库和当前前后端构建物，保存旧配置；停止入口流量或安排维护窗口，避免新旧服务混跑。
2. 准备独立随机的 `SKY_JWT_ADMIN_SECRET`、`SKY_JWT_USER_SECRET`、`SKY_INTERNAL_TOKEN`，各至少 32 字节，实际用密码学随机生成，生产检查只能排除明显弱值，不能证明随机性。所有服务使用相同的对应密钥。显式配置 `SKY_CORS_ORIGINS`（逗号分隔的实际前端来源），生产 profile，不开启模拟功能。
3. 配置数据库、Redis、RabbitMQ、OSS 和微信真实参数。生产基础配置支持 `SKY_DB_*`、`SKY_ACCOUNT_DB`、`SKY_CATALOG_DB`、`SKY_TRADE_DB`、`SKY_REDIS_*`、`SKY_RABBIT_*`、`SKY_OSS_*`、`SKY_WECHAT_*`。通知服务通过服务发现查询账户服务，也可配置 `sky.security.account-url`。
4. 先启动账户服务完成 Flyway 与密码迁移，核对员工角色；再启动商品、交易、通知和网关，发布管理端构建。内部端口仅对服务网络开放，公网入口启用 HTTPS/WSS。业务服务监控指标通过内部网络和内部令牌访问。
5. 用管理员、只读员工、普通用户分别回归登录、菜单、下单、支付通知、地址和通知；验证 401/403、数据归属、退出及禁用。通过后恢复流量。

绑定角色示例（替换为已确认的员工 ID，不批量赋权）：

```sql
INSERT INTO employee_role(employee_id, role_id)
SELECT 目标员工ID, id FROM role WHERE code = 'SECURITY_READONLY';
```

如果必须回退原版：停止写入并恢复配套账户库备份、旧服务构建和旧配置，再恢复旧前端。**不能只回退服务二进制**：原版不认识新密码哈希及会话版本。回滚备份会丢失备份后的账户变更，因此需要维护窗口与恢复核对。不要在已迁移数据库上修改既有 Flyway 文件；后续权限调整使用新增迁移。

尚未执行生产部署，源码/本地验收不代表已经完成生产环境全量安全审计。
