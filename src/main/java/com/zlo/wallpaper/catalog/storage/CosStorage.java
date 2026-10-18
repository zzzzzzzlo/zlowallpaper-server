package com.zlo.wallpaper.catalog.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.*;
import com.qcloud.cos.region.Region;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.config.CosProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 腾讯云COS上传、封面读取、下载签名和精确删除；由Spring创建，注入ObjectStorage。
 * 使用官方COS Java SDK，延迟创建一个可复用客户端；迁移命令也可传入配置实例化使用。
 */
@Component
public class CosStorage {
    private static final Logger log = LoggerFactory.getLogger(CosStorage.class);
    private static final long PART_SIZE = 8L * 1024 * 1024;
    private final CosProperties properties;
    private COSClient client;

    /** Spring或迁移命令创建；只保存配置，此时不连接云端。 */
    @Autowired
    public CosStorage(CosProperties properties) { this.properties = properties; }

    /** 本类实际操作调用；校验本地配置并创建HTTPS客户端，返回可复用SDK对象。 */
    private synchronized COSClient getClient() {
        if (client != null) { return client; }
        if (blank(properties.getBucket()) || !properties.getBucket().matches("[a-z0-9][a-z0-9-]*-[0-9]+")
                || blank(properties.getRegion()) || !properties.getRegion().matches("[a-z]{2}-[a-z0-9]+(?:-[a-z0-9]+)*")
                || blank(properties.getSecretId()) || blank(properties.getSecretKey())) { throw configurationError(); }
        ClientConfig config = new ClientConfig(new Region(properties.getRegion()));
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(5000);
        config.setSocketTimeout(60000);
        config.setMaxErrorRetry(0); // 写请求超时由调用方展示真实结果，不自动重放整个上传。
        client = new COSClient(new BasicCOSCredentials(properties.getSecretId(), properties.getSecretKey()), config);
        return client;
    }

    /** ObjectStorage或迁移命令调用；上传完整临时文件，返回COS桶、对象键及实际存储版本。 */
    public StoredObject upload(Path file, String key, String mimeType, long size) {
        COSClient cos = getClient();
        String bucket = properties.getBucket();
        try {
            if (size <= PART_SIZE) {
                try (InputStream input = Files.newInputStream(file)) {
                    PutObjectRequest request = new PutObjectRequest(bucket, key, input, metadata(mimeType, size));
                    PutObjectResult result = cos.putObject(request);
                    return new StoredObject("COS", bucket, key, result.getVersionId());
                }
            }
            return uploadMultipart(cos, file, bucket, key, mimeType, size);
        } catch (CosServiceException exception) { throw translate(exception); }
        catch (CosClientException | IOException exception) { throw unavailable(); }
    }

    /** upload调用；顺序流式上传8MiB分片，失败尽力取消分片，返回最终对象位置。 */
    private StoredObject uploadMultipart(COSClient cos, Path file, String bucket, String key, String mimeType, long size) throws IOException {
        InitiateMultipartUploadRequest start = new InitiateMultipartUploadRequest(bucket, key);
        start.setObjectMetadata(metadata(mimeType, size));
        String uploadId = cos.initiateMultipartUpload(start).getUploadId();
        try {
            List<PartETag> parts = new ArrayList<>();
            long offset = 0;
            int partNumber = 1;
            while (offset < size) {
                long partSize = Math.min(PART_SIZE, size - offset);
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                    channel.position(offset);
                    UploadPartRequest part = new UploadPartRequest();
                    part.setBucketName(bucket); part.setKey(key); part.setUploadId(uploadId);
                    part.setPartNumber(partNumber); part.setPartSize(partSize);
                    part.setInputStream(Channels.newInputStream(channel));
                    parts.add(cos.uploadPart(part).getPartETag());
                }
                offset += partSize; partNumber++;
            }
            CompleteMultipartUploadResult result = cos.completeMultipartUpload(new CompleteMultipartUploadRequest(bucket, key, uploadId, parts));
            return new StoredObject("COS", bucket, key, result.getVersionId());
        } catch (RuntimeException | IOException exception) {
            try { cos.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, uploadId)); }
            catch (RuntimeException cleanup) { log.warn("COS分片取消失败，objectKey={}，需要检查未完成分片", key); }
            throw exception;
        }
    }

    /** 公共封面服务调用；只读取已通过可见性检查的对象，核对实际长度后返回字节。 */
    public byte[] readCover(String bucket, String key, long expectedSize) {
        if (expectedSize <= 0 || expectedSize > 4194304) { throw missing(); }
        try (COSObject object = getClient().getObject(bucket, key)) {
            if (object.getObjectMetadata().getContentLength() != expectedSize) { throw missing(); }
            try (InputStream input = object.getObjectContent()) {
                byte[] bytes = input.readNBytes((int) expectedSize + 1);
                if (bytes.length != expectedSize) { throw missing(); }
                return bytes;
            }
        } catch (CosServiceException exception) {
            if ("NoSuchKey".equals(exception.getErrorCode())) { throw missing(); }
            throw translate(exception);
        } catch (CosClientException | IOException exception) { throw unavailable(); }
    }

    /** 下载服务调用；本地签名、不下载原文件，返回到expiresAt为止有效的HTTPS GET地址。 */
    public String createDownloadUrl(String bucket, String key, String versionId, OffsetDateTime expiresAt) {
        if (blank(bucket) || blank(key)) { throw configurationError(); }
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.GET);
            request.setExpiration(Date.from(expiresAt.toInstant()));
            if (!blank(versionId)) { request.setVersionId(versionId); }
            return getClient().generatePresignedUrl(request).toExternalForm();
        } catch (CosServiceException exception) { throw translate(exception); }
        catch (CosClientException exception) { throw unavailable(); }
    }

    /** 清理服务调用；有存储版本时精确删除版本，普通新桶则删除该UUID对象。 */
    public void deleteObject(StoredObject object) {
        try {
            if (blank(object.getVersionId())) { getClient().deleteObject(object.getBucket(), object.getObjectKey()); }
            else { getClient().deleteVersion(object.getBucket(), object.getObjectKey(), object.getVersionId()); }
        } catch (CosServiceException exception) { throw translate(exception); }
        catch (CosClientException exception) { throw unavailable(); }
    }

    /** 迁移验收调用；流式下载指定对象/版本到本地校验文件，不向日志输出临时地址或密钥。 */
    public void downloadToFile(String bucket, String key, String versionId, Path destination) {
        try {
            GetObjectRequest request = new GetObjectRequest(bucket, key);
            if (!blank(versionId)) { request.setVersionId(versionId); }
            getClient().getObject(request, destination.toFile());
        } catch (CosServiceException exception) { throw translate(exception); }
        catch (CosClientException exception) { throw unavailable(); }
    }

    private ObjectMetadata metadata(String mimeType, long size) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(mimeType); metadata.setContentLength(size);
        metadata.setHeader("x-cos-forbid-overwrite", "true");
        return metadata;
    }

    private BusinessException translate(CosServiceException exception) {
        String code = exception.getErrorCode();
        log.warn("COS请求失败，errorCode={}, requestId={}", code, exception.getRequestId());
        if ("AccessDenied".equals(code)) {
            return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "COS_PERMISSION_ERROR", "COS授权不足，请联系管理员");
        }
        if ("InvalidAccessKeyId".equals(code) || "InvalidSecretId".equals(code) || "SignatureDoesNotMatch".equals(code)
                || "NoSuchBucket".equals(code) || "AuthorizationHeaderMalformed".equals(code)) { return configurationError(); }
        return unavailable();
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private BusinessException configurationError() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "COS_CONFIGURATION_ERROR", "COS配置不可用，请联系管理员");
    }
    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "COS_UNAVAILABLE", "云存储暂不可用，上传结果可能未确认，请勿自动重试");
    }
    private BusinessException missing() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "封面不存在或不可访问");
    }

    /** Spring销毁或迁移命令结束时调用；关闭已创建的COS连接池，无返回值。 */
    @PreDestroy
    public synchronized void close() { if (client != null) { client.shutdown(); } }
}
