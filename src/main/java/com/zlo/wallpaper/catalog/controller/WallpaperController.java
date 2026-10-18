package com.zlo.wallpaper.catalog.controller;

import com.zlo.wallpaper.catalog.dto.WallpaperQueryDTO;
import com.zlo.wallpaper.catalog.dto.OwnedWallpaperQueryDTO;
import com.zlo.wallpaper.catalog.service.WallpaperService;
import com.zlo.wallpaper.catalog.vo.WallpaperVO;
import com.zlo.wallpaper.common.ApiResponse;
import com.zlo.wallpaper.common.RequestIdFilter;
import com.zlo.wallpaper.common.vo.PageVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 提供公开壁纸分页、详情及当前用户已购查询，公开与已购使用不同的Mapper条件。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@RestController
public class WallpaperController {
    private final WallpaperService wallpaperService;
    /**
     * 构造WallpaperController并保存其依赖或初始属性。
     * 框架调用位置：Spring创建当前组件时调用。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     * @param wallpaperService 由Spring注入的业务服务依赖
     */
    public WallpaperController(WallpaperService wallpaperService) { this.wallpaperService = wallpaperService; }

    /**
     * 解析分页与筛选参数，查询已上架商品并返回统一分页视图。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param query 分页及筛选请求参数，包含页码、条数或允许的筛选项
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @GetMapping("/wallpapers")
    public ApiResponse<PageVO<WallpaperVO>> page(@Valid @ModelAttribute WallpaperQueryDTO query,
                                               HttpServletRequest request) {
        return ApiResponse.success(wallpaperService.page(query), RequestIdFilter.current(request));
    }

    /**
     * 按外部字符串ID查询可公开展示的商品详情，不泄露原文件存储地址。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @GetMapping("/wallpapers/{id}")
    public ApiResponse<WallpaperVO> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(wallpaperService.detail(id), RequestIdFilter.current(request));
    }

    /**
     * 查询登录用户拥有的商品，允许已购停售商品；不接收前端指定的用户ID。
     * 调用位置：Spring MVC处理GET /me/wallpapers，AuthFilter先建立可信身份。
     * @param query 页码、每页条数及可选的标题、分类、商品筛选
     * @param request 当前请求，用于读取requestId
     * @return 统一包装的分页对象，无权益或页码越界时items为空数组
     */
    @GetMapping("/me/wallpapers")
    public ApiResponse<PageVO<WallpaperVO>> getMyWallpapers(@Valid @ModelAttribute OwnedWallpaperQueryDTO query,
                                                           HttpServletRequest request) {
        return ApiResponse.success(wallpaperService.getMyWallpapers(query), RequestIdFilter.current(request));
    }
}
