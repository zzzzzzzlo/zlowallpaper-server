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

/** 只验证工程与 MySQL 就绪，不实现任何商城业务。 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

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

    private ResponseEntity<ApiResponse<Void>> unavailable(String requestId) {
        return ResponseEntity.status(503)
                .body(ApiResponse.failure("SERVICE_UNAVAILABLE", "数据库暂不可用", requestId));
    }
}
