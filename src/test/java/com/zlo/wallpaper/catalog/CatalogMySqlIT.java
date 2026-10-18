package com.zlo.wallpaper.catalog;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zlo.wallpaper.catalog.mapper.CategoryMapper;
import com.zlo.wallpaper.catalog.mapper.WallpaperMapper;
import com.zlo.wallpaper.catalog.model.WallpaperRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.mybatis.spring.SqlSessionTemplate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证公开商品及封面查询的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "catalog.mysql.tests", matches = "true")
@Transactional
class CatalogMySqlIT {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private WallpaperMapper wallpaperMapper;
    @Autowired private SqlSessionTemplate sqlSession;

    private final long base = -1100000000L - ThreadLocalRandom.current().nextInt(1000000);
    private final String marker = "catalog-sql-" + UUID.randomUUID().toString().substring(0, 8);

    /**
     * 在测试回滚事务中建立公开商品查询所需的夹具。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach
    void insertTransactionFixtures() {
        jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, ?, ?, 'ADMIN')",
                base, marker, "TEST_ONLY_NEVER_LOGIN");
        jdbc.update("INSERT INTO categories (id, name, sort_order, enabled) VALUES (?, ?, 0, 1), (?, ?, 1, 0)",
                base - 1, marker, base - 2, marker + "-disabled");
        insertWallpaper(base - 30, base - 1, "100%" + marker);
        insertWallpaper(base - 31, base - 2, marker + "第二张");
    }

    /**
     * 向测试数据库插入指定状态商品，验证查询或引用保护。
     * 调用/引用位置：CatalogMySqlIT.insertTransactionFixtures。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param categoryId 分类数据库ID或外部分类筛选ID
     * @param title 本方法使用的String输入，按上面的业务用途处理
     */
    private void insertWallpaper(long id, long categoryId, String title) {
        jdbc.update("""
                INSERT INTO wallpapers (id, title, category_id, type, price_cents, created_by, publication_status, published_at,
                    original_filename, original_storage_provider, original_object_key, original_mime_type, original_file_extension,
                    original_size_bytes, original_sha256, original_width, original_height, original_fps, original_status,
                    original_uploaded_by, original_uploaded_at,
                    cover_filename, cover_storage_provider, cover_object_key, cover_mime_type, cover_file_extension,
                    cover_size_bytes, cover_sha256, cover_width, cover_height, cover_fps, cover_status,
                    cover_uploaded_by, cover_uploaded_at)
                VALUES (?, ?, ?, 'IMAGE', 100, ?, 'PUBLISHED', '2026-10-10 10:00:00',
                    'original.png','LOCAL','test-only/original.png','image/png','png',123456,REPEAT('a',64),1920,1080,0,'READY',?,UTC_TIMESTAMP(3),
                    'cover.png','LOCAL','test-only/cover.png','image/png','png',68,REPEAT('b',64),1,1,0,'READY',?,UTC_TIMESTAMP(3))
                """, id, title, categoryId, base, base, base);
    }

    /**
     * 验证MySQL内嵌文件元数据和发布约束生效。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void inlineFileMetadataAndPublicationConstraintsAreEnforced() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('assets','wallpaper_resources')", Integer.class));
        assertCheckViolation("UPDATE wallpapers SET original_object_key=NULL WHERE id=?");
        assertCheckViolation("UPDATE wallpapers SET original_status='PROCESSING' WHERE id=?");
        assertCheckViolation("UPDATE wallpapers SET cover_size_bytes=4194305 WHERE id=?");
        assertCheckViolation("UPDATE wallpapers SET original_size_bytes=10737418241 WHERE id=?");
        assertCheckViolation("UPDATE wallpapers SET original_sha256='invalid' WHERE id=?");
        assertCheckViolation("UPDATE wallpapers SET original_storage_provider='OSS', original_bucket=NULL WHERE id=?");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE wallpapers SET original_uploaded_by=? WHERE id=?", Long.MIN_VALUE, base - 30));
        jdbc.update("UPDATE wallpapers SET original_width=640, original_sha256=REPEAT('c',64), original_object_key='test-only/replaced.png' WHERE id=?", base - 30);
        sqlSession.clearCache();
        WallpaperRow changed = wallpaperMapper.selectPublishedById(base - 30);
        assertEquals(640, changed.width());
        assertEquals("c".repeat(64), changed.sha256());
    }

    /**
     * 断言真实MySQL以CHECK约束错误拒绝非法数据。
     * 调用/引用位置：CatalogMySqlIT.inlineFileMetadataAndPublicationConstraintsAreEnforced。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param sql 本方法使用的String输入，按上面的业务用途处理
     */
    private void assertCheckViolation(String sql) {
        org.springframework.dao.DataAccessException exception = assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update(sql, base - 30));
        java.sql.SQLException cause = assertInstanceOf(java.sql.SQLException.class, exception.getMostSpecificCause());
        assertEquals(3819, cause.getErrorCode());
    }

    /**
     * 验证MySQL分页关联当前文件并保留停用分类中的已上架商品。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void mysqlPaginationJoinsCurrentFileAndKeepsDisabledCategoryProducts() {
        assertTrue(categoryMapper.selectEnabled().stream().anyMatch(row -> row.id() == base - 1));
        assertFalse(categoryMapper.selectEnabled().stream().anyMatch(row -> row.id() == base - 2));
        var page = new Page<WallpaperRow>(2, 1);
        page.setOptimizeJoinOfCountSql(false);
        var result = wallpaperMapper.selectPublishedPage(page, marker, null);
        assertEquals(2, result.getTotal());
        assertEquals(1, result.getRecords().size());
        var row = result.getRecords().getFirst();
        assertEquals(base - 31, row.id());
        assertEquals("a".repeat(64), row.sha256());
        assertEquals(123456L, row.sizeBytes());
        assertEquals(1920, row.width());
        assertEquals(marker + "-disabled", row.categoryName());
        assertEquals(1L, wallpaperMapper.selectPublishedPage(new Page<>(1, 12), "!%" + marker, null).getTotal());
        jdbc.update("UPDATE wallpapers SET publication_status = 'WITHDRAWN' WHERE id = ?", base - 31);
        // JDBC 写入绕过了 Mapper 的缓存刷新，测试中需显式清理事务内缓存。
        sqlSession.clearCache();
        assertNull(wallpaperMapper.selectPublishedById(base - 31));
        assertEquals(1, wallpaperMapper.selectPublishedPage(new Page<>(1, 12), marker, null).getTotal());
    }
}
