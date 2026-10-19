package com.zlo.wallpaper.catalog;

import com.zlo.wallpaper.catalog.service.CoverCacheService;
import com.zlo.wallpaper.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** JUnit实例化，验证缓存读写、故障降级及二进制序列化；不连接真实Redis或COS。 */
@ExtendWith(MockitoExtension.class)
class CoverCacheServiceTest {
    @Mock private RedisTemplate<String, byte[]> template;
    @Mock private ValueOperations<String, byte[]> values;
    @InjectMocks private CoverCacheService cache;

    /** 各用例前模拟Spring注入的开关和TTL，避免测试依赖本机配置。 */
    @BeforeEach
    void settings() {
        ReflectionTestUtils.setField(cache, "enabled", true);
        ReflectionTestUtils.setField(cache, "ttlSeconds", 21600L);
    }

    /** 二进制图片不经过JSON或Base64，一次SET同时写入内容和过期时间。 */
    @Test
    void readsAndWritesImageBytesWithTtl() {
        byte[] bytes = {0, (byte) 255, 7};
        when(template.opsForValue()).thenReturn(values);
        when(values.get("cover:test")).thenReturn(bytes);
        assertArrayEquals(bytes, cache.getCover("cover:test"));
        cache.putCover("cover:test", bytes);
        verify(values).set("cover:test", bytes, Duration.ofHours(6));
    }

    /** 缓存读取失败只表现为未命中，交给CoverService继续读取云文件。 */
    @Test
    void failedGetIsACacheMiss() {
        when(template.opsForValue()).thenReturn(values);
        when(values.get("cover:test")).thenThrow(new RedisConnectionFailureException("offline"));
        assertNull(cache.getCover("cover:test"));
    }

    /** 缓存写失败不能覆盖已经成功读出的封面结果。 */
    @Test
    void failedSetDoesNotFailTheRequest() {
        byte[] bytes = {1};
        when(template.opsForValue()).thenReturn(values);
        doThrow(new RedisConnectionFailureException("offline")).when(values).set("cover:test", bytes, Duration.ofHours(6));
        assertDoesNotThrow(() -> cache.putCover("cover:test", bytes));
    }

    /** 测试/运维关闭缓存时，不连接Redis。 */
    @Test
    void disabledCacheDoesNotAccessRedis() {
        ReflectionTestUtils.setField(cache, "enabled", false);
        assertNull(cache.getCover("cover:test"));
        cache.putCover("cover:test", new byte[]{1});
        verifyNoInteractions(template);
    }

    /** Spring配置显式采用字符串key及原始图片byte[]，防止默认Java序列化头混入缓存。 */
    @Test
    void templateStoresRawImageBytes() {
        RedisTemplate<String, byte[]> actual = new RedisConfig().coverRedisTemplate(mock(RedisConnectionFactory.class));
        byte[] bytes = {0, (byte) 255, 1};
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.serializer.RedisSerializer<byte[]> serializer =
                (org.springframework.data.redis.serializer.RedisSerializer<byte[]>) actual.getValueSerializer();
        assertArrayEquals(bytes, serializer.serialize(bytes));
        assertArrayEquals("cover:test".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ((org.springframework.data.redis.serializer.RedisSerializer<String>) actual.getKeySerializer()).serialize("cover:test"));
    }
}
