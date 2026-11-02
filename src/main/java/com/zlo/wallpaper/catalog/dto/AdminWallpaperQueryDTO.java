package com.zlo.wallpaper.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 接收管理员商品分页及状态、分类筛选参数；空筛选表示全部。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class AdminWallpaperQueryDTO {
    @NotNull @Min(1) private Integer page = 1;
    @NotNull @Min(1) @Max(50) private Integer pageSize = 12;
    @Pattern(regexp = "|PUBLISHED|DRAFT|WITHDRAWN", message = "壁纸状态须为PUBLISHED、DRAFT或WITHDRAWN")
    private String publicationStatus = "";
    @Pattern(regexp = "[A-Za-z0-9._-]{0,128}", message = "categoryId格式不符合接口约定")
    private String categoryId = "";
}
