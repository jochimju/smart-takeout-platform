-- Give every enabled restaurant an independent, usable menu instead of the
-- placeholder dishes introduced with the multi-canteen migration.
START TRANSACTION;

INSERT INTO category (type, name, sort, status, canteen_id, create_time, update_time, create_user, update_user)
SELECT 1, CONCAT(c.name, '特色主食'), 3, 1, c.id, NOW(), NOW(), 1, 1
FROM canteen c
WHERE c.code IN ('CANTEEN_ONE', 'CANTEEN_TWO', 'CANTEEN_MEIHUA', 'CANTEEN_FOOD_CITY', 'CANTEEN_FIVE', 'CANTEEN_SIX');

INSERT INTO category (type, name, sort, status, canteen_id, create_time, update_time, create_user, update_user)
SELECT 1, CONCAT(c.name, '小食饮品'), 4, 1, c.id, NOW(), NOW(), 1, 1
FROM canteen c
WHERE c.code IN ('CANTEEN_ONE', 'CANTEEN_TWO', 'CANTEEN_MEIHUA', 'CANTEEN_FOOD_CITY', 'CANTEEN_FIVE', 'CANTEEN_SIX');

INSERT INTO category (type, name, sort, status, canteen_id, create_time, update_time, create_user, update_user)
SELECT 2, CONCAT(c.name, '超值套餐'), 1, 1, c.id, NOW(), NOW(), 1, 1
FROM canteen c
WHERE c.code IN ('CANTEEN_ONE', 'CANTEEN_TWO', 'CANTEEN_MEIHUA', 'CANTEEN_FOOD_CITY', 'CANTEEN_FIVE', 'CANTEEN_SIX');

INSERT INTO dish (name, category_id, canteen_id, price, image, description, status, stock, create_time, update_time, create_user, update_user, is_recommended, campus_tag)
SELECT x.dish_name, cg.id, c.id, x.price, NULL, x.description, 1, x.stock, NOW(), NOW(), 1, 1, x.is_recommended, x.campus_tag
FROM canteen c
JOIN (
    SELECT 'CANTEEN_ONE' code, '红烧肉盖饭' dish_name, 17.00 price, '肥瘦相间红烧肉，搭配时蔬和米饭' description, 60 stock, 1 is_recommended, '午餐,晚餐' campus_tag, '特色主食' category_suffix
    UNION ALL SELECT 'CANTEEN_ONE', '黑椒鸡排饭', 16.00, '现煎鸡排配黑椒酱和时蔬', 60, 1, '午餐,晚餐', '特色主食'
    UNION ALL SELECT 'CANTEEN_ONE', '冰豆浆', 3.00, '现磨豆浆，冰饮更清爽', 100, 0, '饮品', '小食饮品'
    UNION ALL SELECT 'CANTEEN_ONE', '五香茶叶蛋', 2.00, '慢卤入味的加餐小食', 100, 0, '早餐,小食', '小食饮品'
    UNION ALL SELECT 'CANTEEN_TWO', '黑椒牛柳饭', 18.00, '嫩牛柳搭配黑椒酱和时蔬', 50, 1, '午餐,晚餐', '特色主食'
    UNION ALL SELECT 'CANTEEN_TWO', '照烧鸡腿饭', 17.00, '照烧鸡腿配溏心蛋和米饭', 50, 1, '午餐,晚餐', '特色主食'
    UNION ALL SELECT 'CANTEEN_TWO', '鲜榨橙汁', 6.00, '新鲜橙子现榨，无额外加糖', 80, 0, '饮品', '小食饮品'
    UNION ALL SELECT 'CANTEEN_TWO', '爽口海带丝', 4.00, '微辣开胃小菜', 80, 0, '小食', '小食饮品'
    UNION ALL SELECT 'CANTEEN_MEIHUA', '藤椒鸡腿饭', 18.00, '藤椒鲜香，鸡腿肉软嫩入味', 50, 1, '午餐,晚餐', '特色主食'
    UNION ALL SELECT 'CANTEEN_MEIHUA', '梅菜扣肉饭', 19.00, '咸香梅菜搭配慢炖扣肉', 45, 1, '午餐,晚餐', '特色主食'
    UNION ALL SELECT 'CANTEEN_MEIHUA', '桂花酸梅汤', 5.00, '桂花清香，酸甜解腻', 100, 0, '饮品', '小食饮品'
    UNION ALL SELECT 'CANTEEN_MEIHUA', '脆皮薯角', 6.00, '外脆内软，现炸供应', 70, 0, '小食', '小食饮品'
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '麻辣香锅', 20.00, '荤素可选，麻辣鲜香', 45, 1, '午餐,晚餐,微辣', '特色主食'
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '韩式石锅拌饭', 18.00, '韩式辣酱配时蔬和煎蛋', 50, 1, '午餐,晚餐', '特色主食'
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '柠檬红茶', 5.00, '清新柠檬搭配醇香红茶', 100, 0, '饮品', '小食饮品'
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '黄金炸鸡块', 8.00, '外酥里嫩的热食小吃', 60, 0, '小食', '小食饮品'
    UNION ALL SELECT 'CANTEEN_FIVE', '番茄肥牛米线', 17.00, '番茄汤底搭配肥牛和米线', 55, 1, '午餐,晚餐', '特色主食'
    UNION ALL SELECT 'CANTEEN_FIVE', '菌菇鸡肉饭', 16.00, '菌菇鸡肉搭配香软米饭', 55, 1, '午餐,晚餐', '特色主食'
    UNION ALL SELECT 'CANTEEN_FIVE', '冰镇绿豆沙', 4.00, '细腻绿豆沙，清凉解暑', 100, 0, '饮品', '小食饮品'
    UNION ALL SELECT 'CANTEEN_FIVE', '香煎手抓饼', 6.00, '酥香饼皮搭配生菜和酱料', 70, 0, '早餐,小食', '小食饮品'
    UNION ALL SELECT 'CANTEEN_SIX', '川味燃面', 14.00, '麻辣鲜香，拌匀后食用', 60, 1, '午餐,晚餐,微辣', '特色主食'
    UNION ALL SELECT 'CANTEEN_SIX', '砂锅牛肉粉', 18.00, '热汤砂锅，牛肉软烂入味', 50, 1, '午餐,晚餐', '特色主食'
    UNION ALL SELECT 'CANTEEN_SIX', '红糖糍粑', 6.00, '软糯香甜的川味小吃', 80, 0, '小食', '小食饮品'
    UNION ALL SELECT 'CANTEEN_SIX', '银耳羹', 5.00, '温润清甜，晚餐加餐佳选', 80, 0, '饮品,甜品', '小食饮品'
) x ON x.code = c.code
JOIN category cg ON cg.canteen_id = c.id AND cg.name = CONCAT(c.name, x.category_suffix);

INSERT INTO setmeal (category_id, canteen_id, name, price, status, description, image, stock, create_time, update_time, create_user, update_user)
SELECT cg.id, c.id, CONCAT(c.name, x.setmeal_suffix), x.price, 1, x.description, NULL, x.stock, NOW(), NOW(), 1, 1
FROM canteen c
JOIN (
    SELECT 'CANTEEN_ONE' code, '热销套餐' setmeal_suffix, 18.50 price, '红烧肉盖饭加冰豆浆' description, 50 stock
    UNION ALL SELECT 'CANTEEN_ONE', '满足套餐', 17.00, '黑椒鸡排饭加五香茶叶蛋', 50
    UNION ALL SELECT 'CANTEEN_TWO', '热销套餐', 20.00, '黑椒牛柳饭加鲜榨橙汁', 45
    UNION ALL SELECT 'CANTEEN_TWO', '满足套餐', 19.00, '照烧鸡腿饭加爽口海带丝', 45
    UNION ALL SELECT 'CANTEEN_MEIHUA', '热销套餐', 20.00, '藤椒鸡腿饭加桂花酸梅汤', 45
    UNION ALL SELECT 'CANTEEN_MEIHUA', '满足套餐', 21.00, '梅菜扣肉饭加脆皮薯角', 40
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '热销套餐', 22.00, '麻辣香锅加柠檬红茶', 40
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '满足套餐', 21.00, '韩式石锅拌饭加黄金炸鸡块', 40
    UNION ALL SELECT 'CANTEEN_FIVE', '热销套餐', 19.00, '番茄肥牛米线加冰镇绿豆沙', 45
    UNION ALL SELECT 'CANTEEN_FIVE', '满足套餐', 19.00, '菌菇鸡肉饭加香煎手抓饼', 45
    UNION ALL SELECT 'CANTEEN_SIX', '热销套餐', 17.00, '川味燃面加红糖糍粑', 50
    UNION ALL SELECT 'CANTEEN_SIX', '满足套餐', 20.00, '砂锅牛肉粉加银耳羹', 45
) x ON x.code = c.code
JOIN category cg ON cg.canteen_id = c.id AND cg.name = CONCAT(c.name, '超值套餐');

INSERT INTO setmeal_dish (setmeal_id, dish_id, name, price, copies)
SELECT sm.id, d.id, d.name, d.price, 1
FROM setmeal sm
JOIN canteen c ON c.id = sm.canteen_id
JOIN dish d ON d.canteen_id = c.id
JOIN (
    SELECT 'CANTEEN_ONE' code, '热销套餐' setmeal_suffix, '红烧肉盖饭' dish_name
    UNION ALL SELECT 'CANTEEN_ONE', '热销套餐', '冰豆浆'
    UNION ALL SELECT 'CANTEEN_ONE', '满足套餐', '黑椒鸡排饭'
    UNION ALL SELECT 'CANTEEN_ONE', '满足套餐', '五香茶叶蛋'
    UNION ALL SELECT 'CANTEEN_TWO', '热销套餐', '黑椒牛柳饭'
    UNION ALL SELECT 'CANTEEN_TWO', '热销套餐', '鲜榨橙汁'
    UNION ALL SELECT 'CANTEEN_TWO', '满足套餐', '照烧鸡腿饭'
    UNION ALL SELECT 'CANTEEN_TWO', '满足套餐', '爽口海带丝'
    UNION ALL SELECT 'CANTEEN_MEIHUA', '热销套餐', '藤椒鸡腿饭'
    UNION ALL SELECT 'CANTEEN_MEIHUA', '热销套餐', '桂花酸梅汤'
    UNION ALL SELECT 'CANTEEN_MEIHUA', '满足套餐', '梅菜扣肉饭'
    UNION ALL SELECT 'CANTEEN_MEIHUA', '满足套餐', '脆皮薯角'
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '热销套餐', '麻辣香锅'
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '热销套餐', '柠檬红茶'
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '满足套餐', '韩式石锅拌饭'
    UNION ALL SELECT 'CANTEEN_FOOD_CITY', '满足套餐', '黄金炸鸡块'
    UNION ALL SELECT 'CANTEEN_FIVE', '热销套餐', '番茄肥牛米线'
    UNION ALL SELECT 'CANTEEN_FIVE', '热销套餐', '冰镇绿豆沙'
    UNION ALL SELECT 'CANTEEN_FIVE', '满足套餐', '菌菇鸡肉饭'
    UNION ALL SELECT 'CANTEEN_FIVE', '满足套餐', '香煎手抓饼'
    UNION ALL SELECT 'CANTEEN_SIX', '热销套餐', '川味燃面'
    UNION ALL SELECT 'CANTEEN_SIX', '热销套餐', '红糖糍粑'
    UNION ALL SELECT 'CANTEEN_SIX', '满足套餐', '砂锅牛肉粉'
    UNION ALL SELECT 'CANTEEN_SIX', '满足套餐', '银耳羹'
) x ON x.code = c.code AND sm.name = CONCAT(c.name, x.setmeal_suffix) AND d.name = x.dish_name;

COMMIT;
