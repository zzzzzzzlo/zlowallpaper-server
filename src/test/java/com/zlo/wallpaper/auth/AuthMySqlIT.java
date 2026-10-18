package com.zlo.wallpaper.auth;

import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.mapper.UserMapper;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.auth.vo.SessionVO;
import com.zlo.wallpaper.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证用户认证与会话轮换的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "wallpaper.auth.cleanup-cron=-")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "auth.mysql.tests", matches = "true")
class AuthMySqlIT {
    @Autowired private AuthService authService;
    @Autowired private UserMapper userMapper;
    @Autowired private JdbcTemplate jdbc;
    private String username;
    private Long userId;

    /**
     * 创建带唯一标识的MySQL测试账号，只供本测试使用。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach
    void registerOwnAccount() {
        username = "authit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username);
        dto.setPassword("TestOnly123!");
        authService.register(dto);
        userId = userMapper.selectByUsername(username).getId();
    }

    /**
     * 仅清理本类生成的MySQL测试记录，保留真实用户数据。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @AfterEach
    void removeOnlyOwnedFixtures() {
        if (userId != null) {
            jdbc.update("DELETE FROM sessions WHERE user_id=?", userId);
            jdbc.update("DELETE FROM users WHERE id=? AND username=?", userId, username);
        }
    }

    /**
     * 验证MySQL真实映射与重放撤销事务提交正确。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void roundTripAndReplayCommitOnMySql() {
        SessionVO first = login();
        SessionVO independent = login();
        SessionVO next = authService.refresh(first.getRefreshToken());
        assertEquals(userId, authService.authenticate(next.getAccessToken()).getId());
        assertThrows(BusinessException.class, () -> authService.refresh(first.getRefreshToken()));
        assertThrows(BusinessException.class, () -> authService.authenticate(next.getAccessToken()));
        assertEquals(userId, authService.authenticate(independent.getAccessToken()).getId());
        authService.logout(independent.getRefreshToken());
        assertThrows(BusinessException.class, () -> authService.authenticate(independent.getAccessToken()));
    }

    /**
     * 验证MySQL并发刷新只有一个成功者并按重放规则撤销整组。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void concurrentRefreshHasOneWinnerAndRevokesReplayFamilyOnMySql() throws Exception {
        SessionVO first = login();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> a = executor.submit(() -> refreshTogether(start, first.getRefreshToken()));
            Future<Boolean> b = executor.submit(() -> refreshTogether(start, first.getRefreshToken()));
            start.countDown();
            assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(2, count("SELECT COUNT(*) FROM sessions WHERE user_id=?"));
            assertEquals(0, count("SELECT COUNT(*) FROM sessions WHERE user_id=? AND revoked_at IS NULL"));
        } finally { executor.shutdownNow(); }
    }

    /**
     * 验证MySQL刷新与登出竞争后不会留下有效会话。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void logoutRacingRefreshCannotLeaveActiveSessionOnMySql() throws Exception {
        SessionVO first = login();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> refresh = executor.submit(() -> refreshTogether(start, first.getRefreshToken()));
            Future<?> logout = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                authService.logout(first.getRefreshToken());
                return null;
            });
            start.countDown();
            refresh.get(15, TimeUnit.SECONDS);
            logout.get(15, TimeUnit.SECONDS);
            assertEquals(0, count("SELECT COUNT(*) FROM sessions WHERE user_id=? AND revoked_at IS NULL"));
        } finally { executor.shutdownNow(); }
    }

    /**
     * 让刷新或登出请求同时执行，收集每个线程的业务结果。
     * 调用/引用位置：AuthMySqlIT.concurrentRefreshHasOneWinnerAndRevokesReplayFamilyOnMySql、AuthMySqlIT.logoutRacingRefreshCannotLeaveActiveSessionOnMySql。
     *
     * @param start 本方法使用的CountDownLatch输入，按上面的业务用途处理
     * @param token 刷新令牌或访问令牌字符串，按当前方法用途处理且不得写入日志
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean refreshTogether(CountDownLatch start, String token) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try { authService.refresh(token); return true; }
        catch (BusinessException exception) { assertEquals("SESSION_EXPIRED", exception.getCode()); return false; }
    }

    /**
     * 查询本测试关注的记录数量，表名来自测试常量而不是客户端输入。
     * 调用/引用位置：AuthMySqlIT.concurrentRefreshHasOneWinnerAndRevokesReplayFamilyOnMySql、AuthMySqlIT.logoutRacingRefreshCannotLeaveActiveSessionOnMySql。
     *
     * @param sql 本方法使用的String输入，按上面的业务用途处理
     * @return 上述处理得到的int结果；业务校验失败时通过异常返回错误
     */
    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class, userId); }

    /**
     * 登录或注册并登录测试账号，获取当前用例使用的令牌或业务身份。
     * 调用/引用位置：AuthMySqlIT.concurrentRefreshHasOneWinnerAndRevokesReplayFamilyOnMySql、AuthMySqlIT.logoutRacingRefreshCannotLeaveActiveSessionOnMySql、AuthMySqlIT.roundTripAndReplayCommitOnMySql。
     * 输入参数：无。
     *
     * @return 新accessToken、refreshToken、实际有效秒数expiresIn及公开UserVO
     */
    private SessionVO login() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword("TestOnly123!");
        return authService.login(dto);
    }
}
