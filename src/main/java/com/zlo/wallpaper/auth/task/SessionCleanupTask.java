package com.zlo.wallpaper.auth.task;

import com.zlo.wallpaper.auth.mapper.SessionMapper;
import com.zlo.wallpaper.config.AuthProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 按计划删除已过期且超过保留期的整组会话，保留必要的轮换历史。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AuthIntegrationTest.cleanup。
 */
@Component
public class SessionCleanupTask {
    @Autowired private SessionMapper sessionMapper;
    @Autowired private AuthProperties properties;
    @Autowired private Clock clock;

    /**
     * 计算保留截止时间并删除超期会话历史。
     * 调用/引用位置：AuthIntegrationTest.cleanupRetainsRotationHistoryUntilFamilyExpiryPlusRetention。
     * 框架调用位置：Spring定时任务调度器按配置执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Scheduled(cron = "${wallpaper.auth.cleanup-cron:0 0 3 * * *}", zone = "UTC")
    public void cleanExpiredSessions() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minusDays(properties.getRetentionDays());
        sessionMapper.deleteExpired(cutoff);
    }
}
