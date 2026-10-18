package com.zlo.wallpaper.catalog.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.*;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.config.OssProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.time.OffsetDateTime;
import com.aliyun.oss.HttpMethod;

/**
 * 封装私有OSS上传、分片、封面读取、下载签名和精确删除；客户端在实际调用时延迟创建。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：CoverService.ossStorage、FileUploadServiceImpl.ossStorage、ProductFileCleanupService.storage、DownloadServiceImpl.ossStorage。
 */
@Component
public class OssStorage {
    private static final Logger log = LoggerFactory.getLogger(OssStorage.class);
    private static final long PART_SIZE = 8L * 1024 * 1024;
    @Autowired private OssProperties properties;
    private OSS client;

    /**
     * 校验OSS配置并延迟创建客户端，已有客户端则复用。
     * 调用/引用位置：OssStorage.deleteObject、OssStorage.readCover、OssStorage.upload、OssStorage.createDownloadUrl。
     * 输入参数：无。
     *
     * @return 上述处理得到的OSS结果；业务校验失败时通过异常返回错误
     */
    private synchronized OSS getClient() {
        if (client != null) { return client; }
        if (blank(properties.getBucket()) || blank(properties.getRegion()) || blank(properties.getEndpoint())
                || blank(properties.getAccessKeyId()) || blank(properties.getAccessKeySecret())) {
            throw configurationError();
        }
        String region = properties.getRegion().strip();
        if (region.startsWith("oss-") || !region.matches("[a-z0-9]+(?:-[a-z0-9]+)+")) {
            throw configurationError();
        }
        String endpoint = properties.getEndpoint().strip();
        if (!endpoint.contains("://")) { endpoint = "https://" + endpoint; }
        // 密钥只发往显式配置的阿里云 HTTPS 服务，不接受任意 URL。
        if (!endpoint.matches("https://oss-[a-z0-9-]+\\.aliyuncs\\.com/?")) { throw configurationError(); }
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);
        configuration.setConnectionTimeout(5000);
        configuration.setSocketTimeout(60000);
        configuration.setMaxErrorRetry(0); // 超时不自动重放整个写请求。
        try {
            client = OSSClientBuilder.create().endpoint(endpoint).region(region)
                    .credentialsProvider(new DefaultCredentialProvider(
                            properties.getAccessKeyId(), properties.getAccessKeySecret()))
                    .clientConfiguration(configuration).build();
            return client;
        } catch (RuntimeException exception) {
            throw configurationError();
        }
    }

    /**
     * 根据实际文件大小选择普通或分片OSS上传，返回准确对象位置及存储版本。
     * 调用/引用位置：FileUploadServiceImpl.uploadFile、FileUploadServiceTest.cloudFailureKeepsErrorAndCleansTemporaryFile、FileUploadServiceTest.returnsTrustedFileMetadataAndExactOssObjectVersionWithoutDatabase、OssStorageTest.missingConfigurationFailsOnRequestNotOnConstruction、OssStorageTest.multipartFailureAbortsAndPreservesPrimaryErrorEvenIfAbortFails、OssStorageTest.multipartUsesCorrectOffsetsSizesAndOrderedParts、OssStorageTest.ossAccessDeniedBecomes503InsteadOfFrontend403、OssStorageTest.simpleUploadStreamsFileAndPreservesContentTypeAndVersion。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @param objectKey 服务器生成或从可信数据库取得的OSS对象键
     * @param mimeType 媒体检查得到的标准Content-Type
     * @param size 经过实际读取校验的文件字节数
     * @return OSS实际Bucket、对象键和版本标识，用于之后精确清理
     */
    public StoredObject upload(Path file, String objectKey, String mimeType, long size) {
        OSS oss = getClient();
        String bucket = properties.getBucket();
        try {
            if (size <= PART_SIZE) {
                try (InputStream input = Files.newInputStream(file)) {
                    ObjectMetadata metadata = metadata(mimeType, size);
                    PutObjectResult result = oss.putObject(new PutObjectRequest(bucket, objectKey, input, metadata));
                    return new StoredObject(bucket, objectKey, result.getVersionId());
                }
            }
            return uploadMultipart(oss, file, bucket, objectKey, mimeType, size);
        } catch (OSSException exception) {
            throw translate(exception);
        } catch (ClientException | IOException exception) {
            throw unavailable();
        }
    }

    /**
     * 本地签名生成私有对象的GET URL，不下载文件、不把云访问密钥返回客户端。
     * 调用位置：DownloadServiceImpl.createDownloadGrant；测试直接调用验证SDK签名参数。
     * @param bucket 可信数据库记录中的Bucket
     * @param objectKey 可信数据库记录中的当前原文件对象键
     * @param versionId 可选OSS存储版本，固定本次文件对象，不是商品历史版本
     * @param expiresAt 本次授权统一的UTC过期时间
     * @return 可直接GET文件的HTTPS签名URL，禁止写入日志
     */
    public String createDownloadUrl(String bucket, String objectKey, String versionId, OffsetDateTime expiresAt) {
        if (blank(bucket) || blank(objectKey)) { throw configurationError(); }
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.GET);
            request.setExpiration(Date.from(expiresAt.toInstant()));
            if (!blank(versionId)) { request.addQueryParameter("versionId", versionId); }
            return getClient().generatePresignedUrl(request).toExternalForm();
        } catch (OSSException exception) {
            throw translate(exception);
        } catch (ClientException exception) {
            throw unavailable();
        }
    }

    /**
     * 逐片读取临时文件并上传，完成后返回存储对象；失败时取消未完成分片。
     * 调用/引用位置：OssStorage.upload。
     *
     * @param oss 已初始化的OSS SDK客户端
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @param bucket 配置或可信数据库记录中的OSS Bucket名称
     * @param objectKey 服务器生成或从可信数据库取得的OSS对象键
     * @param mimeType 媒体检查得到的标准Content-Type
     * @param size 经过实际读取校验的文件字节数
     * @return OSS实际Bucket、对象键和版本标识，用于之后精确清理
     */
    private StoredObject uploadMultipart(OSS oss, Path file, String bucket, String objectKey,
                                         String mimeType, long size) throws IOException {
        InitiateMultipartUploadRequest start = new InitiateMultipartUploadRequest(bucket, objectKey);
        start.setObjectMetadata(metadata(mimeType, size));
        String uploadId = oss.initiateMultipartUpload(start).getUploadId();
        try {
            List<PartETag> parts = new ArrayList<>();
            long offset = 0;
            int partNumber = 1;
            while (offset < size) {
                long partSize = Math.min(PART_SIZE, size - offset);
                // 每次只读取当前分片；position 定位文件，不把整个文件载入内存。
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                    channel.position(offset);
                    UploadPartRequest part = new UploadPartRequest();
                    part.setBucketName(bucket);
                    part.setKey(objectKey);
                    part.setUploadId(uploadId);
                    part.setInputStream(Channels.newInputStream(channel));
                    part.setPartSize(partSize);
                    part.setPartNumber(partNumber);
                    parts.add(oss.uploadPart(part).getPartETag());
                }
                offset += partSize;
                partNumber++;
            }
            CompleteMultipartUploadResult result = oss.completeMultipartUpload(
                    new CompleteMultipartUploadRequest(bucket, objectKey, uploadId, parts));
            return new StoredObject(bucket, objectKey, result.getVersionId());
        } catch (RuntimeException | IOException exception) {
            try {
                oss.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, objectKey, uploadId));
            } catch (RuntimeException cleanupException) {
                log.warn("OSS 分片取消失败，objectKey={}；需要清理未完成分片", objectKey);
            }
            throw exception;
        }
    }

    /**
     * 构造可信Content-Type、长度和禁止覆盖的OSS请求元数据。
     * 调用/引用位置：OssStorage.upload、OssStorage.uploadMultipart。
     *
     * @param mimeType 媒体检查得到的标准Content-Type
     * @param size 经过实际读取校验的文件字节数
     * @return 可信Content-Type、文件长度和禁止覆盖标志组成的OSS上传元数据
     */
    private ObjectMetadata metadata(String mimeType, long size) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(mimeType);
        metadata.setContentLength(size);
        metadata.setHeader("x-oss-forbid-overwrite", "true");
        return metadata;
    }

    /**
     * 补偿失败操作产生的OSS文件，删除失败不覆盖原始业务异常。
     * 调用/引用位置：OssStorageTest.exactVersionIsDeletedForVersionedBucket、OssStorageTest.unversionedObjectUsesNormalDeletionAndCleanupFailureDoesNotThrow。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param stored OSS返回的实际Bucket、对象键与存储版本
     */
    public void deleteAfterFailure(StoredObject stored) {
        try {
            deleteObject(stored);
        } catch (RuntimeException exception) {
            // 补偿失败不覆盖原异常；日志不打印访问密钥或 SDK 原始请求。
            log.error("OSS 失败补偿未完成，bucket={}, objectKey={}, versionId={}；需管理员清理",
                    stored.getBucket(), stored.getObjectKey(), stored.getVersionId());
        }
    }

    /**
     * 按对象位置或精确存储版本删除云文件，失败时通知清理服务保留任务。
     * 调用/引用位置：AdminWallpaperIntegrationTest.changedCategoryAndRevokedRoleDuringLongUploadAreRecheckedAndBothFilesReclaimed、AdminWallpaperIntegrationTest.cleanupNeverDeletesFilesStillReferencedByAnyProductOrActiveUpload、AdminWallpaperIntegrationTest.concurrentFileReplacementReturnsConflictAndKeepsOtherRequestFile、AdminWallpaperIntegrationTest.createWritesBothFilesInProductAndReturnsDraftWithoutCloudLocations、AdminWallpaperIntegrationTest.databaseFailureRollsBackProductAndRetainsFailedDeletesForRetry、AdminWallpaperIntegrationTest.originalUploadFailureReclaimsAlreadyUploadedCoverAndDoesNotInsert、AdminWallpaperIntegrationTest.partialUpdatePreservesFilesAndPublicationThenReplacesOnlyOriginal、OssStorage.deleteAfterFailure、ProductFileCleanupService.attempt。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param stored OSS返回的实际Bucket、对象键与存储版本
     */
    public void deleteObject(StoredObject stored) {
        try {
            OSS oss = getClient();
            if (blank(stored.getVersionId())) { oss.deleteObject(stored.getBucket(), stored.getObjectKey()); }
            else { oss.deleteVersion(new DeleteVersionRequest(stored.getBucket(), stored.getObjectKey(), stored.getVersionId())); }
        } catch (OSSException exception) { throw translate(exception); }
        catch (ClientException exception) { throw unavailable(); }
    }

    /**
     * 从指定私有OSS对象读取最多4MiB封面字节，并核对实际大小。
     * 调用/引用位置：AdminWallpaperIntegrationTest.fixtures、CatalogIntegrationTest.ossCoverReadsOnlyPublishedProductsCoverLocation、CoverService.readFile、OssStorageTest.coverReadEnforcesActualSizeAndReturnsBytes。
     *
     * @param bucket 配置或可信数据库记录中的OSS Bucket名称
     * @param objectKey 服务器生成或从可信数据库取得的OSS对象键
     * @param expectedSize 数据库保存的预期封面字节数，读取时还要核对真实长度
     * @return 实际封面字节；文件不存在、大小不符或云请求失败时抛业务异常
     */
    public byte[] readCover(String bucket, String objectKey, long expectedSize) {
        if (expectedSize <= 0 || expectedSize > 4194304) { throw missing(); }
        try (OSSObject object = getClient().getObject(bucket, objectKey)) {
            if (object.getObjectMetadata().getContentLength() != expectedSize) { throw missing(); }
            try (InputStream input = object.getObjectContent()) {
                byte[] bytes = input.readNBytes((int) expectedSize + 1);
                if (bytes.length != expectedSize) { throw missing(); }
                return bytes;
            }
        } catch (OSSException exception) {
            if ("NoSuchKey".equals(exception.getErrorCode())) { throw missing(); }
            throw translate(exception);
        } catch (ClientException | IOException exception) {
            throw unavailable();
        }
    }

    /**
     * 将OSS错误映射为配置、权限或暂不可用业务异常，避免把云端权限错误当作用户403。
     * 调用/引用位置：OssStorage.deleteObject、OssStorage.readCover、OssStorage.upload。
     *
     * @param exception 当前捕获的异常，仅向前端返回安全业务说明
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException translate(OSSException exception) {
        String code = exception.getErrorCode();
        log.warn("OSS 请求失败，errorCode={}, ossRequestId={}", code, exception.getRequestId());
        if ("AccessDenied".equals(code)) {
            return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_PERMISSION_ERROR", "OSS 授权不足，请联系管理员");
        }
        if ("InvalidAccessKeyId".equals(code) || "SignatureDoesNotMatch".equals(code)
                || "NoSuchBucket".equals(code) || "AuthorizationHeaderMalformed".equals(code)) {
            return configurationError();
        }
        return unavailable();
    }

    /**
     * 判断字符串是否为null或仅包含空白。
     * 调用/引用位置：OssStorage.deleteObject、OssStorage.getClient。
     *
     * @param value 当前待读取、校验或转换的输入值
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean blank(String value) { return value == null || value.isBlank(); }
    /**
     * 构造OSS配置不可用的503业务异常。
     * 调用/引用位置：OssStorage.getClient、OssStorage.translate。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException configurationError() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_CONFIGURATION_ERROR", "OSS 配置不可用，请联系管理员");
    }
    /**
     * 构造OSS暂不可用的503业务异常，提示上传结果可能未确认。
     * 调用/引用位置：OssStorage.deleteObject、OssStorage.readCover、OssStorage.translate、OssStorage.upload。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_UNAVAILABLE", "云存储暂不可用，上传结果可能未确认，请勿自动重试");
    }
    /**
     * 构造当前商品或封面不存在的404业务异常。
     * 调用/引用位置：OssStorage.readCover。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException missing() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "封面不存在或不可公开访问");
    }

    /**
     * 应用关闭时释放已创建的OSS客户端连接资源。
     * 框架调用位置：Spring关闭组件时执行资源回收。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @PreDestroy
    public synchronized void close() {
        if (client != null) { client.shutdown(); }
    }
}
