package com.zlo.wallpaper.catalog.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

/**
 * 保存一次OSS/COS操作的provider、bucket、对象键和实际存储版本标识，用于精确补偿删除。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
@NoArgsConstructor
public class StoredObject {
    // 缺省OSS，兼容迁移前已经写到磁盘、没有provider字段的清理任务。
    private String provider = "OSS";
    private String bucket;
    private String objectKey;
    private String versionId;

    /** OSS SDK和已有清理任务使用的对象位置；不查询云端。 */
    public StoredObject(String bucket, String objectKey, String versionId) {
        this("OSS", bucket, objectKey, versionId);
    }

    /** 两种云存储返回的对象位置，provider决定后续读取和删除使用哪一家SDK。 */
    public StoredObject(String provider, String bucket, String objectKey, String versionId) {
        this.provider = provider;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.versionId = versionId;
    }
}
