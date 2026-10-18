package com.zlo.wallpaper.catalog;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.*;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.catalog.storage.CosStorage;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.config.CosProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证COS SDK适配的流式上传、权限错误、精确删除和签名；只使用本地替身，不消耗云流量。 */
class CosStorageTest {
    @TempDir Path directory;
    private CosStorage storage;
    private COSClient client;

    @BeforeEach
    void setup() {
        CosProperties properties = new CosProperties(); properties.setBucket("private-1250000000");
        storage = new CosStorage(properties); client = mock(COSClient.class);
        ReflectionTestUtils.setField(storage, "client", client);
    }

    @Test
    void simpleUploadReturnsActualCosLocationAndMetadata() throws Exception {
        Path file = directory.resolve("image.png"); Files.write(file, new byte[]{1,2,3});
        when(client.putObject(any(PutObjectRequest.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            assertEquals("private-1250000000", request.getBucketName());
            assertEquals("image/png", request.getMetadata().getContentType());
            assertEquals(3, request.getMetadata().getContentLength());
            assertEquals("true", request.getMetadata().getRawMetadata().get("x-cos-forbid-overwrite"));
            assertArrayEquals(new byte[]{1,2,3}, request.getInputStream().readAllBytes());
            PutObjectResult result = new PutObjectResult(); result.setVersionId("v1"); return result;
        });
        StoredObject result = storage.upload(file,"covers/a.png","image/png",3);
        assertEquals("COS",result.getProvider()); assertEquals("v1",result.getVersionId());
        assertEquals("private-1250000000",result.getBucket()); verify(client,never()).initiateMultipartUpload(any());
    }

    @Test
    void multipartReadsEachOffsetAndCompletesInOrder() throws Exception {
        long partSize = 8L*1024*1024;
        Path file = directory.resolve("video.mp4");
        try (RandomAccessFile output = new RandomAccessFile(file.toFile(),"rw")) {
            output.setLength(partSize+3); output.seek(partSize); output.write(new byte[]{7,8,9});
        }
        InitiateMultipartUploadResult start = new InitiateMultipartUploadResult(); start.setUploadId("upload-1");
        when(client.initiateMultipartUpload(any())).thenReturn(start);
        AtomicInteger count = new AtomicInteger();
        when(client.uploadPart(any())).thenAnswer(call -> {
            UploadPartRequest request = call.getArgument(0); int number = count.incrementAndGet();
            assertEquals(number,request.getPartNumber()); assertEquals(number==1?partSize:3,request.getPartSize());
            if(number==1) { assertEquals(0,request.getInputStream().read()); }
            else { assertArrayEquals(new byte[]{7,8,9},request.getInputStream().readAllBytes()); }
            UploadPartResult result = new UploadPartResult(); result.setPartNumber(number); result.setETag("part-"+number); return result;
        });
        when(client.completeMultipartUpload(any())).thenAnswer(call -> {
            CompleteMultipartUploadRequest request = call.getArgument(0);
            assertEquals(2,request.getPartETags().size()); assertEquals(1,request.getPartETags().get(0).getPartNumber());
            assertEquals(2,request.getPartETags().get(1).getPartNumber());
            return new CompleteMultipartUploadResult();
        });
        assertEquals("COS",storage.upload(file,"originals/a.mp4","video/mp4",partSize+3).getProvider());
        verify(client,never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void multipartFailureAbortsWithoutHidingPrimaryError() throws Exception {
        Path file = directory.resolve("video.mp4");
        try(RandomAccessFile output = new RandomAccessFile(file.toFile(),"rw")) { output.setLength(8L*1024*1024+1); }
        InitiateMultipartUploadResult start = new InitiateMultipartUploadResult(); start.setUploadId("failed");
        when(client.initiateMultipartUpload(any())).thenReturn(start);
        when(client.uploadPart(any())).thenThrow(new CosClientException("network failure"));
        doThrow(new CosClientException("abort failure")).when(client).abortMultipartUpload(any());
        BusinessException error = assertThrows(BusinessException.class,()->storage.upload(file,"originals/a.mp4","video/mp4",Files.size(file)));
        assertEquals("COS_UNAVAILABLE",error.getCode()); verify(client).abortMultipartUpload(any());
        verify(client,never()).completeMultipartUpload(any());
    }

    @Test
    void coverChecksLengthAndPermissionErrorsStayServiceErrors() {
        COSObject object = new COSObject(); ObjectMetadata metadata = new ObjectMetadata(); metadata.setContentLength(3);
        object.setObjectMetadata(metadata);
        object.setObjectContent(new COSObjectInputStream(new ByteArrayInputStream(new byte[]{1,2,3}),mock(org.apache.http.client.methods.HttpRequestBase.class)));
        when(client.getObject("private-1250000000","covers/a.png")).thenReturn(object);
        assertArrayEquals(new byte[]{1,2,3},storage.readCover("private-1250000000","covers/a.png",3));
        CosServiceException denied = new CosServiceException("private request information"); denied.setErrorCode("AccessDenied");
        when(client.getObject("private-1250000000","covers/b.png")).thenThrow(denied);
        BusinessException error = assertThrows(BusinessException.class,()->storage.readCover("private-1250000000","covers/b.png",3));
        assertEquals(503,error.getStatus().value()); assertEquals("COS_PERMISSION_ERROR",error.getCode());
        assertFalse(error.getMessage().contains("private request"));
    }

    @Test
    void versionedCleanupDeletesOnlyTheSpecifiedVersion() {
        storage.deleteObject(new StoredObject("COS","private-1250000000","covers/a.png","version-1"));
        verify(client).deleteVersion("private-1250000000","covers/a.png","version-1");
        verify(client,never()).deleteObject(anyString(),anyString());
    }

    @Test
    void signedDownloadIsHttpsAndKeepsTheObjectVersionWithoutNetwork() {
        CosProperties properties = new CosProperties(); properties.setBucket("private-1250000000"); properties.setRegion("ap-guangzhou");
        properties.setSecretId("test-id"); properties.setSecretKey("test-key");
        CosStorage signer = new CosStorage(properties);
        try {
            String url = signer.createDownloadUrl(properties.getBucket(),"zlowallpaper/originals/a.mp4","version-1",OffsetDateTime.now().plusMinutes(5));
            assertTrue(url.startsWith("https://private-1250000000.cos.ap-guangzhou.myqcloud.com/"));
            String decoded = java.net.URLDecoder.decode(url,java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(decoded.contains("versionId=version-1"),decoded); assertTrue(decoded.contains("q-signature="),decoded);
        } finally { signer.close(); }
    }

    @Test
    void missingConfigurationFailsOnlyOnUse() {
        CosStorage unconfigured = new CosStorage(new CosProperties());
        BusinessException error = assertThrows(BusinessException.class,()->unconfigured.createDownloadUrl("bucket","a",null,OffsetDateTime.now().plusMinutes(5)));
        assertEquals("COS_CONFIGURATION_ERROR",error.getCode());
    }
}
