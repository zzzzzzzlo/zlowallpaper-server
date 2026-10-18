package com.zlo.wallpaper.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.order.service.OrderService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用真实过滤器、Controller、Service及H2 SQL验证订单查询的账号隔离、快照和业务上限。
 * 创建位置：JUnit/Spring Test实例化；只操作本类独立内存库，不注入生产业务代码。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-query;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Sql(scripts = {"/auth-schema.sql", "/admin-category-schema.sql", "/admin-wallpaper-schema.sql", "/order-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class OrderQueryIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthService auth;
    @Autowired private OrderService service;
    private String token;
    private String otherToken;
    private Long buyerId;
    private Long otherId;

    /** JUnit在测试类开始时创建两个账号，通过真实AuthService签发令牌。 */
    @BeforeAll
    void accounts() {
        token = login("query_buyer");
        otherToken = login("query_other");
        buyerId = jdbc.queryForObject("SELECT id FROM users WHERE username='query_buyer'", Long.class);
        otherId = jdbc.queryForObject("SELECT id FROM users WHERE username='query_other'", Long.class);
    }

    /** JUnit每个用例前清空本类内存库的订单，准备同一件可反复取消购买的商品。 */
    @BeforeEach
    void fixtures() {
        BaseContext.removeCurrentId();
        jdbc.update("DELETE FROM entitlements");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM wallpapers");
        jdbc.update("DELETE FROM categories");
        jdbc.update("UPDATE users SET status='ACTIVE'");
        jdbc.update("INSERT INTO categories(id,name,sort_order,enabled,created_at,updated_at) VALUES(1,'查询测试',0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("""
                INSERT INTO wallpapers(id,title,category_id,type,price_cents,publication_status,
                    created_by,created_at,updated_at)
                VALUES(301,'商品当前标题',1,'IMAGE',2000,'WITHDRAWN',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, buyerId);
        jdbc.update("""
                INSERT INTO wallpapers(id,title,category_id,type,price_cents,publication_status,
                    created_by,created_at,updated_at)
                VALUES(302,'另一商品当前标题',1,'IMAGE',2000,'WITHDRAWN',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, buyerId);
    }

    /** JUnit验证无订单返回空数组，同时保留统一包装、requestId并清理线程身份。 */
    @Test
    void emptyListKeepsEnvelopeAndRequestId() throws Exception {
        MvcResult response = mvc.perform(get("/api/v1/me/orders").contextPath("/api/v1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data").isEmpty()).andReturn();
        assertEquals(response.getResponse().getHeader("X-Request-Id"),
                json.readTree(response.getResponse().getContentAsString()).path("requestId").asText());
        assertNull(BaseContext.getCurrentId());
    }

    /** JUnit验证四种状态全部保留、创建时间与ID双重倒序，使用成交快照及字符串大ID。 */
    @Test
    void allStatesSortedByCreationThenIdKeepHistoricalSnapshots() throws Exception {
        insertOrder(1, buyerId, "REFUNDED", "2026-10-10 12:00:00");
        insertOrder(2, buyerId, "CANCELED", "2026-10-11 12:00:00");
        insertOrder(3, buyerId, "PAID", "2026-10-12 12:00:00");
        long largeId = 9007199254740993L;
        insertOrder(largeId, buyerId, "PENDING", "2026-10-12 12:00:00");
        jdbc.update("UPDATE orders SET currency='CNY' WHERE id=1");

        JsonNode rows = list(token);
        assertEquals(4, rows.size());
        assertEquals(Long.toString(largeId), rows.get(0).path("id").asText());
        assertEquals("PAID", rows.get(1).path("status").asText());
        assertEquals("CANCELED", rows.get(2).path("status").asText());
        assertEquals("REFUNDED", rows.get(3).path("status").asText());
        assertEquals("CNY", rows.get(3).path("currency").asText());
        for (JsonNode row : rows) {
            assertEquals(9, row.size());
            assertTrue(row.path("id").isTextual());
            assertTrue(row.path("wallpaperId").isTextual());
            assertEquals("PAID".equals(row.path("status").asText()) ? "302" : "301",
                    row.path("wallpaperId").asText());
            assertEquals("成交时标题", row.path("title").asText());
            assertEquals(990, row.path("amountCents").asInt());
            assertTrue(row.path("createdAt").asText().endsWith("Z"));
            assertTrue(row.path("userId").isTextual());
            assertTrue(row.path("balanceCents").isTextual());
            assertFalse(row.has("paymentMethod"));
        }
    }

    /** JUnit验证普通用户只能查自己的订单，伪造查询参数userId也不能改变数据库身份条件。 */
    @Test
    void accountsAreIsolatedEvenWhenQueryUserIdIsForged() throws Exception {
        insertOrder(1, buyerId, "PENDING", "2026-10-12 12:00:00");
        insertOrder(2, otherId, "PENDING", "2026-10-12 12:00:00");
        mvc.perform(get("/api/v1/me/orders").contextPath("/api/v1").param("userId", otherId.toString())
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("1"));
        assertEquals("2", list(otherToken).get(0).path("id").asText());
    }

    /**
     * JUnit分别验证缺少令牌及无效令牌，均在认证过滤器返回401。
     * @param authorization 空请求头或无效Bearer令牌
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer invalid-token"})
    void missingOrInvalidLoginIsRejected(String authorization) throws Exception {
        mvc.perform(get("/api/v1/me/orders").contextPath("/api/v1").header("Authorization", authorization))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        assertNull(BaseContext.getCurrentId());
    }

    /** JUnit验证已禁用账号不能使用原令牌读取订单。 */
    @Test
    void disabledUserCannotReadOrders() throws Exception {
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", buyerId);
        mvc.perform(get("/api/v1/me/orders").contextPath("/api/v1").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data").isEmpty());
    }

    /** JUnit验证Service被直接调用且没有可信身份时仍拒绝查询，避免忘记设置身份后查全表。 */
    @Test
    void serviceRequiresTrustedIdentity() {
        BusinessException exception = assertThrows(BusinessException.class, () -> service.getMyOrders());
        assertEquals(401, exception.getStatus().value());
    }

    /** JUnit验证恰好1000条完整返回，1001条明确报错，并且别人的订单不计入本账号上限。 */
    @Test
    void exactLimitSucceedsAndOverflowIsNotSilentlyTruncated() throws Exception {
        List<Object[]> values = new ArrayList<>();
        for (long id = 1; id <= 1000; id++) {
            values.add(new Object[]{id, buyerId});
        }
        jdbc.batchUpdate("""
                INSERT INTO orders(id,user_id,wallpaper_id,title_snapshot,amount_cents,status,
                    created_at,updated_at,canceled_at)
                VALUES(?,?,301,'成交时标题',990,'CANCELED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, values);
        insertOrder(1001, otherId, "PENDING", "2026-10-12 12:00:00");
        assertEquals(1000, list(token).size());
        insertOrder(1002, buyerId, "PENDING", "2026-10-12 12:00:00");
        mvc.perform(get("/api/v1/me/orders").contextPath("/api/v1").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_LIST_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data").isEmpty());
        assertEquals(1001, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=?", Integer.class, buyerId));
    }

    /**
     * 注册测试账号并登录，仅供accounts调用。
     * @param username 本类独立账号名
     * @return 真实AuthService签发的accessToken
     */
    private String login(String username) {
        RegisterDTO registration = new RegisterDTO();
        registration.setUsername(username);
        registration.setPassword("Query-test-password-123");
        auth.register(registration);
        LoginDTO login = new LoginDTO();
        login.setUsername(username);
        login.setPassword(registration.getPassword());
        return auth.login(login).getAccessToken();
    }

    /**
     * 写入符合状态约束的订单夹具，供查询用例调用；无需权益，查询应包含全部历史状态。
     * @param id 测试订单ID
     * @param userId 订单归属账号
     * @param status 订单状态
     * @param createdAt 固定UTC创建时间，用于验证排序
     * 返回：无；仅写本类H2数据库。
     */
    private void insertOrder(long id, Long userId, String status, String createdAt) {
        jdbc.update("""
                INSERT INTO orders(id,user_id,wallpaper_id,title_snapshot,amount_cents,currency,status,
                    created_at,updated_at,payment_method,paid_at,canceled_at,refunded_at)
                VALUES(?,?,?,'成交时标题',990,'ZLC',?,?,?, ?,?,?,?)
                """, id, userId, "PAID".equals(status) ? 302 : 301, status, createdAt, createdAt,
                "PAID".equals(status) || "REFUNDED".equals(status) ? "COIN" : null,
                "PAID".equals(status) || "REFUNDED".equals(status) ? createdAt : null,
                "CANCELED".equals(status) ? createdAt : null,
                "REFUNDED".equals(status) ? createdAt : null);
    }

    /**
     * 通过完整HTTP处理链查询订单，用于成功路径用例。
     * @param accessToken 当前测试账号的令牌
     * @return 成功响应data数组
     */
    private JsonNode list(String accessToken) throws Exception {
        MvcResult response = mvc.perform(get("/api/v1/me/orders").contextPath("/api/v1").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }
}
