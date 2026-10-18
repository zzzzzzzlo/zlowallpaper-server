package com.zlo.wallpaper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot后端启动入口，建立应用容器并扫描已实现业务组件。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 */
@SpringBootApplication
public class ZloWallpaperApplication {

    /**
     * 启动Spring Boot应用，创建容器并加载Web接口与配置。
     * 框架调用位置：JVM从启动脚本、IDE或java -jar进入。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param args Java进程启动参数，交给Spring Boot读取
     */
    public static void main(String[] args) {
        SpringApplication.run(ZloWallpaperApplication.class, args);
    }
}
