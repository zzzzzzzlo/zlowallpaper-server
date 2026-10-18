package com.zlo.wallpaper.catalog.model;

import java.math.BigDecimal;

/**
 * 承接公开商品或本账号已购查询的展示字段和当前原文件元数据，不包含内部文件位置。
 * 创建方式：调用方通过record构造器创建，编译器生成字段访问方法；Jackson用于序列化或反序列化。
 * 数据库映射位置：src/main/resources/mapper/catalog/WallpaperMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public record WallpaperRow(
        Long id, String title, String description, Long categoryId,
        String categoryName, String type, Integer priceCents, String currency,
        Integer width, Integer height, BigDecimal fps,
        String sha256, Long sizeBytes, String creator) { }
