package com.sky.controller.admin;

import com.sky.dto.RestaurantDTO;
import com.sky.dto.RestaurantPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.RestaurantService;
import com.sky.vo.CampusZoneVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
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

@RestController("adminRestaurantController")
@RequestMapping("/admin/restaurant")
@Api(tags = "管理端-餐厅管理")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    @PostMapping
    @ApiOperation("新增餐厅")
    public Result<Void> create(@RequestBody RestaurantDTO dto) {
        restaurantService.create(dto);
        return Result.success();
    }

    @PutMapping
    @ApiOperation("编辑餐厅")
    public Result<Void> update(@RequestBody RestaurantDTO dto) {
        restaurantService.update(dto);
        return Result.success();
    }

    @GetMapping("/page")
    @ApiOperation("餐厅分页查询")
    public Result<PageResult> page(RestaurantPageQueryDTO queryDTO) {
        return Result.success(restaurantService.pageQuery(queryDTO));
    }

    @GetMapping("/campus-zones")
    @ApiOperation("查询可用校区")
    public Result<List<CampusZoneVO>> campusZones() {
        return Result.success(restaurantService.listCampusZones());
    }

    @PutMapping("/{id}/status")
    @ApiOperation("启用或停用餐厅")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        restaurantService.updateStatus(id, status);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @ApiOperation("删除无关联餐厅")
    public Result<Void> delete(@PathVariable Long id) {
        restaurantService.delete(id);
        return Result.success();
    }
}
