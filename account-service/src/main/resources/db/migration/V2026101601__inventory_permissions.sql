INSERT INTO permission(code,name,path,method,type) VALUES
('POST:/admin/inventory/adjust','调整交易库存','/admin/inventory/adjust','POST','API'),
('POST:/admin/seckill/activity/{activityId}/inventory-return','回收秒杀库存额度','/admin/seckill/activity/{activityId}/inventory-return','POST','API')
ON DUPLICATE KEY UPDATE name=VALUES(name);
INSERT IGNORE INTO role_permission(role_id,permission_id)
SELECT r.id,p.id FROM role r JOIN permission p ON p.code IN ('POST:/admin/inventory/adjust','POST:/admin/seckill/activity/{activityId}/inventory-return') WHERE r.code='SECURITY_ADMIN';
