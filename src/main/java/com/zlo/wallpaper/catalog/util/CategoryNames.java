package com.zlo.wallpaper.catalog.util;

/**
 * 按统一Unicode空白规则整理分类名称，保证新增、查询和修改使用相同名称。
 * 创建方式：静态工具类不实例化；私有构造器防止业务代码new。
 */
public final class CategoryNames {
    /**
     * 限制外部实例化，当前类只通过静态方法使用。
     * 调用位置：没有业务调用，此构造器用于禁止new。
     * 输入参数：无。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     */
    private CategoryNames() { }

    /**
     * 移除分类名称两端的统一Unicode空白，保留名称中间字符。
     * 调用/引用位置：CategoryMutationDTO.isNameValid、CategoryServiceImpl.createCategory、CategoryServiceImpl.updateCategory。
     *
     * @param name 分类名称或未定义JSON字段名
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    public static String normalize(String name) {
        if (name == null) { return null; }
        int start = 0;
        int end = name.length();
        while (start < end && isWhitespace(name.codePointAt(start))) {
            start += Character.charCount(name.codePointAt(start));
        }
        while (start < end && isWhitespace(name.codePointBefore(end))) {
            end -= Character.charCount(name.codePointBefore(end));
        }
        return name.substring(start, end);
    }

    /**
     * 判断码点是否属于分类名称归一化规则中的空白字符。
     * 调用/引用位置：CategoryNames.normalize。
     *
     * @param codePoint 待判断的Unicode码点
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private static boolean isWhitespace(int codePoint) {
        // isSpaceChar 补充不换行空格等字符；FEFF 是浏览器 trim 会去除的 BOM。
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0xFEFF;
    }
}
