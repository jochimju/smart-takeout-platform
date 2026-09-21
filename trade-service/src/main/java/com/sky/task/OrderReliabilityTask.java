package com.sky.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MqConstant;
import com.sky.service.impl.OrderReliabilityStore;
import com.sky.service.impl.OrderLifecycleService;
import com.sky.utils.WeChatPayUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

/** Polls durable events, never scans orders to discover timeouts. */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderReliabilityTask {
    private final OrderReliabilityStore store;
    private final RabbitTemplate rabbit;
    private final WeChatPayUtil payment;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    @Scheduled(fixedDelayString="${sky.order.reliability-dispatch-ms:1000}")
    public void dispatch() {
        for(Map<String,Object> job:store.ready()) {
            String id=(String)job.get("id");
            String token=store.claim(id);
            if(token==null) continue;
            try {
                if("TIMEOUT".equals(job.get("kind"))) publishTimeout(job,token);
                else if("REFUND".equals(job.get("kind"))) refund(job,token);
                else throw new IllegalStateException("unknown job kind");
            } catch(Exception e) {
                store.failed(id,token,e);
                log.error("Order reliability job failed; inspect /admin/order/reliability/failures: {}",id,e);
            }
        }
    }

    public void publishTimeout(Map<String,Object> job,String token) throws Exception {
        String id=(String)job.get("id"), number=(String)job.get("order_number");
        LocalDateTime due=toLocalDateTime(job.get("due_at"));
        long remaining=Duration.between(LocalDateTime.now(),due).toMillis();
        String routing=remaining>0 ? MqConstant.ORDER_DELAY_ROUTING_KEY : MqConstant.ORDER_DEAD_ROUTING_KEY;
        CorrelationData correlation=new CorrelationData(id+":"+token);
        rabbit.convertAndSend(MqConstant.ORDER_DELAY_EXCHANGE,routing,number,message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setMessageId(id);
            if(remaining>0) message.getMessageProperties().setExpiration(Long.toString(Math.max(1,remaining)));
            return message;
        },correlation);
        CorrelationData.Confirm confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
        if(!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("timeout publish not confirmed/routed");
        // Keep the event until business completion. This recovers lost dead-letter transfers
        // and bypasses variable-TTL head-of-line blocking without extending the deadline.
        store.waitUntil(id,token,remaining>0 ? due.plusSeconds(2) : LocalDateTime.now().plusSeconds(30));
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof Timestamp) return ((Timestamp) value).toLocalDateTime();
        throw new IllegalStateException("unsupported due_at value: " + value);
    }

    public void refund(Map<String,Object> job,String token) throws Exception {
        String number=(String)job.get("order_number"), id=(String)job.get("id");
        boolean mock=OrderLifecycleService.asBoolean(job.get("mock_payment"));
        BigDecimal amount=(BigDecimal)job.get("amount");
        if(!mock) {
            List<Map<String,Object>> receipts=jdbc.queryForList("select * from order_payment_receipt where order_number=?",number);
            if(receipts.size()!=1 || OrderLifecycleService.asBoolean(receipts.get(0).get("mock_payment")) ||
                amount.compareTo((BigDecimal)receipts.get(0).get("amount"))!=0)
                throw new IllegalStateException("verified payment receipt required; reconcile historical payment before refund");
            String refundNumber=UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
            JSONObject result=JSON.parseObject(payment.queryRefund(refundNumber));
            if("RESOURCE_NOT_EXISTS".equals(result.getString("code")))
                result=JSON.parseObject(payment.refund(number,refundNumber,amount,amount));
            String status=result.getString("status");
            if("PROCESSING".equals(status)) {
                store.waitUntil(id,token,LocalDateTime.now().plusSeconds(30)); return;
            }
            if(!"SUCCESS".equals(status)) throw new IllegalStateException("refund not successful: "+result.getString("code")+"/"+status);
            JSONObject refunded=result.getJSONObject("amount");
            if(!refundNumber.equals(result.getString("out_refund_no")) || !number.equals(result.getString("out_trade_no")) ||
                refunded==null || refunded.getBigDecimal("refund")==null ||
                refunded.getBigDecimal("refund").compareTo(amount.movePointRight(2))!=0)
                throw new IllegalStateException("refund response does not match task");
        }
        transactions.executeWithoutResult(status -> {
            // Match lifecycle lock ordering: order first, then job.
            List<Map<String,Object>> orders=jdbc.queryForList("select status,pay_status from orders where number=? for update",number);
            if(orders.size()!=1 || ((Number)orders.get(0).get("status")).intValue()!=6)
                throw new IllegalStateException("refund order is not cancelled");
            jdbc.update("update orders set pay_status=2 where number=? and status=6 and pay_status=1",number);
            store.done(id);
        });
    }
}
