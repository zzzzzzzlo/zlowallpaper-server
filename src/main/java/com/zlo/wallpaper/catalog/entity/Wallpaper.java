package com.zlo.wallpaper.catalog.entity;

import com.zlo.wallpaper.catalog.model.UploadedFile;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 对应商品表及其当前原文件、封面的内嵌元数据，不保存历史商品版本。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：WallpaperPersistenceService.create。
 * 数据库映射位置：src/main/resources/mapper/catalog/AdminWallpaperMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class Wallpaper {
    private Long id;
    private String title;
    private String description;
    private Long categoryId;
    // 联表展示字段，不写入 wallpapers。
    private String categoryName;
    private String type;
    private Integer priceCents;
    private String currency;
    private String creator;
    private String publicationStatus;
    private LocalDateTime publishedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private UploadedFile original;
    private UploadedFile cover;
}
