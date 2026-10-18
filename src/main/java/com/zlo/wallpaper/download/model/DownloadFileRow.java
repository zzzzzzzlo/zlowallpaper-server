package com.zlo.wallpaper.download.model;

import lombok.Data;

/**
 * 承接本账号权益对应的当前原文件位置和校验信息；这些内部存储字段不直接返回前端。
 * 创建方式：MyBatis映射DownloadMapper.xml查询结果；不作为Spring组件注入。
 * 使用位置：DownloadServiceImpl.createDownloadGrant。
 */
@Data
public class DownloadFileRow {
    private String storageProvider;
    private String bucket;
    private String objectKey;
    private String objectVersionId;
    private String fileExtension;
    private Long sizeBytes;
    private String sha256;
    private String status;
}
