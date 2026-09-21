package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.client.TradeClient;
import com.sky.dto.RestaurantDTO;
import com.sky.dto.RestaurantPageQueryDTO;
import com.sky.entity.Canteen;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.CanteenMapper;
import com.sky.result.PageResult;
import com.sky.service.RestaurantService;
import com.sky.vo.CampusZoneVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class RestaurantServiceImpl implements RestaurantService {

    private final CanteenMapper canteenMapper;
    private final TradeClient tradeClient;

    @Override
    public void create(RestaurantDTO dto) {
        validate(dto, null);
        Canteen canteen = new Canteen();
        BeanUtils.copyProperties(dto, canteen);
        canteen.setStatus(1);
        canteen.setMealPeriod(normalizeMealPeriod(dto.getMealPeriod()));
        canteen.setSort(dto.getSort() == null ? 0 : dto.getSort());
        canteen.setCreateTime(LocalDateTime.now());
        canteen.setUpdateTime(LocalDateTime.now());
        canteenMapper.insert(canteen);
    }

    @Override
    public void update(RestaurantDTO dto) {
        if (dto == null || dto.getId() == null || canteenMapper.getById(dto.getId()) == null) {
            throw new OrderBusinessException("餐厅不存在");
        }
        validate(dto, dto.getId());
        Canteen canteen = new Canteen();
        BeanUtils.copyProperties(dto, canteen);
        canteen.setMealPeriod(normalizeMealPeriod(dto.getMealPeriod()));
        canteen.setSort(dto.getSort() == null ? 0 : dto.getSort());
        canteen.setUpdateTime(LocalDateTime.now());
        canteenMapper.update(canteen);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        if (id == null || status == null || (status != 0 && status != 1)) {
            throw new OrderBusinessException("餐厅状态只能是 0（停用）或 1（启用）");
        }
        if (canteenMapper.updateStatus(id, status, LocalDateTime.now()) != 1) {
            throw new OrderBusinessException("餐厅不存在");
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (id == null || canteenMapper.getById(id) == null) {
            throw new OrderBusinessException("餐厅不存在");
        }
        int related = canteenMapper.countStalls(id)
                + canteenMapper.countCategories(id)
                + canteenMapper.countDishes(id)
                + canteenMapper.countSetmeals(id)
                + countOrders(id);
        if (related > 0) {
            throw new DeletionNotAllowedException("餐厅仍关联档口、菜单或订单，不能删除；请使用停用功能");
        }
        canteenMapper.deleteById(id);
    }

    @Override
    public PageResult pageQuery(RestaurantPageQueryDTO queryDTO) {
        PageHelper.startPage(queryDTO.getPage(), queryDTO.getPageSize());
        Page<Canteen> page = canteenMapper.pageQuery(queryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    @Override
    public List<CampusZoneVO> listCampusZones() {
        return canteenMapper.listEnabledCampusZones();
    }

    /**
     * 订单属于交易服务，通过内部接口统计，避免跨库直连。
     * 订单服务不可用时保守失败，避免误删仍有关联订单的餐厅。
     */
    private int countOrders(Long canteenId) {
        try {
            Integer count = tradeClient.countOrdersByCanteen(canteenId);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("查询餐厅关联订单失败, canteenId={}", canteenId, e);
            throw new OrderBusinessException("订单服务暂不可用，无法确认餐厅关联情况，请稍后重试");
        }
    }

    private void validate(RestaurantDTO dto, Long excludeId) {
        if (dto == null || dto.getCampusZoneId() == null
                || isBlank(dto.getCode()) || isBlank(dto.getName()) || isBlank(dto.getLocation())) {
            throw new OrderBusinessException("校区、餐厅编码、名称和位置均不能为空");
        }
        if (canteenMapper.countEnabledCampusZone(dto.getCampusZoneId()) != 1) {
            throw new OrderBusinessException("校区不存在或已停用");
        }
        if (canteenMapper.countByCode(dto.getCode().trim(), excludeId) > 0) {
            throw new OrderBusinessException("餐厅编码已存在");
        }
        if (canteenMapper.countByZoneAndName(dto.getCampusZoneId(), dto.getName().trim(), excludeId) > 0) {
            throw new OrderBusinessException("该校区内餐厅名称已存在");
        }
        dto.setCode(dto.getCode().trim());
        dto.setName(dto.getName().trim());
        dto.setLocation(dto.getLocation().trim());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalizeMealPeriod(String mealPeriod) {
        String value = mealPeriod == null ? "ALL" : mealPeriod.trim().toUpperCase();
        if (!"ALL".equals(value) && !"BREAKFAST".equals(value)
                && !"LUNCH".equals(value) && !"DINNER".equals(value)) {
            throw new OrderBusinessException("用餐时段只能是早餐、午餐、晚餐或三餐");
        }
        return value;
    }
}
