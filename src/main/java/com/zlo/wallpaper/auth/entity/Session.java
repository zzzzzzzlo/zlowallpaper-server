package com.zlo.wallpaper.auth.entity;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 对应sessions数据库记录，保存令牌哈希、轮换链、撤销信息和固定截止时间。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：AuthServiceImpl.createSession。
 * 数据库映射位置：src/main/resources/mapper/auth/SessionMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
public class Session {
    private Long id;
    private Long userId;
    private String familyId;
    private String refreshTokenHash;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private String revokeReason;
    private Long replacedById;
}
