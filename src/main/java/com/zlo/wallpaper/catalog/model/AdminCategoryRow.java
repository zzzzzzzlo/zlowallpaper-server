package com.zlo.wallpaper.catalog.model;

import com.zlo.wallpaper.catalog.entity.Category;
import lombok.Getter;
import lombok.Setter;

/**
 * 承接管理分类查询结果，包含动态计算的商品引用数而不新增分类表字段。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 数据库映射位置：src/main/resources/mapper/catalog/CategoryMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
public class AdminCategoryRow extends Category {
    private Long wallpaperCount;
}
