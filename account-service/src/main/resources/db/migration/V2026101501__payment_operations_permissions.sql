-- Explicit payment operations permissions. Grant write operations only to existing SECURITY_ADMIN.
INSERT INTO permission(code,name,path,method,type) VALUES
('GET:/admin/payment/recovery/failures','支付恢复失败任务','/admin/payment/recovery/failures','GET','API'),
('POST:/admin/payment/recovery/replay','支付恢复任务重放','/admin/payment/recovery/replay','POST','API'),
('POST:/admin/payment/reconciliation/download','下载核对微信账单','/admin/payment/reconciliation/download','POST','API'),
('POST:/admin/payment/reconciliation/import','导入核对账单','/admin/payment/reconciliation/import','POST','API'),
('GET:/admin/payment/reconciliation/runs','查看账单核对记录','/admin/payment/reconciliation/runs','GET','API'),
('GET:/admin/payment/reconciliation/differences','查看账单差异','/admin/payment/reconciliation/differences','GET','API')
ON DUPLICATE KEY UPDATE name=VALUES(name);
INSERT IGNORE INTO role_permission(role_id,permission_id)
SELECT r.id,p.id FROM role r JOIN permission p ON p.path LIKE '/admin/payment/%' WHERE r.code='SECURITY_ADMIN';
INSERT IGNORE INTO role_permission(role_id,permission_id)
SELECT r.id,p.id FROM role r JOIN permission p ON p.path LIKE '/admin/payment/%' AND p.method='GET' WHERE r.code='SECURITY_READONLY';
