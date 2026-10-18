package com.zlo.wallpaper.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 返回访问令牌、刷新令牌、剩余秒数和公开用户资料。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
@AllArgsConstructor
public class SessionVO {
    private String accessToken;
    private String refreshToken;
    private Integer expiresIn;
    private UserVO user;
}
