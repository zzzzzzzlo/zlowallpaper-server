package com.zlo.wallpaper.catalog;

import com.zlo.wallpaper.catalog.media.MediaInspector;
import com.zlo.wallpaper.catalog.model.MediaMetadata;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.catalog.model.UploadedFile;
import com.zlo.wallpaper.catalog.service.impl.FileUploadServiceImpl;
import com.zlo.wallpaper.catalog.storage.ObjectStorage;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证文件检查及上传编排的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@ExtendWith(MockitoExtension.class)
class FileUploadServiceTest {
    @Mock private MediaInspector mediaInspector;
    @Mock private ObjectStorage ossStorage;
    @Mock private Clock clock;
    @InjectMocks private FileUploadServiceImpl service;
    private static final byte[] BYTES = {1, 2, 3, 4};
    private static final byte[] COMPRESSED = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private Path temporaryFile;
    private Path sourceFile;
    private Path compressedFile;

    /**
     * 为上传测试设置可信用户身份并配置依赖替身。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach void owner() { BaseContext.setCurrentId(11L); }
    /**
     * 测试结束后清理线程身份及临时测试状态。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @AfterEach void clear() {
        BaseContext.removeCurrentId();
        if (temporaryFile != null) assertFalse(Files.exists(temporaryFile));
        if (sourceFile != null) assertFalse(Files.exists(sourceFile));
        if (compressedFile != null) assertFalse(Files.exists(compressedFile));
    }

    /**
     * 构造通过媒体检查的元数据，用于隔离测试上传编排。
     * 调用/引用位置：FileUploadServiceTest.cloudFailureKeepsErrorAndCleansTemporaryFile、FileUploadServiceTest.returnsTrustedFileMetadataAndExactOssObjectVersionWithoutDatabase。
     * @param cover 是否需要模拟封面压缩产物；原文件测试不注册压缩替身
     */
    private void validMedia(boolean cover) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.setMimeType("image/png"); metadata.setWidth(640); metadata.setHeight(480); metadata.setFps(BigDecimal.ZERO);
        when(mediaInspector.inspect(any(), anyString(), anyString())).thenReturn(metadata);
        if (cover) {
            when(mediaInspector.compressCover(any(), any())).thenAnswer(call -> {
                sourceFile = call.getArgument(0);
                compressedFile = call.getArgument(1);
                Files.write(compressedFile, COMPRESSED);
                MediaMetadata compressed = new MediaMetadata();
                compressed.setMimeType("image/jpeg"); compressed.setWidth(640); compressed.setHeight(480); compressed.setFps(BigDecimal.ZERO);
                return compressed;
            });
        }
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T00:00:00Z"));
    }

    /**
     * 验证上传返回可信元数据与准确OSS存储版本，不独立写资产表。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void returnsTrustedFileMetadataAndExactOssObjectVersionWithoutDatabase() throws Exception {
        validMedia(true);
        when(ossStorage.upload(any(), anyString(), anyString(), anyLong())).thenAnswer(call -> {
            temporaryFile = call.getArgument(0);
            assertArrayEquals(COMPRESSED, Files.readAllBytes(temporaryFile));
            return new StoredObject("private-bucket", call.getArgument(1), "oss-version");
        });
        UploadedFile file = service.uploadFile(file("封面.PNG", BYTES), "COVER");
        assertEquals("封面.PNG", file.getOriginalFilename());
        assertEquals("private-bucket", file.getBucket());
        assertEquals("oss-version", file.getObjectVersionId());
        assertEquals(11L, file.getUploadedBy());
        assertEquals(3L, file.getSizeBytes());
        assertEquals("image/jpeg", file.getMimeType());
        assertEquals("jpg", file.getFileExtension());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(COMPRESSED)), file.getSha256());
        assertTrue(file.getObjectKey().matches("zlowallpaper/covers/[a-f0-9-]{36}\\.jpg"));
    }

    /**
     * 验证非法上传用途不触发云上传。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param purpose 文件用途ORIGINAL或COVER，决定允许类型及大小上限
     */
    @ParameterizedTest @ValueSource(strings = {"", "cover", "VIDEO", "../../COVER"})
    void invalidPurposeNeverUploads(String purpose) {
        assertThrows(BusinessException.class, () -> service.uploadFile(file("test.png", BYTES), purpose));
        verifyNoInteractions(ossStorage, mediaInspector);
    }

    /**
     * 验证非法文件名不触发云上传。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param filename 已经检查过路径字符和长度的原始显示文件名
     */
    @ParameterizedTest @ValueSource(strings = {"test.exe", "no_extension", "test.svg", "test.png.html", "bad\n.png"})
    void invalidFilenameNeverUploads(String filename) {
        assertThrows(BusinessException.class, () -> service.uploadFile(file(filename, BYTES), "COVER"));
        verifyNoInteractions(ossStorage, mediaInspector);
    }

    /**
     * 验证缺少可信用户身份不触发云上传。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void missingIdentityNeverUploads() {
        BaseContext.removeCurrentId();
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(BusinessException.class,
                () -> service.uploadFile(file("test.png", BYTES), "COVER")).getStatus());
        verifyNoInteractions(ossStorage, mediaInspector);
    }

    /**
     * 验证缺失或空文件不触发云上传。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void missingOrEmptyFileNeverUploads() {
        assertThrows(BusinessException.class, () -> service.uploadFile(null, "COVER"));
        assertThrows(BusinessException.class, () -> service.uploadFile(file("test.png", new byte[0]), "COVER"));
        verifyNoInteractions(ossStorage, mediaInspector);
    }

    /**
     * 验证声明与实际封面大小都受到限制。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void declaredAndActualCoverLimitsAreEnforced() {
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(BusinessException.class,
                () -> service.uploadFile(file("test.png", oversized), "COVER")).getStatus());
        MockMultipartFile lying = new MockMultipartFile("file", "test.png", "image/png", oversized) /**
         * 上传测试中的匿名文件对象，覆盖声明大小以验证实际内容大小限制。
         * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
         */
        {
            /**
             * 在测试替身中在上传测试中提供声明大小，用于验证后端不能只相信客户端长度。
             * 调用位置：当前源码未发现显式业务调用；保留当前接口或测试辅助用途。
             * 输入参数：无。
             *
             * @return 上述处理得到的long结果；业务校验失败时通过异常返回错误
             */
            @Override public long getSize() { return 1; }
        };
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(BusinessException.class,
                () -> service.uploadFile(lying, "COVER")).getStatus());
        verifyNoInteractions(ossStorage, mediaInspector);
    }

    /**
     * 验证媒体检查失败不触发云上传。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void invalidMediaNeverUploads() {
        when(mediaInspector.inspect(any(), anyString(), anyString())).thenAnswer(call -> {
            temporaryFile = call.getArgument(0);
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MEDIA", "媒体文件损坏");
        });
        assertEquals("INVALID_MEDIA", assertThrows(BusinessException.class,
                () -> service.uploadFile(file("bad.png", BYTES), "ORIGINAL")).getCode());
        verifyNoInteractions(ossStorage);
    }

    /**
     * 验证云上传失败保留业务错误并清理临时文件。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test void cloudFailureKeepsErrorAndCleansTemporaryFile() {
        validMedia(true);
        when(ossStorage.upload(any(), anyString(), anyString(), anyLong())).thenAnswer(call -> {
            temporaryFile = call.getArgument(0);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_PERMISSION_ERROR", "OSS 授权不足");
        });
        assertEquals("OSS_PERMISSION_ERROR", assertThrows(BusinessException.class,
                () -> service.uploadFile(file("test.png", BYTES), "COVER")).getCode());
    }

    @Test void tenMiBCoverIsAcceptedBeforeCompression() {
        validMedia(true);
        when(ossStorage.upload(any(), anyString(), anyString(), anyLong()))
                .thenAnswer(call -> new StoredObject("private-bucket", call.getArgument(1), "version"));
        UploadedFile result = service.uploadFile(file("large.png", new byte[10 * 1024 * 1024]), "COVER");
        assertEquals(3L, result.getSizeBytes());
    }

    @Test void originalBytesAndMetadataAreNotCompressed() throws Exception {
        validMedia(false);
        when(ossStorage.upload(any(), anyString(), anyString(), anyLong())).thenAnswer(call -> {
            temporaryFile = call.getArgument(0);
            assertArrayEquals(BYTES, Files.readAllBytes(temporaryFile));
            return new StoredObject("private-bucket", call.getArgument(1), "version");
        });
        UploadedFile result = service.uploadFile(file("wallpaper.png", BYTES), "ORIGINAL");
        assertEquals(4L, result.getSizeBytes());
        assertEquals("png", result.getFileExtension());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)), result.getSha256());
        verify(mediaInspector, never()).compressCover(any(), any());
    }

    @Test void compressionFailureNeverUploadsAndCleansBothTemporaryFiles() {
        MediaMetadata metadata = new MediaMetadata();
        when(mediaInspector.inspect(any(), anyString(), anyString())).thenReturn(metadata);
        when(mediaInspector.compressCover(any(), any())).thenAnswer(call -> {
            if (sourceFile != null) { assertFalse(Files.exists(sourceFile)); }
            if (compressedFile != null) { assertFalse(Files.exists(compressedFile)); }
            sourceFile = call.getArgument(0);
            compressedFile = call.getArgument(1);
            Files.write(compressedFile, COMPRESSED);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_SERVICE_UNAVAILABLE", "压缩失败");
        });
        // 槽位总数为2，连续失败3次仍进入压缩，才能证明没有泄漏槽位。
        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals("MEDIA_SERVICE_UNAVAILABLE", assertThrows(BusinessException.class,
                    () -> service.uploadFile(file("cover.png", BYTES), "COVER")).getCode());
        }
        verifyNoInteractions(ossStorage);
    }

    @ParameterizedTest @ValueSource(ints = {0, 4194305})
    void invalidCompressedSizeNeverReachesStorage(int size) {
        when(mediaInspector.inspect(any(), anyString(), anyString())).thenReturn(new MediaMetadata());
        when(mediaInspector.compressCover(any(), any())).thenAnswer(call -> {
            sourceFile = call.getArgument(0);
            compressedFile = call.getArgument(1);
            Files.write(compressedFile, new byte[size]);
            return new MediaMetadata();
        });
        assertEquals("INVALID_MEDIA", assertThrows(BusinessException.class,
                () -> service.uploadFile(file("cover.png", BYTES), "COVER")).getCode());
        verifyNoInteractions(ossStorage);
    }

    /**
     * 构造当前测试所需的文件、上传参数或可信元数据。
     * 调用/引用位置：FileUploadServiceTest.cloudFailureKeepsErrorAndCleansTemporaryFile、FileUploadServiceTest.declaredAndActualCoverLimitsAreEnforced、FileUploadServiceTest.invalidFilenameNeverUploads、FileUploadServiceTest.invalidMediaNeverUploads、FileUploadServiceTest.invalidPurposeNeverUploads、FileUploadServiceTest.missingIdentityNeverUploads、FileUploadServiceTest.missingOrEmptyFileNeverUploads、FileUploadServiceTest.returnsTrustedFileMetadataAndExactOssObjectVersionWithoutDatabase。
     *
     * @param name 分类名称或未定义JSON字段名
     * @param bytes 读取的文件头字节或待校验字节数组
     * @return 上述处理得到的MockMultipartFile结果；业务校验失败时通过异常返回错误
     */
    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "image/png", bytes);
    }
}
