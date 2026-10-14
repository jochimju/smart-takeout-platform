package com.sky.service.mq;

import com.sky.constant.MqConstant;
import com.sky.dto.SeckillOrderMessageDTO;

public final class SeckillRouting {
    public static final String PREFIX="seckill.order.bucket.";
    private SeckillRouting() {}
    public static String route(SeckillOrderMessageDTO command) {
        int count=command.inventoryBuckets(),bucket=command.inventoryBucket();
        if(count!=1 && count!=2 && count!=4 && count!=8 || bucket<0 || bucket>=count)
            throw new IllegalArgumentException("Invalid seckill inventory bucket");
        return count==1 ? MqConstant.SECKILL_ORDER_ROUTING_KEY : PREFIX+bucket;
    }
    public static boolean isOrder(String route) {
        return MqConstant.SECKILL_ORDER_ROUTING_KEY.equals(route) || route!=null && route.matches("seckill\\.order\\.bucket\\.[0-7]");
    }
}
