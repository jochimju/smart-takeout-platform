package com.sky.mapper;

import com.sky.entity.SeckillReservation;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SeckillReservationMapper {
    @Select("select * from seckill_reservation where order_number=#{number}")
    SeckillReservation get(String number);

    @Select("select * from seckill_reservation where user_id=#{user} and request_id=#{request}")
    SeckillReservation byRequest(@Param("user") Long user, @Param("request") String request);

    @Update("update seckill_reservation set request_id=#{request},request_hash=#{hash} where order_number=#{number}")
    void setRequest(@Param("number") String number,@Param("request") String request,@Param("hash") String hash);

    @Select("select count(*) from seckill_reservation where activity_id=#{id}")
    int countForActivity(Long id);

    @Update("update seckill_reservation set release_status=2 where order_number=#{number} and release_status<>2")
    int releaseOnce(String number);
    @Insert("insert into seckill_reservation(order_number,activity_id,setmeal_id,user_id,release_status) values(#{number},#{activity},#{setmeal},#{user},0)")
    void insert(@Param("number") String number, @Param("activity") Long activity,
                @Param("setmeal") Long setmeal, @Param("user") Long user);

    @Select("select * from seckill_reservation where order_number=#{number} for update")
    SeckillReservation lock(String number);

    @Update("update seckill_reservation set release_status=1 where order_number=#{number} and release_status=0")
    int markPending(String number);

    @Update("update seckill_reservation set release_status=2 where order_number=#{number} and release_status=1")
    int markReleased(String number);

    @Select("select order_number from seckill_reservation where release_status=1 limit 100")
    List<String> pending();
}
