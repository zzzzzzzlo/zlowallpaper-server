package com.zlo.wallpaper.order.service.impl;

import com.zlo.wallpaper.catalog.service.CatalogIds;
import com.zlo.wallpaper.auth.mapper.UserMapper;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.entitlement.entity.Entitlement;
import com.zlo.wallpaper.entitlement.mapper.EntitlementMapper;
import com.zlo.wallpaper.order.dto.OrderCreateDTO;
import com.zlo.wallpaper.order.entity.Order;
import com.zlo.wallpaper.order.mapper.OrderMapper;
import com.zlo.wallpaper.order.model.OrderCreateResult;
import com.zlo.wallpaper.order.model.OrderProductRow;
import com.zlo.wallpaper.order.service.OrderService;
import com.zlo.wallpaper.order.vo.OrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 集中实现创建、查询订单与壁纸币付款，写操作通过事务、行锁和唯一约束保护订单和当前权益。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：OrderController.orderService、OrderIntegrationTest.service、OrderMySqlIT.service。
 */
@Service
public class OrderServiceImpl implements OrderService {
    // v1订单列表不分页；多读1条识别超限，避免把不完整列表当成全部订单返回。
    private static final int ORDER_LIST_LIMIT = 1000;
    @Autowired private OrderMapper orderMapper;
    @Autowired private EntitlementMapper entitlementMapper;
    @Autowired private UserMapper userMapper;

    /**
     * 按登录身份查询全部状态订单，并直接转换订单快照，不关联商品或权益表。
     * 调用位置：OrderController.getMyOrders；测试也可直接调用验证身份与SQL。
     * 输入参数：无，当前用户ID来自BaseContext。
     * @return 最多1000条OrderVO；没有订单返回空列表，超过上限抛出业务异常
     */
    @Override
    public List<OrderVO> getMyOrders() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) { throw loginRequired(); }

        // 一次普通SELECT读取已提交的订单，无需事务或FOR UPDATE，不参与下单、付款锁竞争。
        List<Order> orders = orderMapper.selectOrdersByUserId(userId, ORDER_LIST_LIMIT + 1);
        if (orders.size() > ORDER_LIST_LIMIT) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_LIST_LIMIT_EXCEEDED", "订单超过1000条，暂不支持查询完整列表");
        }
        // 列表中余额是本次查询时的当前余额；只查一次，避免每笔订单再查一次用户表。
        Long balanceCents = userMapper.selectBalanceById(userId);
        if (balanceCents == null) { throw loginRequired(); }
        List<OrderVO> result = new ArrayList<>(orders.size());
        for (Order order : orders) {
            result.add(toOrderVO(order, balanceCents));
        }
        return result;
    }

    /**
     * 在事务内先查当前权益，再复用待付款订单；确需新单才读取价格创建，免费单同时授予权益。
     * 调用/引用位置：OrderController.createOrder、OrderIntegrationTest.concurrent、OrderMySqlIT.create。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 订单VO与created标志；新单为true，已有权益或待付款订单复用为false
     */
    @Override
    @Transactional
    public OrderCreateResult createOrder(OrderCreateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) { throw loginRequired(); }
        Long wallpaperId = CatalogIds.findDatabaseId(dto.getWallpaperId());
        if (wallpaperId == null) { throw productMissing(); }

        // 【并发控制：账号行锁】下单与付款都先锁本账号，使并发请求依次读取最新购买状态。
        if (!"ACTIVE".equals(orderMapper.selectBuyerStatusForUpdate(userId))) { throw loginRequired(); }
        // 用户行锁仍由原下单事务持有，余额不会被另一笔并发付款改动。
        Long balanceCents = userMapper.selectBalanceById(userId);
        // 先锁商品，使新下单与调价/下架保持一致；发布状态只在确实需要新单时检查。
        OrderProductRow product = orderMapper.selectProductForUpdate(wallpaperId);
        if (product == null) { throw productMissing(); }

        // 权益表只保存有效权益；存在即已拥有，直接返回授予该权益的订单。
        Entitlement entitlement = entitlementMapper.selectForUpdate(userId, wallpaperId);
        if (entitlement != null) {
            Order ownedOrder = orderMapper.selectOwnedOrderForUpdate(userId, entitlement.getSourceOrderId());
            if (ownedOrder == null || !wallpaperId.equals(ownedOrder.getWallpaperId()) || !"PAID".equals(ownedOrder.getStatus())) {
                throw purchaseConflict();
            }
            return result(ownedOrder, false, balanceCents);
        }

        Order activeOrder = orderMapper.selectActiveOrderForUpdate(userId, wallpaperId);
        if (activeOrder != null) {
            // 查到PAID却没有权益，说明记录不一致，不能再创建一笔收费订单。
            if (!"PENDING".equals(activeOrder.getStatus())) { throw purchaseConflict(); }
            return result(activeOrder, false, balanceCents);
        }
        if (!"PUBLISHED".equals(product.getPublicationStatus())) { throw productMissing(); }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        Order order = new Order();
        order.setUserId(userId);
        order.setWallpaperId(wallpaperId);
        order.setTitleSnapshot(product.getTitle());
        order.setAmountCents(product.getPriceCents());
        order.setCurrency(product.getCurrency());
        order.setStatus(product.getPriceCents() == 0 ? "PAID" : "PENDING");
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        if (product.getPriceCents() == 0) {
            order.setPaymentMethod("FREE");
            order.setPaidAt(now);
        }
        orderMapper.insertOrder(order);
        if ("PAID".equals(order.getStatus())) { grantEntitlement(order, now); }
        return result(order, true, balanceCents);
    }

    /**
     * 按订单快照完成壁纸币付款；主流程在本方法内，扣余额、更新订单和增加权益在同一事务提交。
     * 调用位置：OrderController.payOrder，也由集成测试直接调用以验证并发。
     * @param id 前端传入的订单字符串ID
     * @return 付款后的订单；已付款且有对应权益时返回原订单，不修改付款信息
     */
    @Override
    @Transactional
    public OrderVO payOrder(String id) {
        // 1. 身份取自登录上下文；正常壁纸币付款不再依赖测试开关。
        Long userId = BaseContext.getCurrentId();
        if (userId == null) { throw loginRequired(); }
        CatalogIds.validatePathId(id);
        Long orderId = CatalogIds.findDatabaseId(id);
        if (orderId == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        }

        // 2. 【并发控制】与createOrder保持相同的第一把锁：用户行，然后锁本用户订单。
        // 【并发复查】过滤器确认的是认证时的账号状态，拿到锁后重新确认账号仍有效。
        if (!"ACTIVE".equals(orderMapper.selectBuyerStatusForUpdate(userId))) { throw loginRequired(); }
        Order order = orderMapper.selectOwnedOrderForUpdate(userId, orderId);
        if (order == null) {
            // 不区分不存在和他人的订单；查询本身已经带user_id条件。
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        }

        // 3. 取消/退款订单不能重新付款；退款后再购买应创建新订单。
        if (!"PENDING".equals(order.getStatus()) && !"PAID".equals(order.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_NOT_PAYABLE", "已取消或已退款的订单不能付款");
        }
        Entitlement entitlement = entitlementMapper.selectForUpdate(userId, order.getWallpaperId());
        if ("PAID".equals(order.getStatus())) {
            // 【并发复查】后到的付款请求在锁释放后读到PAID，只返回先提交的结果。
            // 对应权益缺失/来源不同属于记录冲突，不自动补发，也不改FREE订单原付款方式。
            if (entitlement == null || !order.getId().equals(entitlement.getSourceOrderId())) {
                throw purchaseConflict();
            }
            return toOrderVO(order, userMapper.selectBalanceById(userId));
        }
        if (entitlement != null) { throw purchaseConflict(); }

        // 4. 使用订单原金额、标题、币种，不受商品后续调价或下架影响。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        // 【并发控制：扣余额】用户行锁覆盖整笔付款；SQL余额条件保证不同订单也不能透支。
        // 同一订单重复付款已在上面返回，不会走到这里，因此不会重复扣币。
        if (userMapper.deductBalance(userId, order.getAmountCents(), now) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", "壁纸币余额不足");
        }
        order.setStatus("PAID");
        order.setPaymentMethod("COIN");
        order.setPaidAt(now);
        order.setUpdatedAt(now);
        if (orderMapper.updatePaidStatus(order) != 1) { throw purchaseConflict(); }
        grantEntitlement(order, now);

        // 5. 任意写入失败，异常向外抛出，Spring一起回滚余额、订单和权益；Controller在提交后返回。
        return toOrderVO(order, userMapper.selectBalanceById(userId));
    }

    /**
     * 为免费下单或壁纸币付款的PAID订单插入当前权益，复用调用方事务。
     * 调用位置：OrderServiceImpl.createOrder、payOrder。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param order 当前订单实体，包含服务器成交快照
     * @param now 本次操作统一使用的UTC时间
     */
    private void grantEntitlement(Order order, LocalDateTime now) {
        Entitlement entitlement = new Entitlement();
        entitlement.setUserId(order.getUserId());
        entitlement.setWallpaperId(order.getWallpaperId());
        entitlement.setCreatedAt(now);
        entitlement.setSourceOrderId(order.getId());
        entitlement.setGrantedAt(now);
        entitlement.setUpdatedAt(now);
        entitlementMapper.insert(entitlement);
    }

    /**
     * 把订单快照转成OrderVO，并携带仅供Controller使用的新建标志。
     * 调用/引用位置：OrderServiceImpl.createOrder。
     *
     * @param order 当前订单实体，包含服务器成交快照
     * @param created 是否刚创建订单；false表示复用已有业务结果
     * @param balanceCents 已查询的当前余额，和订单一起返回确认界面
     * @return 订单VO与created标志；新单为true，已有权益或待付款订单复用为false
     */
    private OrderCreateResult result(Order order, boolean created, Long balanceCents) {
        OrderCreateResult result = new OrderCreateResult();
        result.setOrder(toOrderVO(order, balanceCents));
        result.setCreated(created);
        return result;
    }

    /**
     * 将订单快照与当前余额转换为公开订单数据；不在此函数中再次查询数据库。
     * 调用位置：OrderServiceImpl.result、payOrder、getMyOrders。
     * @param order 下单、复用、付款或列表查询取得的订单实体
     * @param balanceCents 本次查询时的当前余额，付款成功时为扣款后的余额
     * @return 字符串订单/用户/商品ID、当前余额、成交快照、状态和UTC创建时间
     */
    private OrderVO toOrderVO(Order order, Long balanceCents) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId().toString());
        vo.setUserId(order.getUserId().toString());
        vo.setBalanceCents(balanceCents.toString());
        vo.setWallpaperId(order.getWallpaperId().toString());
        vo.setTitle(order.getTitleSnapshot());
        vo.setAmountCents(order.getAmountCents());
        vo.setCurrency(order.getCurrency());
        vo.setStatus(order.getStatus());
        vo.setCreatedAt(order.getCreatedAt().atOffset(ZoneOffset.UTC));
        return vo;
    }

    /**
     * 构造商品不存在或当前不可购买的404业务异常。
     * 调用/引用位置：OrderServiceImpl.createOrder。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException productMissing() {
        return new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在或当前不可购买");
    }

    /**
     * 构造订单与权益不一致的409业务异常，避免重复收费。
     * 调用位置：OrderServiceImpl.createOrder、payOrder。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException purchaseConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "PURCHASE_STATE_CONFLICT", "购买记录状态不一致，请联系管理员");
    }

    /**
     * 构造没有可信用户身份或账号已禁用的401业务异常。
     * 调用位置：OrderServiceImpl.createOrder、payOrder、getMyOrders。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException loginRequired() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请重新登录");
    }
}
