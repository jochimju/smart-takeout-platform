package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 璁㈠崟
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Orders implements Serializable {

    /**
     * 璁㈠崟鐘舵€?1寰呬粯娆?2寰呮帴鍗?3宸叉帴鍗?4娲鹃€佷腑 5宸插畬鎴?6宸插彇娑?
     */
    public static final Integer PENDING_PAYMENT = 1;
    public static final Integer TO_BE_CONFIRMED = 2;
    public static final Integer CONFIRMED = 3;
    public static final Integer DELIVERY_IN_PROGRESS = 4;
    public static final Integer COMPLETED = 5;
    public static final Integer CANCELLED = 6;

    /**
     * 鏀粯鐘舵€?0鏈敮浠?1宸叉敮浠?2閫€娆?
     */
    public static final Integer UN_PAID = 0;
    public static final Integer PAID = 1;
    public static final Integer REFUND = 2;

    private static final long serialVersionUID = 1L;

    private Long id;

    //璁㈠崟鍙?
    private String number;

    //璁㈠崟鐘舵€?1寰呬粯娆?2寰呮帴鍗?3宸叉帴鍗?4娲鹃€佷腑 5宸插畬鎴?6宸插彇娑?7閫€娆?
    private Integer status;

    //涓嬪崟鐢ㄦ埛id
    private Long userId;

    //鍦板潃id
    private Long addressBookId;

    //涓嬪崟鏃堕棿
    private LocalDateTime orderTime;

    private LocalDateTime expireTime;

    //缁撹处鏃堕棿
    private LocalDateTime checkoutTime;

    //鏀粯鏂瑰紡 1寰俊锛?鏀粯瀹?
    private Integer payMethod;

    //鏀粯鐘舵€?0鏈敮浠?1宸叉敮浠?2閫€娆?
    private Integer payStatus;

    //瀹炴敹閲戦
    private BigDecimal amount;

    private Long couponId;

    private BigDecimal discountAmount;

    private Long userRedPacketId;

    //澶囨敞
    private String remark;

    //鐢ㄦ埛鍚?
    private String userName;

    //鎵嬫満鍙?
    private String phone;

    //鍦板潃
    private String address;

    //鏀惰揣浜?
    private String consignee;

    //璁㈠崟鍙栨秷鍘熷洜
    private String cancelReason;

    //璁㈠崟鎷掔粷鍘熷洜
    private String rejectionReason;

    //璁㈠崟鍙栨秷鏃堕棿
    private LocalDateTime cancelTime;

    //棰勮閫佽揪鏃堕棿
    private LocalDateTime estimatedDeliveryTime;

    //閰嶉€佺姸鎬? 1绔嬪嵆閫佸嚭  0閫夋嫨鍏蜂綋鏃堕棿
    private Integer deliveryStatus;

    //閫佽揪鏃堕棿
    private LocalDateTime deliveryTime;

    //鎵撳寘璐?
    private int packAmount;

    //椁愬叿鏁伴噺
    private int tablewareNumber;

    //椁愬叿鏁伴噺鐘舵€? 1鎸夐閲忔彁渚? 0閫夋嫨鍏蜂綋鏁伴噺
    private Integer tablewareStatus;

    //所属餐厅（食堂）id
    private Long canteenId;

    //下单时的餐厅名称快照
    private String canteenName;
}
