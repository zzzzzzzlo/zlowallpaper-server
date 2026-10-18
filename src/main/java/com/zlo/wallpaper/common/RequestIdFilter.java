package com.zlo.wallpaper.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求建立或复用合法请求标识，并放入响应头和请求属性。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    /**
     * 执行当前过滤器的一次请求处理，设置上下文或请求标识并继续过滤器链。
     * 框架调用位置：Servlet容器通过OncePerRequestFilter回调。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @param response 当前HTTP响应，用于写入状态、响应头和JSON
     * @param filterChain Servlet过滤器链，通过当前处理后继续后续Filter和Controller
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        filterChain.doFilter(request, response);
    }

    /**
     * 从请求属性读取RequestIdFilter已经建立的本次请求标识。
     * 调用/引用位置：AdminCategoryController.createCategory、AdminCategoryController.deleteCategory、AdminCategoryController.getAdminCategories、AdminCategoryController.updateCategory、AdminWallpaperController.createWallpaper、AdminWallpaperController.getWallpapers、AdminWallpaperController.setPublication、AdminWallpaperController.updateWallpaper、AuthController.login、AuthController.logout、AuthController.refresh、AuthController.register、AuthFilter.doFilterInternal、AuthFilter.writeError、CategoryController.getCategories、GlobalExceptionHandler.error、GlobalExceptionHandler.unavailable、GlobalExceptionHandler.unexpected、HealthController.health、OrderController.createOrder、UserController.getCurrentUser、WallpaperController.detail、WallpaperController.page。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    public static String current(HttpServletRequest request) {
        return (String) request.getAttribute(ATTRIBUTE);
    }
}
