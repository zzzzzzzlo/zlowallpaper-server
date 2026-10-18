package com.zlo.wallpaper.catalog.vo;

import java.math.BigDecimal;

/**
 * 返回公开商品展示信息和文件校验元数据，不暴露原文件存储位置。
 * 创建方式：调用方通过record构造器创建，编译器生成字段访问方法；Jackson用于序列化或反序列化。
 * 显式实例化位置：WallpaperServiceImpl.toVO。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public record WallpaperVO(
        String id, String title, String description,
        String categoryId, String categoryName, String type,
        int priceCents, String currency, String coverUrl,
        int width, int height, BigDecimal fps,
        String sha256, String sizeBytes, String creator) { }
