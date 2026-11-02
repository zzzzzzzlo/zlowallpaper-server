package com.zlo.wallpaper.auth.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 修改当前账号的密码；用户ID来自登录上下文，不接收客户端指定的账号。 */
@Getter
@Setter
public class ChangePasswordDTO {
    @NotNull(message = "当前密码不能为空")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String currentPassword;

    @NotNull(message = "新密码不能为空")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String newPassword;

    // 与注册/登录保持一致：按Unicode字符计数，不trim密码，不接受纯空白。
    @AssertTrue(message = "当前密码须为8–128个字符，且不能全部为空白")
    public boolean isCurrentPasswordValid() {
        if (currentPassword == null) { return true; }
        int length = currentPassword.codePointCount(0, currentPassword.length());
        return length >= 8 && length <= 128 && !currentPassword.isBlank();
    }

    @AssertTrue(message = "新密码须为8–128个字符，且不能全部为空白")
    public boolean isNewPasswordValid() {
        if (newPassword == null) { return true; }
        int length = newPassword.codePointCount(0, newPassword.length());
        return length >= 8 && length <= 128 && !newPassword.isBlank();
    }
}
