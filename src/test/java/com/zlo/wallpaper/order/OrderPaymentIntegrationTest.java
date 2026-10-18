package com.zlo.wallpaper.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.entitlement.mapper.EntitlementMapper;
import com.zlo.wallpaper.order.dto.OrderCreateDTO;
import com.zlo.wallpaper.order.service.OrderService;
import com.zlo.wallpaper.order.vo.OrderVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用真实HTTP处理链、Service事务及H2验证壁纸币付款；只对失败场景的权益写入使用Spy。
 * 创建位置：JUnit/Spring Test创建；不注入生产业务代码，也不连接真实商城库或OSS。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-payment;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Sql(scripts = {"/auth-schema.sql", "/admin-category-schema.sql", "/admin-wallpaper-schema.sql", "/order-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class OrderPaymentIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthService auth;
    @Autowired private OrderService service;
    @Autowired private TransactionTemplate transactions;
    @MockitoSpyBean private EntitlementMapper entitlements;
    private String token;
    private String otherToken;
    private Long buyerId;

    /** JUnit在本类测试前创建两个独立账号，令牌通过真实认证Service签发。 */
    @BeforeAll
    void accounts() {
        token = login("payment_buyer");
        otherToken = login("payment_other");
        buyerId = jdbc.queryForObject("SELECT id FROM users WHERE username='payment_buyer'", Long.class);
    }

    /** JUnit每个用例前重置购买记录，准备收费和免费商品，恢复Spy及账号状态。 */
    @BeforeEach
    void fixtures() {
        reset(entitlements);
        jdbc.update("DELETE FROM entitlements");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM wallpapers");
        jdbc.update("DELETE FROM categories");
        jdbc.update("UPDATE users SET status='ACTIVE',balance_cents=0");
        jdbc.update("UPDATE users SET balance_cents=10000 WHERE id=?", buyerId);
        jdbc.update("INSERT INTO categories(id,name,sort_order,enabled,created_at,updated_at) VALUES(1,'付款测试',0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("""
                INSERT INTO wallpapers(id,title,category_id,type,price_cents,publication_status,published_at,
                created_by,created_at,updated_at,original_object_key,original_status,cover_object_key,cover_status)
                VALUES(201,'收费壁纸',1,'VIDEO',990,'PUBLISHED',CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                'test/video.mp4','READY','test/cover.png','READY'),
                (202,'免费壁纸',1,'VIDEO',0,'PUBLISHED',CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                'test/free.mp4','READY','test/free.png','READY')
                """, buyerId, buyerId);
    }

    /** JUnit验证下架、改价后仍按原订单成交，两项数据库写入及统一响应符合契约。 */
    @Test
    void paymentUsesOrderSnapshotAndGrantsEntitlementEvenAfterWithdrawal() throws Exception {
        JsonNode pending = create("201");
        String id = pending.path("id").asText();
        jdbc.update("UPDATE wallpapers SET price_cents=0,title='改名',publication_status='WITHDRAWN' WHERE id=201");

        MvcResult response = pay(id, token, 200);
        JsonNode paid = data(response);
        assertEquals(9, paid.size());
        assertEquals(buyerId.toString(), paid.path("userId").asText());
        assertEquals("10000", pending.path("balanceCents").asText());
        assertEquals("9010", paid.path("balanceCents").asText());
        assertEquals(id, paid.path("id").asText());
        assertEquals("201", paid.path("wallpaperId").asText());
        assertEquals("PAID", paid.path("status").asText());
        assertEquals(990, paid.path("amountCents").asInt());
        assertEquals(pending.path("title"), paid.path("title"));
        assertEquals(pending.path("createdAt"), paid.path("createdAt"));
        assertEquals("ZLC", paid.path("currency").asText());
        assertEquals("COIN", jdbc.queryForObject("SELECT payment_method FROM orders", String.class));
        Timestamp paidAt = jdbc.queryForObject("SELECT paid_at FROM orders", Timestamp.class);
        assertNotNull(paidAt);
        assertEquals(paidAt, jdbc.queryForObject("SELECT updated_at FROM orders", Timestamp.class));
        assertEquals(paidAt, jdbc.queryForObject("SELECT granted_at FROM entitlements", Timestamp.class));
        assertEquals(Long.valueOf(id), jdbc.queryForObject("SELECT source_order_id FROM entitlements", Long.class));
        assertEquals(buyerId, jdbc.queryForObject("SELECT user_id FROM entitlements", Long.class));
        assertEquals(201L, jdbc.queryForObject("SELECT wallpaper_id FROM entitlements", Long.class));
        assertEquals(1, count("orders"));
        assertEquals(1, count("entitlements"));
        assertEquals(9010L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
        JsonNode envelope = json.readTree(response.getResponse().getContentAsString());
        assertEquals("OK", envelope.path("code").asText());
        assertEquals(response.getResponse().getHeader("X-Request-Id"), envelope.path("requestId").asText());
        assertNull(BaseContext.getCurrentId());
    }

    /** JUnit验证重复POST（含桌面端发送的空JSON）不改变付款时间、方式或权益编号。 */
    @Test
    void repeatedPaymentKeepsOriginalPaymentAndEntitlement() throws Exception {
        String id = create("201").path("id").asText();
        JsonNode first = data(pay(id, token, 200));
        Timestamp paidAt = jdbc.queryForObject("SELECT paid_at FROM orders", Timestamp.class);
        Long rightId = jdbc.queryForObject("SELECT id FROM entitlements", Long.class);
        MvcResult repeated = mvc.perform(post("/api/v1/orders/" + id + "/pay").contextPath("/api/v1")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first, data(repeated));
        assertEquals(paidAt, jdbc.queryForObject("SELECT paid_at FROM orders", Timestamp.class));
        assertEquals(rightId, jdbc.queryForObject("SELECT id FROM entitlements", Long.class));
        assertEquals(9010L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
        assertEquals(1, count("entitlements"));
    }

    /** JUnit验证免费已付款订单重复调用仍保持FREE，不改为COIN付款。 */
    @Test
    void freePaidOrderReturnsWithoutChangingFreePayment() throws Exception {
        JsonNode free = create("202");
        Timestamp paidAt = jdbc.queryForObject("SELECT paid_at FROM orders", Timestamp.class);
        assertEquals(free, data(pay(free.path("id").asText(), token, 200)));
        assertEquals("FREE", jdbc.queryForObject("SELECT payment_method FROM orders", String.class));
        assertEquals(paidAt, jdbc.queryForObject("SELECT paid_at FROM orders", Timestamp.class));
        assertEquals(1, count("entitlements"));
    }

    /** JUnit验证旧终结订单不能付款，也不会删除或覆盖重新购买产生的新权益。
     * @param state 本次验证的CANCELED或REFUNDED状态
     */
    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "REFUNDED"})
    void terminalOrderCannotPayOrAffectRepurchase(String state) throws Exception {
        String oldId = create("201").path("id").asText();
        if ("REFUNDED".equals(state)) {
            pay(oldId, token, 200);
            transactions.executeWithoutResult(status -> {
                jdbc.update("UPDATE orders SET status='REFUNDED',refunded_at=CURRENT_TIMESTAMP WHERE id=?", oldId);
                entitlements.deleteBySourceOrder(buyerId, 201L, Long.valueOf(oldId));
                // 模拟未来COIN退款的完整结果：退币、退款状态和删权益同事务。
                jdbc.update("UPDATE users SET balance_cents=balance_cents+990 WHERE id=?", buyerId);
            });
        } else {
            jdbc.update("UPDATE orders SET status='CANCELED',canceled_at=CURRENT_TIMESTAMP WHERE id=?", oldId);
        }
        String newId = create("201").path("id").asText();
        pay(newId, token, 200);
        error(pay(oldId, token, 409), "ORDER_NOT_PAYABLE");
        assertEquals(state, jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, oldId));
        assertEquals(Long.valueOf(newId), jdbc.queryForObject("SELECT source_order_id FROM entitlements", Long.class));
        assertEquals(1, count("entitlements"));
    }

    /** JUnit验证他人的订单统一404，匿名或已停用账号401，不能产生付款副作用。 */
    @Test
    void orderOwnershipAndAuthenticationAreEnforced() throws Exception {
        String id = create("201").path("id").asText();
        error(pay(id, otherToken, 404), "ORDER_NOT_FOUND");
        error(pay(id, null, 401), "SESSION_EXPIRED");
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", buyerId);
        error(pay(id, token, 401), "SESSION_EXPIRED");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM orders", String.class));
        assertEquals(0, count("entitlements"));
    }

    /** JUnit验证不能映射或不存在的订单ID返回404，而非执行付款。
     * @param id 不存在、非数字或超出数据库Long范围的外部订单ID
     */
    @ParameterizedTest
    @ValueSource(strings = {"99999999", "no-order", "0", "9999999999999999999"})
    void missingOrderReturnsNotFound(String id) throws Exception {
        error(pay(id, token, 404), "ORDER_NOT_FOUND");
        assertEquals(0, count("orders"));
    }

    /** JUnit验证付款后权益缺失时报告冲突，不自动补发。 */
    @Test
    void paidWithoutEntitlementIsRejected() throws Exception {
        String id = create("201").path("id").asText();
        pay(id, token, 200);
        jdbc.update("DELETE FROM entitlements");
        error(pay(id, token, 409), "PURCHASE_STATE_CONFLICT");
        assertEquals(0, count("entitlements"));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders", String.class));
    }

    /** JUnit验证已付款订单对应的权益若来源错误，不能当成正常重复付款返回。 */
    @Test
    void paidWithDifferentEntitlementSourceIsRejected() throws Exception {
        String id = create("201").path("id").asText();
        pay(id, token, 200);
        jdbc.update("""
                INSERT INTO orders(user_id,wallpaper_id,title_snapshot,amount_cents,currency,status,
                                   created_at,canceled_at,updated_at)
                VALUES(?,201,'旧订单',990,'ZLC','CANCELED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, buyerId);
        Long wrongSource = jdbc.queryForObject("SELECT id FROM orders WHERE status='CANCELED'", Long.class);
        jdbc.update("UPDATE entitlements SET source_order_id=?", wrongSource);
        error(pay(id, token, 409), "PURCHASE_STATE_CONFLICT");
        assertEquals(wrongSource, jdbc.queryForObject("SELECT source_order_id FROM entitlements", Long.class));
    }

    /** JUnit模拟已认证后账号才被停用，直接进入Service验证拿锁后的账号状态复查。 */
    @Test
    void buyerDisabledAfterAuthenticationIsRecheckedUnderLock() throws Exception {
        String id = create("201").path("id").asText();
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", buyerId);
        BaseContext.setCurrentId(buyerId);
        try {
            BusinessException failure = assertThrows(BusinessException.class, () -> service.payOrder(id));
            assertEquals("SESSION_EXPIRED", failure.getCode());
            assertEquals(401, failure.getStatus().value());
        } finally { BaseContext.removeCurrentId(); }
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM orders", String.class));
        assertEquals(0, count("entitlements"));
    }

    /** JUnit人为制造待付款却已有权益的错误记录，验证不会再次付款或插入重复权益。 */
    @Test
    void pendingWithEntitlementIsRejected() throws Exception {
        String id = create("201").path("id").asText();
        jdbc.update("""
                INSERT INTO entitlements(user_id,wallpaper_id,source_order_id,granted_at,created_at,updated_at)
                VALUES(?,201,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, buyerId, id);
        error(pay(id, token, 409), "PURCHASE_STATE_CONFLICT");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM orders", String.class));
        assertNull(jdbc.queryForObject("SELECT paid_at FROM orders", Timestamp.class));
        assertEquals(1, count("entitlements"));
    }

    /** JUnit让权益INSERT抛出数据库异常，验证已执行的订单UPDATE也回滚，恢复后可重试。 */
    @Test
    void entitlementFailureRollsBackPaymentAndRetrySucceeds() throws Exception {
        String id = create("201").path("id").asText();
        doThrow(new DataAccessResourceFailureException("test payment failure")).when(entitlements).insert(any());
        error(pay(id, token, 503), "SERVICE_UNAVAILABLE");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM orders", String.class));
        assertNull(jdbc.queryForObject("SELECT paid_at FROM orders", Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT payment_method FROM orders", String.class));
        assertEquals(0, count("entitlements"));
        assertEquals(10000L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
        reset(entitlements);
        pay(id, token, 200);
        assertEquals(1, count("entitlements"));
    }

    /** JUnit验证注册余额为0，登录与/me返回字符串余额；余额只能来自服务端。 */
    @Test
    void registrationStartsAtZeroAndCurrentUserReturnsActualBalance() throws Exception {
        String freshToken = login("coin_zero_buyer");
        MvcResult fresh = mvc.perform(get("/api/v1/me").contextPath("/api/v1")
                .header("Authorization", "Bearer " + freshToken)).andExpect(status().isOk()).andReturn();
        assertEquals("0", data(fresh).path("balanceCents").asText());
        assertTrue(data(fresh).path("balanceCents").isTextual());
        String id = create("201").path("id").asText();
        pay(id, token, 200);
        MvcResult current = mvc.perform(get("/api/v1/me").contextPath("/api/v1")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        assertEquals("9010", data(current).path("balanceCents").asText());
    }

    /** JUnit验证余额差1时不改变任何购买状态，补到刚好足够后允许付款且余额归零。 */
    @Test
    void insufficientBalanceDoesNotPayAndExactBalanceCanRetry() throws Exception {
        String id = create("201").path("id").asText();
        jdbc.update("UPDATE users SET balance_cents=989 WHERE id=?", buyerId);
        Timestamp updatedAt = jdbc.queryForObject("SELECT updated_at FROM orders", Timestamp.class);
        error(pay(id, token, 409), "INSUFFICIENT_BALANCE");
        assertEquals(989L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM orders", String.class));
        assertEquals(updatedAt, jdbc.queryForObject("SELECT updated_at FROM orders", Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT paid_at FROM orders", Timestamp.class));
        assertEquals(0, count("entitlements"));
        jdbc.update("UPDATE users SET balance_cents=990 WHERE id=?", buyerId);
        pay(id, token, 200);
        assertEquals(0L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
        assertEquals(1, count("entitlements"));
    }

    /** JUnit验证扣款只更新当前买家，不改变另一个账号的余额。 */
    @Test
    void paymentDoesNotDeductAnotherUsersBalance() throws Exception {
        jdbc.update("UPDATE users SET balance_cents=750 WHERE username='payment_other'");
        String id = create("201").path("id").asText();
        pay(id, token, 200);
        assertEquals(750L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE username='payment_other'", Long.class));
        assertEquals(9010L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
    }

    /** JUnit验证迁移前已有TEST/CNY的PAID记录重复请求不追扣壁纸币、不改变历史付款方式。 */
    @Test
    void legacyPaidOrderDoesNotChargeCoinsRetroactively() throws Exception {
        String id = create("202").path("id").asText();
        jdbc.update("UPDATE orders SET amount_cents=990,currency='CNY',payment_method='TEST' WHERE id=?", id);
        JsonNode historic = data(pay(id, token, 200));
        assertEquals("CNY", historic.path("currency").asText());
        assertEquals("PAID", historic.path("status").asText());
        assertEquals("TEST", jdbc.queryForObject("SELECT payment_method FROM orders", String.class));
        assertEquals(10000L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
    }

    /** JUnit验证旧test-pay已经移除，不能通过旧入口绕过余额扣减。 */
    @Test
    void removedTestPaymentRouteReturnsNotFound() throws Exception {
        String id = create("201").path("id").asText();
        mvc.perform(post("/api/v1/orders/" + id + "/test-pay").contextPath("/api/v1")
                .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM orders", String.class));
        assertEquals(10000L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
    }

    /** JUnit对两个不同商品同时付款，余额只够一笔时只允许一笔成功，避免跨订单透支。 */
    @Test
    void concurrentDifferentOrdersCannotOverdrawBalance() throws Exception {
        jdbc.update("""
                INSERT INTO wallpapers(id,title,category_id,type,price_cents,publication_status,published_at,
                created_by,created_at,updated_at,original_object_key,original_status,cover_object_key,cover_status)
                SELECT 203,'第二张壁纸',category_id,type,price_cents,publication_status,published_at,
                created_by,created_at,updated_at,original_object_key,original_status,cover_object_key,cover_status
                FROM wallpapers WHERE id=201
                """);
        List<String> ids = List.of(create("201").path("id").asText(), create("203").path("id").asText());
        jdbc.update("UPDATE users SET balance_cents=1500 WHERE id=?", buyerId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> tasks = new ArrayList<>();
        try {
            for (String id : ids) {
                tasks.add(pool.submit(() -> {
                    BaseContext.setCurrentId(buyerId);
                    try {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("test timeout"); }
                        try { return service.payOrder(id).getStatus(); }
                        catch (BusinessException failure) { return failure.getCode(); }
                    } finally { BaseContext.removeCurrentId(); }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> task : tasks) { results.add(task.get(20, TimeUnit.SECONDS)); }
            assertTrue(results.contains("PAID"));
            assertTrue(results.contains("INSUFFICIENT_BALANCE"));
            assertEquals(510L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
            assertEquals(1, count("entitlements"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status='PENDING'", Integer.class));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** JUnit同时发出三次付款和一次下单，验证账号锁保护跨入口流程并且只授予一次权益。 */
    @Test
    void concurrentPaymentsAndCreateReuseOneOrderAndOneEntitlement() throws Exception {
        String id = create("201").path("id").asText();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderVO>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                boolean createAgain = i == 3;
                tasks.add(pool.submit(() -> {
                    BaseContext.setCurrentId(buyerId);
                    try {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("test timeout"); }
                        if (createAgain) {
                            OrderCreateDTO dto = new OrderCreateDTO();
                            dto.setWallpaperId("201");
                            return service.createOrder(dto).getOrder();
                        }
                        return service.payOrder(id);
                    } finally { BaseContext.removeCurrentId(); }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<OrderVO> task : tasks) { assertEquals(id, task.get(20, TimeUnit.SECONDS).getId()); }
            assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders", String.class));
            assertEquals(1, count("orders"));
            assertEquals(1, count("entitlements"));
            verify(entitlements, times(1)).insert(any());
            assertEquals(9010L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 注册并登录独立测试账号；由accounts调用。
     * @param username 本测试唯一的用户名
     * @return 真实AuthService返回的访问令牌
     */
    private String login(String username) {
        RegisterDTO register = new RegisterDTO();
        register.setUsername(username); register.setPassword("test-only-payment-password");
        auth.register(register);
        LoginDTO login = new LoginDTO();
        login.setUsername(username); login.setPassword("test-only-payment-password");
        return auth.login(login).getAccessToken();
    }

    /** 用真实创建接口准备订单；由本类各付款测试调用。
     * @param productId 本测试准备的201或202商品ID
     * @return 新订单的data JSON
     */
    private JsonNode create(String productId) throws Exception {
        return data(mvc.perform(post("/api/v1/orders").contextPath("/api/v1")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"wallpaperId\":\"" + productId + "\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    /** 执行无业务请求体的壁纸币付款；由本类HTTP用例调用。
     * @param id 订单路径ID
     * @param bearer 访问令牌，null表示不携带登录凭证
     * @param expected 预期HTTP状态
     * @return 已断言状态码的响应，供业务数据断言使用
     */
    private MvcResult pay(String id, String bearer, int expected) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                post("/api/v1/orders/" + id + "/pay").contextPath("/api/v1");
        if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
        return mvc.perform(request).andExpect(status().is(expected)).andReturn();
    }

    /** 读取响应data；由本类请求断言调用。
     * @param result HTTP响应
     * @return JSON中的业务data
     */
    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** 检查统一错误码和空data；由本类失败场景调用。
     * @param result HTTP响应
     * @param code 预期业务错误码
     */
    private void error(MvcResult result, String code) throws Exception {
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertEquals(code, response.path("code").asText());
        assertTrue(response.path("data").isNull());
    }

    /** 统计测试库记录；由本类断言调用，表名来自代码常量。
     * @param table orders或entitlements
     * @return 测试表记录总数
     */
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
