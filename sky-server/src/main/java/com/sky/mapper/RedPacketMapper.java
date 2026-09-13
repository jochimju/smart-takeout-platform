package com.sky.mapper;

import com.sky.entity.RedPacketPackage;
import com.sky.entity.RedPacketPurchaseOrder;
import com.sky.entity.UserRedPacket;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RedPacketMapper {
    @Select("select * from red_packet_package where id=#{id} and status=1")
    RedPacketPackage activePackage(Long id);

    @Select("select * from red_packet_package where status=1 order by id")
    List<RedPacketPackage> activePackages();

    @Insert("insert into red_packet_purchase_order(order_no,user_id,package_id,pay_amount,packet_count_snapshot,packet_amount_snapshot,valid_months_snapshot,status,create_time) values(#{orderNo},#{userId},#{packageId},#{payAmount},#{packetCountSnapshot},#{packetAmountSnapshot},#{validMonthsSnapshot},#{status},#{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPurchase(RedPacketPurchaseOrder order);

    @Select("select * from red_packet_purchase_order where order_no=#{orderNo}")
    RedPacketPurchaseOrder purchaseByNumber(String orderNo);

    @Select("select * from red_packet_purchase_order where order_no=#{orderNo} for update")
    RedPacketPurchaseOrder lockPurchase(String orderNo);

    @Update("update red_packet_purchase_order set status=1,transaction_id=#{transactionId},paid_time=#{paidTime} where id=#{id} and status=0")
    int markPurchasePaid(@Param("id") Long id, @Param("transactionId") String transactionId, @Param("paidTime") LocalDateTime paidTime);

    @Insert("insert into user_red_packet(user_id,package_id,purchase_order_id,sequence_no,amount,status,receive_time,expire_time) values(#{userId},#{packageId},#{purchaseOrderId},#{sequenceNo},#{amount},#{status},#{receiveTime},#{expireTime})")
    int insertUserRedPacket(UserRedPacket packet);

    @Select("select * from user_red_packet where user_id=#{userId} and status=0 and expire_time>now() order by expire_time,id")
    List<UserRedPacket> availableByUser(Long userId);

    @Select("select * from user_red_packet where id=#{id} and user_id=#{userId}")
    UserRedPacket byIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update user_red_packet set status=1,food_order_id=#{orderId} where id=#{id} and user_id=#{userId} and status=0 and expire_time>now()")
    int reserve(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") Long orderId);
}
