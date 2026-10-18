package com.zlo.wallpaper.auth.controller;

import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RefreshTokenDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.service.AuthService;
import com.zlo.wallpaper.auth.vo.RegisterVO;
import com.zlo.wallpaper.auth.vo.SessionVO;
import com.zlo.wallpaper.common.ApiResponse;
import com.zlo.wallpaper.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 提供注册、登录、刷新和登出的HTTP入口，把请求交给AuthService并返回统一响应。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    @Autowired
    private AuthService authService;

    /**
     * 接收注册JSON并调用注册业务，以201返回用户名和请求标识。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RegisterVO> register(@Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
        return ApiResponse.success(authService.register(dto), RequestIdFilter.current(request));
    }

    /**
     * 接收登录JSON并返回Service签发的两种令牌及用户资料。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @PostMapping("/login")
    public ApiResponse<SessionVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        return ApiResponse.success(authService.login(dto), RequestIdFilter.current(request));
    }

    /**
     * 接收刷新令牌JSON并返回轮换后的会话响应。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @PostMapping("/refresh")
    public ApiResponse<SessionVO> refresh(@Valid @RequestBody RefreshTokenDTO dto, HttpServletRequest request) {
        return ApiResponse.success(authService.refresh(dto.getRefreshToken()), RequestIdFilter.current(request));
    }

    /**
     * 接收刷新令牌并调用登出业务，成功返回空对象而不暴露会话记录。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(@Valid @RequestBody RefreshTokenDTO dto, HttpServletRequest request) {
        authService.logout(dto.getRefreshToken());
        return ApiResponse.success(Map.of(), RequestIdFilter.current(request));
    }
}
