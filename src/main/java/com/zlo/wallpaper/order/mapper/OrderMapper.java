package com.zlo.wallpaper.order.mapper;

import com.zlo.wallpaper.order.entity.Order;
import com.zlo.wallpaper.order.model.OrderProductRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * 声明本账号订单列表查询，以及下单、付款时的锁定读取与订单写入SQL。
 * 创建方式：MyBatis扫描@Mapper后生成代理，SQL实现在同模块Mapper XML中。
 * 注入位置：OrderServiceImpl.orderMapper。
 * 数据库映射位置：src/main/resources/mapper/order/OrderMapper.xml。
 */
@Mapper
public interface OrderMapper {
    /**
     * 只查询指定账号的订单，包含全部状态，按创建时间和ID倒序读取成交快照。
     * 调用位置：OrderServiceImpl.getMyOrders；SQL位于OrderMapper.xml。
     * @param userId 从BaseContext取得的可信用户ID
     * @param limit 最大读取条数，业务层传上限加1用于识别超限
     * @return 匹配的订单实体列表，没有记录时为空列表
     */
    List<Order> selectOrdersByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 锁定当前用户行并读取账号状态，使同账号并发下单按顺序判断。
     * 调用/引用位置：OrderServiceImpl.createOrder、payOrder。
     *
     * @param userId 当前可信用户的数据库ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    String selectBuyerStatusForUpdate(@Param("userId") Long userId);
    /**
     * 锁定商品并读取标题、价格、币种和发布状态快照。
     * 调用/引用位置：OrderServiceImpl.createOrder、payOrder。
     *
     * @param wallpaperId 本次购买或查询的商品数据库ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    OrderProductRow selectProductForUpdate(@Param("wallpaperId") Long wallpaperId);
    /**
     * 锁定读取同用户同商品的PENDING或PAID订单，供复用待付款订单或发现异常。
     * 调用/引用位置：OrderServiceImpl.createOrder、payOrder。
     *
     * @param userId 当前可信用户的数据库ID
     * @param wallpaperId 本次购买或查询的商品数据库ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Order selectActiveOrderForUpdate(@Param("userId") Long userId, @Param("wallpaperId") Long wallpaperId);
    /**
     * 同时限制用户ID和订单ID读取来源订单，禁止跨账号读取。
     * 调用/引用位置：OrderServiceImpl.createOrder、payOrder。
     *
     * @param userId 当前可信用户的数据库ID
     * @param orderId 指定来源订单的数据库ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Order selectOwnedOrderForUpdate(@Param("userId") Long userId, @Param("orderId") Long orderId);
    /**
     * 插入服务器生成的成交快照和初始付款状态，并回填订单主键。
     * 调用/引用位置：OrderServiceImpl.createOrder、payOrder。
     *
     * @param order 当前订单实体，包含服务器成交快照
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int insertOrder(Order order);

    /**
     * 将已锁定的待付款订单更新为壁纸币支付成功，保持下单金额及标题快照。
     * 调用位置：OrderServiceImpl.payOrder；SQL位于OrderMapper.xml。
     * @param order 当前用户的订单，带本次统一的paidAt和updatedAt
     * @return 更新行数，正常状态转换应为1
     */
    int updatePaidStatus(Order order);
}
