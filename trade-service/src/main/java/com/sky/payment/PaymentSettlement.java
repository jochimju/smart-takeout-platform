package com.sky.payment;

import com.alibaba.fastjson.JSONObject;
import com.sky.service.RedPacketService;
import com.sky.service.impl.OrderLifecycleService;
import com.sky.utils.properties.WeChatProperties;
import java.math.BigDecimal;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One authoritative receipt path for verified callbacks and signed channel queries. */
@Service @RequiredArgsConstructor
public class PaymentSettlement {
    private final OrderLifecycleService orders;
    private final RedPacketService packets;
    private final WeChatProperties properties;
    private final JdbcTemplate jdbc;
    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void accept(JSONObject receipt) {
        String number=receipt.getString("out_trade_no"),transaction=receipt.getString("transaction_id");
        if (!properties.getMchid().equals(receipt.getString("mchid")) || !properties.getAppid().equals(receipt.getString("appid"))
                || !"SUCCESS".equals(receipt.getString("trade_state")) || number==null || !number.matches("[a-zA-Z0-9_-]{1,64}")
                || transaction==null || transaction.isBlank()) throw new SecurityException("Payment receipt identity/state mismatch");
        var amount=receipt.getJSONObject("amount");
        if(amount==null || !"CNY".equals(amount.getString("currency"))) throw new SecurityException("Payment receipt currency missing");
        BigDecimal cents=amount.getBigDecimal("total");
        if(cents==null || cents.signum()<=0 || cents.stripTrailingZeros().scale()>0) throw new SecurityException("Payment receipt amount invalid");
        OffsetDateTime channelTime=OffsetDateTime.parse(receipt.getString("success_time"));
        if(channelTime.toInstant().isAfter(Instant.now().plusSeconds(300))) throw new SecurityException("Payment time is in the future");
        var paidAt=channelTime.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        if(number.startsWith("RP")) packets.paySuccess(number,transaction,cents.movePointLeft(2));
        else orders.paid(number,transaction,cents.movePointLeft(2),false,paidAt);
        if(jdbc.update("update order_payment_receipt set channel_paid_at=? where order_number=? and transaction_id=? and mock_payment=0 and (channel_paid_at is null or channel_paid_at=?)",paidAt,number,transaction,paidAt)!=1)
            throw new SecurityException("Payment receipt timestamp conflict");
    }
}
