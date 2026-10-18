package com.zlo.wallpaper.auth.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/**
 * 为账号、密码、令牌和订单商品ID提供严格的JSON字符串读取规则。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public class StrictStringDeserializer extends JsonDeserializer<String> {
    /**
     * 按当前反序列化器的类型规则读取JSON字段，类型不符时拒绝请求。
     * 框架调用位置：Jackson依据@JsonDeserialize回调当前反序列化器。
     *
     * @param parser Jackson提供的JSON解析或类型校验上下文
     * @param context Jackson提供的JSON解析或类型校验上下文
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(String.class, "认证字段必须是JSON字符串");
        }
        return parser.getText();
    }
}
