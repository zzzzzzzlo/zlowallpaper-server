package com.zlo.wallpaper.catalog.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 承接已上传且校验完成的当前文件元数据，内嵌到商品实体中。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：FileUploadServiceImpl.uploadFile。
 * 数据库映射位置：src/main/resources/mapper/catalog/AdminWallpaperMapper.xml。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class UploadedFile {
    private String purpose;
    private String originalFilename;
    private String storageProvider;
    private String bucket;
    private String objectKey;
    private String objectVersionId;
    private String mimeType;
    private String fileExtension;
    private Long sizeBytes;
    private String sha256;
    private Integer width;
    private Integer height;
    private BigDecimal fps;
    private String status;
    private Long uploadedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
