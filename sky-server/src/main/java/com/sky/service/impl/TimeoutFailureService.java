package com.sky.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TimeoutFailureService {
    private final OrderReliabilityStore store;
    @Transactional
    public void record(String number,Exception failure) {
        if(!store.hasTimeout(number)) throw new IllegalStateException("no durable timeout event for "+number,failure);
        store.failed("timeout:"+number,null,failure);
    }
}
