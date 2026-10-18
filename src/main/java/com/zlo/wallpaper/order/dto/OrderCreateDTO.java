package com.zlo.wallpaper.order.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.zlo.wallpaper.auth.dto.StrictStringDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 接收唯一允许的wallpaperId字符串，拒绝客户端提交价格、用户ID或付款状态。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class OrderCreateDTO {
    @NotBlank(message = "请选择要购买的壁纸")
    @Size(max = 128, message = "壁纸编号过长")
    @Pattern(regexp = "[A-Za-z0-9._-]+", message = "壁纸编号格式不正确")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String wallpaperId;

    /**
     * 拒绝当前DTO未定义的JSON字段，防止客户端误写内部业务字段。
     * 框架调用位置：Jackson遇到DTO未定义字段时回调。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param name 分类名称或未定义JSON字段名
     * @param value 当前待读取、校验或转换的输入值
     */
    @JsonAnySetter
    public void rejectUnknownField(String name, JsonNode value) {
        // 禁止把客户端价格、用户ID或付款状态当成下单依据。
        throw new IllegalArgumentException("创建订单包含未定义字段");
    }
}
