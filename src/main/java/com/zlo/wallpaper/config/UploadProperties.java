package com.zlo.wallpaper.config;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 承接ffprobe、ffmpeg路径和媒体检查超时参数。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：MediaInspector.properties。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "wallpaper.upload")
public class UploadProperties {
    @NotBlank private String ffprobe = "ffprobe";
    @NotBlank private String ffmpeg = "ffmpeg";
    @Min(1) @Max(60) private int mediaTimeoutSeconds = 20;
}
