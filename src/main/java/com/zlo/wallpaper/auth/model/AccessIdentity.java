package com.zlo.wallpaper.auth.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 承接JWT验证得到的用户ID与会话ID；这些编号还必须与数据库会话核对。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@AllArgsConstructor
public class AccessIdentity {
    private long userId;
    private long sessionId;
}
