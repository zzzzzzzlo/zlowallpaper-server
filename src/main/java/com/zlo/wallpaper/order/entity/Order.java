package com.zlo.wallpaper.order.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 对应orders表，保存成交快照和支付/取消/退款时间，不写入数据库生成列。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：OrderServiceImpl.createOrder。
 * 数据库映射位置：src/main/resources/mapper/order/OrderMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class Order {
    private Long id;
    private Long userId;
    private Long wallpaperId;
    private String titleSnapshot;
    private Integer amountCents;
    private String currency;
    private String status;
    private String paymentMethod;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime canceledAt;
    private LocalDateTime refundedAt;
    private LocalDateTime updatedAt;
}
