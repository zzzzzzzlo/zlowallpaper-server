package com.zlo.wallpaper.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 通过可推进的服务器时钟验证截止边界，不依赖sleep，也不连接真实商城库。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:order-lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = {"/auth-schema.sql", "/admin-category-schema.sql", "/admin-wallpaper-schema.sql", "/order-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class OrderLifecycleIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthService auth;
    @Autowired private OrderService service;
    @MockitoBean private Clock clock;
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private String token;
    private String otherToken;
    private long buyerId;

    @BeforeEach
    void fixtures() {
        time.set(Instant.parse("2026-11-02T04:00:00Z"));
        when(clock.instant()).thenAnswer(invocation -> time.get());
        jdbc.update("DELETE FROM entitlements"); jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM wallpapers"); jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM sessions"); jdbc.update("DELETE FROM users");
        token = account("lifecycle_buyer"); otherToken = account("lifecycle_other");
        buyerId = jdbc.queryForObject("SELECT id FROM users WHERE username='lifecycle_buyer'", Long.class);
        jdbc.update("UPDATE users SET balance_cents=10000");
        jdbc.update("INSERT INTO categories(id,name,sort_order,enabled,created_at,updated_at) VALUES(1,'生命周期测试',0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        for (int id : new int[]{401, 402}) {
            jdbc.update("""
                    INSERT INTO wallpapers(id,title,category_id,type,price_cents,publication_status,published_at,
                        created_by,created_at,updated_at,original_object_key,original_status,cover_object_key,cover_status)
                    VALUES(?, '生命周期商品',1,'IMAGE',?,'PUBLISHED',CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                        'test/original.png','READY','test/cover.png','READY')
                    """, id, id == 401 ? 1200 : 0, buyerId);
        }
    }

    @Test
    void deadlineIsFixedAndFreeOrdersDoNotExpire() throws Exception {
        JsonNode pending = create("401");
        assertEquals(time.get().plusSeconds(900), Instant.parse(pending.path("expiresAt").asText()));
        time.updateAndGet(value -> value.plusSeconds(120));
        JsonNode reused = create("401");
        assertEquals(pending.path("id"), reused.path("id"));
        assertEquals(pending.path("expiresAt"), reused.path("expiresAt"));
        JsonNode free = create("402"); assertTrue(free.path("expiresAt").isNull());
        time.updateAndGet(value -> value.plusSeconds(10000));
        assertEquals(1, service.closeExpiredOrders());
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, free.path("id").asLong()));
        assertEquals(1, count("entitlements"));
    }

    @Test
    void cancelIsOwnedIdempotentAndDoesNotChargeOrGrant() throws Exception {
        String id = create("401").path("id").asText();
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").contextPath("/api/v1")
                .header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
        JsonNode canceled = data(cancel(id));
        assertEquals("CANCELED", canceled.path("status").asText());
        assertEquals("USER", canceled.path("cancelReason").asText());
        time.updateAndGet(value -> value.plusSeconds(20));
        assertEquals(canceled, data(cancel(id)));
        mvc.perform(post("/api/v1/orders/" + id + "/pay").contextPath("/api/v1")
                .header("Authorization", "Bearer " + token)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
        assertEquals(10000L, balance()); assertEquals(0, count("entitlements"));
        assertNotEquals(id, create("401").path("id").asText());
    }

    @Test
    void paymentAtExactDeadlineCommitsTimeoutBeforeReturning409() throws Exception {
        JsonNode pending = create("401"); String id = pending.path("id").asText();
        time.set(Instant.parse(pending.path("expiresAt").asText()));
        mvc.perform(post("/api/v1/orders/" + id + "/pay").contextPath("/api/v1")
                .header("Authorization", "Bearer " + token)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_EXPIRED")).andExpect(jsonPath("$.data").isEmpty());
        assertEquals("CANCELED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, Long.valueOf(id)));
        assertEquals("TIMEOUT", jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id=?", String.class, Long.valueOf(id)));
        assertEquals(10000L, balance()); assertEquals(0, count("entitlements"));
        JsonNode again = data(cancel(id)); assertEquals("TIMEOUT", again.path("cancelReason").asText());
    }

    @Test
    void newPurchaseClosesExpiredSnapshotAndUsesCurrentPrice() throws Exception {
        JsonNode old = create("401");
        jdbc.update("UPDATE wallpapers SET price_cents=2500 WHERE id=401");
        time.set(Instant.parse(old.path("expiresAt").asText()));
        JsonNode next = create("401");
        assertNotEquals(old.path("id"), next.path("id")); assertEquals(2500, next.path("amountCents").asInt());
        assertEquals("TIMEOUT", jdbc.queryForObject("SELECT cancel_reason FROM orders WHERE id=?", String.class, old.path("id").asLong()));
        assertEquals(1200, jdbc.queryForObject("SELECT amount_cents FROM orders WHERE id=?", Integer.class, old.path("id").asLong()));
    }

    @Test
    void scheduledCloseIsRepeatableAndNeverOverwritesPaidOrders() throws Exception {
        JsonNode pending = create("401"); String id = pending.path("id").asText();
        time.set(Instant.parse(pending.path("expiresAt").asText()).minusMillis(1));
        mvc.perform(post("/api/v1/orders/" + id + "/pay").contextPath("/api/v1")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        time.updateAndGet(value -> value.plusSeconds(900));
        assertEquals(0, service.closeExpiredOrders());
        cancel(id, 409); assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders", String.class));
        assertEquals(8800L, balance()); assertEquals(1, count("entitlements"));
        mvc.perform(post("/api/v1/orders/" + id + "/pay").contextPath("/api/v1")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertEquals(8800L, balance());
    }

    @Test
    void deadlineTaskClosesOnlyExpiredPendingAndCancellationKeepsTimeoutReason() throws Exception {
        JsonNode pending = create("401");
        time.set(Instant.parse(pending.path("expiresAt").asText()));
        assertEquals(1, service.closeExpiredOrders()); assertEquals(0, service.closeExpiredOrders());
        JsonNode canceled = data(cancel(pending.path("id").asText()));
        assertEquals("TIMEOUT", canceled.path("cancelReason").asText());
        assertEquals(0, count("entitlements")); assertEquals(10000L, balance());
    }

    @Test
    void paymentAndCancellationRaceHaveOneFinalState() throws Exception {
        String id = create("401").path("id").asText();
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> paid = pool.submit(() -> concurrent(start, id, true));
            Future<String> canceled = pool.submit(() -> concurrent(start, id, false));
            start.countDown(); String payResult = paid.get(15, TimeUnit.SECONDS), cancelResult = canceled.get(15, TimeUnit.SECONDS);
            String status = jdbc.queryForObject("SELECT status FROM orders", String.class);
            if ("PAID".equals(status)) {
                assertEquals("PAID", payResult); assertEquals("ORDER_NOT_CANCELABLE", cancelResult);
                assertEquals(8800L, balance()); assertEquals(1, count("entitlements"));
            } else {
                assertEquals("CANCELED", status); assertEquals("ORDER_NOT_PAYABLE", payResult); assertEquals("CANCELED", cancelResult);
                assertEquals(10000L, balance()); assertEquals(0, count("entitlements"));
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void timeoutTaskPaymentAndCancellationRaceNeverChargeExpiredOrder() throws Exception {
        JsonNode pending = create("401"); String id = pending.path("id").asText();
        time.set(Instant.parse(pending.path("expiresAt").asText()));
        ExecutorService pool = Executors.newFixedThreadPool(3); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> paid = pool.submit(() -> concurrent(start, id, true));
            Future<String> canceled = pool.submit(() -> concurrent(start, id, false));
            Future<Integer> closed = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return service.closeExpiredOrders(); });
            start.countDown(); assertEquals("CANCELED", paid.get(15, TimeUnit.SECONDS));
            assertEquals("CANCELED", canceled.get(15, TimeUnit.SECONDS)); assertTrue(closed.get(15, TimeUnit.SECONDS) <= 1);
            assertEquals("TIMEOUT", jdbc.queryForObject("SELECT cancel_reason FROM orders", String.class));
            assertEquals(10000L, balance()); assertEquals(0, count("entitlements"));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void missingAndAnonymousCancellationAreRejected() throws Exception {
        mvc.perform(post("/api/v1/orders/999/cancel").contextPath("/api/v1")).andExpect(status().isUnauthorized());
        cancel("999", 404);
    }

    private String concurrent(CountDownLatch start, String id, boolean pay) throws Exception {
        start.await(5, TimeUnit.SECONDS); BaseContext.setCurrentId(buyerId);
        try { return (pay ? service.payOrder(id) : service.cancelOrder(id)).getStatus(); }
        catch (BusinessException failure) { return failure.getCode(); }
        finally { BaseContext.removeCurrentId(); }
    }

    private String account(String username) {
        RegisterDTO register = new RegisterDTO(); register.setUsername(username); register.setPassword("Lifecycle123!"); auth.register(register);
        LoginDTO login = new LoginDTO(); login.setUsername(username); login.setPassword("Lifecycle123!");
        return auth.login(login).getAccessToken();
    }

    private JsonNode create(String id) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/orders").contextPath("/api/v1").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("wallpaperId", id))))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return data(result);
    }

    private MvcResult cancel(String id) throws Exception { return cancel(id, 200); }
    private MvcResult cancel(String id, int status) throws Exception {
        return mvc.perform(post("/api/v1/orders/" + id + "/cancel").contextPath("/api/v1")
                .header("Authorization", "Bearer " + token)).andExpect(status().is(status)).andReturn();
    }
    private JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()).path("data"); }
    private long balance() { return jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
