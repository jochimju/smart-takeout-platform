package com.sky.service.impl;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.MqConstant;
import com.sky.constant.RedisKeyConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.Dish;
import com.sky.entity.MqFailMessage;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.SetmealEnableFailedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.MqFailMessageMapper;
import com.sky.mapper.SeckillOrderGuardMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import com.sky.vo.SeckillOrderVO;
import com.sky.vo.SetmealVO;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 濂楅涓氬姟瀹炵幇
 */
@Service
@Slf4j
public class SetmealServiceImpl implements SetmealService {
    @Autowired private OrderReliabilityStore reliability;

    @Autowired private com.sky.mapper.SeckillReservationMapper reservationMapper;

    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private SeckillOrderGuardMapper seckillOrderGuardMapper;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private com.sky.cache.MenuCache menuCache;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private DefaultRedisScript<Long> seckillScript;
    @Autowired
    private MqFailMessageMapper mqFailMessageMapper;

    /**
     * 鏂板濂楅锛屽悓鏃堕渶瑕佷繚瀛樺椁愬拰鑿滃搧鐨勫叧鑱斿叧绯?
     * @param setmealDTO
     */
    @Transactional
    public void saveWithDish(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);

        //鍚戝椁愯〃鎻掑叆鏁版嵁
        setmealMapper.insert(setmeal);

        //鑾峰彇鐢熸垚鐨勫椁恑d
        Long setmealId = setmeal.getId();

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        setmealDishes.forEach(setmealDish -> {
            setmealDish.setSetmealId(setmealId);
        });

        //淇濆瓨濂楅鍜岃彍鍝佺殑鍏宠仈鍏崇郴
        setmealDishMapper.insertBatch(setmealDishes);
    }

    /**
     * 鍒嗛〉鏌ヨ
     * @param setmealPageQueryDTO
     * @return
     */
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        int pageNum = setmealPageQueryDTO.getPage();
        int pageSize = setmealPageQueryDTO.getPageSize();

        PageHelper.startPage(pageNum, pageSize);
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 鎵归噺鍒犻櫎濂楅
     * @param ids
     */
    @Transactional
    public void deleteBatch(List<Long> ids) {
        ids.forEach(id -> {
            Setmeal setmeal = setmealMapper.getById(id);
            if(StatusConstant.ENABLE == setmeal.getStatus()){
                //璧峰敭涓殑濂楅涓嶈兘鍒犻櫎
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        });

        ids.forEach(setmealId -> {
            //鍒犻櫎濂楅琛ㄤ腑鐨勬暟鎹?
            setmealMapper.deleteById(setmealId);
            //鍒犻櫎濂楅鑿滃搧鍏崇郴琛ㄤ腑鐨勬暟鎹?
            setmealDishMapper.deleteBySetmealId(setmealId);
        });
    }

    /**
     * 鏍规嵁id鏌ヨ濂楅鍜屽椁愯彍鍝佸叧绯?
     *
     * @param id
     * @return
     */
    public SetmealVO getByIdWithDish(Long id) {
        Setmeal setmeal = setmealMapper.getById(id);
        List<SetmealDish> setmealDishes = setmealDishMapper.getBySetmealId(id);

        SetmealVO setmealVO = new SetmealVO();
        BeanUtils.copyProperties(setmeal, setmealVO);
        setmealVO.setSetmealDishes(setmealDishes);

        return setmealVO;
    }

    /**
     * 淇敼濂楅
     *
     * @param setmealDTO
     */
    @Transactional
    public void update(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);

        //1銆佷慨鏀瑰椁愯〃锛屾墽琛寀pdate
        setmealMapper.update(setmeal);

        //濂楅id
        Long setmealId = setmealDTO.getId();

        //2銆佸垹闄ゅ椁愬拰鑿滃搧鐨勫叧鑱斿叧绯伙紝鎿嶄綔setmeal_dish琛紝鎵цdelete
        setmealDishMapper.deleteBySetmealId(setmealId);

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        setmealDishes.forEach(setmealDish -> {
            setmealDish.setSetmealId(setmealId);
        });
        //3銆侀噸鏂版彃鍏ュ椁愬拰鑿滃搧鐨勫叧鑱斿叧绯伙紝鎿嶄綔setmeal_dish琛紝鎵цinsert
        setmealDishMapper.insertBatch(setmealDishes);
    }

    /**
     * 濂楅璧峰敭銆佸仠鍞?
     * @param status
     * @param id
     */
    @Transactional
    public void startOrStop(Integer status, Long id) {
        //璧峰敭濂楅鏃讹紝鍒ゆ柇濂楅鍐呮槸鍚︽湁鍋滃敭鑿滃搧锛屾湁鍋滃敭鑿滃搧鎻愮ず"濂楅鍐呭寘鍚湭鍚敭鑿滃搧锛屾棤娉曞惎鍞?
        if(status == StatusConstant.ENABLE){
            //select a.* from dish a left join setmeal_dish b on a.id = b.dish_id where b.setmeal_id = ?
            List<Dish> dishList = dishMapper.getBySetmealId(id);
            if(dishList != null && dishList.size() > 0){
                dishList.forEach(dish -> {
                    if(StatusConstant.DISABLE == dish.getStatus()){
                        throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                    }
                });
            }
        }

        Setmeal setmeal = Setmeal.builder()
                .id(id)
                .status(status)
                .build();
        setmealMapper.update(setmeal);
    }

    /**
     * 鏉′欢鏌ヨ
     * @param setmeal
     * @return
     */
    public List<Setmeal> listCache(Long categoryId) {
        return menuCache.get("setmeal", categoryId, () -> list(Setmeal.builder()
                .categoryId(categoryId).status(StatusConstant.ENABLE).build()));
    }

    public List<Setmeal> list(Setmeal setmeal) {
        List<Setmeal> list = setmealMapper.list(setmeal);
        return list;
    }

    /**
     * 鏍规嵁id鏌ヨ鑿滃搧閫夐」
     * @param id
     * @return
     */
    public List<DishItemVO> getDishItemById(Long id) {
        return setmealMapper.getDishItemBySetmealId(id);
    }

    public void preloadSeckillStock(Long setmealId, Integer stock) {
        if (stock == null || stock < 0) {
            throw new OrderBusinessException("seckill stock cannot be less than 0");
        }
        setmealMapper.update(Setmeal.builder()
                .id(setmealId)
                .stock(stock)
                .build());
        stringRedisTemplate.opsForValue().set(RedisKeyConstant.SECKILL_STOCK + setmealId, stock.toString());
        stringRedisTemplate.delete(RedisKeyConstant.SECKILL_USERS + setmealId);
    }

    public SeckillOrderVO seckill(Long setmealId) {
        throw new OrderBusinessException("请通过限时秒杀活动结算页下单");
    }

    @Transactional
    public void createSeckillOrder(SeckillOrderMessageDTO messageDTO) {
        Orders exists = orderMapper.getByNumber(messageDTO.getOrderNumber());
        if (exists != null) {
            return;
        }
        throw new OrderBusinessException("旧秒杀消息入口已停用，请通过活动结算页重新下单");
    }

    public void rollbackSeckillReservation(SeckillOrderMessageDTO messageDTO) {
        if (messageDTO == null || messageDTO.getSetmealId() == null || messageDTO.getUserId() == null) {
            return;
        }
        stringRedisTemplate.opsForValue().increment(RedisKeyConstant.SECKILL_STOCK + messageDTO.getSetmealId());
        stringRedisTemplate.opsForSet().remove(RedisKeyConstant.SECKILL_USERS + messageDTO.getSetmealId(),
                messageDTO.getUserId().toString());
    }

    private String generateOrderNumber() {
        return System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private void saveMqFailMessage(String exchange, String routingKey, Object body, Exception e) {
        mqFailMessageMapper.insert(MqFailMessage.builder()
                .exchangeName(exchange)
                .routingKey(routingKey)
                .messageBody(JSON.toJSONString(body))
                .failReason(e.getMessage())
                .status(0)
                .retryCount(0)
                .createTime(LocalDateTime.now())
                .build());
    }
}
