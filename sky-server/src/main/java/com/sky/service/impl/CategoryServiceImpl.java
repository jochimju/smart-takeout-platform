package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.CategoryDTO;
import com.sky.dto.CategoryPageQueryDTO;
import com.sky.entity.Category;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.OrderBusinessException;
import com.sky.entity.Canteen;
import com.sky.mapper.CanteenMapper;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.CategoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分类业务层
 */
@Service
@Slf4j
public class CategoryServiceImpl implements CategoryService {

    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private CanteenMapper canteenMapper;

    /**
     * 新增分类
     * @param categoryDTO
     */
    public void save(CategoryDTO categoryDTO) {
        validateCanteen(categoryDTO == null ? null : categoryDTO.getCanteenId());
        if (categoryDTO.getType() == null || (categoryDTO.getType() != 1 && categoryDTO.getType() != 2)) {
            throw new OrderBusinessException("分类类型只能是菜品分类或套餐分类");
        }
        Category category = new Category();
        //属性拷贝
        BeanUtils.copyProperties(categoryDTO, category);

        //分类状态默认为禁用状态0
        category.setStatus(StatusConstant.DISABLE);

        //设置创建时间、修改时间、创建人、修改人
//        category.setCreateTime(LocalDateTime.now());
//        category.setUpdateTime(LocalDateTime.now());
//        category.setCreateUser(BaseContext.getCurrentId());
//        category.setUpdateUser(BaseContext.getCurrentId());

        categoryMapper.insert(category);
    }

    /**
     * 分页查询
     * @param categoryPageQueryDTO
     * @return
     */
    public PageResult pageQuery(CategoryPageQueryDTO categoryPageQueryDTO) {
        PageHelper.startPage(categoryPageQueryDTO.getPage(),categoryPageQueryDTO.getPageSize());
        //下一条sql进行分页，自动加入limit关键字分页
        Page<Category> page = categoryMapper.pageQuery(categoryPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 根据id删除分类
     * @param id
     */
    public void deleteById(Long id) {
        //查询当前分类是否关联了菜品，如果关联了就抛出业务异常
        Integer count = dishMapper.countByCategoryId(id);
        if(count > 0){
            //当前分类下有菜品，不能删除
            throw new DeletionNotAllowedException(MessageConstant.CATEGORY_BE_RELATED_BY_DISH);
        }

        //查询当前分类是否关联了套餐，如果关联了就抛出业务异常
        count = setmealMapper.countByCategoryId(id);
        if(count > 0){
            //当前分类下有菜品，不能删除
            throw new DeletionNotAllowedException(MessageConstant.CATEGORY_BE_RELATED_BY_SETMEAL);
        }

        //删除分类数据
        categoryMapper.deleteById(id);
    }

    /**
     * 修改分类
     * @param categoryDTO
     */
    public void update(CategoryDTO categoryDTO) {
        if (categoryDTO == null || categoryDTO.getId() == null) {
            throw new OrderBusinessException("分类不存在");
        }
        Category existing = categoryMapper.getById(categoryDTO.getId());
        if (existing == null) {
            throw new OrderBusinessException("分类不存在");
        }
        if (existing.getCanteenId() == null) {
            throw new OrderBusinessException("历史分类尚未归属餐厅，请在目标餐厅新建分类");
        }
        // 菜单归属一旦建立不允许通过编辑迁移，避免历史菜品/套餐被拆到另一餐厅。
        if (categoryDTO.getCanteenId() != null && !categoryDTO.getCanteenId().equals(existing.getCanteenId())) {
            throw new OrderBusinessException("分类不允许跨餐厅迁移，请在目标餐厅新建分类");
        }
        Category category = new Category();
        BeanUtils.copyProperties(categoryDTO,category);

        //设置修改时间、修改人
//        category.setUpdateTime(LocalDateTime.now());
//        category.setUpdateUser(BaseContext.getCurrentId());

        categoryMapper.update(category);
    }

    /**
     * 启用、禁用分类
     * @param status
     * @param id
     */
    public void startOrStop(Integer status, Long id) {
        Category category = Category.builder()
                .id(id)
                .status(status)
//                .updateTime(LocalDateTime.now())
//                .updateUser(BaseContext.getCurrentId())
                .build();
        categoryMapper.update(category);
    }

    /**
     * 根据类型查询分类
     * @param type
     * @return
     */
    public List<Category> list(Integer type, Long canteenId) {
        return categoryMapper.list(type, canteenId);
    }

    private void validateCanteen(Long canteenId) {
        if (canteenId == null) {
            throw new OrderBusinessException("请选择所属餐厅后再维护菜单");
        }
        Canteen canteen = canteenMapper.getById(canteenId);
        if (canteen == null || !Integer.valueOf(1).equals(canteen.getStatus())) {
            throw new OrderBusinessException("所属餐厅不存在或已停用");
        }
    }
}
