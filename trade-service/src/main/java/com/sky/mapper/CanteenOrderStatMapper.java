package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 供商品服务删除餐厅前校验关联订单数。
 */
@Mapper
public interface CanteenOrderStatMapper {

    @Select("select count(1) from orders where canteen_id = #{canteenId}")
    int countByCanteen(Long canteenId);
}
