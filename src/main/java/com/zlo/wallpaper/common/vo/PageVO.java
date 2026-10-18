package com.zlo.wallpaper.common.vo;

import java.util.List;

/**
 * 封装分页items、页码、每页条数与总记录数，避免把分页插件对象直接返回。
 * 创建方式：调用方通过record构造器创建，编译器生成字段访问方法；Jackson用于序列化或反序列化。
 * 显式实例化位置：AdminWallpaperServiceImpl.getWallpapers、WallpaperServiceImpl.page。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public record PageVO<T>(List<T> items, int page, int pageSize, long total) { }
