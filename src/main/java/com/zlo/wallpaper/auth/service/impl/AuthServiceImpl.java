package com.zlo.wallpaper.auth.service.impl;

import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.entity.Session;
import com.zlo.wallpaper.auth.entity.User;
import com.zlo.wallpaper.auth.mapper.SessionMapper;
import com.zlo.wallpaper.auth.mapper.UserMapper;
import com.zlo.wallpaper.auth.model.AccessIdentity;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.auth.util.JwtUtil;
import com.zlo.wallpaper.auth.util.TokenGenerator;
import com.zlo.wallpaper.auth.vo.RegisterVO;
import com.zlo.wallpaper.auth.vo.SessionVO;
import com.zlo.wallpaper.auth.vo.UserVO;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.config.AuthProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 实现密码校验、独立登录会话、访问令牌刷新与当前会话撤销。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AdminCategoryIntegrationTest.authService、AdminWallpaperIntegrationTest.auth、AuthController.authService、AuthFilter.authService、AuthIntegrationTest.authService、AuthMySqlIT.authService、OrderIntegrationTest.authService。
 */
@Service
public class AuthServiceImpl implements AuthService {
    @Autowired private UserMapper userMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private TokenGenerator tokenGenerator;
    @Autowired private AuthProperties properties;
    @Autowired private Clock clock;
    private String dummyPasswordHash;

    /**
     * 预先生成占位密码哈希，让不存在的账号也执行一次密码比较。
     * 框架调用位置：Spring完成依赖注入后执行初始化。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @PostConstruct
    public void initializeDummyPassword() {
        // 用户不存在时也执行一次密码比较，避免明显的耗时差异。
        dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 校验注册资料、保存安全密码哈希并返回注册结果。
     * 调用/引用位置：AdminCategoryIntegrationTest.registerAndLogin、AdminWallpaperIntegrationTest.account、AuthController.register、AuthMySqlIT.registerOwnAccount、OrderIntegrationTest.login。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 注册成功后的用户名，不包含登录令牌
     */
    @Override
    @Transactional
    public RegisterVO register(RegisterDTO dto) {
        if (userMapper.selectByUsername(dto.getUsername()) != null) { throw usernameExists(); }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        // 不接收客户端指定的角色，注册永远创建普通用户。
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setBalanceCents(0L);
        user.setCreatedAt(now());
        user.setUpdatedAt(user.getCreatedAt());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            // 并发注册的最后一道保护来自数据库唯一约束。
            throw usernameExists();
        }
        return new RegisterVO(user.getUsername());
    }

    /**
     * 校验账号密码和当前用户状态，创建会话并签发登录结果。
     * 调用/引用位置：AdminCategoryIntegrationTest.registerAndLogin、AdminWallpaperIntegrationTest.account、AuthController.login、AuthMySqlIT.login、OrderIntegrationTest.login。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 新accessToken、refreshToken、实际有效秒数expiresIn及公开UserVO
     */
    @Override
    @Transactional
    public SessionVO login(LoginDTO dto) {
        User user = userMapper.selectByUsername(dto.getUsername());
        String hash = user == null ? dummyPasswordHash : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(dto.getPassword(), hash);
        if (user == null || !matches || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误，或账号不可用");
        }
        String refreshToken = tokenGenerator.generateRefreshToken();
        LocalDateTime createdAt = now();
        Session session = createSession(user.getId(), refreshToken,
                createdAt, createdAt.plusDays(properties.getSessionDays()));
        return sessionVO(user, session, refreshToken, createdAt);
    }

    /**
     * 核对固定刷新令牌和当前会话，重新签发访问令牌，不更新会话或轮换刷新令牌。
     * 调用/引用位置：AuthController.refresh、AuthIntegrationTest.concurrentRefresh、AuthMySqlIT.refreshTogether、AuthMySqlIT.refreshReusesSessionAndLogoutIsIndependentOnMySql。
     *
     * @param refreshToken 客户端持有的明文刷新令牌，用于刷新或撤销当前登录，不记录到日志
     * @return 新accessToken、refreshToken、实际有效秒数expiresIn及公开UserVO
     */
    @Override
    public SessionVO refresh(String refreshToken) {
        Session session = sessionMapper.selectByRefreshHash(tokenGenerator.hash(refreshToken));
        LocalDateTime currentTime = now();
        if (session == null || session.getRevokedAt() != null || !session.getExpiresAt().isAfter(currentTime)) { throw expired(); }
        User user = userMapper.selectById(session.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) { throw expired(); }
        // SessionVO要求至少60秒；刷新不延长这次登录的固定截止时间。
        if (Duration.between(currentTime, session.getExpiresAt()).getSeconds() < 60) { throw expired(); }
        // 刷新只读、不新增或恢复会话。若此时并发登出，authenticate仍会查到撤销并拒绝该JWT。
        return sessionVO(user, session, refreshToken, currentTime);
    }

    /**
     * 原子撤销当前登录，令该会话的全部访问令牌失效，不影响其他登录。
     * 调用/引用位置：AuthController.logout、AuthMySqlIT.logoutRacingRefreshCannotLeaveActiveSessionOnMySql、AuthMySqlIT.refreshReusesSessionAndLogoutIsIndependentOnMySql。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param refreshToken 客户端持有的明文刷新令牌，用于刷新或撤销当前登录，不记录到日志
     */
    @Override
    public void logout(String refreshToken) {
        // 单条条件UPDATE即可；重复登出、未知令牌不更新记录，仍返回成功。
        sessionMapper.revokeByRefreshHash(tokenGenerator.hash(refreshToken), now());
    }

    /**
     * 验证访问JWT并核对数据库会话、用户归属和当前账号状态。
     * 调用/引用位置：AuthFilter.doFilterInternal、AuthMySqlIT.refreshReusesSessionAndLogoutIsIndependentOnMySql。
     *
     * @param accessToken Authorization携带的访问JWT，需要验证签名并核对会话和用户
     * @return 上述处理得到的User结果；业务校验失败时通过异常返回错误
     */
    @Override
    public User authenticate(String accessToken) {
        AccessIdentity identity = jwtUtil.parseAccessToken(accessToken);
        Session session = sessionMapper.selectById(identity.getSessionId());
        if (session == null || session.getUserId() != identity.getUserId()
                || session.getRevokedAt() != null || !session.getExpiresAt().isAfter(now())) {
            throw expired();
        }
        // 每次读取当前角色/状态，让禁用和撤销管理员权限立即生效。
        User user = userMapper.selectById(identity.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) { throw expired(); }
        return user;
    }

    /**
     * 保存新会话记录，只存刷新令牌哈希并回填会话ID。
     * 调用/引用位置：AuthServiceImpl.login。
     *
     * @param userId 当前可信用户的数据库ID
     * @param token 刷新令牌或访问令牌字符串，按当前方法用途处理且不得写入日志
     * @param createdAt 记录的UTC创建时间
     * @param expiresAt 当前会话的固定UTC截止时间
     * @return 上述处理得到的Session结果；业务校验失败时通过异常返回错误
     */
    private Session createSession(long userId, String token,
                                  LocalDateTime createdAt, LocalDateTime expiresAt) {
        Session session = new Session();
        session.setUserId(userId);
        session.setRefreshTokenHash(tokenGenerator.hash(token));
        session.setCreatedAt(createdAt);
        session.setExpiresAt(expiresAt);
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 将用户与会话转成登录响应，访问期限不超过当前会话剩余时间。
     * 调用/引用位置：AuthServiceImpl.login、AuthServiceImpl.refresh。
     *
     * @param user 已查询或校验的用户实体
     * @param session 已查询或新创建的会话实体
     * @param token 刷新令牌或访问令牌字符串，按当前方法用途处理且不得写入日志
     * @param currentTime 签发令牌时的UTC当前时间
     * @return 新accessToken、refreshToken、实际有效秒数expiresIn及公开UserVO
     */
    private SessionVO sessionVO(User user, Session session, String token, LocalDateTime currentTime) {
        int seconds = (int) Math.min(properties.getAccessTokenSeconds(),
                Duration.between(currentTime, session.getExpiresAt()).getSeconds());
        String accessToken = jwtUtil.createAccessToken(user.getId(), session.getId(),
                currentTime.toInstant(ZoneOffset.UTC), seconds);
        UserVO userVO = new UserVO(user.getId().toString(), user.getUsername(), user.getBalanceCents().toString());
        return new SessionVO(accessToken, token, seconds, userVO);
    }

    /**
     * 读取当前UTC时间并与数据库毫秒时间精度保持一致。
     * 调用/引用位置：AuthServiceImpl.authenticate、AuthServiceImpl.login、AuthServiceImpl.logout、AuthServiceImpl.refresh、AuthServiceImpl.register。
     * 输入参数：无。
     *
     * @return 当前UTC时间，精度与数据库的毫秒字段一致
     */
    private LocalDateTime now() {
        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 构造登录或令牌已失效的401业务异常。
     * 调用/引用位置：AuthServiceImpl.authenticate、AuthServiceImpl.refresh。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException expired() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录已失效，请重新登录");
    }

    /**
     * 构造用户名已注册的409业务异常。
     * 调用/引用位置：AuthServiceImpl.register。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException usernameExists() {
        return new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被注册");
    }
}
