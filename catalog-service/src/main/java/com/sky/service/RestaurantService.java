package com.sky.service;

import com.sky.dto.RestaurantDTO;
import com.sky.dto.RestaurantPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.vo.CampusZoneVO;

import java.util.List;

/**
 * 餐厅（食堂）管理服务。
 */
public interface RestaurantService {

    void create(RestaurantDTO dto);

    void update(RestaurantDTO dto);

    void updateStatus(Long id, Integer status);

    void delete(Long id);

    PageResult pageQuery(RestaurantPageQueryDTO queryDTO);

    List<CampusZoneVO> listCampusZones();
}
