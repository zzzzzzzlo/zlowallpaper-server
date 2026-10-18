package com.zlo.wallpaper.catalog.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zlo.wallpaper.catalog.entity.Wallpaper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;

/**
 * 声明管理员商品读写、锁定读取、发布状态修改与文件引用统计SQL。
 * 创建方式：MyBatis扫描@Mapper后生成代理，SQL实现在同模块Mapper XML中。
 * 注入位置：AdminWallpaperIntegrationTest.mapper、AdminWallpaperMySqlIT.mapper、AdminWallpaperServiceImpl.mapper、OrderMySqlIT.products、ProductFileCleanupService.mapper、WallpaperPersistenceService.mapper。
 * 数据库映射位置：src/main/resources/mapper/catalog/AdminWallpaperMapper.xml。
 */
@Mapper
public interface AdminWallpaperMapper {
    /**
     * 执行管理商品分页SQL，分页限制和计数由MyBatis-Plus统一处理。
     * 调用/引用位置：AdminWallpaperMySqlIT.generatedKeyRoundTripMapsBothInlineFilesAndCategoryName、AdminWallpaperServiceImpl.getWallpapers。
     *
     * @param page 分页插件对象，控制查询范围并接收总数
     * @return 分页结果，包含当前页记录和插件计算的总数
     */
    IPage<Wallpaper> selectPage(Page<Wallpaper> page);
    /**
     * 按主键读取数据库记录；不存在时返回null。
     * 调用/引用位置：AdminWallpaperIntegrationTest.cleanupNeverDeletesFilesStillReferencedByAnyProductOrActiveUpload、AdminWallpaperIntegrationTest.concurrentFileReplacementReturnsConflictAndKeepsOtherRequestFile、AdminWallpaperIntegrationTest.createWritesBothFilesInProductAndReturnsDraftWithoutCloudLocations、AdminWallpaperIntegrationTest.draftPreviewIsAdminOnlyAndPublishingControlsAllPublicQueries、AdminWallpaperIntegrationTest.partialUpdatePreservesFilesAndPublicationThenReplacesOnlyOriginal、AdminWallpaperMySqlIT.generatedKeyRoundTripMapsBothInlineFilesAndCategoryName、AdminWallpaperMySqlIT.partialUpdatePreservesCoverAndPublishingControlsReadVisibility、AdminWallpaperServiceImpl.updateWallpaper、WallpaperPersistenceService.publish、WallpaperPersistenceService.update。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Wallpaper selectById(@Param("id") long id);
    /**
     * 按主键执行事务内锁定读取，防止并发修改基于旧状态。
     * 调用/引用位置：AdminWallpaperMySqlIT.partialUpdatePreservesCoverAndPublishingControlsReadVisibility、WallpaperPersistenceService.publish、WallpaperPersistenceService.update。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Wallpaper selectByIdForUpdate(@Param("id") long id);
    /**
     * 把实体写入对应数据库表，并在有自增主键时回填id。
     * 调用/引用位置：AdminWallpaperMySqlIT.generatedKeyRoundTripMapsBothInlineFilesAndCategoryName、AdminWallpaperMySqlIT.partialUpdatePreservesCoverAndPublishingControlsReadVisibility、AdminWallpaperMySqlIT.realDatabaseRejectsIncompleteFileAndInvalidPublishedState、OrderMySqlIT.fixtures、WallpaperPersistenceService.create。
     *
     * @param wallpaper 当前商品实体，包含资料与当前文件元数据
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int insert(Wallpaper wallpaper);
    /**
     * 更新实体的允许字段或指定文件列，保留不应覆盖的省略字段。
     * 调用/引用位置：AdminWallpaperMySqlIT.partialUpdatePreservesCoverAndPublishingControlsReadVisibility、WallpaperPersistenceService.update。
     *
     * @param wallpaper 当前商品实体，包含资料与当前文件元数据
     * @param replaceOriginal 是否写入新的原文件字段；false时保留已有原文件
     * @param replaceCover 是否写入新的封面字段；false时保留已有封面
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int update(@Param("wallpaper") Wallpaper wallpaper,
      @Param("replaceOriginal") boolean replaceOriginal, @Param("replaceCover") boolean replaceCover);
    /**
     * 只更新商品发布状态、上架时间和修改时间。
     * 调用/引用位置：AdminWallpaperMySqlIT.partialUpdatePreservesCoverAndPublishingControlsReadVisibility、AdminWallpaperMySqlIT.realDatabaseRejectsIncompleteFileAndInvalidPublishedState、WallpaperPersistenceService.publish。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     * @param publishedAt 商品的UTC上架时间；是否保留或清空由发布状态业务决定
     * @param updatedAt 本次数据库更新使用的UTC修改时间
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int updatePublication(@Param("id") long id, @Param("status") String status,
                          @Param("publishedAt") LocalDateTime publishedAt, @Param("updatedAt") LocalDateTime updatedAt);
    /**
     * 统计仍引用指定存储对象的商品数量，供删除云文件前再次确认。
     * 调用/引用位置：AdminWallpaperIntegrationTest.createWritesBothFilesInProductAndReturnsDraftWithoutCloudLocations、AdminWallpaperMySqlIT.generatedKeyRoundTripMapsBothInlineFilesAndCategoryName、ProductFileCleanupService.attempt。
     *
     * @param bucket 配置或可信数据库记录中的OSS Bucket名称
     * @param objectKey 服务器生成或从可信数据库取得的OSS对象键
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    long countFileReferences(@Param("bucket") String bucket, @Param("objectKey") String objectKey);
}
