package com.zlo.wallpaper.catalog.entity;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 对应categories表的分类实体，保存名称、排序、启用标志与时间。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：CategoryServiceImpl.createCategory、CategoryServiceImpl.updateCategory。
 * 数据库映射位置：src/main/resources/mapper/catalog/CategoryMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
public class Category {
    private Long id;
    private String name;
    private Integer sortOrder;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
