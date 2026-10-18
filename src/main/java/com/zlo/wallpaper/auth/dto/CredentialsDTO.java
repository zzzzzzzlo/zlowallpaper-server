package com.zlo.wallpaper.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 定义用户名、密码的公共输入及密码校验，供登录和注册DTO继承。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
public class CredentialsDTO {
    @NotNull(message = "用户名不能为空")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    @Pattern(regexp = "[A-Za-z0-9._-]{3,32}", message = "用户名须为3–32位字母、数字、点、下划线或短横线")
    private String username;
    @NotNull(message = "密码不能为空")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String password;

    /**
     * 按Unicode字符长度、空白规则验证密码，不修改用户输入的密码。
     * 框架调用位置：Bean Validation在校验DTO时调用，方法不作为请求业务字段。
     * 输入参数：无。
     *
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    @AssertTrue(message = "密码须为8–128个字符，且不能全部为空白")
    public boolean isPasswordValid() {
        if (password == null) { return true; } // 空值交给 @NotNull。
        int length = password.codePointCount(0, password.length());
        return length >= 8 && length <= 128 && !password.isBlank();
    }
}
