package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.dto.OrderSubmitMessageDTO;
import com.sky.mapper.OrderSubmitRequestMapper;
import com.sky.service.mq.OrderMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class NormalOrderRecovery {
    public static class MissingAcceptance extends IllegalStateException { public MissingAcceptance(){super("普通订单受理记录缺失");} }
    private final OrderSubmitRequestMapper requests;
    private final OrderMessagePublisher publisher;
    @Transactional(rollbackFor=Exception.class)
    public void failed(String number,Exception failure,boolean permanent) {
        var row=requests.lock(number);if(row==null)throw new IllegalStateException("Missing durable acceptance");
        String error=String.valueOf(failure.getMessage());
        requests.failed(number,error.substring(0,Math.min(500,error.length())),permanent);
    }
    @Transactional(rollbackFor=Exception.class)
    public void recover(String number) {
        var row=requests.lock(number);if(row==null || !"ACCEPTED".equals(row.get("state")))return;
        if(((Number)row.get("expired")).intValue()==1)requests.failed(number,"建单等待超时",true);
        else {
            requests.postpone(number);
            publisher.submitAfterCommit(JSON.parseObject((String)row.get("command_body"),OrderSubmitMessageDTO.class));
        }
    }
}
