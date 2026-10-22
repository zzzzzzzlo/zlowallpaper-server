package com.zlo.wallpaper.config;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 承接ffprobe、ffmpeg路径、媒体检查超时及封面压缩尺寸/质量参数。
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
    @Min(128) @Max(2048) private int coverMaxEdge = 1920;
    // FFmpeg JPEG q:v 范围为 2–31，数值越小画质越高；不是百分比。
    @Min(2) @Max(31) private int coverJpegQuality = 2;
}
