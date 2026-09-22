# Feign + Sentinel 压测与熔断验证

## 实现边界

`trade-service` 与 `catalog-service` 已接入 Sentinel，并为所有 Feign 客户端配置了 `FallbackFactory`。连接超时为 1 秒；读取超时按调用重要性设置为 1.5 至 2.5 秒。Feign 自动重试已关闭，避免下游异常时将单次请求放大为多次流量。

降级策略不是一律返回默认值：

| 调用 | 降级行为 | 原因 |
| --- | --- | --- |
| 权限校验 | 返回 `false` | 必须拒绝，不能放行越权请求 |
| 下单的地址、菜单、报价 | 返回业务失败 | 不得以陈旧地址或价格继续下单 |
| 餐厅删除前订单检查 | 返回业务失败 | 不能在无法确认订单关联时删除餐厅 |
| 报表汇总 | 返回业务失败 | 不把“服务不可用”伪造成零数据 |

## 规则发布

规则文件在 `tools/sentinel-rules/`，并通过 Nacos 数据源热更新。规则使用 Sentinel 的 Feign 资源名格式：`HTTP_METHOD:http://服务名/接口路径`。

```powershell
.\tools\publish-sentinel-rules.ps1 -Username nacos -Password nacos
```

若使用非默认 Nacos 地址、命名空间或分组，请同时传入 `-ServerAddr`、`-Namespace`、`-Group`。发布后可在 Nacos 的配置列表确认四个 `*-sentinel-*.json` Data ID；服务无需重启。

当前演示阈值刻意较低：读调用 20 至 80 QPS；当 10 秒统计窗口内至少有 5 次请求、异常比例达到 50% 时熔断 10 秒。生产值必须根据压测容量与错误预算重新设定，不能直接照搬。

## 压测与恢复验证

1. 启动 Nacos、五个服务，并确认 `account-service` 正常。用有效管理端 token 配置环境变量：

```powershell
$env:SKY_ADMIN_TOKEN = '替换为有效管理端JWT'
$env:SKY_LOAD_URL = 'http://localhost:8080/admin/workspace/businessData'
k6 run .\tools\sentinel-load.js
```

2. 正常情况下，请求应主要计入 `application_success`，Prometheus 中可查看 `http_client_requests_seconds_*` 与 `http_server_requests_seconds_*`。
3. 停止 `account-service`，再次执行脚本。达到最小请求量后，交易服务对账户服务的 Feign 调用应被熔断或走 fallback：响应是受控业务失败或拒绝，不应出现 5xx，也不应放行权限校验。
4. 恢复 `account-service`，等待超过 10 秒熔断窗口，再以 10 QPS 执行脚本。连续成功请求表明半开探测和闭合恢复正常。
5. 在 Sentinel Dashboard 或应用日志中确认资源的 `pass/block/exception` 变化；在 Grafana 中确认压测期间请求速率、P95 与错误曲线。

本机未安装 Maven、Docker 或 k6，故本次未执行编译和真实压测。规则 JSON 与 k6 脚本均已做静态语法校验；真实验证必须在具备 Nacos、下游服务及有效 token 的环境完成。
