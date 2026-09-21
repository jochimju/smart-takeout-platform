-- A customer's entitlement is scoped to a campaign, not permanently to a setmeal.
alter table seckill_order_guard add column activity_id bigint null after user_id;

update seckill_order_guard g
join seckill_reservation r on r.order_number = g.order_number
set g.activity_id = r.activity_id
where g.activity_id is null;

alter table seckill_order_guard modify activity_id bigint not null;
alter table seckill_order_guard drop index uk_user_setmeal;
alter table seckill_order_guard add unique key uk_user_activity (user_id, activity_id);
alter table seckill_order_guard add key idx_seckill_guard_activity (activity_id);
