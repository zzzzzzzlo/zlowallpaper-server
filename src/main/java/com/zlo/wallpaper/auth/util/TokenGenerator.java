package com.zlo.wallpaper.auth.util;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 生成安全随机刷新令牌，并计算用于数据库查找的SHA-256哈希。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AuthServiceImpl.tokenGenerator。
 */
@Component
public class TokenGenerator {
    private final SecureRandom random = new SecureRandom();

    /**
     * 生成密码学安全的随机刷新令牌，只将明文交给客户端。
     * 调用/引用位置：AuthServiceImpl.login、AuthServiceImpl.refresh。
     * 输入参数：无。
     *
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 计算刷新令牌的SHA-256十六进制哈希，用于持久化与查找。
     * 调用/引用位置：AuthServiceImpl.createSession、AuthServiceImpl.lockSession。
     *
     * @param token 刷新令牌或访问令牌字符串，按当前方法用途处理且不得写入日志
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    public String hash(String token) {
    try {
            // SHA-256 用于高熵随机令牌；用户密码使用另一套 PBKDF2 算法。
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持SHA-256", exception);
        }
    }
}
