package com.zlo.wallpaper.catalog.dto;

import jakarta.validation.constraints.AssertTrue;

/**
 * 接收新增分类资料，额外要求提供名称。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public class CategoryCreateDTO extends CategoryMutationDTO {
    /**
     * 检查新增分类时是否实际提供名称。
     * 框架调用位置：Bean Validation在校验DTO时调用，方法不作为请求业务字段。
     * 输入参数：无。
     *
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    @AssertTrue(message = "新建分类必须提供name")
    public boolean isNameProvided() { return getName() != null; }
}
