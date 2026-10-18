package com.zlo.wallpaper.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.auth.task.SessionCleanupTask;
import com.zlo.wallpaper.auth.util.JwtUtil;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证用户认证与会话轮换的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:auth;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/auth-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AuthIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthService authService;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private SessionCleanupTask cleanup;
    private static final String PASSWORD = "Learning123!";

    /**
     * 清理本测试数据库的会话与用户，并重置线程身份，保证用例相互独立。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM users");
        BaseContext.removeCurrentId();
    }

    /**
     * 验证注册保存安全哈希并固定普通用户角色，不自动建立登录会话。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void registrationStoresHashAndOrdinaryRoleWithoutLoggingIn() throws Exception {
        MvcResult result = postJson("/auth/register", Map.of("username", "learner", "password", PASSWORD,
                "role", "ADMIN", "isAdmin", true), 201);
        JsonNode body = body(result);
        assertEquals("learner", body.path("data").path("username").asText());
        assertEquals(result.getResponse().getHeader("X-Request-Id"), body.path("requestId").asText());
        assertEquals("USER", jdbc.queryForObject("SELECT role FROM users", String.class));
        assertNotEquals(PASSWORD, jdbc.queryForObject("SELECT password_hash FROM users", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class));
        assertFalse(result.getResponse().getContentAsString().contains("password"));
        assertCode(postJson("/auth/register", credentials("LEARNER", PASSWORD), 409), "USERNAME_EXISTS");
    }

    /**
     * 验证登录JWT实际期限为8天，用户资料符合契约且不泄露内部字段。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void loginAndMeMatchContractAndDoNotLeakInternalFields() throws Exception {
        JsonNode session = registerAndLogin("learner");
        assertEquals(691200, session.path("expiresIn").asInt());
        // 验证JWT自身到期时间也为8天，不能只改接口返回的expiresIn。
        String payload = session.path("accessToken").asText().split("\\.")[1];
        JsonNode claims = json.readTree(java.util.Base64.getUrlDecoder().decode(payload));
        assertEquals(691200, claims.path("exp").asLong() - claims.path("iat").asLong());
        assertTrue(session.path("user").path("id").isTextual());
        String storedHash = jdbc.queryForObject("SELECT refresh_token_hash FROM sessions", String.class);
        assertNotEquals(session.path("refreshToken").asText(), storedHash);
        assertEquals(64, storedHash.length());
        MvcResult me = getWithToken("/me", session.path("accessToken").asText(), 200);
        assertEquals("learner", body(me).path("data").path("username").asText());
        assertFalse(body(me).path("data").has("displayName"));
        assertFalse(session.path("user").has("displayName"));
        assertEquals(3, body(me).path("data").size());
        assertEquals("0", body(me).path("data").path("balanceCents").asText());
        assertNull(BaseContext.getCurrentId());
    }

    /**
     * 验证错误密码、不存在账号和禁用账号都无法登录。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void wrongPasswordUnknownAndDisabledAccountsCannotLogin() throws Exception {
        register("learner");
        assertCode(postJson("/auth/login", credentials("learner", "Wrong123!"), 401), "INVALID_CREDENTIALS");
        assertCode(postJson("/auth/login", credentials("unknown", PASSWORD), 401), "INVALID_CREDENTIALS");
        jdbc.update("UPDATE users SET status='DISABLED'");
        assertCode(postJson("/auth/login", credentials("learner", PASSWORD), 401), "INVALID_CREDENTIALS");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class));
    }

    /**
     * 验证非法用户名不能注册。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param username 待查询或注册的用户名
     */
    @ParameterizedTest
    @ValueSource(strings = {"ab", "contains space", "用户名", "name/escape"})
    void invalidUsernamesAreRejected(String username) throws Exception {
        assertCode(postJson("/auth/register", credentials(username, PASSWORD), 400), "VALIDATION_ERROR");
    }

    /**
     * 验证不满足长度或空白规则的密码不能注册。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param password 用户密码或测试密码，不进行日志输出
     */
    @ParameterizedTest
    @ValueSource(strings = {"short", "        "})
    void invalidPasswordsAreRejected(String password) throws Exception {
        assertCode(postJson("/auth/register", credentials("learner", password), 400), "VALIDATION_ERROR");
    }

    /**
     * 验证Unicode密码长度按字符计数并保留真实密码空白。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void longUnicodePasswordsWorkAndPasswordWhitespaceIsPreserved() throws Exception {
        String password = "😀".repeat(128);
        postJson("/auth/register", credentials("unicode", password), 201);
        postJson("/auth/login", credentials("unicode", password), 200);
        postJson("/auth/register", credentials("tooLong", password + "😀"), 400);
        postJson("/auth/register", credentials("spaced", " " + PASSWORD + " "), 201);
        postJson("/auth/login", credentials("spaced", PASSWORD), 401);
        postJson("/auth/login", credentials("spaced", " " + PASSWORD + " "), 200);
    }

    /**
     * 验证缺失或格式错误的认证JSON返回安全校验错误。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void missingAndMalformedJsonReturnSafeValidationErrors() throws Exception {
        postJson("/auth/register", Map.of("username", "learner"), 400);
        mvc.perform(post("/api/v1/auth/login").contextPath("/api/v1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\": secret"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postJson("/auth/refresh", Map.of("refreshToken", ""), 400);
        postJson("/auth/register", Map.of("username", 12345, "password", PASSWORD), 400);
        postJson("/auth/login", Map.of("username", "learner", "password", 12345678), 400);
        postJson("/auth/refresh", Map.of("refreshToken", true), 400);
    }

    /**
     * 验证访问令牌过期后仍可刷新，旧刷新令牌可注销所属登录组。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void expiredAccessCanRefreshAndOldRefreshCanLogoutTheWholeFamily() throws Exception {
        JsonNode first = registerAndLogin("learner");
        Long userId = jdbc.queryForObject("SELECT id FROM users", Long.class);
        Long sessionId = jdbc.queryForObject("SELECT id FROM sessions", Long.class);
        String expiredAccess = jwtUtil.createAccessToken(userId, sessionId, Instant.now().minusSeconds(1000), 900);
        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").contextPath("/api/v1")
                .header("Authorization", "Bearer " + expiredAccess).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(refresh(first)))).andExpect(status().isOk()).andReturn();
        JsonNode next = body(refreshed).path("data");
        postJson("/auth/logout", refresh(first), 200);
        getWithToken("/me", next.path("accessToken").asText(), 401);
    }

    /**
     * 验证访问令牌不能冒用其他用户的会话。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void accessTokenCannotClaimAnotherUsersSession() throws Exception {
        JsonNode first = registerAndLogin("learner");
        register("another");
        Long anotherUser = jdbc.queryForObject("SELECT id FROM users WHERE username='another'", Long.class);
        Long sessionId = jdbc.queryForObject("SELECT id FROM sessions", Long.class);
        String mismatched = jwtUtil.createAccessToken(anotherUser, sessionId, Instant.now(), 900);
        getWithToken("/me", mismatched, 401);
        postJson("/auth/refresh", Map.of("refreshToken", first.path("accessToken").asText()), 401);
        getWithToken("/me", first.path("refreshToken").asText(), 401);
        getWithToken("/me", first.path("accessToken").asText(), 200);
    }

    /**
     * 验证刷新轮换令牌但不延长整组截止时间。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void refreshRotatesBothTokensAndKeepsFixedFamilyExpiry() throws Exception {
        JsonNode first = registerAndLogin("learner");
        JsonNode next = body(postJson("/auth/refresh", refresh(first), 200)).path("data");
        assertNotEquals(first.path("refreshToken").asText(), next.path("refreshToken").asText());
        assertNotEquals(first.path("accessToken").asText(), next.path("accessToken").asText());
        getWithToken("/me", next.path("accessToken").asText(), 200);
        getWithToken("/me", first.path("accessToken").asText(), 401);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT family_id) FROM sessions", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT expires_at) FROM sessions", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE replaced_by_id IS NOT NULL", Integer.class));
    }

    /**
     * 验证刷新重放返回401时仍提交撤销，且不影响其他独立登录。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void replayRevocationCommitsDespite401AndDoesNotRevokeAnotherLogin() throws Exception {
        JsonNode first = registerAndLogin("learner");
        JsonNode another = login("learner");
        JsonNode next = body(postJson("/auth/refresh", refresh(first), 200)).path("data");
        assertCode(postJson("/auth/refresh", refresh(first), 401), "SESSION_EXPIRED");
        getWithToken("/me", next.path("accessToken").asText(), 401);
        postJson("/auth/refresh", refresh(next), 401);
        getWithToken("/me", another.path("accessToken").asText(), 200);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE revoke_reason='REPLAY'", Integer.class));
    }

    /**
     * 验证注销可重复调用并撤销整组，无需有效访问令牌。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void logoutRevokesFamilyIsRetryableAndDoesNotNeedValidAccessToken() throws Exception {
        JsonNode first = registerAndLogin("learner");
        JsonNode second = login("learner");
        MvcResult result = postJson("/auth/logout", refresh(first), 200);
        assertEquals(0, body(result).path("data").size());
        postJson("/auth/logout", refresh(first), 200);
        postJson("/auth/logout", Map.of("refreshToken", "unknown"), 200);
        postJson("/auth/refresh", refresh(first), 401);
        getWithToken("/me", first.path("accessToken").asText(), 401);
        getWithToken("/me", second.path("accessToken").asText(), 200);
    }

    /**
     * 验证数据库会话到期、JWT到期或伪造令牌均被拒绝。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void expiredSessionAndJwtAndForgedTokenAreRejected() throws Exception {
        JsonNode session = registerAndLogin("learner");
        Long userId = jdbc.queryForObject("SELECT id FROM users", Long.class);
        Long sessionId = jdbc.queryForObject("SELECT id FROM sessions", Long.class);
        String expiredJwt = jwtUtil.createAccessToken(userId, sessionId, Instant.now().minusSeconds(1000), 900);
        getWithToken("/me", expiredJwt, 401);
        getWithToken("/me", session.path("accessToken").asText() + "x", 401);
        jdbc.update("UPDATE sessions SET created_at=?, expires_at=?", LocalDateTime.now().minusDays(2),
                LocalDateTime.now().minusDays(1));
        postJson("/auth/refresh", refresh(session), 401);
        getWithToken("/me", session.path("accessToken").asText(), 401);
        assertCode(postJson("/auth/refresh", Map.of("refreshToken", "unknown"), 401), "SESSION_EXPIRED");
    }

    /**
     * 验证管理权限按数据库当前角色与状态实时校验。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void adminPermissionUsesCurrentDatabaseRoleAndStatus() throws Exception {
        JsonNode session = registerAndLogin("learner");
        String token = session.path("accessToken").asText();
        assertCode(getWithToken("/admin/permission-probe", token, 403), "FORBIDDEN");
        jdbc.update("UPDATE users SET role='ADMIN'");
        // 使用不存在的探测路由：管理员通过过滤器后应得到404，无须引入商品表夹具。
        getWithToken("/admin/permission-probe", token, 404);
        jdbc.update("UPDATE users SET role='USER'");
        getWithToken("/admin/permission-probe", token, 403);
        jdbc.update("UPDATE users SET status='DISABLED'");
        getWithToken("/me", token, 401);
        postJson("/auth/refresh", refresh(session), 401);
        jdbc.update("UPDATE users SET status='ACTIVE'");
        getWithToken("/me", token, 401); // 刷新时的禁用处理已经撤销整组。
    }

    /**
     * 验证缺失认证、跨域响应和请求结束线程清理符合约定。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void missingAuthAndCorsAndContextCleanupAreCorrect() throws Exception {
        mvc.perform(get("/api/v1/me").contextPath("/api/v1").header("Origin", "http://127.0.0.1:4173"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:4173"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        mvc.perform(options("/api/v1/admin/categories/1").contextPath("/api/v1")
                .header("Origin", "http://127.0.0.1:4173").header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk());
        assertNull(BaseContext.getCurrentId());
    }

    /**
     * 验证并发刷新不能留下两个有效后继会话。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void parallelRefreshCanNeverCreateTwoActiveDescendants() throws Exception {
        JsonNode session = registerAndLogin("learner");
        String refreshToken = session.path("refreshToken").asText();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> concurrentRefresh(start, refreshToken));
            Future<Boolean> second = executor.submit(() -> concurrentRefresh(start, refreshToken));
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE revoked_at IS NULL", Integer.class));
        } finally { executor.shutdownNow(); }
    }

    /**
     * 验证会话清理保留截止时间及保留期内的轮换历史。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void cleanupRetainsRotationHistoryUntilFamilyExpiryPlusRetention() throws Exception {
        JsonNode first = registerAndLogin("learner");
        postJson("/auth/refresh", refresh(first), 200);
        cleanup.cleanExpiredSessions();
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class));
        jdbc.update("UPDATE sessions SET created_at=?, expires_at=?", LocalDateTime.now().minusDays(20),
                LocalDateTime.now().minusDays(8));
        cleanup.cleanExpiredSessions();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }

    /**
     * 准备同步起跑的线程，验证并发刷新结果与数据库状态。
     * 调用/引用位置：AuthIntegrationTest.parallelRefreshCanNeverCreateTwoActiveDescendants。
     *
     * @param latch 本方法使用的CountDownLatch输入，按上面的业务用途处理
     * @param token 刷新令牌或访问令牌字符串，按当前方法用途处理且不得写入日志
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean concurrentRefresh(CountDownLatch latch, String token) throws Exception {
        latch.await(5, TimeUnit.SECONDS);
        try { authService.refresh(token); return true; }
        catch (BusinessException exception) { assertEquals("SESSION_EXPIRED", exception.getCode()); return false; }
    }

    /**
     * 构造用户名与密码的测试JSON参数。
     * 调用/引用位置：AuthIntegrationTest.invalidPasswordsAreRejected、AuthIntegrationTest.invalidUsernamesAreRejected、AuthIntegrationTest.login、AuthIntegrationTest.longUnicodePasswordsWorkAndPasswordWhitespaceIsPreserved、AuthIntegrationTest.register、AuthIntegrationTest.registrationStoresHashAndOrdinaryRoleWithoutLoggingIn、AuthIntegrationTest.wrongPasswordUnknownAndDisabledAccountsCannotLogin。
     *
     * @param username 待查询或注册的用户名
     * @param password 用户密码或测试密码，不进行日志输出
     * @return 上述处理得到的Map&lt;String, String&gt;结果；业务校验失败时通过异常返回错误
     */
    private Map<String, String> credentials(String username, String password) {
        return Map.of("username", username, "password", password);
    }
    /**
     * 构造刷新或登出请求的refreshToken测试JSON参数。
     * 调用/引用位置：AuthIntegrationTest.adminPermissionUsesCurrentDatabaseRoleAndStatus、AuthIntegrationTest.cleanupRetainsRotationHistoryUntilFamilyExpiryPlusRetention、AuthIntegrationTest.expiredAccessCanRefreshAndOldRefreshCanLogoutTheWholeFamily、AuthIntegrationTest.expiredSessionAndJwtAndForgedTokenAreRejected、AuthIntegrationTest.logoutRevokesFamilyIsRetryableAndDoesNotNeedValidAccessToken、AuthIntegrationTest.refreshRotatesBothTokensAndKeepsFixedFamilyExpiry、AuthIntegrationTest.replayRevocationCommitsDespite401AndDoesNotRevokeAnotherLogin。
     *
     * @param session 已查询或新创建的会话实体
     * @return 上述处理得到的Map&lt;String, String&gt;结果；业务校验失败时通过异常返回错误
     */
    private Map<String, String> refresh(JsonNode session) {
        return Map.of("refreshToken", session.path("refreshToken").asText());
    }
    /**
     * 调用真实注册入口，为后续认证用例准备账号。
     * 调用/引用位置：AuthIntegrationTest.accessTokenCannotClaimAnotherUsersSession、AuthIntegrationTest.registerAndLogin、AuthIntegrationTest.wrongPasswordUnknownAndDisabledAccountsCannotLogin。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param username 待查询或注册的用户名
     */
    private void register(String username) throws Exception {
        postJson("/auth/register", credentials(username, PASSWORD), 201);
    }
    /**
     * 注册测试账号并登录，必要时配置测试角色，返回令牌或会话。
     * 调用/引用位置：AuthIntegrationTest.accessTokenCannotClaimAnotherUsersSession、AuthIntegrationTest.adminPermissionUsesCurrentDatabaseRoleAndStatus、AuthIntegrationTest.cleanupRetainsRotationHistoryUntilFamilyExpiryPlusRetention、AuthIntegrationTest.expiredAccessCanRefreshAndOldRefreshCanLogoutTheWholeFamily、AuthIntegrationTest.expiredSessionAndJwtAndForgedTokenAreRejected、AuthIntegrationTest.loginAndMeMatchContractAndDoNotLeakInternalFields、AuthIntegrationTest.logoutRevokesFamilyIsRetryableAndDoesNotNeedValidAccessToken、AuthIntegrationTest.parallelRefreshCanNeverCreateTwoActiveDescendants、AuthIntegrationTest.refreshRotatesBothTokensAndKeepsFixedFamilyExpiry、AuthIntegrationTest.replayRevocationCommitsDespite401AndDoesNotRevokeAnotherLogin。
     *
     * @param username 待查询或注册的用户名
     * @return 解析后的测试JSON节点，供用例读取业务字段
     */
    private JsonNode registerAndLogin(String username) throws Exception {
        register(username);
        return login(username);
    }
    /**
     * 登录或注册并登录测试账号，获取当前用例使用的令牌或业务身份。
     * 调用/引用位置：AuthIntegrationTest.logoutRevokesFamilyIsRetryableAndDoesNotNeedValidAccessToken、AuthIntegrationTest.registerAndLogin、AuthIntegrationTest.replayRevocationCommitsDespite401AndDoesNotRevokeAnotherLogin。
     *
     * @param username 待查询或注册的用户名
     * @return 解析后的测试JSON节点，供用例读取业务字段
     */
    private JsonNode login(String username) throws Exception {
        return body(postJson("/auth/login", credentials(username, PASSWORD), 200)).path("data");
    }
    /**
     * 通过MockMvc发送JSON请求，并断言实际HTTP状态。
     * 调用/引用位置：AuthIntegrationTest.accessTokenCannotClaimAnotherUsersSession、AuthIntegrationTest.adminPermissionUsesCurrentDatabaseRoleAndStatus、AuthIntegrationTest.cleanupRetainsRotationHistoryUntilFamilyExpiryPlusRetention、AuthIntegrationTest.expiredAccessCanRefreshAndOldRefreshCanLogoutTheWholeFamily、AuthIntegrationTest.expiredSessionAndJwtAndForgedTokenAreRejected、AuthIntegrationTest.invalidPasswordsAreRejected、AuthIntegrationTest.invalidUsernamesAreRejected、AuthIntegrationTest.login、AuthIntegrationTest.logoutRevokesFamilyIsRetryableAndDoesNotNeedValidAccessToken、AuthIntegrationTest.longUnicodePasswordsWorkAndPasswordWhitespaceIsPreserved、AuthIntegrationTest.missingAndMalformedJsonReturnSafeValidationErrors、AuthIntegrationTest.refreshRotatesBothTokensAndKeepsFixedFamilyExpiry、AuthIntegrationTest.register、AuthIntegrationTest.registrationStoresHashAndOrdinaryRoleWithoutLoggingIn、AuthIntegrationTest.replayRevocationCommitsDespite401AndDoesNotRevokeAnotherLogin、AuthIntegrationTest.wrongPasswordUnknownAndDisabledAccountsCannotLogin。
     *
     * @param path 请求路径或服务器控制的文件路径
     * @param payload 本方法使用的Object输入，按上面的业务用途处理
     * @param expectedStatus 本方法使用的int输入，按上面的业务用途处理
     * @return 已经检查HTTP状态的MockMvc响应，供后续断言解析
     */
    private MvcResult postJson(String path, Object payload, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/v1" + path).contextPath("/api/v1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }
    /**
     * 通过MockMvc发送带Bearer令牌的查询并断言HTTP状态。
     * 调用/引用位置：AuthIntegrationTest.accessTokenCannotClaimAnotherUsersSession、AuthIntegrationTest.adminPermissionUsesCurrentDatabaseRoleAndStatus、AuthIntegrationTest.expiredAccessCanRefreshAndOldRefreshCanLogoutTheWholeFamily、AuthIntegrationTest.expiredSessionAndJwtAndForgedTokenAreRejected、AuthIntegrationTest.loginAndMeMatchContractAndDoNotLeakInternalFields、AuthIntegrationTest.logoutRevokesFamilyIsRetryableAndDoesNotNeedValidAccessToken、AuthIntegrationTest.refreshRotatesBothTokensAndKeepsFixedFamilyExpiry、AuthIntegrationTest.replayRevocationCommitsDespite401AndDoesNotRevokeAnotherLogin。
     *
     * @param path 请求路径或服务器控制的文件路径
     * @param token 刷新令牌或访问令牌字符串，按当前方法用途处理且不得写入日志
     * @param expectedStatus 本方法使用的int输入，按上面的业务用途处理
     * @return 已经检查HTTP状态的MockMvc响应，供后续断言解析
     */
    private MvcResult getWithToken(String path, String token, int expectedStatus) throws Exception {
        return mvc.perform(get("/api/v1" + path).contextPath("/api/v1")
                .header("Authorization", "Bearer " + token)).andExpect(status().is(expectedStatus)).andReturn();
    }
    /**
     * 解析测试HTTP响应的完整JSON外壳。
     * 调用/引用位置：AuthIntegrationTest.assertCode、AuthIntegrationTest.expiredAccessCanRefreshAndOldRefreshCanLogoutTheWholeFamily、AuthIntegrationTest.login、AuthIntegrationTest.loginAndMeMatchContractAndDoNotLeakInternalFields、AuthIntegrationTest.logoutRevokesFamilyIsRetryableAndDoesNotNeedValidAccessToken、AuthIntegrationTest.refreshRotatesBothTokensAndKeepsFixedFamilyExpiry、AuthIntegrationTest.registrationStoresHashAndOrdinaryRoleWithoutLoggingIn、AuthIntegrationTest.replayRevocationCommitsDespite401AndDoesNotRevokeAnotherLogin。
     *
     * @param result 本方法要转换或断言的执行结果
     * @return 解析后的测试JSON节点，供用例读取业务字段
     */
    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
    /**
     * 断言错误响应的业务码与统一错误格式。
     * 调用/引用位置：AuthIntegrationTest.adminPermissionUsesCurrentDatabaseRoleAndStatus、AuthIntegrationTest.expiredSessionAndJwtAndForgedTokenAreRejected、AuthIntegrationTest.invalidPasswordsAreRejected、AuthIntegrationTest.invalidUsernamesAreRejected、AuthIntegrationTest.registrationStoresHashAndOrdinaryRoleWithoutLoggingIn、AuthIntegrationTest.replayRevocationCommitsDespite401AndDoesNotRevokeAnotherLogin、AuthIntegrationTest.wrongPasswordUnknownAndDisabledAccountsCannotLogin。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param result 本方法要转换或断言的执行结果
     * @param code 统一响应使用的业务错误码
     */
    private void assertCode(MvcResult result, String code) throws Exception {
        assertEquals(code, body(result).path("code").asText());
        assertTrue(body(result).path("data").isNull());
    }
}
