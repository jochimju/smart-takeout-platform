package com.sky.controller.user;

import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;
import com.sky.result.Result;
import com.sky.service.ShoppingCartService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Api(tags = "shopping cart api")
@RequestMapping("/user/shoppingCart")
@Slf4j
public class ShoppingCartController {

    @Autowired
    private ShoppingCartService shoppingCartService;

    @PostMapping("/add")
    @ApiOperation("add shopping cart")
    public Result<String> add(@RequestBody ShoppingCartDTO shoppingCartDTO) {
        log.info("add shopping cart: {}", shoppingCartDTO);
        shoppingCartService.addShoppingCart(shoppingCartDTO);
        return Result.success();
    }

    @PostMapping("/switch")
    @ApiOperation("清空原餐厅购物车并加入新餐厅商品")
    public Result<String> switchRestaurant(@RequestBody ShoppingCartDTO shoppingCartDTO) {
        shoppingCartService.switchRestaurantAndAdd(shoppingCartDTO);
        return Result.success();
    }

    @PostMapping("/sub")
    @ApiOperation("sub shopping cart")
    public Result<String> sub(@RequestBody ShoppingCartDTO shoppingCartDTO) {
        log.info("sub shopping cart: {}", shoppingCartDTO);
        shoppingCartService.subShoppingCart(shoppingCartDTO);
        return Result.success();
    }

    @GetMapping("/list")
    @ApiOperation("list shopping cart")
    public Result<List<ShoppingCart>> list() {
        return Result.success(shoppingCartService.showShoppingCart());
    }

    @DeleteMapping("/clean")
    @ApiOperation("clean shopping cart")
    public Result<String> clean() {
        shoppingCartService.cleanShoppingCart();
        return Result.success();
    }
}
