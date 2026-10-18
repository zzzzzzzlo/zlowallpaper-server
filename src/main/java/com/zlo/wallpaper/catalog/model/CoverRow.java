package com.zlo.wallpaper.catalog.model;

/**
 * 承接封面读取所需的存储位置、类型和大小，不接收客户端指定的服务器路径。
 * 创建方式：调用方通过record构造器创建，编译器生成字段访问方法；Jackson用于序列化或反序列化。
 * 数据库映射位置：src/main/resources/mapper/catalog/WallpaperMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public record CoverRow(Long id, String storageProvider, String bucket, String objectKey,
                       String mimeType, Long sizeBytes) { }
