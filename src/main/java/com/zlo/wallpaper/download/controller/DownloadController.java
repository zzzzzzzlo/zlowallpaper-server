package com.zlo.wallpaper.download.controller;

import com.zlo.wallpaper.common.ApiResponse;
import com.zlo.wallpaper.common.RequestIdFilter;
import com.zlo.wallpaper.download.service.DownloadService;
import com.zlo.wallpaper.download.vo.DownloadGrantVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供原文件下载授权HTTP入口，不负责传输文件字节，也不接收客户端指定OSS地址。
 * 创建方式：Spring组件扫描创建，注入DownloadService接口的实现。
 */
@RestController
public class DownloadController {
    @Autowired private DownloadService downloadService;

    /**
     * 签发本账号已购商品的短时下载授权，禁止缓存包含票据的JSON响应。
     * 调用位置：Spring MVC处理POST /wallpapers/{id}/download-grants，AuthFilter先验证登录。
     * @param id 要下载的商品字符串ID
     * @param request 当前HTTP请求，用于取得requestId
     * @return HTTP 200及统一授权响应，错误由现有GlobalExceptionHandler处理
     */
    @PostMapping("/wallpapers/{id}/download-grants")
    public ResponseEntity<ApiResponse<DownloadGrantVO>> createDownloadGrant(@PathVariable String id,
                                                                          HttpServletRequest request) {
        DownloadGrantVO grant = downloadService.createDownloadGrant(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(grant, RequestIdFilter.current(request)));
    }
}
