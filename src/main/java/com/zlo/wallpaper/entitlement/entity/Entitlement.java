package com.zlo.wallpaper.entitlement.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 对应七列当前权益表；记录存在即有权益，退款删除，重新购买新增。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：OrderServiceImpl.grantEntitlement。
 * 数据库映射位置：src/main/resources/mapper/entitlement/EntitlementMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class Entitlement {
    private Long id;
    private Long userId;
    private Long wallpaperId;
    private Long sourceOrderId;
    private LocalDateTime grantedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
