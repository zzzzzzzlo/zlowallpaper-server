package com.zlo.wallpaper.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册OSS与媒体上传配置对象；保留名称以兼容已完成的存储配置。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@Configuration
@EnableConfigurationProperties({OssProperties.class, CosProperties.class, UploadProperties.class})
public class AssetConfig { }
