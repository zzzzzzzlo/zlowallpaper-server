package com.zlo.wallpaper.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.catalog.storage.OssStorage;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证公开商品及封面查询的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:catalog;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql({"/catalog-schema.sql", "/catalog-data.sql"})
class CatalogIntegrationTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aP1sAAAAASUVORK5CYII=");
    private static final Path STORAGE = prepareStorage();
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoBean private OssStorage storage;

    /**
     * 为公开封面测试提供隔离的本地存储根目录。
     * 调用位置：当前源码未发现显式业务调用；保留当前接口或测试辅助用途。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param registry 本方法使用的DynamicPropertyRegistry输入，按上面的业务用途处理
     */
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("wallpaper.storage.root", STORAGE::toString);
    }

    /**
     * 准备本测试的本地图片文件和存储目录。
     * 调用/引用位置：CatalogIntegrationTest.。
     * 输入参数：无。
     *
     * @return 上述处理得到的Path结果；业务校验失败时通过异常返回错误
     */
    private static Path prepareStorage() {
        try {
            Path root = Files.createTempDirectory("zlo-catalog-test-");
            Files.createDirectories(root.resolve("covers"));
            Files.write(root.resolve("covers/catalog.png"), PNG);
            return root;
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    /**
     * 仅清理本测试建立的本地存储文件。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @AfterAll
    static void cleanFiles() throws IOException {
        Files.deleteIfExists(STORAGE.resolve("covers/catalog.png"));
        Files.deleteIfExists(STORAGE.resolve("covers"));
        Files.deleteIfExists(STORAGE);
    }

    /**
     * 验证公开分类仅启用、排序稳定且字符串ID和请求标识正确。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void categoriesAreEnabledSortedObjectsWithStringIdsAndMatchingRequestId() throws Exception {
        var response = mvc.perform(get("/api/v1/categories").contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value(""))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("3"))
                .andExpect(jsonPath("$.data[1].name").value("自然"))
                .andExpect(jsonPath("$.data[0].enabled").doesNotExist())
                .andReturn().getResponse();
        assertEquals(response.getHeader("X-Request-Id"), json.readTree(response.getContentAsString()).get("requestId").asText());
    }

    /**
     * 验证分类全停用时列表为空，但商品不被自动下架。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void disabledCategoriesProduceEmptyArrayButDoNotWithdrawProducts() throws Exception {
        jdbc.update("UPDATE categories SET enabled = 0");
        mvc.perform(get("/api/v1/categories").contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7));
        mvc.perform(get("/api/v1/wallpapers/103").contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.categoryName").value("已停用分类"));
    }

    /**
     * 验证默认公开分页只返回已上架且文件就绪商品，字段符合契约。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void defaultsReturnOnlyPublishedReadyProductsAndContractPageFields() throws Exception {
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(12))
                .andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.items.length()").value(7))
                .andExpect(jsonPath("$.data.items[0].id").value("104"))
                .andExpect(jsonPath("$.data.records").doesNotExist());
    }

    /**
     * 验证标题与分类筛选计数准确，排序稳定且无历史版本重复。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void searchAndCategoryFilterHaveAccurateCountStableOrderAndNoVersionDuplicates() throws Exception {
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1")
                        .param("keyword", " 星空 ").param("categoryId", "1").param("pageSize", "2").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("101"));
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1").param("keyword", "星空").param("pageSize", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[1].id").value("102"));
    }

    /**
     * 验证标题搜索将SQL通配符作为普通字符处理。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param keyword 按标题匹配的搜索关键词
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     */
    @ParameterizedTest
    @CsvSource({"%,104", "_,105"})
    void searchTreatsSqlWildcardsAsLiteralTitleCharacters(String keyword, String id) throws Exception {
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1").param("keyword", keyword))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id));
    }

    /**
     * 验证空筛选或纯空白关键词表示不筛选。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void emptyFiltersAndWhitespaceSearchMeanNoFilter() throws Exception {
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1")
                        .param("keyword", "   ").param("categoryId", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7));
    }

    /**
     * 验证未知分类或超出末页返回空items并保留正确总数。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void unknownCategoryAndBeyondLastPageReturnEmptyItemsWithCorrectTotal() throws Exception {
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1").param("categoryId", "nature"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1")
                        .param("page", "2147483647").param("pageSize", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2147483647))
                .andExpect(jsonPath("$.data.total").value(7)).andExpect(jsonPath("$.data.items").isEmpty());
    }

    /**
     * 验证非法分页返回400与统一错误及CORS头。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param name 分类名称或未定义JSON字段名
     * @param value 当前待读取、校验或转换的输入值
     */
    @ParameterizedTest
    @CsvSource({"page,0", "page,-1", "page,abc", "page,2147483648", "pageSize,0", "pageSize,51", "pageSize,abc"})
    void invalidPagingIs400WithErrorEnvelopeAndCors(String name, String value) throws Exception {
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1").param(name, value)
                        .header("Origin", "http://127.0.0.1:4173"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data").value(nullValue())).andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:4173"));
    }

    /**
     * 验证关键词长度按Unicode字符计数，超过上限拒绝。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void keywordLengthUsesUnicodeCharactersAndRejectsOverLimit() throws Exception {
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1").param("keyword", "🚀".repeat(100)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/wallpapers").contextPath("/api/v1").param("keyword", "星".repeat(101)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /**
     * 验证换当前文件更新元数据与哈希，不创建历史版本。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void changingCurrentFileChangesMetadataAndChecksumWithoutHistory() throws Exception {
        jdbc.update("UPDATE wallpapers SET original_width=640, original_height=480, original_size_bytes=10, original_sha256=REPEAT('a',64), original_object_key='originals/new.png' WHERE id=101");
        mvc.perform(get("/api/v1/wallpapers/101").contextPath("/api/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.width").value(640))
                .andExpect(jsonPath("$.data.sizeBytes").value("10"))
                .andExpect(jsonPath("$.data.sha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.resourceVersion").doesNotExist());
    }

    /**
     * 验证详情使用当前资源信息并隐藏内部字段。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void detailUsesCurrentResourceMetadataAndOmitsInternalFields() throws Exception {
        mvc.perform(get("/api/v1/wallpapers/101").contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("101"))
                .andExpect(jsonPath("$.data.categoryId").value("1"))
                .andExpect(jsonPath("$.data.categoryName").value("自然"))
                .andExpect(jsonPath("$.data.resourceVersion").doesNotExist())
                .andExpect(jsonPath("$.data.sha256").value("b".repeat(64)))
                .andExpect(jsonPath("$.data.sizeBytes").value("123456"))
                .andExpect(jsonPath("$.data.width").value(1920)).andExpect(jsonPath("$.data.height").value(1080))
                .andExpect(jsonPath("$.data.priceCents").value(1200))
                .andExpect(jsonPath("$.data.fps").value(0.0))
                .andExpect(jsonPath("$.data.coverUrl").value("http://127.0.0.1:8080/api/v1/catalog/covers/101"))
                .andExpect(jsonPath("$.data.objectKey").doesNotExist())
                .andExpect(jsonPath("$.data.originalAssetId").doesNotExist())
                .andExpect(jsonPath("$.data.publicationStatus").doesNotExist());
    }

    /**
     * 验证大ID和文件字节数保持十进制字符串精度。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void largeIdsAndFileSizesRemainDecimalStrings() throws Exception {
        mvc.perform(get("/api/v1/wallpapers/9007199254740993").contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("9007199254740993"));
        mvc.perform(get("/api/v1/wallpapers/106").contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sizeBytes").value("10737418240"))
                .andExpect(jsonPath("$.data.type").value("VIDEO")).andExpect(jsonPath("$.data.fps").value(30.0));
    }

    /**
     * 验证未上架、下架或不存在商品返回404。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     */
    @ParameterizedTest
    @ValueSource(strings = {"201", "202", "203", "999", "wp-1001", "99999999999999999999", "0"})
    void unavailableOrNonexistentProductsAre404(String id) throws Exception {
        mvc.perform(get("/api/v1/wallpapers/" + id).contextPath("/api/v1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    /**
     * 验证非法路径ID返回400，不触发数据库转换异常。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void malformedPathIdIs400InsteadOfDatabaseConversionError() throws Exception {
        mvc.perform(get("/api/v1/wallpapers/bad$id").contextPath("/api/v1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /**
     * 验证公开封面URL直接返回图片字节，不依赖重定向。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void publicCoverUrlServesActualImageBytesWithoutRedirect() throws Exception {
        var result = mvc.perform(get("/api/v1/wallpapers/101").contextPath("/api/v1"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String url = json.readTree(result.getContentAsString()).at("/data/coverUrl").asText();
        String path = java.net.URI.create(url).getPath();
        mvc.perform(get(path).contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG)).andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    /**
     * 验证草稿封面和原文件不能通过公开封面入口读取。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     */
    @ParameterizedTest
    @ValueSource(strings = {"201", "202", "400", "999"})
    void draftOnlyCoversAndOriginalAssetsCannotBeReadPublicly(String id) throws Exception {
        mvc.perform(get("/api/v1/catalog/covers/" + id).contextPath("/api/v1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * 验证OSS公开封面只读取已上架商品的封面位置。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void ossCoverReadsOnlyPublishedProductsCoverLocation() throws Exception {
        jdbc.update("UPDATE wallpapers SET cover_storage_provider='OSS', cover_bucket='private-bucket' WHERE id=101");
        when(storage.readCover("private-bucket", "covers/catalog.png", 68L)).thenReturn(PNG);
        mvc.perform(get("/api/v1/catalog/covers/101").contextPath("/api/v1"))
                .andExpect(status().isOk()).andExpect(content().bytes(PNG));
        verify(storage).readCover("private-bucket", "covers/catalog.png", 68L);
        clearInvocations(storage);
        jdbc.update("UPDATE wallpapers SET publication_status='DRAFT' WHERE id=101");
        // JDBC 更新不通知 MyBatis；清理本测试外层事务的一级缓存，模拟下一次独立请求。
        sqlSession.clearCache();
        mvc.perform(get("/api/v1/catalog/covers/101").contextPath("/api/v1"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    /**
     * 验证数据库中的越界本地路径与缺失文件被拒绝。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void storedPathTraversalAndMissingFilesAreRejected() throws Exception {
        jdbc.update("UPDATE wallpapers SET cover_object_key = '../private.png' WHERE id = 101");
        mvc.perform(get("/api/v1/catalog/covers/101").contextPath("/api/v1"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE wallpapers SET cover_object_key = 'covers/missing.png' WHERE id = 101");
        mvc.perform(get("/api/v1/catalog/covers/101").contextPath("/api/v1"))
                .andExpect(status().isNotFound());
    }
}
