package com.zlo.wallpaper.catalog.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zlo.wallpaper.catalog.model.CoverRow;
import com.zlo.wallpaper.catalog.model.WallpaperRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 声明公开与本账号已购商品查询，以及对应权限下的封面位置查询SQL。
 * 创建方式：MyBatis扫描@Mapper后生成代理，SQL实现在同模块Mapper XML中。
 * 注入位置：AdminWallpaperMySqlIT.publicMapper、CatalogMySqlIT.wallpaperMapper、CoverService的构造器、WallpaperServiceImpl的构造器。
 * 数据库映射位置：src/main/resources/mapper/catalog/WallpaperMapper.xml。
 */
@Mapper
public interface WallpaperMapper {
    /**
     * 按本账号权益查询商品，不限制必须上架；SQL通过user_id限制可见范围。
     * 调用位置：仅WallpaperServiceImpl.getMyWallpapers，公开入口不能调用此方法。
     * @param userId BaseContext提供的可信用户ID
     * @param page MyBatis-Plus分页对象，插件计算总数并补充LIMIT
     * @param keyword 已转义LIKE通配符的标题关键词
     * @param categoryId 可选分类ID
     * @param wallpaperId 可选商品ID，用于确认当前账号对指定商品的权益
     * @return 按权益授予时间、权益ID倒序的已购分页及总数
     */
    IPage<WallpaperRow> selectOwnedWallpapers(Page<WallpaperRow> page, @Param("userId") Long userId,
            @Param("keyword") String keyword, @Param("categoryId") Long categoryId,
            @Param("wallpaperId") Long wallpaperId);

    /**
     * 查询本账号已购商品的READY封面，上下架状态不影响有效购买权益。
     * 调用位置：CoverService.readOwned；公开封面仍使用selectPublicCover。
     * @param userId BaseContext提供的可信用户ID
     * @param id 要读取封面的商品ID
     * @return 本账号有权益且封面就绪时返回位置，否则为null
     */
    CoverRow selectOwnedCover(@Param("userId") Long userId, @Param("id") long id);

    /**
     * 分页读取已上架且文件就绪的商品与当前媒体元数据。
     * 调用/引用位置：CatalogMySqlIT.mysqlPaginationJoinsCurrentFileAndKeepsDisabledCategoryProducts、WallpaperServiceImpl.page。
     *
     * @param page 分页插件对象，控制查询范围并接收总数
     * @param keyword 按标题匹配的搜索关键词
     * @param categoryId 分类数据库ID或外部分类筛选ID
     * @return 分页结果，包含当前页记录和插件计算的总数
     */
    IPage<WallpaperRow> selectPublishedPage(Page<WallpaperRow> page,
            @Param("keyword") String keyword, @Param("categoryId") Long categoryId);
    /**
     * 按编号读取公开可见商品详情，草稿或下架商品视为不存在。
     * 调用/引用位置：AdminWallpaperMySqlIT.partialUpdatePreservesCoverAndPublishingControlsReadVisibility、CatalogMySqlIT.inlineFileMetadataAndPublicationConstraintsAreEnforced、CatalogMySqlIT.mysqlPaginationJoinsCurrentFileAndKeepsDisabledCategoryProducts、WallpaperServiceImpl.detail。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    WallpaperRow selectPublishedById(@Param("id") long id);
    /**
     * 查询已上架商品的当前封面位置，禁止借此读取草稿或原文件。
     * 调用/引用位置：AdminWallpaperMySqlIT.partialUpdatePreservesCoverAndPublishingControlsReadVisibility、CoverService.read。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    CoverRow selectPublicCover(@Param("id") long id);
    /**
     * 查询任意状态商品的当前封面位置，由上层管理员权限保护。
     * 调用/引用位置：AdminWallpaperMySqlIT.partialUpdatePreservesCoverAndPublishingControlsReadVisibility、CoverService.readAdmin。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    CoverRow selectAdminCover(@Param("id") long id);
}
