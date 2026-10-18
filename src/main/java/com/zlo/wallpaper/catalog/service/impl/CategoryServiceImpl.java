package com.zlo.wallpaper.catalog.service.impl;

import com.zlo.wallpaper.catalog.mapper.CategoryMapper;
import com.zlo.wallpaper.catalog.service.CategoryService;
import com.zlo.wallpaper.catalog.vo.CategoryVO;
import com.zlo.wallpaper.catalog.dto.CategoryCreateDTO;
import com.zlo.wallpaper.catalog.dto.CategoryUpdateDTO;
import com.zlo.wallpaper.catalog.entity.Category;
import com.zlo.wallpaper.catalog.model.AdminCategoryRow;
import com.zlo.wallpaper.catalog.service.CatalogIds;
import com.zlo.wallpaper.catalog.util.CategoryNames;
import com.zlo.wallpaper.catalog.vo.AdminCategoryVO;
import com.zlo.wallpaper.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 实现分类名称、引用保护、排序启停和并发修改处理。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AdminCategoryController.categoryService、AdminCategoryIntegrationTest.categoryService、CategoryController的构造器（按CategoryService接口）。
 */
@Service
public class CategoryServiceImpl implements CategoryService {
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private Clock clock;

    /**
     * 读取启用分类并转换为公开分类列表。
     * 调用/引用位置：CategoryController.getCategories。
     * 输入参数：无。
     *
     * @return 按排序值及ID排列的启用分类ID/名称列表，空库时为空列表
     */
    @Override
    public List<CategoryVO> listEnabled() {
        return categoryMapper.selectEnabled().stream()
                .map(row -> new CategoryVO(row.id().toString(), row.name())).toList();
    }

    /**
     * 读取完整管理分类列表，包括停用分类和所有商品状态的引用数。
     * 调用/引用位置：AdminCategoryController.getAdminCategories。
     * 输入参数：无。
     *
     * @return 包含停用分类和所有商品引用数的完整管理分类列表
     */
    @Override
    public List<AdminCategoryVO> getAdminCategories() {
        List<AdminCategoryRow> rows = categoryMapper.selectAdminCategories();
        List<AdminCategoryVO> result = new ArrayList<>();
        for (AdminCategoryRow row : rows) { result.add(toAdminVO(row)); }
        return result;
    }

    /**
     * 校验名称及默认值，创建分类并返回完整管理视图。
     * 调用/引用位置：AdminCategoryController.createCategory、AdminCategoryIntegrationTest.createTogether。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 分类ID、名称、排序、启用标志和实时商品引用数
     */
    @Override
    @Transactional
    public AdminCategoryVO createCategory(CategoryCreateDTO dto) {
        String name = CategoryNames.normalize(dto.getName());
        checkNameAvailable(name, null);
        Category category = new Category();
        category.setName(name);
        category.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        category.setEnabled(dto.getEnabled() == null ? true : dto.getEnabled());
        category.setCreatedAt(now());
        category.setUpdatedAt(category.getCreatedAt());
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException exception) {
            // 先查再插无法独自防住并发重名，最终依靠数据库唯一约束。
            throw nameExists();
        }
        return toAdminVO(categoryMapper.selectAdminById(category.getId()));
    }

    /**
     * 仅更新实际提交的分类字段，保留省略值并校验名称冲突。
     * 调用/引用位置：AdminCategoryController.updateCategory、AdminCategoryIntegrationTest.concurrentPatchesPreserveBothIndependentChanges。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 分类ID、名称、排序、启用标志和实时商品引用数
     */
    @Override
    @Transactional
    public AdminCategoryVO updateCategory(String id, CategoryUpdateDTO dto) {
        Category existing = getCategoryForUpdate(id);
        Category changes = new Category();
        changes.setId(existing.getId());
        if (dto.getName() != null) {
            String name = CategoryNames.normalize(dto.getName());
            checkNameAvailable(name, existing.getId());
            changes.setName(name);
        }
        // 不填省略字段，XML仅更新非null字段；false和0均是合法修改值。
        changes.setSortOrder(dto.getSortOrder());
        changes.setEnabled(dto.getEnabled());
        changes.setUpdatedAt(now());
        try {
            categoryMapper.update(changes);
        } catch (DuplicateKeyException exception) {
            throw nameExists();
        }
        return toAdminVO(categoryMapper.selectAdminById(existing.getId()));
    }

    /**
     * 删除未被任何商品引用的分类，已引用分类返回冲突。
     * 调用/引用位置：AdminCategoryController.deleteCategory。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     */
    @Override
    @Transactional
    public void deleteCategory(String id) {
        Category category = getCategoryForUpdate(id);
        if (categoryMapper.countWallpapers(category.getId()) > 0) { throw categoryInUse(); }
        try {
            if (categoryMapper.deleteById(category.getId()) != 1) { throw notFound(); }
        } catch (DataIntegrityViolationException exception) {
            // 外键是最后一道引用保护，不能级联删除商品，也不能自动转成停用。
            throw categoryInUse();
        }
    }

    /**
     * 转换分类ID后取得事务行锁，不存在时返回分类业务错误。
     * 调用/引用位置：CategoryServiceImpl.deleteCategory、CategoryServiceImpl.updateCategory。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 上述处理得到的Category结果；业务校验失败时通过异常返回错误
     */
    private Category getCategoryForUpdate(String id) {
        CatalogIds.validatePathId(id);
        Long databaseId = CatalogIds.findDatabaseId(id);
        if (databaseId == null) { throw notFound(); }
        // 修改/删除同一分类串行处理，并配合外键阻止删除期间插入新引用。
        Category category = categoryMapper.selectByIdForUpdate(databaseId);
        if (category == null) { throw notFound(); }
        return category;
    }

    /**
     * 核对名称是否被其他分类使用，编辑本分类同名时允许保留。
     * 调用/引用位置：CategoryServiceImpl.createCategory、CategoryServiceImpl.updateCategory。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param name 分类名称或未定义JSON字段名
     * @param excludeId 编辑时排除本分类的ID；新增传null，避免把自己原名称判为重名
     */
    private void checkNameAvailable(String name, Long excludeId) {
        if (categoryMapper.selectByName(name, excludeId) != null) { throw nameExists(); }
    }

    /**
     * 将分类及商品引用数查询结果转换成管理员响应。
     * 调用/引用位置：CategoryServiceImpl.createCategory、CategoryServiceImpl.getAdminCategories、CategoryServiceImpl.updateCategory。
     *
     * @param row Mapper返回的分类或商品查询行，供当前方法转换为前端VO
     * @return 分类ID、名称、排序、启用标志和实时商品引用数
     */
    private AdminCategoryVO toAdminVO(AdminCategoryRow row) {
        AdminCategoryVO vo = new AdminCategoryVO();
        vo.setId(row.getId().toString());
        vo.setName(row.getName());
        vo.setSortOrder(row.getSortOrder());
        vo.setEnabled(row.getEnabled());
        vo.setWallpaperCount(row.getWallpaperCount());
        return vo;
    }

    /**
     * 读取当前UTC时间并与数据库毫秒时间精度保持一致。
     * 调用/引用位置：CategoryServiceImpl.createCategory、CategoryServiceImpl.updateCategory。
     * 输入参数：无。
     *
     * @return 当前UTC时间，精度与数据库的毫秒字段一致
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    /**
     * 构造分类名称已存在的409业务异常。
     * 调用/引用位置：CategoryServiceImpl.checkNameAvailable、CategoryServiceImpl.createCategory、CategoryServiceImpl.updateCategory。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException nameExists() {
        return new BusinessException(HttpStatus.CONFLICT, "CATEGORY_NAME_EXISTS", "分类名称已存在");
    }

    /**
     * 构造分类仍有商品引用、不能删除的409业务异常。
     * 调用/引用位置：CategoryServiceImpl.deleteCategory。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException categoryInUse() {
        return new BusinessException(HttpStatus.CONFLICT, "CATEGORY_IN_USE", "分类仍被商品引用，不能删除");
    }

    /**
     * 构造当前请求资源不存在的404业务错误。
     * 调用/引用位置：CategoryServiceImpl.deleteCategory、CategoryServiceImpl.getCategoryForUpdate。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "分类不存在");
    }
}
