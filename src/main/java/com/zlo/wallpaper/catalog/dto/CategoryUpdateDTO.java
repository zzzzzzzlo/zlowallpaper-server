package com.zlo.wallpaper.catalog.dto;

import jakarta.validation.constraints.AssertTrue;

/**
 * 接收分类修改资料，要求至少提供一个允许修改的字段。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public class CategoryUpdateDTO extends CategoryMutationDTO {
    /**
     * 判断分类修改请求是否至少包含一个可修改字段。
     * 框架调用位置：Bean Validation在校验DTO时调用，方法不作为请求业务字段。
     * 输入参数：无。
     *
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    @AssertTrue(message = "修改分类至少提供name、sortOrder、enabled中的一个字段")
    public boolean isChangeProvided() {
        return getName() != null || getSortOrder() != null || getEnabled() != null;
    }
}
