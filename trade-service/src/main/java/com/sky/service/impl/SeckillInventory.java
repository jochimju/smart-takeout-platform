package com.sky.service.impl;

import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.entity.SeckillActivity;
import com.sky.mapper.SeckillActivityMapper;
import com.sky.exception.OrderBusinessException;

public final class SeckillInventory {
    private SeckillInventory() {}
    public static SeckillActivity lock(SeckillActivityMapper mapper,SeckillOrderMessageDTO command) {
        int count=command.inventoryBuckets(),bucket=command.inventoryBucket();
        if(count!=1 && count!=2 && count!=4 && count!=8 || bucket<0 || bucket>=count)
            throw new OrderBusinessException("秒杀库存桶资料不一致");
        if(count==1) {
            var activity=mapper.lock(command.getActivityId());
            if(activity!=null && activity.getBucketCount()!=null && activity.getBucketCount()!=1)
                throw new OrderBusinessException("秒杀库存桶资料不一致");
            return activity;
        }
        var activity=mapper.metadata(command.getActivityId());
        if(activity==null || !Integer.valueOf(count).equals(activity.getBucketCount()) ||
                mapper.lockBucket(command.getActivityId(),bucket)==null)
            throw new OrderBusinessException("秒杀库存桶资料不一致");
        return activity;
    }
    public static int debit(SeckillActivityMapper mapper,SeckillOrderMessageDTO command,int count) {
        return command.inventoryBuckets()>1 ? mapper.fulfillBucket(command.getActivityId(),command.inventoryBucket(),count)
            : count==1 ? mapper.fulfill(command.getActivityId()) : mapper.fulfillBatch(command.getActivityId(),count);
    }
}
