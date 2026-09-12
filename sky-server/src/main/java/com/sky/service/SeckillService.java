package com.sky.service;

import com.sky.dto.SeckillOrderSubmitDTO;
import com.sky.dto.SeckillActivityDTO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.SeckillActivityVO;
import java.util.List;

/**
 * 秒杀服务接口
 * 用于处理秒杀相关的业务逻辑
 */
public interface SeckillService {

    /**
     * 秒杀套餐下单（资格校验、防超卖、一人一单）
     * @param ordersSubmitDTO 秒杀套餐订单参数
     * @return 订单提交结果
     */
    OrderSubmitVO seckillOrder(SeckillOrderSubmitDTO ordersSubmitDTO);

    /**
     * 将活动资格和库存预热到 Redis。活动创建、库存调整后调用。
     * @param activityId 秒杀活动 ID
     */
    void warmUpActivity(Long activityId);

    /**
     * 获取 Redis 中的剩余库存；缺失时自动预热。
     * @param activityId 秒杀活动 ID
     * @return 库存数量
     */
    Integer getSeckillStock(Long activityId);

    List<SeckillActivityVO> listAvailableActivities();

    List<SeckillActivityVO> listActivities();

    void saveActivity(SeckillActivityDTO activityDTO);

    void deleteActivity(Long activityId);
    void adjustStock(Long activityId, int targetTotal, int expectedTotal);
    OrderSubmitVO findRequest(String requestId);
}
