package com.zlo.wallpaper.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.catalog.mapper.AdminWallpaperMapper;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.catalog.model.UploadedFile;
import com.zlo.wallpaper.catalog.service.FileUploadService;
import com.zlo.wallpaper.catalog.service.ProductFileCleanupService;
import com.zlo.wallpaper.catalog.storage.OssStorage;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import com.zlo.wallpaper.catalog.service.AdminWallpaperService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证管理员商品管理的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-wallpaper;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "wallpaper.cors.allowed-origins=http://127.0.0.1:14173"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Sql(scripts = {"/auth-schema.sql", "/admin-category-schema.sql", "/admin-wallpaper-schema.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AdminWallpaperIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired AdminWallpaperMapper mapper;
    @Autowired ProductFileCleanupService cleanup;
    @Autowired AdminWallpaperService adminService;
    @LocalServerPort int port;
    @MockitoBean FileUploadService uploader;
    @MockitoBean OssStorage storage;
    private String adminToken, userToken;
    private final List<UploadedFile> uploads = new ArrayList<>();
    private final Map<String,byte[]> cloudBytes = new HashMap<>();
    private static final Path ROOT;
    static {
        try { ROOT = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "wallpaper-integration-"); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    /**
     * 向测试Spring上下文提供隔离的临时目录或动态服务地址。
     * 调用位置：当前源码未发现显式业务调用；保留当前接口或测试辅助用途。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param registry 本方法使用的DynamicPropertyRegistry输入，按上面的业务用途处理
     */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("wallpaper.storage.root", ROOT::toString);
    }

    /**
     * 建立本测试需要的账号与登录令牌。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeAll void accounts() {
        adminToken = account("wallpaper_admin", "ADMIN"); userToken = account("wallpaper_user", "USER");
    }
    /**
     * 准备当前测试独立的数据库或媒体夹具，避免复用上个用例的结果。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach void fixtures() throws Exception {
        jdbc.update("DELETE FROM wallpapers"); jdbc.update("DELETE FROM categories");
        jdbc.update("UPDATE users SET role='ADMIN',status='ACTIVE' WHERE username='wallpaper_admin'");
        jdbc.update("INSERT INTO categories(id,name,sort_order,enabled,created_at,updated_at) VALUES(1,'自然',0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'停用',1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        reset(uploader, storage); uploads.clear(); cloudBytes.clear();
        ReflectionTestUtils.setField(adminService,"publicBaseUrl","http://127.0.0.1:"+port+"/api/v1");
        cleanup.retryPendingFiles(); clearInvocations(storage);
        when(uploader.uploadFile(any(), anyString())).thenAnswer(call -> upload(call.getArgument(0), call.getArgument(1)));
        when(storage.readCover(anyString(), anyString(), anyLong())).thenAnswer(call -> cloudBytes.get(call.<String>getArgument(1)));
    }
    /**
     * 测试结束后回收本测试创建的临时清理任务文件。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @AfterAll void removeTemporaryTasks() throws Exception {
        // 删除的目标是此类自己创建的测试目录，先核对边界。
        try (java.util.stream.Stream<Path> paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                assertTrue(path.toAbsolutePath().normalize().startsWith(ROOT.toAbsolutePath().normalize()));
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 验证商品创建写入两份当前文件并返回草稿，不泄露云端位置。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void createWritesBothFilesInProductAndReturnsDraftWithoutCloudLocations() throws Exception {
        JsonNode data = create();
        assertEquals("DRAFT", data.path("publicationStatus").asText());
        assertEquals("original.png", data.path("originalFilename").asText());
        assertTrue(data.path("coverUrl").asText().endsWith("/admin/wallpapers/"+data.path("id").asText()+"/cover"));
        assertFalse(data.has("originalAssetId")); assertFalse(data.has("resourceVersion")); assertFalse(data.has("objectKey"));
        long id = data.path("id").asLong();
        assertEquals(uploads.get(1).getObjectKey(), mapper.selectById(id).getOriginal().getObjectKey());
        assertEquals(uploads.get(0).getObjectKey(), mapper.selectById(id).getCover().getObjectKey());
        assertEquals(1, mapper.countFileReferences("test-bucket", uploads.get(0).getObjectKey()));
        verify(storage, never()).deleteObject(any());
        assertEquals(0, taskCount()); assertNull(BaseContext.getCurrentId());
    }

    /**
     * 验证草稿预览仅管理员可用，上架状态控制公开查询。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void draftPreviewIsAdminOnlyAndPublishingControlsAllPublicQueries() throws Exception {
        String id = create().path("id").asText();
        request(get("/admin/wallpapers/"+id+"/cover"), adminToken, 200).getResponse().getContentAsByteArray();
        request(get("/admin/wallpapers/"+id+"/cover"), userToken, 403);
        request(get("/admin/wallpapers/"+id+"/cover"), null, 401);
        request(get("/catalog/covers/"+id), null, 404);
        assertEquals(0, body(request(get("/wallpapers"), null, 200)).path("data").path("total").asInt());
        publish(id, "PUBLISHED", 200);
        LocalDateTime time = mapper.selectById(Long.parseLong(id)).getPublishedAt();
        publish(id, "PUBLISHED", 200);
        assertEquals(time, mapper.selectById(Long.parseLong(id)).getPublishedAt());
        request(get("/wallpapers/"+id), null, 200); request(get("/catalog/covers/"+id), null, 200);
        publish(id,"WITHDRAWN",200);
        request(get("/wallpapers/"+id),null,404); request(get("/catalog/covers/"+id),null,404);
        request(get("/admin/wallpapers/"+id+"/cover"),adminToken,200);
    }

    /**
     * 验证管理分页包含全部状态并兼容旧草稿空文件字段。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void paginatedAdminListIncludesEveryStatusAndNullableLegacyDraft() throws Exception {
        String draft = create().path("id").asText();
        String published = create().path("id").asText(); publish(published,"PUBLISHED",200);
        String withdrawn = create().path("id").asText(); publish(withdrawn,"WITHDRAWN",200);
        jdbc.update("UPDATE wallpapers SET original_object_key=NULL, cover_object_key=NULL WHERE id=?", draft);
        JsonNode data = body(request(get("/admin/wallpapers").param("pageSize","2"),adminToken,200)).path("data");
        assertEquals(3,data.path("total").asInt()); assertEquals(2,data.path("items").size());
        JsonNode legacy = body(request(get("/admin/wallpapers").param("page","2").param("pageSize","2"),adminToken,200)).path("data").path("items").get(0);
        assertTrue(legacy.path("width").isNull()); assertEquals("",legacy.path("coverUrl").asText());
        publish(draft,"PUBLISHED",409);
        request(get("/admin/wallpapers").param("page","0"),adminToken,400);
        request(get("/admin/wallpapers").param("pageSize","51"),adminToken,400);
        request(get("/admin/wallpapers").param("keyword","x"),adminToken,400);
        request(get("/admin/wallpapers").param("page","1","2"),adminToken,400);
    }

    /**
     * 验证部分修改保留文件和发布状态，仅替换指定原文件。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void partialUpdatePreservesFilesAndPublicationThenReplacesOnlyOriginal() throws Exception {
        String id = create().path("id").asText(); publish(id,"PUBLISHED",200);
        String oldCover = mapper.selectById(Long.parseLong(id)).getCover().getObjectKey();
        String oldOriginal = mapper.selectById(Long.parseLong(id)).getOriginal().getObjectKey();
        clearInvocations(uploader);
        JsonNode updated = body(request(form(HttpMethod.PATCH,"/admin/wallpapers/"+id).param("title","修改").param("description",""),adminToken,200)).path("data");
        assertEquals("PUBLISHED", updated.path("publicationStatus").asText());
        verifyNoInteractions(uploader); verify(storage,never()).deleteObject(any());
        request(form(HttpMethod.PATCH,"/admin/wallpapers/"+id).file(file("originalFile","new.mp4")),adminToken,200);
        assertEquals("VIDEO",mapper.selectById(Long.parseLong(id)).getType());
        assertEquals(oldCover,mapper.selectById(Long.parseLong(id)).getCover().getObjectKey());
        verify(storage).deleteObject(argThat(object -> object.getObjectKey().equals(oldOriginal)));
        verify(storage,never()).deleteObject(argThat(object -> object.getObjectKey().equals(oldCover)));
    }

    /**
     * 验证可保留原停用分类，但新建或换分类必须使用启用分类。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void categoriesMayBeRetainedWhenDisabledButCannotBeAssignedToNewProducts() throws Exception {
        request(createForm().param("categoryId","2"),adminToken,400); // 重复字段必须先拒绝。
        request(form(HttpMethod.POST,"/admin/wallpapers").file(file("coverFile","cover.png")).file(file("originalFile","original.png"))
                .param("title","分类").param("priceCents","0").param("categoryId","2"),adminToken,409);
        verifyNoInteractions(uploader);
        String id = create().path("id").asText(); jdbc.update("UPDATE categories SET enabled=0 WHERE id=1");
        request(form(HttpMethod.PATCH,"/admin/wallpapers/"+id).param("categoryId","1").param("title","保留"),adminToken,200);
        request(form(HttpMethod.PATCH,"/admin/wallpapers/"+id).param("categoryId","2"),adminToken,409);
        request(form(HttpMethod.PATCH,"/admin/wallpapers/"+id).param("categoryId","999"),adminToken,404);
    }

    /**
     * 验证上传前拒绝未知、只读、重复、缺失及类型错误表单。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void rejectsUnknownReadonlyDuplicateMalformedAndMissingFormInputsBeforeCloudUpload() throws Exception {
        for (String name : List.of("originalAssetId","resourceVersion","type","width","publicationStatus","original.objectKey"))
            request(createForm().param(name,"bad"),adminToken,400);
        for (String price : List.of("", "-1", "1.2", "1e2", "2147483648", "100000001"))
            request(form(HttpMethod.POST,"/admin/wallpapers").file(file("coverFile","cover.png")).file(file("originalFile","original.png"))
                    .param("title","测试").param("categoryId","1").param("priceCents",price),adminToken,400);
        request(createForm().file(file("title","data.png")),adminToken,400);
        request(createForm().file(file("coverFile","duplicate.png")),adminToken,400);
        request(createForm().param("originalFile","text"),adminToken,400);
        request(form(HttpMethod.POST,"/admin/wallpapers").param("title","missing"),adminToken,400);
        request(post("/admin/wallpapers").contentType(MediaType.APPLICATION_JSON).content("{}"),adminToken,415);
        request(form(HttpMethod.PATCH,"/admin/wallpapers/1"),adminToken,400);
        request(form(HttpMethod.PATCH,"/admin/wallpapers/no-such-id").param("title","x"),adminToken,404);
        request(createForm(),null,401); request(createForm(),userToken,403);
        verifyNoInteractions(uploader);
    }

    /**
     * 验证原文件上传失败回收已上传封面，商品不入库。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void originalUploadFailureReclaimsAlreadyUploadedCoverAndDoesNotInsert() throws Exception {
        when(uploader.uploadFile(any(),eq("ORIGINAL"))).thenThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"OSS_UNAVAILABLE","测试云故障"));
        request(createForm(),adminToken,503);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM wallpapers",Integer.class));
        verify(storage).deleteObject(argThat(object -> object.getObjectKey().equals(uploads.get(0).getObjectKey())));
        assertEquals(0,taskCount());
    }

    /**
     * 验证长上传期间分类或角色改变后重新校验并回收新文件。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void changedCategoryAndRevokedRoleDuringLongUploadAreRecheckedAndBothFilesReclaimed() throws Exception {
        when(uploader.uploadFile(any(),eq("ORIGINAL"))).thenAnswer(call -> {
            UploadedFile result = upload(call.getArgument(0), "ORIGINAL"); jdbc.update("UPDATE categories SET enabled=0 WHERE id=1"); return result;
        });
        request(createForm(),adminToken,409); verify(storage,times(2)).deleteObject(any());
        jdbc.update("UPDATE categories SET enabled=1 WHERE id=1"); reset(storage);
        when(uploader.uploadFile(any(),eq("ORIGINAL"))).thenAnswer(call -> {
            UploadedFile result=upload(call.getArgument(0),"ORIGINAL"); jdbc.update("UPDATE users SET role='USER' WHERE username='wallpaper_admin'"); return result;
        });
        request(createForm(),adminToken,403); verify(storage,times(2)).deleteObject(any());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM wallpapers",Integer.class));
    }

    /**
     * 验证数据库失败回滚商品，失败删除任务保留供重试。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void databaseFailureRollsBackProductAndRetainsFailedDeletesForRetry() throws Exception {
        // 不模拟Mapper：让真实数据库CHECK在插入时拒绝不合法文件大小。
        when(uploader.uploadFile(any(),eq("ORIGINAL"))).thenAnswer(call -> {
            UploadedFile result = upload(call.getArgument(0), "ORIGINAL"); result.setOriginalFilename("x".repeat(256)); return result;
        });
        doThrow(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"OSS_UNAVAILABLE","删除暂失败")).when(storage).deleteObject(any());
        request(createForm(),adminToken,500);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM wallpapers",Integer.class)); assertEquals(2,taskCount());
        reset(storage); cleanup.retryPendingFiles(); verify(storage,times(2)).deleteObject(any()); assertEquals(0,taskCount());
    }

    /**
     * 验证并发替换文件返回冲突并保护先提交请求的文件。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void concurrentFileReplacementReturnsConflictAndKeepsOtherRequestFile() throws Exception {
        String id=create().path("id").asText(); String otherKey="zlowallpaper/originals/"+UUID.randomUUID()+".png";
        clearInvocations(storage);
        when(uploader.uploadFile(any(),eq("ORIGINAL"))).thenAnswer(call -> {
            UploadedFile result=upload(call.getArgument(0),"ORIGINAL"); jdbc.update("UPDATE wallpapers SET original_object_key=? WHERE id=?",otherKey,id); return result;
        });
        request(form(HttpMethod.PATCH,"/admin/wallpapers/"+id).file(file("originalFile","replaced.png")),adminToken,409);
        assertEquals(otherKey,mapper.selectById(Long.parseLong(id)).getOriginal().getObjectKey());
        verify(storage).deleteObject(argThat(object -> object.getObjectKey().equals(uploads.get(2).getObjectKey())));
        verify(storage,never()).deleteObject(argThat(object -> object.getObjectKey().equals(otherKey)));
    }

    /**
     * 验证清理不删除仍被引用或正在保存的上传对象。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void cleanupNeverDeletesFilesStillReferencedByAnyProductOrActiveUpload() throws Exception {
        String id=create().path("id").asText(); UploadedFile original=mapper.selectById(Long.parseLong(id)).getOriginal();
        cleanup.retireFile(original); verify(storage,never()).deleteObject(any());
        UploadedFile pending=upload(file("originalFile","pending.png"),"ORIGINAL");
        cleanup.trackUpload(pending); cleanup.retryPendingFiles(); verify(storage,never()).deleteObject(any());
        cleanup.rollbackUpload(pending); verify(storage).deleteObject(argThat(object -> object.getObjectKey().equals(pending.getObjectKey())));
    }

    /**
     * 验证上下架拒绝未知状态与字段，保留实际错误状态。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void publicationRejectsUnknownStateFieldsAndPreservesActualErrors() throws Exception {
        String id=create().path("id").asText();
        request(put("/admin/wallpapers/"+id+"/publication").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DRAFT\"}"),adminToken,400);
        request(put("/admin/wallpapers/"+id+"/publication").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\",\"type\":\"IMAGE\"}"),adminToken,400);
        publish("999999","PUBLISHED",404);
    }

    /**
     * 验证浏览器到真实Tomcat的multipart创建编辑及封面读取完整联调。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    @EnabledIfSystemProperty(named="wallpaper.browser.tests",matches="true")
    void realBrowserAndTomcatMultipartWorkflow() throws Exception {
        ProcessBuilder builder=new ProcessBuilder("node",Path.of("src/test/admin-wallpaper-browser.cjs").toAbsolutePath().toString());
        builder.environment().put("TEST_API_BASE","http://127.0.0.1:"+port+"/api/v1");
        builder.redirectErrorStream(true).redirectOutput(Path.of(".local-setup/admin-wallpaper-browser.log").toFile());
        Process process=builder.start();
        try {
            assertTrue(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS),"浏览器测试超时");
            assertEquals(0,process.exitValue(),"查看.local-setup/admin-wallpaper-browser.log");
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wallpapers",Integer.class));
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }

    /**
     * 配置上传测试替身的返回值或故障，模拟特定上传阶段。
     * 调用/引用位置：AdminWallpaperIntegrationTest.changedCategoryAndRevokedRoleDuringLongUploadAreRecheckedAndBothFilesReclaimed、AdminWallpaperIntegrationTest.cleanupNeverDeletesFilesStillReferencedByAnyProductOrActiveUpload、AdminWallpaperIntegrationTest.concurrentFileReplacementReturnsConflictAndKeepsOtherRequestFile、AdminWallpaperIntegrationTest.databaseFailureRollsBackProductAndRetainsFailedDeletesForRetry、AdminWallpaperIntegrationTest.fixtures。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @param purpose 文件用途ORIGINAL或COVER，决定允许类型及大小上限
     * @return 检查及上传完成的文件显示名、存储位置、哈希、大小和媒体信息
     */
    private UploadedFile upload(MultipartFile file,String purpose) throws Exception {
        if(file.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST,"EMPTY_FILE","文件为空");
        UploadedFile result = new UploadedFile();
        String name=file.getOriginalFilename(); String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase();
        result.setPurpose(purpose); result.setOriginalFilename(name); result.setFileExtension(extension);
        result.setMimeType(extension.equals("mp4") ? "video/mp4" : "image/png");
        result.setStorageProvider("OSS"); result.setBucket("test-bucket");
        result.setObjectKey("zlowallpaper/"+(purpose.equals("COVER")?"covers":"originals")+"/"+UUID.randomUUID()+"."+extension);
        result.setObjectVersionId(UUID.randomUUID().toString()); result.setSizeBytes(file.getSize());
        result.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes())));
        result.setWidth(640); result.setHeight(480); result.setFps(extension.equals("mp4")?BigDecimal.valueOf(30):BigDecimal.ZERO);
        result.setStatus("READY"); result.setUploadedBy(BaseContext.getCurrentId()); result.setCreatedAt(LocalDateTime.now());
        uploads.add(result); cloudBytes.put(result.getObjectKey(),file.getBytes()); return result;
    }
    /**
     * 构造当前测试所需的文件、上传参数或可信元数据。
     * 调用/引用位置：AdminWallpaperIntegrationTest.categoriesMayBeRetainedWhenDisabledButCannotBeAssignedToNewProducts、AdminWallpaperIntegrationTest.cleanupNeverDeletesFilesStillReferencedByAnyProductOrActiveUpload、AdminWallpaperIntegrationTest.concurrentFileReplacementReturnsConflictAndKeepsOtherRequestFile、AdminWallpaperIntegrationTest.createForm、AdminWallpaperIntegrationTest.partialUpdatePreservesFilesAndPublicationThenReplacesOnlyOriginal、AdminWallpaperIntegrationTest.rejectsUnknownReadonlyDuplicateMalformedAndMissingFormInputsBeforeCloudUpload。
     *
     * @param field JSON中未定义的字段名，用于拒绝契约外输入
     * @param name 分类名称或未定义JSON字段名
     * @return 上述处理得到的MockMultipartFile结果；业务校验失败时通过异常返回错误
     */
    private MockMultipartFile file(String field,String name) { return new MockMultipartFile(field,name,"application/octet-stream",new byte[]{1,2,3}); }
    /**
     * 构造商品测试的multipart表单请求。
     * 调用/引用位置：AdminWallpaperIntegrationTest.categoriesMayBeRetainedWhenDisabledButCannotBeAssignedToNewProducts、AdminWallpaperIntegrationTest.concurrentFileReplacementReturnsConflictAndKeepsOtherRequestFile、AdminWallpaperIntegrationTest.createForm、AdminWallpaperIntegrationTest.partialUpdatePreservesFilesAndPublicationThenReplacesOnlyOriginal、AdminWallpaperIntegrationTest.rejectsUnknownReadonlyDuplicateMalformedAndMissingFormInputsBeforeCloudUpload。
     *
     * @param method HTTP方法字符串，用于决定公开性或测试请求类型
     * @param path 请求路径或服务器控制的文件路径
     * @return 上述处理得到的MockMultipartHttpServletRequestBuilder结果；业务校验失败时通过异常返回错误
     */
    private MockMultipartHttpServletRequestBuilder form(HttpMethod method,String path) { return multipart(method,path); }
    /**
     * 构造包含创建商品必需文本和两份文件的multipart请求。
     * 调用/引用位置：AdminWallpaperIntegrationTest.categoriesMayBeRetainedWhenDisabledButCannotBeAssignedToNewProducts、AdminWallpaperIntegrationTest.changedCategoryAndRevokedRoleDuringLongUploadAreRecheckedAndBothFilesReclaimed、AdminWallpaperIntegrationTest.create、AdminWallpaperIntegrationTest.databaseFailureRollsBackProductAndRetainsFailedDeletesForRetry、AdminWallpaperIntegrationTest.originalUploadFailureReclaimsAlreadyUploadedCoverAndDoesNotInsert、AdminWallpaperIntegrationTest.rejectsUnknownReadonlyDuplicateMalformedAndMissingFormInputsBeforeCloudUpload。
     * 输入参数：无。
     *
     * @return 上述处理得到的MockMultipartHttpServletRequestBuilder结果；业务校验失败时通过异常返回错误
     */
    private MockMultipartHttpServletRequestBuilder createForm() { return form(HttpMethod.POST,"/admin/wallpapers")
            .file(file("coverFile","cover.png")).file(file("originalFile","original.png")); }
    /**
     * 执行本测试的创建请求或业务调用，并返回订单/商品结果。
     * 调用/引用位置：AdminWallpaperIntegrationTest.categoriesMayBeRetainedWhenDisabledButCannotBeAssignedToNewProducts、AdminWallpaperIntegrationTest.cleanupNeverDeletesFilesStillReferencedByAnyProductOrActiveUpload、AdminWallpaperIntegrationTest.concurrentFileReplacementReturnsConflictAndKeepsOtherRequestFile、AdminWallpaperIntegrationTest.createWritesBothFilesInProductAndReturnsDraftWithoutCloudLocations、AdminWallpaperIntegrationTest.draftPreviewIsAdminOnlyAndPublishingControlsAllPublicQueries、AdminWallpaperIntegrationTest.paginatedAdminListIncludesEveryStatusAndNullableLegacyDraft、AdminWallpaperIntegrationTest.partialUpdatePreservesFilesAndPublicationThenReplacesOnlyOriginal、AdminWallpaperIntegrationTest.publicationRejectsUnknownStateFieldsAndPreservesActualErrors。
     * 输入参数：无。
     *
     * @return 解析后的测试JSON节点，供用例读取业务字段
     */
    private JsonNode create() throws Exception { return body(request(createForm().param("title","商品").param("categoryId","1").param("priceCents","29"),adminToken,201)).path("data"); }
    /**
     * 发送商品上下架测试请求并断言HTTP状态。
     * 调用/引用位置：AdminWallpaperIntegrationTest.draftPreviewIsAdminOnlyAndPublishingControlsAllPublicQueries、AdminWallpaperIntegrationTest.paginatedAdminListIncludesEveryStatusAndNullableLegacyDraft、AdminWallpaperIntegrationTest.partialUpdatePreservesFilesAndPublicationThenReplacesOnlyOriginal、AdminWallpaperIntegrationTest.publicationRejectsUnknownStateFieldsAndPreservesActualErrors。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param state 本方法使用的String输入，按上面的业务用途处理
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     */
    private void publish(String id,String state,int status) throws Exception { request(put("/admin/wallpapers/"+id+"/publication").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\""+state+"\"}"),adminToken,status); }
    /**
     * 发送测试HTTP请求，携带需要的身份和参数，并断言预期状态。
     * 调用/引用位置：AdminWallpaperIntegrationTest.categoriesMayBeRetainedWhenDisabledButCannotBeAssignedToNewProducts、AdminWallpaperIntegrationTest.changedCategoryAndRevokedRoleDuringLongUploadAreRecheckedAndBothFilesReclaimed、AdminWallpaperIntegrationTest.concurrentFileReplacementReturnsConflictAndKeepsOtherRequestFile、AdminWallpaperIntegrationTest.create、AdminWallpaperIntegrationTest.databaseFailureRollsBackProductAndRetainsFailedDeletesForRetry、AdminWallpaperIntegrationTest.draftPreviewIsAdminOnlyAndPublishingControlsAllPublicQueries、AdminWallpaperIntegrationTest.originalUploadFailureReclaimsAlreadyUploadedCoverAndDoesNotInsert、AdminWallpaperIntegrationTest.paginatedAdminListIncludesEveryStatusAndNullableLegacyDraft、AdminWallpaperIntegrationTest.partialUpdatePreservesFilesAndPublicationThenReplacesOnlyOriginal、AdminWallpaperIntegrationTest.publicationRejectsUnknownStateFieldsAndPreservesActualErrors、AdminWallpaperIntegrationTest.publish、AdminWallpaperIntegrationTest.rejectsUnknownReadonlyDuplicateMalformedAndMissingFormInputsBeforeCloudUpload。
     *
     * @param builder 本方法使用的MockHttpServletRequestBuilder输入，按上面的业务用途处理
     * @param token 刷新令牌或访问令牌字符串，按当前方法用途处理且不得写入日志
     * @param expected 测试断言期望的状态或值
     * @return 已经检查HTTP状态的MockMvc响应，供后续断言解析
     */
    private MvcResult request(MockHttpServletRequestBuilder builder,String token,int expected) throws Exception {
        String path=builder.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI();
        builder.uri(java.net.URI.create("/api/v1"+path)).contextPath("/api/v1");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        // 完整表单默认补齐必要资料；特意测试缺失的表单显式指定title。
        if(builder instanceof MockMultipartHttpServletRequestBuilder && path.equals("/admin/wallpapers") &&
                builder.buildRequest(new org.springframework.mock.web.MockServletContext()).getParameter("title")==null &&
                !builder.buildRequest(new org.springframework.mock.web.MockServletContext()).getParameterMap().containsKey("title")) {
            builder.param("title","商品").param("categoryId","1").param("priceCents","29");
        }
        MvcResult result=mvc.perform(builder).andExpect(status().is(expected)).andReturn();
        if(expected>=400) {
            JsonNode response=body(result); assertTrue(response.path("data").isNull());
            assertEquals(result.getResponse().getHeader("X-Request-Id"),response.path("requestId").asText());
        }
        return result;
    }
    /**
     * 解析测试HTTP响应的完整JSON外壳。
     * 调用/引用位置：AdminWallpaperIntegrationTest.create、AdminWallpaperIntegrationTest.draftPreviewIsAdminOnlyAndPublishingControlsAllPublicQueries、AdminWallpaperIntegrationTest.paginatedAdminListIncludesEveryStatusAndNullableLegacyDraft、AdminWallpaperIntegrationTest.partialUpdatePreservesFilesAndPublicationThenReplacesOnlyOriginal、AdminWallpaperIntegrationTest.request。
     *
     * @param result 本方法要转换或断言的执行结果
     * @return 解析后的测试JSON节点，供用例读取业务字段
     */
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    /**
     * 统计本测试待清理任务文件数量。
     * 调用/引用位置：AdminWallpaperIntegrationTest.createWritesBothFilesInProductAndReturnsDraftWithoutCloudLocations、AdminWallpaperIntegrationTest.databaseFailureRollsBackProductAndRetainsFailedDeletesForRetry、AdminWallpaperIntegrationTest.originalUploadFailureReclaimsAlreadyUploadedCoverAndDoesNotInsert。
     * 输入参数：无。
     *
     * @return 上述处理得到的long结果；业务校验失败时通过异常返回错误
     */
    private long taskCount() throws Exception {
        Path directory=ROOT.resolve(".catalog-cleanup"); if(!Files.isDirectory(directory)) return 0;
        try(java.util.stream.Stream<Path> paths=Files.list(directory)) { return paths.filter(path -> path.toString().endsWith(".json")).count(); }
    }
    /**
     * 建立指定角色的测试账号并返回访问令牌。
     * 调用/引用位置：AdminWallpaperIntegrationTest.accounts。
     *
     * @param name 分类名称或未定义JSON字段名
     * @param role 测试账号需要的USER或ADMIN角色
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    private String account(String name,String role) {
        RegisterDTO register=new RegisterDTO(); register.setUsername(name);register.setPassword("WallpaperTest123!");auth.register(register);
        jdbc.update("UPDATE users SET role=? WHERE username=?",role,name);
        LoginDTO login=new LoginDTO();login.setUsername(name);login.setPassword("WallpaperTest123!");return auth.login(login).getAccessToken();
    }
}
