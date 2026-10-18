package com.zlo.wallpaper.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.catalog.service.WallpaperService;
import com.zlo.wallpaper.catalog.dto.OwnedWallpaperQueryDTO;
import com.zlo.wallpaper.catalog.storage.OssStorage;
import com.zlo.wallpaper.catalog.storage.CosStorage;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.download.service.DownloadService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用真实过滤器、Controller、Service和H2验证已购查询、封面权限和下载授权，仅云存储为替身。
 * 创建位置：JUnit/Spring Test实例化；独立内存库不连接真实商城库，也不向云端写文件。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:owned-download;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Sql(scripts = {"/auth-schema.sql", "/admin-category-schema.sql", "/admin-wallpaper-schema.sql", "/order-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class OwnedDownloadIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private AuthService auth;
    @Autowired private WallpaperService wallpapers;
    @Autowired private DownloadService downloads;
    @MockitoBean private OssStorage storage;
    @MockitoBean private CosStorage cosStorage;
    private String token;
    private String otherToken;
    private Long buyerId;
    private Long otherId;

    /** JUnit在测试类开始时建立两个普通用户，通过真实AuthService签发令牌。 */
    @BeforeAll
    void accounts() {
        token = login("owned_buyer");
        otherToken = login("owned_other");
        buyerId = jdbc.queryForObject("SELECT id FROM users WHERE username='owned_buyer'", Long.class);
        otherId = jdbc.queryForObject("SELECT id FROM users WHERE username='owned_other'", Long.class);
    }

    /** JUnit每个用例前重置独立内存库，准备上架、停售及草稿商品和可验证参数的云替身。 */
    @BeforeEach
    void fixtures() {
        BaseContext.removeCurrentId();
        reset(storage, cosStorage);
        jdbc.update("DELETE FROM entitlements");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM wallpapers");
        jdbc.update("DELETE FROM categories");
        jdbc.update("UPDATE users SET status='ACTIVE'");
        jdbc.update("INSERT INTO categories(id,name,sort_order,enabled,created_at,updated_at) VALUES(1,'已购分类',0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        product(10, "PUBLISHED");
        product(11, "WITHDRAWN");
        product(12, "WITHDRAWN");
        product(13, "DRAFT");
        product(14, "PUBLISHED");
        when(storage.readCover(eq("private-test"), anyString(), eq(3L))).thenReturn(new byte[]{1, 2, 3});
        when(storage.createDownloadUrl(anyString(), anyString(), nullable(String.class), any()))
                .thenReturn("https://private-test.oss-cn-guangzhou.aliyuncs.com/original.mp4?test-signature=temporary");
    }

    /** JUnit验证没有权益时返回空数组、统一requestId，并清理请求线程身份。 */
    @Test
    void noEntitlementsReturnsEmptyArray() throws Exception {
        MvcResult result = mvc.perform(getRequest("/me/wallpapers", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(12)).andReturn();
        assertEquals(result.getResponse().getHeader("X-Request-Id"),
                json.readTree(result.getResponse().getContentAsString()).path("requestId").asText());
        assertNull(BaseContext.getCurrentId());
    }

    /** JUnit验证已购列表按权益排序，停售及停用分类仍可见，只返回本账号当前商品资料。 */
    @Test
    void ownedListKeepsWithdrawnProductsAndCurrentMetadataWithoutLeakingOthers() throws Exception {
        grant(buyerId, 10, "2026-10-15 10:00:00");
        grant(buyerId, 11, "2026-10-15 11:00:00");
        grant(buyerId, 14, "2026-10-15 11:00:00");
        grant(otherId, 12, "2026-10-15 12:00:00");
        jdbc.update("UPDATE categories SET enabled=0 WHERE id=1");
        jdbc.update("UPDATE wallpapers SET title='修改后的当前标题',price_cents=1234 WHERE id=11");
        JsonNode rows = data(mvc.perform(getRequest("/me/wallpapers", token).param("userId", otherId.toString()))
                .andExpect(status().isOk()).andReturn()).path("items");
        assertEquals(3, rows.size());
        assertEquals("14", rows.get(0).path("id").asText());
        assertEquals("11", rows.get(1).path("id").asText());
        assertEquals("10", rows.get(2).path("id").asText());
        assertEquals("修改后的当前标题", rows.get(1).path("title").asText());
        assertEquals(1234, rows.get(1).path("priceCents").asInt());
        assertEquals("12", data(mvc.perform(getRequest("/me/wallpapers", otherToken))
                .andExpect(status().isOk()).andReturn()).path("items").get(0).path("id").asText());
        for (JsonNode row : rows) {
            assertEquals(15, row.size());
            assertEquals("ZLC", row.path("currency").asText());
            assertTrue(row.path("sizeBytes").isTextual());
            assertTrue(row.path("coverUrl").asText().endsWith("/me/wallpapers/" + row.path("id").asText() + "/cover"));
            assertFalse(row.has("originalObjectKey"));
            assertFalse(row.has("url"));
        }
        verifyNoInteractions(storage);
    }

    /** JUnit验证公开分页、详情和封面仍不泄露未上架商品，即使请求者已经购买也不放宽公开入口。 */
    @Test
    void publicQueriesRemainPublishedOnly() throws Exception {
        grant(buyerId, 11, "2026-10-15 10:00:00");
        mvc.perform(getRequest("/wallpapers/11", token)).andExpect(status().isNotFound());
        mvc.perform(getRequest("/wallpapers/13", null)).andExpect(status().isNotFound());
        mvc.perform(getRequest("/catalog/covers/11", token)).andExpect(status().isNotFound());
        JsonNode items = data(mvc.perform(getRequest("/wallpapers", null))
                .andExpect(status().isOk()).andReturn()).path("items");
        assertEquals(2, items.size());
        for (JsonNode row : items) { assertTrue(List.of("10", "14").contains(row.path("id").asText())); }
        verifyNoInteractions(storage);
    }

    /** JUnit验证停售封面只有已购用户可读，匿名401、他人404，不使用共享缓存。 */
    @Test
    void ownedCoverRequiresMatchingEntitlementAndReturnsBytes() throws Exception {
        grant(buyerId, 11, "2026-10-15 10:00:00");
        mvc.perform(getRequest("/me/wallpapers/11/cover", token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(new byte[]{1, 2, 3}));
        mvc.perform(getRequest("/me/wallpapers/11/cover", otherToken)).andExpect(status().isNotFound());
        mvc.perform(getRequest("/me/wallpapers/11/cover", null)).andExpect(status().isUnauthorized());
        mvc.perform(getRequest("/me/wallpapers/12/cover", token)).andExpect(status().isNotFound());
        verify(storage, times(1)).readCover("private-test", "covers/11.png", 3L);
    }

    /** JUnit验证停售商品授权与当前原文件快照一致、有效期5分钟、返回字段和禁止缓存头符合协议。 */
    @Test
    void grantUsesTrustedFileAndExpiresInFiveMinutes() throws Exception {
        grant(buyerId, 11, "2026-10-15 10:00:00");
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        MvcResult response = mvc.perform(grantRequest("11", token).queryParam("objectKey", "attacker-file"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        JsonNode grant = data(response);
        assertEquals(6, grant.size());
        assertEquals("11", grant.path("wallpaperId").asText());
        assertEquals("mp4", grant.path("fileExtension").asText());
        assertEquals("34567", grant.path("sizeBytes").asText());
        assertEquals("a".repeat(64), grant.path("sha256").asText());
        assertTrue(grant.path("url").asText().startsWith("https://"));
        OffsetDateTime expiresAt = OffsetDateTime.parse(grant.path("expiresAt").asText());
        assertTrue(expiresAt.isAfter(before.plusSeconds(298)));
        assertTrue(expiresAt.isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(301)));
        verify(storage).createDownloadUrl("private-test", "originals/11.mp4", "version-11", expiresAt);
        assertNull(BaseContext.getCurrentId());
    }

    /** JUnit验证未购、仅他人拥有、不存在或不可映射商品都返回NOT_OWNED，不触发签名。
     * @param id 没有本账号权益的商品ID
     */
    @ParameterizedTest
    @ValueSource(strings = {"11", "12", "999999", "9223372036854775808", "wp-unknown"})
    void noEntitlementCannotGetSignedUrl(String id) throws Exception {
        grant(otherId, 12, "2026-10-15 10:00:00");
        mvc.perform(grantRequest(id, token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNED")).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(storage);
    }

    /** JUnit验证查询、封面和下载授权均需要登录，不受公开商品GET路径白名单影响。
     * @param path 要请求的登录保护路径
     */
    @ParameterizedTest
    @ValueSource(strings = {"/me/wallpapers", "/me/wallpapers/11/cover", "/wallpapers/11/download-grants"})
    void allPrivateEndpointsRequireLogin(String path) throws Exception {
        MockHttpServletRequestBuilder request = path.endsWith("download-grants")
                ? grantRequest("11", null) : getRequest(path, null);
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        verifyNoInteractions(storage);
    }

    /** JUnit验证禁用账号即使仍有权益和令牌也不能申请授权。 */
    @Test
    void disabledAccountIsRejected() throws Exception {
        grant(buyerId, 11, "2026-10-15 10:00:00");
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", buyerId);
        mvc.perform(grantRequest("11", token)).andExpect(status().isUnauthorized());
        mvc.perform(getRequest("/me/wallpapers", token)).andExpect(status().isUnauthorized());
        verifyNoInteractions(storage);
    }

    /** JUnit验证非法ID在业务入口统一返回400，不调用云签名。 */
    @Test
    void malformedPathIdIsRejected() throws Exception {
        mvc.perform(grantRequest("bad$id", token)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(storage);
    }

    /** JUnit验证退款删除权益后，列表、封面、新下载授权同时收回访问。 */
    @Test
    void removingEntitlementRevokesFutureReadsAndGrants() throws Exception {
        grant(buyerId, 11, "2026-10-15 10:00:00");
        mvc.perform(grantRequest("11", token)).andExpect(status().isOk());
        jdbc.update("UPDATE orders SET status='REFUNDED',refunded_at=CURRENT_TIMESTAMP WHERE user_id=?", buyerId);
        jdbc.update("DELETE FROM entitlements WHERE user_id=?", buyerId);
        mvc.perform(getRequest("/me/wallpapers", token)).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(getRequest("/me/wallpapers/11/cover", token)).andExpect(status().isNotFound());
        mvc.perform(grantRequest("11", token)).andExpect(status().isForbidden());
        verify(storage, times(1)).createDownloadUrl(anyString(), anyString(), anyString(), any());
    }

    /** JUnit验证商品换文件后，新授权使用新对象、新存储版本、新SHA和大小，不读历史文件。 */
    @Test
    void replacingFileChangesNewGrantMetadata() throws Exception {
        grant(buyerId, 11, "2026-10-15 10:00:00");
        mvc.perform(grantRequest("11", token)).andExpect(status().isOk());
        jdbc.update("UPDATE wallpapers SET original_object_key='originals/replaced.webm',original_object_version_id='version-new',original_file_extension='webm',original_size_bytes=67890,original_sha256=? WHERE id=11", "b".repeat(64));
        mvc.perform(grantRequest("11", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileExtension").value("webm"))
                .andExpect(jsonPath("$.data.sizeBytes").value("67890"))
                .andExpect(jsonPath("$.data.sha256").value("b".repeat(64)));
        verify(storage).createDownloadUrl(eq("private-test"), eq("originals/replaced.webm"), eq("version-new"), any());
    }

    /** JUnit验证未准备好的原文件不签发链接，不静默返回损坏授权。 */
    @Test
    void fileNotReadyIsExplicitConflict() throws Exception {
        grant(buyerId, 11, "2026-10-15 10:00:00");
        jdbc.update("UPDATE wallpapers SET original_status='PROCESSING' WHERE id=11");
        mvc.perform(grantRequest("11", token)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_NOT_READY"));
        verifyNoInteractions(storage);
    }

    /** JUnit验证旧本地原文件不会伪造OSS URL，明确提示未接入云下载。 */
    @Test
    void localOriginalDoesNotPretendToBeCloudDownload() throws Exception {
        grant(buyerId, 11, "2026-10-15 10:00:00");
        jdbc.update("UPDATE wallpapers SET original_storage_provider='LOCAL' WHERE id=11");
        mvc.perform(grantRequest("11", token)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_NOT_SUPPORTED"));
        verifyNoInteractions(storage);
    }

    /** JUnit验证签名配置失败沿用明确503错误，不返回成功授权，不改变权益。 */
    @Test
    void cloudConfigurationFailureDoesNotReturnSuccess() throws Exception {
        grant(buyerId, 11, "2026-10-15 10:00:00");
        when(storage.createDownloadUrl(anyString(), anyString(), anyString(), any()))
                .thenThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_CONFIGURATION_ERROR", "存储配置不可用"));
        mvc.perform(grantRequest("11", token)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OSS_CONFIGURATION_ERROR"))
                .andExpect(jsonPath("$.data").isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM entitlements", Integer.class));
    }

    /** JUnit验证两项Service直接调用缺少可信身份时拒绝执行，未增加跨层重复身份数据库查询。 */
    @Test
    void servicesRequireIdentity() {
        assertEquals(401, assertThrows(BusinessException.class, () -> wallpapers.getMyWallpapers(new OwnedWallpaperQueryDTO())).getStatus().value());
        assertEquals(401, assertThrows(BusinessException.class, () -> downloads.createDownloadGrant("11")).getStatus().value());
        verifyNoInteractions(storage);
    }

    /** JUnit验证超过200张仍可逐页取完，排序稳定、无重复、越界保留总数。 */
    @Test
    void ownedPaginationCanReadMoreThanTwoHundredProducts() throws Exception {
        jdbc.update("""
                INSERT INTO wallpapers(id,title,category_id,type,price_cents,publication_status,created_by,created_at,updated_at,
                    original_width,original_height,original_fps,original_sha256,original_size_bytes)
                SELECT x,'批量已购',1,'VIDEO',990,'WITHDRAWN',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                    1920,1080,30,REPEAT('a',64),34567 FROM SYSTEM_RANGE(500,699)
                """, buyerId);
        jdbc.update("""
                INSERT INTO orders(user_id,wallpaper_id,title_snapshot,amount_cents,status,payment_method,paid_at,created_at,updated_at)
                SELECT ?,id,title,990,'PAID','COIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                FROM wallpapers WHERE id BETWEEN 500 AND 699
                """, buyerId);
        jdbc.update("""
                INSERT INTO entitlements(user_id,wallpaper_id,source_order_id,granted_at,created_at,updated_at)
                SELECT user_id,wallpaper_id,id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM orders
                """);
        jdbc.update("UPDATE wallpapers SET description=REPEAT('壁',4000) WHERE id BETWEEN 500 AND 699");
        grant(buyerId, 10, "2026-10-15 10:00:00");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int page = 1; page <= 5; page++) {
            MvcResult response = mvc.perform(getRequest("/me/wallpapers", token)
                    .param("page", Integer.toString(page)).param("pageSize", "50"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(201))
                    .andExpect(jsonPath("$.data.page").value(page)).andReturn();
            JsonNode rows = data(response).path("items");
            assertEquals(page == 5 ? 1 : 50, rows.size());
            for (JsonNode row : rows) { assertTrue(ids.add(row.path("id").asText())); }
            assertTrue(response.getResponse().getContentAsByteArray().length < 4 * 1024 * 1024);
        }
        assertEquals(201, ids.size());
        mvc.perform(getRequest("/me/wallpapers", token).param("page", "6").param("pageSize", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(201)).andExpect(jsonPath("$.data.page").value(6));
    }

    /** 分页筛选先于LIMIT且不放宽权益范围；同名、通配符和停用分类都按真实数据处理。 */
    @Test
    void ownedFiltersAndProductOwnershipUseTheSameScopedQuery() throws Exception {
        grant(buyerId, 10, "2026-10-15 10:00:00"); grant(buyerId, 11, "2026-10-15 10:00:00");
        grant(otherId, 12, "2026-10-15 10:00:00");
        jdbc.update("UPDATE wallpapers SET title='山水100%_收藏' WHERE id=11");
        jdbc.update("UPDATE categories SET enabled=0 WHERE id=1");
        mvc.perform(getRequest("/me/wallpapers", token).param("pageSize", "1").param("keyword", "%_")
                        .param("categoryId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("11"));
        mvc.perform(getRequest("/me/wallpapers", token).param("wallpaperId", "11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(getRequest("/me/wallpapers", token).param("wallpaperId", "12"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(getRequest("/me/wallpapers", token).param("categoryId", "not-a-database-id"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(getRequest("/me/wallpapers", token).param("wallpaperId", "9223372036854775808"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    /** 分页参数在Controller绑定和Bean Validation阶段校验，不把非法值悄悄改成默认值。 */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "bad", "2147483648", ""})
    void invalidOwnedPageIsRejected(String page) throws Exception {
        mvc.perform(getRequest("/me/wallpapers", token).param("page", page))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** 每页最大50项，保证含长描述时仍能被客户端接收。 */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "51", "bad", ""})
    void invalidOwnedPageSizeIsRejected(String size) throws Exception {
        mvc.perform(getRequest("/me/wallpapers", token).param("pageSize", size))
                .andExpect(status().isBadRequest());
    }

    /** COS切换后仍以权益限制停售封面和下载，不能因更换云厂商而放宽访问范围。 */
    @Test
    void cosFilesUseCosSdkWithoutRelaxingOwnership() throws Exception {
        jdbc.update("UPDATE wallpapers SET original_storage_provider='COS',cover_storage_provider='COS' WHERE id=11");
        grant(buyerId, 11, "2026-10-15 10:00:00");
        when(cosStorage.readCover("private-test", "covers/11.png", 3L)).thenReturn(new byte[]{4,5,6});
        when(cosStorage.createDownloadUrl(eq("private-test"), eq("originals/11.mp4"), eq("version-11"), any()))
                .thenReturn("https://private-test.cos.ap-guangzhou.myqcloud.com/originals/11.mp4?test=signature");
        mvc.perform(getRequest("/me/wallpapers/11/cover", token)).andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{4,5,6}));
        mvc.perform(grantRequest("11", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.containsString("cos.ap-guangzhou")));
        mvc.perform(getRequest("/me/wallpapers/11/cover", otherToken)).andExpect(status().isNotFound());
        mvc.perform(grantRequest("11", otherToken)).andExpect(status().isForbidden());
        mvc.perform(getRequest("/catalog/covers/11", token)).andExpect(status().isNotFound());
        verify(cosStorage, times(1)).readCover("private-test", "covers/11.png", 3L);
        verify(cosStorage, times(1)).createDownloadUrl(eq("private-test"), eq("originals/11.mp4"), eq("version-11"), any());
        verifyNoInteractions(storage);
    }

    /** 注册并登录本类隔离账号，供accounts使用。
     * @param username 测试账号名
     * @return AuthService签发的accessToken
     */
    private String login(String username) {
        RegisterDTO registration = new RegisterDTO();
        registration.setUsername(username); registration.setPassword("owned-test-password-123");
        auth.register(registration);
        LoginDTO login = new LoginDTO();
        login.setUsername(username); login.setPassword(registration.getPassword());
        return auth.login(login).getAccessToken();
    }

    /** 插入带完整当前文件元数据的商品夹具，供fixtures调用。
     * @param id 商品ID
     * @param publication 商品发布状态
     * 返回：无；只写本类H2数据库。
     */
    private void product(long id, String publication) {
        jdbc.update("""
                INSERT INTO wallpapers(id,title,category_id,type,price_cents,publication_status,published_at,created_by,created_at,updated_at,
                    original_storage_provider,original_bucket,original_object_key,original_object_version_id,original_file_extension,
                    original_size_bytes,original_sha256,original_width,original_height,original_fps,original_status,
                    cover_storage_provider,cover_bucket,cover_object_key,cover_mime_type,cover_size_bytes,cover_status)
                VALUES(?, ?,1,'VIDEO',990,?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                    'OSS','private-test',?,?,'mp4',34567,REPEAT('a',64),1920,1080,30,'READY',
                    'OSS','private-test',?,'image/png',3,'READY')
                """, id, "壁纸" + id, publication, buyerId, "originals/" + id + ".mp4", "version-" + id, "covers/" + id + ".png");
    }

    /** 建立符合约束的已付款订单及当前权益，供各已购和授权测试调用。
     * @param userId 权益所属账号
     * @param productId 已购商品ID
     * @param grantedAt 固定授予时间，用于验证排序
     * 返回：无；不代表生产付款路径，也不操作真实余额。
     */
    private void grant(Long userId, long productId, String grantedAt) {
        jdbc.update("""
                INSERT INTO orders(user_id,wallpaper_id,title_snapshot,amount_cents,status,payment_method,paid_at,created_at,updated_at)
                VALUES(?,?,'下单快照',990,'PAID','COIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, userId, productId);
        Long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE user_id=? AND wallpaper_id=? AND status='PAID'", Long.class, userId, productId);
        jdbc.update("""
                INSERT INTO entitlements(user_id,wallpaper_id,source_order_id,granted_at,created_at,updated_at)
                VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, userId, productId, orderId, grantedAt);
    }

    /** 构造带正确contextPath的GET请求，由本类HTTP用例使用。
     * @param path API内路径
     * @param bearer 可选访问令牌
     * @return MockMvc请求构造器
     */
    private MockHttpServletRequestBuilder getRequest(String path, String bearer) {
        MockHttpServletRequestBuilder request = get("/api/v1" + path).contextPath("/api/v1");
        if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
        return request;
    }

    /** 构造无请求体的下载授权请求，由本类HTTP用例使用。
     * @param id 商品路径ID
     * @param bearer 可选访问令牌
     * @return MockMvc请求构造器
     */
    private MockHttpServletRequestBuilder grantRequest(String id, String bearer) {
        MockHttpServletRequestBuilder request = post("/api/v1/wallpapers/" + id + "/download-grants").contextPath("/api/v1");
        if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
        return request;
    }

    /** 读取统一响应的data字段，供成功请求断言使用。
     * @param result HTTP请求结果
     * @return data JSON节点
     */
    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
