package com.zlo.wallpaper.auth.entity;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 对应users数据库记录；密码哈希仅用于认证，不作为接口响应直接返回。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：AuthServiceImpl.register。
 * 数据库映射位置：src/main/resources/mapper/auth/UserMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
public class User {
    private Long id;
    private String username;
    private String passwordHash;
    private String role;
    private String status;
    // 壁纸币余额的最小单位：100单位=1币；使用整数避免浮点误差。
    private Long balanceCents;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
