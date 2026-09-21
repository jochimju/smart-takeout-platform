-- 餐厅（食堂）主数据归入商品库。
-- 原因：category / dish / setmeal 都通过 canteen_id 归属餐厅，餐厅是菜单的组织维度。
-- 本脚本从交易库迁移三张表结构并复制现有数据，id 保持与原库一致。

CREATE TABLE IF NOT EXISTS `campus_zone` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `code` varchar(32) NOT NULL,
    `name` varchar(64) NOT NULL,
    `description` varchar(255) DEFAULT NULL,
    `status` int NOT NULL DEFAULT '1',
    `create_time` datetime NOT NULL,
    `update_time` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_campus_zone_code` (`code`),
    UNIQUE KEY `uk_campus_zone_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='campus zone';

CREATE TABLE IF NOT EXISTS `canteen` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `campus_zone_id` bigint NOT NULL,
    `code` varchar(32) NOT NULL,
    `name` varchar(64) NOT NULL,
    `location` varchar(128) NOT NULL,
    `description` varchar(255) DEFAULT NULL,
    `status` int NOT NULL DEFAULT '1',
    `sort` int NOT NULL DEFAULT '0',
    `create_time` datetime NOT NULL,
    `update_time` datetime NOT NULL,
    `meal_period` varchar(16) NOT NULL DEFAULT 'ALL' COMMENT 'BREAKFAST/LUNCH/DINNER/ALL',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_canteen_code` (`code`),
    UNIQUE KEY `uk_canteen_zone_name` (`campus_zone_id`,`name`),
    KEY `idx_canteen_zone_status` (`campus_zone_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='campus canteen';

CREATE TABLE IF NOT EXISTS `stall` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `canteen_id` bigint NOT NULL,
    `code` varchar(32) NOT NULL,
    `name` varchar(64) NOT NULL,
    `location` varchar(128) DEFAULT NULL,
    `category` varchar(32) DEFAULT NULL,
    `daily_status` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, CLOSED, SOLD_OUT',
    `status` int NOT NULL DEFAULT '1',
    `sort` int NOT NULL DEFAULT '0',
    `create_time` datetime NOT NULL,
    `update_time` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stall_code` (`code`),
    UNIQUE KEY `uk_stall_canteen_name` (`canteen_id`,`name`),
    KEY `idx_stall_canteen_status` (`canteen_id`,`status`,`daily_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='canteen stall';

INSERT IGNORE INTO `campus_zone` (`id`, `code`, `name`, `description`, `status`, `create_time`, `update_time`) VALUES
(1,'XIASHACAMPUS','下沙校区','校园餐饮服务范围',1,'2026-09-09 15:43:00','2026-09-09 15:43:00');

INSERT IGNORE INTO `canteen` (`id`, `campus_zone_id`, `code`, `name`, `location`, `description`, `status`, `sort`, `create_time`, `update_time`, `meal_period`) VALUES
(1,1,'CANTEEN_ONE','一餐','一餐食堂','提供快餐、面点与早餐服务',1,1,'2026-09-09 15:43:00','2026-09-09 15:43:00','ALL'),
(2,1,'CANTEEN_TWO','二餐','二餐食堂','提供轻食、风味小炒与晚餐服务',1,2,'2026-09-09 15:43:00','2026-09-09 15:43:00','ALL'),
(10,1,'CANTEEN_MEIHUA','梅花餐厅','梅花餐厅一层','家常菜与特色面点',1,3,'2026-09-15 21:32:41','2026-09-15 21:32:41','ALL'),
(11,1,'CANTEEN_FOOD_CITY','美食城','美食城一层','风味小吃与特色套餐',1,4,'2026-09-15 21:32:41','2026-09-15 21:32:41','ALL'),
(12,1,'CANTEEN_FIVE','五餐','五餐食堂','快餐与轻食',1,5,'2026-09-15 21:32:41','2026-09-15 21:32:41','ALL'),
(13,1,'CANTEEN_SIX','六餐','六餐食堂','面食与夜宵',1,6,'2026-09-15 21:32:41','2026-09-15 21:32:41','ALL');

INSERT IGNORE INTO `stall` (`id`, `canteen_id`, `code`, `name`, `location`, `category`, `daily_status`, `status`, `sort`, `create_time`, `update_time`) VALUES
(1,1,'ONE_FAST','一层快餐档','一食堂一层','盖浇饭','OPEN',1,1,'2026-09-09 15:43:00','2026-09-09 15:43:00'),
(2,1,'ONE_NOODLE','面点档','一食堂一层','面点面食','OPEN',1,2,'2026-09-09 15:43:00','2026-09-09 15:43:00'),
(3,2,'TWO_LIGHT','轻食简餐档','二食堂一层','轻食简餐','OPEN',1,1,'2026-09-09 15:43:00','2026-09-09 15:43:00'),
(4,2,'TWO_WOK','风味小炒档','二食堂二层','风味小炒','OPEN',1,2,'2026-09-09 15:43:00','2026-09-09 15:43:00'),
(12,12,'CANTEEN_FIVE_MAIN','五餐主档口','五餐食堂','综合餐饮','OPEN',1,1,'2026-09-15 21:32:41','2026-09-15 21:32:41'),
(13,11,'CANTEEN_FOOD_CITY_MAIN','美食城主档口','美食城一层','综合餐饮','OPEN',1,1,'2026-09-15 21:32:41','2026-09-15 21:32:41'),
(14,10,'CANTEEN_MEIHUA_MAIN','梅花餐厅主档口','梅花餐厅一层','综合餐饮','OPEN',1,1,'2026-09-15 21:32:41','2026-09-15 21:32:41'),
(15,13,'CANTEEN_SIX_MAIN','六餐主档口','六餐食堂','综合餐饮','OPEN',1,1,'2026-09-15 21:32:41','2026-09-15 21:32:41');
