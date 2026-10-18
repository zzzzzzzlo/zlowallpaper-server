package com.zlo.wallpaper.catalog.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 接收multipart商品资料与两份可选文件；修改时null表示保留原字段。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class WallpaperMutationDTO {
    private String title;
    private String description;
    private String categoryId;
    private Integer priceCents;
    private String creator;
    private MultipartFile originalFile;
    private MultipartFile coverFile;
}
