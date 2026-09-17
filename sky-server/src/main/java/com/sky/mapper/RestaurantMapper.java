package com.sky.mapper;

import com.sky.entity.Category;
import com.sky.vo.RestaurantVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RestaurantMapper {

    @Select("select c.id, c.code, c.name, c.location, c.description, c.meal_period, c.sort, " +
            "case when exists (select 1 from stall s where s.canteen_id = c.id and s.status = 1 and s.daily_status = 'OPEN') " +
            "then 'OPEN' else 'CLOSED' end as business_status " +
            "from canteen c where c.status = 1 order by c.sort asc, c.id asc")
    List<RestaurantVO> listEnabled();

    @Select("select c.id, c.code, c.name, c.location, c.description, c.meal_period, c.sort, " +
            "case when exists (select 1 from stall s where s.canteen_id = c.id and s.status = 1 and s.daily_status = 'OPEN') " +
            "then 'OPEN' else 'CLOSED' end as business_status " +
            "from canteen c where c.id = #{id} and c.status = 1")
    RestaurantVO getEnabledById(Long id);

    @Select("select * from category where canteen_id = #{canteenId} and status = 1 " +
            "and (#{type} is null or type = #{type}) order by sort asc, create_time desc")
    List<Category> listCategories(@Param("canteenId") Long canteenId, @Param("type") Integer type);

    @Select("select count(1) from category where id = #{categoryId} and canteen_id = #{canteenId} and status = 1")
    int countCategory(@Param("canteenId") Long canteenId, @Param("categoryId") Long categoryId);
}
