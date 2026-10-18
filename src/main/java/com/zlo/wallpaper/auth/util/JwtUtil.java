package com.zlo.wallpaper.auth.util;

import com.zlo.wallpaper.auth.model.AccessIdentity;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * 封装JWT签名和验证，校验发行者、令牌用途、算法和截止时间。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AuthIntegrationTest.jwtUtil、AuthServiceImpl.jwtUtil。
 */
@Component
public class JwtUtil {
    private final SecretKey key;
    private final Clock clock;

    /**
     * 构造JwtUtil并保存其依赖或初始属性。
     * 框架调用位置：Spring创建当前组件时调用。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     * @param properties Spring绑定并校验后的认证配置
     * @param clock UTC时钟依赖，可由测试替换以控制时间
     */
    public JwtUtil(AuthProperties properties, Clock clock) {
        this.clock = clock;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(properties.getJwtSecret());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AUTH_JWT_SECRET必须是Base64格式", exception);
        }
        if (bytes.length < 32) { throw new IllegalStateException("JWT签名密钥至少需要32个随机字节"); }
        key = Keys.hmacShaKeyFor(bytes);
    }

    /**
     * 把可信用户ID、会话ID、签发时间及有效秒数签名为访问JWT。
     * 调用/引用位置：AuthIntegrationTest.accessTokenCannotClaimAnotherUsersSession、AuthIntegrationTest.expiredAccessCanRefreshAndOldRefreshCanLogoutTheWholeFamily、AuthIntegrationTest.expiredSessionAndJwtAndForgedTokenAreRejected、AuthServiceImpl.sessionVO。
     *
     * @param userId 当前可信用户的数据库ID
     * @param sessionId JWT或数据库记录所对应的会话ID
     * @param now 本次操作统一使用的UTC时间
     * @param seconds 访问令牌的实际有效秒数
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    public String createAccessToken(long userId, long sessionId, Instant now, int seconds) {
        return Jwts.builder().issuer("zlowallpaper").subject(Long.toString(userId))
                .claim("sid", Long.toString(sessionId)).claim("kind", "access")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(seconds)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    /**
     * 验证JWT签名与标准声明，解析用户和会话身份，失败时拒绝认证。
     * 调用/引用位置：AuthServiceImpl.authenticate。
     *
     * @param token 刷新令牌或访问令牌字符串，按当前方法用途处理且不得写入日志
     * @return 经过JWT声明验证的用户ID与会话ID，仍须进一步核对数据库状态
     */
    public AccessIdentity parseAccessToken(String token) {
        try {
   Jws<Claims> jwt = Jwts.parser().verifyWith(key).requireIssuer("zlowallpaper")
                    .require("kind", "access").clock(() -> Date.from(clock.instant()))
                    .build().parseSignedClaims(token);
            if (!"HS256".equals(jwt.getHeader().getAlgorithm()) || jwt.getPayload().getExpiration() == null) {
                throw expired();
            }
            long userId = Long.parseLong(jwt.getPayload().getSubject());
            long sessionId = Long.parseLong(jwt.getPayload().get("sid", String.class));
            if (userId <= 0 || sessionId <= 0) { throw expired(); }
            return new AccessIdentity(userId, sessionId);
        } catch (JwtException | IllegalArgumentException exception) {
            throw expired();
        }
    }

    /**
     * 构造登录或令牌已失效的401业务异常。
     * 调用/引用位置：JwtUtil.parseAccessToken。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException expired() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录已失效，请重新登录");
    }
}
