-- Campus dining domain: keep legacy records, add campus-oriented organization and pickup data.

create table campus_zone (
    id bigint primary key auto_increment,
    code varchar(32) not null,
    name varchar(64) not null,
    description varchar(255) null,
    status int not null default 1,
    create_time datetime not null,
    update_time datetime not null,
    unique key uk_campus_zone_code (code),
    unique key uk_campus_zone_name (name)
) comment 'campus zone';

create table canteen (
    id bigint primary key auto_increment,
    campus_zone_id bigint not null,
    code varchar(32) not null,
    name varchar(64) not null,
    location varchar(128) not null,
    description varchar(255) null,
    status int not null default 1,
    sort int not null default 0,
    create_time datetime not null,
    update_time datetime not null,
    unique key uk_canteen_code (code),
    unique key uk_canteen_zone_name (campus_zone_id, name),
    key idx_canteen_zone_status (campus_zone_id, status)
) comment 'campus canteen';

create table stall (
    id bigint primary key auto_increment,
    canteen_id bigint not null,
    code varchar(32) not null,
    name varchar(64) not null,
    location varchar(128) null,
    category varchar(32) null,
    daily_status varchar(16) not null default 'OPEN' comment 'OPEN, CLOSED, SOLD_OUT',
    status int not null default 1,
    sort int not null default 0,
    create_time datetime not null,
    update_time datetime not null,
    unique key uk_stall_code (code),
    unique key uk_stall_canteen_name (canteen_id, name),
    key idx_stall_canteen_status (canteen_id, status, daily_status)
) comment 'canteen stall';

create table pickup_point (
    id bigint primary key auto_increment,
    campus_zone_id bigint not null,
    canteen_id bigint not null,
    code varchar(32) not null,
    name varchar(64) not null,
    location varchar(128) not null,
    status int not null default 1,
    sort int not null default 0,
    create_time datetime not null,
    update_time datetime not null,
    unique key uk_pickup_point_code (code),
    unique key uk_pickup_point_canteen_name (canteen_id, name),
    key idx_pickup_point_zone_status (campus_zone_id, status)
) comment 'campus pickup point';

create table meal_period (
    id bigint primary key auto_increment,
    code varchar(32) not null,
    name varchar(32) not null,
    start_time time not null,
    end_time time not null,
    status int not null default 1,
    sort int not null default 0,
    unique key uk_meal_period_code (code)
) comment 'meal period';

create table stall_business_hours (
    id bigint primary key auto_increment,
    stall_id bigint not null,
    meal_period_id bigint not null,
    weekday_mask varchar(16) not null default '1,2,3,4,5,6,7',
    start_time time not null,
    end_time time not null,
    status int not null default 1,
    unique key uk_stall_meal_period (stall_id, meal_period_id),
    key idx_business_hours_stall_status (stall_id, status)
) comment 'stall business hours';

alter table dish
    add column stall_id bigint null comment 'serving stall id',
    add column meal_period_id bigint null comment 'available meal period id',
    add column is_recommended tinyint not null default 0 comment 'campus recommendation',
    add column campus_tag varchar(128) null comment 'dietary or campus tags';

alter table orders
    add column order_type varchar(16) not null default 'PICKUP' comment 'PICKUP or DELIVERY',
    add column pickup_code varchar(16) null comment 'pickup verification code',
    add column appointment_time datetime null comment 'scheduled pickup time',
    add column campus_zone_id bigint null,
    add column canteen_id bigint null,
    add column stall_id bigint null,
    add column pickup_point_id bigint null,
    add column meal_period_id bigint null,
    add column pickup_status varchar(16) not null default 'PENDING' comment 'PENDING, READY, PICKED_UP';

create index idx_dish_stall_status on dish(stall_id, status);
create index idx_orders_campus_pickup on orders(campus_zone_id, canteen_id, pickup_status, appointment_time);
create unique index uk_orders_pickup_code on orders(pickup_code);

insert into campus_zone (code, name, description, status, create_time, update_time)
values ('XIASHACAMPUS', '下沙校区', '校园餐饮服务范围', 1, now(), now());

insert into canteen (campus_zone_id, code, name, location, description, status, sort, create_time, update_time)
select id, 'CANTEEN_ONE', '校园一食堂', '下沙校区生活区', '提供快餐、面点与早餐服务', 1, 1, now(), now()
from campus_zone where code = 'XIASHACAMPUS';

insert into canteen (campus_zone_id, code, name, location, description, status, sort, create_time, update_time)
select id, 'CANTEEN_TWO', '校园二食堂', '下沙校区教学区附近', '提供轻食、风味小炒与晚餐服务', 1, 2, now(), now()
from campus_zone where code = 'XIASHACAMPUS';

insert into meal_period (code, name, start_time, end_time, status, sort) values
('BREAKFAST', '早餐', '06:30:00', '09:30:00', 1, 1),
('LUNCH', '午餐', '10:30:00', '13:30:00', 1, 2),
('DINNER', '晚餐', '16:30:00', '19:30:00', 1, 3);

insert into stall (canteen_id, code, name, location, category, daily_status, status, sort, create_time, update_time)
select id, 'ONE_FAST', '一层快餐档', '一食堂一层', '盖浇饭', 'OPEN', 1, 1, now(), now()
from canteen where code = 'CANTEEN_ONE';

insert into stall (canteen_id, code, name, location, category, daily_status, status, sort, create_time, update_time)
select id, 'ONE_NOODLE', '面点档', '一食堂一层', '面点面食', 'OPEN', 1, 2, now(), now()
from canteen where code = 'CANTEEN_ONE';

insert into stall (canteen_id, code, name, location, category, daily_status, status, sort, create_time, update_time)
select id, 'TWO_LIGHT', '轻食简餐档', '二食堂一层', '轻食简餐', 'OPEN', 1, 1, now(), now()
from canteen where code = 'CANTEEN_TWO';

insert into stall (canteen_id, code, name, location, category, daily_status, status, sort, create_time, update_time)
select id, 'TWO_WOK', '风味小炒档', '二食堂二层', '风味小炒', 'OPEN', 1, 2, now(), now()
from canteen where code = 'CANTEEN_TWO';

insert into pickup_point (campus_zone_id, canteen_id, code, name, location, status, sort, create_time, update_time)
select z.id, c.id, 'ONE_GATE', '一食堂取餐架', '一食堂一层入口右侧', 1, 1, now(), now()
from campus_zone z join canteen c on c.code = 'CANTEEN_ONE' where z.code = 'XIASHACAMPUS';

insert into pickup_point (campus_zone_id, canteen_id, code, name, location, status, sort, create_time, update_time)
select z.id, c.id, 'TWO_GATE', '二食堂取餐架', '二食堂一层入口左侧', 1, 2, now(), now()
from campus_zone z join canteen c on c.code = 'CANTEEN_TWO' where z.code = 'XIASHACAMPUS';

insert into stall_business_hours (stall_id, meal_period_id, weekday_mask, start_time, end_time, status)
select s.id, m.id, '1,2,3,4,5,6,7', m.start_time, m.end_time, 1
from stall s join meal_period m on m.code in ('LUNCH', 'DINNER')
where s.code in ('ONE_FAST', 'TWO_LIGHT', 'TWO_WOK');

insert into stall_business_hours (stall_id, meal_period_id, weekday_mask, start_time, end_time, status)
select s.id, m.id, '1,2,3,4,5,6,7', m.start_time, m.end_time, 1
from stall s join meal_period m on m.code in ('BREAKFAST', 'LUNCH', 'DINNER')
where s.code = 'ONE_NOODLE';

-- Preserve historical social-dining samples while preventing them from appearing in the campus menu.
update dish set status = 0, is_recommended = 0
where id in (51, 52, 53, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 48);

update category set status = 0 where id in (16, 17, 18, 20);

insert into category (type, name, sort, status, create_time, update_time, create_user, update_user) values
(1, '校园盖浇饭', 1, 1, now(), now(), 1, 1),
(1, '面点面食', 2, 1, now(), now(), 1, 1),
(1, '轻食简餐', 3, 1, now(), now(), 1, 1),
(1, '风味小炒', 4, 1, now(), now(), 1, 1);

insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user, stock, stall_id, meal_period_id, is_recommended, campus_tag)
select '香菇鸡腿盖浇饭', c.id, 14.00, null, '现制热餐，搭配时蔬', 1, now(), now(), 1, 1, 60, s.id, m.id, 1, '午餐,晚餐'
from category c join stall s on s.code = 'ONE_FAST' join meal_period m on m.code = 'LUNCH' where c.name = '校园盖浇饭';

insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user, stock, stall_id, meal_period_id, is_recommended, campus_tag)
select '番茄炒蛋盖浇饭', c.id, 11.00, null, '经典家常口味', 1, now(), now(), 1, 1, 80, s.id, m.id, 1, '午餐,晚餐'
from category c join stall s on s.code = 'ONE_FAST' join meal_period m on m.code = 'LUNCH' where c.name = '校园盖浇饭';

insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user, stock, stall_id, meal_period_id, is_recommended, campus_tag)
select '土豆牛肉盖浇饭', c.id, 16.00, null, '牛肉与土豆慢炖', 1, now(), now(), 1, 1, 50, s.id, m.id, 1, '午餐,晚餐'
from category c join stall s on s.code = 'ONE_FAST' join meal_period m on m.code = 'DINNER' where c.name = '校园盖浇饭';

insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user, stock, stall_id, meal_period_id, is_recommended, campus_tag)
select '鲜肉小笼包', c.id, 6.00, null, '四只装，适合早餐', 1, now(), now(), 1, 1, 100, s.id, m.id, 1, '早餐'
from category c join stall s on s.code = 'ONE_NOODLE' join meal_period m on m.code = 'BREAKFAST' where c.name = '面点面食';

insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user, stock, stall_id, meal_period_id, is_recommended, campus_tag)
select '葱油拌面', c.id, 8.00, null, '现拌面条，清爽不腻', 1, now(), now(), 1, 1, 80, s.id, m.id, 1, '早餐,午餐'
from category c join stall s on s.code = 'ONE_NOODLE' join meal_period m on m.code = 'LUNCH' where c.name = '面点面食';

insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user, stock, stall_id, meal_period_id, is_recommended, campus_tag)
select '鸡丝汤面', c.id, 12.00, null, '鸡丝、青菜与热汤面', 1, now(), now(), 1, 1, 60, s.id, m.id, 0, '午餐,晚餐'
from category c join stall s on s.code = 'ONE_NOODLE' join meal_period m on m.code = 'DINNER' where c.name = '面点面食';

insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user, stock, stall_id, meal_period_id, is_recommended, campus_tag)
select '香煎鸡胸轻食碗', c.id, 18.00, null, '鸡胸肉、玉米与时蔬', 1, now(), now(), 1, 1, 40, s.id, m.id, 1, '午餐,高蛋白'
from category c join stall s on s.code = 'TWO_LIGHT' join meal_period m on m.code = 'LUNCH' where c.name = '轻食简餐';

insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user, stock, stall_id, meal_period_id, is_recommended, campus_tag)
select '时蔬豆腐轻食碗', c.id, 13.00, null, '豆腐、杂粮与当季时蔬', 1, now(), now(), 1, 1, 50, s.id, m.id, 0, '午餐,素食'
from category c join stall s on s.code = 'TWO_LIGHT' join meal_period m on m.code = 'DINNER' where c.name = '轻食简餐';

insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user, stock, stall_id, meal_period_id, is_recommended, campus_tag)
select '杭椒小炒肉', c.id, 15.00, null, '下饭小炒，现点现做', 1, now(), now(), 1, 1, 50, s.id, m.id, 1, '午餐,晚餐'
from category c join stall s on s.code = 'TWO_WOK' join meal_period m on m.code = 'LUNCH' where c.name = '风味小炒';

insert into dish (name, category_id, price, image, description, status, create_time, update_time, create_user, update_user, stock, stall_id, meal_period_id, is_recommended, campus_tag)
select '鱼香茄子饭', c.id, 12.00, null, '少油少盐的家常风味', 1, now(), now(), 1, 1, 60, s.id, m.id, 0, '午餐,晚餐'
from category c join stall s on s.code = 'TWO_WOK' join meal_period m on m.code = 'DINNER' where c.name = '风味小炒';

insert into setmeal (category_id, name, price, status, description, image, create_time, update_time, create_user, update_user, stock)
select c.id, '午间能量套餐', 18.00, 1, '香菇鸡腿盖浇饭加鸡蛋汤', null, now(), now(), 1, 1, 50
from category c where c.name = '人气套餐';

insert into setmeal (category_id, name, price, status, description, image, create_time, update_time, create_user, update_user, stock)
select c.id, '轻盈午餐套餐', 22.00, 1, '香煎鸡胸轻食碗加饮品', null, now(), now(), 1, 1, 40
from category c where c.name = '人气套餐';

insert into setmeal_dish (setmeal_id, dish_id, name, price, copies)
select sm.id, d.id, d.name, d.price, 1
from setmeal sm join dish d on d.name = '香菇鸡腿盖浇饭' where sm.name = '午间能量套餐';

insert into setmeal_dish (setmeal_id, dish_id, name, price, copies)
select sm.id, d.id, d.name, d.price, 1
from setmeal sm join dish d on d.name = '鸡蛋汤' where sm.name = '午间能量套餐';

insert into setmeal_dish (setmeal_id, dish_id, name, price, copies)
select sm.id, d.id, d.name, d.price, 1
from setmeal sm join dish d on d.name = '香煎鸡胸轻食碗' where sm.name = '轻盈午餐套餐';

insert into setmeal_dish (setmeal_id, dish_id, name, price, copies)
select sm.id, d.id, d.name, d.price, 1
from setmeal sm join dish d on d.name = '王老吉' where sm.name = '轻盈午餐套餐';

update orders o
join campus_zone z on z.code = 'XIASHACAMPUS'
join canteen c on c.code = 'CANTEEN_ONE'
join stall s on s.code = 'ONE_FAST'
join pickup_point p on p.code = 'ONE_GATE'
set o.order_type = 'PICKUP',
    o.campus_zone_id = z.id,
    o.canteen_id = c.id,
    o.stall_id = s.id,
    o.pickup_point_id = p.id,
    o.pickup_status = case when o.status in (5, 6) then 'PICKED_UP' else 'PENDING' end
where o.campus_zone_id is null;
