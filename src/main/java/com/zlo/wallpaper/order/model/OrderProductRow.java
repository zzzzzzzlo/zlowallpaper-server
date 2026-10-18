package com.zlo.wallpaper.order.model;

import lombok.Data;

/**
 * 承接下单所需的商品ID、标题、价格、币种和发布状态五列。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 数据库映射位置：src/main/resources/mapper/order/OrderMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class OrderProductRow {
    private Long id;
    private String title;
    private Integer priceCents;
    private String currency;
    private String publicationStatus;
}
