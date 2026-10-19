package com.zlo.wallpaper.catalog.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zlo.wallpaper.catalog.dto.WallpaperQueryDTO;
import com.zlo.wallpaper.catalog.dto.OwnedWallpaperQueryDTO;
import com.zlo.wallpaper.catalog.mapper.WallpaperMapper;
import com.zlo.wallpaper.catalog.model.WallpaperRow;
import com.zlo.wallpaper.catalog.service.CatalogIds;
import com.zlo.wallpaper.catalog.service.WallpaperService;
import com.zlo.wallpaper.catalog.vo.WallpaperVO;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.vo.PageVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * 集中实现公开商品查询和我的壁纸查询；已购查询通过权益条件保护未上架商品。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：WallpaperController的构造器（按WallpaperService接口）。
 */
@Service
public class WallpaperServiceImpl implements WallpaperService {
    private final WallpaperMapper wallpaperMapper;
    private final String publicBaseUrl;

    /**
     * 构造WallpaperServiceImpl并保存其依赖或初始属性。
     * 框架调用位置：Spring创建当前组件时调用。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     * @param wallpaperMapper 由Spring注入的数据库Mapper代理
     * @param publicBaseUrl 服务器配置的公开API根地址，用于生成受约束的封面URL
     */
    public WallpaperServiceImpl(WallpaperMapper wallpaperMapper,
                                @Value("${wallpaper.public-base-url}") String publicBaseUrl) {
        this.wallpaperMapper = wallpaperMapper;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * 获取本账号权益对应商品，返回当前文件信息，既有权益不因商品停售或分类停用而消失。
     * 调用位置：WallpaperController.getMyWallpapers。
     * @param query 已校验的分页、标题、分类和商品筛选参数
     * @return 本账号已购分页，账号身份从BaseContext取得，允许停售商品
     */
    @Override
    public PageVO<WallpaperVO> getMyWallpapers(OwnedWallpaperQueryDTO query) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请先登录");
        }
        // 1. 整理筛选参数；标题按普通文本匹配，不能把%或_当SQL通配符。
        String keyword = query.getKeyword().strip();
        keyword = keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        String category = query.getCategoryId().strip();
        Long categoryId = category.isEmpty() ? null : CatalogIds.findDatabaseId(category);
        // 无法映射为数据库ID的非空分类/商品筛选应匹配不到，不能变成“不筛选”。
        if (!category.isEmpty() && categoryId == null) { categoryId = -1L; }
        Long wallpaperId = null;
        if (query.getWallpaperId() != null && !query.getWallpaperId().isBlank()) {
            CatalogIds.validatePathId(query.getWallpaperId());
            wallpaperId = CatalogIds.findDatabaseId(query.getWallpaperId());
            if (wallpaperId == null) { wallpaperId = -1L; }
        }
        // 2. 分页查询；只有本入口使用权益SQL，插件执行COUNT并补LIMIT。
        Page<WallpaperRow> page = new Page<>(query.getPage(), query.getPageSize());
        page.setOptimizeJoinOfCountSql(false);
        IPage<WallpaperRow> records = wallpaperMapper.selectOwnedWallpapers(page, userId, keyword, categoryId, wallpaperId);
        // 3. 逐条转换当前页；已购封面允许本账号读取停售商品。
        List<WallpaperVO> result = new ArrayList<>(records.getRecords().size());
        for (WallpaperRow row : records.getRecords()) {
            String coverUrl = UriComponentsBuilder.fromUriString(publicBaseUrl)
                    .pathSegment("me", "wallpapers", row.id().toString(), "cover").build().toUriString();
            result.add(toVO(row, coverUrl));
        }
        return new PageVO<>(result, query.getPage(), query.getPageSize(), records.getTotal());
    }

    /**
     * 解析分页与筛选参数，查询已上架商品并返回统一分页视图。
     * 调用/引用位置：WallpaperController.page。
     *
     * @param query 分页及筛选请求参数，包含页码、条数或允许的筛选项
     * @return items、page、pageSize、total组成的公开商品分页结果
     */
    @Override
    public PageVO<WallpaperVO> page(WallpaperQueryDTO query) {
        // 1. 整理筛选参数；与我的已购保持相同的顺序和转换方式。
        String keyword = query.getKeyword().strip();
        // 把用户输入中的 LIKE 通配符作为普通标题字符处理。
        keyword = keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        String category = query.getCategoryId().strip();
        Long categoryId = category.isEmpty() ? null : CatalogIds.findDatabaseId(category);
        if (!category.isEmpty() && categoryId == null) { categoryId = -1L; }
        // 2. 分页查询；公开SQL仍只读取已上架且文件就绪的商品。
        Page<WallpaperRow> page = new Page<>(query.getPage(), query.getPageSize());
        page.setOptimizeJoinOfCountSql(false);
        IPage<WallpaperRow> records = wallpaperMapper.selectPublishedPage(page, keyword, categoryId);
        // 3. 逐条转换当前页；封面使用公开入口。
        List<WallpaperVO> result = new ArrayList<>(records.getRecords().size());
        for (WallpaperRow row : records.getRecords()) {
            String coverUrl = UriComponentsBuilder.fromUriString(publicBaseUrl)
                    .pathSegment("catalog", "covers", row.id().toString()).build().toUriString();
            result.add(toVO(row, coverUrl));
        }
        return new PageVO<>(result, query.getPage(), query.getPageSize(), records.getTotal());
    }

    /**
     * 按外部字符串ID查询可公开展示的商品详情，不泄露原文件存储地址。
     * 调用/引用位置：WallpaperController.detail。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 公开可见商品资料和当前文件元数据，不包含原文件对象键或下载URL
     */
    @Override
    public WallpaperVO detail(String id) {
        CatalogIds.validatePathId(id);
        Long databaseId = CatalogIds.findDatabaseId(id);
        WallpaperRow row = databaseId == null ? null : wallpaperMapper.selectPublishedById(databaseId);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在或未上架");
        }
        String coverUrl = UriComponentsBuilder.fromUriString(publicBaseUrl)
                .pathSegment("catalog", "covers", row.id().toString()).build().toUriString();
        return toVO(row, coverUrl);
    }

    /**
     * 复用商品字段转换，封面地址由调用方按公开或已购访问场景生成。
     * 调用位置：WallpaperServiceImpl.page、getMyWallpapers、detail；只有这一层字段转换。
     * @param row 查询返回的当前商品及原文件元数据
     * @param coverUrl 当前访问场景的公开或带身份封面入口
     * @return 沿用现有Product协议的WallpaperVO，不返回内部原文件位置
     */
    private WallpaperVO toVO(WallpaperRow row, String coverUrl) {
        return new WallpaperVO(row.id().toString(), row.title(), row.description(),
                row.categoryId().toString(), row.categoryName(), row.type(), row.priceCents(),
                row.currency(), coverUrl, row.width(), row.height(), row.fps(),
                row.sha256(), row.sizeBytes().toString(), row.creator());
    }
}
