package com.zlo.wallpaper.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.auth.entity.User;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.common.ApiResponse;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.common.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

/**
 * 统一保护私有路径和管理路径，认证成功后设置并最终清理当前线程用户ID。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AuthFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/auth/register", "/auth/login", "/auth/refresh", "/auth/logout");
    @Autowired private AuthService authService;
    @Autowired private ObjectMapper objectMapper;

    /**
     * 执行当前过滤器的一次请求处理，设置上下文或请求标识并继续过滤器链。
     * 框架调用位置：Servlet容器通过OncePerRequestFilter回调。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @param response 当前HTTP响应，用于写入状态、响应头和JSON
     * @param chain Servlet过滤器链，校验通过后继续后续处理
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        BaseContext.removeCurrentId();
        try {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!isPublic(request.getMethod(), path)) {
                try {
                    String header = request.getHeader("Authorization");
                    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() > 4096) {
                        throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请先登录");
                    }
                    User user = authService.authenticate(header.substring(7));
                    if ((path.equals("/admin") || path.startsWith("/admin/")) && !"ADMIN".equals(user.getRole())) {
                        throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "需要管理员权限");
                    }
                    BaseContext.setCurrentId(user.getId());
                } catch (BusinessException exception) {
                    writeError(response, request, exception.getStatus().value(), exception.getCode(), exception.getMessage());
                    return;
                } catch (DataAccessException exception) {
                    logger.error("认证数据库暂不可用，requestId=" + RequestIdFilter.current(request));
                    writeError(response, request, 503, "SERVICE_UNAVAILABLE", "数据库暂不可用");
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    /**
     * 判断请求方法和路径是否属于可匿名访问的接口。
     * 调用/引用位置：AuthFilter.doFilterInternal。
     *
     * @param method HTTP方法字符串，用于决定公开性或测试请求类型
     * @param path 请求路径或服务器控制的文件路径
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean isPublic(String method, String path) {
        if ("OPTIONS".equals(method)) { return true; }
        if (PUBLIC_AUTH_PATHS.contains(path)) { return true; }
        if (!"GET".equals(method) && !"HEAD".equals(method)) { return false; }
        return path.equals("/health") || path.equals("/categories") || path.equals("/wallpapers")
                || path.matches("/wallpapers/[^/]+") || path.matches("/catalog/covers/[^/]+");
    }

    /**
     * 将认证失败信息写成统一JSON，并保留实际HTTP错误状态。
     * 调用/引用位置：AuthFilter.doFilterInternal。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param response 当前HTTP响应，用于写入状态、响应头和JSON
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     * @param code 统一响应使用的业务错误码
     * @param message 允许向前端展示的业务说明
     */
    private void writeError(HttpServletResponse response, HttpServletRequest request,
                     int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(code, message, RequestIdFilter.current(request)));
    }
}
