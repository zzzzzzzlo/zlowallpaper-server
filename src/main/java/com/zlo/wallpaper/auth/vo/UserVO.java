package com.zlo.wallpaper.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 返回用户资料及壁纸币余额；余额用字符串表示整数最小单位，避免前端大整数精度丢失。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
@AllArgsConstructor
public class UserVO {
    private String id;
    private String username;
    private String balanceCents;
}
