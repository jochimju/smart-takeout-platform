package com.sky.service.impl;

import com.sky.mapper.SeckillActivityMapper;
import com.sky.service.SeckillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Initializes new activities and checks readiness; never resets live Redis inventory. */
@Component
@Slf4j
public class SeckillReconciler {
    @Autowired private SeckillActivityMapper activities;
    @Autowired private SeckillService service;
    @Scheduled(fixedDelayString="${sky.seckill.reconcile-ms:5000}")
    public void reconcile() {
        for(Long id:activities.allIds()) {
            try { service.warmUpActivity(id); }
            catch(Exception e) { log.warn("秒杀缓存 {} 未就绪，暂停受理并等待核对: {}",id,e.getMessage()); }
        }
    }
}
