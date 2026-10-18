package com.zlo.wallpaper.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.entitlement.mapper.EntitlementMapper;
import com.zlo.wallpaper.order.dto.OrderCreateDTO;
import com.zlo.wallpaper.order.mapper.OrderMapper;
import com.zlo.wallpaper.order.model.OrderCreateResult;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证订单与当前权益的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-create;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Sql(scripts = {"/auth-schema.sql", "/admin-category-schema.sql", "/admin-wallpaper-schema.sql", "/order-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class OrderIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthService authService;
    @Autowired private OrderService service;
    @MockitoSpyBean private EntitlementMapper entitlementMapper;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactions;
    private String token;
    private String otherToken;
    private Long buyerId;

    /**
     * 建立本测试需要的账号与登录令牌。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeAll
    void accounts() {
        token = login("order_buyer");
        otherToken = login("order_other");
        buyerId = jdbc.queryForObject("SELECT id FROM users WHERE username='order_buyer'", Long.class);
    }

    /**
     * 准备当前测试独立的数据库或媒体夹具，避免复用上个用例的结果。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach
    void fixtures() {
        reset(entitlementMapper);

        jdbc.update("DELETE FROM entitlements");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM wallpapers");
        jdbc.update("DELETE FROM categories");
        jdbc.update("UPDATE users SET status='ACTIVE'");
        jdbc.update("INSERT INTO categories(id,name,sort_order,enabled,created_at,updated_at) VALUES(1,'测试',0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        product(100, 0, "PUBLISHED");
        product(101, 990, "PUBLISHED");
        product(102, 0, "DRAFT");
        product(103, 0, "WITHDRAWN");
        product(104, 0, "PUBLISHED");
    }

    /**
     * 验证免费下单同时生成PAID快照与当前权益，响应字段符合契约。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void freeOrderCreatesPaidSnapshotAndEntitlementInOneRequest() throws Exception {
        MvcResult response = create("{\"wallpaperId\":\"100\"}", "free-1", token, 201);
        JsonNode order = data(response);
        assertEquals(9, order.size());
        assertEquals(jdbc.queryForObject("SELECT user_id FROM orders WHERE id=?", Long.class, order.path("id").asLong()).toString(), order.path("userId").asText());
        assertEquals("0", order.path("balanceCents").asText());
        assertTrue(order.path("id").isTextual());
        assertEquals("100", order.path("wallpaperId").asText());
        assertEquals("壁纸100", order.path("title").asText());
        assertEquals("PAID", order.path("status").asText());
        assertEquals(0, order.path("amountCents").asInt());
        assertEquals("ZLC", order.path("currency").asText());
        assertTrue(order.path("createdAt").asText().endsWith("Z"));
        assertEquals(response.getResponse().getHeader("X-Request-Id"), json.readTree(response.getResponse().getContentAsString()).path("requestId").asText());
        assertEquals("FREE", jdbc.queryForObject("SELECT payment_method FROM orders", String.class));
        assertEquals(order.path("id").asLong(), jdbc.queryForObject("SELECT source_order_id FROM entitlements", Long.class));
        assertNull(BaseContext.getCurrentId());

    }

    /**
     * 验证收费商品使用服务器价格，仅创建PENDING订单。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void paidProductCreatesOnlyPendingOrderUsingServerPrice() throws Exception {
        JsonNode order = data(create("{\"wallpaperId\":\"101\"}", "paid-1", token, 201));
        assertEquals("PENDING", order.path("status").asText());
        assertEquals(990, order.path("amountCents").asInt());
        assertEquals(0, count("entitlements"));
        assertNull(jdbc.queryForObject("SELECT payment_method FROM orders", String.class));
        assertNull(jdbc.queryForObject("SELECT paid_at FROM orders", java.sql.Timestamp.class));
    }

    /**
     * 验证复用待付款订单时保留旧标题价格，不随商品调整改变。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void existingPendingOrderKeepsSnapshotAfterProductChanges() throws Exception {
        JsonNode first = data(create("{\"wallpaperId\":\"101\"}", "retry", token, 201));
        jdbc.update("UPDATE wallpapers SET price_cents=0,title='新标题',publication_status='WITHDRAWN' WHERE id=101");
        assertEquals(first, data(create("{\"wallpaperId\":\"101\"}", "retry", token, 200)));
        assertEquals(first, data(create("{\"wallpaperId\":\"101\"}", "different-key", token, 200)));
        assertEquals(1, count("orders"));

    }

    /**
     * 验证重复免费购买通过权益返回来源订单，不新增权益。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void repeatedFreePurchaseReusesEntitlementSourceOrder() throws Exception {
        JsonNode first = data(create("{\"wallpaperId\":\"100\"}", "Case", token, 201));
        assertEquals(first, data(create("{\"wallpaperId\":\"100\"}", "case", token, 200)));
        assertEquals(1, count("orders"));
        assertEquals(1, count("entitlements"));

    }

    /**
     * 验证旧客户端请求键被忽略，不限制购买另一张商品。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void legacyRequestKeyDoesNotRestrictPurchasingAnotherProduct() throws Exception {
        create("{\"wallpaperId\":\"100\"}", "legacy", token, 201);
        create("{\"wallpaperId\":\"104\"}", "legacy", token, 201);
        assertEquals(2, count("orders"));
        assertEquals(2, count("entitlements"));
    }

    /**
     * 验证不同账号分别购买，相同商品不导致账号间混用订单。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void purchasesBelongToEachBuyerSeparately() throws Exception {
        String firstId = data(create("{\"wallpaperId\":\"100\"}", "same", token, 201)).path("id").asText();
        String secondId = data(create("{\"wallpaperId\":\"100\"}", "same", otherToken, 201)).path("id").asText();
        assertNotEquals(firstId, secondId);
        assertEquals(2, count("orders"));
        assertEquals(2, count("entitlements"));
    }

    /**
     * 验证不可购买商品不会产生订单或权益。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     */
    @ParameterizedTest
    @ValueSource(strings = {"102", "103", "999", "wp-not-here", "9223372036854775808"})
    void unavailableProductDoesNotCreateAnything(String id) throws Exception {
        error(create("{\"wallpaperId\":\"" + id + "\"}", "missing", token, 404), "PRODUCT_NOT_FOUND");
        assertEquals(0, count("orders"));

    }

    /**
     * 验证分类停用不阻止已上架商品正常下单。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void categoryDisabledDoesNotHideAnAlreadyPublishedProduct() throws Exception {
        jdbc.update("UPDATE categories SET enabled=0 WHERE id=1");
        create("{\"wallpaperId\":\"100\"}", "disabled-category", token, 201);
    }

    /**
     * 验证非法JSON或客户端指定价格、身份、状态被拒绝。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param body 测试请求JSON或待解析的响应内容
     */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"wallpaperId\":null}", "{\"wallpaperId\":100}", "{\"wallpaperId\":true}",
            "{\"wallpaperId\":\"\"}", "{\"wallpaperId\":\" 100\"}", "{\"wallpaperId\":[]}",
            "{\"wallpaperId\":\"100\",\"amountCents\":0}", "{\"wallpaperId\":\"100\",\"userId\":2}",
            "{\"wallpaperId\":\"100\",\"status\":\"PAID\"}", "{broken"})
    void malformedOrClientControlledFieldsAreRejected(String body) throws Exception {
        error(create(body, "invalid", token, 400), "VALIDATION_ERROR");
        assertEquals(0, count("orders"));
    }

    /**
     * 验证下单要求有效认证，但不要求请求键。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void orderNeedsAuthenticationButNoRequestKey() throws Exception {
        create("{\"wallpaperId\":\"100\"}", null, token, 201);
        create("{\"wallpaperId\":\"100\"}", " ", token, 200);
        error(create("{\"wallpaperId\":\"100\"}", null, null, 401), "SESSION_EXPIRED");
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", buyerId);
        error(create("{\"wallpaperId\":\"100\"}", null, token, 401), "SESSION_EXPIRED");
        assertEquals(1, count("orders"));
    }

    /**
     * 验证退款删除权益并保留REFUNDED历史，重新购买产生新单新权益。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void refundDeletesEntitlementAndRepurchaseCreatesNewOrderAndEntitlement() throws Exception {
        String oldId = data(create("{\"wallpaperId\":\"100\"}", null, token, 201)).path("id").asText();
        Long oldEntitlementId = jdbc.queryForObject("SELECT id FROM entitlements", Long.class);
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE orders SET status='REFUNDED',refunded_at=CURRENT_TIMESTAMP WHERE id=?", oldId);
            assertEquals(1, entitlementMapper.deleteBySourceOrder(buyerId, 100L, Long.valueOf(oldId)));
        });
        assertEquals(0, count("entitlements"));
        JsonNode newOrder = data(create("{\"wallpaperId\":\"100\"}", null, token, 201));
        assertNotEquals(oldId, newOrder.path("id").asText());
        assertEquals(2, count("orders"));
        assertEquals(1, count("entitlements"));
        assertNotEquals(oldEntitlementId, jdbc.queryForObject("SELECT id FROM entitlements", Long.class));
        assertEquals(newOrder.path("id").asLong(), jdbc.queryForObject("SELECT source_order_id FROM entitlements", Long.class));
        assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, oldId));
        assertEquals(0, entitlementMapper.deleteBySourceOrder(buyerId, 100L, Long.valueOf(oldId)));
        assertEquals(1, count("entitlements"));
    }

    /**
     * 验证取消后可无请求键重新购买，之后重复提交复用新待付款订单。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void canceledOrderDoesNotPreventNewPurchaseWithoutKey() throws Exception {
        String oldId = data(create("{\"wallpaperId\":\"101\"}", null, token, 201)).path("id").asText();
        jdbc.update("UPDATE orders SET status='CANCELED',canceled_at=CURRENT_TIMESTAMP WHERE id=?", oldId);
        String newId = data(create("{\"wallpaperId\":\"101\"}", null, token, 201)).path("id").asText();
        assertNotEquals(oldId, newId);
        assertEquals(newId, data(create("{\"wallpaperId\":\"101\"}", "legacy", token, 200)).path("id").asText());
        assertEquals(2, count("orders"));
    }

    /**
     * 验证权益插入失败回滚订单，恢复后可以直接重试。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void entitlementInsertFailureRollsBackOrderAndAllowsRetry() throws Exception {
        doThrow(new DataAccessResourceFailureException("test failure")).when(entitlementMapper).insert(any());
        error(create("{\"wallpaperId\":\"100\"}", "rollback", token, 503), "SERVICE_UNAVAILABLE");
        assertEmptyPurchase();
        reset(entitlementMapper);
        create("{\"wallpaperId\":\"100\"}", "rollback", token, 201);
    }

    /**
     * 验证PAID订单缺少权益时返回冲突，不能再次收费。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void paidOrderWithoutEntitlementIsRejectedInsteadOfChargedAgain() throws Exception {
        create("{\"wallpaperId\":\"100\"}", null, token, 201);
        jdbc.update("DELETE FROM entitlements");
        error(create("{\"wallpaperId\":\"100\"}", null, token, 409), "PURCHASE_STATE_CONFLICT");
        assertEquals(1, count("orders"));
        assertEquals(0, count("entitlements"));
    }

    /**
     * 验证无请求键三线程并发只产生一笔订单和一条权益。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void concurrentRequestsWithoutKeysCreateOneOrderAndOneEntitlement() throws Exception { concurrent(); }

    

    /**
     * 同步发起多个下单线程，检查有效订单与权益数量。
     * 调用/引用位置：OrderIntegrationTest.concurrentRequestsWithoutKeysCreateOneOrderAndOneEntitlement。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    private void concurrent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderCreateResult>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                tasks.add(pool.submit(() -> {
                    BaseContext.setCurrentId(buyerId);
                    try {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("test timeout"); }
                        OrderCreateDTO dto = new OrderCreateDTO(); dto.setWallpaperId("100");
                        return service.createOrder(dto);
                    } finally { BaseContext.removeCurrentId(); }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int newlyCreated = 0;
            String orderId = null;
            for (Future<OrderCreateResult> task : tasks) {
                OrderCreateResult result = task.get(15, TimeUnit.SECONDS);
                if (result.isCreated()) { newlyCreated++; }
                if (orderId == null) { orderId = result.getOrder().getId(); }
                assertEquals(orderId, result.getOrder().getId());
            }
            assertEquals(1, newlyCreated);
            assertEquals(1, count("orders"));
            assertEquals(1, count("entitlements"));

        } finally { start.countDown(); pool.shutdownNow(); }
    }

    /**
     * 构造下单或商品持久化测试所需的商品实体/数据库记录。
     * 调用/引用位置：OrderIntegrationTest.fixtures。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param price 测试商品价格，单位整数分
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     */
    private void product(long id, int price, String status) {
        jdbc.update("""
                INSERT INTO wallpapers(id,title,category_id,type,price_cents,publication_status,published_at,
                created_by,created_at,updated_at,original_object_key,original_status,cover_object_key,cover_status)
                VALUES(?, ?, 1, 'VIDEO', ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                'test/video.mp4','READY','test/cover.png','READY')
                """, id, "壁纸" + id, price, status, buyerId);
    }

    /**
     * 登录或注册并登录测试账号，获取当前用例使用的令牌或业务身份。
     * 调用/引用位置：OrderIntegrationTest.accounts。
     *
     * @param username 待查询或注册的用户名
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    private String login(String username) {
        RegisterDTO register = new RegisterDTO(); register.setUsername(username); register.setPassword("test-only-password");
        authService.register(register);
        LoginDTO login = new LoginDTO(); login.setUsername(username); login.setPassword("test-only-password");
        return authService.login(login).getAccessToken();
    }

    /**
     * 执行本测试的创建请求或业务调用，并返回订单/商品结果。
     * 调用/引用位置：OrderIntegrationTest.canceledOrderDoesNotPreventNewPurchaseWithoutKey、OrderIntegrationTest.categoryDisabledDoesNotHideAnAlreadyPublishedProduct、OrderIntegrationTest.entitlementInsertFailureRollsBackOrderAndAllowsRetry、OrderIntegrationTest.existingPendingOrderKeepsSnapshotAfterProductChanges、OrderIntegrationTest.freeOrderCreatesPaidSnapshotAndEntitlementInOneRequest、OrderIntegrationTest.legacyRequestKeyDoesNotRestrictPurchasingAnotherProduct、OrderIntegrationTest.malformedOrClientControlledFieldsAreRejected、OrderIntegrationTest.orderNeedsAuthenticationButNoRequestKey、OrderIntegrationTest.paidOrderWithoutEntitlementIsRejectedInsteadOfChargedAgain、OrderIntegrationTest.paidProductCreatesOnlyPendingOrderUsingServerPrice、OrderIntegrationTest.purchasesBelongToEachBuyerSeparately、OrderIntegrationTest.refundDeletesEntitlementAndRepurchaseCreatesNewOrderAndEntitlement、OrderIntegrationTest.repeatedFreePurchaseReusesEntitlementSourceOrder、OrderIntegrationTest.unavailableProductDoesNotCreateAnything。
     *
     * @param body 测试请求JSON或待解析的响应内容
     * @param key 仅供旧客户端兼容性测试的请求键，当前创建业务忽略该值
     * @param bearer 测试请求携带的访问令牌，null表示匿名请求
     * @param expected 测试断言期望的状态或值
     * @return 已经检查HTTP状态的MockMvc响应，供后续断言解析
     */
    private MvcResult create(String body, String key, String bearer, int expected) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = post("/api/v1/orders")
                .contextPath("/api/v1").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) { request.header("Idempotency-Key", key); }
        if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
        return mvc.perform(request).andExpect(status().is(expected)).andReturn();
    }

    /**
     * 从测试响应JSON中读取data业务部分。
     * 调用/引用位置：OrderIntegrationTest.canceledOrderDoesNotPreventNewPurchaseWithoutKey、OrderIntegrationTest.existingPendingOrderKeepsSnapshotAfterProductChanges、OrderIntegrationTest.freeOrderCreatesPaidSnapshotAndEntitlementInOneRequest、OrderIntegrationTest.paidProductCreatesOnlyPendingOrderUsingServerPrice、OrderIntegrationTest.purchasesBelongToEachBuyerSeparately、OrderIntegrationTest.refundDeletesEntitlementAndRepurchaseCreatesNewOrderAndEntitlement、OrderIntegrationTest.repeatedFreePurchaseReusesEntitlementSourceOrder。
     *
     * @param result 本方法要转换或断言的执行结果
     * @return 解析后的测试JSON节点，供用例读取业务字段
     */
    private JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).path("data"); }
    /**
     * 断言指定业务错误及data为null的统一响应。
     * 调用/引用位置：OrderIntegrationTest.entitlementInsertFailureRollsBackOrderAndAllowsRetry、OrderIntegrationTest.malformedOrClientControlledFieldsAreRejected、OrderIntegrationTest.orderNeedsAuthenticationButNoRequestKey、OrderIntegrationTest.paidOrderWithoutEntitlementIsRejectedInsteadOfChargedAgain、OrderIntegrationTest.unavailableProductDoesNotCreateAnything。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param result 本方法要转换或断言的执行结果
     * @param code 统一响应使用的业务错误码
     */
    private void error(MvcResult result, String code) throws Exception {
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertEquals(code, response.path("code").asText()); assertTrue(response.path("data").isNull());
    }
    /**
     * 查询本测试关注的记录数量，表名来自测试常量而不是客户端输入。
     * 调用/引用位置：OrderIntegrationTest.assertEmptyPurchase、OrderIntegrationTest.canceledOrderDoesNotPreventNewPurchaseWithoutKey、OrderIntegrationTest.concurrent、OrderIntegrationTest.existingPendingOrderKeepsSnapshotAfterProductChanges、OrderIntegrationTest.legacyRequestKeyDoesNotRestrictPurchasingAnotherProduct、OrderIntegrationTest.malformedOrClientControlledFieldsAreRejected、OrderIntegrationTest.orderNeedsAuthenticationButNoRequestKey、OrderIntegrationTest.paidOrderWithoutEntitlementIsRejectedInsteadOfChargedAgain、OrderIntegrationTest.paidProductCreatesOnlyPendingOrderUsingServerPrice、OrderIntegrationTest.purchasesBelongToEachBuyerSeparately、OrderIntegrationTest.refundDeletesEntitlementAndRepurchaseCreatesNewOrderAndEntitlement、OrderIntegrationTest.repeatedFreePurchaseReusesEntitlementSourceOrder、OrderIntegrationTest.unavailableProductDoesNotCreateAnything。
     *
     * @param table 本方法使用的String输入，按上面的业务用途处理
     * @return 上述处理得到的int结果；业务校验失败时通过异常返回错误
     */
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    /**
     * 断言失败下单没有留下订单或权益记录。
     * 调用/引用位置：OrderIntegrationTest.entitlementInsertFailureRollsBackOrderAndAllowsRetry。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    private void assertEmptyPurchase() { assertEquals(0, count("orders")); assertEquals(0, count("entitlements"));  }
}
