package com.zlo.wallpaper.catalog.mapper;

import com.zlo.wallpaper.catalog.model.CategoryRow;
import com.zlo.wallpaper.catalog.entity.Category;
import com.zlo.wallpaper.catalog.model.AdminCategoryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 声明公开及管理分类查询、分类写入和商品引用统计SQL。
 * 创建方式：MyBatis扫描@Mapper后生成代理，SQL实现在同模块Mapper XML中。
 * 注入位置：AdminCategoryMySqlIT.categoryMapper、CatalogMySqlIT.categoryMapper、CategoryServiceImpl.categoryMapper、WallpaperPersistenceService.categoryMapper。
 * 数据库映射位置：src/main/resources/mapper/catalog/CategoryMapper.xml。
 */
@Mapper
public interface CategoryMapper {
    /**
     * 查询启用分类，按排序值和ID稳定排序。
     * 调用/引用位置：CatalogMySqlIT.mysqlPaginationJoinsCurrentFileAndKeepsDisabledCategoryProducts、CategoryServiceImpl.listEnabled。
     * 输入参数：无。
     *
     * @return 匹配条件的结果列表，没有记录时为空列表
     */
    @Select("SELECT id, name FROM categories WHERE enabled = 1 ORDER BY sort_order ASC, id ASC")
    List<CategoryRow> selectEnabled();

    /**
     * 查询完整分类列表，并动态统计所有商品状态的引用数。
     * 调用/引用位置：AdminCategoryMySqlIT.adminJoinReturnsDisabledZeroCountAndCompleteReferenceCount、CategoryServiceImpl.getAdminCategories。
     * 输入参数：无。
     *
     * @return 匹配条件的结果列表，没有记录时为空列表
     */
    List<AdminCategoryRow> selectAdminCategories();
    /**
     * 按分类ID读取包含商品引用数的管理视图。
     * 调用/引用位置：AdminCategoryMySqlIT.adminJoinReturnsDisabledZeroCountAndCompleteReferenceCount、AdminCategoryMySqlIT.dynamicUpdateKeepsMissingFieldsAndWritesFalseAndZero、AdminCategoryMySqlIT.realForeignKeyRejectsReferencedDeletionAndAllowsUnusedDeletion、AdminCategoryMySqlIT.utf8mb4ColumnStores64SupplementaryUnicodeCharacters、CategoryServiceImpl.createCategory、CategoryServiceImpl.updateCategory。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    AdminCategoryRow selectAdminById(@Param("id") long id);
    /**
     * 按主键执行事务内锁定读取，防止并发修改基于旧状态。
     * 调用/引用位置：AdminCategoryMySqlIT.dynamicUpdateKeepsMissingFieldsAndWritesFalseAndZero、CategoryServiceImpl.getCategoryForUpdate、WallpaperPersistenceService.checkCategory。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Category selectByIdForUpdate(@Param("id") long id);
    /**
     * 按主键读取数据库记录；不存在时返回null。
     * 调用/引用位置：WallpaperPersistenceService.checkCategory。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Category selectById(@Param("id") long id);
    /**
     * 按归一化名称查找分类，包含停用分类以防重复命名。
     * 调用/引用位置：AdminCategoryMySqlIT.duplicateNameLookupAndUniqueKeyIncludeDisabledRows、CategoryServiceImpl.checkNameAvailable。
     *
     * @param name 分类名称或未定义JSON字段名
     * @param excludeId 编辑时排除本分类的ID；新增传null，避免把自己原名称判为重名
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Category selectByName(@Param("name") String name, @Param("excludeId") Long excludeId);
    /**
     * 统计引用当前分类的所有商品，不局限于已上架状态。
     * 调用/引用位置：AdminCategoryMySqlIT.realForeignKeyRejectsReferencedDeletionAndAllowsUnusedDeletion、CategoryServiceImpl.deleteCategory。
     *
     * @param categoryId 分类数据库ID或外部分类筛选ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    long countWallpapers(@Param("categoryId") long categoryId);
    /**
     * 把实体写入对应数据库表，并在有自增主键时回填id。
     * 调用/引用位置：CategoryServiceImpl.createCategory。
     *
     * @param category 当前分类实体
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int insert(Category category);
    /**
     * 更新实体的允许字段或指定文件列，保留不应覆盖的省略字段。
     * 调用/引用位置：AdminCategoryMySqlIT.duplicateNameLookupAndUniqueKeyIncludeDisabledRows、AdminCategoryMySqlIT.dynamicUpdateKeepsMissingFieldsAndWritesFalseAndZero、AdminCategoryMySqlIT.utf8mb4ColumnStores64SupplementaryUnicodeCharacters、CategoryServiceImpl.updateCategory。
     *
     * @param category 当前分类实体
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int update(Category category);
    /**
     * 删除指定分类，外键继续阻止有商品引用的删除。
     * 调用/引用位置：AdminCategoryMySqlIT.realForeignKeyRejectsReferencedDeletionAndAllowsUnusedDeletion、CategoryServiceImpl.deleteCategory。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int deleteById(@Param("id") long id);
}
