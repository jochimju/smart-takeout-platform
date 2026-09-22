package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.constant.MqConstant;
import com.sky.entity.MqFailMessage;
import com.sky.mapper.MqFailMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TimeoutFailureService {
    private final OrderReliabilityStore store;
    private final MqFailMessageMapper failures;
    @Transactional
    public void record(String number,Exception failure) {
        // Legacy timeout jobs can still be replayed while a deployment drains
        // them. New orders are recovered from the MQ retry table instead of
        // recreating a periodic order-timeout scan.
        if (store.hasTimeout(number)) {
            store.failed("timeout:"+number,null,failure);
            return;
        }
        failures.insert(MqFailMessage.builder()
                .exchangeName(MqConstant.ORDER_DELAY_EXCHANGE)
                .routingKey(MqConstant.ORDER_DEAD_ROUTING_KEY)
                .messageBody(JSON.toJSONString(number))
                .failReason(failure.toString()).status(0).retryCount(0)
                .createTime(java.time.LocalDateTime.now()).build());
    }
}
