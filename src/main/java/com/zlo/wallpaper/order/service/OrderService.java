package com.zlo.wallpaper.order.service;

import com.zlo.wallpaper.order.dto.OrderCreateDTO;
import com.zlo.wallpaper.order.model.OrderCreateResult;
import com.zlo.wallpaper.order.vo.OrderVO;
import java.util.List;

/**
 * 声明创建订单、查询我的订单及壁纸币付款能力，身份来自当前请求上下文。
 * 创建方式：接口本身不实例化，由对应@Service实现提供注入对象。
 * 注入位置：OrderController.orderService、OrderIntegrationTest.service、OrderMySqlIT.service。
 */
public interface OrderService {
    /**
     * 查询当前账号的所有状态订单，只返回下单快照；超过1000条时明确报错。
     * 调用位置：OrderController.getMyOrders；由OrderServiceImpl实现。
     * 输入参数：无，身份读取BaseContext，不接受前端指定用户。
     * @return 按创建时间及ID倒序的订单列表，没有订单时为空列表
     */
    List<OrderVO> getMyOrders();

    /**
     * 在事务内先查当前权益，再复用待付款订单；确需新单才读取价格创建，免费单同时授予权益。
     * 调用/引用位置：OrderController.createOrder、OrderIntegrationTest.concurrent、OrderMySqlIT.create。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 订单VO与created标志；新单为true，已有权益或待付款订单复用为false
     */
    OrderCreateResult createOrder(OrderCreateDTO dto);

    /**
     * 完成本账号订单的壁纸币付款，重复付款返回原结果，实际扣减站内壁纸币，不涉及外部支付渠道。
     * 调用位置：OrderController.payOrder，由OrderServiceImpl实现。
     * @param id 前端传入的订单字符串ID
     * @return 已付款订单的现有OrderVO；无法付款时抛出业务异常
     */
    OrderVO payOrder(String id);
}
