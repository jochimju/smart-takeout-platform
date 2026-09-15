package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
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
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderCheckoutVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.vo.RedPacketCheckoutVO;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
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
 * 闂備浇宕垫慨鎶芥⒔瀹ュ鍨傞柣鐔稿閺?
 */
@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    /** 配送费和打包费只能在服务端结算，不能信任小程序传入的金额。 */
    private static final BigDecimal DELIVERY_FEE = new BigDecimal("6.00");
    private static final BigDecimal PACK_FEE_PER_ITEM = new BigDecimal("1.00");
    @Autowired private OrderLifecycleService lifecycle;
    @Autowired private OrderReliabilityStore reliability;

    @Autowired private SeckillReservationService seckillReservationService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private CouponMapper couponMapper;
    @Autowired
    private UserCouponMapper userCouponMapper;
    @Autowired
    private RedPacketMapper redPacketMapper;
    @Autowired
    private MqFailMessageMapper mqFailMessageMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private OrderSubmitRequestMapper orderSubmitRequestMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private WebSocketServer webSocketServer;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RedisTemplate redisTemplate;
    @Value("${sky.payment.mock-enabled:false}")
    private boolean mockPaymentEnabled;

    /**
     * 闂傚倷鐒﹀鍨焽閸ф绀夌€广儱顦弰銉︾箾閹寸偟鎳呴柍缁樻閹鏁愭惔婵堢泿缂備讲鍋?
     *
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        Long userId = BaseContext.getCurrentId();
        validateRequestId(ordersSubmitDTO);
        OrderSubmitVO previous = findSubmittedOrder(userId, ordersSubmitDTO.getRequestId());
        if (previous != null) {
            return previous;
        }

        AddressBook addressBook = addressBookMapper.getByIdAndUserId(ordersSubmitDTO.getAddressBookId(), userId);
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        ensureShopOpen();

        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.size() == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        validateCartItems(shoppingCartList);

        BigDecimal foodAmount = calculateCartAmount(shoppingCartList);
        if (ordersSubmitDTO.getCouponId() != null && ordersSubmitDTO.getUserRedPacketId() != null) {
            throw new OrderBusinessException("coupon and red packet cannot be used together");
        }
        Long userRedPacketId = resolveRedPacketId(userId, ordersSubmitDTO.getCouponId(),
                ordersSubmitDTO.getUserRedPacketId(), ordersSubmitDTO.getUseRedPacket());
        BigDecimal discountAmount = calculateDiscount(userId, ordersSubmitDTO.getCouponId(), foodAmount)
                .add(calculateRedPacketDiscount(userId, userRedPacketId, foodAmount));
        int packAmount = calculatePackAmount(shoppingCartList);
        BigDecimal payableAmount = foodAmount.subtract(discountAmount)
                .add(BigDecimal.valueOf(packAmount).multiply(PACK_FEE_PER_ITEM))
                .add(DELIVERY_FEE);

        String orderNumber = generateOrderNumber();
        if (orderSubmitRequestMapper.claim(userId, ordersSubmitDTO.getRequestId(), orderNumber) != 1) {
            OrderSubmitVO submitted = findSubmittedOrder(userId, ordersSubmitDTO.getRequestId());
            if (submitted != null) {
                return submitted;
            }
            throw new OrderBusinessException("order is processing, please retry later");
        }
        OrderSubmitMessageDTO messageDTO = OrderSubmitMessageDTO.builder()
                .userId(userId)
                .addressBookId(ordersSubmitDTO.getAddressBookId())
                .payMethod(ordersSubmitDTO.getPayMethod())
                .remark(ordersSubmitDTO.getRemark())
                .estimatedDeliveryTime(ordersSubmitDTO.getEstimatedDeliveryTime())
                .deliveryStatus(ordersSubmitDTO.getDeliveryStatus())
                .tablewareNumber(ordersSubmitDTO.getTablewareNumber())
                .tablewareStatus(ordersSubmitDTO.getTablewareStatus())
                .packAmount(packAmount)
                .orderNumber(orderNumber)
                .couponId(ordersSubmitDTO.getCouponId())
                .userRedPacketId(userRedPacketId)
                .useRedPacket(ordersSubmitDTO.getUseRedPacket())
                .build();

        // Reservation, stock deduction and order persistence must succeed together.
        // The RabbitMQ consumer remains compatible with messages already in the queue.
        createOrderFromMessage(messageDTO);
        Orders created = orderMapper.getByNumber(orderNumber);

        return OrderSubmitVO.builder()
                .orderNumber(orderNumber)
                .orderAmount(payableAmount)
                .orderTime(LocalDateTime.now())
                .id(created == null ? null : created.getId())
                .build();
    }

    @Transactional
    public void createOrderFromMessage(OrderSubmitMessageDTO messageDTO) {
        Orders exists = orderMapper.getByNumber(messageDTO.getOrderNumber());
        if (exists != null) {
            return;
        }

        AddressBook addressBook = addressBookMapper.getByIdAndUserId(messageDTO.getAddressBookId(), messageDTO.getUserId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(messageDTO.getUserId());
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.size() == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }
        ensureShopOpen();
        validateCartItems(shoppingCartList);
        deductCartStock(shoppingCartList);

        Orders order = new Orders();
        BeanUtils.copyProperties(messageDTO, order);
            order.setPhone(addressBook.getPhone());
            order.setAddress(addressBook.getDetail());
            order.setConsignee(addressBook.getConsignee());
            order.setNumber(messageDTO.getOrderNumber());
            order.setUserId(messageDTO.getUserId());
            order.setStatus(Orders.PENDING_PAYMENT);
            order.setPayStatus(Orders.UN_PAID);
            order.setOrderTime(LocalDateTime.now());
            BigDecimal originAmount = calculateCartAmount(shoppingCartList);
            if (messageDTO.getCouponId() != null && messageDTO.getUserRedPacketId() != null) {
                throw new OrderBusinessException("coupon and red packet cannot be used together");
            }
            Long userRedPacketId = resolveRedPacketId(messageDTO.getUserId(), messageDTO.getCouponId(),
                    messageDTO.getUserRedPacketId(), messageDTO.getUseRedPacket());
            BigDecimal discountAmount = calculateDiscount(messageDTO.getUserId(), messageDTO.getCouponId(), originAmount)
                    .add(calculateRedPacketDiscount(messageDTO.getUserId(), userRedPacketId, originAmount));
            int packAmount = calculatePackAmount(shoppingCartList);
            order.setCouponId(messageDTO.getCouponId());
            order.setUserRedPacketId(userRedPacketId);
            order.setDiscountAmount(discountAmount);
            // amount 是实际支付金额：菜品金额 - 优惠 + 打包费 + 配送费。
            // 前端传入的 amount、packAmount 均不参与结算，避免被篡改。
            order.setPackAmount(packAmount);
            order.setAmount(originAmount.subtract(discountAmount)
                    .add(BigDecimal.valueOf(packAmount).multiply(PACK_FEE_PER_ITEM))
                    .add(DELIVERY_FEE));

            order.setExpireTime(order.getOrderTime().plusMinutes(15));
            orderMapper.insert(order);
            markCouponUsed(messageDTO.getUserId(), messageDTO.getCouponId(), order.getId());
            reserveRedPacket(messageDTO.getUserId(), userRedPacketId, order.getId());

            List<OrderDetail> orderDetailList = new ArrayList<>();
            for (ShoppingCart cart : shoppingCartList) {
                OrderDetail orderDetail = new OrderDetail();
                BeanUtils.copyProperties(cart, orderDetail);
                orderDetail.setOrderId(order.getId());
                orderDetail.setAmount(queryCurrentPrice(cart));
                orderDetailList.add(orderDetail);
            }

            orderDetailMapper.insertBatch(orderDetailList);
            shoppingCartMapper.deleteByUserId(messageDTO.getUserId());
        reliability.scheduleTimeout(order);
    }

    @Transactional
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
    }t

    /**
     * 闂備浇宕垫慨鎶芥⒔瀹ュ鍨傞柣鐔稿閺嗭箓鏌ｉ弮鍌氬付缂備讲鏅滈妵鍕冀閵娧勫櫘闁?
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
        User user=userMapper.getById(userId);
        JSONObject result=weChatPayUtil.pay(order.getNumber(),order.getAmount(),"Sky take-out order",user.getOpenid());
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
     * 闂傚倷娴囬妴鈧柛瀣尰閵囧嫰寮介妸褎鍣柣銏╁灡閻╊垶寮婚悢琛″亾濞戞顏呮叏閸パ€鏀芥い鏃傛櫕濞叉挳鏌℃担鍝バゅù鐙呯畵瀹曟鎮℃惔鈥茬礋闂傚倷娴囬妴鈧柛瀣尵閻ヮ亪顢橀悙鏉戭€涢梺鍛婅壘閸婂潡寮诲☉妯滄梹鎷呴崷顓фФ濠电姵顔栭崰妤呭箖閸岀偟宓侀柍?
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {
        if(!mockPaymentEnabled) throw new OrderBusinessException("verified payment receipt required");
        if(lifecycle.paid(outTradeNo,"mock:"+outTradeNo,null,true)) notifyPaid(outTradeNo);
    }

    /**
     * 闂傚倷鐒﹀鍨焽閸ф绀夌€广儱顦弰銉︾箾閹寸偞鐨戦柛妤佺閵囧嫰寮介妸褏顓奸梺鍛婅壘閸婂潡寮诲☉妯滄梹鎷呴崷顓фК闂備焦鎮堕崝宀€鈧稈鏅犳俊鐢稿礋椤栨艾鐎銈嗗姂閸婃洖顕ｉ妸鈺傜厽?
     *
     * @param pageNum
     * @param pageSize
     * @param status
     * @return
     */
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        // 闂備浇宕垫慨宕囩矆娴ｈ娅犲ù鐘差儐閸嬵亪鏌涢埄鍐槈缂佲偓閸℃稒鐓曢柍鈺佸暟閳笺倝鏌?
        PageHelper.startPage(pageNum, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);
        ordersPageQueryDTO.setUserDeleted(0);

        // 闂傚倷绀侀幉锛勬暜閹烘嚦娑樷攽鐎ｎ€儱顭块懜闈涘缂佺嫏鍥ㄧ厓闁靛鍎辩痪褎銇勯幇顏嗙煓闁哄矉绱曟禒锔炬嫚閹绘帒袚婵?
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList();

        // 闂傚倷绀侀幖顐ゆ偖椤愶箑纾块柟缁㈠櫘閺佸淇婇妶鍛櫣缂佲偓閸曨倠鏃堟晜缁涘鍔岄梺鍛婅壘閸婂潡寮诲☉妯滄梹鎷呴崷顓фО婵犵鈧啿绾ч柛銊ユ贡濡叉劙骞掑Δ鈧獮銏°亜閹捐泛啸妞わ富鍠栬灃闁绘﹢娼ф禒鈺傜箾婢跺娲撮柟顖氬暙閳规垿宕奸悢閿嬫珴闂備礁鎲℃笟妤呭储閻撳寒鍤曢柣顔煎derVO闂備礁鎼ˇ顐﹀疾濠婂懐鐭欓柡宥庡幑閳ь兛绶氶獮瀣晝閳ь剚瀵奸悩纰樺亾楠炲灝鍔氶柟铏姉閳?
        if (page != null && page.getTotal() > 0) {
            for (Orders orders : page) {
                Long orderId = orders.getId();// 闂備浇宕垫慨鎶芥⒔瀹ュ鍨傞柣鐔稿閺嗭箓鏌ｉ幒鎾剁厵

                // 闂傚倷绀侀幖顐ゆ偖椤愶箑纾块柟缁㈠櫘閺佸淇婇妶鍛殲濠殿垰銈搁弻鏇＄疀閺囩倫銏㈢磼閳ь剛鈧綆鍠楅悡娑㈡煕閺囥劌浜濋柟鐧哥悼缁?
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
     * 闂傚倷绀侀幖顐ゆ偖椤愶箑纾块柟缁㈠櫘閺佸淇婇妶鍛殲濠殿垰銈搁弻鏇＄疀閺囩倫銏㈢磼閳ь剛鈧綆鍠楅崑锝夋煕閵壯冨幋婵＄虎鍣ｉ弻?
     *
     * @param id
     * @return
     */
    public OrderVO details(Long id) {
        // 闂傚倷绀侀幖顐ょ矓閻戞枻缍栧璺猴功閺嗐倕鈽夐弮鍌涙殜闂傚倷绀侀幖顐ゆ偖椤愶箑纾块柟缁㈠櫘閺佸淇婇妶鍛殲濠殿垰銈搁弻鏇＄疀閺囩倫銏㈢磼閳?
        Orders orders = requireOwnedOrder(id);

        // 闂傚倷绀侀幖顐ゆ偖椤愶箑纾块柟缁㈠櫘閺佸淇婇妶鍛殲閻庢碍宀搁弻鏇熷緞濡厧甯ラ梺鍛婅壘閸婂潡寮诲☉妯滄梹鎷呴崷顓фК婵＄偑鍊栧ú锕傚窗濡ゅ懎绠氶柡鍐ㄧ墕缁秹鏌涚仦鍓с€掗柡鍡欏█濮婃椽宕楅悡搴殝缂備緡鍠栭惌鍌炵嵁?婵犵數濞€濞佳囧磹瑜版帇鈧啯绻濋崶浣告喘閹晫绮欓崹顔兼尋闂佺澹堥幓顏嗗緤閸ф鍎?
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 闂備浇顕х换鎰崲閹邦儵娑橆煥閸偅鏅┑顔筋焾妞村憡鍒婃總鍛婄厪闊洦娲栧瓭缂備讲鍋撻悗锝庡枟閻撴洘绻涢崱妤呯崪鐎规悶鍎甸弻娑樷枎韫囨挴鎸冮梺褰掝棑婵炩偓闁轰礁鍊块幐濠冨緞婵犲偆妫堥梻浣筋嚙缁绘劗鎹㈢€ｎ€㈠綊宕堕锕€顦扮换婵嬪炊瑜忛ˇ顐︽⒑鐠嬪骸瀚€濞撳垾rVO濠德板€楁慨鐑藉磻閻愬搫纾块柤娴嬫櫆瀹曟煡鏌熸潏鍓х暠闁?
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);

        return orderVO;
    }

    /**
     * 闂傚倷鐒﹀鍨焽閸ф绀夌€广儱顦弰銉︾箾閹存瑥鐏╅柣顓燁殜閺屸€愁吋閸愩劌顬嬮柛鐔告倐閺岋綁鎮╅柆宥嗩€栭梺鎼炲妿閹虫捇鎮?
     *
     * @param id
     */
    @Transactional
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
     * 闂傚倷绀侀幉锟犲礉閺囩姷鐭撻梻鍫熶緱閻掍粙鏌涢…鎴濅簽闁崇粯妫冮弻宥堫檨闁告挾鍠庨悾?
     *
     * @param id
     */
    public void repetition(Long id) {
        // 闂傚倷绀侀幖顐ゆ偖椤愶箑纾块柟缁㈠櫘閺佸淇婇妶鍛仴濞存粌缍婇弻鐔煎箚瑜嶉弳杈ㄣ亜閵堝懏鍤囬柡宀嬬秮閿濈偤顢楅埀顒佷繆娴犲鐓曢柍鍝勫€块幖鈺?
        Long userId = BaseContext.getCurrentId();
        requireOwnedOrder(id);

        // 闂傚倷绀侀幖顐ょ矓閻戞枻缍栧璺猴功閺嗐倕霉閿濆洤鍔嬪┑顖氥偢閺屾洝绠涢弴鐐愩垻绱掗埀顒佸垔閺€鍕⒒娴ｅ憡鎯堥悶姘煎亰瀹曟洟骞橀鍛櫔濠德板€曠€氥劍绂嶈ぐ鎺撶厵闁诡垎鍐╂瘣濡炪們鍊曢幊姗€骞冪憴鍕闂傚牊绋撴禒濂告倵鐟欏嫭绀堥柛鐘虫崌楠炲繘鎮╃紒妯绘珕闂佽姤锚椤︻垱绔?
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        // 闂備浇顕х换鎰崲閹邦儵娑橆煥閸偅鏅ｉ悷婊呭鐢帞娑甸埀顒勬⒑閸濆嫭绀屾俊鎻掓嚇瀹曞灚绻濋崶銊у幍濡炪倖鐗楃喊宥夊闯鐟欏嫨浜滈柡鍥ュ妼閺嬨倝鏌熼崨濠傛诞婵℃崘椴哥换娑㈠级閹寸儐妫﹀Δ鐘靛仦閸ㄥ潡鏁愰悙渚晣闁挎稑瀚峰Σ褰掓⒑閼姐倕校闁告棑绠撳畷銏ゆ倷椤掍焦鐝烽梺鍦焾鐎涒晜鎱ㄥ鍫熺叆闁哄洦顨呮禍鎯ь渻閵堝棙澶勯柛鐘冲哺楠?
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(x -> {
            ShoppingCart shoppingCart = new ShoppingCart();

            // 闂備浇顕х换鎰崲閹邦儵娑樜旈崘鈺傛濡炪倖鍔戦崐鏍ㄥ垔婵傚憡鐓忛煫鍥ㄦ礀瀛濈紓浣插亾閻庯綆鍠楅崑锝夋煕閵壯冨幋婵＄虎鍣ｉ弻娑欐償閿涘嫮顔掗梺鍝勮嫰閼活垶鎮惧┑瀣妞ゆ帊闄嶉埀顒€鍟村鍝勑ч崶褍顬堥柣搴㈠嚬閸ㄧ敻濡甸幇顒夊悑濠㈣泛锕ｇ槐鑸电箾鏉堝墽绉繛鎾虫贡閹广垽宕卞☉娆戝幍濡炪倖鎸嗛崘顏冩闂備線娼荤徊楣冨箖閸屾凹鍤曟い鎺戝缁狙勭箾閸℃瑥浜炬禍娑㈡⒒娴ｅ憡鍟為悽顖滃枎閳绘柨鈽夐姀鐘虫К閻庡厜鍋撻柍褜鍓熼獮蹇氥亹閹烘繃鏅ｉ梺缁樕戠粊鎾箰閸涘瓨鐓涘璺鸿嫰閸撳磭绱掗悩鍐茬伌妞ゃ垺宀搁、娆撴倷椤掆偓椤曪繝姊洪悙钘夊姤閻忓浚浜畷?
            BeanUtils.copyProperties(x, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());

            return shoppingCart;
        }).collect(Collectors.toList());

        // 闂備浇顕х换鎰崲閹邦儵娑橆煥閸繄鐛ュ┑顔姐仜閸嬫捇鏌熼銊ユ搐閻撴盯鏌涢弴銊ュ闁烩晛鍟撮弻锝嗘償閿濆棙姣勫銈冨灩閿曨亪骞愰崨鏉戠妞ゆ牗姘ㄩ濂告偡濠婂懎顣奸悽顖涘笒閳诲秹濡堕崱娆戭啎闂佹寧绻傞悧婊堝吹濞嗗繆鏀芥い鏃€鍎虫禒杈┾偓瑙勬礃瀹€鎼佸箖瑜斿畷濂告偄閸撴彃鏅欓梻鍌欒兌椤㈠﹪顢氶弽顓炵獥闁哄稁鍋夋慨?
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 闂備浇宕垫慨鎶芥⒔瀹ュ鍨傞柣鐔稿閺嗭箓鏌ｉ弮鍌氬付缂佺姾顫夌换婵囩節閸屾稑娅ｉ梺?
     *
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        // 闂傚倸鍊风欢锟犲窗閹捐绀夌€光偓閸曨偄鐎柟鑹版彧缁茬偓鍒婃總鍛婄厪闊洦娲栧瓭缂備讲鍋撻悗锝庡枟閻撶喐淇婇姘变虎闁绘挻鍔欓弻宥堫檨闁稿繑绋撳▎銏ゅΧ閸ヮ煈娼熷┑鐘绘涧椤戝棝藟閸℃稒鐓冪憸婊堝礈濞戙垹绠熼柣妤€鐗忛悷褰掓煃瑜滈崜鐔荤熅闂佺鏈粙鎴犳閻愮儤鐓涚€广儱楠告晶鎵磼閻樺磭鍙€闁哄本鐩俊鐤槻濞寸姭鏅滈妵鍕箻瀹曞洨楔閻庤娲╃换婵嗩嚕閹绢喖鐏崇€规洖娲ㄥ瓭闂傚倷绀侀幉锛勬崲閳ь剚淇婇悙鏉戠瑲缂佸矁椴哥换婵嬪炊瑜忛悾鍐差渻閵堝棛澧﹂柛濠冾殘缁牊寰勯幇顓炩偓鍫曠叓閸ャ劍灏伴柟顖涙涧ders闂備礁鎼ˇ閬嶅磿閹版澘绀堟繛鍡樺灍閸嬫捇妫冨☉妯奸獓缂備礁顑呴ˇ鍗烆嚕瑜嶉埢鏃€绋婇埡顡竀O
        List<OrderVO> orderVOList = getOrderVOList(page);

        return new PageResult(page.getTotal(), orderVOList);
    }

    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        // 闂傚倸鍊搁崐绋棵洪悩璇茬；闁瑰墽绮崑锟犳煛閸ャ劎顣茬紒浣瑰缁辨帞绱掑Ο铏逛紝閻庤娲栭悥濂稿箖濞嗘劧绱ｆ繝闈涙閸婃洟姊绘担鍛婂暈妞ゃ劌妫楃叅婵犻潧顭堟禍鍦偓骞垮劚椤︻垱瀵奸悩瑁佸綊鏁愰崼鐔诲悅缂備浇顕уΛ婵嬪蓟閻旂⒈鏁囩憸宥夋倶閼碱剛纾奸弶鍫涘妿缁犵偤鏌熼搹顐ゆ创妞ゃ垺妫冨畷鐔碱敇閻斿摜妲ユ繝鐢靛仦閸ㄥ爼寮婚妸鈹库偓渚€寮叉穱顦媟VO闂傚倷绀侀幉锛勬崲閸屾粎鐭撻悗鍨摃婵娊鏌ょ喊鍗炲缁炬儳銈搁弻鐔煎箚瑜滈崵鐔访?
        List<OrderVO> orderVOList = new ArrayList<>();

        List<Orders> ordersList = page.getResult();
        if (!CollectionUtils.isEmpty(ordersList)) {
            for (Orders orders : ordersList) {
                // 闂備浇顕х换鎰崲閹邦儵娑樜旈崨顓犵厬闂佽法鍠撴慨瀵告喆閿曞倹鍊堕柣鎰仛濞呮洟鏌熼悾灞芥瀾缂佺粯鐩畷濂稿Ψ閿斾粙鏁俊鐐€ч梽鍕偂閳ュ磭鏆︽慨妯块哺瀹曞鏌涘┑鍡楊伀闁告ɑ澧簉derVO
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                String orderDishes = getOrderDishesStr(orders);

                // 闂備浇顕х换鎰崲閹邦儵娑橆煥閸偅鏅ｉ悷婊呭鐢帞娑甸埀顒勬⒑閸濆嫭绀岄柍褜鍓欑壕顓犵矓閹绢喗鈷戦柛娑橈功缁犳垶淇婇悙鏉戠瑲缂佸矁椴哥换婵嬪炊瑜忛悾鍐差渻閵堝棛澧慨妯稿妼閳绘捇鏌嗗鍡椾画婵炶揪绲介幉锟犲闯瑜版帗鐓曢柍杞扮贰閸炵derVO婵犵數鍋為崹鍫曞箹閳哄懎鐭楅煫鍥ㄦ礃椤洘绻濋棃娑卞剳鐎规挷绶氶弻銈夊箹娴ｈ閿┑鐐插悑閸ㄥ潡寮诲☉銏犵睄闁稿本顕撮姀銈嗙厱闁宠桨绶￠崬绔erVOList
                orderVO.setOrderDishes(orderDishes);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }

    /**
     * 闂傚倷绀侀幖顐ょ矓閻戞枻缍栧璺猴功閺嗐倕霉閿濆洤鍔嬪┑顖氥偢閺屾洝绠涢弴鐐愩垻绱掗埀顒佸垔閺€鍕⒒娴ｇ鎮戦柛搴㈠▕瀹曟煡鎳犻煬韫睏闂佹悶鍎洪崜姘跺吹閺囩喓绡€濠电姴鍊搁弳鐐烘煙椤栨艾鈧瓕褰侀梺鎼炲劵缁茶姤鏅堕幓鎹楀綊鎮℃惔銏犳闂侀€炲苯澧紒瀣浮閵嗗啴宕奸妷顔芥櫈婵炶揪绲藉﹢閬嶅煝?
     *
     * @param orders
     * @return
     */
    private String getOrderDishesStr(Orders orders) {
        // 闂傚倷绀侀幖顐ゆ偖椤愶箑纾块柟缁㈠櫘閺佸淇婇妶鍛殲濠殿垰銈搁弻鏇＄疀閺囩倫銏㈢磼閳ь剛鈧綆鍠楅悡鐘绘煙缁嬫寧鎹ｉ柣顓烇躬閺岀喐鎷呴崘鍙夊櫤閻庢碍宀搁弻銊╁籍閸ヮ煈妫勯梺鍛婃尰濠㈡褰侀梺鎼炲劵缁茶姤鏅堕幓鎹楀綊鎮℃惔銏犳畻闂佽桨绀佺粔鐟扮暦婵傚憡鏅查幖绮瑰墲閸婃洟姊绘担鍛婂暈妞ゃ劌妫楃叅闁硅揪闄勯崵鍫ユ煙鏉堥箖妾柛搴＄Ч閺屾盯寮撮妸銉︾亪缂傚倸绉撮幊姗€寮诲☉妯锋斀闁告侗鍨煎Σ顕€姊虹涵鍛吂闁告鍟块锝夘敃閿濆拋鍤ら梺鍝勵槹閸ㄥ爼顢旈敓鐘斥拺?
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 闂備浇顕х换鎰崲閹邦儵娑樷枎閹炬潙鍓ㄩ梺鏂ユ櫅閸熶即鍩㈤弮鍫熺厓鐟滄粓宕滈悢濂夊殨闁割偅娲橀鎰渻閵堝棙灏紓宥咃工閻ｇ兘鏁撻悩鎻掑祮闂佺偨鍎辩壕顓犵矓閹绢喗鈷戦柛娑橈功缁犳垶淇婇悙鏉戠瑲缂佸矁椴哥换婵嬪炊瑜忛悾鍐差渻閵堝棛澧紒瀣灴椤㈡梹绻濆顓犲幍闂佽鍘界敮鎺楀礉濞嗘挻鍋ｉ柟閭﹀枛閺嬫棃鏌嶈閸撴氨绮欓幒妞尖偓鍐醇閵夘喗鏅炴繛杈剧到濠€閬嶅煝閺冨牊鍋ｉ柛銉ユ搐閹虫劙藝閳哄懏鈷戦柛婵嗗鑲栭梺鍛婎焼閸涱噮娼熼梺璺ㄥ枔婵敻寮查浣瑰弿婵妫楁晶濠氭煕閻戝棗浜滈懣鎰版煕閵夛絽濡跨紒鐘靛仦缁绘盯骞栭鐐寸亶缂?3闂傚倷鐒︾€笛呯矙閹烘绀冮柤濮愬€栭～?
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            String orderDish = x.getName() + "*" + x.getNumber() + ";";
            return orderDish;
        }).collect(Collectors.toList());

        // 闂備浇顕х换鎰崲閹邦儵娑橆煥閸偅鏅┑顔筋焾妞村憡鍒婃總鍛婄厪闊洦娲栧瓭缂備讲鍋撻悗锝庡枟閸婄敻鏌ｉ姀鐘典粵闁搞倐鍋撶紓鍌欓檷閸斿秹鎮￠敓鐘茬畾闁告劦鍠栫粈瀣亜閹哄棗浜惧銈忕稻閻擄繝寮婚敓鐘查唶婵犲灚鍔栨瓏闁荤喐绮嶅妯肩矓閻熸壆鏆︽い鎰剁稻婵挳鏌у顒€鈧崵绮诲鑸碘拺閻犲洠鈧啿瀛ｉ梺鎼炲姀娴滎剟鍩€椤掍礁鍝虹紒鐘崇墵閻涱噣骞掑Δ鈧敮闂侀潧顧€缁犳帡宕ラ锝囩闁瑰鍋炵亸銊╂煕鐎ｎ偅宕岄柟?
        return String.join("", orderDishList);
    }

    private String generateOrderNumber() {
        return System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

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

    private BigDecimal queryCurrentPrice(ShoppingCart cart) {
        if (cart.getDishId() != null) {
            Dish dish = dishMapper.getById(cart.getDishId());
            if (dish == null || !Integer.valueOf(1).equals(dish.getStatus())) {
                throw new OrderBusinessException("dish is unavailable");
            }
            return dish.getPrice() == null ? BigDecimal.ZERO : dish.getPrice();
        }
        if (cart.getSetmealId() != null) {
            Setmeal setmeal = setmealMapper.getById(cart.getSetmealId());
            if (setmeal == null || !Integer.valueOf(1).equals(setmeal.getStatus())) {
                throw new OrderBusinessException("setmeal is unavailable");
            }
            return setmeal.getPrice() == null ? BigDecimal.ZERO : setmeal.getPrice();
        }
        return cart.getAmount() == null ? BigDecimal.ZERO : cart.getAmount();
    }

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

    private void reserveRedPacket(Long userId, Long redPacketId, Long orderId) {
        if (redPacketId == null) {
            return;
        }
        if (redPacketMapper.reserve(redPacketId, userId, orderId) != 1) {
            throw new OrderBusinessException("red packet already used or expired");
        }
    }

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

    private void deductCartStock(List<ShoppingCart> shoppingCartList) {
        for (ShoppingCart cart : shoppingCartList) {
            int number = cart.getNumber();
            if (cart.getDishId() != null) {
                int affected = dishMapper.deductStock(cart.getDishId(), number);
                if (affected == 0) {
                    throw new OrderBusinessException("dish stock not enough");
                }
            } else if (cart.getSetmealId() != null) {
                int affected = setmealMapper.deductStock(cart.getSetmealId(), number);
                if (affected == 0) {
                    throw new OrderBusinessException("setmeal stock not enough");
                }
            }
        }
    }

    private void saveMqFailMessage(String exchange, String routingKey, Object body, Exception e) {
        mqFailMessageMapper.insert(MqFailMessage.builder()
                .exchangeName(exchange)
                .routingKey(routingKey)
                .messageBody(JSON.toJSONString(body))
                .failReason(e.getMessage())
                .status(0)
                .retryCount(0)
                .createTime(LocalDateTime.now())
                .build());
    }

    /**
     * 闂傚倷绀侀幉锟犳嚌閸撗€鍋撳闂撮偗闁哄苯鑻…銊╁醇閻旇渹姹楅梻浣哄帶閹芥粓銆傛禒瀣；闁规儳鐡ㄦ刊鎾煟閹寸儐鐒介柡鍡欏█閺岋綁鎮╅柆宥嗩€栭梺鎼炲妿閹虫捇鎮鹃悜鑺ュ亜闁绘挸瀛╅悗顒佺箾閺夋垵鎮戞繛鍏肩懃閳诲秹濡堕崶鈺冿紳婵炶揪绲介幖顐﹀几濞嗘劑浜?     *
     * @return
     */
    public OrderStatisticsVO statistics() {
        // 闂傚倷绀侀幖顐ょ矓閻戞枻缍栧璺猴功閺嗐倕銆掑锝呬壕闂佽鍠掗弲鐘诲箠濠婂懎鏋堟俊顖濇〃婢规洘绻涢幘纾嬪闁挎洩濡囩划鍫熷緞閹邦厾鍘遍梺鍦劋閸ㄥ爼藟濠婂牊鐓曢柍杞扮劍椤ャ垻鈧鍠栭…閿嬩繆閻戣В鈧箓骞嬪┑鍥╂殸闂傚倷绀侀幉锟犲垂閻㈢绠规い鎰╁€栭浠嬫煟閺冨倸甯剁紒鐘冲▕閺屾洘寰勯崼婵嗗缂備讲鍋撻悗锝庡枟閻撳繘鏌涢埄鍐╃妞わ讣濡囩槐鎺楀礈娴ｅ憡鐎婚柣鎾卞€曡灃闁挎繂鎳庨弳濠囨煕鐎ｎ偅灏伴柟宄版噽閸犲﹤螣鏉炴澘顥氬┑鐐舵彧缂嶁偓妞ゎ偄顦甸幃婊堟晝閸屾稈鎷哄銈嗗姀閸撴繈藝閻戣姤鐓犻柛顭戝亜閻忔挳鏌熼姘殻鐎规洜鍠栭、姘跺川椤撶喎鈧洟姊绘担鍛婂暈妞ゃ劌妫楃叅婵せ鍋撶€殿喗濞婇幃銏ゅ礂閼测晛甯?
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        // 闂備浇顕х换鎰崲閹邦儵娑樷枎閹炬潙浜楅梺鎸庣☉鐎氼厾鈧碍宀搁弻鏇＄疀閺囩倫銏℃叏閿濆拋妯€闁哄矉缍佹俊鎼佸Ψ閵夘喕鐥梻浣告惈濡盯宕伴弽顓犲祦闁硅揪瀵岄弫濠囨煠濞村娅嗘い鏂跨墦閺岋綁鎮㈤崜渚囨М闂佺懓鍟块柊锝呯暦閾忓厜鍋撻崷顓熺derStatisticsVO婵犵數鍋為崹鍫曞箹閳哄懎鍌ㄥ┑鍌滎焾缁犲綊姊洪崹顕呭剱闁?
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    /**
     * 闂傚倷娴囬～澶嬬娴犲纾块柛妤冨仧閺?
     *
     * @param ordersConfirmDTO
     */
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        if(orderMapper.transition(ordersConfirmDTO.getId(),2,3)!=1) throw new OrderBusinessException("order state changed");
    }

    /**
     * 闂傚倷绀佺紞濠囧绩鏉堚晜鏆滈柟鐑樺灩閺?
     *
     * @param ordersRejectionDTO
     */
    @Transactional
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        lifecycle.cancel(ordersRejectionDTO.getId(),ordersRejectionDTO.getRejectionReason(),"REJECT");
    }

    /**
     * 闂傚倷绀侀幉锟犳偡閿曞倹鍋嬫俊銈呭暟閻挸顪冪€ｎ亜顒㈠┑顖氥偢閺屾洝绠涢弴鐐愩垻绱掗埀?
     *
     * @param ordersCancelDTO
     */
    @Transactional
    public void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        lifecycle.cancel(ordersCancelDTO.getId(),ordersCancelDTO.getCancelReason(),"ADMIN");
    }

    /**
     * 濠电姷鏁搁崑鐐烘偂閿涘嫮涓嶉柡宥庡幖閻掑灚銇勯幋鐐差嚋缂佷胶澧楅妵鍕箻瀹曞洨楔閻?
     *
     * @param id
     */
    public void delivery(Long id) {
        if(orderMapper.transition(id,3,4)!=1) throw new OrderBusinessException("order state changed");
    }

    /**
     * 闂備浇顕уù鐑藉箠閹捐瀚夋い鎺戝閸ㄥ倹鎱ㄥΟ鍧楀摵濠殿垰銈搁弻鏇＄疀閺囩倫銏㈢磼閳?
     *
     * @param id
     */
    public void complete(Long id) {
        if(orderMapper.transition(id,4,5)!=1) throw new OrderBusinessException("order state changed");
    }

    /**
     * 闂傚倷鐒﹀鍨焽閸ф绀夌€广儱顦弰銉︾箾閹存瑥鐏╃痪鎯у悑閵囧嫰骞掗崱妞惧闁?
     *
     * @param id
     */
    public void reminder(Long id) {
        // 闂傚倷绀侀幖顐ゆ偖椤愶箑纾块柟缁㈠櫘閺佸淇婇妶鍛殲濠殿垰銈搁弻鏇＄疀閺囩倫銏㈢磼閳ь剛鈧綆鍠楅悡娑㈡煕閺囥垺娑ч柣蹇曞█閺岀喖顢涘▎鎺戝帯闂侀€炲苯澧紒瀣浮閺佸鈹戦悩顐壕?
        Orders orders = requireOwnedOrder(id);

        //闂傚倷鑳剁涵鍫曞疾閻愬樊娴栭柕濞у棗小闂佽崵鍋炲濉⊿ocket闂備浇顕ф绋匡耿闁秴纾婚柣鏃囧亹瀹撲線鏌涢妷顔煎缁炬儳鍚嬮妵鍕箳閸℃ぞ澹曢柣?
        Map map = new HashMap();
        map.put("type", 2);//2婵犵數鍋涢顓熷垔鐎靛摜绀婇柍褜鍓熼弻鏇㈠幢濡も偓閺嗭綁鏌熼鑽ょ煓濠碘剝鎮傞弫鍐焵椤掑嫭鍋傞柡鍥ュ灪閻撴洜鈧厜鍋撳┑鐘插€搁～鈺呮倵?
        map.put("orderId", id);
        map.put("content", "order number: " + orders.getNumber());
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }


    @Override
    public void paySuccess(String number,String transactionId,BigDecimal amount) {
        if(lifecycle.paid(number,transactionId,amount,false)) notifyPaid(number);
    }

    private void notifyPaid(String number) {
        // Payment is already committed. A notification failure must not reject the callback.
        try {
            Orders order=orderMapper.getByNumber(number);
            Map<String,Object> message=new HashMap<>();
            message.put("type",1); message.put("orderId",order.getId()); message.put("content","order number: "+number);
            webSocketServer.sendToAllClient(JSON.toJSONString(message));
        } catch(Exception e) { log.warn("Payment committed but websocket notification failed: {}",number,e); }
    }

    private void validateRequestId(OrdersSubmitDTO dto) {
        if (dto == null || dto.getRequestId() == null || !dto.getRequestId().matches("[A-Za-z0-9_-]{16,64}")) {
            throw new OrderBusinessException("requestId is required");
        }
    }

    private OrderSubmitVO findSubmittedOrder(Long userId, String requestId) {
        String orderNumber = orderSubmitRequestMapper.findOrderNumber(userId, requestId);
        if (orderNumber == null) {
            return null;
        }
        Orders order = orderMapper.getByNumberAndUserId(orderNumber, userId);
        if (order == null) {
            return null;
        }
        return OrderSubmitVO.builder().id(order.getId()).orderNumber(order.getNumber())
                .orderAmount(order.getAmount()).orderTime(order.getOrderTime()).build();
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
