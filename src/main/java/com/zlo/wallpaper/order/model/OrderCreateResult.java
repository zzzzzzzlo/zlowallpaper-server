package com.zlo.wallpaper.order.model;

import com.zlo.wallpaper.order.vo.OrderVO;
import lombok.Data;

/**
 * 供Service告知Controller返回哪笔订单及是否新建，created不作为接口业务字段返回。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：OrderServiceImpl.result。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class OrderCreateResult {
    private OrderVO order;
    private boolean created;
}
