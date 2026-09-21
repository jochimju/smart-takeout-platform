create table order_submit_request (
    id bigint primary key auto_increment,
    user_id bigint not null,
    request_id varchar(64) not null,
    order_number varchar(64) not null,
    create_time datetime not null,
    unique key uk_order_submit_request (user_id, request_id),
    unique key uk_order_submit_number (order_number)
) comment 'durable normal-order idempotency binding';
