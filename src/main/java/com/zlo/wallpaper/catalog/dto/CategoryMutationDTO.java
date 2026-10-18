package com.zlo.wallpaper.catalog.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.zlo.wallpaper.catalog.util.CategoryNames;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;

/**
 * 定义分类修改的共同字段、名称规则和未知字段拒绝逻辑。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Getter
@Setter
public class CategoryMutationDTO {
    @JsonSetter(nulls = Nulls.FAIL)
    @JsonDeserialize(using = CategoryFieldDeserializers.StringValue.class)
    private String name;
    @JsonSetter(nulls = Nulls.FAIL)
    @JsonDeserialize(using = CategoryFieldDeserializers.IntegerValue.class)
    private Integer sortOrder;
    @JsonSetter(nulls = Nulls.FAIL)
    @JsonDeserialize(using = CategoryFieldDeserializers.BooleanValue.class)
    private Boolean enabled;

    /**
     * 按统一空白及Unicode长度规则检查分类名称。
     * 框架调用位置：Bean Validation在校验DTO时调用，方法不作为请求业务字段。
     * 输入参数：无。
     *
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    @AssertTrue(message = "分类名称去除首尾空白后须为1–64个Unicode字符")
    public boolean isNameValid() {
        if (name == null) { return true; } // 是否必须提供，由具体DTO检查。
        String normalized = CategoryNames.normalize(name);
        return !normalized.isEmpty() && normalized.codePointCount(0, normalized.length()) <= 64;
    }

    /**
     * 拒绝当前DTO未定义的JSON字段，防止客户端误写内部业务字段。
     * 框架调用位置：Jackson遇到DTO未定义字段时回调。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param field JSON中未定义的字段名，用于拒绝契约外输入
     * @param value 当前待读取、校验或转换的输入值
     */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        // 局部严格检查，不改变旧接口对额外JSON字段的处理方式。
        throw new IllegalArgumentException("分类请求包含未定义字段");
    }
}
