package com.sky.service;

import com.sky.exception.OrderBusinessException;
import com.sky.mapper.RestaurantMapper;
import com.sky.utils.MealPeriodUtils;
import com.sky.vo.RestaurantVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 餐厅营业状态判定：手动开关与用餐时段共同决定最终状态。
 */
@Service
@RequiredArgsConstructor
public class RestaurantAvailabilityService {

    private static final String OPEN = "OPEN";
    private static final String CLOSED = "CLOSED";

    private final RestaurantMapper restaurantMapper;

    /**
     * 把数据库里的档口聚合状态与用餐时段合并成对外状态，并补充展示文案。
     */
    public RestaurantVO describe(RestaurantVO restaurant) {
        if (restaurant == null) {
            return null;
        }
        boolean manualOpen = OPEN.equals(restaurant.getBusinessStatus());
        boolean mealTimeOpen = MealPeriodUtils.isOpen(restaurant.getMealPeriod());
        restaurant.setBusinessStatus(manualOpen && mealTimeOpen ? OPEN : CLOSED);
        restaurant.setMealPeriodText(MealPeriodUtils.displayName(restaurant.getMealPeriod()));
        return restaurant;
    }

    /**
     * 校验餐厅处于营业中，未营业时抛出可读的业务异常。
     */
    public RestaurantVO requireOpen(Long restaurantId) {
        RestaurantVO restaurant = describe(restaurantMapper.getEnabledById(restaurantId));
        if (restaurant == null) {
            throw new OrderBusinessException("RESTAURANT_NOT_FOUND");
        }
        if (!OPEN.equals(restaurant.getBusinessStatus())) {
            throw new OrderBusinessException("餐厅休息中，" + restaurant.getMealPeriodText());
        }
        return restaurant;
    }
}
