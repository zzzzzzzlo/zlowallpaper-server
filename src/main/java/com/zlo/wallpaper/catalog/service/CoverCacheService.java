package com.zlo.wallpaper.catalog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

/**
 * 只负责Redis封面缓存读写，由Spring创建并注入CoverService；不查询权限、不访问COS。
 * 缓存不可用按未命中处理，云文件读取和返回顺序仍集中在CoverService.readFile。
 */
@Service
public class CoverCacheService {
    private static final Logger log = LoggerFactory.getLogger(CoverCacheService.class);
    @Autowired private RedisTemplate<String, byte[]> coverRedisTemplate;
    @Value("${wallpaper.cover-cache.enabled:true}") private boolean enabled;
    @Value("${wallpaper.cover-cache.ttl-seconds:21600}") private long ttlSeconds;

    /**
     * CoverService在完成商品/权益查询后调用，Redis失败不影响后续云读取。
     * @param key 由可信存储服务、桶名、对象键组成的缓存键
     * @return 命中的图片字节；未命中、关闭或Redis不可用时返回null
     */
    public byte[] getCover(String key) {
        if (!enabled) { return null; }
        try {
            return coverRedisTemplate.opsForValue().get(key);
        } catch (DataAccessException exception) {
            log.debug("Redis封面缓存读取失败，改为从云存储读取");
            return null;
        }
    }

    /**
     * CoverService成功读取云文件后调用；一次SET同时写内容和TTL，失败仍返回原图片。
     * @param key 当前文件对应的缓存键，替换封面产生新对象键，不覆盖旧文件缓存
     * @param bytes 已经通过大小检查的图片字节
     * 返回：无；缓存只用于加速，不能改变商品接口的成功或失败结果。
     */
    public void putCover(String key, byte[] bytes) {
        if (!enabled) { return; }
        try {
            coverRedisTemplate.opsForValue().set(key, bytes, Duration.ofSeconds(ttlSeconds));
        } catch (DataAccessException exception) {
            log.debug("Redis封面缓存写入失败，本次仍返回云存储图片");
        }
    }
}
