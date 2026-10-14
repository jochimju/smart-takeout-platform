"""Export security route inventory and generate an explicit RBAC seed migration."""
from pathlib import Path
import re, json
root = Path(__file__).resolve().parents[1]
rows = []
pattern = re.compile(r'@(Request|Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?')
for module in ['account-service', 'catalog-service', 'trade-service']:
    for source in sorted((root / module / 'src/main/java/com/sky/controller').rglob('*.java')):
        matches = list(pattern.finditer(source.read_text(encoding='utf-8')))
        base = ''
        if matches and matches[0][1] == 'Request':
            base = re.findall(r'"([^"]*)"', matches.pop(0)[2] or '')[0]
        for match in matches:
            method = match[1].upper()
            for route in re.findall(r'"([^"]*)"', match[2] or '') or ['']:
                path = (base.rstrip('/') + '/' + route.lstrip('/')).rstrip('/') or '/'
                rows.append(dict(service=module, method=method, path=path))
assert any(r['method']=='POST' and r['path']=='/admin/employee/login' for r in rows)
assert any(r['method']=='POST' and r['path']=='/user/user/login' for r in rows)
assert any(r['method']=='POST' and r['path']=='/notify/paySuccess' for r in rows)
(root / 'docs/security-route-inventory.json').write_text(json.dumps(rows, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
admin = [r for r in rows if r['path'].startswith('/admin/') and r['path'] not in ['/admin/employee/login','/admin/employee/logout','/admin/employee/editPassword']]
admin += [dict(method='GET',path='/ws/{sid}')]
sql = ["-- Explicit routes only. Never assign roles to all employees.",
       "INSERT INTO role(code,name,status) VALUES ('SECURITY_ADMIN','系统管理员',1),('SECURITY_READONLY','管理端只读',1) ON DUPLICATE KEY UPDATE code=VALUES(code);"]
for row in admin:
    method, path = row['method'], row['path']
    assert method in ['GET','POST','PUT','DELETE','PATCH'], row
    code = method+':'+path
    sql.append(f"INSERT INTO permission(code,name,path,method,type) VALUES ('{code}','{code}','{path}','{method}','API') ON DUPLICATE KEY UPDATE name=VALUES(name);")
    sql.append(f"INSERT IGNORE INTO role_permission(role_id,permission_id) SELECT r.id,p.id FROM role r JOIN permission p ON p.method='{method}' AND p.path='{path}' WHERE r.code='SECURITY_ADMIN';")
    if method=='GET':
        sql.append(f"INSERT IGNORE INTO role_permission(role_id,permission_id) SELECT r.id,p.id FROM role r JOIN permission p ON p.method='{method}' AND p.path='{path}' WHERE r.code='SECURITY_READONLY';")
sql += ["-- Preserve the existing named administrator's management access, leave all other employees unassigned.",
        "INSERT IGNORE INTO employee_role(employee_id,role_id) SELECT e.id,r.id FROM employee e JOIN role r ON r.code='SECURITY_ADMIN' WHERE e.username='admin';"]
(root/'account-service/src/main/resources/db/migration/V2026101402__security_permissions.sql').write_text('\n'.join(sql)+'\n',encoding='utf-8')
print(f'Validated {len(rows)} MVC routes; seeded {len(admin)} RBAC routes')
