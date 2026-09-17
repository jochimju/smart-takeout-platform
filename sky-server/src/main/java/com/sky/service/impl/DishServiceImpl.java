package com.sky.service.impl;



import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.RedisKeyConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.entity.Setmeal;
import com.sky.entity.Category;
import com.sky.entity.Canteen;
import com.sky.event.DishStatusChangedEvent;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.OrderBusinessException;
import com.sky.cache.MenuCache;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.CanteenMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;

    @Autowired
    private SetmealDishMapper setmealDishMapper;

    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private CanteenMapper canteenMapper;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private MenuCache menuCache;

    /**
     * 新增菜品和对应的口味
     *
     * @param dishDTO
     */
    @Transactional //因为同时要操作多个表，所以要保持数据的一致性，需要加@Transactional注解
    public void saveWithFlavor(DishDTO dishDTO) {  //同时操作菜品表和口味表
        normalizeStock(dishDTO);
        validateDishOwnership(dishDTO, null);

        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        //向菜品表插入1条数据  （每次只能添加一个菜品）
        dishMapper.insert(dish);//后绪步骤实现

        //获取insert语句生成的主键值
        Long dishId = dish.getId();

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishId);
            });
            //向口味表插入n条数据
            dishFlavorMapper.insertBatch(flavors);//后绪步骤实现
        }
    }

    /**
     * 菜品分页查询
     *
     * @param dishPageQueryDTO
     * @return
     */
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);//后绪步骤实现
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 菜品批量删除
     *
     * @param ids
     */
    @Transactional//事务
    public void deleteBatch(List<Long> ids) {
        //判断当前菜品是否能够删除---是否存在起售中的菜品？？   	可以先写一下业务逻辑，然后再翻译成java代码
        for (Long id : ids) {
            Dish dish = dishMapper.getById(id);//后绪步骤实现
            if (dish.getStatus() == StatusConstant.ENABLE) {
                //当前菜品处于起售中，不能删除
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }

        //判断当前菜品是否能够删除---是否被套餐关联了？？
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && setmealIds.size() > 0) {
            //当前菜品被套餐关联了，不能删除
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }

        //删除菜品表中的菜品数据
        for (Long id : ids) {
            dishMapper.deleteById(id);//后绪步骤实现
            //删除菜品关联的口味数据
            dishFlavorMapper.deleteByDishId(id);//后绪步骤实现
        }
    }

    /**
     * 根据id查询菜品和对应的口味数据
     *
     * @param id
     * @return
     */
    public DishVO getByIdWithFlavor(Long id) {
        //根据id查询菜品数据
        Dish dish = dishMapper.getById(id);

        //根据菜品id查询口味数据
        List<DishFlavor> dishFlavors = dishFlavorMapper.getByDishId(id);//后绪步骤实现

        //将查询到的数据封装到VO
        DishVO dishVO = new DishVO();
        BeanUtils.copyProperties(dish, dishVO);
        dishVO.setFlavors(dishFlavors);

        return dishVO;
    }

    /**
     * 根据id修改菜品基本信息和对应的口味信息
     *
     * @param dishDTO
     */
    @Transactional
    public void updateWithFlavor(DishDTO dishDTO) {
        validateStock(dishDTO.getStock());
        validateDishOwnership(dishDTO, dishDTO == null ? null : dishDTO.getId());
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        //修改菜品表基本信息
        dishMapper.update(dish);

        //删除原有的口味数据
        dishFlavorMapper.deleteByDishId(dishDTO.getId());

        //重新插入口味数据
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishDTO.getId());
            });
            //向口味表插入n条数据
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    /**
     * 兼容未升级的管理端：新增时没有填写库存则按 0 入库，避免将 null 写入 NOT NULL 字段。
     */
    private void normalizeStock(DishDTO dishDTO) {
        if (dishDTO.getStock() == null) {
            dishDTO.setStock(0);
        }
        validateStock(dishDTO.getStock());
    }

    private void validateStock(Integer stock) {
        if (stock != null && stock < 0) {
            throw new OrderBusinessException("库存不能小于 0");
        }
    }

    /** 新增必须显式选择餐厅；编辑则始终沿用原餐厅，不能借编辑跨餐厅搬运菜品。 */
    private void validateDishOwnership(DishDTO dishDTO, Long existingDishId) {
        if (dishDTO == null || dishDTO.getCategoryId() == null) {
            throw new OrderBusinessException("请选择菜品分类");
        }
        Long canteenId = dishDTO.getCanteenId();
        if (existingDishId != null) {
            Dish existing = dishMapper.getById(existingDishId);
            if (existing == null) throw new OrderBusinessException("菜品不存在");
            if (canteenId != null && !canteenId.equals(existing.getCanteenId())) {
                throw new OrderBusinessException("菜品不允许跨餐厅迁移，请在目标餐厅新建菜品");
            }
            canteenId = existing.getCanteenId();
            dishDTO.setCanteenId(canteenId);
        }
        if (canteenId == null) throw new OrderBusinessException("请选择所属餐厅后再维护菜品");
        Canteen canteen = canteenMapper.getById(canteenId);
        if (canteen == null || !Integer.valueOf(1).equals(canteen.getStatus())) {
            throw new OrderBusinessException("所属餐厅不存在或已停用");
        }
        Category category = categoryMapper.getById(dishDTO.getCategoryId());
        if (category == null || category.getType() == null || category.getType() != 1 || !canteenId.equals(category.getCanteenId())) {
            throw new OrderBusinessException("菜品分类不属于当前餐厅");
        }
    }

    /**
     * 菜品起售停售
     *
     * @param status
     * @param id
     */
    @Transactional
    public void startOrStop(Integer status, Long id) {
        boolean setmealsChanged = false;
        Dish dish = Dish.builder()
                .id(id)
                .status(status)
                .build();
        dishMapper.update(dish);

        if (status == StatusConstant.DISABLE) {
            // 如果是停售操作，还需要将包含当前菜品的套餐也停售
            List<Long> dishIds = new ArrayList<>();
            dishIds.add(id);
            // select setmeal_id from setmeal_dish where dish_id in (?,?,?)
            List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(dishIds);
            if (setmealIds != null && setmealIds.size() > 0) {
                setmealsChanged = true;
                for (Long setmealId : setmealIds) {
                    Setmeal setmeal = Setmeal.builder()
                            .id(setmealId)
                            .status(StatusConstant.DISABLE)
                            .build();
                    setmealMapper.update(setmeal);
                }
            }
        }
        eventPublisher.publishEvent(new DishStatusChangedEvent(setmealsChanged));
    }

    /**
     * 根据分类id查询菜品
     * @param categoryId
     * @return
     */
    public List<Dish> list(Long categoryId) {
        Dish dish = Dish.builder()
                .categoryId(categoryId)
                .status(StatusConstant.ENABLE)
                .build();
        return dishMapper.list(dish);
    }

    /**
     * 条件查询菜品和口味
     * @param dish
     * @return
     */
    public List<DishVO> listWithFlavor(Dish dish) {
        List<Dish> dishList = dishMapper.list(dish);

        List<DishVO> dishVOList = new ArrayList<>();

        for (Dish d : dishList) {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(d,dishVO);

            //根据菜品id查询对应的口味
            List<DishFlavor> flavors = dishFlavorMapper.getByDishId(d.getId());

            dishVO.setFlavors(flavors);
            dishVOList.add(dishVO);
        }

        return dishVOList;
    }

    public List<DishVO> listWithFlavorCache(Long categoryId) {
        return menuCache.get("dish", categoryId, () -> listWithFlavor(Dish.builder()
                .categoryId(categoryId).status(StatusConstant.ENABLE).build()));
    }
}
