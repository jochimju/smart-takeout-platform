package com.sky.controller.internal;

import com.sky.contract.catalog.ProductQuote;
import com.sky.contract.catalog.ProductQuoteRequest;
import com.sky.contract.catalog.CatalogOverview;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;

@RestController
@RequestMapping("/internal/catalog")
@RequiredArgsConstructor
public class CatalogInternalController {
    private final DishMapper dishMapper;
    private final SetmealMapper setmealMapper;
    @Value("${sky.internal.token}") private String internalToken;

    @PostMapping("/quotes")
    public List<ProductQuote> quotes(@RequestHeader("X-Internal-Token") String token,
                                     @RequestBody ProductQuoteRequest request) {
        if (!Objects.equals(token, internalToken)) throw new IllegalArgumentException("invalid internal token");
        List<ProductQuote> result = new ArrayList<>();
        if (request.getDishIds() != null) request.getDishIds().stream().distinct().forEach(id -> {
            Dish d = dishMapper.getById(id);
            if (d != null) result.add(ProductQuote.builder().productId(id).productType(ProductQuote.DISH)
                    .name(d.getName()).image(d.getImage()).price(d.getPrice()).status(d.getStatus())
                    .stock(d.getStock()).version(version(d.getUpdateTime())).canteenId(d.getCanteenId()).build());
        });
        if (request.getSetmealIds() != null) request.getSetmealIds().stream().distinct().forEach(id -> {
            Setmeal s = setmealMapper.getById(id);
            if (s != null) result.add(ProductQuote.builder().productId(id).productType(ProductQuote.SETMEAL)
                    .name(s.getName()).image(s.getImage()).price(s.getPrice()).status(s.getStatus())
                    .stock(s.getStock()).version(version(s.getUpdateTime())).canteenId(s.getCanteenId()).build());
        });
        return result;
    }

    @org.springframework.web.bind.annotation.GetMapping("/overview")
    public CatalogOverview overview(@RequestHeader("X-Internal-Token") String token) {
        if (!Objects.equals(token, internalToken)) throw new IllegalArgumentException("invalid internal token");
        HashMap<String,Object> enabled=new HashMap<>(); enabled.put("status",1);
        HashMap<String,Object> disabled=new HashMap<>(); disabled.put("status",0);
        return CatalogOverview.builder().soldDishes(dishMapper.countByMap(enabled))
                .discontinuedDishes(dishMapper.countByMap(disabled))
                .soldSetmeals(setmealMapper.countByMap(enabled)).discontinuedSetmeals(setmealMapper.countByMap(disabled)).build();
    }

    private long version(java.time.LocalDateTime time) {
        return time == null ? 0L : time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
