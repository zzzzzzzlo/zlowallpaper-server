package com.zlo.wallpaper.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 腾讯云普通COS的地域、完整存储桶名和服务端凭据；由AssetConfig注册并注入CosStorage。
 * 真实凭据只从本机.env或部署环境读取，不作为接口返回对象。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "wallpaper.cos")
public class CosProperties {
    private String region;
    private String bucket;
    private String secretId;
    private String secretKey;
}
