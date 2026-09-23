package com.sky.mapper;

import com.sky.entity.SeckillActivity;
import com.sky.vo.SeckillActivityVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface SeckillActivityMapper {
    @Select("select * from seckill_activity where id = #{activityId}")
    SeckillActivity getById(@Param("activityId") Long activityId);

    @Select("select * from seckill_activity where id=#{id} for update")
    SeckillActivity lock(Long id);

    @Update("update seckill_activity set remaining_stock=remaining_stock-1,stock_version=stock_version+1 " +
            "where id=#{id} and status=1 and begin_time<=now() and end_time>=now() and remaining_stock>0")
    int reserve(Long id);

    @Update("update seckill_activity set remaining_stock=remaining_stock+1,stock_version=stock_version+1 " +
            "where id=#{id} and remaining_stock<stock")
    int restore(Long id);

    @Select("select id from seckill_activity order by id")
    List<Long> allIds();

    @Update("update seckill_activity set stock=stock+#{delta},remaining_stock=remaining_stock+#{delta}, " +
            "stock_version=stock_version+1 where id=#{id} and remaining_stock+#{delta}>=0 and stock+#{delta}>=0")
    int adjust(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 秒杀活动只保存套餐 id，套餐资料归商品服务所有。
     * 不能 JOIN 交易库里的旧 setmeal 表：新建套餐只写商品库，关联不上会导致整条活动从列表消失。
     * 名称、图片和原价由 SeckillServiceImpl 通过商品服务报价补齐。
     */
    @Select("select a.id, a.setmeal_id setmealId, a.seckill_price seckillPrice, a.stock, " +
            "a.remaining_stock remainingStock, a.purchase_limit purchaseLimit, " +
            "a.begin_time beginTime, a.end_time endTime, a.status " +
            "from seckill_activity a order by a.begin_time desc")
    List<SeckillActivityVO> list();

    @Select("select a.id, a.setmeal_id setmealId, a.seckill_price seckillPrice, a.remaining_stock stock, " +
            "a.remaining_stock remainingStock, a.purchase_limit purchaseLimit, " +
            "a.begin_time beginTime, a.end_time endTime, a.status " +
            "from seckill_activity a where a.status=1 and a.end_time >= now() order by a.begin_time asc")
    List<SeckillActivityVO> listAvailable();

    @Insert("insert into seckill_activity (setmeal_id, stock, remaining_stock, stock_version, purchase_limit, seckill_price, begin_time, end_time, status, create_time, update_time) " +
            "values (#{setmealId}, #{stock}, #{stock}, 0, #{purchaseLimit}, #{seckillPrice}, #{beginTime}, #{endTime}, #{status}, now(), now())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(SeckillActivity activity);

    @Update("update seckill_activity set purchase_limit=#{purchaseLimit}, seckill_price=#{seckillPrice}, " +
            "begin_time=#{beginTime}, end_time=#{endTime}, status=#{status}, stock_version=stock_version+1, update_time=now() where id=#{id}")
    void update(SeckillActivity activity);

    @Delete("delete from seckill_activity where id=#{activityId}")
    int deleteById(@Param("activityId") Long activityId);

    /** 到期活动自动下架；C 端列表和 Lua 时间校验仍会即时生效。 */
    @Update("update seckill_activity set status=0, update_time=now() where status=1 and end_time < now()")
    int disableExpiredActivities();
}
