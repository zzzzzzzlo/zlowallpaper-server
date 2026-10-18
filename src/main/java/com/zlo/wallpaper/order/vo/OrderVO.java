package com.zlo.wallpaper.order.vo;

import lombok.Data;
import java.time.OffsetDateTime;

/**
 * 返回订单快照及所属用户ID、查询时的当前余额；余额用于确认付款，不是订单历史余额。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：OrderServiceImpl.toOrderVO。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class OrderVO {
    private String id;
    private String userId;
    private String balanceCents;
    private String wallpaperId;
    private String title;
    private Integer amountCents;
    private String currency;
    private String status;
    private OffsetDateTime createdAt;
}
