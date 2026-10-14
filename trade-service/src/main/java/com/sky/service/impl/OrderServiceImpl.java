package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.MqConstant;
import com.sky.constant.RedisKeyConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.client.AccountClient;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderCheckoutVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.vo.RedPacketCheckoutVO;
import com.sky.contract.account.AccountAddressView;
import com.sky.contract.account.AccountUserView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 订单
 */
@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    @Autowired(required=false) private com.sky.observability.OrderCreationMetrics creationMetrics;
    /** 配送费和打包费只能在服务端结算，不能信任小程序传入的金额。 */
    private static final BigDecimal DELIVERY_FEE = new BigDecimal("6.00");
    private static final BigDecimal PACK_FEE_PER_ITEM = new BigDecimal("1.00");
    @Autowired private OrderLifecycleService lifecycle;

    @Autowired private SeckillReservationService seckillReservationService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private AccountClient accountClient;
    @Autowired
    private CouponMapper couponMapper;
    @Autowired
    private UserCouponMapper userCouponMapper;
    @Autowired
    private RedPacketMapper redPacketMapper;
    @Autowired
    private CatalogQuoteService catalogQuoteService;
    @Autowired
    private TradeInventoryService tradeInventoryService;
    @Autowired
    private OrderSubmitRequestMapper orderSubmitRequestMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired private com.sky.payment.PaymentRecoveryStore paymentRecovery;
    @Autowired
    private BusinessEventOutbox businessEventOutbox;
    @Autowired
    private com.sky.service.mq.OrderMessagePublisher orderMessagePublisher;
    @Autowired
    private RedisTemplate redisTemplate;
    @Value("${sky.payment.mock-enabled:false}")
    private boolean mockPaymentEnabled;

    /**
     * 普通下单的受理入口：此处只绑定请求和订单号、持久化建单命令，不写 orders。
     * HTTP 成功表示请求已受理；首次返回的 id、orderAmount 为空，前端需轮询 submit-result。
     * 真正的建单入口是 MQ 消费者调用的 createOrderFromMessage。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        Long userId = BaseContext.getCurrentId();
        if(userId==null)throw new OrderBusinessException("用户未登录");
        // requestId 标识同一次提交；同一用户重试时复用它，避免生成多个订单。
        validateRequestId(ordersSubmitDTO);
        String requestHash=fingerprint(ordersSubmitDTO);
        var accepted=orderSubmitRequestMapper.find(userId,ordersSubmitDTO.getRequestId());
        if(accepted!=null && accepted.get("request_hash")!=null && !requestHash.equals(accepted.get("request_hash")))
            throw new OrderBusinessException("请求编号已被其他参数使用");
        //查询同一次请求是否已创建订单
        OrderSubmitVO previous = findSubmittedOrder(userId, ordersSubmitDTO.getRequestId());
        if (previous != null) {
            return previous;
        }
        //生成订单号
        String orderNumber = generateOrderNumber();
        ShoppingCart lookup=new ShoppingCart();lookup.setUserId(userId);
        List<ShoppingCart> snapshot=shoppingCartMapper.list(lookup);
        if(snapshot==null || snapshot.isEmpty()) throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        validateCartItems(snapshot);
        // 固化首次受理的商品与数量；消费者继续以服务端报价及地址归属完成校验。
        OrderSubmitMessageDTO messageDTO = OrderSubmitMessageDTO.builder()
                .userId(userId)
                .acceptedAt(System.currentTimeMillis())
                .cartSnapshot(snapshot)
                .requestId(ordersSubmitDTO.getRequestId()).requestHash(requestHash)
                .expectedAmount(ordersSubmitDTO.getAmount())
                .addressBookId(ordersSubmitDTO.getAddressBookId())
                .payMethod(ordersSubmitDTO.getPayMethod())
                .remark(ordersSubmitDTO.getRemark())
                .estimatedDeliveryTime(ordersSubmitDTO.getEstimatedDeliveryTime())
                .deliveryStatus(ordersSubmitDTO.getDeliveryStatus())
                .tablewareNumber(ordersSubmitDTO.getTablewareNumber())
                .tablewareStatus(ordersSubmitDTO.getTablewareStatus())
                .packAmount(ordersSubmitDTO.getPackAmount())
                .orderNumber(orderNumber)
                .couponId(ordersSubmitDTO.getCouponId())
                .userRedPacketId(ordersSubmitDTO.getUserRedPacketId())
                .useRedPacket(ordersSubmitDTO.getUseRedPacket())
                .canteenId(ordersSubmitDTO.getCanteenId())
                .canteenName(ordersSubmitDTO.getCanteenName())
                .build();

        if(orderSubmitRequestMapper.claimSnapshot(userId,ordersSubmitDTO.getRequestId(),orderNumber,requestHash,
                com.alibaba.fastjson.JSON.toJSONString(messageDTO))!=1) {
            var competing=orderSubmitRequestMapper.findCurrent(userId,ordersSubmitDTO.getRequestId());
            if(competing==null || !requestHash.equals(competing.get("request_hash"))) throw new OrderBusinessException("请求编号参数冲突");
            return OrderSubmitVO.builder().orderNumber((String)competing.get("order_number")).submitStatus((String)competing.get("state"))
                    .failureMessage((String)competing.get("last_error")).build();
        }

        // The request is accepted quickly; cart validation, pricing, stock
        // deduction and persistence execute in the idempotent MQ consumer.
        // 待发消息与请求绑定一起提交，提交后才发 MQ；发送失败由持久化记录支持补发。
        orderMessagePublisher.submitAfterCommit(messageDTO);

        return OrderSubmitVO.builder()
                .orderNumber(orderNumber)
                .orderAmount(null)
                .orderTime(LocalDateTime.now())
                .id(null)
                .submitStatus("ACCEPTED")
                .build();
    }

    /** 前端轮询入口：查到真实 orders 才有 id 和最终金额；null 不区分处理中和失败。 */
    @Override
    public OrderSubmitVO findSubmitResult(String requestId) {
        if (BaseContext.getCurrentId() == null || requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new OrderBusinessException("requestId is required");
        }
        return findSubmittedOrder(BaseContext.getCurrentId(), requestId);
    }

    /**
     * MQ 消费线程执行的建单事务。地址、商品信息通过 Feign 远程读取；
     * 交易库存、订单、优惠、明细、购物车和待发超时消息在本地事务内写入。
     * 远程读取不属于分布式事务；本地事务失败后，消费者负责记录失败并处理 ACK/NACK。
     */
    @Transactional
    public void createOrderFromMessage(OrderSubmitMessageDTO messageDTO) {
        createOrderBody(messageDTO,null);
    }
    @Override public com.sky.service.PreparedNormalOrder prepareNormalOrder(OrderSubmitMessageDTO received) {
        var row=orderSubmitRequestMapper.get(received.getOrderNumber());
        if(row==null)throw new NormalOrderRecovery.MissingAcceptance();
        if(!"ACCEPTED".equals(row.get("state")))return null;
        var command=com.alibaba.fastjson.JSON.parseObject((String)row.get("command_body"),OrderSubmitMessageDTO.class);
        if(command==null || command.getCartSnapshot()==null)throw new OrderBusinessException("订单提交快照缺失");
        var items=command.getCartSnapshot();validateCartItems(items);
        var quotes=catalogQuoteService.quotes(items);catalogQuoteService.refresh(items,quotes);
        var address=accountClient.address(command.getUserId(),command.getAddressBookId());
        if(address==null || !command.getUserId().equals(address.getUserId()))throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        return new com.sky.service.PreparedNormalOrder(command,address,items,quotes);
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public void createPreparedNormalOrder(com.sky.service.PreparedNormalOrder prepared) {createOrderBody(prepared.command(),prepared);}

    private void createOrderBody(OrderSubmitMessageDTO messageDTO,com.sky.service.PreparedNormalOrder prepared) {
        if(orderSubmitRequestMapper!=null) {
            var acceptance=orderSubmitRequestMapper.lock(messageDTO.getOrderNumber());
            if(acceptance==null) throw new NormalOrderRecovery.MissingAcceptance();
            if(!"ACCEPTED".equals(acceptance.get("state")))return;
            if(acceptance.get("command_body")!=null) {
                if(((Number)acceptance.get("expired")).intValue()==1)throw new OrderBusinessException("建单等待超时");
                messageDTO=com.alibaba.fastjson.JSON.parseObject((String)acceptance.get("command_body"),OrderSubmitMessageDTO.class);
                if(prepared!=null && !java.util.Objects.equals(messageDTO.getRequestHash(),prepared.command().getRequestHash()))throw new OrderBusinessException("订单快照发生冲突");
            }
        }
        // ① 先查这个订单号是否已经建过, 已完成订单的重复消息直接返回；这是重复投递的业务幂等检查。
        Orders exists = orderMapper.getByNumber(messageDTO.getOrderNumber());
        if (exists != null) {
            return;
        }

        // ② 远程查询收货地址 地址归 account-service 管理，使用 userId + addressId 查询，避免使用他人地址。
        AccountAddressView addressBook = prepared==null?accountClient.address(messageDTO.getUserId(), messageDTO.getAddressBookId()):prepared.address();
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        // ③ 根据用户读取购物车 普通订单的商品来源是交易服务购物车，用户身份取自受理阶段保存的消息。
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(messageDTO.getUserId());
        List<ShoppingCart> shoppingCartList = prepared!=null?prepared.items():messageDTO.getCartSnapshot()!=null ? messageDTO.getCartSnapshot() : shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.size() == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }
        //④ 检查营业状态和购物车结构
        ensureShopOpen();
        validateCartItems(shoppingCartList);
        //⑤ 从商品服务刷新报价 以商品服务的现价与销售状态为准，覆盖购物车中的旧报价。
        if(prepared==null)catalogQuoteService.refresh(shoppingCartList);
        //⑥ 扣减交易库存 实际扣减的是本地 trade_inventory，不是远程修改 catalog-service 库存。
        if(prepared==null)tradeInventoryService.deduct(shoppingCartList);
        else tradeInventoryService.deductQuoted(shoppingCartList,prepared.quotes());

        // ⑦ 在内存里组装订单对象  从此处组装待支付订单：尚未付款，但库存和优惠会在本事务中占用。
        Orders order = new Orders();
        BeanUtils.copyProperties(messageDTO, order,"packAmount","tablewareNumber");
            order.setTablewareNumber(messageDTO.getTablewareNumber()==null?0:messageDTO.getTablewareNumber());
            order.setTablewareStatus(messageDTO.getTablewareStatus()==null?1:messageDTO.getTablewareStatus());
            order.setDeliveryStatus(messageDTO.getDeliveryStatus()==null?1:messageDTO.getDeliveryStatus());
            order.setPhone(addressBook.getPhone());
            order.setAddress(addressBook.getDetail());
            order.setConsignee(addressBook.getConsignee());
            order.setNumber(messageDTO.getOrderNumber());
            order.setUserId(messageDTO.getUserId());
            order.setStatus(Orders.PENDING_PAYMENT);
            order.setPayStatus(Orders.UN_PAID);
            order.setOrderTime(LocalDateTime.now());
            //⑧ 计算商品小计和优惠
            BigDecimal originAmount = calculateCartAmount(shoppingCartList);
            if (messageDTO.getCouponId() != null && messageDTO.getUserRedPacketId() != null) {
                throw new OrderBusinessException("coupon and red packet cannot be used together");
            }
            Long userRedPacketId = resolveRedPacketId(messageDTO.getUserId(), messageDTO.getCouponId(),
                    messageDTO.getUserRedPacketId(), messageDTO.getUseRedPacket());
            BigDecimal discountAmount = calculateDiscount(messageDTO.getUserId(), messageDTO.getCouponId(), originAmount)
                    .add(calculateRedPacketDiscount(messageDTO.getUserId(), userRedPacketId, originAmount));
            //⑨ 计算最终金额，并设置截止时间
            int packAmount = calculatePackAmount(shoppingCartList);
            order.setCouponId(messageDTO.getCouponId());
            order.setUserRedPacketId(userRedPacketId);
            order.setDiscountAmount(discountAmount);
            //向订单表插入1条数据
            // 前端传入的 amount、packAmount 均不参与结算，避免被篡改。
            order.setPackAmount(packAmount);
            order.setAmount(originAmount.subtract(discountAmount)
                    .add(BigDecimal.valueOf(packAmount).multiply(PACK_FEE_PER_ITEM))
                    .add(DELIVERY_FEE));
            if(messageDTO.getExpectedAmount()!=null && messageDTO.getExpectedAmount().compareTo(order.getAmount())!=0)
                throw new OrderBusinessException("金额或优惠已变化，请重新结算");

            // 支付期限从消费者实际建单时间开始；排队时间不计入这 15 分钟。
            order.setExpireTime(order.getOrderTime().plusMinutes(15).withNano(0));
            //⑩ 插入订单，拿到数据库主键 ID
            orderMapper.insert(order);
            // ⑪ 真正占用优惠券或红包 优惠券建单即标记使用，红包建单先预占；取消时按订单关联释放。
            markCouponUsed(messageDTO.getUserId(), messageDTO.getCouponId(), order.getId());
            reserveRedPacket(messageDTO.getUserId(), userRedPacketId, order.getId());

            // 订单明细保存本次交易的商品快照，后续退款/退库存不能依赖已清空的购物车。
            List<OrderDetail> orderDetailList = new ArrayList<>();
            //⑫ 将购物车转换成订单明细
            for (ShoppingCart cart : shoppingCartList) {
                OrderDetail orderDetail = new OrderDetail();
                BeanUtils.copyProperties(cart, orderDetail);
                orderDetail.setOrderId(order.getId());
                orderDetail.setAmount(queryCurrentPrice(cart));
                orderDetailList.add(orderDetail);
            }

            orderDetailMapper.insertBatch(orderDetailList);
            //⑬ 清空购物车，并保存超时命令 成功建单后清购物车；若后续本地操作失败，此删除也随事务回滚。
            if(messageDTO.getCartSnapshot()!=null) {
                for(var item:messageDTO.getCartSnapshot())shoppingCartMapper.deleteSubmitted(item);
                orderSubmitRequestMapper.succeeded(order.getNumber());
            } else shoppingCartMapper.deleteByUserId(messageDTO.getUserId());
        // Publish only after the order transaction commits.  The delay queue's
        // 15-minute TTL dead-letters it to the cancellation consumer.
        // 新普通订单通过延迟队列触发关闭，此处没有创建 order_reliability_job 的 TIMEOUT 任务。
        orderMessagePublisher.timeoutDeferred(order.getNumber(),order.getExpireTime());
        if(creationMetrics!=null)creationMetrics.committed("normal",java.util.Collections.singletonList(messageDTO.getAcceptedAt()));
    }

    /**
     * Runs in its own transaction because the consumer transaction that called
     * it has just rolled back. Do not remove a binding when an order actually
     * exists: a duplicate delivery is still a successful submission.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseFailedSubmission(String orderNumber) {
        if (orderNumber != null && orderMapper.getByNumber(orderNumber) == null) {
            orderSubmitRequestMapper.releaseByOrderNumber(orderNumber);
        }
    }

    public void cancelTimeoutOrder(String orderNumber) {
        lifecycle.timeout(orderNumber);
    }

    @Override
    public OrderCheckoutVO checkout() {
        Long userId = BaseContext.getCurrentId();
        ShoppingCart cart = new ShoppingCart();
        cart.setUserId(userId);
        List<ShoppingCart> items = shoppingCartMapper.list(cart);
        if (items == null || items.isEmpty()) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }
        BigDecimal food = calculateCartAmount(items);
        int pack = calculatePackAmount(items);
        List<UserRedPacket> packets = redPacketMapper.availableByUser(userId);
        Long defaultId = packets.isEmpty() ? null : packets.get(0).getId();
        BigDecimal discount = defaultId == null ? BigDecimal.ZERO : packets.get(0).getAmount().min(food);
        List<RedPacketCheckoutVO> vos = packets.stream().map(p -> RedPacketCheckoutVO.builder()
                .id(p.getId()).amount(p.getAmount()).expireTime(p.getExpireTime()).build()).collect(Collectors.toList());
        return OrderCheckoutVO.builder().foodAmount(food)
                .packAmount(BigDecimal.valueOf(pack).multiply(PACK_FEE_PER_ITEM))
                .deliveryAmount(DELIVERY_FEE).discountAmount(discount)
                .payableAmount(food.subtract(discount).add(BigDecimal.valueOf(pack).multiply(PACK_FEE_PER_ITEM)).add(DELIVERY_FEE))
                .defaultRedPacketId(defaultId).redPackets(vos).build();
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        Long userId=BaseContext.getCurrentId();
        Orders order=orderMapper.getByNumberAndUserId(ordersPaymentDTO.getOrderNumber(),userId);
        if(order==null || !Orders.PENDING_PAYMENT.equals(order.getStatus()) || !Orders.UN_PAID.equals(order.getPayStatus())
            || !LocalDateTime.now().isBefore(OrderLifecycleService.deadline(order)))
            throw new OrderBusinessException("order is not available for payment");
        if(mockPaymentEnabled) return OrderPaymentVO.builder().mockPayment(true).build();
        AccountUserView user=accountClient.user(userId);
        paymentRecovery.begin(order.getNumber(),order.getAmount(),OrderLifecycleService.deadline(order));
        JSONObject result=weChatPayUtil.pay(order.getNumber(),order.getAmount(),"Sky take-out order",user.getOpenid(),OrderLifecycleService.deadline(order));
        if(result.getString("code")!=null) throw new OrderBusinessException("payment request failed: "+result.getString("code"));
        OrderPaymentVO vo=result.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(result.getString("package"));
        return vo;
    }

    @Override
    public void confirmMockPayment(String orderNumber) {
        paySuccess(orderNumber);
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {
        if(!mockPaymentEnabled) throw new OrderBusinessException("verified payment receipt required");
        lifecycle.paid(outTradeNo,"mock:"+outTradeNo,null,true);
    }

    /**
     * 用户端订单分页查询
     *
     * @param pageNum
     * @param pageSize
     * @param status
     * @return
     */
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        // 设置分页
        PageHelper.startPage(pageNum, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);
        ordersPageQueryDTO.setUserDeleted(0);

        // 分页条件查询
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList();

        // 查询出订单明细，并封装入OrderVO进行响应
        if (page != null && page.getTotal() > 0) {
            for (Orders orders : page) {
                Long orderId = orders.getId();// 订单id

                // 查询订单明细
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orderId);

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetails);

                list.add(orderVO);
            }
        }
        return new PageResult(page.getTotal(), list);
    }

    /**
     * 查询订单详情
     *
     * @param id
     * @return
     */
    public OrderVO details(Long id) {
        // 根据id查询订单
        Orders orders = requireOwnedOrder(id);

        // 查询该订单对应的菜品/套餐明细
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将该订单及其详情封装到OrderVO并返回
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);

        return orderVO;
    }

    @Override
    public OrderVO adminDetails(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailMapper.getByOrderId(orders.getId()));
        return orderVO;
    }

    /**
     * 用户取消订单
     *
     * @param id
     */
    /** 用户取消入口；所有资源释放交给生命周期服务，避免只改状态却漏退库存。 */
    public void userCancelById(Long id) throws Exception {
        lifecycle.cancel(id,"user cancel","USER");
    }

    @Override
    @Transactional
    public void deleteHistoryOrder(Long id) {
        Long userId = BaseContext.getCurrentId();
        Orders order = orderMapper.getById(id);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (!Orders.COMPLETED.equals(order.getStatus()) && !Orders.CANCELLED.equals(order.getStatus())) {
            throw new OrderBusinessException("仅已完成或已取消的订单可以删除");
        }
        if (orderMapper.hideForUser(id, userId) != 1) {
            throw new OrderBusinessException("订单已删除或状态已发生变化");
        }
    }

    /**
     * 再来一单
     *
     * @param id
     */
    public Map<String, Object> repetition(Long id) {
        // 查询当前用户id
        Long userId = BaseContext.getCurrentId();
        Orders order = requireOwnedOrder(id);

        // 根据订单id查询当前订单详情
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        // 将订单详情对象转换为购物车对象
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(x -> {
            // 普通订单的商品来源是交易服务购物车，用户身份取自受理阶段保存的消息。
        ShoppingCart shoppingCart = new ShoppingCart();

            // 将原订单详情里面的菜品信息重新复制到购物车对象中
            BeanUtils.copyProperties(x, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());

            return shoppingCart;
        }).collect(Collectors.toList());

        // 将购物车对象批量添加到数据库
        shoppingCartMapper.insertBatch(shoppingCartList);

        // 返回订单快照中的餐厅，供小程序"再来一单"定位原餐厅。
        Map<String, Object> canteen = new HashMap<>();
        canteen.put("id", order.getCanteenId());
        canteen.put("name", order.getCanteenName());
        return canteen;
    }

    /**
     * 订单搜索
     *
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        // 部分订单状态，需要额外返回订单菜品信息，将Orders转化为OrderVO
        List<OrderVO> orderVOList = getOrderVOList(page);

        return new PageResult(page.getTotal(), orderVOList);
    }

    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        // 需要返回订单菜品信息，自定义OrderVO响应结果
        List<OrderVO> orderVOList = new ArrayList<>();

        List<Orders> ordersList = page.getResult();
        if (!CollectionUtils.isEmpty(ordersList)) {
            for (Orders orders : ordersList) {
                // 将共同字段复制到OrderVO
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                String orderDishes = getOrderDishesStr(orders);

                // 将订单菜品信息封装到orderVO中，并添加到orderVOList
                orderVO.setOrderDishes(orderDishes);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }

    /**
     * 根据订单id获取菜品信息字符串
     *
     * @param orders
     * @return
     */
    private String getOrderDishesStr(Orders orders) {
        // 查询订单菜品详情信息（订单中的菜品和数量）
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将每一条订单菜品信息拼接为字符串（格式：宫保鸡丁*3；）
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            String orderDish = x.getName() + "*" + x.getNumber() + ";";
            return orderDish;
        }).collect(Collectors.toList());

        // 将该订单对应的所有菜品信息拼接在一起
        return String.join("", orderDishList);
    }

    private String generateOrderNumber() {
        return System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 商品小计只汇总现价乘数量，不包含配送、打包和优惠。 */
    private BigDecimal calculateCartAmount(List<ShoppingCart> shoppingCartList) {
        BigDecimal amount = BigDecimal.ZERO;
        for (ShoppingCart cart : shoppingCartList) {
            BigDecimal price = queryCurrentPrice(cart);
            int number = cart.getNumber() == null ? 0 : cart.getNumber();
            amount = amount.add(price.multiply(BigDecimal.valueOf(number)));
        }
        return amount;
    }

    private int calculatePackAmount(List<ShoppingCart> shoppingCartList) {
        return shoppingCartList.stream()
                .map(ShoppingCart::getNumber)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    /** 读取购物车对象上的报价；建单时 refresh 已更新它，此方法本身不请求商品服务。 */
    private BigDecimal queryCurrentPrice(ShoppingCart cart) {
        return cart.getAmount() == null ? BigDecimal.ZERO : cart.getAmount();
    }

    /** 校验用户券、有效期和门槛；折扣金额限制在商品小计范围，不抵扣额外费用。 */
    private BigDecimal calculateDiscount(Long userId, Long couponId, BigDecimal originAmount) {
        if (couponId == null) {
            return BigDecimal.ZERO;
        }
        UserCoupon userCoupon = userCouponMapper.getByUserIdAndCouponId(userId, couponId);
        if (userCoupon == null || !UserCoupon.UNUSED.equals(userCoupon.getStatus())) {
            throw new OrderBusinessException("coupon unavailable");
        }
        Coupon coupon = couponMapper.getById(couponId);
        LocalDateTime now = LocalDateTime.now();
        if (coupon == null || coupon.getStatus() == null || coupon.getStatus() != 1
                || now.isBefore(coupon.getBeginTime()) || now.isAfter(coupon.getEndTime())) {
            throw new OrderBusinessException("coupon unavailable");
        }
        if (originAmount.compareTo(coupon.getThresholdAmount()) < 0) {
            throw new OrderBusinessException("coupon threshold not reached");
        }
        BigDecimal discount;
        if ("DISCOUNT".equals(coupon.getType())) {
            BigDecimal rate = coupon.getDiscountRate() == null ? BigDecimal.ONE : coupon.getDiscountRate();
            discount = originAmount.subtract(originAmount.multiply(rate));
        } else {
            discount = coupon.getDiscountAmount() == null ? BigDecimal.ZERO : coupon.getDiscountAmount();
        }
        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (discount.compareTo(originAmount) > 0) {
            return originAmount;
        }
        return discount;
    }

    private BigDecimal calculateRedPacketDiscount(Long userId, Long redPacketId, BigDecimal foodAmount) {
        if (redPacketId == null) {
            return BigDecimal.ZERO;
        }
        UserRedPacket packet = redPacketMapper.byIdAndUser(redPacketId, userId);
        if (packet == null || !UserRedPacket.UNUSED.equals(packet.getStatus())
                || packet.getExpireTime() == null || !packet.getExpireTime().isAfter(LocalDateTime.now())) {
            throw new OrderBusinessException("red packet unavailable");
        }
        return packet.getAmount().min(foodAmount);
    }

    /**
     * The default is to use a red packet. Only an explicit false from the
     * checkout page disables it; this keeps a failed client-side preview from
     * silently charging the user the full price.
     */
    private Long resolveRedPacketId(Long userId, Long couponId, Long requestedRedPacketId, Boolean useRedPacket) {
        if (couponId != null || Boolean.FALSE.equals(useRedPacket)) {
            return null;
        }
        if (requestedRedPacketId != null) {
            return requestedRedPacketId;
        }
        List<UserRedPacket> packets = redPacketMapper.availableByUser(userId);
        return packets.isEmpty() ? null : packets.get(0).getId();
    }

    /** 条件更新预占红包，竞争失败抛异常，使建单事务回滚。 */
    private void reserveRedPacket(Long userId, Long redPacketId, Long orderId) {
        if (redPacketId == null) {
            return;
        }
        if (redPacketMapper.reserve(redPacketId, userId, orderId) != 1) {
            throw new OrderBusinessException("red packet already used or expired");
        }
    }

    /** 仅从未使用状态转为使用；绑定 orderId 供取消时精确退回。 */
    private void markCouponUsed(Long userId, Long couponId, Long orderId) {
        if (couponId == null) {
            return;
        }
        UserCoupon userCoupon = userCouponMapper.getByUserIdAndCouponId(userId, couponId);
        if (userCoupon == null) {
            throw new OrderBusinessException("coupon unavailable");
        }
        int affected = userCouponMapper.markUsed(userCoupon.getId(), orderId);
        if (affected == 0) {
            throw new OrderBusinessException("coupon already used");
        }
    }

    /**
     * 各个状态的订单数量统计
     *
     * @return
     */
    public OrderStatisticsVO statistics() {
        // 根据状态，分别查询出待接单、待派送、派送中的订单数量
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        // 将查询出的数据封装到orderStatisticsVO中响应
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    /**
     * 接单
     *
     * @param ordersConfirmDTO
     */
    @Transactional
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        if(orderMapper.transition(ordersConfirmDTO.getId(),2,3)!=1) throw new OrderBusinessException("order state changed");
        businessEventOutbox.record("ORDER_STATUS_CHANGED", ordersConfirmDTO.getId(), Map.of("type",3,"orderId",ordersConfirmDTO.getId(),"content","order confirmed"));
    }

    /**
     * 拒单
     *
     * @param ordersRejectionDTO
     */
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        lifecycle.cancel(ordersRejectionDTO.getId(),ordersRejectionDTO.getRejectionReason(),"REJECT");
    }

    /**
     * 取消订单
     *
     * @param ordersCancelDTO
     */
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        lifecycle.cancel(ordersCancelDTO.getId(),ordersCancelDTO.getCancelReason(),"ADMIN");
    }

    /**
     * 派送订单
     *
     * @param id
     */
    @Transactional
    public void delivery(Long id) {
        if(orderMapper.transition(id,3,4)!=1) throw new OrderBusinessException("order state changed");
        businessEventOutbox.record("ORDER_STATUS_CHANGED", id, Map.of("type",3,"orderId",id,"content","order delivering"));
    }

    /**
     * 完成订单
     *
     * @param id
     */
    @Transactional
    public void complete(Long id) {
        if(orderMapper.transition(id,4,5)!=1) throw new OrderBusinessException("order state changed");
        businessEventOutbox.record("ORDER_STATUS_CHANGED", id, Map.of("type",3,"orderId",id,"content","order completed"));
    }

    /**
     * 用户催单
     *
     * @param id
     */
    public void reminder(Long id) {
        // 查询订单是否存在
        Orders orders = requireOwnedOrder(id);

        //基于WebSocket实现催单
        Map map = new HashMap();
        map.put("type", 2);//2代表用户催单
        map.put("orderId", id);
        map.put("content", "order number: " + orders.getNumber());
        businessEventOutbox.record("ORDER_REMINDER", id, map);
    }


    @Override
    public void paySuccess(String number,String transactionId,BigDecimal amount) {
        lifecycle.paid(number,transactionId,amount,false);
    }

    private void validateRequestId(OrdersSubmitDTO dto) {
        if (dto == null || dto.getRequestId() == null || !dto.getRequestId().matches("[A-Za-z0-9_-]{16,64}")) {
            throw new OrderBusinessException("requestId is required");
        }
    }

    /** 先用当前用户和 requestId 找订单号，再查该用户订单；绑定存在不等于建单成功。 */
    private OrderSubmitVO findSubmittedOrder(Long userId, String requestId) {
        String orderNumber = orderSubmitRequestMapper.findOrderNumber(userId, requestId);
        if (orderNumber == null) {
            return null;
        }
        Orders order = orderMapper.getByNumberAndUserId(orderNumber, userId);
        if (order == null) {
            var row=orderSubmitRequestMapper.find(userId,requestId);
            return row==null?null:OrderSubmitVO.builder().orderNumber(orderNumber).submitStatus((String)row.get("state"))
                    .failureMessage((String)row.get("last_error")).build();
        }
        return OrderSubmitVO.builder().id(order.getId()).orderNumber(order.getNumber())
                .orderAmount(order.getAmount()).orderTime(order.getOrderTime()).submitStatus(Orders.CANCELLED.equals(order.getStatus())?"CANCELLED":"SUCCEEDED").build();
    }

    private String fingerprint(OrdersSubmitDTO dto) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                com.alibaba.fastjson.JSON.toJSONString(dto,com.alibaba.fastjson.serializer.SerializerFeature.SortField).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    private void ensureShopOpen() {
        Object status = redisTemplate.opsForValue().get(RedisKeyConstant.SHOP_STATUS);
        if (!Integer.valueOf(1).equals(status)) {
            throw new OrderBusinessException("shop is closed");
        }
    }

    private void validateCartItems(List<ShoppingCart> items) {
        for (ShoppingCart cart : items) {
            if (cart.getNumber() == null || cart.getNumber() <= 0) {
                throw new OrderBusinessException("item quantity must be positive");
            }
            boolean dish = cart.getDishId() != null;
            boolean setmeal = cart.getSetmealId() != null;
            if (dish == setmeal) {
                throw new OrderBusinessException("invalid shopping cart item");
            }
        }
    }

    private Orders requireOwnedOrder(Long id) {
        Orders order = orderMapper.getByIdAndUserId(id, BaseContext.getCurrentId());
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        return order;
    }
}
