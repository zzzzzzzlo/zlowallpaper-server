package com.zlo.wallpaper.catalog.model;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 承接媒体检查工具确认的MIME、扩展名、宽高和帧率。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：MediaInspector.inspect。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class MediaMetadata {
    private String mimeType;
    private Integer width;
    private Integer height;
    private BigDecimal fps;
}
