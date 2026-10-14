INSERT INTO permission(code,name,path,method,type) VALUES
('GET:/admin/order/runtime/backlog','查看交易积压','/admin/order/runtime/backlog','GET','API'),
('POST:/admin/order/runtime/archive','归档交易事件','/admin/order/runtime/archive','POST','API'),
('POST:/admin/order/runtime/retire/{activityId}','退役活动扫描','/admin/order/runtime/retire/{activityId}','POST','API'),
('POST:/admin/order/runtime/replay','审计重放交易任务','/admin/order/runtime/replay','POST','API')
ON DUPLICATE KEY UPDATE name=VALUES(name);
INSERT IGNORE INTO role_permission(role_id,permission_id)
SELECT r.id,p.id FROM role r JOIN permission p ON p.code IN
('GET:/admin/order/runtime/backlog','POST:/admin/order/runtime/archive','POST:/admin/order/runtime/retire/{activityId}','POST:/admin/order/runtime/replay') WHERE r.code='SECURITY_ADMIN';
