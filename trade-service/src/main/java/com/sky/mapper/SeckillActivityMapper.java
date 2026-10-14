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
    @Select("select a.*, case when a.bucket_count>1 then (select sum(b.remaining_stock) from seckill_stock_bucket b where b.activity_id=a.id) else a.remaining_stock end bucket_remaining_stock from seckill_activity a where a.id = #{activityId}")
    SeckillActivity getById(@Param("activityId") Long activityId);

    @Select("select * from seckill_activity where id=#{id}")
    SeckillActivity metadata(Long id);

    @Update("update seckill_activity set bucket_count=#{count} where id=#{id} and async_initialized=0")
    int setBucketCount(@Param("id") Long id,@Param("count") int count);

    @Select("select remaining_stock from seckill_stock_bucket where activity_id=#{id} and bucket_id=#{bucket} for update")
    Integer lockBucket(@Param("id") Long id,@Param("bucket") int bucket);

    @Update("update seckill_stock_bucket set remaining_stock=remaining_stock-#{count},stock_version=stock_version+#{count} where activity_id=#{id} and bucket_id=#{bucket} and remaining_stock>=#{count}")
    int fulfillBucket(@Param("id") Long id,@Param("bucket") int bucket,@Param("count") int count);

    @Update("update seckill_stock_bucket set remaining_stock=remaining_stock+1,stock_version=stock_version+1 where activity_id=#{id} and bucket_id=#{bucket} and remaining_stock<stock")
    int restoreBucket(@Param("id") Long id,@Param("bucket") int bucket);

    @Insert("insert into seckill_stock_bucket(activity_id,bucket_id,stock,remaining_stock) values(#{id},#{bucket},#{stock},#{stock})")
    void insertBucket(@Param("id") Long id,@Param("bucket") int bucket,@Param("stock") int stock);

    @Select("select * from seckill_activity where id=#{id} for update")
    SeckillActivity lock(Long id);

    @Update("update seckill_activity set remaining_stock=remaining_stock-1,stock_version=stock_version+1 " +
            "where id=#{id} and status=1 and begin_time<=now() and end_time>=now() and remaining_stock>0")
    int reserve(Long id);

    // Redis 已在活动有效期内受理；消费延迟不改变资格。
    @Update("update seckill_activity set remaining_stock=remaining_stock-1,stock_version=stock_version+1 where id=#{id} and remaining_stock>0")
    int fulfill(Long id);

    /** One conditional MySQL debit for all validated orders in a transaction. */
    @Update("update seckill_activity set remaining_stock=remaining_stock-#{count},stock_version=stock_version+#{count} " +
            "where id=#{id} and remaining_stock>=#{count}")
    int fulfillBatch(@Param("id") Long id,@Param("count") int count);

    @Update("update seckill_activity set async_initialized=1 where id=#{id}")
    void markAsyncInitialized(Long id);

    @Update("update seckill_activity set remaining_stock=remaining_stock+1,stock_version=stock_version+1 " +
            "where id=#{id} and remaining_stock<stock")
    int restore(Long id);

    @Select("select id from seckill_activity where runtime_retired=0 order by id")
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
            "case when a.bucket_count>1 then (select sum(b.remaining_stock) from seckill_stock_bucket b where b.activity_id=a.id) else a.remaining_stock end remainingStock, a.purchase_limit purchaseLimit, " +
            "a.begin_time beginTime, a.end_time endTime, a.status " +
            "from seckill_activity a order by a.begin_time desc")
    List<SeckillActivityVO> list();

    @Select("select a.id, a.setmeal_id setmealId, a.seckill_price seckillPrice, case when a.bucket_count>1 then (select sum(b.remaining_stock) from seckill_stock_bucket b where b.activity_id=a.id) else a.remaining_stock end stock, " +
            "case when a.bucket_count>1 then (select sum(b.remaining_stock) from seckill_stock_bucket b where b.activity_id=a.id) else a.remaining_stock end remainingStock, a.purchase_limit purchaseLimit, " +
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
