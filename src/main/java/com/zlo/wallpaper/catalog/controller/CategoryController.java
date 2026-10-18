package com.zlo.wallpaper.catalog.controller;

import com.zlo.wallpaper.catalog.service.CategoryService;
import com.zlo.wallpaper.catalog.vo.CategoryVO;
import com.zlo.wallpaper.common.ApiResponse;
import com.zlo.wallpaper.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 提供公开的启用分类列表，交给CategoryService读取数据。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@RestController
public class CategoryController {
    private final CategoryService categoryService;
    /**
     * 构造CategoryController并保存其依赖或初始属性。
     * 框架调用位置：Spring创建当前组件时调用。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     * @param categoryService 由Spring注入的业务服务依赖
     */
    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 调用分类业务并以统一响应返回公开启用分类列表。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @GetMapping("/categories")
    public ApiResponse<List<CategoryVO>> getCategories(HttpServletRequest request) {
        return ApiResponse.success(categoryService.listEnabled(), RequestIdFilter.current(request));
    }
}
