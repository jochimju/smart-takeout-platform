# Gateway 统一认证与权限

## 安全边界

Gateway 现在是公开 HTTP 和 WebSocket 流量的第一道边界：

| 路径 | 网关行为 | 服务侧行为 |
| --- | --- | --- |
| `/admin/**` | 验证管理端 `token`，要求 `empId` 声明 | 再验 JWT，并向账户服务校验细粒度权限 |
| `/user/**` | 验证用户端 `authentication`，要求 `userId` 声明 | 再验 JWT 和业务归属 |
| `/ws/**` | 按用户 JWT 验证 | 通知服务握手再次验 JWT |
| `/internal/**` | 直接拒绝 | account/catalog/trade 服务要求 `X-Internal-Token` |
| 登录、店铺状态、秒杀列表、支付回调 | 明确白名单 | 支付回调仍在交易服务验证微信签名 |

网关会删除外部伪造的 `X-Authenticated-Id`、`X-Authenticated-Role`，再根据已验签 JWT 写入同名身份头。这些头仅供可观测性或内部辅助使用；服务端的 JWT 与权限校验才是最终授权依据。

## WebSocket 兼容性

WebSocket 不再允许匿名连接。优先在握手请求中携带 `authentication` 用户 token。浏览器原生 WebSocket 无法自定义请求头时，可临时设置 `SKY_GATEWAY_ALLOW_WEBSOCKET_QUERY_TOKEN=true`，使用 `?authentication=<JWT>`；此模式会让 token 出现在 URL 中，生产环境应优先改为 cookie 或受控子协议方案，而非长期启用。

## 验收

1. `GET /admin/order/page` 无 token 应返回 401；携带用户 token 也应返回 401。
2. 携带有效管理端 token 通过 Gateway 时，后端会收到 `X-Authenticated-Id` 和 `X-Authenticated-Role: ADMIN`，但仍执行服务端权限校验。
3. 直接访问 `http://localhost:8081/internal/trade/orders/count` 或 `http://localhost:8083/internal/catalog/overview`，未携带正确 `X-Internal-Token` 应返回 403。
4. `ws://localhost:8080/ws/{sid}` 无用户 token 应在 Gateway 返回 401；绕过 Gateway 直连通知服务也会被服务端以 1008 策略关闭。

## 仍需处理的风险

当前通知服务以广播方式向所有已连接会话推送订单事件；认证只能防匿名连接，不能保证每名用户只收到自己的事件。若通知包含订单号、地址或状态等敏感信息，下一步必须按用户/员工身份维护会话并定向投递，不能继续全量广播。
