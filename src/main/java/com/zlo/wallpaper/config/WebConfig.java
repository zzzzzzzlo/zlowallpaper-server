package com.zlo.wallpaper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import java.util.List;

/**
 * 注册先于认证执行的CORS过滤器，允许具体管理端来源访问并读取请求标识。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@Configuration
public class WebConfig {
    /**
     * 构建允许指定来源、方法和请求头的CORS过滤器，并注册在认证之前。
     * 框架调用位置：Spring配置处理器在创建容器Bean时调用。
     *
     * @param origins 服务器配置允许的CORS来源列表
     * @return 配置了允许来源及请求方法的过滤器注册对象，顺序早于AuthFilter
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(
            @Value("${wallpaper.cors.allowed-origins}") String origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        for (String origin : origins.split(",")) {
            if (!origin.isBlank()) { configuration.addAllowedOrigin(origin.strip()); }
        }
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        // 在认证之前执行，让401/403响应也带上跨域响应头。
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
