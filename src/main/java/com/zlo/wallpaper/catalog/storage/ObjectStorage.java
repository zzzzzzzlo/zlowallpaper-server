package com.zlo.wallpaper.catalog.storage;

import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * 文件组件的统一存储入口，Spring注入上传、封面、下载和清理Service。
 * 新上传按配置选择服务；读取/删除严格按文件自身provider选择，支持迁移期间混合数据。
 * 只在本类分流，不在各业务Service重复写COS/OSS的SDK调用。
 */
@Component
public class ObjectStorage {
    @Autowired private OssStorage oss;
    @Autowired private CosStorage cos;
    @Value("${wallpaper.storage.provider:OSS}") private String provider;

    /** 上传组件调用；参数为已经校验的临时文件、对象键、类型和大小，返回实际存储位置。 */
    public StoredObject upload(Path file, String key, String mimeType, long size) {
        if ("COS".equals(provider)) { return cos.upload(file, key, mimeType, size); }
        if ("OSS".equals(provider)) { return oss.upload(file, key, mimeType, size); }
        throw unsupported();
    }

    /** CoverService调用；身份/商品可见性检查已经完成，返回指定存储中的封面字节。 */
    public byte[] readCover(String fileProvider, String bucket, String key, long expectedSize) {
        if ("COS".equals(fileProvider)) { return cos.readCover(bucket, key, expectedSize); }
        if ("OSS".equals(fileProvider)) { return oss.readCover(bucket, key, expectedSize); }
        throw unsupported();
    }

    /** DownloadService调用；按已购文件的provider签名，返回固定期限的HTTPS下载地址。 */
    public String createDownloadUrl(String fileProvider, String bucket, String key, String versionId, OffsetDateTime expiresAt) {
        if ("COS".equals(fileProvider)) { return cos.createDownloadUrl(bucket, key, versionId, expiresAt); }
        if ("OSS".equals(fileProvider)) { return oss.createDownloadUrl(bucket, key, versionId, expiresAt); }
        throw unsupported();
    }

    /** 文件清理调用；按待清理对象自己的provider删除，不受当前新上传配置影响。 */
    public void deleteObject(StoredObject object) {
        if ("COS".equals(object.getProvider())) { cos.deleteObject(object); return; }
        if ("OSS".equals(object.getProvider())) { oss.deleteObject(object); return; }
        throw unsupported();
    }

    private BusinessException unsupported() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_NOT_SUPPORTED", "当前云存储方式未配置");
    }
}
