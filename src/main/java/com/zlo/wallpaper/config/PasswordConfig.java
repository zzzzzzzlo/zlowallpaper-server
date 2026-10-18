package com.zlo.wallpaper.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import java.time.Clock;

/**
 * 注册密码编码器、UTC时钟以及认证配置绑定。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AuthProperties.class)
public class PasswordConfig {
    /**
     * 创建并注册带预设参数的密码哈希编码器，供注册和登录注入使用。
     * 框架调用位置：Spring配置处理器在创建容器Bean时调用。
     * 输入参数：无。
     *
     * @return 用于注册encode与登录matches的Spring密码编码器组件
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // 成熟的密码哈希组件，包含随机盐，支持契约中的长密码。
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * 提供UTC系统时钟，供认证时间比较、JWT验证及会话清理共用。
     * 框架调用位置：Spring配置处理器在创建容器Bean时调用。
     * 输入参数：无。
     *
     * @return 基于UTC的系统时钟Bean，注入认证与会话清理组件
     */
    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }
}
