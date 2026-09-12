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
    private final DishMapper dishes;
    private final SetmealMapper setmeals;
    private final UserCouponMapper coupons;
    private final SeckillReservationService reservations;
    private final OrderReliabilityStore jobs;
    private final JdbcTemplate jdbc;
    @Value("${sky.payment.mock-enabled:false}") private boolean mockEnabled;

    public static LocalDateTime deadline(Orders order) {
        return order.getExpireTime()!=null ? order.getExpireTime() : order.getOrderTime().plusMinutes(15);
    }

    @Transactional(rollbackFor=Exception.class)
    public void timeout(String number) {
        Orders order=orders.lockByNumber(number);
        if(jobs.isFailed(number)) return;
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

    @Transactional(rollbackFor=Exception.class)
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

    private void cancelLocked(Orders order,String reason,boolean rejected,boolean timeout) {
        int changed=orders.cancelIfCurrent(order.getId(),order.getStatus(),order.getPayStatus(),
            reason,rejected,timeout,LocalDateTime.now());
        if(changed!=1) throw new OrderBusinessException("order state changed; retry");
        // These writes commit together; no external refund call is made inside this transaction.
        if(!reservations.cancel(order)) {
            List<OrderDetail> lines=details.getByOrderId(order.getId());
            if(lines==null || lines.isEmpty()) throw new OrderBusinessException("order details missing");
            for(OrderDetail line:lines) {
                if(line.getNumber()==null || line.getNumber()<=0) throw new OrderBusinessException("invalid order quantity");
                int updated=line.getDishId()!=null ? dishes.rollbackStock(line.getDishId(),line.getNumber()) :
                    line.getSetmealId()!=null ? setmeals.rollbackStock(line.getSetmealId(),line.getNumber()) : 0;
                if(updated!=1) throw new OrderBusinessException("stock item missing");
            }
        }
        coupons.rollbackByOrderId(order.getId());
        if(Orders.PAID.equals(order.getPayStatus())) {
            List<Map<String,Object>> receipt=jdbc.queryForList("select * from order_payment_receipt where order_number=?",order.getNumber());
            // Never infer a historical payment channel from today's mock switch.
            // Missing real receipts remain visible as failed refund jobs for reconciliation.
            boolean mock=!receipt.isEmpty() && asBoolean(receipt.get(0).get("mock_payment"));
            if(!receipt.isEmpty()) order.setAmount((BigDecimal)receipt.get(0).get("amount"));
            jobs.refund(order,mock);
        }
        jobs.done("timeout:"+order.getNumber());
        order.setStatus(Orders.CANCELLED);
    }

    /** Returns true only for the first transition to a payable business order. */
    @Transactional(rollbackFor=Exception.class)
    public boolean paid(String number,String transaction,BigDecimal amount,boolean mock) {
        if(mock && !mockEnabled) throw new OrderBusinessException("mock payment is disabled");
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
        if(Orders.PENDING_PAYMENT.equals(order.getStatus()) && !LocalDateTime.now().isBefore(deadline(order)))
            cancelLocked(order,"payment received after deadline",false,true);
        if(Orders.CANCELLED.equals(order.getStatus())) {
            jdbc.update("update orders set pay_status=1,checkout_time=now() where id=? and status=6",order.getId());
            jobs.refund(order,mock);
            jobs.done("timeout:"+number);
            return false;
        }
        if(!Orders.PENDING_PAYMENT.equals(order.getStatus()) || !Orders.UN_PAID.equals(order.getPayStatus()))
            throw new OrderBusinessException("payment state requires reconciliation");
        if(orders.markPaid(order.getId())!=1) throw new OrderBusinessException("payment state changed");
        jobs.done("timeout:"+number);
        return true;
    }

    public static boolean asBoolean(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number && ((Number)value).intValue()!=0);
    }
}
