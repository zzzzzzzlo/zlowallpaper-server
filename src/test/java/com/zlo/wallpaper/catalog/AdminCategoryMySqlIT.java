package com.zlo.wallpaper.catalog;

import com.zlo.wallpaper.catalog.entity.Category;
import com.zlo.wallpaper.catalog.mapper.CategoryMapper;
import com.zlo.wallpaper.catalog.model.AdminCategoryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证管理员分类管理的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "wallpaper.auth.cleanup-cron=-")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "category.mysql.tests", matches = "true")
@Transactional
class AdminCategoryMySqlIT {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CategoryMapper categoryMapper;
    private final long base = -1300000000L - ThreadLocalRandom.current().nextInt(1000000);
    private final String marker = "catit_" + UUID.randomUUID().toString().substring(0, 12);

    /**
     * 在回滚事务中准备本类专用分类、用户和商品数据。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach
    void insertRollbackFixtures() {
        jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, ?, 'TEST_ONLY_NEVER_LOGIN', 'ADMIN')",
                base, marker);
        jdbc.update("INSERT INTO categories (id, name, sort_order, enabled) VALUES (?, ?, 5, 1), (?, ?, 0, 0)",
                base - 1, marker, base - 2, marker + "_disabled");
        for (int offset = 10; offset < 13; offset++) {
            // 这里使用三条草稿验证真实外键；三种状态的接口行为另由H2集成用例覆盖。
            jdbc.update("INSERT INTO wallpapers (id, title, category_id, type, price_cents, created_by) VALUES (?, ?, ?, 'IMAGE', 100, ?)",
                    base - offset, marker + offset, base - 1, base);
        }
    }

    /**
     * 验证MySQL管理分类关联查询包含停用、零引用及完整引用数。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void adminJoinReturnsDisabledZeroCountAndCompleteReferenceCount() {
        assertEquals(3L, categoryMapper.selectAdminById(base - 1).getWallpaperCount());
        AdminCategoryRow disabled = categoryMapper.selectAdminById(base - 2);
        assertFalse(disabled.getEnabled());
        assertEquals(0L, disabled.getWallpaperCount());
        List<AdminCategoryRow> rows = categoryMapper.selectAdminCategories();
        boolean found = false;
        for (AdminCategoryRow row : rows) {
            if (row.getId() == base - 2) { found = true; }
        }
        assertTrue(found);
    }

    /**
     * 验证MySQL动态更新保留省略字段并写入false与零。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void dynamicUpdateKeepsMissingFieldsAndWritesFalseAndZero() {
        Category category = categoryMapper.selectByIdForUpdate(base - 1);
        Category changes = new Category();
        changes.setId(category.getId());
        changes.setEnabled(false);
        changes.setSortOrder(0);
        changes.setUpdatedAt(LocalDateTime.of(2026, 10, 11, 0, 0));
        categoryMapper.update(changes);
        AdminCategoryRow row = categoryMapper.selectAdminById(base - 1);
        assertEquals(marker, row.getName());
        assertFalse(row.getEnabled());
        assertEquals(0, row.getSortOrder());
        assertEquals(3L, row.getWallpaperCount());
    }

    /**
     * 验证MySQL名称查询与唯一键都把停用分类纳入判重。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void duplicateNameLookupAndUniqueKeyIncludeDisabledRows() {
        assertNotNull(categoryMapper.selectByName((marker + "_disabled").toUpperCase(java.util.Locale.ROOT), null));
        assertNull(categoryMapper.selectByName(marker, base - 1));
        Category changes = new Category();
        changes.setId(base - 1);
        changes.setName((marker + "_disabled").toUpperCase(java.util.Locale.ROOT));
        changes.setUpdatedAt(LocalDateTime.of(2026, 10, 11, 0, 0));
        assertThrows(DuplicateKeyException.class, () -> categoryMapper.update(changes));
    }

    /**
     * 验证真实外键拒绝有引用删除并允许删除未使用分类。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void realForeignKeyRejectsReferencedDeletionAndAllowsUnusedDeletion() {
        assertEquals(3, categoryMapper.countWallpapers(base - 1));
        assertThrows(DataIntegrityViolationException.class, () -> categoryMapper.deleteById(base - 1));
        assertNotNull(categoryMapper.selectAdminById(base - 1));
        assertEquals(1, categoryMapper.deleteById(base - 2));
        assertNull(categoryMapper.selectAdminById(base - 2));
    }

    /**
     * 验证utf8mb4字段正确保存64个补充Unicode字符。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void utf8mb4ColumnStores64SupplementaryUnicodeCharacters() {
        Category changes = new Category();
        changes.setId(base - 2);
        changes.setName("😀".repeat(64));
        changes.setUpdatedAt(LocalDateTime.of(2026, 10, 11, 0, 0));
        categoryMapper.update(changes);
        assertEquals(changes.getName(), categoryMapper.selectAdminById(base - 2).getName());
    }
}
