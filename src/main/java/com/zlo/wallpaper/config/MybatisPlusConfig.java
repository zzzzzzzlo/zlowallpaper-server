package com.zlo.wallpaper.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册统一MyBatis-Plus分页拦截器，本项目不另引入PageHelper。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@Configuration
public class MybatisPlusConfig {
    /**
     * 创建并注册MyBatis-Plus拦截器，加入MySQL分页插件。
     * 框架调用位置：Spring配置处理器在创建容器Bean时调用。
     * 输入参数：无。
     *
     * @return 包含统一MySQL分页插件的MyBatis-Plus拦截器Bean
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        var pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(50L);
        // 超出末页返回空列表，不能悄悄跳回第一页。
        pagination.setOverflow(false);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
