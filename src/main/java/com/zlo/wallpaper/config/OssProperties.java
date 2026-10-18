package com.zlo.wallpaper.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 承接OSS服务地址、区域、Bucket及访问凭证，不作为接口返回对象。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：OssStorage.properties。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "wallpaper.oss")
public class OssProperties {
    private String endpoint;
    private String region;
    private String bucket;
    private String accessKeyId;
    private String accessKeySecret;
}
