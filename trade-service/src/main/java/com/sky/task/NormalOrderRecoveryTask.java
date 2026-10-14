package com.sky.task;
import com.sky.mapper.OrderSubmitRequestMapper;
import com.sky.service.impl.NormalOrderRecovery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component @RequiredArgsConstructor @Slf4j
public class NormalOrderRecoveryTask {
 private final OrderSubmitRequestMapper requests;
 private final NormalOrderRecovery recovery;
 @Scheduled(fixedDelayString="${sky.order.recovery-ms:1000}")
 public void recover(){for(String number:requests.ready())try{recovery.recover(number);}catch(Exception failure){log.warn("Normal recovery retained: {}",number,failure);}}
}
