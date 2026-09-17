package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.RestaurantPageQueryDTO;
import com.sky.entity.Canteen;
import com.sky.vo.CampusZoneVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CanteenMapper {
    Page<Canteen> pageQuery(RestaurantPageQueryDTO queryDTO);

    @Select("select * from canteen where id = #{id}")
    Canteen getById(Long id);

    @Select("select id, code, name from campus_zone where status = 1 order by id asc")
    List<CampusZoneVO> listEnabledCampusZones();

    @Select("select count(1) from campus_zone where id = #{id} and status = 1")
    int countEnabledCampusZone(Long id);

    @Select("select count(1) from canteen where code = #{code} and id != ifnull(#{excludeId}, 0)")
    int countByCode(@Param("code") String code, @Param("excludeId") Long excludeId);

    @Select("select count(1) from canteen where campus_zone_id = #{campusZoneId} and name = #{name} and id != ifnull(#{excludeId}, 0)")
    int countByZoneAndName(@Param("campusZoneId") Long campusZoneId, @Param("name") String name,
                           @Param("excludeId") Long excludeId);

    @Insert("insert into canteen (campus_zone_id, code, name, location, description, meal_period, status, sort, create_time, update_time) " +
            "values (#{campusZoneId}, #{code}, #{name}, #{location}, #{description}, #{mealPeriod}, #{status}, #{sort}, #{createTime}, #{updateTime})")
    void insert(Canteen canteen);

    @Update("update canteen set campus_zone_id=#{campusZoneId}, code=#{code}, name=#{name}, location=#{location}, " +
            "description=#{description}, meal_period=#{mealPeriod}, sort=#{sort}, update_time=#{updateTime} where id=#{id}")
    int update(Canteen canteen);

    @Update("update canteen set status=#{status}, update_time=#{updateTime} where id=#{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status,
                     @Param("updateTime") java.time.LocalDateTime updateTime);

    @Select("select count(1) from stall where canteen_id = #{canteenId}")
    int countStalls(Long canteenId);

    @Select("select count(1) from category where canteen_id = #{canteenId}")
    int countCategories(Long canteenId);

    @Select("select count(1) from dish where canteen_id = #{canteenId}")
    int countDishes(Long canteenId);

    @Select("select count(1) from setmeal where canteen_id = #{canteenId}")
    int countSetmeals(Long canteenId);

    @Select("select count(1) from orders where canteen_id = #{canteenId}")
    int countOrders(Long canteenId);

    @Delete("delete from canteen where id = #{id}")
    int deleteById(Long id);
}
