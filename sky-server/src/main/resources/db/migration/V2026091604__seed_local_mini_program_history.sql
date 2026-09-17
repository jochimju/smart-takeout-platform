-- The local mock-login account is separate from historical demo accounts.
-- Give it its own address and completed-order samples for client-side testing.
START TRANSACTION;

INSERT INTO address_book (user_id, consignee, sex, phone, province_name, city_name, district_name, detail, label, is_default)
SELECT u.id, '本地演示用户', '0', '13800000000', '浙江省', '杭州市', '钱塘区', '杭州电子科技大学下沙校区生活区 8 栋 302', '3', 1
FROM user u
WHERE u.openid = 'local-mini-program-user'
  AND NOT EXISTS (SELECT 1 FROM address_book a WHERE a.user_id = u.id);

INSERT INTO orders (number, status, user_id, address_book_id, order_time, checkout_time, pay_method, pay_status, amount,
                    remark, phone, address, user_name, consignee, delivery_status, delivery_time, pack_amount,
                    tableware_number, tableware_status, order_type, pickup_status, user_deleted, canteen_name)
SELECT x.number, 5, u.id, a.id, x.order_time, x.order_time, 1, 1, x.amount,
       '本地演示历史订单', a.phone, CONCAT(a.province_name, a.city_name, a.district_name, a.detail),
       '本地演示用户', a.consignee, 1, x.order_time, 1, 1, 1, 'DELIVERY', 'PICKED_UP', 0, '一餐'
FROM user u
JOIN address_book a ON a.user_id = u.id AND a.is_default = 1
JOIN (
    SELECT 'LOCAL-DEMO-ORDER-001' number, 18.50 amount, DATE_SUB(NOW(), INTERVAL 3 DAY) order_time
    UNION ALL SELECT 'LOCAL-DEMO-ORDER-002', 20.00, DATE_SUB(NOW(), INTERVAL 6 DAY)
    UNION ALL SELECT 'LOCAL-DEMO-ORDER-003', 19.00, DATE_SUB(NOW(), INTERVAL 9 DAY)
) x
WHERE u.openid = 'local-mini-program-user'
  AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.number = x.number);

INSERT INTO order_detail (name, image, order_id, setmeal_id, number, amount)
SELECT sm.name, sm.image, o.id, sm.id, 1, o.amount
FROM orders o
JOIN setmeal sm ON sm.name = CASE o.number
    WHEN 'LOCAL-DEMO-ORDER-001' THEN '一餐热销套餐'
    WHEN 'LOCAL-DEMO-ORDER-002' THEN '二餐热销套餐'
    WHEN 'LOCAL-DEMO-ORDER-003' THEN '五餐热销套餐'
END
WHERE o.number IN ('LOCAL-DEMO-ORDER-001', 'LOCAL-DEMO-ORDER-002', 'LOCAL-DEMO-ORDER-003')
  AND NOT EXISTS (SELECT 1 FROM order_detail od WHERE od.order_id = o.id);

COMMIT;
