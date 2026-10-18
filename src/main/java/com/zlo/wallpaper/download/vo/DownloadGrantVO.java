package com.zlo.wallpaper.download.vo;

import lombok.Data;
import java.time.OffsetDateTime;

/**
 * 返回短时下载授权及当前文件校验信息；URL本身授权，下载时不转发账号Bearer。
 * 创建方式：DownloadServiceImpl.createDownloadGrant通过new组装；Jackson序列化返回前端。
 * 不作为Spring组件注入，Lombok生成普通字段访问方法。
 */
@Data
public class DownloadGrantVO {
    private String wallpaperId;
    private String url;
    private OffsetDateTime expiresAt;
    private String fileExtension;
    private String sizeBytes;
    private String sha256;
}
