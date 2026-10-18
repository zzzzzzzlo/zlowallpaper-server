package com.zlo.wallpaper.catalog.service;

import com.zlo.wallpaper.catalog.model.UploadedFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 声明文件检查和上传能力，供商品保存流程调用。
 * 创建方式：接口本身不实例化，由对应@Service实现提供注入对象。
 * 注入位置：AdminWallpaperServiceImpl.uploader。
 */
public interface FileUploadService {
    /**
     * 校验当前管理员和上传文件，流式计算哈希并检查真实媒体后上传OSS。
     * 调用/引用位置：AdminWallpaperIntegrationTest.changedCategoryAndRevokedRoleDuringLongUploadAreRecheckedAndBothFilesReclaimed、AdminWallpaperIntegrationTest.concurrentFileReplacementReturnsConflictAndKeepsOtherRequestFile、AdminWallpaperIntegrationTest.databaseFailureRollsBackProductAndRetainsFailedDeletesForRetry、AdminWallpaperIntegrationTest.fixtures、AdminWallpaperIntegrationTest.originalUploadFailureReclaimsAlreadyUploadedCoverAndDoesNotInsert、AdminWallpaperServiceImpl.createWallpaper、AdminWallpaperServiceImpl.updateWallpaper。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @param purpose 文件用途ORIGINAL或COVER，决定允许类型及大小上限
     * @return 检查及上传完成的文件显示名、存储位置、哈希、大小和媒体信息
     */
    UploadedFile uploadFile(MultipartFile file, String purpose);
}
