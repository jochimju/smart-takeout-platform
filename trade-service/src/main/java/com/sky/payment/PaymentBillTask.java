package com.sky.payment;

import java.time.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class PaymentBillTask {
    private final PaymentReconciliation reconciliation;
    private final JdbcTemplate jdbc;
    @Value("${sky.payment.reconciliation.enabled:false}") private boolean enabled;
    @Scheduled(cron="0 15 10-23 * * *",zone="Asia/Shanghai")
    public void daily() {
        if(!enabled) return;
        LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        if(jdbc.queryForObject("select count(*) from payment_reconciliation_run where bill_date=? and source='CHANNEL_VERIFIED' and state in ('MATCHED','DIFFERENCES')",Integer.class,date)>0) return;
        try { var result=reconciliation.download(date); if(((Number)result.get("difference_count")).intValue()>0) log.error("Payment bill differences require review: run {}",result.get("id")); }
        catch(Exception ex) { log.error("Payment bill download/reconciliation failed for {}; will retry next hour",date,ex); }
    }
}
