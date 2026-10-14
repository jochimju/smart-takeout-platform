# 支付配置与验收

本地仍使用 `dev`：模拟支付默认开启，支付查单与账单下载默认关闭，不需要微信商户号。真实支付失败不会自动转为模拟到账。以下工作已经落到源码，但没有商户凭据就不能完成真实扣款、退款及真机收银台联调，也不能据此宣布已经具备完整上线条件。

## 本次完成的五项工作

1. 微信请求签名、响应验签及回调验签共用信任配置；支持多个平台证书、支付公钥及 SDK 自动更新平台证书。回调验证时间窗口，解密验证 AES-GCM 完整性，金额使用精确的整数分。
2. 真实预支付请求前持久化查单任务，网络结果未知、进程重启和回调丢失都可恢复。取消/超时登记关单任务；关单期间支付成功则重新查单进入统一到账事务。
3. 普通订单及红包购买共用到账流水。红包过期/关闭后到账进入待退款，保持不发资产、不重复释放预算；退款使用固定退款号，先查再申请，渠道确认成功后才完成本地退款。
4. 下载前一日账单，验证签名元数据和文件摘要；核对商户、AppID、日期、币种、交易号、订单金额、退款及双向漏单。差异落库，重复账单幂等，同日支付和退款可以同时存在。手动上传只生成核对报告，不能据此自动改变资金状态。
5. 添加生产配置启动检查和六个管理接口的 RBAC 权限。失败查单任务可由管理员核查后重放；配置缺失不会让生产服务带着空商户参数继续接单。

## 环境变量

| 参数 | 真实环境配置 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod`；使用现有生产 JWT、数据库、Redis、MQ、内部服务密钥配置 |
| `SKY_PAYMENT_MOCK_ENABLED` | `false` |
| `SKY_PAYMENT_RECOVERY_ENABLED` | `true` |
| `SKY_PAYMENT_RECONCILIATION_ENABLED` | `true` |
| `SKY_WECHAT_APPID` / `SKY_WECHAT_SECRET` | 真实小程序配置；AppID 与商户支付关系已经绑定 |
| `SKY_WECHAT_MCHID` | 微信支付商户号 |
| `SKY_WECHAT_MCH_SERIAL_NO` | 商户 API 证书序列号，不能填成平台证书序列号或支付公钥 ID |
| `SKY_WECHAT_PRIVATE_KEY_PATH` | 可读的商户 PKCS#8 PEM 私钥文件绝对路径；只读挂载，禁止提交仓库 |
| `SKY_WECHAT_API_V3_KEY` | 32 字节 API v3 密钥；通过环境/密钥管理注入 |
| `SKY_WECHAT_NOTIFY_URL` | 公网 HTTPS 地址，路径 `/notify/paySuccess`，需要网关转发到交易服务 |
| `SKY_WECHAT_REFUND_NOTIFY_URL` | 可留空：退款通过主动查询确认；本项目没有退款回调控制器，不能填写未经实现的地址 |

信任材料选择与商户当前验签模式一致的一种：

- 支付公钥模式：设置 `SKY_WECHAT_PUBLIC_KEY_ID`、`SKY_WECHAT_PUBLIC_KEY_PATH`，并设置 `SKY_WECHAT_AUTO_UPDATE_CERTIFICATES=false`。需要公钥轮换时通过 `sky.wechat.public-key-paths` 配置多个真实 ID 到 PEM 路径的映射，新旧并存后再移除旧材料。
- 平台证书模式：设置 `SKY_WECHAT_AUTO_UPDATE_CERTIFICATES=true`，SDK 负责更新；也可用 `SKY_WECHAT_PLATFORM_CERTIFICATE_PATHS` 提供逗号分隔的证书路径，兼容旧变量 `SKY_WECHAT_PAY_CERT_PATH`。测试覆盖本地新旧证书切换，自动下载证书尚未使用真实商户验证。

生产只能使用官方 HTTPS 支付接口；不能配置测试替身地址。启动检查验证必要参数、文件可读性和 HTTPS 回调形式，不能证明商户号有效、证书私钥匹配、商户余额充足或公网回调可达。这些必须在有商户配置后验证。

交易服务启动时 Flyway 执行新增 `V2026101502__payment_recovery.sql`；账户服务执行 `V2026101501__payment_operations_permissions.sql`。迁移编号排在已有迁移之后，不能改写已应用迁移。历史支付流水缺少渠道支付时间时不会猜测日期；经查单或有效回调补齐后才能参加按渠道日期的双向对账。历史红包重复有效通知可补齐公共流水，但不会重复发资产。

## 运维入口

请求通过现有管理端 `token` 请求头认证。

| 方法及路径 | 用途/参数 |
| --- | --- |
| `GET /admin/payment/recovery/failures` | 最近 100 条失败查单/关单任务 |
| `POST /admin/payment/recovery/replay` | `id=check:订单号` 或 `close:订单号`；仅重置 FAILED 任务 |
| `POST /admin/payment/reconciliation/download` | `date=YYYY-MM-DD`；主动补下载指定日期 |
| `POST /admin/payment/reconciliation/import` | multipart 参数 `date`、`file`；最大 8 MiB |
| `GET /admin/payment/reconciliation/runs` | 最近 100 条核对结果 |
| `GET /admin/payment/reconciliation/differences` | `runId`；最多 1000 条差异 |

`SECURITY_ADMIN` 获得六项权限，`SECURITY_READONLY` 只有三个 GET 权限；其他角色需要显式授权。管理界面未新增页面，可通过上述受保护接口使用。

查单任务每秒扫描、等待查单间隔 30 秒，租期两分钟，连续失败累计 10 次进入 FAILED；重放前先核实商户与订单，禁止直接改数据库为已付款。退款任务继续使用已有持久化任务系统。对账每天上海时间 10:15 至 23:15 每小时尝试，成功下载后停止当天重复下载；服务跨日停机后的漏账需按日期补下载。

应把支付恢复失败、退款任务失败、账单下载失败及差异日志接入实际部署环境的告警平台，并安排负责人处理。当前提供持久化记录和错误日志，没有接通外部短信、邮件或值班平台。导入账单来源为 `UPLOAD`，微信签名元数据校验后的下载来源为 `CHANNEL_VERIFIED`，二者不能等同。

## 验收证据与上线边界

自动化使用临时 RSA 密钥、平台证书、AES-GCM 报文、本地 HTTP 接口替身以及一次性 MySQL 数据库；真实 SDK 的请求签名与响应验签保持启用。测试服务器采用仅限测试客户端的 DNS 映射，不修改系统 hosts，也不发送真实微信商户请求。

支付专项覆盖正常到账、重复回调、丢回调查单、关单竞态、过期关单、租期恢复、错误重试与重放、金额不符、红包迟到退款、退款处理中、预算失败事务回滚、过期购买关单与预算释放的原子性、历史流水修复、重复账单、跨日期账单、同日支付加退款、管理权限及生产配置检查。最终相关后端验收 40 项通过、4 项条件用例跳过，其中包含 13 项真实 MySQL 支付场景；前端 27 项通过。显式排除已知秒杀失败用例的更广回归为 186 项通过、4 项跳过；最后调整迁移编号和补充预算原子性检查后，重新执行了受影响的支付及订单用例并打包成功。测试结果见 `payment-test-results.json`。

全项目回归发现既有秒杀批量接管的并发测试 `batchPersistsOneOutboxPerRequestEvenWithConcurrentRepeatedHandoffs` 重复主键失败，已经连续复现两次；该路径不在本次支付改动范围，未修改。保留失败日志 `.runtime/payment-full-regression-with-seckill-failure.log`，其余回归采用显式排除该用例执行并记录，不能描述为“无条件全量通过”。RabbitMQ/15 分钟 TTL 的四个条件用例未开启。

上线前仍需实际商户下单、回调、查单、关单、真实退款、证书更新、公网回调以及真实账单核对的联调记录；同时完成告警接入与部署验证。没有商户号时可完成上述代码与本地协议/业务测试，不能替代真实支付验收。

协议依据：[微信支付小程序开发指引](https://pay.wechatpay.cn/doc/v3/merchant/4012791911)、[下载账单说明](https://pay.wechatpay.cn/doc/v3/merchant/4013071215)、[支付回调和查单指引](https://pay.wechatpay.cn/doc/v3/merchant/4012075249)。
