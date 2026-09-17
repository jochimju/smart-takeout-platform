-- Treat the existing canteen master data as the restaurant aggregate used by the client.
-- A user cart and every menu item belong to exactly one canteen in V1.

alter table category add column canteen_id bigint null comment 'restaurant(canteen) id';
alter table dish add column canteen_id bigint null comment 'restaurant(canteen) id';
alter table setmeal add column canteen_id bigint null comment 'restaurant(canteen) id';
alter table shopping_cart add column canteen_id bigint null comment 'restaurant(canteen) id';
alter table orders add column canteen_name varchar(64) null comment 'canteen name snapshot';

create index idx_category_canteen_status on category(canteen_id, status, type);
create index idx_dish_canteen_status on dish(canteen_id, status, category_id);
create index idx_setmeal_canteen_status on setmeal(canteen_id, status, category_id);
create index idx_shopping_cart_user_canteen on shopping_cart(user_id, canteen_id);

-- Rename the two campus records to the short names used by the product.
update canteen set name = '一餐', location = '一餐食堂' where code = 'CANTEEN_ONE';
update canteen set name = '二餐', location = '二餐食堂' where code = 'CANTEEN_TWO';

insert into canteen (campus_zone_id, code, name, location, description, status, sort, create_time, update_time)
select z.id, x.code, x.name, x.location, x.description, 1, x.sort, now(), now()
from campus_zone z
join (
    select 'CANTEEN_MEIHUA' code, '梅花餐厅' name, '梅花餐厅一层' location, '家常菜与特色面点' description, 3 sort
    union all select 'CANTEEN_FOOD_CITY', '美食城', '美食城一层', '风味小吃与特色套餐', 4
    union all select 'CANTEEN_FIVE', '五餐', '五餐食堂', '快餐与轻食', 5
    union all select 'CANTEEN_SIX', '六餐', '六餐食堂', '面食与夜宵', 6
) x
where z.code = 'XIASHACAMPUS';

insert into stall (canteen_id, code, name, location, category, daily_status, status, sort, create_time, update_time)
select c.id, concat(c.code, '_MAIN'), concat(c.name, '主档口'), c.location, '综合餐饮', 'OPEN', 1, 1, now(), now()
from canteen c
where c.code in ('CANTEEN_MEIHUA', 'CANTEEN_FOOD_CITY', 'CANTEEN_FIVE', 'CANTEEN_SIX');

-- Backfill old campus menu records from their stalls.
update dish d join stall s on d.stall_id = s.id
set d.canteen_id = s.canteen_id
where d.canteen_id is null;

update category c join dish d on d.category_id = c.id
set c.canteen_id = d.canteen_id
where c.canteen_id is null and d.canteen_id is not null;

update setmeal s join category c on s.category_id = c.id
set s.canteen_id = c.canteen_id
where s.canteen_id is null and c.canteen_id is not null;

-- Historical carts and orders are retained under 一餐 rather than left unscoped.
update shopping_cart sc join canteen c on c.code = 'CANTEEN_ONE'
set sc.canteen_id = c.id
where sc.canteen_id is null;

update orders o left join canteen c on c.id = o.canteen_id
set o.canteen_name = coalesce(c.name, '一餐')
where o.canteen_name is null;

-- Each of the four newly added restaurants gets a concrete category and test menu.
insert into category (type, name, sort, status, canteen_id, create_time, update_time, create_user, update_user)
select 1, concat(c.name, '招牌主食'), 1, 1, c.id, now(), now(), 1, 1
from canteen c
where c.code in ('CANTEEN_MEIHUA', 'CANTEEN_FOOD_CITY', 'CANTEEN_FIVE', 'CANTEEN_SIX');

insert into category (type, name, sort, status, canteen_id, create_time, update_time, create_user, update_user)
select 2, concat(c.name, '特色套餐'), 2, 1, c.id, now(), now(), 1, 1
from canteen c
where c.code in ('CANTEEN_MEIHUA', 'CANTEEN_FOOD_CITY', 'CANTEEN_FIVE', 'CANTEEN_SIX');

insert into dish (name, category_id, canteen_id, price, image, description, status, stock, create_time, update_time, create_user, update_user)
select concat(c.name, '招牌饭'), cg.id, c.id, 15.00, null, '本地演示菜单，可在管理端维护', 1, 100, now(), now(), 1, 1
from canteen c join category cg on cg.canteen_id = c.id and cg.name = concat(c.name, '招牌主食')
where c.code in ('CANTEEN_MEIHUA', 'CANTEEN_FOOD_CITY', 'CANTEEN_FIVE', 'CANTEEN_SIX');

insert into dish (name, category_id, canteen_id, price, image, description, status, stock, create_time, update_time, create_user, update_user)
select concat(c.name, '风味面'), cg.id, c.id, 12.00, null, '本地演示菜单，可在管理端维护', 1, 100, now(), now(), 1, 1
from canteen c join category cg on cg.canteen_id = c.id and cg.name = concat(c.name, '招牌主食')
where c.code in ('CANTEEN_MEIHUA', 'CANTEEN_FOOD_CITY', 'CANTEEN_FIVE', 'CANTEEN_SIX');
