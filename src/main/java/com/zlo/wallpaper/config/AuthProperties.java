package com.zlo.wallpaper.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定认证配置，校验签名密钥、默认8天访问期限及会话保留参数。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AuthServiceImpl.properties、JwtUtil的构造器、SessionCleanupTask.properties。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "wallpaper.auth")
public class AuthProperties {
    @NotBlank
    private String jwtSecret;
    // 8天 = 8 × 24 × 60 × 60秒；与前端期限校验及OpenAPI上限保持一致。
    @Min(60) @Max(691200)
    private int accessTokenSeconds = 691200;
    @Min(1) @Max(90)
    private int sessionDays = 30;
    @Min(1) @Max(30)
    private int retentionDays = 7;
}
