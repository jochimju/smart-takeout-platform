package com.sky.controller.admin;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import com.sky.entity.Dish;
import com.sky.constant.StatusConstant;
import com.sky.cache.MenuCache;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/dish")
@Api(tags = "admin dish api")
@Slf4j
public class DishController {

    @Autowired
    private DishService dishService;
    @Autowired
    private MenuCache menuCache;

    @PostMapping
    @ApiOperation("add dish")
    public Result save(@RequestBody DishDTO dishDTO) {
        log.info("add dish: {}", dishDTO);
        dishService.saveWithFlavor(dishDTO);
        menuCache.invalidateDishes();
        return Result.success();
    }

    @GetMapping("/page")
    @ApiOperation("page dishes")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO) {
        PageResult pageResult = dishService.pageQuery(dishPageQueryDTO);
        return Result.success(pageResult);
    }

    @DeleteMapping
    @ApiOperation("delete dishes")
    public Result delete(@RequestParam List<Long> ids) {
        dishService.deleteBatch(ids);
        menuCache.invalidateDishes();
        return Result.success();
    }

    @GetMapping("/{id}")
    @ApiOperation("get dish by id")
    public Result<DishVO> getById(@PathVariable Long id) {
        DishVO dishVO = dishService.getByIdWithFlavor(id);
        return Result.success(dishVO);
    }

    @PutMapping
    @ApiOperation("update dish")
    public Result update(@RequestBody DishDTO dishDTO) {
        dishService.updateWithFlavor(dishDTO);
        menuCache.invalidateDishes();
        return Result.success();
    }

    @PostMapping("/status/{status}")
    @ApiOperation("enable or disable dish")
    public Result<String> startOrStop(@PathVariable Integer status, Long id) {
        dishService.startOrStop(status, id);
        return Result.success();
    }

    @GetMapping("/list")
    @ApiOperation("list dishes by category")
    public Result<List<DishVO>> list(Long categoryId, String name, Long canteenId) {
        // 分类下拉读取可走缓存；套餐挑选菜品的关键词/餐厅过滤必须实时按条件查询。
        List<DishVO> list = (name == null && canteenId == null)
                ? dishService.listWithFlavorCache(categoryId)
                : dishService.listWithFlavor(Dish.builder()
                    .categoryId(categoryId).name(name).canteenId(canteenId).status(StatusConstant.ENABLE).build());
        return Result.success(list);
    }

}
