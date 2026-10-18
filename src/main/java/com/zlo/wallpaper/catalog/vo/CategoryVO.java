package com.zlo.wallpaper.catalog.vo;

/**
 * 返回公开分类的字符串ID和名称。
 * 创建方式：调用方通过record构造器创建，编译器生成字段访问方法；Jackson用于序列化或反序列化。
 * 显式实例化位置：CategoryServiceImpl.listEnabled。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public record CategoryVO(String id, String name) { }
