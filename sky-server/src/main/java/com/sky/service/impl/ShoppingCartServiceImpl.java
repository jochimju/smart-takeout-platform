package com.sky.service.impl;


import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import com.sky.service.RestaurantAvailabilityService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private RestaurantAvailabilityService restaurantAvailabilityService;

    /**
     * 添加购物车
     *
     * @param shoppingCartDTO
     */
    @Transactional
    public void addShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        ShoppingCart shoppingCart = buildCart(shoppingCartDTO);
        Long currentCanteenId = shoppingCartMapper.findCanteenIdByUserId(shoppingCart.getUserId());
        if (currentCanteenId != null && !currentCanteenId.equals(shoppingCart.getCanteenId())) {
            throw new ShoppingCartBusinessException("CART_RESTAURANT_CONFLICT");
        }
        addResolvedCart(shoppingCart);
    }

    @Override
    @Transactional
    public void switchRestaurantAndAdd(ShoppingCartDTO shoppingCartDTO) {
        ShoppingCart shoppingCart = buildCart(shoppingCartDTO);
        Long currentCanteenId = shoppingCartMapper.findCanteenIdByUserId(shoppingCart.getUserId());
        if (currentCanteenId != null && !currentCanteenId.equals(shoppingCart.getCanteenId())) {
            shoppingCartMapper.deleteByUserId(shoppingCart.getUserId());
        }
        addResolvedCart(shoppingCart);
    }

    private ShoppingCart buildCart(ShoppingCartDTO shoppingCartDTO) {
        if (shoppingCartDTO == null || (shoppingCartDTO.getDishId() == null && shoppingCartDTO.getSetmealId() == null)
                || (shoppingCartDTO.getDishId() != null && shoppingCartDTO.getSetmealId() != null)) {
            throw new ShoppingCartBusinessException("请选择一个菜品或套餐");
        }
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);
        shoppingCart.setUserId(BaseContext.getCurrentId());
        if (shoppingCartDTO.getDishId() != null) {
            Dish dish = dishMapper.getById(shoppingCartDTO.getDishId());
            if (dish == null || dish.getStatus() == null || dish.getStatus() != 1 || dish.getCanteenId() == null) {
                throw new ShoppingCartBusinessException("DISH_OFF_SALE");
            }
            shoppingCart.setName(dish.getName());
            shoppingCart.setImage(dish.getImage());
            shoppingCart.setAmount(dish.getPrice());
            shoppingCart.setCanteenId(dish.getCanteenId());
        } else {
            Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
            if (setmeal == null || setmeal.getStatus() == null || setmeal.getStatus() != 1 || setmeal.getCanteenId() == null) {
                throw new ShoppingCartBusinessException("DISH_OFF_SALE");
            }
            shoppingCart.setName(setmeal.getName());
            shoppingCart.setImage(setmeal.getImage());
            shoppingCart.setAmount(setmeal.getPrice());
            shoppingCart.setCanteenId(setmeal.getCanteenId());
        }
        if (shoppingCartDTO.getCanteenId() != null && !shoppingCartDTO.getCanteenId().equals(shoppingCart.getCanteenId())) {
            throw new ShoppingCartBusinessException("DISH_NOT_IN_RESTAURANT");
        }
        restaurantAvailabilityService.requireOpen(shoppingCart.getCanteenId());
        return shoppingCart;
    }

    private void addResolvedCart(ShoppingCart shoppingCart) {
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList != null && shoppingCartList.size() == 1) {
            ShoppingCart existing = shoppingCartList.get(0);
            existing.setNumber(existing.getNumber() + 1);
            shoppingCartMapper.updateNumberById(existing);
            return;
        }
        shoppingCart.setNumber(1);
        shoppingCart.setCreateTime(LocalDateTime.now());
        shoppingCartMapper.insert(shoppingCart);
    }

    @Override
    public void subShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        ShoppingCart condition = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, condition);
        condition.setUserId(BaseContext.getCurrentId());

        if (shoppingCartDTO.getDishId() != null) {
            Dish dish = dishMapper.getById(shoppingCartDTO.getDishId());
            if (dish == null || dish.getCanteenId() == null) return;
            condition.setCanteenId(dish.getCanteenId());
        } else if (shoppingCartDTO.getSetmealId() != null) {
            Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
            if (setmeal == null || setmeal.getCanteenId() == null) return;
            condition.setCanteenId(setmeal.getCanteenId());
        }

        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(condition);
        if (shoppingCartList == null || shoppingCartList.isEmpty()) {
            return;
        }

        ShoppingCart shoppingCart = shoppingCartList.get(0);
        if (shoppingCart.getNumber() > 1) {
            shoppingCart.setNumber(shoppingCart.getNumber() - 1);
            shoppingCartMapper.updateNumberById(shoppingCart);
        } else {
            shoppingCartMapper.deleteById(shoppingCart.getId());
        }
    }
    /**
     * 查看购物车
     * @return
     */
    public List<ShoppingCart> showShoppingCart() {
        return shoppingCartMapper.list(ShoppingCart.
                builder().
                userId(BaseContext.getCurrentId()).
                build());
    }

    /**
     * 清空购物车商品
     */
    public void cleanShoppingCart() {
        shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());
    }
}
