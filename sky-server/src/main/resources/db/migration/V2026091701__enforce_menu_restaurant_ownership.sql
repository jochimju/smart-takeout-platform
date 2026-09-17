-- Restaurant menu ownership, phase 1.
-- Existing legacy rows whose restaurant cannot be inferred safely are recorded
-- for manual assignment instead of being assigned to an arbitrary restaurant.

create table if not exists menu_ownership_issue (
    id bigint primary key auto_increment,
    entity_type varchar(32) not null comment 'CATEGORY/DISH/SETMEAL/SETMEAL_DISH',
    entity_id bigint not null,
    reason varchar(128) not null,
    detected_canteen_id bigint null,
    expected_canteen_id bigint null,
    resolved_canteen_id bigint null,
    resolved_by bigint null,
    resolved_time datetime null,
    create_time datetime not null default current_timestamp,
    update_time datetime not null default current_timestamp on update current_timestamp,
    unique key uk_menu_ownership_issue_entity (entity_type, entity_id),
    key idx_menu_ownership_issue_open (resolved_time, entity_type)
) comment 'menu rows requiring manual restaurant ownership assignment';

insert ignore into menu_ownership_issue (entity_type, entity_id, reason, detected_canteen_id)
select 'CATEGORY', c.id, 'MISSING_RESTAURANT', c.canteen_id
from category c
where c.canteen_id is null;

insert ignore into menu_ownership_issue (entity_type, entity_id, reason, detected_canteen_id, expected_canteen_id)
select 'DISH', d.id,
       case when c.id is null then 'CATEGORY_NOT_FOUND'
            when c.canteen_id is null then 'CATEGORY_MISSING_RESTAURANT'
            when d.canteen_id is null then 'MISSING_RESTAURANT'
            else 'CATEGORY_RESTAURANT_MISMATCH' end,
       d.canteen_id, c.canteen_id
from dish d left join category c on c.id = d.category_id
where c.id is null or c.canteen_id is null or d.canteen_id is null or d.canteen_id <> c.canteen_id;

-- Quarantine invalid legacy dishes. Their category relation is retained for
-- manual repair, but clearing the scoped owner makes the composite FK safe and
-- prevents them from appearing in restaurant-scoped menus.
update dish d left join category c on c.id = d.category_id
set d.canteen_id = null, d.status = 0
where c.id is null or c.canteen_id is null or d.canteen_id is null or d.canteen_id <> c.canteen_id;

insert ignore into menu_ownership_issue (entity_type, entity_id, reason, detected_canteen_id, expected_canteen_id)
select 'SETMEAL', s.id,
       case when c.id is null then 'CATEGORY_NOT_FOUND'
            when c.canteen_id is null then 'CATEGORY_MISSING_RESTAURANT'
            when s.canteen_id is null then 'MISSING_RESTAURANT'
            else 'CATEGORY_RESTAURANT_MISMATCH' end,
       s.canteen_id, c.canteen_id
from setmeal s left join category c on c.id = s.category_id
where c.id is null or c.canteen_id is null or s.canteen_id is null or s.canteen_id <> c.canteen_id;

-- Apply the same safe quarantine policy to invalid legacy setmeals.
update setmeal s left join category c on c.id = s.category_id
set s.canteen_id = null, s.status = 0
where c.id is null or c.canteen_id is null or s.canteen_id is null or s.canteen_id <> c.canteen_id;

alter table setmeal_dish add column canteen_id bigint null comment 'restaurant ownership snapshot';

update setmeal_dish sd
join setmeal s on s.id = sd.setmeal_id
join dish d on d.id = sd.dish_id
set sd.canteen_id = s.canteen_id
where sd.canteen_id is null
  and s.canteen_id is not null
  and s.canteen_id = d.canteen_id;

insert ignore into menu_ownership_issue (entity_type, entity_id, reason, detected_canteen_id, expected_canteen_id)
select 'SETMEAL_DISH', sd.id,
       case when s.id is null then 'SETMEAL_NOT_FOUND'
            when d.id is null then 'DISH_NOT_FOUND'
            when s.canteen_id is null or d.canteen_id is null then 'MISSING_RESTAURANT'
            else 'SETMEAL_DISH_RESTAURANT_MISMATCH' end,
       s.canteen_id, d.canteen_id
from setmeal_dish sd
left join setmeal s on s.id = sd.setmeal_id
left join dish d on d.id = sd.dish_id
where s.id is null or d.id is null or s.canteen_id is null or d.canteen_id is null or s.canteen_id <> d.canteen_id;

-- These composite keys make restaurant ownership part of each relationship.
-- Rows with NULL legacy ownership are deliberately not covered until resolved.
alter table category add unique key uk_category_id_canteen (id, canteen_id);
alter table dish add unique key uk_dish_id_canteen (id, canteen_id);
alter table setmeal add unique key uk_setmeal_id_canteen (id, canteen_id);

alter table dish
    add constraint fk_dish_category_canteen
        foreign key (category_id, canteen_id) references category (id, canteen_id);
alter table setmeal
    add constraint fk_setmeal_category_canteen
        foreign key (category_id, canteen_id) references category (id, canteen_id);
alter table setmeal_dish
    add constraint fk_setmeal_dish_setmeal_canteen
        foreign key (setmeal_id, canteen_id) references setmeal (id, canteen_id),
    add constraint fk_setmeal_dish_dish_canteen
        foreign key (dish_id, canteen_id) references dish (id, canteen_id);

create index idx_setmeal_dish_canteen on setmeal_dish(canteen_id, setmeal_id, dish_id);

-- Follow with a NOT NULL migration only after no unresolved issue remains.
