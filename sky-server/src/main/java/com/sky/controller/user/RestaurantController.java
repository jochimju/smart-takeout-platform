package com.sky.controller.user;

import com.sky.entity.Category;
import com.sky.entity.Setmeal;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.RestaurantMapper;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.service.SetmealService;
import com.sky.service.RestaurantAvailabilityService;
import com.sky.vo.DishVO;
import com.sky.vo.RestaurantVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/user/restaurants")
@Api(tags = "C端-多餐厅接口")
public class RestaurantController {

    @Autowired
    private RestaurantMapper restaurantMapper;
    @Autowired
    private DishService dishService;
    @Autowired
    private SetmealService setmealService;
    @Autowired
    private RestaurantAvailabilityService restaurantAvailabilityService;

    @GetMapping
    @ApiOperation("查询可用餐厅")
    public Result<List<RestaurantVO>> list() {
        List<RestaurantVO> restaurants = restaurantMapper.listEnabled();
        restaurants.forEach(restaurantAvailabilityService::describe);
        return Result.success(restaurants);
    }

    @GetMapping("/{restaurantId}")
    @ApiOperation("查询餐厅详情")
    public Result<RestaurantVO> detail(@PathVariable Long restaurantId) {
        return Result.success(restaurantAvailabilityService.describe(requireRestaurant(restaurantId)));
    }

    @GetMapping("/{restaurantId}/categories")
    @ApiOperation("查询餐厅分类")
    public Result<List<Category>> categories(@PathVariable Long restaurantId,
                                              @RequestParam(required = false) Integer type) {
        restaurantAvailabilityService.requireOpen(restaurantId);
        return Result.success(restaurantMapper.listCategories(restaurantId, type));
    }

    @GetMapping("/{restaurantId}/dishes")
    @ApiOperation("查询餐厅菜品")
    public Result<List<DishVO>> dishes(@PathVariable Long restaurantId, Long categoryId) {
        requireCategory(restaurantId, categoryId);
        return Result.success(dishService.listWithFlavorCache(categoryId));
    }

    @GetMapping("/{restaurantId}/setmeals")
    @ApiOperation("查询餐厅套餐")
    public Result<List<Setmeal>> setmeals(@PathVariable Long restaurantId, Long categoryId) {
        requireCategory(restaurantId, categoryId);
        return Result.success(setmealService.listCache(categoryId));
    }

    private RestaurantVO requireRestaurant(Long restaurantId) {
        RestaurantVO restaurant = restaurantMapper.getEnabledById(restaurantId);
        if (restaurant == null) {
            throw new OrderBusinessException("RESTAURANT_NOT_FOUND");
        }
        return restaurant;
    }

    private void requireCategory(Long restaurantId, Long categoryId) {
        restaurantAvailabilityService.requireOpen(restaurantId);
        if (categoryId == null || restaurantMapper.countCategory(restaurantId, categoryId) != 1) {
            throw new OrderBusinessException("DISH_NOT_IN_RESTAURANT");
        }
    }
}
