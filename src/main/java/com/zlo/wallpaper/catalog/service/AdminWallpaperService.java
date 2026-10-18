package com.zlo.wallpaper.catalog.service;

import com.zlo.wallpaper.catalog.dto.AdminWallpaperQueryDTO;
import com.zlo.wallpaper.catalog.dto.WallpaperMutationDTO;
import com.zlo.wallpaper.catalog.vo.AdminWallpaperVO;
import com.zlo.wallpaper.common.vo.PageVO;

/**
 * 声明管理商品分页、创建、修改与上下架业务能力。
 * 创建方式：接口本身不实例化，由对应@Service实现提供注入对象。
 * 注入位置：AdminWallpaperController.service、AdminWallpaperIntegrationTest.adminService。
 */
public interface AdminWallpaperService {
    /**
     * 查询管理员商品分页，包含草稿、已上架和下架商品。
     * 调用/引用位置：AdminWallpaperController.getWallpapers。
     *
     * @param query 分页及筛选请求参数，包含页码、条数或允许的筛选项
     * @return 包含全部商品状态的管理分页items、page、pageSize、total
     */
    PageVO<AdminWallpaperVO> getWallpapers(AdminWallpaperQueryDTO query);
    /**
     * 处理资料及两份文件，校验后创建DRAFT商品并返回当前商品资料。
     * 调用/引用位置：AdminWallpaperController.createWallpaper。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 管理商品资料、当前媒体元数据、文件显示名及受保护封面URL
     */
    AdminWallpaperVO createWallpaper(WallpaperMutationDTO dto);
    /**
     * 更新商品资料或替换当前文件，保留省略字段与原发布状态。
     * 调用/引用位置：AdminWallpaperController.updateWallpaper。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 管理商品资料、当前媒体元数据、文件显示名及受保护封面URL
     */
    AdminWallpaperVO updateWallpaper(String id, WallpaperMutationDTO dto);
    /**
     * 校验目标状态及商品文件就绪信息，再执行上架或下架。
     * 调用/引用位置：AdminWallpaperController.setPublication。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     * @return 管理商品资料、当前媒体元数据、文件显示名及受保护封面URL
     */
    AdminWallpaperVO setPublication(String id, String status);
}
