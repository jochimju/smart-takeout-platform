package com.sky.service.impl;

import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.SeckillOrderSubmitDTO;
import com.sky.dto.SeckillActivityDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.SeckillActivity;
import com.sky.entity.Setmeal;
import com.sky.entity.UserRedPacket;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.SeckillActivityMapper;
import com.sky.mapper.SeckillOrderGuardMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.RedPacketMapper;
import com.sky.service.SeckillService;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.SeckillActivityVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.UUID;
import java.util.List;

/** Redis + Lua 原子预扣库存、资格校验与一人一单的套餐秒杀服务。 */
@Service
@Slf4j
public class SeckillServiceImpl implements SeckillService {


    @Autowired private SeckillActivityMapper seckillActivityMapper;
    @Autowired private SeckillOrderGuardMapper seckillOrderGuardMapper;
    @Autowired private SetmealMapper setmealMapper;
    @Autowired private AddressBookMapper addressBookMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private OrderDetailMapper orderDetailMapper;
    @Autowired private OrderReliabilityStore jobs;
    @Autowired private SeckillCache cache;
    @Autowired private com.sky.mapper.SeckillReservationMapper reservationMapper;
    @Autowired private RedPacketMapper redPacketMapper;


    

    

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderSubmitVO seckillOrder(SeckillOrderSubmitDTO request) {
        validateRequest(request);
        Long userId=BaseContext.getCurrentId();
        SeckillActivity activity=seckillActivityMapper.lock(request.getActivityId());
        String fingerprint=fingerprint(request);
        com.sky.entity.SeckillReservation previous=reservationMapper.byRequest(userId,request.getRequestId());
        if(previous!=null) {
            if(!fingerprint.equals(previous.getRequestHash())) throw new OrderBusinessException("请求编号已被其他参数使用");
            Orders existing=orderMapper.getByNumber(previous.getOrderNumber());
            if(existing==null) throw new OrderBusinessException("订单状态待核对，请稍后查询");
            return submitVO(existing);
        }
        validateActivityAndSetmeal(activity,request.getSetmealId());
        Long userRedPacketId=resolveRedPacketId(userId,request.getUserRedPacketId(),request.getUseRedPacket());
        java.math.BigDecimal discount=calculateRedPacketDiscount(userId,userRedPacketId,activity.getSeckillPrice());
        java.math.BigDecimal payable=activity.getSeckillPrice().subtract(discount).add(new java.math.BigDecimal("7.00"));
        if(request.getAmount()!=null && request.getAmount().compareTo(payable)!=0)
            throw new OrderBusinessException("秒杀价格已变更，请返回重新选择套餐");
        Setmeal setmeal=setmealMapper.getById(request.getSetmealId());
        if(setmeal==null || !StatusConstant.ENABLE.equals(setmeal.getStatus())) throw new OrderBusinessException("秒杀套餐不可购买");
        AddressBook address=addressBookMapper.getById(request.getAddressBookId());
        if(address==null || !userId.equals(address.getUserId())) throw new OrderBusinessException("收货地址不存在");
        // Rebuild under the activity row lock, so stale/failed Redis predebits never become authoritative.
        try {
            cache.rebuild(activity);
            assertSeckillResult(cache.reserve(activity,userId,request.getRequestId()));
        } catch(org.springframework.dao.DataAccessException e) {
            throw new OrderBusinessException("秒杀缓存暂不可用，请稍后使用同一请求重试");
        }
        if(seckillActivityMapper.reserve(activity.getId())!=1) throw new OrderBusinessException("库存不足或活动已结束");
        String number=createOrderNumber();
        try { seckillOrderGuardMapper.insertGuard(userId,activity.getId(),setmeal.getId(),number); }
        catch(DuplicateKeyException e) { throw new OrderBusinessException("您已购买过该秒杀活动"); }
        LocalDateTime now=LocalDateTime.now();
        Orders order=buildOrder(request,userId,address,setmeal,activity.getSeckillPrice(),discount,userRedPacketId,number,now);
        order.setExpireTime(now.plusMinutes(15));
        orderMapper.insert(order);
        reserveRedPacket(userId,userRedPacketId,order.getId());
        reservationMapper.insert(number,activity.getId(),setmeal.getId(),userId);
        reservationMapper.setRequest(number,request.getRequestId(),fingerprint);
        orderDetailMapper.insertBatch(Collections.singletonList(OrderDetail.builder().name(setmeal.getName())
            .orderId(order.getId()).setmealId(setmeal.getId()).number(1).amount(activity.getSeckillPrice()).image(setmeal.getImage()).build()));
        // The timeout event commits with the order; a broker outage cannot lose it.
        jobs.scheduleTimeout(order);
        return submitVO(order);
    }

    @Override
    @Transactional
    public void warmUpActivity(Long activityId) {
        SeckillActivity activity=seckillActivityMapper.lock(activityId);
        if(activity==null) throw new OrderBusinessException("秒杀活动不存在");
        cache.rebuild(activity);
    }

    @Override
    @Transactional
    public Integer getSeckillStock(Long activityId) {
        SeckillActivity activity=seckillActivityMapper.lock(activityId);
        if(activity==null) throw new OrderBusinessException("秒杀活动不存在");
        cache.rebuild(activity);
        return activity.getRemainingStock();
    }

    @Override
    public List<SeckillActivityVO> listAvailableActivities() {
        List<SeckillActivityVO> activities = seckillActivityMapper.listAvailable();
        for (SeckillActivityVO activity : activities) {
            activity.setBeginTimestamp(toMillis(activity.getBeginTime()));
            activity.setEndTimestamp(toMillis(activity.getEndTime()));
        }
        return activities;
    }

    @Override
    public List<SeckillActivityVO> listActivities() {
        return seckillActivityMapper.list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveActivity(SeckillActivityDTO dto) {
        if (dto == null || dto.getSetmealId() == null || dto.getStock() == null || dto.getStock() < 0 ||
                dto.getSeckillPrice() == null || dto.getSeckillPrice().signum() < 0 ||
                dto.getBeginTime() == null || dto.getEndTime() == null || !dto.getEndTime().isAfter(dto.getBeginTime())) {
            throw new OrderBusinessException("请完整填写秒杀套餐、价格、库存和有效时间");
        }
        Setmeal setmeal = setmealMapper.getById(dto.getSetmealId());
        if (setmeal == null || !StatusConstant.ENABLE.equals(setmeal.getStatus())) {
            throw new OrderBusinessException("请选择已启售套餐");
        }
        if (dto.getSeckillPrice().compareTo(setmeal.getPrice()) >= 0) {
            throw new OrderBusinessException("秒杀价必须低于套餐原价");
        }
        SeckillActivity activity = new SeckillActivity();
        activity.setId(dto.getId()); activity.setSetmealId(dto.getSetmealId()); activity.setStock(dto.getStock());
        // Lua 用户集合与数据库唯一索引共同保障一人一单，因此当前活动仅允许配置每人 1 份。
        if (dto.getPurchaseLimit() != null && dto.getPurchaseLimit() != 1) {
            throw new OrderBusinessException("当前秒杀活动仅支持每人限购 1 份");
        }
        activity.setPurchaseLimit(1);
        activity.setSeckillPrice(dto.getSeckillPrice()); activity.setBeginTime(dto.getBeginTime());
        activity.setEndTime(dto.getEndTime()); activity.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        if(activity.getId()==null) seckillActivityMapper.insert(activity);
        else {
            SeckillActivity previous=seckillActivityMapper.lock(activity.getId());
            if(previous==null) throw new OrderBusinessException("秒杀活动不存在");
            if(!previous.getSetmealId().equals(activity.getSetmealId())) throw new OrderBusinessException("已创建活动不能更换套餐");
            if(!previous.getStock().equals(activity.getStock())) throw new OrderBusinessException("请通过库存调整接口修改总库存");
            // 一人一单资格绑定活动 ID。旧活动一旦已有购买记录，不能通过编辑将其重新启用，
            // 否则历史购买用户会继续被拦截，与“新一轮秒杀”的业务语义不一致。
            boolean hasPurchases=reservationMapper.countForActivity(previous.getId())>0;
            boolean wasInactive=!StatusConstant.ENABLE.equals(previous.getStatus()) || !previous.getEndTime().isAfter(LocalDateTime.now());
            if(hasPurchases && wasInactive && StatusConstant.ENABLE.equals(activity.getStatus()))
                throw new OrderBusinessException("已有购买记录的活动不能重新上架，请使用“再次上架”创建新活动");
            seckillActivityMapper.update(activity);
        }
        return activity.getId();
    }

    @Override
    @Transactional
    public void deleteActivity(Long activityId) {
        SeckillActivity activity=seckillActivityMapper.lock(activityId);
        if(activity==null) return;
        if(reservationMapper.countForActivity(activityId)>0) throw new OrderBusinessException("已有订单的活动只能停用，不能删除");
        if (seckillActivityMapper.deleteById(activityId) != 1) {
            throw new OrderBusinessException("秒杀活动删除失败，请刷新后重试");
        }
        cache.evict(activityId);
    }

    

    

    private void validateRequest(SeckillOrderSubmitDTO request) {
        if (request == null || request.getActivityId() == null || request.getSetmealId() == null || request.getAddressBookId() == null) {
            throw new OrderBusinessException("秒杀活动、套餐和收货地址不能为空");
        }
        if(request.getRequestId()==null || !request.getRequestId().matches("[A-Za-z0-9_-]{16,64}"))
            throw new OrderBusinessException("请求编号缺失或无效，请重新进入结算页");
        if (BaseContext.getCurrentId() == null) {
            throw new OrderBusinessException("用户未登录");
        }
    }

    private void validateActivityAndSetmeal(SeckillActivity activity, Long setmealId) {
        if (activity == null || !StatusConstant.ENABLE.equals(activity.getStatus())) throw new OrderBusinessException("秒杀活动未启用");
        if (!setmealId.equals(activity.getSetmealId())) throw new OrderBusinessException("套餐不属于该秒杀活动");
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getBeginTime())) throw new OrderBusinessException("秒杀尚未开始");
        if (now.isAfter(activity.getEndTime())) throw new OrderBusinessException("秒杀已结束");
    }

    private void assertSeckillResult(Long result) {
        if (result == null) throw new OrderBusinessException("秒杀服务暂不可用");
        if (result == 1L) return;
        String[] messages = {"库存不足", "您已购买过该秒杀套餐", "秒杀活动未启用", "套餐不属于该秒杀活动", "秒杀尚未开始", "秒杀已结束"};
        int index = result.intValue() == 0 ? 0 : -result.intValue();
        throw new OrderBusinessException(index >= 0 && index < messages.length ? messages[index] : "秒杀资格校验失败");
    }

    private Orders buildOrder(SeckillOrderSubmitDTO request, Long userId, AddressBook address, Setmeal setmeal,
                              java.math.BigDecimal seckillPrice, java.math.BigDecimal discount, Long userRedPacketId,
                              String number, LocalDateTime now) {
        return Orders.builder().number(number).userId(userId).addressBookId(address.getId())
                .status(Orders.PENDING_PAYMENT).payStatus(Orders.UN_PAID).payMethod(request.getPayMethod())
                .amount(seckillPrice.subtract(discount).add(new java.math.BigDecimal("7.00"))).discountAmount(discount)
                .userRedPacketId(userRedPacketId)
                .remark(request.getRemark()).phone(address.getPhone()).address(address.getDetail())
                .consignee(address.getConsignee()).orderTime(now).estimatedDeliveryTime(request.getEstimatedDeliveryTime())
                // 结算页未传配送和餐具选项时使用默认值，避免写入 NOT NULL 列失败。
                .deliveryStatus(request.getDeliveryStatus() == null ? 1 : request.getDeliveryStatus())
                .packAmount(1)
                .tablewareNumber(request.getTablewareNumber() == null ? 0 : request.getTablewareNumber())
                .tablewareStatus(request.getTablewareStatus() == null ? 1 : request.getTablewareStatus()).build();
    }

    private Long resolveRedPacketId(Long userId, Long requestedId, Boolean useRedPacket) {
        if (Boolean.FALSE.equals(useRedPacket)) return null;
        if (requestedId != null) return requestedId;
        List<UserRedPacket> packets=redPacketMapper.availableByUser(userId);
        return packets.isEmpty() ? null : packets.get(0).getId();
    }

    private java.math.BigDecimal calculateRedPacketDiscount(Long userId, Long redPacketId, java.math.BigDecimal foodAmount) {
        if (redPacketId == null) return java.math.BigDecimal.ZERO;
        UserRedPacket packet=redPacketMapper.byIdAndUser(redPacketId,userId);
        if (packet==null || !UserRedPacket.UNUSED.equals(packet.getStatus()) || packet.getExpireTime()==null
                || !packet.getExpireTime().isAfter(LocalDateTime.now())) throw new OrderBusinessException("红包不可用，请重新选择");
        return packet.getAmount().min(foodAmount);
    }

    private void reserveRedPacket(Long userId, Long redPacketId, Long orderId) {
        if (redPacketId != null && redPacketMapper.reserve(redPacketId,userId,orderId)!=1)
            throw new OrderBusinessException("红包已被使用或已过期");
    }

    private long toMillis(LocalDateTime time) { return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    private String createOrderNumber() { return "SK" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8); }

    @Override
    @Transactional
    public void adjustStock(Long id, int targetTotal, int expectedTotal) {
        SeckillActivity activity=seckillActivityMapper.lock(id);
        if(activity==null) throw new OrderBusinessException("秒杀活动不存在");
        if(activity.getStock()==targetTotal) return; // Retrying an applied adjustment does not add stock twice.
        if(activity.getStock()!=expectedTotal) throw new OrderBusinessException("库存已被修改，请刷新后再调整");
        if(targetTotal<0 || targetTotal<activity.getStock()-activity.getRemainingStock())
            throw new OrderBusinessException("总库存不能低于已占用库存");
        if(seckillActivityMapper.adjust(id,targetTotal-activity.getStock())!=1) throw new OrderBusinessException("库存调整失败");
    }

    private OrderSubmitVO submitVO(Orders order) {
        return OrderSubmitVO.builder().id(order.getId()).orderNumber(order.getNumber())
            .orderAmount(order.getAmount()).orderTime(order.getOrderTime()).build();
    }
    @Override
    public OrderSubmitVO findRequest(String requestId) {
        if(BaseContext.getCurrentId()==null) throw new OrderBusinessException("用户未登录");
        com.sky.entity.SeckillReservation reservation=reservationMapper.byRequest(BaseContext.getCurrentId(),requestId);
        if(reservation==null) return null;
        Orders order=orderMapper.getByNumber(reservation.getOrderNumber());
        if(order==null) throw new OrderBusinessException("订单状态待核对");
        return submitVO(order);
    }
    private String fingerprint(SeckillOrderSubmitDTO request) {
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(
                com.alibaba.fastjson.JSON.toJSONString(request,com.alibaba.fastjson.serializer.SerializerFeature.SortField).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder();
            for(byte b:digest) result.append(String.format("%02x",b & 255));
            return result.toString();
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
