package com.sky.payment;

import com.alibaba.fastjson.JSONObject;
import com.sky.service.RedPacketService;
import com.sky.service.impl.OrderLifecycleService;
import com.sky.utils.WeChatPayUtil;
import com.sky.utils.properties.WeChatProperties;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class PaymentRecoveryTask {
    private final PaymentRecoveryStore store;
    private final WeChatPayUtil wechat;
    private final WeChatProperties properties;
    private final OrderLifecycleService orders;
    private final RedPacketService packets;
    private final JdbcTemplate jdbc;
    private final PaymentSettlement settlement;
    @Value("${sky.payment.recovery.enabled:true}") private boolean enabled;
    @Scheduled(fixedDelayString="${sky.payment.recovery.interval-ms:1000}")
    public void dispatch() {
        if (!enabled) return;
        for (var job:store.ready()) {
            String id=(String)job.get("id"), token=store.claim(id); if(token==null) continue;
            try { process(job,token); }
            catch(Exception ex) { store.failed(id,token,ex); log.warn("Payment recovery requires retry/review: {}",id,ex); }
        }
    }
    public void process(Map<String,Object> job,String token) throws Exception {
        String number=(String)job.get("order_number"),id=(String)job.get("id");
        LocalDateTime deadline=job.get("deadline") instanceof Timestamp t?t.toLocalDateTime():(LocalDateTime)job.get("deadline");
        JSONObject result=wechat.queryOrder(number);
        String code=result.getString("code"),state=result.getString("trade_state");
        if ("ORDER_NOT_EXIST".equals(code) || "RESOURCE_NOT_EXISTS".equals(code)) {
            // A prepay request may still be in flight. Keep checking until the channel deadline has passed.
            if (LocalDateTime.now().isAfter(deadline.plusMinutes(2))) store.done(id,token);
            else retry(id,token); return;
        }
        if (code!=null) throw new IllegalStateException("WeChat query failed: "+code);
        if (!number.equals(result.getString("out_trade_no")) || !properties.getMchid().equals(result.getString("mchid"))
                || !properties.getAppid().equals(result.getString("appid"))) throw new SecurityException("Payment query identity mismatch");
        if ("SUCCESS".equals(state) || "REFUND".equals(state)) {
            var amount=result.getJSONObject("amount");
            if (amount==null || !"CNY".equals(amount.getString("currency")) || amount.getBigDecimal("total")==null)
                throw new SecurityException("Payment query currency/amount missing");
            BigDecimal total=amount.getBigDecimal("total").movePointLeft(2);
            if (total.compareTo((BigDecimal)job.get("amount"))!=0 || result.getString("transaction_id")==null)
                throw new SecurityException("Payment query receipt mismatch");
            if ("REFUND".equals(state) && !cancelled(number)) throw new IllegalStateException("Unexpected channel refund requires reconciliation");
            if ("REFUND".equals(state)) result.put("trade_state","SUCCESS");
            settlement.accept(result);
            store.done(id,token); return;
        }
        if ("CLOSED".equals(state) || "REVOKED".equals(state) || "PAYERROR".equals(state)) {
            if (!cancelled(number) && LocalDateTime.now().isBefore(deadline))
                throw new IllegalStateException("Channel closed while local order remains payable; review required");
            store.done(id,token); return;
        }
        if (!"NOTPAY".equals(state) && !"USERPAYING".equals(state)) throw new IllegalStateException("Unknown payment state: "+state);
        if ("CLOSE".equals(job.get("kind")) || cancelled(number) || !LocalDateTime.now().isBefore(deadline)) {
            JSONObject closed=wechat.closeOrder(number);
            String closeCode=closed.getString("code");
            if (closeCode==null || "ORDER_CLOSED".equals(closeCode)) { store.done(id,token); return; }
            if (!"ORDERPAID".equals(closeCode) && !"ORDER_PAID".equals(closeCode))
                throw new IllegalStateException("WeChat close failed: "+closeCode);
        }
        retry(id,token); // Including paid-during-close: re-query and settle, never infer payment from a close error.
    }
    private void retry(String id,String token) { store.waitUntil(id,token,LocalDateTime.now().plusSeconds(30)); }
    private boolean cancelled(String number) {
        String sql=number.startsWith("RP")?"select status from red_packet_purchase_order where order_no=?":"select status from orders where number=?";
        var rows=jdbc.queryForList(sql,number);
        if(rows.size()!=1) throw new IllegalStateException("Local payment order missing");
        int status=((Number)rows.get(0).get("status")).intValue();
        return number.startsWith("RP")?(status==2||status==3||status==4):status==6;
    }
}
