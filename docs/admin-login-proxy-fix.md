# 管理端登录 403 修复

2026-10-14，本地管理端 `http://localhost/#/login` 点击登录后停留在原页。

实际请求返回 HTTP 403、`Invalid CORS request`。同一账号的无 Origin 接口请求成功，因此并非账号密码错误。Nginx 转发到网关、网关再转发到 account 时使用了后端 Host；浏览器的 Origin 仍为 `http://localhost`，域服务将同源请求误判为跨域，而默认 CORS 来源列表为空。

修复：Nginx 使用 `proxy_set_header Host $http_host`；Gateway 默认过滤器使用 `PreserveHostHeader`，两跳均保留浏览器面对的主机名。本地配置已加载，网关已重启，账号/交易/商品服务未重启。参考配置位于 `deploy/nginx/admin-local.conf`。

验证：5 项网关测试通过；Nginx 配置校验通过；实际浏览器进入 `#/dashboard`，工作台数据加载正常；登录和受保护业务请求返回 200。不匹配的 Origin 仍返回 403，正确的 `127.0.0.1` Host/Origin 组合也能登录。未关闭 CORS、鉴权或 RBAC。

这是本地 HTTP 同源反向代理修复。生产 TLS 终止场景仍应正确配置可信代理的协议转发；若前后端确实部署在不同来源，需显式配置允许来源，不能使用通配符替代来源校验。
