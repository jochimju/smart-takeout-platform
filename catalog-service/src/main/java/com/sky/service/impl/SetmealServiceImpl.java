package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.cache.MenuCache;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.SetmealEnableFailedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import com.sky.vo.SetmealVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SetmealServiceImpl implements SetmealService {
    private final SetmealMapper setmealMapper;
    private final SetmealDishMapper setmealDishMapper;
    private final DishMapper dishMapper;
    private final MenuCache menuCache;

    @Override @Transactional
    public void saveWithDish(SetmealDTO dto) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(dto, setmeal);
        setmealMapper.insert(setmeal);
        saveRelations(setmeal.getId(), dto.getSetmealDishes());
        menuCache.invalidateAll();
    }
    @Override public PageResult pageQuery(SetmealPageQueryDTO dto) {
        PageHelper.startPage(dto.getPage(), dto.getPageSize());
        Page<SetmealVO> page = setmealMapper.pageQuery(dto);
        return new PageResult(page.getTotal(), page.getResult());
    }
    @Override @Transactional
    public void deleteBatch(List<Long> ids) {
        for (Long id : ids) {
            Setmeal setmeal = setmealMapper.getById(id);
            if (setmeal != null && StatusConstant.ENABLE == setmeal.getStatus())
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
        }
        ids.forEach(id -> { setmealMapper.deleteById(id); setmealDishMapper.deleteBySetmealId(id); });
        menuCache.invalidateAll();
    }
    @Override public SetmealVO getByIdWithDish(Long id) {
        Setmeal setmeal = setmealMapper.getById(id);
        SetmealVO vo = new SetmealVO();
        if (setmeal != null) BeanUtils.copyProperties(setmeal, vo);
        vo.setSetmealDishes(setmealDishMapper.getBySetmealId(id));
        return vo;
    }
    @Override @Transactional
    public void update(SetmealDTO dto) {
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(dto, setmeal);
        setmealMapper.update(setmeal);
        setmealDishMapper.deleteBySetmealId(dto.getId());
        saveRelations(dto.getId(), dto.getSetmealDishes());
        menuCache.invalidateAll();
    }
    @Override @Transactional
    public void startOrStop(Integer status, Long id) {
        if (StatusConstant.ENABLE == status) {
            for (Dish dish : dishMapper.getBySetmealId(id)) {
                if (StatusConstant.DISABLE == dish.getStatus())
                    throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
            }
        }
        setmealMapper.update(Setmeal.builder().id(id).status(status).build());
        menuCache.invalidateAll();
    }
    @Override public List<Setmeal> list(Setmeal value) { return setmealMapper.list(value); }
    @Override public List<Setmeal> listCache(Long categoryId) {
        return menuCache.get("setmeal", categoryId,
                () -> list(Setmeal.builder().categoryId(categoryId).status(StatusConstant.ENABLE).build()));
    }
    @Override public List<DishItemVO> getDishItemById(Long id) { return setmealMapper.getDishItemBySetmealId(id); }
    private void saveRelations(Long id, List<SetmealDish> relations) {
        List<SetmealDish> safe = relations == null ? Collections.emptyList() : relations;
        safe.forEach(item -> item.setSetmealId(id));
        if (!safe.isEmpty()) setmealDishMapper.insertBatch(safe);
    }
}
