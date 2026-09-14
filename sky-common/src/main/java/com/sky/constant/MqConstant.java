package com.sky.constant;

public class MqConstant {

    public static final String ORDER_EXCHANGE = "sky.order.exchange";
    public static final String ORDER_SUBMIT_QUEUE = "sky.order.submit.queue";
    public static final String ORDER_SUBMIT_ROUTING_KEY = "order.submit";

    public static final String ORDER_DELAY_EXCHANGE = "sky.order.delay.exchange";
    public static final String ORDER_DELAY_QUEUE = "sky.order.delay.queue";
    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay";
    public static final String ORDER_DEAD_QUEUE = "sky.order.dead.queue";
    public static final String ORDER_DEAD_ROUTING_KEY = "order.dead";
    public static final long ORDER_TIMEOUT_MILLIS = 15 * 60 * 1000L;

    public static final String SECKILL_ORDER_QUEUE = "sky.seckill.order.queue";
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order";

    /** Failed async commands are retained for investigation and controlled replay. */
    public static final String ORDER_FAILURE_EXCHANGE = "sky.order.failure.exchange";
    public static final String ORDER_FAILURE_QUEUE = "sky.order.failure.queue";
    public static final String ORDER_FAILURE_ROUTING_KEY = "order.failed";

    private MqConstant() {
    }
}
