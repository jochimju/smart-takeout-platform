package com.sky.service.impl;

import com.sky.contract.account.AccountAddressView;
import com.sky.contract.account.AccountAddressLookup;

import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.observability.SeckillTiming;
import com.sky.dto.SeckillOrderSubmitDTO;
import com.sky.dto.SeckillActivityDTO;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.SeckillActivity;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.entity.UserRedPacket;
import com.sky.exception.OrderBusinessException;
import com.sky.client.AccountClient;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.SeckillActivityMapper;
import com.sky.mapper.SeckillOrderGuardMapper;
import com.sky.contract.catalog.ProductQuote;
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
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;

/** Redis + Lua 原子预扣库存、资格校验与一人一单的套餐秒杀服务。 */
@Service
@Slf4j
public class SeckillServiceImpl implements SeckillService {


    @Autowired private SeckillActivityMapper seckillActivityMapper;
    @Autowired private SeckillOrderGuardMapper seckillOrderGuardMapper;
    @Autowired private CatalogQuoteService catalogQuoteService;
    @Autowired private AccountClient accountClient;
    @Autowired private OrderMapper orderMapper;
    @Autowired private OrderDetailMapper orderDetailMapper;
    @Autowired private SeckillAsyncStore asyncStore;
    @Autowired private SeckillCache cache;
    @Autowired private TradeInventoryService tradeInventoryService;
    @Autowired private com.sky.mapper.SeckillReservationMapper reservationMapper;
    @Autowired private RedPacketMapper redPacketMapper;
    @Autowired private com.sky.service.mq.OrderMessagePublisher orderMessagePublisher;
    @org.springframework.beans.factory.annotation.Value("${sky.seckill.order-batch-enabled:true}")
    private boolean orderBatchEnabled=true;
    @org.springframework.beans.factory.annotation.Value("${sky.seckill.stock-buckets:8}") private int stockBuckets=8;


    

    

    @Override
    public OrderSubmitVO seckillOrder(SeckillOrderSubmitDTO request) {
        validateRequest(request);
        com.sky.dto.SeckillOrderMessageDTO command=com.sky.dto.SeckillOrderMessageDTO.builder()
            .activityId(request.getActivityId()).setmealId(request.getSetmealId()).userId(BaseContext.getCurrentId())
            .orderNumber(createOrderNumber()).request(request).requestHash(fingerprint(request)).build();
        try { return cache.accept(command); }
        catch(org.springframework.dao.DataAccessException e) {
            throw new OrderBusinessException("秒杀受理结果未知，请使用同一请求重试或查询");
        }
    }

    /** Only MQ consumers invoke creation; HTTP has no synchronous fallback. */
    @Override
    @Transactional(rollbackFor=Exception.class)
    public void createSeckillOrder(com.sky.dto.SeckillOrderMessageDTO command) {
        SeckillTiming.transaction();
        SeckillTiming.run("body", () -> createSeckillOrderBody(command));
    }

    private void createSeckillOrderBody(com.sky.dto.SeckillOrderMessageDTO received) {
        var prepared=prepareSeckillOrder(received);
        if(prepared==null)return;
        var activity=SeckillTiming.measure("activity_lock",()->SeckillInventory.lock(seckillActivityMapper,prepared.command()));
        boolean created=writePreparedOrder(prepared,activity);
        SeckillTiming.outcome(created?"created":"skipped");
    }

    /** Invoked by the batch coordinator outside the write transaction. */
    @Override public com.sky.service.PreparedSeckillOrder prepareSeckillOrder(com.sky.dto.SeckillOrderMessageDTO received) {
        Map<String,Object> snapshot=SeckillTiming.measure("snapshot", () -> asyncStore.get(received.getOrderNumber()));
        if(snapshot==null) throw new SeckillAsyncStore.MissingAcceptance(received.getOrderNumber());
        if(!"ACCEPTED".equals(snapshot.get("state"))) { SeckillTiming.outcome("skipped"); return null; }
        // Database acceptance snapshot is authoritative; do not trust a redelivered payload.
        com.sky.dto.SeckillOrderMessageDTO command=com.alibaba.fastjson.JSON.parseObject((String)snapshot.get("command_body"),com.sky.dto.SeckillOrderMessageDTO.class);
        SeckillOrderSubmitDTO request=command.getRequest();
        Long userId=command.getUserId();
        ProductQuote quote=SeckillTiming.measure("quote", () -> catalogQuoteService.quoteSetmeal(command.getSetmealId()));
        if(!StatusConstant.ENABLE.equals(quote.getStatus())) throw new OrderBusinessException("秒杀套餐不可购买");
        AccountAddressView address=SeckillTiming.measure("address", () -> accountClient.address(userId,request.getAddressBookId()));
        if(address==null || !userId.equals(address.getUserId())) throw new OrderBusinessException("收货地址不存在");
        return new com.sky.service.PreparedSeckillOrder(command,quote,address);
    }

    @Override public com.sky.service.SeckillPreparation prepareSeckillOrders(List<com.sky.dto.SeckillOrderMessageDTO> received) {
        if(received.isEmpty()) return new com.sky.service.SeckillPreparation(List.of(),Map.of());
        if(received.size()>16) throw new IllegalArgumentException("Seckill preparation batch exceeds 16");
        var snapshots=SeckillTiming.measure("snapshot",()->asyncStore.getBatch(received.stream().map(com.sky.dto.SeckillOrderMessageDTO::getOrderNumber).toList()));
        var commands=new ArrayList<com.sky.dto.SeckillOrderMessageDTO>();
        var failures=new LinkedHashMap<String,Exception>();
        for(var delivery:received) {
            try {
                var row=snapshots.get(delivery.getOrderNumber());
                if(row==null) throw new SeckillAsyncStore.MissingAcceptance(delivery.getOrderNumber());
                if(!"ACCEPTED".equals(row.get("state"))) continue;
                var command=com.alibaba.fastjson.JSON.parseObject((String)row.get("command_body"),com.sky.dto.SeckillOrderMessageDTO.class);
                if(command==null || command.getRequest()==null || command.getSetmealId()==null || command.getUserId()==null || command.getRequest().getAddressBookId()==null)
                    throw new OrderBusinessException("秒杀受理资料不完整");
                commands.add(command);
            } catch(Exception failure) {failures.put(delivery.getOrderNumber(),failure);}
        }
        if(commands.isEmpty()) return new com.sky.service.SeckillPreparation(List.of(),failures);
        var setmeals=commands.stream().map(com.sky.dto.SeckillOrderMessageDTO::getSetmealId).distinct()
                .map(id->ShoppingCart.builder().setmealId(id).build()).toList();
        var quotes=SeckillTiming.measure("quote",()->catalogQuoteService.quotes(setmeals));
        var lookups=commands.stream().map(command->new AccountAddressLookup(command.getUserId(),command.getRequest().getAddressBookId())).distinct().toList();
        var addresses=SeckillTiming.measure("address",()->accountClient.addresses(lookups));
        if(addresses==null) throw new com.sky.exception.RemoteServiceUnavailableException("用户服务暂时不可用，请稍后重试");
        var owned=new HashMap<AccountAddressLookup,AccountAddressView>();
        addresses.forEach(address->owned.put(new AccountAddressLookup(address.getUserId(),address.getId()),address));
        var ready=new ArrayList<com.sky.service.PreparedSeckillOrder>();
        for(var command:commands) {
            var quote=quotes.get(com.sky.contract.catalog.ProductQuote.SETMEAL+":"+command.getSetmealId());
            var address=owned.get(new AccountAddressLookup(command.getUserId(),command.getRequest().getAddressBookId()));
            if(quote==null || !StatusConstant.ENABLE.equals(quote.getStatus()))
                failures.put(command.getOrderNumber(),new OrderBusinessException("秒杀套餐不可购买"));
            else if(address==null) failures.put(command.getOrderNumber(),new OrderBusinessException("收货地址不存在"));
            else ready.add(new com.sky.service.PreparedSeckillOrder(command,quote,address));
        }
        return new com.sky.service.SeckillPreparation(ready,failures);
    }

    /** Same-activity small batch: one lock and commit, still conditional stock debit per order. */
    @Override @Transactional(rollbackFor=Exception.class)
    public void createPreparedOrders(List<com.sky.service.PreparedSeckillOrder> prepared) {
        if(prepared.isEmpty())return;
        if(prepared.size()>16)throw new IllegalArgumentException("Seckill fulfillment batch exceeds 16");
        Long activityId=prepared.get(0).command().getActivityId();
        if(prepared.stream().anyMatch(row->!activityId.equals(row.command().getActivityId()) || row.command().inventoryBucket()!=prepared.get(0).command().inventoryBucket() || row.command().inventoryBuckets()!=prepared.get(0).command().inventoryBuckets()))throw new IllegalArgumentException("Mixed activity transaction");
        SeckillTiming.transaction();
        SeckillTiming.run("body",()->{
            var activity=SeckillTiming.measure("activity_lock",()->SeckillInventory.lock(seckillActivityMapper,prepared.get(0).command()));
            int created=prepared.size()==1 ? (writePreparedOrder(prepared.get(0),activity)?1:0)
                    :writePreparedBatch(prepared,activity);
            SeckillTiming.outcome(created>0?"created":"skipped");
        });
    }

    private record BatchRow(com.sky.service.PreparedSeckillOrder prepared,Long packet,
                            java.math.BigDecimal discount) {}

    private int writePreparedBatch(List<com.sky.service.PreparedSeckillOrder> prepared,SeckillActivity activity) {
        var numbers=prepared.stream().map(row->row.command().getOrderNumber()).distinct().sorted().toList();
        var locked=SeckillTiming.measure("request_lock",()->asyncStore.lockBatch(numbers));
        var rows=new ArrayList<BatchRow>();
        var seen=new java.util.HashSet<String>();
        for(var item:prepared) {
            var command=item.command();var request=command.getRequest();
            if(!seen.add(command.getOrderNumber()))continue;
            var acceptance=locked.get(command.getOrderNumber());
            if(acceptance==null)throw new SeckillAsyncStore.MissingAcceptance(command.getOrderNumber());
            if(!"ACCEPTED".equals(acceptance.get("state"))) {SeckillTiming.count("orders_skipped",1);continue;}
            if(((Number)acceptance.get("expired")).intValue()==1)
                throw new OrderBusinessException("秒杀建单等待超时，请重新抢购");
            if(activity==null || !command.getSetmealId().equals(activity.getSetmealId()))
                throw new OrderBusinessException("秒杀活动资料不一致");
            Long packet=resolveRedPacketId(command.getUserId(),request.getUserRedPacketId(),request.getUseRedPacket());
            var discount=calculateRedPacketDiscount(command.getUserId(),packet,command.getPrice());
            var payable=command.getPrice().subtract(discount).add(new java.math.BigDecimal("7.00"));
            if(request.getAmount()!=null && request.getAmount().compareTo(payable)!=0)
                throw new OrderBusinessException("秒杀金额或红包已变化，请重新结算");
            rows.add(new BatchRow(item,packet,discount));
        }
        if(rows.isEmpty())return 0;
        if(SeckillTiming.measure("stock_update",()->SeckillInventory.debit(seckillActivityMapper,prepared.get(0).command(),rows.size()))!=1)
            throw new OrderBusinessException("秒杀库存核对失败，未创建订单");
        var reservations=new ArrayList<com.sky.entity.SeckillReservation>();
        for(var row:rows) {
            var command=row.prepared().command();var reservation=new com.sky.entity.SeckillReservation();
            reservation.setOrderNumber(command.getOrderNumber());reservation.setActivityId(activity.getId());
            reservation.setSetmealId(command.getSetmealId());reservation.setUserId(command.getUserId());
            reservation.setBucketId(command.inventoryBucket());
            reservation.setRequestId(command.getRequest().getRequestId());reservation.setRequestHash(command.getRequestHash());
            reservations.add(reservation);
        }
        try {SeckillTiming.run("guard_insert",()->seckillOrderGuardMapper.insertBatch(reservations));}
        catch(DuplicateKeyException e) {throw new OrderBusinessException("您已购买过该秒杀活动");}
        var orders=new ArrayList<Orders>();var details=new ArrayList<OrderDetail>();
        for(var row:rows) {
            var command=row.prepared().command();var quote=row.prepared().quote();var request=command.getRequest();
            var setmeal=Setmeal.builder().id(quote.getProductId()).name(quote.getName()).image(quote.getImage()).build();
            var now=LocalDateTime.now();
            var order=buildOrder(request,command.getUserId(),row.prepared().address(),setmeal,command.getPrice(),
                    row.discount(),row.packet(),command.getOrderNumber(),now);
            order.setExpireTime(now.plusMinutes(15).withNano(0));
            orders.add(order);
        }
        int inserted=SeckillTiming.measure("order_insert",()->{
            if(orderBatchEnabled)return orderMapper.insertSeckillBatch(orders);
            orders.forEach(orderMapper::insert);return orders.size();
        });
        if(inserted!=orders.size() || orders.stream().anyMatch(order->order.getId()==null) ||
                orders.stream().map(Orders::getId).distinct().count()!=orders.size())
            throw new IllegalStateException("Seckill batch order keys were not returned correctly");
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);var command=row.prepared().command();var quote=row.prepared().quote();var order=orders.get(i);
            reserveRedPacket(command.getUserId(),row.packet(),order.getId());
            details.add(OrderDetail.builder().name(quote.getName()).orderId(order.getId()).setmealId(quote.getProductId())
                    .number(1).amount(command.getPrice()).image(quote.getImage()).build());
        }
        SeckillTiming.run("reservation",()->reservationMapper.insertBatch(reservations));
        SeckillTiming.run("details",()->orderDetailMapper.insertBatch(details));
        SeckillTiming.run("success_state",()->asyncStore.succeededBatch(rows.stream().map(row->row.prepared().command()).toList()));
        SeckillTiming.run("timeout_outbox",()->orderMessagePublisher.timeoutDeferredBatch(orders));
        SeckillTiming.count("orders_created",rows.size());
        return rows.size();
    }

    private boolean writePreparedOrder(com.sky.service.PreparedSeckillOrder prepared,SeckillActivity activity) {
        var command=prepared.command();var quote=prepared.quote();var address=prepared.address();
        var request=command.getRequest();Long userId=command.getUserId();
        Map<String,Object> accepted=SeckillTiming.measure("request_lock", () -> asyncStore.lock(command.getOrderNumber()));
        if(accepted==null)throw new SeckillAsyncStore.MissingAcceptance(command.getOrderNumber());
        if(!"ACCEPTED".equals(accepted.get("state"))) { SeckillTiming.count("orders_skipped",1); return false; }
        if(((Number)accepted.get("expired")).intValue()==1) throw new OrderBusinessException("秒杀建单等待超时，请重新抢购");
        if(activity==null || !command.getSetmealId().equals(activity.getSetmealId())) throw new OrderBusinessException("秒杀活动资料不一致");
        Long packet=resolveRedPacketId(userId,request.getUserRedPacketId(),request.getUseRedPacket());
        java.math.BigDecimal discount=calculateRedPacketDiscount(userId,packet,command.getPrice());
        java.math.BigDecimal payable=command.getPrice().subtract(discount).add(new java.math.BigDecimal("7.00"));
        if(request.getAmount()!=null && request.getAmount().compareTo(payable)!=0) throw new OrderBusinessException("秒杀金额或红包已变化，请重新结算");
        if(SeckillTiming.measure("stock_update", () -> SeckillInventory.debit(seckillActivityMapper,command,1))!=1) throw new OrderBusinessException("秒杀库存核对失败，未创建订单");
        try { SeckillTiming.run("guard_insert", () -> seckillOrderGuardMapper.insertGuard(userId,activity.getId(),command.getSetmealId(),command.getOrderNumber())); }
        catch(DuplicateKeyException e) { throw new OrderBusinessException("您已购买过该秒杀活动"); }
        LocalDateTime now=LocalDateTime.now();
        Setmeal setmeal=Setmeal.builder().id(quote.getProductId()).name(quote.getName()).image(quote.getImage()).build();
        Orders order=buildOrder(request,userId,address,setmeal,command.getPrice(),discount,packet,command.getOrderNumber(),now);
        // orders.expire_time is DATETIME(0): keep the outbox deadline identical to the stored value.
        order.setExpireTime(now.plusMinutes(15).withNano(0));
        SeckillTiming.run("order_insert", () -> orderMapper.insert(order));
        reserveRedPacket(userId,packet,order.getId());
        SeckillTiming.run("reservation", () -> {
            reservationMapper.insert(command.getOrderNumber(),activity.getId(),command.getSetmealId(),userId);
            reservationMapper.setRequest(command.getOrderNumber(),request.getRequestId(),command.getRequestHash());
            reservationMapper.setBucket(command.getOrderNumber(),command.inventoryBucket());
        });
        SeckillTiming.run("details", () -> orderDetailMapper.insertBatch(Collections.singletonList(OrderDetail.builder().name(setmeal.getName())
            .orderId(order.getId()).setmealId(setmeal.getId()).number(1).amount(command.getPrice()).image(setmeal.getImage()).build())));
        SeckillTiming.run("success_state", () -> asyncStore.succeeded(command));
        SeckillTiming.run("timeout_outbox", () -> orderMessagePublisher.timeoutDeferred(order.getNumber(),order.getExpireTime()));
        SeckillTiming.count("orders_created",1);
        return true;
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void warmUpActivity(Long activityId) {
        SeckillActivity activity=seckillActivityMapper.lock(activityId);
        if(activity==null) throw new OrderBusinessException("秒杀活动不存在");
        if(Integer.valueOf(1).equals(activity.getRuntimeRetired()))throw new OrderBusinessException("活动已退役，不能重新启用");
        if(Integer.valueOf(1).equals(activity.getAsyncInitialized())) {
            if(!cache.ready(activityId) && cache.prepared(activityId)) cache.enable(activityId);
            if(!cache.ready(activityId)) throw new OrderBusinessException("秒杀缓存缺失或未就绪，已暂停受理；请核对预留后人工恢复");
            return;
        }
        if(stockBuckets!=1 && stockBuckets!=2 && stockBuckets!=4 && stockBuckets!=8)
            throw new IllegalArgumentException("stock-buckets must be 1, 2, 4 or 8");
        tradeInventoryService.allocate(activityId,activity.getSetmealId(),activity.getStock());
        if(stockBuckets>1 && reservationMapper.countForActivity(activityId)==0 && activity.getStock().equals(activity.getRemainingStock())) {
            seckillActivityMapper.setBucketCount(activityId,stockBuckets);
            activity.setBucketCount(stockBuckets);
            for(int bucket=0;bucket<stockBuckets;bucket++)
                seckillActivityMapper.insertBucket(activityId,bucket,activity.getStock()/stockBuckets+(bucket<activity.getStock()%stockBuckets?1:0));
        }
        cache.initialize(activity);
        seckillActivityMapper.markAsyncInitialized(activityId);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { cache.enable(activityId); }
            });
    }

    @Override
    public Integer getSeckillStock(Long activityId) { return cache.stock(activityId); }

    @Override
    public List<SeckillActivityVO> listAvailableActivities(Long canteenId) {
        List<SeckillActivityVO> activities = seckillActivityMapper.listAvailable();
        for (SeckillActivityVO activity : activities) {
            try {
                int stock=cache.stock(activity.getId());
                activity.setStock(stock);activity.setRemainingStock(stock);
            } catch(org.springframework.dao.DataAccessException | OrderBusinessException unavailable) {
                activity.setStock(0);activity.setRemainingStock(0);
            }
            activity.setBeginTimestamp(toMillis(activity.getBeginTime()));
            activity.setEndTimestamp(toMillis(activity.getEndTime()));
        }
        Map<Long, Long> setmealCanteens = fillSetmealInfo(activities);
        if (canteenId == null) {
            return activities;
        }
        // 只保留当前餐厅的套餐活动，避免 A 餐厅的菜单里出现 B 餐厅的秒杀。
        return activities.stream()
                .filter(activity -> canteenId.equals(setmealCanteens.get(activity.getSetmealId())))
                .toList();
    }

    @Override
    public List<SeckillActivityVO> listActivities() {
        List<SeckillActivityVO> activities = seckillActivityMapper.list();
        fillSetmealInfo(activities);
        return activities;
    }

    /**
     * 套餐资料归商品服务所有，这里批量补齐秒杀列表需要的名称、图片和原价。
     * 同时返回「套餐 id -> 所属餐厅 id」映射，供按餐厅过滤。
     * 商品服务不可用时只降级为缺少套餐资料，不能让整个活动列表查询失败。
     */
    private Map<Long, Long> fillSetmealInfo(List<SeckillActivityVO> activities) {
        Map<Long, Long> setmealCanteens = new java.util.HashMap<>();
        if (activities == null || activities.isEmpty()) {
            return setmealCanteens;
        }
        try {
            List<ShoppingCart> refs = activities.stream()
                    .map(SeckillActivityVO::getSetmealId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .map(id -> ShoppingCart.builder().setmealId(id).build())
                    .toList();
            if (refs.isEmpty()) {
                return setmealCanteens;
            }
            Map<String, ProductQuote> quotes = catalogQuoteService.quotes(refs);
            for (SeckillActivityVO activity : activities) {
                ProductQuote quote = quotes.get(ProductQuote.SETMEAL + ':' + activity.getSetmealId());
                if (quote != null) {
                    activity.setSetmealName(quote.getName());
                    activity.setImage(quote.getImage());
                    activity.setOriginalPrice(quote.getPrice());
                    setmealCanteens.put(activity.getSetmealId(), quote.getCanteenId());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("秒杀活动套餐资料查询失败，降级为不展示套餐名称与原价", ex);
        }
        return setmealCanteens;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveActivity(SeckillActivityDTO dto) {
        if (dto == null || dto.getSetmealId() == null || dto.getStock() == null || dto.getStock() < 0 ||
                dto.getSeckillPrice() == null || dto.getSeckillPrice().signum() < 0 ||
                dto.getBeginTime() == null || dto.getEndTime() == null || !dto.getEndTime().isAfter(dto.getBeginTime())) {
            throw new OrderBusinessException("请完整填写秒杀套餐、价格、库存和有效时间");
        }
        ProductQuote quote = catalogQuoteService.quoteSetmeal(dto.getSetmealId());
        if (!StatusConstant.ENABLE.equals(quote.getStatus())) {
            throw new OrderBusinessException("请选择已启售套餐");
        }
        if (dto.getSeckillPrice().compareTo(quote.getPrice()) >= 0) {
            throw new OrderBusinessException("秒杀价必须低于套餐原价");
        }
        SeckillActivity activity = new SeckillActivity();
        activity.setId(dto.getId()); activity.setSetmealId(dto.getSetmealId()); activity.setStock(dto.getStock());
        // Lua 用户预留与数据库唯一索引共同保障一人一单，因此当前活动仅允许配置每人 1 份。
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
            if(Integer.valueOf(1).equals(previous.getRuntimeRetired()))throw new OrderBusinessException("活动已退役，不能修改");
            if(Integer.valueOf(1).equals(previous.getAsyncInitialized())) {
                if(Integer.valueOf(0).equals(activity.getStatus())) {
                    cache.pause(previous.getId());
                    activity=previous; activity.setStatus(0);
                    seckillActivityMapper.update(activity); return activity.getId();
                }
                throw new OrderBusinessException("已预热活动只能停用，请创建新活动修改价格、时间或库存");
            }
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
        if(Integer.valueOf(1).equals(activity.getAsyncInitialized())) throw new OrderBusinessException("已预热活动只能停用，不能删除");
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

    private Orders buildOrder(SeckillOrderSubmitDTO request, Long userId, AccountAddressView address, Setmeal setmeal,
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
        if(Integer.valueOf(1).equals(activity.getAsyncInitialized())) throw new OrderBusinessException("异步秒杀已预热，请创建新活动调整库存");
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
    public OrderSubmitVO findRequest(String requestId) { return findRequest(requestId,null); }

    @Override
    public OrderSubmitVO findRequest(String requestId,Long activityId) {
        Long user=BaseContext.getCurrentId();
        if(user==null || requestId==null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw new OrderBusinessException("用户或请求编号无效");
        Map<String,Object> accepted=asyncStore.find(user,requestId,activityId);
        if(accepted!=null) {
            String number=(String)accepted.get("order_number");
            Orders order=orderMapper.getByNumber(number);
            if(order!=null) { OrderSubmitVO result=submitVO(order); result.setSubmitStatus(Orders.CANCELLED.equals(order.getStatus())?"CANCELLED":"SUCCEEDED");
                if(Orders.CANCELLED.equals(order.getStatus())) result.setFailureMessage("订单已取消，请重新抢购");
                return result; }
            return OrderSubmitVO.builder().orderNumber(number).submitStatus((String)accepted.get("state"))
                .failureMessage((String)accepted.get("last_error")).build();
        }
        com.sky.entity.SeckillReservation legacy=reservationMapper.byRequest(user,requestId);
        if(legacy!=null) {
            Orders order=orderMapper.getByNumber(legacy.getOrderNumber());
            if(order!=null) { OrderSubmitVO result=submitVO(order); result.setSubmitStatus(Orders.CANCELLED.equals(order.getStatus())?"CANCELLED":"SUCCEEDED");
                if(Orders.CANCELLED.equals(order.getStatus())) result.setFailureMessage("订单已取消，请重新抢购");
                return result; }
        }
        if(activityId!=null) {
            try { return cache.find(activityId,user,requestId); }
            catch(org.springframework.dao.DataAccessException e) { throw new OrderBusinessException("秒杀状态暂不可查询，请保留原请求编号"); }
        }
        return null;
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
