package com.zlo.wallpaper.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.catalog.dto.CategoryCreateDTO;
import com.zlo.wallpaper.catalog.dto.CategoryUpdateDTO;
import com.zlo.wallpaper.catalog.service.CategoryService;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证管理员分类管理的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:admin-category;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Sql(scripts = {"/auth-schema.sql", "/admin-category-schema.sql", "/catalog-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AdminCategoryIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthService authService;
    @Autowired private CategoryService categoryService;
    private String adminToken;
    private String userToken;

    /**
     * 创建测试需要的管理员与普通账号，并缓存合法访问令牌。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeAll
    void createTestAccounts() {
        adminToken = registerAndLogin("category_admin", "ADMIN");
        userToken = registerAndLogin("category_user", "USER");
    }

    /**
     * 重置分类及商品夹具，为每个用例建立相同初始状态。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach
    void resetCategoryFixtures() {
        jdbc.update("DELETE FROM wallpapers");
        jdbc.update("DELETE FROM categories");
        jdbc.execute("ALTER TABLE categories ALTER COLUMN id RESTART WITH 1000");
        jdbc.update("UPDATE users SET role='ADMIN', status='ACTIVE' WHERE username='category_admin'");
        jdbc.update("""
                INSERT INTO categories (id, name, sort_order, enabled, created_at, updated_at) VALUES
                (1, '自然', 5, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                (2, 'Disabled', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                (3, '游戏', 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                (4, '未引用', 9, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        insertWallpaper(100, 1, "PUBLISHED");
        insertWallpaper(101, 1, "DRAFT");
        insertWallpaper(102, 1, "WITHDRAWN");
    }

    /**
     * 验证管理列表包含停用分类并统计所有商品状态，排序稳定。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void adminListIncludesDisabledSortedCategoriesAndCountsEveryProductState() throws Exception {
        MvcResult result = request(get("/admin/categories"), null, adminToken, 200);
        JsonNode data = body(result).path("data");
        assertEquals(4, data.size());
        assertEquals("2", data.get(0).path("id").asText());
        assertFalse(data.get(0).path("enabled").asBoolean());
        assertEquals("3", data.get(1).path("id").asText());
        assertEquals(3, data.get(2).path("wallpaperCount").asInt());
        assertEquals(0, data.get(3).path("wallpaperCount").asInt());
        assertEquals(5, data.get(0).size());
        assertEquals(result.getResponse().getHeader("X-Request-Id"), body(result).path("requestId").asText());
        assertNull(BaseContext.getCurrentId());
        JsonNode publicCategories = body(request(get("/categories"), null, null, 200)).path("data");
        assertEquals(3, publicCategories.size());
        assertEquals(2, publicCategories.get(0).size());
        assertFalse(publicCategories.get(0).has("enabled"));
    }

    /**
     * 验证空管理分类列表仍返回数组。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void emptyAdminListIsAnArray() throws Exception {
        jdbc.update("DELETE FROM wallpapers");
        jdbc.update("DELETE FROM categories");
        assertTrue(body(request(get("/admin/categories"), null, adminToken, 200)).path("data").isArray());
        assertEquals(0, body(request(get("/admin/categories"), null, adminToken, 200)).path("data").size());
    }

    /**
     * 验证新增分类整理名称、使用默认值并返回数据库生成的字符串ID。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void creationTrimsNameAppliesDefaultsAndReturnsGeneratedStringId() throws Exception {
        JsonNode data = body(request(post("/admin/categories"), "{\"name\":\"  风景  \"}", adminToken, 201)).path("data");
        assertTrue(data.path("id").isTextual());
        assertEquals("风景", data.path("name").asText());
        assertEquals(0, data.path("sortOrder").asInt());
        assertTrue(data.path("enabled").asBoolean());
        assertEquals(0, data.path("wallpaperCount").asInt());
        assertEquals(5, data.size());
        assertEquals("风景", jdbc.queryForObject("SELECT name FROM categories WHERE id=?", String.class,
                data.path("id").asLong()));
    }

    /**
     * 验证新增分类允许false、零及int32边界值。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void creationAcceptsFalseZeroAndInt32Limits() throws Exception {
        JsonNode data = body(request(post("/admin/categories"),
                "{\"name\":\"关闭\",\"sortOrder\":0,\"enabled\":false}", adminToken, 201)).path("data");
        assertFalse(data.path("enabled").asBoolean());
        request(post("/admin/categories"), "{\"name\":\"最小\",\"sortOrder\":-2147483648}", adminToken, 201);
        request(post("/admin/categories"), "{\"name\":\"最大\",\"sortOrder\":2147483647}", adminToken, 201);
    }

    /**
     * 验证包括停用分类的名称按整理后的大小写无关规则判重。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void duplicateNamesIncludeDisabledAndUseTrimmedCaseInsensitiveComparison() throws Exception {
        assertError(request(post("/admin/categories"), "{\"name\":\"  dISABLED  \"}", adminToken, 409), "CATEGORY_NAME_EXISTS");
        assertError(request(patch("/admin/categories/1"), "{\"name\":\" disabled \"}", adminToken, 409), "CATEGORY_NAME_EXISTS");
        assertEquals("自然", jdbc.queryForObject("SELECT name FROM categories WHERE id=1", String.class));
        request(patch("/admin/categories/2"), "{\"name\":\"Disabled\"}", adminToken, 200);
    }

    /**
     * 验证分类名称整理空白后按Unicode字符计数，允许64个补充字符。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void unicodeLengthIsMeasuredAfterTrimmingAndAllows64Emoji() throws Exception {
        String name = "😀".repeat(64);
        request(post("/admin/categories"), json.writeValueAsString(java.util.Map.of("name", "\u00a0" + name + "\uFEFF")), adminToken, 201);
        request(post("/admin/categories"), json.writeValueAsString(java.util.Map.of("name", name + "😀")), adminToken, 400);
        request(post("/admin/categories"), json.writeValueAsString(java.util.Map.of("name", "\u00a0\uFEFF\u3000")), adminToken, 400);
    }

    /**
     * 验证PATCH只修改实际提供字段，正确保留false和零。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void patchOnlyUpdatesProvidedFieldsAndAcceptsFalseAndZero() throws Exception {
        request(patch("/admin/categories/1"), "{\"enabled\":false}", adminToken, 200);
        assertEquals("自然", jdbc.queryForObject("SELECT name FROM categories WHERE id=1", String.class));
        assertEquals(5, jdbc.queryForObject("SELECT sort_order FROM categories WHERE id=1", Integer.class));
        JsonNode data = body(request(patch("/admin/categories/1"), "{\"sortOrder\":0}", adminToken, 200)).path("data");
        assertFalse(data.path("enabled").asBoolean());
        assertEquals(3, data.path("wallpaperCount").asInt());
        request(patch("/admin/categories/1"), "{\"name\":\" 山水 \"}", adminToken, 200);
        assertEquals("山水", jdbc.queryForObject("SELECT name FROM categories WHERE id=1", String.class));
    }

    /**
     * 验证停用分类不会下架或隐藏已上架商品。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void disablingCategoryDoesNotWithdrawOrHidePublishedWallpaper() throws Exception {
        request(patch("/admin/categories/1"), "{\"enabled\":false}", adminToken, 200);
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT publication_status FROM wallpapers WHERE id=100", String.class));
        mvc.perform(get("/api/v1/wallpapers/100").contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.categoryName").value("自然"));
        assertEquals(2, body(request(get("/categories"), null, null, 200)).path("data").size());
        request(patch("/admin/categories/1"), "{\"enabled\":true}", adminToken, 200);
        assertEquals(3, body(request(get("/categories"), null, null, 200)).path("data").size());
    }

    /**
     * 验证未引用分类删除返回空对象，重复删除为404。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void unusedCategoryDeleteReturnsEmptyObjectAndRepeatIsNotFound() throws Exception {
        assertEquals(0, body(request(delete("/admin/categories/4"), null, adminToken, 200)).path("data").size());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM categories", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM wallpapers", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM wallpapers WHERE original_object_key IS NOT NULL AND cover_object_key IS NOT NULL", Integer.class));
        assertError(request(delete("/admin/categories/4"), null, adminToken, 404), "CATEGORY_NOT_FOUND");
    }

    /**
     * 验证任意商品状态的引用都阻止分类删除。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param state 本方法使用的String输入，按上面的业务用途处理
     */
    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PUBLISHED", "WITHDRAWN"})
    void anyProductStatePreventsDeletion(String state) throws Exception {
        insertWallpaper(200, 4, state);
        assertError(request(delete("/admin/categories/4"), null, adminToken, 409), "CATEGORY_IN_USE");
        assertEquals(1, jdbc.queryForObject("SELECT enabled FROM categories WHERE id=4", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wallpapers WHERE category_id=4", Integer.class));
    }

    /**
     * 验证列表引用数过期时仍重新检查，不能据旧零计数删除。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void staleZeroCountIsNotPermissionToDelete() throws Exception {
        JsonNode snapshot = body(request(get("/admin/categories"), null, adminToken, 200)).path("data");
        assertEquals(0, snapshot.get(3).path("wallpaperCount").asInt());
        insertWallpaper(200, 4, "DRAFT");
        assertError(request(delete("/admin/categories/4"), null, adminToken, 409), "CATEGORY_IN_USE");
        assertEquals(1, body(request(get("/admin/categories"), null, adminToken, 200)).path("data").get(3).path("wallpaperCount").asInt());
    }

    /**
     * 验证非法分类创建被拒绝且没有数据库残留。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param payload 本方法使用的String输入，按上面的业务用途处理
     */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":1}", "{\"name\":true}", "{\"name\":\" \"}",
            "{\"name\":\"测试\",\"sortOrder\":\"1\"}", "{\"name\":\"测试\",\"sortOrder\":1.5}",
            "{\"name\":\"测试\",\"sortOrder\":2147483648}", "{\"name\":\"测试\",\"sortOrder\":-2147483649}",
            "{\"name\":\"测试\",\"sortOrder\":null}", "{\"name\":\"测试\",\"enabled\":1}",
            "{\"name\":\"测试\",\"enabled\":\"false\"}", "{\"name\":\"测试\",\"enabled\":null}",
            "{\"name\":\"测试\",\"id\":\"99\"}", "{\"name\":\"测试\",\"wallpaperCount\":0}",
            "{\"name\":\"测试\",\"nameValid\":true}", "{\"name\":\"测试\",\"nameProvided\":true}"})
    void invalidCreationIsRejectedWithoutInserting(String payload) throws Exception {
        assertError(request(post("/admin/categories"), payload, adminToken, 400), "VALIDATION_ERROR");
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM categories", Integer.class));
    }

    /**
     * 验证非法修改不会改变原分类。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param payload 本方法使用的String输入，按上面的业务用途处理
     */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":false}", "{\"name\":\" \"}",
            "{\"sortOrder\":null}", "{\"sortOrder\":true}", "{\"sortOrder\":\"0\"}", "{\"sortOrder\":0.0}",
            "{\"sortOrder\":2147483648}", "{\"enabled\":null}", "{\"enabled\":0}", "{\"enabled\":\"true\"}",
            "{\"id\":\"9\"}", "{\"wallpaperCount\":0}", "{\"name\":\"改变\",\"unknown\":1}",
            "{\"enabled\":true,\"changeProvided\":true}", "{\"enabled\":true,\"nameValid\":true}"})
    void invalidPatchDoesNotChangeCategory(String payload) throws Exception {
        assertError(request(patch("/admin/categories/1"), payload, adminToken, 400), "VALIDATION_ERROR");
        assertEquals("自然", jdbc.queryForObject("SELECT name FROM categories WHERE id=1", String.class));
        assertEquals(5, jdbc.queryForObject("SELECT sort_order FROM categories WHERE id=1", Integer.class));
    }

    /**
     * 验证外部合法但无法映射的分类ID返回404。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "wp-1000", "0", "9223372036854775808", "9999999"})
    void validButUnmappedIdsAreNotFound(String id) throws Exception {
        assertError(request(patch("/admin/categories/" + id), "{\"enabled\":true}", adminToken, 404), "CATEGORY_NOT_FOUND");
        assertError(request(delete("/admin/categories/" + id), null, adminToken, 404), "CATEGORY_NOT_FOUND");
    }

    /**
     * 验证非法路径ID和JSON返回400。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void malformedIdAndMalformedJsonAreBadRequests() throws Exception {
        assertError(request(delete("/admin/categories/a!b"), null, adminToken, 400), "VALIDATION_ERROR");
        assertError(request(post("/admin/categories"), "{not-json", adminToken, 400), "VALIDATION_ERROR");
    }

    /**
     * 验证分类管理四个路由都要求当前管理员身份。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void allFourRoutesRequireCurrentAdminRole() throws Exception {
        for (String token : new String[] {null, userToken}) {
            int status = token == null ? 401 : 403;
            request(get("/admin/categories"), null, token, status);
            request(post("/admin/categories"), "{\"name\":\"越权\"}", token, status);
            request(patch("/admin/categories/1"), "{\"name\":\"越权\"}", token, status);
            request(delete("/admin/categories/4"), null, token, status);
        }
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM categories", Integer.class));
        jdbc.update("UPDATE users SET role='USER' WHERE username='category_admin'");
        request(get("/admin/categories"), null, adminToken, 403);
        jdbc.update("UPDATE users SET role='ADMIN', status='DISABLED' WHERE username='category_admin'");
        request(get("/admin/categories"), null, adminToken, 401);
    }

    /**
     * 验证删除预检与权限失败响应正确保留CORS头。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void deleteCorsPreflightSucceedsAndForbiddenResponseKeepsCorsHeader() throws Exception {
        mvc.perform(options("/api/v1/admin/categories/4").contextPath("/api/v1")
                .header("Origin", "http://127.0.0.1:4173").header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:4173"));
        mvc.perform(delete("/api/v1/admin/categories/4").contextPath("/api/v1")
                .header("Origin", "http://127.0.0.1:4173").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden()).andExpect(header().exists("Access-Control-Allow-Origin"));
    }

    /**
     * 验证名称中的SQL符号通过绑定作为数据，不作为SQL执行。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void sqlCharactersInNameAreBoundAsData() throws Exception {
        String name = "' OR '1'='1";
        request(post("/admin/categories"), json.writeValueAsString(java.util.Map.of("name", name)), adminToken, 201);
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM categories", Integer.class));
    }

    /**
     * 验证并发修改不同分类字段不互相覆盖。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void concurrentPatchesPreserveBothIndependentChanges() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> rename = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                CategoryUpdateDTO dto = new CategoryUpdateDTO();
                dto.setName("并发改名");
                return categoryService.updateCategory("4", dto);
            });
            Future<?> reorder = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                CategoryUpdateDTO dto = new CategoryUpdateDTO();
                dto.setSortOrder(-10);
                return categoryService.updateCategory("4", dto);
            });
            start.countDown();
            rename.get(15, TimeUnit.SECONDS);
            reorder.get(15, TimeUnit.SECONDS);
            assertEquals("并发改名", jdbc.queryForObject("SELECT name FROM categories WHERE id=4", String.class));
            assertEquals(-10, jdbc.queryForObject("SELECT sort_order FROM categories WHERE id=4", Integer.class));
        } finally { executor.shutdownNow(); }
    }

    /**
     * 验证并发创建同名分类不会产生重复记录。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void concurrentCreatesCannotInsertDuplicateNames() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> createTogether(start));
            Future<Boolean> second = executor.submit(() -> createTogether(start));
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE name='并发名称'", Integer.class));
        } finally { executor.shutdownNow(); }
    }

    /**
     * 同时发起多个分类新增操作，验证重名保护或各自结果。
     * 调用/引用位置：AdminCategoryIntegrationTest.concurrentCreatesCannotInsertDuplicateNames。
     *
     * @param start 本方法使用的CountDownLatch输入，按上面的业务用途处理
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean createTogether(CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        CategoryCreateDTO dto = new CategoryCreateDTO();
        dto.setName("并发名称");
        try { categoryService.createCategory(dto); return true; }
        catch (BusinessException exception) { assertEquals("CATEGORY_NAME_EXISTS", exception.getCode()); return false; }
    }

    /**
     * 向测试数据库插入指定状态商品，验证查询或引用保护。
     * 调用/引用位置：AdminCategoryIntegrationTest.anyProductStatePreventsDeletion、AdminCategoryIntegrationTest.resetCategoryFixtures、AdminCategoryIntegrationTest.staleZeroCountIsNotPermissionToDelete。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param categoryId 分类数据库ID或外部分类筛选ID
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     */
    private void insertWallpaper(long id, long categoryId, String status) {
        jdbc.update("""
                INSERT INTO wallpapers (id, title, description, category_id, type, price_cents, currency, creator,
                    publication_status, published_at, original_status, original_object_key, original_width, original_height, original_fps, original_size_bytes, original_sha256, cover_status, cover_object_key)
                VALUES (?, '壁纸', '', ?, 'IMAGE', 100, 'ZLC', '', ?, CURRENT_TIMESTAMP, 'READY','originals/test.png',640,480,0,10,REPEAT('a',64),'READY','covers/test.png')
                """, id, categoryId, status);
    }

    /**
     * 注册测试账号并登录，必要时配置测试角色，返回令牌或会话。
     * 调用/引用位置：AdminCategoryIntegrationTest.createTestAccounts。
     *
     * @param username 待查询或注册的用户名
     * @param role 测试账号需要的USER或ADMIN角色
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    private String registerAndLogin(String username, String role) {
        RegisterDTO register = new RegisterDTO();
        register.setUsername(username);
        register.setPassword("CategoryTest123!");
        authService.register(register);
        jdbc.update("UPDATE users SET role=? WHERE username=?", role, username);
        LoginDTO login = new LoginDTO();
        login.setUsername(username);
        login.setPassword("CategoryTest123!");
        return authService.login(login).getAccessToken();
    }

    /**
     * 发送测试HTTP请求，携带需要的身份和参数，并断言预期状态。
     * 调用/引用位置：AdminCategoryIntegrationTest.adminListIncludesDisabledSortedCategoriesAndCountsEveryProductState、AdminCategoryIntegrationTest.allFourRoutesRequireCurrentAdminRole、AdminCategoryIntegrationTest.anyProductStatePreventsDeletion、AdminCategoryIntegrationTest.creationAcceptsFalseZeroAndInt32Limits、AdminCategoryIntegrationTest.creationTrimsNameAppliesDefaultsAndReturnsGeneratedStringId、AdminCategoryIntegrationTest.disablingCategoryDoesNotWithdrawOrHidePublishedWallpaper、AdminCategoryIntegrationTest.duplicateNamesIncludeDisabledAndUseTrimmedCaseInsensitiveComparison、AdminCategoryIntegrationTest.emptyAdminListIsAnArray、AdminCategoryIntegrationTest.invalidCreationIsRejectedWithoutInserting、AdminCategoryIntegrationTest.invalidPatchDoesNotChangeCategory、AdminCategoryIntegrationTest.malformedIdAndMalformedJsonAreBadRequests、AdminCategoryIntegrationTest.patchOnlyUpdatesProvidedFieldsAndAcceptsFalseAndZero、AdminCategoryIntegrationTest.sqlCharactersInNameAreBoundAsData、AdminCategoryIntegrationTest.staleZeroCountIsNotPermissionToDelete、AdminCategoryIntegrationTest.unicodeLengthIsMeasuredAfterTrimmingAndAllows64Emoji、AdminCategoryIntegrationTest.unusedCategoryDeleteReturnsEmptyObjectAndRepeatIsNotFound、AdminCategoryIntegrationTest.validButUnmappedIdsAreNotFound。
     *
     * @param builder 本方法使用的MockHttpServletRequestBuilder输入，按上面的业务用途处理
     * @param payload 本方法使用的String输入，按上面的业务用途处理
     * @param token 刷新令牌或访问令牌字符串，按当前方法用途处理且不得写入日志
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     * @return 已经检查HTTP状态的MockMvc响应，供后续断言解析
     */
    private MvcResult request(MockHttpServletRequestBuilder builder, String payload, String token, int status) throws Exception {
        // 各测试传入相对路径；MockMvc需显式声明部署前缀。
        String relative = builder.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI();
        builder.uri(java.net.URI.create("/api/v1" + relative)).contextPath("/api/v1");
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        if (payload != null) { builder.contentType(MediaType.APPLICATION_JSON).content(payload); }
        return mvc.perform(builder).andExpect(status().is(status)).andReturn();
    }

    /**
     * 解析测试HTTP响应的完整JSON外壳。
     * 调用/引用位置：AdminCategoryIntegrationTest.adminListIncludesDisabledSortedCategoriesAndCountsEveryProductState、AdminCategoryIntegrationTest.assertError、AdminCategoryIntegrationTest.creationAcceptsFalseZeroAndInt32Limits、AdminCategoryIntegrationTest.creationTrimsNameAppliesDefaultsAndReturnsGeneratedStringId、AdminCategoryIntegrationTest.disablingCategoryDoesNotWithdrawOrHidePublishedWallpaper、AdminCategoryIntegrationTest.emptyAdminListIsAnArray、AdminCategoryIntegrationTest.patchOnlyUpdatesProvidedFieldsAndAcceptsFalseAndZero、AdminCategoryIntegrationTest.staleZeroCountIsNotPermissionToDelete、AdminCategoryIntegrationTest.unusedCategoryDeleteReturnsEmptyObjectAndRepeatIsNotFound。
     *
     * @param result 本方法要转换或断言的执行结果
     * @return 解析后的测试JSON节点，供用例读取业务字段
     */
    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * 断言响应是指定业务错误，不把失败当作成功。
     * 调用/引用位置：AdminCategoryIntegrationTest.anyProductStatePreventsDeletion、AdminCategoryIntegrationTest.duplicateNamesIncludeDisabledAndUseTrimmedCaseInsensitiveComparison、AdminCategoryIntegrationTest.invalidCreationIsRejectedWithoutInserting、AdminCategoryIntegrationTest.invalidPatchDoesNotChangeCategory、AdminCategoryIntegrationTest.malformedIdAndMalformedJsonAreBadRequests、AdminCategoryIntegrationTest.staleZeroCountIsNotPermissionToDelete、AdminCategoryIntegrationTest.unusedCategoryDeleteReturnsEmptyObjectAndRepeatIsNotFound、AdminCategoryIntegrationTest.validButUnmappedIdsAreNotFound。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param result 本方法要转换或断言的执行结果
     * @param code 统一响应使用的业务错误码
     */
    private void assertError(MvcResult result, String code) throws Exception {
        assertEquals(code, body(result).path("code").asText());
        assertTrue(body(result).path("data").isNull());
    }
}
