package com.zlo.wallpaper.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 检查数据库和Redis是否可用，返回健康状态及请求标识。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造HealthController并保存其依赖或初始属性。
     * 框架调用位置：Spring创建当前组件时调用。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     * @param jdbcTemplate Spring注入的数据库访问工具，用于健康检查而不返回连接详情
     */
    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 检查数据库和Redis连通性，依赖不可用时保留真实503状态。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @GetMapping("/health")
    public ResponseEntity<?> health(HttpServletRequest request) {
        String requestId = RequestIdFilter.current(request);
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (!Integer.valueOf(1).equals(result)) {
                return unavailable(requestId);
            }
            return ResponseEntity.ok(ApiResponse.success(Map.of(), requestId));
        } catch (DataAccessException exception) {
            // 不把连接串、账号密码或底层异常内容回传到接口。
            log.warn("MySQL readiness check failed; requestId={}", requestId);
            return unavailable(requestId);
        }
    }

    /**
     * 构造数据库或Redis不可用的503健康检查结果，不公开连接详情。
     * 调用/引用位置：HealthController.health。
     *
     * @param requestId 当前HTTP请求标识，不包含认证令牌
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    private ResponseEntity<ApiResponse<Void>> unavailable(String requestId) {
        return ResponseEntity.status(503)
                .body(ApiResponse.failure("SERVICE_UNAVAILABLE", "数据库暂不可用", requestId));
    }
}
