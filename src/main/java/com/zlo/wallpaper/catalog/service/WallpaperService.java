package com.zlo.wallpaper.catalog.service;

import com.zlo.wallpaper.catalog.dto.WallpaperQueryDTO;
import com.zlo.wallpaper.catalog.dto.OwnedWallpaperQueryDTO;
import com.zlo.wallpaper.catalog.vo.WallpaperVO;
import com.zlo.wallpaper.common.vo.PageVO;

/**
 * 声明公开商品分页、详情及本账号已购商品查询能力。
 * 创建方式：接口本身不实例化，由对应@Service实现提供注入对象。
 * 注入位置：WallpaperController的构造器。
 */
public interface WallpaperService {
    /**
     * 查询本账号当前权益对应的商品，不以必须上架作为条件。
     * 调用位置：WallpaperController.getMyWallpapers；由WallpaperServiceImpl实现。
     * @param query 已校验的分页和筛选参数，账号身份另从BaseContext读取
     * @return 本账号已购商品分页，包含当前页、每页条数和筛选后的总数
     */
    PageVO<WallpaperVO> getMyWallpapers(OwnedWallpaperQueryDTO query);

    /**
     * 解析分页与筛选参数，查询已上架商品并返回统一分页视图。
     * 调用/引用位置：WallpaperController.page。
     *
     * @param query 分页及筛选请求参数，包含页码、条数或允许的筛选项
     * @return items、page、pageSize、total组成的公开商品分页结果
     */
    PageVO<WallpaperVO> page(WallpaperQueryDTO query);
    /**
     * 按外部字符串ID查询可公开展示的商品详情，不泄露原文件存储地址。
     * 调用/引用位置：WallpaperController.detail。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 公开可见商品资料和当前文件元数据，不包含原文件对象键或下载URL
     */
    WallpaperVO detail(String id);
}
