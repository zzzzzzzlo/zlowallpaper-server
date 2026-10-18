package com.zlo.wallpaper.catalog.model;

/**
 * 承接启用分类查询的ID和名称两列。
 * 创建方式：调用方通过record构造器创建，编译器生成字段访问方法；Jackson用于序列化或反序列化。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public record CategoryRow(Long id, String name) { }
