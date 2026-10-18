package com.zlo.wallpaper.order;

import com.zlo.wallpaper.catalog.entity.Wallpaper;
import com.zlo.wallpaper.catalog.mapper.AdminWallpaperMapper;
import com.zlo.wallpaper.catalog.mapper.WallpaperMapper;
import com.zlo.wallpaper.catalog.service.WallpaperService;
import com.zlo.wallpaper.download.mapper.DownloadMapper;
import com.zlo.wallpaper.download.service.DownloadService;
import com.zlo.wallpaper.download.vo.DownloadGrantVO;
import com.zlo.wallpaper.catalog.model.UploadedFile;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.order.dto.OrderCreateDTO;
import com.zlo.wallpaper.order.model.OrderCreateResult;
import com.zlo.wallpaper.order.service.OrderService;
import com.zlo.wallpaper.order.vo.OrderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证订单与当前权益的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "wallpaper.auth.cleanup-cron=-", "wallpaper.storage.root=E:/zlowallpaper_sever/.local-setup/order-mysql-storage"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "wallpaper.mysql.tests", matches = "true")
class OrderMySqlIT {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private OrderService service;
    @Autowired private AdminWallpaperMapper products;
    @Autowired private WallpaperMapper wallpaperMapper;
    @Autowired private WallpaperService wallpaperService;
    @Autowired private DownloadMapper downloadMapper;
    @Autowired private DownloadService downloadService;
    @Autowired private com.zlo.wallpaper.entitlement.mapper.EntitlementMapper entitlements;
    private final long buyerId = -1700000000L - ThreadLocalRandom.current().nextInt(10000000);
    private final long categoryId = buyerId - 1;
    private final String marker = "orderit_" + UUID.randomUUID().toString().substring(0, 12);
    private Long wallpaperId;

    /**
     * 准备当前测试独立的数据库或媒体夹具，避免复用上个用例的结果。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach
    void fixtures() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO users(id,username,password_hash,role) VALUES(?,?,'TEST_ONLY_NEVER_LOGIN','USER')", buyerId, marker);
            jdbc.update("INSERT INTO categories(id,name,enabled) VALUES(?,?,1)", categoryId, marker);
            jdbc.update("UPDATE users SET balance_cents=10000 WHERE id=?", buyerId);
            Wallpaper product = new Wallpaper();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            product.setTitle(marker); product.setDescription(""); product.setCategoryId(categoryId); product.setType("IMAGE");
            product.setPriceCents(0); product.setCurrency("ZLC"); product.setCreator(""); product.setPublicationStatus("PUBLISHED");
            product.setPublishedAt(now); product.setCreatedBy(buyerId); product.setCreatedAt(now); product.setUpdatedAt(now);
            product.setOriginal(file(false)); product.setCover(file(true)); products.insert(product);
            wallpaperId = product.getId();
        });
    }

    /**
     * 按唯一测试身份清理订单、权益、商品及账号，不碰真实业务记录。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @AfterEach
    void cleanupOnlyThisTestsFixtures() {
        BaseContext.removeCurrentId();
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM entitlements WHERE user_id=?", buyerId);
            jdbc.update("DELETE FROM orders WHERE user_id=?", buyerId);
            jdbc.update("DELETE FROM wallpapers WHERE category_id=? AND created_by=?", categoryId, buyerId);
            jdbc.update("DELETE FROM categories WHERE id=? AND name=?", categoryId, marker);
            jdbc.update("DELETE FROM users WHERE id=? AND username=?", buyerId, marker);
        });
    }

    /** JUnit验证真实MySQL的列表LIMIT参数及快照映射；夹具仍由本类按唯一账号清理。 */
    @Test
    void myOrdersReadsSnapshotFromMySqlAfterProductChanges() {
        OrderVO created = create().getOrder();
        jdbc.update("UPDATE wallpapers SET title='列表不应返回当前标题',price_cents=990,publication_status='WITHDRAWN' WHERE id=?", wallpaperId);
        BaseContext.setCurrentId(buyerId);
        try {
            List<OrderVO> orders = service.getMyOrders();
            assertEquals(1, orders.size());
            assertEquals(created, orders.get(0));
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    /** JUnit验证真实MySQL已购JOIN、LIMIT及停售封面权限；公开SQL仍然隐藏该商品。 */
    @Test
    void ownedWallpaperAndCoverAreScopedOnMySql() {
        create();
        jdbc.update("UPDATE wallpapers SET publication_status='WITHDRAWN' WHERE id=?", wallpaperId);
        jdbc.update("UPDATE categories SET enabled=0 WHERE id=?", categoryId);
        BaseContext.setCurrentId(buyerId);
        try {
            assertEquals(wallpaperId.toString(), wallpaperService.getMyWallpapers(new com.zlo.wallpaper.catalog.dto.OwnedWallpaperQueryDTO()).items().get(0).id());
            assertNull(wallpaperMapper.selectPublishedById(wallpaperId));
            assertNull(wallpaperMapper.selectPublicCover(wallpaperId));
            assertNotNull(wallpaperMapper.selectOwnedCover(buyerId, wallpaperId));
            assertNull(wallpaperMapper.selectOwnedCover(buyerId - 20, wallpaperId));
            assertTrue(wallpaperMapper.selectOwnedWallpapers(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 12), buyerId - 20, "", null, null).getRecords().isEmpty());
        } finally { BaseContext.removeCurrentId(); }
    }

    /** JUnit验证真实MySQL下载联合查询、真实SDK离线签名及权益删除后新授权被拒绝。 */
    @Test
    void downloadGrantAndRevokedEntitlementOnMySql() {
        create();
        jdbc.update("UPDATE wallpapers SET publication_status='WITHDRAWN' WHERE id=?", wallpaperId);
        assertNotNull(downloadMapper.selectOwnedDownloadFile(buyerId, wallpaperId));
        assertNull(downloadMapper.selectOwnedDownloadFile(buyerId - 20, wallpaperId));
        BaseContext.setCurrentId(buyerId);
        try {
            DownloadGrantVO grant = downloadService.createDownloadGrant(wallpaperId.toString());
            assertEquals(wallpaperId.toString(), grant.getWallpaperId());
            assertEquals("3", grant.getSizeBytes());
            assertTrue(grant.getUrl().startsWith("https://test-order-bucket."));
            jdbc.update("DELETE FROM entitlements WHERE user_id=?", buyerId);
            assertEquals("NOT_OWNED", assertThrows(BusinessException.class,
                    () -> downloadService.createDownloadGrant(wallpaperId.toString())).getCode());
        } finally { BaseContext.removeCurrentId(); }
    }

    /**
     * 显式启用时只读已有真实OSS文件：隔离商品引用同一对象，通过新Service签发后流式校验全部字节。
     * 调用位置：JUnit；需wallpaper.mysql.tests和download.oss.tests同时为true。
     * 不上传、不删除云对象，不改真实商品；本类AfterEach只清理隔离数据库夹具。
     */
    @Test
    @EnabledIfSystemProperty(named = "download.oss.tests", matches = "true")
    void realOssDownloadMatchesSignedGrant() throws Exception {
        Long sourceId = jdbc.queryForObject("""
                SELECT id FROM wallpapers WHERE id>0 AND original_storage_provider='OSS' AND original_status='READY'
                    AND original_object_key LIKE 'zlowallpaper/originals/%'
                    AND original_size_bytes BETWEEN 1 AND 52428800
                ORDER BY id LIMIT 1
                """, Long.class);
        jdbc.update("""
                UPDATE wallpapers target JOIN wallpapers source ON source.id=?
                SET target.type=source.type,
                    target.original_filename=source.original_filename,
                    target.original_storage_provider=source.original_storage_provider,
                    target.original_bucket=source.original_bucket,
                    target.original_object_key=source.original_object_key,
                    target.original_object_version_id=source.original_object_version_id,
                    target.original_mime_type=source.original_mime_type,
                    target.original_file_extension=source.original_file_extension,
                    target.original_size_bytes=source.original_size_bytes,
                    target.original_sha256=source.original_sha256,
                    target.original_width=source.original_width,
                    target.original_height=source.original_height,
                    target.original_fps=source.original_fps
                WHERE target.id=?
                """, sourceId, wallpaperId);
        create();
        DownloadGrantVO grant;
        BaseContext.setCurrentId(buyerId);
        try { grant = downloadService.createDownloadGrant(wallpaperId.toString()); }
        finally { BaseContext.removeCurrentId(); }

        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(grant.getUrl()))
                .timeout(java.time.Duration.ofSeconds(60)).GET().build();
        java.net.http.HttpResponse<java.io.InputStream> response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        try (java.io.InputStream input = response.body()) {
            // 不在失败信息或日志输出签名URL、访问密钥及真实文件位置。
            if (response.statusCode() != 200) {
                String xml = new String(input.readNBytes(8192), java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher code = java.util.regex.Pattern.compile("<Code>([A-Za-z0-9._-]+)</Code>").matcher(xml);
                java.util.regex.Matcher action = java.util.regex.Pattern.compile("<AuthAction>([A-Za-z:]+)</AuthAction>").matcher(xml);
                java.util.regex.Matcher ec = java.util.regex.Pattern.compile("<EC>([A-Za-z0-9-]+)</EC>").matcher(xml);
                fail("OSS HTTP " + response.statusCode() + "; code=" + (code.find() ? code.group(1) : "UNKNOWN")
                        + "; action=" + (action.find() ? action.group(1) : "UNKNOWN")
                        + "; EC=" + (ec.find() ? ec.group(1) : "UNKNOWN"));
            }
            assertEquals(200, response.statusCode());
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long size = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                size += length;
                assertTrue(size <= 52428800, "测试读取超过50MiB预算");
                sha.update(buffer, 0, length);
            }
            assertEquals(Long.parseLong(grant.getSizeBytes()), size);
            assertEquals(grant.getSha256(), java.util.HexFormat.of().formatHex(sha.digest()));
            System.out.println("Real OSS GET 200; streamed bytes=" + size + "; SHA-256 matched; no upload/delete");
        }
    }

    /**
     * 验证MySQL退款后新增权益，旧退款删除条件不能移除新权益。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void freePurchaseCreatesNewEntitlementAfterRefundAndOldRefundCannotDeleteIt() {
        OrderCreateResult first = create(); assertTrue(first.isCreated());
        assertEquals("PAID", first.getOrder().getStatus()); assertEquals(1, count("entitlements"));
        assertEquals(first.getOrder().getId(), create().getOrder().getId());
        Long oldEntitlementId = jdbc.queryForObject("SELECT id FROM entitlements WHERE user_id=?", Long.class, buyerId);
        Long oldOrderId = Long.valueOf(first.getOrder().getId());
        transactions.executeWithoutResult(status -> {
            jdbc.update("UPDATE orders SET status='REFUNDED',refunded_at=UTC_TIMESTAMP(3) WHERE id=? AND user_id=?", oldOrderId, buyerId);
            assertEquals(1, entitlements.deleteBySourceOrder(buyerId, wallpaperId, oldOrderId));
        });
        assertEquals(0, count("entitlements"));
        OrderCreateResult repurchase = create(); assertTrue(repurchase.isCreated());
        assertEquals(2, count("orders")); assertEquals(1, count("entitlements"));
        assertNotEquals(oldEntitlementId, jdbc.queryForObject("SELECT id FROM entitlements WHERE user_id=?", Long.class, buyerId));
        assertEquals(repurchase.getOrder().getId(), jdbc.queryForObject("SELECT CAST(source_order_id AS CHAR) FROM entitlements WHERE user_id=?", String.class, buyerId));
        assertEquals(0, entitlements.deleteBySourceOrder(buyerId, wallpaperId, oldOrderId));
        assertEquals(1, count("entitlements"));
    }

    /**
     * 验证MySQL待付款快照不随商品改名调价或下架改变。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void pendingSnapshotStaysStableAfterProductChanges() {
        jdbc.update("UPDATE wallpapers SET price_cents=990 WHERE id=?", wallpaperId);
        OrderCreateResult first = create(); assertEquals("PENDING", first.getOrder().getStatus());
        jdbc.update("UPDATE wallpapers SET price_cents=0,title='new',publication_status='WITHDRAWN' WHERE id=?", wallpaperId);
        assertEquals(990, create().getOrder().getAmountCents());
        assertEquals(marker, create().getOrder().getTitle());
        assertEquals(0, count("entitlements"));
    }

    /**
     * 验证MySQL唯一有效订单及权益来源外键均生效。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void databaseEnforcesOneActiveOrderAndEntitlementSourceConstraint() {
        create();
        assertThrows(DataAccessException.class, () -> transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO orders(user_id,wallpaper_id,title_snapshot,amount_cents,currency,status)
                VALUES(?,?,'重复',990,'ZLC','PENDING')
                """, buyerId, wallpaperId)));
        assertThrows(DataAccessException.class, () -> transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM entitlements WHERE user_id=?", buyerId);
            jdbc.update("INSERT INTO entitlements(user_id,wallpaper_id,source_order_id) VALUES(?,?,0)", buyerId, wallpaperId);
        }));
        assertEquals(1, count("orders")); assertEquals(1, count("entitlements"));
    }

    /**
     * 验证MySQL无请求键并发免费购买只提交一次。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void concurrentFreePurchaseWithoutKeysCommitsOnce() throws Exception { concurrent(false); }

    /**
     * 验证MySQL无请求键并发收费下单只提交一次且不授予权益。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void concurrentPaidPurchaseWithoutKeysCommitsOnce() throws Exception { concurrent(true); }

    /**
     * JUnit在真实MySQL验证付款使用订单快照，重复付款保持时间和权益编号。
     * 测试只操作本类创建的用户、商品与订单，AfterEach精确清理。
     */
    @Test
    void coinPaymentKeepsSnapshotAndRepeatedPaymentDoesNotGrantAgain() {
        jdbc.update("UPDATE wallpapers SET price_cents=990 WHERE id=?", wallpaperId);
        OrderCreateResult pending = create();
        String id = pending.getOrder().getId();
        jdbc.update("UPDATE wallpapers SET price_cents=0,title='changed',publication_status='WITHDRAWN' WHERE id=?", wallpaperId);
        BaseContext.setCurrentId(buyerId);
        try {
            OrderVO paid = service.payOrder(id);
            assertEquals("PAID", paid.getStatus());
            assertEquals(990, paid.getAmountCents());
            assertEquals(marker, paid.getTitle());
            java.sql.Timestamp paidAt = jdbc.queryForObject("SELECT paid_at FROM orders WHERE id=?", java.sql.Timestamp.class, id);
            Long rightId = jdbc.queryForObject("SELECT id FROM entitlements WHERE user_id=?", Long.class, buyerId);
            assertNotNull(paidAt);
            assertEquals("COIN", jdbc.queryForObject("SELECT payment_method FROM orders WHERE id=?", String.class, id));
            assertEquals(Long.valueOf(id), jdbc.queryForObject("SELECT source_order_id FROM entitlements WHERE user_id=?", Long.class, buyerId));
            assertEquals(paidAt, jdbc.queryForObject("SELECT granted_at FROM entitlements WHERE user_id=?", java.sql.Timestamp.class, buyerId));
            assertEquals("PAID", service.payOrder(id).getStatus());
            assertEquals(paidAt, jdbc.queryForObject("SELECT paid_at FROM orders WHERE id=?", java.sql.Timestamp.class, id));
            assertEquals(rightId, jdbc.queryForObject("SELECT id FROM entitlements WHERE user_id=?", Long.class, buyerId));
            assertEquals(9010L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
            assertEquals(1, count("orders"));
            assertEquals(1, count("entitlements"));
        } finally { BaseContext.removeCurrentId(); }
    }

    /** JUnit在真实InnoDB中并发提交三次付款与一次下单，验证跨入口账号锁和唯一约束。 */
    @Test
    void concurrentPaymentsAndCreateCommitOneEntitlement() throws Exception {
        jdbc.update("UPDATE wallpapers SET price_cents=990 WHERE id=?", wallpaperId);
        String id = create().getOrder().getId();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderVO>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                boolean createAgain = i == 3;
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("test timeout"); }
                    if (createAgain) { return create().getOrder(); }
                    BaseContext.setCurrentId(buyerId);
                    try { return service.payOrder(id); }
                    finally { BaseContext.removeCurrentId(); }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<OrderVO> task : tasks) { assertEquals(id, task.get(20, TimeUnit.SECONDS).getId()); }
            assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, id));
            assertEquals(1, count("orders"));
            assertEquals(1, count("entitlements"));
            assertEquals(9010L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** JUnit在真实MySQL验证SQL余额条件：不足时不付款，补到刚好足够后余额归零。 */
    @Test
    void insufficientBalanceThenExactBalanceOnMySql() {
        jdbc.update("UPDATE wallpapers SET price_cents=990 WHERE id=?", wallpaperId);
        String id = create().getOrder().getId();
        jdbc.update("UPDATE users SET balance_cents=989 WHERE id=?", buyerId);
        BaseContext.setCurrentId(buyerId);
        try {
            BusinessException failure = assertThrows(BusinessException.class, () -> service.payOrder(id));
            assertEquals("INSUFFICIENT_BALANCE", failure.getCode());
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, id));
            assertEquals(989L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
            assertEquals(0, count("entitlements"));
            jdbc.update("UPDATE users SET balance_cents=990 WHERE id=?", buyerId);
            service.payOrder(id);
            assertEquals(0L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
        } finally { BaseContext.removeCurrentId(); }
    }

    /** JUnit让外层事务在付款后失败，验证真实数据库同时回滚余额、订单和权益。 */
    @Test
    void failedTransactionRestoresBalanceOrderAndEntitlementOnMySql() {
        jdbc.update("UPDATE wallpapers SET price_cents=990 WHERE id=?", wallpaperId);
        String id = create().getOrder().getId();
        BaseContext.setCurrentId(buyerId);
        try {
            assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
                service.payOrder(id);
                throw new IllegalStateException("test outer transaction failure");
            }));
            assertEquals(10000L, jdbc.queryForObject("SELECT balance_cents FROM users WHERE id=?", Long.class, buyerId));
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, id));
            assertNull(jdbc.queryForObject("SELECT paid_at FROM orders WHERE id=?", java.sql.Timestamp.class, id));
            assertEquals(0, count("entitlements"));
        } finally { BaseContext.removeCurrentId(); }
    }

    /** JUnit在真实InnoDB中同时支付不同订单，余额只够一笔时另一笔保持待付款。 */
    @Test
    void differentOrdersCannotOverdrawOnMySql() throws Exception {
        jdbc.update("UPDATE wallpapers SET price_cents=990 WHERE id=?", wallpaperId);
        String firstId = create().getOrder().getId();
        Wallpaper second = products.selectById(wallpaperId);
        second.setId(null);
        second.setTitle(marker + "_second");
        products.insert(second);
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setWallpaperId(second.getId().toString());
        BaseContext.setCurrentId(buyerId);
        String secondId;
        try { secondId = service.createOrder(dto).getOrder().getId(); }
        finally { BaseContext.removeCurrentId(); }
        jdbc.update("UPDATE users SET balance_cents=1500 WHERE id=?", buyerId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> tasks = new ArrayList<>();
        try {
            for (String id : List.of(firstId, secondId)) {
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
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=? AND status='PENDING'", Integer.class, buyerId));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /**
     * 同步发起多个下单线程，检查有效订单与权益数量。
     * 调用/引用位置：OrderMySqlIT.concurrentFreePurchaseWithoutKeysCommitsOnce、OrderMySqlIT.concurrentPaidPurchaseWithoutKeysCommitsOnce。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param paidProduct true测试收费商品，false测试免费商品
     */
    private void concurrent(boolean paidProduct) throws Exception {
        if (paidProduct) { jdbc.update("UPDATE wallpapers SET price_cents=990 WHERE id=?", wallpaperId); }
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderCreateResult>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("test timeout"); }
                    return create();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            int newlyCreated = 0; String id = null;
            for (Future<OrderCreateResult> task : tasks) {
                OrderCreateResult result = task.get(20, TimeUnit.SECONDS);
                if (result.isCreated()) { newlyCreated++; }
                if (id == null) { id = result.getOrder().getId(); }
                assertEquals(id, result.getOrder().getId());
            }
            assertEquals(1, newlyCreated); assertEquals(1, count("orders")); assertEquals(paidProduct ? 0 : 1, count("entitlements"));
            
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    /**
     * 执行本测试的创建请求或业务调用，并返回订单/商品结果。
     * 调用/引用位置：OrderMySqlIT.concurrent、OrderMySqlIT.databaseEnforcesOneActiveOrderAndEntitlementSourceConstraint、OrderMySqlIT.freePurchaseCreatesNewEntitlementAfterRefundAndOldRefundCannotDeleteIt、OrderMySqlIT.pendingSnapshotStaysStableAfterProductChanges。
     * 输入参数：无。
     *
     * @return 订单VO与created标志；新单为true，已有权益或待付款订单复用为false
     */
    private OrderCreateResult create() {
        BaseContext.setCurrentId(buyerId);
        try {
            OrderCreateDTO dto = new OrderCreateDTO(); dto.setWallpaperId(wallpaperId.toString());
            return service.createOrder(dto);
        } finally { BaseContext.removeCurrentId(); }
    }

    /**
     * 查询本测试关注的记录数量，表名来自测试常量而不是客户端输入。
     * 调用/引用位置：OrderMySqlIT.concurrent、OrderMySqlIT.databaseEnforcesOneActiveOrderAndEntitlementSourceConstraint、OrderMySqlIT.freePurchaseCreatesNewEntitlementAfterRefundAndOldRefundCannotDeleteIt、OrderMySqlIT.pendingSnapshotStaysStableAfterProductChanges。
     *
     * @param table 本方法使用的String输入，按上面的业务用途处理
     * @return 上述处理得到的int结果；业务校验失败时通过异常返回错误
     */
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, buyerId); }

    /**
     * 构造当前测试所需的文件、上传参数或可信元数据。
     * 调用/引用位置：OrderMySqlIT.fixtures。
     *
     * @param cover 本次封面文件元数据或是否按封面限制校验的标志
     * @return 检查及上传完成的文件显示名、存储位置、哈希、大小和媒体信息
     */
    private UploadedFile file(boolean cover) {
        UploadedFile file = new UploadedFile(); file.setOriginalFilename("测试.png"); file.setStorageProvider("OSS");
        file.setBucket("test-order-bucket"); file.setObjectKey("test/order/" + UUID.randomUUID() + (cover ? "-cover.png" : ".png"));
        file.setMimeType("image/png"); file.setFileExtension("png"); file.setSizeBytes(3L); file.setSha256("a".repeat(64));
        file.setWidth(640); file.setHeight(480); file.setFps(BigDecimal.ZERO); file.setStatus("READY");
        file.setUploadedBy(buyerId); file.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); return file;
    }
}
