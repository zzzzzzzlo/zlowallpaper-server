package com.zlo.wallpaper.catalog;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.*;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.catalog.storage.OssStorage;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.config.OssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证OSS上传与补偿的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
class OssStorageTest {
    @TempDir Path directory;
    private OssStorage storage;
    private OSS client;

    /**
     * 创建隔离的测试依赖、媒体文件或OSS替身。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach
    void setup() {
        storage = new OssStorage();
        client = mock(OSS.class);
        OssProperties properties = new OssProperties();
        properties.setBucket("private-bucket");
        ReflectionTestUtils.setField(storage, "properties", properties);
        ReflectionTestUtils.setField(storage, "client", client);
    }

    /**
     * 验证普通上传使用流并保留可信类型与实际版本。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void simpleUploadStreamsFileAndPreservesContentTypeAndVersion() throws Exception {
        Path file = directory.resolve("image.png");
        Files.write(file, new byte[]{1, 2, 3});
        when(client.putObject(any(PutObjectRequest.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            assertEquals("private-bucket", request.getBucketName());
            assertEquals("covers/test.png", request.getKey());
            assertEquals("image/png", request.getMetadata().getContentType());
            assertEquals(3, request.getMetadata().getContentLength());
            assertArrayEquals(new byte[]{1, 2, 3}, request.getInputStream().readAllBytes());
            PutObjectResult result = new PutObjectResult(); result.setVersionId("new-version"); return result;
        });
        StoredObject stored = storage.upload(file, "covers/test.png", "image/png", 3);
        assertEquals("new-version", stored.getVersionId());
        verify(client, never()).initiateMultipartUpload(any());
    }

    /**
     * 验证分片上传使用正确偏移、大小及完成顺序。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void multipartUsesCorrectOffsetsSizesAndOrderedParts() throws Exception {
        long partSize = 8L * 1024 * 1024;
        Path file = directory.resolve("video.mp4");
        try (RandomAccessFile output = new RandomAccessFile(file.toFile(), "rw")) {
            output.setLength(partSize + 3);
            output.seek(partSize);
            output.write(new byte[]{7, 8, 9});
        }
        InitiateMultipartUploadResult initiated = new InitiateMultipartUploadResult(); initiated.setUploadId("upload-1");
        when(client.initiateMultipartUpload(any())).thenReturn(initiated);
        AtomicInteger partCount = new AtomicInteger();
        when(client.uploadPart(any())).thenAnswer(call -> {
            UploadPartRequest request = call.getArgument(0);
            int number = partCount.incrementAndGet();
            assertEquals(number, request.getPartNumber());
            assertEquals(number == 1 ? partSize : 3, request.getPartSize());
            if (number == 1) { assertEquals(0, request.getInputStream().read()); }
            else { assertArrayEquals(new byte[]{7, 8, 9}, request.getInputStream().readAllBytes()); }
            UploadPartResult result = new UploadPartResult(); result.setPartNumber(number); result.setETag("part-" + number); return result;
        });
        when(client.completeMultipartUpload(any())).thenAnswer(call -> {
            CompleteMultipartUploadRequest request = call.getArgument(0);
            assertEquals("upload-1", request.getUploadId());
            assertEquals(2, request.getPartETags().size());
            assertEquals(1, request.getPartETags().get(0).getPartNumber());
            assertEquals(2, request.getPartETags().get(1).getPartNumber());
            CompleteMultipartUploadResult result = new CompleteMultipartUploadResult(); result.setVersionId("version-2"); return result;
        });
        assertEquals("version-2", storage.upload(file, "originals/video.mp4", "video/mp4", partSize + 3).getVersionId());
        assertEquals(2, partCount.get());
        verify(client, never()).putObject(any(PutObjectRequest.class));
    }

    /**
     * 验证分片失败取消上传，取消也失败时保留原错误。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void multipartFailureAbortsAndPreservesPrimaryErrorEvenIfAbortFails() throws Exception {
        Path file = directory.resolve("video.mp4");
        try (RandomAccessFile output = new RandomAccessFile(file.toFile(), "rw")) { output.setLength(8L * 1024 * 1024 + 1); }
        InitiateMultipartUploadResult initiated = new InitiateMultipartUploadResult(); initiated.setUploadId("upload-1");
        when(client.initiateMultipartUpload(any())).thenReturn(initiated);
        when(client.uploadPart(any())).thenThrow(new ClientException("network error"));
        doThrow(new ClientException("abort error")).when(client).abortMultipartUpload(any());
        BusinessException error = assertThrows(BusinessException.class,
                () -> storage.upload(file, "originals/video.mp4", "video/mp4", Files.size(file)));
        assertEquals("OSS_UNAVAILABLE", error.getCode());
        verify(client).abortMultipartUpload(argThat(request -> "upload-1".equals(request.getUploadId())));
        verify(client, never()).completeMultipartUpload(any());
    }

    /**
     * 验证多版本Bucket只精确删除指定存储版本。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void exactVersionIsDeletedForVersionedBucket() {
        storage.deleteAfterFailure(new StoredObject("private-bucket", "covers/a.png", "specific-version"));
        verify(client).deleteVersion(argThat(request -> "specific-version".equals(request.getVersionId())));
        verify(client, never()).deleteObject(anyString(), anyString());
    }

    /**
     * 验证无版本对象普通删除，补偿失败不覆盖原异常。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void unversionedObjectUsesNormalDeletionAndCleanupFailureDoesNotThrow() {
        doThrow(new ClientException("cleanup denied")).when(client).deleteObject(anyString(), anyString());
        assertDoesNotThrow(() -> storage.deleteAfterFailure(new StoredObject("private-bucket", "covers/a.png", null)));
        verify(client).deleteObject("private-bucket", "covers/a.png");
    }

    /**
     * 验证OSS授权失败向前端返回503而不是用户权限403。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void ossAccessDeniedBecomes503InsteadOfFrontend403() throws Exception {
        Path file = directory.resolve("image.png"); Files.write(file, new byte[]{1});
        OSSException denied = new OSSException("private internal message", "AccessDenied", "request-1", "host", "", "", "");
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(denied);
        BusinessException error = assertThrows(BusinessException.class, () -> storage.upload(file, "covers/a.png", "image/png", 1));
        assertEquals(503, error.getStatus().value());
        assertEquals("OSS_PERMISSION_ERROR", error.getCode());
        assertFalse(error.getMessage().contains("private internal message"));
    }

    /**
     * 验证OSS配置缺失在实际调用时报错，不在组件构造时联网。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void missingConfigurationFailsOnRequestNotOnConstruction() {
        ReflectionTestUtils.setField(storage, "client", null);
        BusinessException error = assertThrows(BusinessException.class,
                () -> storage.upload(directory.resolve("unused"), "covers/a.png", "image/png", 1));
        assertEquals("OSS_CONFIGURATION_ERROR", error.getCode());
        verifyNoInteractions(client);
    }

    /**
     * 验证封面读取核对实际长度并返回真实字节。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void coverReadEnforcesActualSizeAndReturnsBytes() {
        OSSObject object = new OSSObject();
        ObjectMetadata metadata = new ObjectMetadata(); metadata.setContentLength(3);
        object.setObjectMetadata(metadata); object.setObjectContent(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(client.getObject("private-bucket", "covers/a.png")).thenReturn(object);
        assertArrayEquals(new byte[]{1, 2, 3}, storage.readCover("private-bucket", "covers/a.png", 3));
        BusinessException tooLarge = assertThrows(BusinessException.class, () -> storage.readCover("private-bucket", "covers/a.png", 4194305));
        assertEquals(404, tooLarge.getStatus().value());
    }

    /** JUnit验证GET下载签名绑定数据库对象和精确存储版本，不附加账号Authorization或改变文件编码。 */
    @Test
    void downloadSignatureBindsGetVersionAndExpiry() throws Exception {
        java.time.OffsetDateTime expiry = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5);
        when(client.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenAnswer(call -> {
            GeneratePresignedUrlRequest request = call.getArgument(0);
            assertEquals(com.aliyun.oss.HttpMethod.GET, request.getMethod());
            assertEquals("private-bucket", request.getBucketName());
            assertEquals("originals/测试.mp4", request.getKey());
            assertEquals(expiry.toInstant().toEpochMilli(), request.getExpiration().getTime());
            assertEquals("exact-version", request.getQueryParameter().get("versionId"));
            assertFalse(request.getQueryParameter().containsKey("x-oss-process"));
            return java.net.URI.create("https://private-bucket.oss-cn-guangzhou.aliyuncs.com/test?signature=unit-test").toURL();
        });
        assertTrue(storage.createDownloadUrl("private-bucket", "originals/测试.mp4", "exact-version", expiry).startsWith("https://"));
        verify(client, never()).getObject(anyString(), anyString());
    }

    /** JUnit验证真实SDK V4签名可离线生成，支持中文文件名并且不泄漏Secret；不访问真实OSS。 */
    @Test
    void realSdkSignsHttpsLocallyWithoutCloudRequest() {
        OssProperties properties = new OssProperties();
        properties.setEndpoint("oss-cn-guangzhou.aliyuncs.com");
        properties.setRegion("cn-guangzhou");
        properties.setBucket("private-bucket");
        properties.setAccessKeyId("UNIT_TEST_ONLY_ID");
        properties.setAccessKeySecret("UNIT_TEST_ONLY_SECRET_NOT_REAL");
        ReflectionTestUtils.setField(storage, "properties", properties);
        ReflectionTestUtils.setField(storage, "client", null);
        try {
            String signed = storage.createDownloadUrl("private-bucket", "originals/测试.mp4", null,
                    java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5));
            java.net.URI uri = java.net.URI.create(signed);
            assertEquals("https", uri.getScheme());
            assertEquals("private-bucket.oss-cn-guangzhou.aliyuncs.com", uri.getHost());
            assertTrue(uri.getRawQuery().contains("x-oss-signature-version=OSS4-HMAC-SHA256"));
            assertTrue(uri.getRawQuery().contains("x-oss-signature="));
            assertFalse(uri.getRawQuery().contains("versionId="));
            assertFalse(signed.contains(properties.getAccessKeySecret()));
            assertEquals("/originals/测试.mp4", uri.getPath());
        } finally {
            storage.close();
        }
    }

    /** JUnit验证缺少可信Bucket时返回配置错误，不把无效地址签发给用户。 */
    @Test
    void downloadSigningRequiresTrustedLocation() {
        BusinessException exception = assertThrows(BusinessException.class, () -> storage.createDownloadUrl(null, "file.mp4", null,
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5)));
        assertEquals("OSS_CONFIGURATION_ERROR", exception.getCode());
        verifyNoInteractions(client);
    }
}
