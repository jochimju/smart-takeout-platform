-- The historical seckill activities have expired. Seed one active activity
-- for each restaurant so the client can expose the dedicated seckill entry.
START TRANSACTION;

INSERT INTO seckill_activity
    (setmeal_id, stock, remaining_stock, stock_version, purchase_limit, seckill_price, begin_time, end_time, status, create_time, update_time)
SELECT sm.id, x.stock, x.stock, 0, 1, x.seckill_price,
       DATE_SUB(NOW(), INTERVAL 5 MINUTE), DATE_ADD(NOW(), INTERVAL 7 DAY), 1, NOW(), NOW()
FROM setmeal sm
JOIN canteen c ON c.id = sm.canteen_id
JOIN (
    SELECT 'CANTEEN_ONE' code, '热销套餐' setmeal_suffix, 30 stock, 14.90 seckill_price
    UNION ALL SELECT 'CANTEEN_TWO', '热销套餐', 30, 15.90
    UNION ALL SELECT 'CANTEEN_MEIHUA', '热销套餐', 25, 15.90
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '热销套餐', 25, 17.90
    UNION ALL SELECT 'CANTEEN_FIVE', '热销套餐', 30, 14.90
    UNION ALL SELECT 'CANTEEN_SIX', '热销套餐', 30, 12.90
) x ON x.code = c.code AND sm.name = CONCAT(c.name, x.setmeal_suffix)
WHERE NOT EXISTS (
    SELECT 1 FROM seckill_activity a
    WHERE a.setmeal_id = sm.id AND a.status = 1 AND a.end_time >= NOW()
);

COMMIT;
