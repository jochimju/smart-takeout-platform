package com.sky.service;

import com.sky.exception.OrderBusinessException;
import com.sky.mapper.RestaurantMapper;
import com.sky.utils.MealPeriodUtils;
import com.sky.vo.RestaurantVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Applies the server-side meal schedule to the manually maintained restaurant status. */
@Service
public class RestaurantAvailabilityService {
    @Autowired
    private RestaurantMapper restaurantMapper;

    public RestaurantVO describe(RestaurantVO restaurant) {
        if (restaurant == null) return null;
        boolean manualOpen = "OPEN".equals(restaurant.getBusinessStatus());
        boolean mealTimeOpen = MealPeriodUtils.isOpen(restaurant.getMealPeriod());
        restaurant.setBusinessStatus(manualOpen && mealTimeOpen ? "OPEN" : "CLOSED");
        restaurant.setMealPeriodText(MealPeriodUtils.displayName(restaurant.getMealPeriod()));
        return restaurant;
    }

    public RestaurantVO requireOpen(Long restaurantId) {
        RestaurantVO restaurant = describe(restaurantMapper.getEnabledById(restaurantId));
        if (restaurant == null) throw new OrderBusinessException("RESTAURANT_NOT_FOUND");
        if (!"OPEN".equals(restaurant.getBusinessStatus())) {
            throw new OrderBusinessException("餐厅休息中，" + restaurant.getMealPeriodText());
        }
        return restaurant;
    }
}
