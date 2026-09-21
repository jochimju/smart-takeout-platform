package com.sky.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sky.entity.MqFailMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MqFailMessageMapper extends BaseMapper<MqFailMessage> {

    @Insert("insert into mq_fail_message(exchange_name, routing_key, message_body, fail_reason, status, retry_count, create_time) " +
            "values(#{exchangeName}, #{routingKey}, #{messageBody}, #{failReason}, #{status}, #{retryCount}, #{createTime})")
    int insert(MqFailMessage mqFailMessage);

    @Select("select * from mq_fail_message where status = 0 and retry_count < #{limit} order by create_time asc limit #{size}")
    List<MqFailMessage> listRetryable(@Param("limit") int limit, @Param("size") int size);

    @Update("update mq_fail_message set status = 3, lease_until = date_add(now(), interval 2 minute), " +
            "update_time = now() where id = #{id} and status = 0 and retry_count < #{limit}")
    int claimRetry(@Param("id") Long id, @Param("limit") int limit);

    @Update("update mq_fail_message set status = 0, lease_until = null where status = 3 and lease_until < now()")
    int releaseExpiredClaims();

    @Update("update mq_fail_message set status = 1, lease_until = null, update_time = #{updateTime} where id = #{id} and status = 3")
    int markSuccess(@Param("id") Long id, @Param("updateTime") LocalDateTime updateTime);

    @Update("update mq_fail_message set status = 0, lease_until = null, retry_count = retry_count + 1, " +
            "fail_reason = #{failReason}, update_time = #{updateTime} where id = #{id} and status = 3")
    int increaseRetry(@Param("id") Long id, @Param("failReason") String failReason, @Param("updateTime") LocalDateTime updateTime);

    @Update("update mq_fail_message set status = 2, lease_until = null, fail_reason = #{failReason}, " +
            "update_time = #{updateTime} where id = #{id} and status = 3")
    int markDead(@Param("id") Long id, @Param("failReason") String failReason, @Param("updateTime") LocalDateTime updateTime);
}
