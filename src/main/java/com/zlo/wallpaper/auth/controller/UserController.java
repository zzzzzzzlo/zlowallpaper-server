package com.zlo.wallpaper.auth.controller;

import com.zlo.wallpaper.auth.service.UserService;
import com.zlo.wallpaper.auth.vo.UserVO;
import com.zlo.wallpaper.common.ApiResponse;
import com.zlo.wallpaper.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供当前登录用户的HTTP查询入口，避免返回密码哈希和内部角色信息。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@RestController
public class UserController {
    @Autowired
    private UserService userService;

    /**
     * 读取当前登录账号资料并转换为公开用户信息。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @GetMapping("/me")
    public ApiResponse<UserVO> getCurrentUser(HttpServletRequest request) {
        return ApiResponse.success(userService.getCurrentUser(), RequestIdFilter.current(request));
    }
}
