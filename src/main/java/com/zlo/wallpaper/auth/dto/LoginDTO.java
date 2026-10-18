package com.zlo.wallpaper.auth.dto;

/**
 * 承接登录JSON，继承账号密码输入规则。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public class LoginDTO extends CredentialsDTO {
}
