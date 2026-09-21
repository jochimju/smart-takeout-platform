alter table dish
    add column stock int not null default 0 comment 'available stock';

alter table setmeal
    add column stock int not null default 0 comment 'available stock';

alter table orders
    add column coupon_id bigint null comment 'used coupon id',
    add column discount_amount decimal(10,2) not null default 0 comment 'discount amount';

alter table orders
    add unique key uk_orders_number (number);

alter table orders
    add index idx_orders_user_time (user_id, order_time),
    add index idx_orders_status_time (status, order_time);

create table coupon (
    id bigint primary key auto_increment,
    name varchar(64) not null,
    type varchar(20) not null comment 'FULL_REDUCTION or DISCOUNT',
    threshold_amount decimal(10,2) not null default 0,
    discount_amount decimal(10,2) null,
    discount_rate decimal(5,2) null,
    total_stock int not null default 0,
    received_count int not null default 0,
    status int not null default 1,
    begin_time datetime not null,
    end_time datetime not null,
    create_time datetime null,
    update_time datetime null
) comment 'coupon rule';

create table user_coupon (
    id bigint primary key auto_increment,
    user_id bigint not null,
    coupon_id bigint not null,
    status int not null default 0 comment '0 unused, 1 used, 2 expired',
    receive_time datetime not null,
    use_time datetime null,
    order_id bigint null,
    unique key uk_user_coupon (user_id, coupon_id)
) comment 'user coupon';

create index idx_user_coupon_status on user_coupon(user_id, status);

create table seckill_order_guard (
    id bigint primary key auto_increment,
    user_id bigint not null,
    setmeal_id bigint not null,
    order_number varchar(64) not null,
    create_time datetime not null,
    unique key uk_user_setmeal (user_id, setmeal_id),
    unique key uk_order_number (order_number)
) comment 'seckill idempotent guard';

create table role (
    id bigint primary key auto_increment,
    code varchar(64) not null unique,
    name varchar(64) not null,
    status int not null default 1,
    create_time datetime null,
    update_time datetime null
) comment 'role';

create table permission (
    id bigint primary key auto_increment,
    code varchar(128) not null unique,
    name varchar(128) not null,
    path varchar(255) null,
    method varchar(16) null,
    type varchar(20) not null default 'API'
) comment 'permission';

create unique index uk_permission_method_path on permission(method, path);

create table employee_role (
    employee_id bigint not null,
    role_id bigint not null,
    primary key (employee_id, role_id)
) comment 'employee role relation';

create table role_permission (
    role_id bigint not null,
    permission_id bigint not null,
    primary key (role_id, permission_id)
) comment 'role permission relation';

create table seckill_activity (
    id bigint primary key auto_increment,
    setmeal_id bigint not null,
    stock int not null default 0,
    begin_time datetime not null,
    end_time datetime not null,
    status int not null default 1,
    create_time datetime null,
    update_time datetime null
) comment 'setmeal seckill activity';

create index idx_seckill_setmeal_status_time on seckill_activity(setmeal_id, status, begin_time, end_time);

create table mq_fail_message (
    id bigint primary key auto_increment,
    exchange_name varchar(128) not null,
    routing_key varchar(128) not null,
    message_body text not null,
    fail_reason varchar(512) null,
    status int not null default 0,
    retry_count int not null default 0,
    create_time datetime not null,
    update_time datetime null
) comment 'failed mq message';
