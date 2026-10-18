package com.zlo.wallpaper.catalog.vo;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 返回管理员可见的商品资料、当前文件显示名和受保护封面URL。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：AdminWallpaperServiceImpl.toVO。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class AdminWallpaperVO {
    private String id;
    private String title;
    private String description;
    private String categoryId;
    private String categoryName;
    private String type;
    private Integer priceCents;
    private String currency;
    private String coverUrl;
    private Integer width;
    private Integer height;
    private BigDecimal fps;
    private String sha256;
    private String sizeBytes;
    private String creator;
    private String publicationStatus;
    private String originalFilename;
    private String coverFilename;
}
