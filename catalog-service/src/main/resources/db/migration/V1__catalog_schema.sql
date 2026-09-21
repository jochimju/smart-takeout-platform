SET FOREIGN_KEY_CHECKS=0;
CREATE TABLE IF NOT EXISTS `category` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `type` int DEFAULT NULL COMMENT '类型   1 菜品分类 2 套餐分类',
  `name` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT '分类名称',
  `sort` int NOT NULL DEFAULT '0' COMMENT '顺序',
  `status` int DEFAULT NULL COMMENT '分类状态 0:禁用，1:启用',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `create_user` bigint DEFAULT NULL COMMENT '创建人',
  `update_user` bigint DEFAULT NULL COMMENT '修改人',
  `canteen_id` bigint DEFAULT NULL COMMENT 'restaurant(canteen) id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_category_name` (`name`),
  UNIQUE KEY `uk_category_id_canteen` (`id`,`canteen_id`),
  KEY `idx_category_canteen_status` (`canteen_id`,`status`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='菜品及套餐分类';

CREATE TABLE IF NOT EXISTS `dish` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT '菜品名称',
  `category_id` bigint NOT NULL COMMENT '菜品分类id',
  `price` decimal(10,2) DEFAULT NULL COMMENT '菜品价格',
  `image` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT '图片',
  `description` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT '描述信息',
  `status` int DEFAULT '1' COMMENT '0 停售 1 起售',
  `monthly_sales` int NOT NULL DEFAULT '0' COMMENT '月销量',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `create_user` bigint DEFAULT NULL COMMENT '创建人',
  `update_user` bigint DEFAULT NULL COMMENT '修改人',
  `stock` int NOT NULL DEFAULT '0' COMMENT 'available stock',
  `stall_id` bigint DEFAULT NULL COMMENT 'serving stall id',
  `meal_period_id` bigint DEFAULT NULL COMMENT 'available meal period id',
  `is_recommended` tinyint NOT NULL DEFAULT '0' COMMENT 'campus recommendation',
  `campus_tag` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT 'dietary or campus tags',
  `canteen_id` bigint DEFAULT NULL COMMENT 'restaurant(canteen) id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_dish_name` (`name`),
  UNIQUE KEY `uk_dish_id_canteen` (`id`,`canteen_id`),
  KEY `idx_dish_stall_status` (`stall_id`,`status`),
  KEY `idx_dish_canteen_status` (`canteen_id`,`status`,`category_id`),
  KEY `fk_dish_category_canteen` (`category_id`,`canteen_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='菜品';

CREATE TABLE IF NOT EXISTS `dish_flavor` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dish_id` bigint NOT NULL COMMENT '菜品',
  `name` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT '口味名称',
  `value` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT '口味数据list',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='菜品口味关系表';

CREATE TABLE IF NOT EXISTS `setmeal` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `category_id` bigint NOT NULL COMMENT '菜品分类id',
  `name` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin NOT NULL COMMENT '套餐名称',
  `price` decimal(10,2) NOT NULL COMMENT '套餐价格',
  `status` int DEFAULT '1' COMMENT '售卖状态 0:停售 1:起售',
  `description` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT '描述信息',
  `image` varchar(255) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT '图片',
  `monthly_sales` int NOT NULL DEFAULT '0' COMMENT '月销量',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `create_user` bigint DEFAULT NULL COMMENT '创建人',
  `update_user` bigint DEFAULT NULL COMMENT '修改人',
  `stock` int NOT NULL DEFAULT '0' COMMENT 'available stock',
  `canteen_id` bigint DEFAULT NULL COMMENT 'restaurant(canteen) id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_setmeal_name` (`name`),
  UNIQUE KEY `uk_setmeal_id_canteen` (`id`,`canteen_id`),
  KEY `idx_setmeal_canteen_status` (`canteen_id`,`status`,`category_id`),
  KEY `fk_setmeal_category_canteen` (`category_id`,`canteen_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='套餐';

CREATE TABLE IF NOT EXISTS `setmeal_dish` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `setmeal_id` bigint DEFAULT NULL COMMENT '套餐id',
  `dish_id` bigint DEFAULT NULL COMMENT '菜品id',
  `name` varchar(32) CHARACTER SET utf8mb3 COLLATE utf8mb3_bin DEFAULT NULL COMMENT '菜品名称 （冗余字段）',
  `price` decimal(10,2) DEFAULT NULL COMMENT '菜品单价（冗余字段）',
  `copies` int DEFAULT NULL COMMENT '菜品份数',
  `canteen_id` bigint DEFAULT NULL COMMENT 'restaurant ownership snapshot',
  PRIMARY KEY (`id`),
  KEY `fk_setmeal_dish_setmeal_canteen` (`setmeal_id`,`canteen_id`),
  KEY `fk_setmeal_dish_dish_canteen` (`dish_id`,`canteen_id`),
  KEY `idx_setmeal_dish_canteen` (`canteen_id`,`setmeal_id`,`dish_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='套餐菜品关系';

SET FOREIGN_KEY_CHECKS=1;
