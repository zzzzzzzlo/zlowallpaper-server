package com.zlo.wallpaper.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 承接刷新或登出的refreshToken字符串，不允许非字符串自动转换。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
public class RefreshTokenDTO {
    @NotBlank(message = "refreshToken不能为空")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    @Size(max = 512, message = "refreshToken过长")
    private String refreshToken;
}
