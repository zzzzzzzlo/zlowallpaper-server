package com.zlo.wallpaper.catalog.controller;

import com.zlo.wallpaper.catalog.dto.CategoryCreateDTO;
import com.zlo.wallpaper.catalog.dto.CategoryUpdateDTO;
import com.zlo.wallpaper.catalog.service.CategoryService;
import com.zlo.wallpaper.catalog.vo.AdminCategoryVO;
import com.zlo.wallpaper.common.ApiResponse;
import com.zlo.wallpaper.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * 提供管理员分类列表、新增、修改和删除HTTP入口；权限由AuthFilter校验。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@RestController
@RequestMapping("/admin/categories")
public class AdminCategoryController {
    @Autowired private CategoryService categoryService;

    /**
     * 读取完整管理分类列表，包括停用分类和所有商品状态的引用数。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @GetMapping
    public ApiResponse<List<AdminCategoryVO>> getAdminCategories(HttpServletRequest request) {
        return ApiResponse.success(categoryService.getAdminCategories(), RequestIdFilter.current(request));
    }

    /**
     * 校验名称及默认值，创建分类并返回完整管理视图。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminCategoryVO> createCategory(@Valid @RequestBody CategoryCreateDTO dto,
                                                       HttpServletRequest request) {
        return ApiResponse.success(categoryService.createCategory(dto), RequestIdFilter.current(request));
    }

    /**
     * 仅更新实际提交的分类字段，保留省略值并校验名称冲突。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @PatchMapping("/{id}")
    public ApiResponse<AdminCategoryVO> updateCategory(@PathVariable String id,
            @Valid @RequestBody CategoryUpdateDTO dto, HttpServletRequest request) {
        return ApiResponse.success(categoryService.updateCategory(id, dto), RequestIdFilter.current(request));
    }

    /**
     * 删除未被任何商品引用的分类，已引用分类返回冲突。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> deleteCategory(@PathVariable String id, HttpServletRequest request) {
        categoryService.deleteCategory(id);
        return ApiResponse.success(Map.of(), RequestIdFilter.current(request));
    }
}
