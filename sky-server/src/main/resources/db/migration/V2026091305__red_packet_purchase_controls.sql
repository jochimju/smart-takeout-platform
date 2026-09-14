alter table red_packet_package
  add column purchase_limit_per_user int not null default 1 after status,
  add column budget_amount decimal(12,2) not null default 5000.00 after purchase_limit_per_user,
  add column reserved_amount decimal(12,2) not null default 0.00 after budget_amount,
  add column issued_amount decimal(12,2) not null default 0.00 after reserved_amount;

alter table red_packet_purchase_order
  add column packet_total_amount_snapshot decimal(10,2) not null default 0.00 after packet_amount_snapshot,
  add column expire_time datetime null after create_time,
  add key idx_red_packet_purchase_expire (status, expire_time),
  add key idx_red_packet_purchase_user_package (user_id, package_id, status);

update red_packet_purchase_order
set packet_total_amount_snapshot = packet_count_snapshot * packet_amount_snapshot,
    expire_time = date_add(create_time, interval 15 minute)
where packet_total_amount_snapshot = 0.00;

alter table red_packet_purchase_order modify expire_time datetime not null;

update red_packet_package p
left join (
  select package_id, coalesce(sum(packet_count_snapshot * packet_amount_snapshot), 0) as total_issued
  from red_packet_purchase_order where status = 1 group by package_id
) paid on paid.package_id = p.id
set p.issued_amount = coalesce(paid.total_issued, 0.00);

-- Do not sell a deterministic cash-equivalent package below its total face value.
update red_packet_package
set sale_price = packet_count * packet_amount
where sale_price < packet_count * packet_amount;
