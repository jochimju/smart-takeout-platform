package com.sky.constant;

public class MqConstant {

    // v2 avoids changing arguments on already-declared v1 queues. RabbitMQ rejects
    // such mutations with PRECONDITION_FAILED, so the old queues remain available
    // for a controlled drain during deployment.
    public static final String ORDER_EXCHANGE = "sky.order.exchange.v2";
    public static final String ORDER_SUBMIT_QUEUE = "sky.order.submit.v2.queue";
    public static final String ORDER_SUBMIT_ROUTING_KEY = "order.submit";

    public static final String ORDER_DELAY_EXCHANGE = "sky.order.delay.exchange";
    public static final String ORDER_DELAY_QUEUE = "sky.order.delay.queue";
    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay";
    public static final String ORDER_DEAD_QUEUE = "sky.order.dead.queue";
    public static final String ORDER_DEAD_ROUTING_KEY = "order.dead";
    public static final long ORDER_TIMEOUT_MILLIS = 15 * 60 * 1000L;

    public static final String SECKILL_ORDER_QUEUE = "sky.seckill.order.v2.queue";
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order";

    /** Failed async commands are retained for investigation and controlled replay. */
    public static final String ORDER_FAILURE_EXCHANGE = "sky.order.failure.v2.exchange";
    public static final String ORDER_FAILURE_QUEUE = "sky.order.failure.v2.queue";
    public static final String ORDER_FAILURE_ROUTING_KEY = "order.failed";

    private MqConstant() {
    }
}
