package com.zlo.wallpaper.catalog.controller;

import com.zlo.wallpaper.catalog.service.CoverService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供已上架商品的公开封面字节，限制资源路径和可见性。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@RestController
public class CoverController {
    private final CoverService coverService;
    /**
     * 构造CoverController并保存其依赖或初始属性。
     * 框架调用位置：Spring创建当前组件时调用。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     * @param coverService 由Spring注入的业务服务依赖
     */
    public CoverController(CoverService coverService) { this.coverService = coverService; }

    // id 现在是商品 ID，封面位置随商品记录保存。
    /**
     * 根据商品可见性读取公开封面，并返回可用于HTTP输出的资源信息。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @GetMapping("/catalog/covers/{id}")
    public ResponseEntity<Resource> read(@PathVariable String id) {
        var cover = coverService.read(id);
        return ResponseEntity.ok().contentType(cover.mediaType()).contentLength(cover.size())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noCache()).body(cover.resource());
    }

    /**
     * 输出本账号已购商品的封面；普通用户可读自己的停售商品，禁止共享缓存。
     * 调用位置：Spring MVC处理GET /me/wallpapers/{id}/cover；AuthFilter负责登录。
     * @param id 已购商品ID
     * @return 直接图片字节及类型；无权益或无封面返回404
     */
    @GetMapping("/me/wallpapers/{id}/cover")
    public ResponseEntity<Resource> readOwned(@PathVariable String id) {
        CoverService.Cover cover = coverService.readOwned(id);
        return ResponseEntity.ok().contentType(cover.mediaType()).contentLength(cover.size())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(cover.resource());
    }
}
