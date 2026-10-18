package com.zlo.wallpaper.catalog;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zlo.wallpaper.catalog.entity.Wallpaper;
import com.zlo.wallpaper.catalog.mapper.AdminWallpaperMapper;
import com.zlo.wallpaper.catalog.mapper.WallpaperMapper;
import com.zlo.wallpaper.catalog.model.UploadedFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证管理员商品管理的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties="wallpaper.auth.cleanup-cron=-")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named="wallpaper.mysql.tests",matches="true")
@Transactional
class AdminWallpaperMySqlIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminWallpaperMapper mapper;
    @Autowired WallpaperMapper publicMapper;
    private final long base=-1500000000L-ThreadLocalRandom.current().nextInt(1000000);
    private final String marker="wpit_"+UUID.randomUUID().toString().substring(0,12);
    /**
     * 准备当前测试独立的数据库或媒体夹具，避免复用上个用例的结果。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach void fixtures() {
        jdbc.update("INSERT INTO users(id,username,password_hash,role) VALUES(?,?,'TEST_ONLY_NEVER_LOGIN','ADMIN')",base,marker);
        jdbc.update("INSERT INTO categories(id,name,enabled) VALUES(?,?,1)",base-1,marker);
    }
    /**
     * 验证MySQL主键回填和两份内嵌文件及分类名称映射正确。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void generatedKeyRoundTripMapsBothInlineFilesAndCategoryName() {
        Wallpaper wallpaper=product();assertEquals(1,mapper.insert(wallpaper));assertNotNull(wallpaper.getId());
        Wallpaper stored=mapper.selectById(wallpaper.getId());
        assertEquals(marker,stored.getCategoryName());assertEquals(wallpaper.getOriginal().getObjectKey(),stored.getOriginal().getObjectKey());
        assertEquals(wallpaper.getCover().getSha256(),stored.getCover().getSha256());
        assertEquals(base,stored.getCover().getUploadedBy());assertEquals(1,mapper.countFileReferences("test-bucket",stored.getCover().getObjectKey()));
        Page<Wallpaper> page=new Page<>(1,50); page.setOptimizeJoinOfCountSql(false);
        assertTrue(mapper.selectPage(page).getRecords().stream().anyMatch(item -> item.getId().equals(wallpaper.getId())));
    }
    /**
     * 验证MySQL部分更新保留封面，上架决定公开可见性。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void partialUpdatePreservesCoverAndPublishingControlsReadVisibility() {
        Wallpaper wallpaper=product();mapper.insert(wallpaper);String coverKey=wallpaper.getCover().getObjectKey();
        wallpaper=mapper.selectByIdForUpdate(wallpaper.getId());wallpaper.setTitle("修改");wallpaper.setOriginal(file(false));
        mapper.update(wallpaper,true,false);Wallpaper stored=mapper.selectById(wallpaper.getId());
        assertEquals(coverKey,stored.getCover().getObjectKey());assertEquals("修改",stored.getTitle());
        assertNull(publicMapper.selectPublishedById(stored.getId()));assertNotNull(publicMapper.selectAdminCover(stored.getId()));
        mapper.updatePublication(stored.getId(),"PUBLISHED",LocalDateTime.now(),LocalDateTime.now());
        assertNotNull(publicMapper.selectPublishedById(stored.getId()));assertNotNull(publicMapper.selectPublicCover(stored.getId()));
        mapper.updatePublication(stored.getId(),"WITHDRAWN",LocalDateTime.now(),LocalDateTime.now());assertNull(publicMapper.selectPublicCover(stored.getId()));
    }
    /**
     * 验证真实数据库约束拒绝不完整文件与非法发布状态。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void realDatabaseRejectsIncompleteFileAndInvalidPublishedState() {
        Wallpaper wallpaper=product();wallpaper.getOriginal().setSha256("invalid");
        assertCheckViolation(() -> mapper.insert(wallpaper));
        wallpaper.getOriginal().setSha256("a".repeat(64));mapper.insert(wallpaper);
        assertCheckViolation(() -> mapper.updatePublication(wallpaper.getId(),"PUBLISHED",null,LocalDateTime.now()));
    }
    /**
     * 断言真实MySQL以CHECK约束错误拒绝非法数据。
     * 调用/引用位置：AdminWallpaperMySqlIT.realDatabaseRejectsIncompleteFileAndInvalidPublishedState。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param action 需要执行并断言失败的测试操作
     */
    private void assertCheckViolation(org.junit.jupiter.api.function.Executable action) {
        DataAccessException exception=assertThrows(DataAccessException.class,action);
        Throwable cause=exception;
        while(cause.getCause()!=null) cause=cause.getCause();
        assertInstanceOf(java.sql.SQLException.class,cause);
        // MySQL CHECK错误3819使用HY000，Spring可能包装为UncategorizedSQLException。
        assertEquals(3819,((java.sql.SQLException)cause).getErrorCode());
    }
    /**
     * 构造下单或商品持久化测试所需的商品实体/数据库记录。
     * 调用/引用位置：AdminWallpaperMySqlIT.generatedKeyRoundTripMapsBothInlineFilesAndCategoryName、AdminWallpaperMySqlIT.partialUpdatePreservesCoverAndPublishingControlsReadVisibility、AdminWallpaperMySqlIT.realDatabaseRejectsIncompleteFileAndInvalidPublishedState。
     * 输入参数：无。
     *
     * @return 上述处理得到的Wallpaper结果；业务校验失败时通过异常返回错误
     */
    private Wallpaper product() {
        Wallpaper wallpaper=new Wallpaper();wallpaper.setTitle(marker);wallpaper.setDescription("");wallpaper.setCategoryId(base-1);
        wallpaper.setType("IMAGE");wallpaper.setPriceCents(29);wallpaper.setCurrency("ZLC");wallpaper.setCreator("");
        wallpaper.setPublicationStatus("DRAFT");wallpaper.setCreatedBy(base);wallpaper.setCreatedAt(LocalDateTime.now());wallpaper.setUpdatedAt(wallpaper.getCreatedAt());
        wallpaper.setOriginal(file(false));wallpaper.setCover(file(true));return wallpaper;
    }
    /**
     * 构造当前测试所需的文件、上传参数或可信元数据。
     * 调用/引用位置：AdminWallpaperMySqlIT.partialUpdatePreservesCoverAndPublishingControlsReadVisibility、AdminWallpaperMySqlIT.product。
     *
     * @param cover 本次封面文件元数据或是否按封面限制校验的标志
     * @return 检查及上传完成的文件显示名、存储位置、哈希、大小和媒体信息
     */
    private UploadedFile file(boolean cover) {
        UploadedFile file=new UploadedFile();file.setOriginalFilename("测试.png");file.setStorageProvider("OSS");file.setBucket("test-bucket");
        file.setObjectKey("zlowallpaper/"+(cover?"covers/":"originals/")+UUID.randomUUID()+".png");file.setObjectVersionId("test-version");
        file.setMimeType("image/png");file.setFileExtension("png");file.setSizeBytes(3L);file.setSha256("a".repeat(64));
        file.setWidth(640);file.setHeight(480);file.setFps(BigDecimal.ZERO);file.setStatus("READY");file.setUploadedBy(base);file.setCreatedAt(LocalDateTime.now());return file;
    }
}
