package com.zlo.wallpaper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 配置封面缓存的Redis读写格式，由Spring创建；模板注入CoverCacheService。
 * key使用字符串，value直接保存图片byte[]，不使用Java对象序列化或Base64。
 */
@Configuration
public class RedisConfig {
    /**
     * Spring创建Bean时调用，连接由Boot配置的LettuceConnectionFactory提供。
     * @param connectionFactory 读取spring.data.redis配置的连接工厂
     * @return 专门存放封面字节的Redis模板
     */
    @Bean
    public RedisTemplate<String, byte[]> coverRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.byteArray());
        return template;
    }
}
