"""Export literal Spring MVC routes from the account, catalog and trade services."""

from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
sources = [
    ("account-service", root / "account-service/src/main/java/com/sky/controller"),
    ("catalog-service", root / "catalog-service/src/main/java/com/sky/controller"),
    ("trade-service", root / "trade-service/src/main/java/com/sky/controller"),
]
mapping = re.compile(r'@(?P<method>Request|Get|Post|Put|Delete|Patch)Mapping(?:\((?P<args>[^)]*)\))?')
literal = re.compile(r'"([^"]+)"')
rows = []

for service, directory in sources:
    for source in sorted(directory.rglob("*.java")):
        lines = source.read_text(encoding="utf-8").splitlines()
        matches = [(number, match) for number, line in enumerate(lines, 1)
                   if (match := mapping.search(line))]
        base = ""
        if matches and matches[0][1].group("method") == "Request":
            values = literal.findall(matches[0][1].group("args") or "")
            if values:
                base = values[0]
                matches = matches[1:]
        for number, match in matches:
            method = match.group("method").upper()
            args = match.group("args") or ""
            paths = literal.findall(args) or [""]
            if method == "REQUEST":
                found = re.search(r"RequestMethod\.(\w+)", args)
                method = found.group(1) if found else "ANY"
            for path in paths:
                full = (base.rstrip("/") + "/" + path.lstrip("/")).rstrip("/") or "/"
                if full == "/admin/employee/login" or full == "/user/user/login":
                    auth = "公开"
                elif full in ("/user/shop/status", "/user/seckill/activity/list"):
                    auth = "公开"
                elif full.startswith("/admin/"):
                    auth = "员工 token"
                elif full.startswith("/user/"):
                    auth = "用户 authentication"
                else:
                    auth = "按原接口"
                rows.append((service, method, full, auth, source.relative_to(root).as_posix(), number))

output = [
    "# 微服务公开接口清单",
    "",
    "> 由 `python tools/export_routes.py` 从当前 Controller 注解生成。响应结构沿用各 Controller 和原有 `Result`/VO；动态路由、请求体及支付签名仍应在联调中核对。",
    "",
    "| 服务 | 方法 | 路径 | 认证 | 源码 |",
    "| --- | --- | --- | --- | --- |",
]
for service, method, path, auth, file, number in sorted(rows, key=lambda row: row[:3]):
    output.append(f"| {service} | {method} | `{path}` | {auth} | [{Path(file).name}](../{file}#L{number}) |")
output += [
    "", "## 非 MVC 路径", "",
    "- `GET /ws/{sid}`：通知服务 WebSocket 握手，网关使用独立 `lb:ws://notification-service` 路由。",
    "- `/actuator/health`：各服务自己的健康检查，默认不通过业务路由公开。",
    "", f"本次扫描得到 {len(rows)} 条 MVC 路由。",
]
(root / "docs/五服务接口清单.md").write_text("\n".join(output) + "\n", encoding="utf-8")
print(f"exported {len(rows)} MVC routes")
