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
     * 异步受理秒杀（Lua 预扣成功不等于订单已创建）
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
     * 获取 Redis 剩余库存；缺失时拒绝受理，禁止自动重置预留。
     * @param activityId 秒杀活动 ID
     * @return 库存数量
     */
    Integer getSeckillStock(Long activityId);

    List<SeckillActivityVO> listAvailableActivities(Long canteenId);

    List<SeckillActivityVO> listActivities();

    /**
     * 保存活动并返回活动编号，管理端可据此立即预热库存。
     */
    Long saveActivity(SeckillActivityDTO activityDTO);

    void deleteActivity(Long activityId);
    void adjustStock(Long activityId, int targetTotal, int expectedTotal);
    OrderSubmitVO findRequest(String requestId);
    OrderSubmitVO findRequest(String requestId, Long activityId);
    void createSeckillOrder(com.sky.dto.SeckillOrderMessageDTO message);
    PreparedSeckillOrder prepareSeckillOrder(com.sky.dto.SeckillOrderMessageDTO message);
    SeckillPreparation prepareSeckillOrders(List<com.sky.dto.SeckillOrderMessageDTO> messages);
    void createPreparedOrders(List<PreparedSeckillOrder> orders);
}
