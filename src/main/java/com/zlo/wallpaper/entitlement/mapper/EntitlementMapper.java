package com.zlo.wallpaper.entitlement.mapper;

import com.zlo.wallpaper.entitlement.entity.Entitlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 声明当前权益查询、新增及按来源订单精确删除的SQL。
 * 创建方式：MyBatis扫描@Mapper后生成代理，SQL实现在同模块Mapper XML中。
 * 注入位置：OrderMySqlIT.entitlements、OrderServiceImpl.entitlementMapper。
 * 数据库映射位置：src/main/resources/mapper/entitlement/EntitlementMapper.xml。
 */
@Mapper
public interface EntitlementMapper {
    /**
     * 按当前用户和商品锁定读取有效权益，记录不存在即没有权益。
     * 调用/引用位置：OrderServiceImpl.createOrder、payOrder。
     *
     * @param userId 当前可信用户的数据库ID
     * @param wallpaperId 本次购买或查询的商品数据库ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Entitlement selectForUpdate(@Param("userId") Long userId, @Param("wallpaperId") Long wallpaperId);
    /**
     * 把实体写入对应数据库表，并在有自增主键时回填id。
     * 调用/引用位置：OrderIntegrationTest.entitlementInsertFailureRollsBackOrderAndAllowsRetry、OrderServiceImpl.grantEntitlement。
     *
     * @param entitlement 当前用户当前商品的权益记录
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int insert(Entitlement entitlement);
    /**
     * 只删除当前用户当前商品由指定订单授予的权益，保护重新购买的新权益。
     * 调用/引用位置：OrderIntegrationTest.refundDeletesEntitlementAndRepurchaseCreatesNewOrderAndEntitlement、OrderMySqlIT.freePurchaseCreatesNewEntitlementAfterRefundAndOldRefundCannotDeleteIt。
     *
     * @param userId 当前可信用户的数据库ID
     * @param wallpaperId 本次购买或查询的商品数据库ID
     * @param orderId 指定来源订单的数据库ID
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int deleteBySourceOrder(@Param("userId") Long userId, @Param("wallpaperId") Long wallpaperId,
                            @Param("orderId") Long orderId);
}
