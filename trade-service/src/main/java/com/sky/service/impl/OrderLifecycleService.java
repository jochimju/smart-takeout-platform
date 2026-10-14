package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.*;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OrderLifecycleService {
    private final OrderMapper orders;
    private final OrderDetailMapper details;
    private final TradeInventoryService inventory;
    private final UserCouponMapper coupons;
    private final SeckillReservationService reservations;
    private final OrderReliabilityStore jobs;
    private final BusinessEventOutbox outbox;
    private final JdbcTemplate jdbc;
    @Value("${sky.payment.mock-enabled:false}") private boolean mockEnabled;

    /** 优先使用订单显式截止时间；兼容旧订单没有 expire_time 的情况。 */
    public static LocalDateTime deadline(Orders order) {
        return order.getExpireTime()!=null ? order.getExpireTime() : order.getOrderTime().plusMinutes(15);
    }

    /** 超时消息只是触发器，关闭资格以锁住后的订单状态及截止时间为准。 */
    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void timeout(String number) {
        // for update 将本订单的超时、取消和到账处理串行化；拿锁后必须重判业务状态。
        Orders order=orders.lockByNumber(number);
        // 已有可靠性任务进入 FAILED 时暂停自动处理，等待人工重放。
        if(jobs.isFailed(number)) return;
        // 已取消或已付款的订单忽略迟到/重复的超时消息，不能再次退库存。
        if(order==null || !Orders.PENDING_PAYMENT.equals(order.getStatus())) {
            jobs.done("timeout:"+number);
            return;
        }
        LocalDateTime due=deadline(order);
        if(LocalDateTime.now().isBefore(due)) { jobs.early(number,due); return; }
        if(!Orders.UN_PAID.equals(order.getPayStatus()))
            throw new OrderBusinessException("pending order has inconsistent payment status");
        cancelLocked(order,"payment timeout, auto cancel",false,true);
    }

    /** USER 校验归属且仅允许待支付/待接单；REJECT 仅待接单；管理取消允许状态 1~4。 */
    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void cancel(Long id,String reason,String mode) {
        Orders order=orders.lockById(id);
        if(order==null) throw new OrderBusinessException("order not found");
        if("USER".equals(mode) && !Objects.equals(BaseContext.getCurrentId(),order.getUserId()))
            throw new OrderBusinessException("order not found");
        if(Orders.CANCELLED.equals(order.getStatus())) return;
        boolean allowed="REJECT".equals(mode) ? Orders.TO_BE_CONFIRMED.equals(order.getStatus()) :
            "USER".equals(mode) ? (order.getStatus()==1 || order.getStatus()==2) :
            (order.getStatus()>=1 && order.getStatus()<=4);
        if(!allowed) throw new OrderBusinessException("order status does not allow cancellation");
        cancelLocked(order,reason,"REJECT".equals(mode),false);
    }

    /**
     * 主动取消与超时关闭的共同收尾，调用方必须持有订单行锁及事务。
     * 状态更新、库存释放、优惠回退、退款任务和通知事件一起提交；任何一步失败全部回滚。
     * 超时关闭同样使用 CANCELLED(6)，通过取消原因与时间区分来源。
     */
    private void cancelLocked(Orders order,String reason,boolean rejected,boolean timeout) {
        // SQL 再次匹配原状态和支付状态；超时路径额外要求未付款且截止时间已到。
        int changed=orders.cancelIfCurrent(order.getId(),order.getStatus(),order.getPayStatus(),
            reason,rejected,timeout,LocalDateTime.now());
        if(changed!=1) throw new OrderBusinessException("order state changed; retry");
        // These writes commit together; no external refund call is made inside this transaction.
        // 秒杀预约先走专用释放；普通订单没有预约记录时，按订单明细退交易库存。
        if(!reservations.cancel(order)) {
            List<OrderDetail> lines=details.getByOrderId(order.getId());
            if(lines==null || lines.isEmpty()) throw new OrderBusinessException("order details missing");
            for(OrderDetail line:lines) if(line.getNumber()==null || line.getNumber()<=0)
                throw new OrderBusinessException("invalid order quantity");
            inventory.rollback(lines);
        }
        // 只释放绑定到本订单的优惠资源，不能按用户批量退回。
        coupons.rollbackByOrderId(order.getId());
        if (order.getUserRedPacketId() != null) {
            // 订单取消（含已支付后退款）都必须退回红包：status=1 是待支付时的预占，
            // status=2 是支付成功后核销的。只退 status=1 会把已支付订单的红包永久吃掉。
            jdbc.update("update user_red_packet set status=case when expire_time>now() then 0 else 3 end, food_order_id=null, used_time=null where food_order_id=? and status in (1,2)",
                    order.getId());
        }
        if(Orders.PAID.equals(order.getPayStatus())) {
            List<Map<String,Object>> receipt=jdbc.queryForList("select * from order_payment_receipt where order_number=?",order.getNumber());
            // Never infer a historical payment channel from today's mock switch.
            // Missing real receipts remain visible as failed refund jobs for reconciliation.
            boolean mock=!receipt.isEmpty() && asBoolean(receipt.get(0).get("mock_payment"));
            if(!receipt.isEmpty()) order.setAmount((BigDecimal)receipt.get(0).get("amount"));
            // 只登记异步退款任务，不在订单锁和本地事务内等待微信退款请求。
            jobs.refund(order,mock);
        }
        jobs.done("timeout:"+order.getNumber());
        order.setStatus(Orders.CANCELLED);
        com.sky.payment.PaymentRecoveryStore.close(jdbc,order.getNumber());
        // 只保存通知事件；后续发送与 WebSocket 推送异步执行。
        outbox.record("ORDER_CANCELLED", order.getId(), Map.of("type", 3, "orderId", order.getId(), "content", "order cancelled: " + order.getNumber()));
    }

    /** Returns true only for the first transition to a payable business order. */
    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean paid(String number,String transaction,BigDecimal amount,boolean mock) {
        return paid(number,transaction,amount,mock,null);
    }

    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean paid(String number,String transaction,BigDecimal amount,boolean mock,LocalDateTime channelPaidAt) {
        if(mock && !mockEnabled) throw new OrderBusinessException("mock payment is disabled");
        // for update 将本订单的超时、取消和到账处理串行化；拿锁后必须重判业务状态。
        Orders order=orders.lockByNumber(number);
        if(order==null) throw new OrderBusinessException("order not found");
        if(mock && !Objects.equals(BaseContext.getCurrentId(),order.getUserId())) throw new OrderBusinessException("order not found");
        if(amount==null) { if(!mock) throw new OrderBusinessException("payment amount missing"); amount=order.getAmount(); }
        if(transaction==null || transaction.isEmpty() || amount.compareTo(order.getAmount())!=0)
            throw new OrderBusinessException("payment details do not match order");
        List<Map<String,Object>> receipt=jdbc.queryForList("select * from order_payment_receipt where order_number=?",number);
        if(!receipt.isEmpty()) {
            Map<String,Object> old=receipt.get(0);
            if(!transaction.equals(old.get("transaction_id")) || amount.compareTo((BigDecimal)old.get("amount"))!=0 ||
                mock!=asBoolean(old.get("mock_payment"))) throw new OrderBusinessException("conflicting payment receipt");
            return false;
        }
        if(mock && (!Orders.PENDING_PAYMENT.equals(order.getStatus()) || !LocalDateTime.now().isBefore(deadline(order))))
            throw new OrderBusinessException("order is no longer payable");
        jdbc.update("insert into order_payment_receipt(order_number,transaction_id,amount,mock_payment) values(?,?,?,?)",
            number,transaction,amount,mock);
        LocalDateTime effectiveTime=channelPaidAt==null?LocalDateTime.now():channelPaidAt;
        if(Orders.PENDING_PAYMENT.equals(order.getStatus()) && !effectiveTime.isBefore(deadline(order)))
            cancelLocked(order,"payment received after deadline",false,true);
        // 真实到账晚于取消时，保持订单取消并安排退款，不恢复为待接单。
        if(Orders.CANCELLED.equals(order.getStatus())) {
            jdbc.update("update orders set pay_status=1,checkout_time=now() where id=? and status=6",order.getId());
            // 只登记异步退款任务，不在订单锁和本地事务内等待微信退款请求。
            jobs.refund(order,mock);
            jobs.done("timeout:"+number);
            return false;
        }
        if(!Orders.PENDING_PAYMENT.equals(order.getStatus()) || !Orders.UN_PAID.equals(order.getPayStatus()))
            throw new OrderBusinessException("payment state requires reconciliation");
        if(orders.markPaid(order.getId())!=1) throw new OrderBusinessException("payment state changed");
        outbox.record("PAYMENT_SUCCEEDED", order.getId(), Map.of("type", 1, "orderId", order.getId(), "content", "order number: " + number));
        if (order.getUserRedPacketId() != null) {
            jdbc.update("update user_red_packet set status=2,used_time=now() where food_order_id=? and status=1", order.getId());
        }
        jobs.done("timeout:"+number);
        return true;
    }

    public static boolean asBoolean(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number && ((Number)value).intValue()!=0);
    }
}
