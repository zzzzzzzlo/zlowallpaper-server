package com.zlo.wallpaper.catalog.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 接收公开商品的分页、标题关键词和分类筛选参数，提供默认值与校验。
 * 创建方式：Controller接收请求时由Spring MVC/Jackson绑定，测试中也可直接new；不作为Service注入。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
public class WallpaperQueryDTO {
    @NotNull(message = "page 不能为空")
    @Min(value = 1, message = "page 必须大于等于 1")
    private Integer page = 1;

    @NotNull(message = "pageSize 不能为空")
    @Min(value = 1, message = "pageSize 必须大于等于 1")
    @Max(value = 50, message = "pageSize 不能超过 50")
    private Integer pageSize = 12;

    private String keyword = "";
    private String categoryId = "";

    // OpenAPI 的字符数按 Unicode 字符计算，不把一个 emoji 当作两个字符。
    /**
     * 检查标题搜索关键词不超过允许的Unicode字符长度。
     * 框架调用位置：Bean Validation在校验DTO时调用，方法不作为请求业务字段。
     * 输入参数：无。
     *
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    @AssertTrue(message = "keyword 不能超过 100 个字符")
    public boolean isKeywordLengthValid() {
        return keyword.codePointCount(0, keyword.length()) <= 100;
    }

    /**
     * 读取分页页码，供分页Service构建查询。
     * 调用/引用位置：WallpaperServiceImpl.page。
     * 输入参数：无。
     *
     * @return 上述处理得到的Integer结果；业务校验失败时通过异常返回错误
     */
    public Integer getPage() { return page; }
    /**
     * 绑定请求页码，null交给@NotNull校验拒绝，不自行改成默认值。
     * 调用位置：当前源码未发现显式业务调用；保留当前接口或测试辅助用途。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param page 分页插件对象，控制查询范围并接收总数
     */
    public void setPage(Integer page) { this.page = page;}
    /**
     * 读取每页条数，供分页Service构建查询。
     * 调用/引用位置：WallpaperServiceImpl.page。
     * 输入参数：无。
     *
     * @return 上述处理得到的Integer结果；业务校验失败时通过异常返回错误
     */
    public Integer getPageSize() { return pageSize; }
    /**
     * 绑定每页条数，null交给@NotNull校验拒绝，不自行改成默认值。
     * 调用位置：当前源码未发现显式业务调用；保留当前接口或测试辅助用途。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param pageSize 请求的每页条数，校验范围为1至50
     */
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize;}
    /**
     * 读取标题搜索字符串。
     * 调用/引用位置：WallpaperServiceImpl.page。
     * 输入参数：无。
     *
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    public String getKeyword() { return keyword; }
    /**
     * 绑定标题搜索字符串，将null作为空关键词处理。
     * 调用位置：当前源码未发现显式业务调用；保留当前接口或测试辅助用途。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param keyword 按标题匹配的搜索关键词
     */
    public void setKeyword(String keyword) { this.keyword = keyword == null ? "" : keyword; }
    /**
     * 读取分类筛选的外部字符串ID。
     * 调用/引用位置：WallpaperServiceImpl.page。
     * 输入参数：无。
     *
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    public String getCategoryId() { return categoryId; }
    /**
     * 绑定分类筛选ID，将null作为不限分类处理。
     * 调用位置：当前源码未发现显式业务调用；保留当前接口或测试辅助用途。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param categoryId 分类数据库ID或外部分类筛选ID
     */
    public void setCategoryId(String categoryId) { this.categoryId = categoryId == null ? "" : categoryId; }
}
