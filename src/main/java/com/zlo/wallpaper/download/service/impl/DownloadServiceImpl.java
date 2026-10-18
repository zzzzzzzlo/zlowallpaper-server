package com.zlo.wallpaper.download.service.impl;

import com.zlo.wallpaper.catalog.service.CatalogIds;
import com.zlo.wallpaper.catalog.storage.ObjectStorage;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.download.mapper.DownloadMapper;
import com.zlo.wallpaper.download.model.DownloadFileRow;
import com.zlo.wallpaper.download.service.DownloadService;
import com.zlo.wallpaper.download.vo.DownloadGrantVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 在一个入口内完成身份、权益与文件判断，再签发短时OSS下载URL，不代理大文件传输。
 * 创建方式：Spring组件扫描创建；按DownloadService接口注入DownloadController。
 */
@Service
public class DownloadServiceImpl implements DownloadService {
    private static final int DOWNLOAD_SECONDS = 300;
    @Autowired private DownloadMapper downloadMapper;
    @Autowired private ObjectStorage storage;

    /**
     * 单次联合查询确认当前权益及当前文件，再生成5分钟下载授权。
     * 调用位置：DownloadController.createDownloadGrant；主流程集中在此，不另拆业务辅助类。
     * @param id 请求路径中的商品字符串ID
     * @return 与当前文件SHA及大小对应的下载授权；未购403，文件未就绪409，存储配置问题503
     */
    @Override
    public DownloadGrantVO createDownloadGrant(String id) {
        // 1. 账号来自认证上下文，路径参数只决定申请哪件商品，不接受前端文件地址。
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请先登录");
        }
        CatalogIds.validatePathId(id);
        Long wallpaperId = CatalogIds.findDatabaseId(id);

        // 2. 同一条SELECT同时读取权益和原文件快照；没有权益就不能得到内部OSS位置。
        DownloadFileRow file = wallpaperId == null ? null : downloadMapper.selectOwnedDownloadFile(userId, wallpaperId);
        if (file == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "NOT_OWNED", "尚未拥有该壁纸");
        }
        if (!"READY".equals(file.getStatus()) || file.getObjectKey() == null || file.getFileExtension() == null
                || file.getSizeBytes() == null || file.getSha256() == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "FILE_NOT_READY", "壁纸文件尚未准备好，请稍后重试");
        }
        if (!"OSS".equals(file.getStorageProvider()) && !"COS".equals(file.getStorageProvider())) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_NOT_SUPPORTED", "当前原文件尚未接入云存储下载");
        }

        // 3. 无数据库写入或远程文件读取，不持有行锁。签发后权益变化不会提前撤销这条5分钟URL。
        // 【并发说明】URL固定本次文件对象/存储版本；更换文件后旧对象被清理，旧URL可能失效，重新申请即可。
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).plusSeconds(DOWNLOAD_SECONDS);
        String url = storage.createDownloadUrl(file.getStorageProvider(), file.getBucket(), file.getObjectKey(), file.getObjectVersionId(), expiresAt);

        // 4. URL直接GET得到OSS文件字节，不携带本系统Bearer；大小和SHA供客户端流式校验。
        DownloadGrantVO result = new DownloadGrantVO();
        result.setWallpaperId(wallpaperId.toString());
        result.setUrl(url);
        result.setExpiresAt(expiresAt);
        result.setFileExtension(file.getFileExtension());
        result.setSizeBytes(file.getSizeBytes().toString());
        result.setSha256(file.getSha256());
        return result;
    }
}
