package com.zlo.wallpaper.catalog.service;

import com.zlo.wallpaper.catalog.vo.CategoryVO;
import com.zlo.wallpaper.catalog.vo.AdminCategoryVO;
import com.zlo.wallpaper.catalog.dto.CategoryCreateDTO;
import com.zlo.wallpaper.catalog.dto.CategoryUpdateDTO;
import java.util.List;

/**
 * 声明公开分类及管理员分类管理业务能力。
 * 创建方式：接口本身不实例化，由对应@Service实现提供注入对象。
 * 注入位置：AdminCategoryController.categoryService、AdminCategoryIntegrationTest.categoryService、CategoryController的构造器。
 */
public interface CategoryService {
    /**
     * 读取启用分类并转换为公开分类列表。
     * 调用/引用位置：CategoryController.getCategories。
     * 输入参数：无。
     *
     * @return 按排序值及ID排列的启用分类ID/名称列表，空库时为空列表
     */
    List<CategoryVO> listEnabled();
    /**
     * 读取完整管理分类列表，包括停用分类和所有商品状态的引用数。
     * 调用/引用位置：AdminCategoryController.getAdminCategories。
     * 输入参数：无。
     *
     * @return 包含停用分类和所有商品引用数的完整管理分类列表
     */
    List<AdminCategoryVO> getAdminCategories();
    /**
     * 校验名称及默认值，创建分类并返回完整管理视图。
     * 调用/引用位置：AdminCategoryController.createCategory、AdminCategoryIntegrationTest.createTogether。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 分类ID、名称、排序、启用标志和实时商品引用数
     */
    AdminCategoryVO createCategory(CategoryCreateDTO dto);
    /**
     * 仅更新实际提交的分类字段，保留省略值并校验名称冲突。
     * 调用/引用位置：AdminCategoryController.updateCategory、AdminCategoryIntegrationTest.concurrentPatchesPreserveBothIndependentChanges。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 分类ID、名称、排序、启用标志和实时商品引用数
     */
    AdminCategoryVO updateCategory(String id, CategoryUpdateDTO dto);
    /**
     * 删除未被任何商品引用的分类，已引用分类返回冲突。
     * 调用/引用位置：AdminCategoryController.deleteCategory。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     */
    void deleteCategory(String id);
}
