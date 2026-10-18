package com.zlo.wallpaper.catalog.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 返回管理分类字段及实时商品引用数。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：CategoryServiceImpl.toAdminVO。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
public class AdminCategoryVO {
    private String id;
    private String name;
    private Integer sortOrder;
    private Boolean enabled;
    private Long wallpaperCount;
}
