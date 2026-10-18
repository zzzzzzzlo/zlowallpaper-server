package com.zlo.wallpaper.catalog.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.zlo.wallpaper.auth.dto.StrictStringDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 接收商品上架或下架状态，并拒绝未知JSON字段。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class WallpaperPublicationDTO {
    @NotBlank
    @Pattern(regexp = "PUBLISHED|WITHDRAWN", message = "发布状态只能为PUBLISHED或WITHDRAWN")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String status;

    /**
     * 拒绝商品上下架请求中的未知JSON字段。
     * 框架调用位置：Jackson遇到DTO未定义字段时回调。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param name 分类名称或未定义JSON字段名
     * @param value 当前待读取、校验或转换的输入值
     */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("上下架请求包含未定义字段");
    }
}
