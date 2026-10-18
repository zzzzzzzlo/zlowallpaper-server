package com.zlo.wallpaper.order.controller;

import com.zlo.wallpaper.common.ApiResponse;
import com.zlo.wallpaper.common.RequestIdFilter;
import com.zlo.wallpaper.order.dto.OrderCreateDTO;
import com.zlo.wallpaper.order.model.OrderCreateResult;
import com.zlo.wallpaper.order.service.OrderService;
import com.zlo.wallpaper.order.vo.OrderVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 提供创建订单、查询我的订单及壁纸币付款入口；创建新单返回201，其余成功返回200。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@RestController
public class OrderController {
    @Autowired private OrderService orderService;

    /**
     * 查询当前登录账号的订单；用户身份由AuthFilter提供，不接收用户ID。
     * 调用位置：Spring MVC处理GET /me/orders。
     * @param request 当前HTTP请求，用于取得requestId
     * @return 按创建时间及ID倒序的OrderVO数组，无订单时data为空数组
     */
    @GetMapping("/me/orders")
    public ApiResponse<List<OrderVO>> getMyOrders(HttpServletRequest request) {
        return ApiResponse.success(orderService.getMyOrders(), RequestIdFilter.current(request));
    }

    /**
     * 接收订单JSON并调用业务层，新单返回201、复用来源或待付款订单返回200。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @PostMapping(value = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<OrderVO>> createOrder(@Valid @RequestBody OrderCreateDTO dto,
            HttpServletRequest request) {
        OrderCreateResult result = orderService.createOrder(dto);
        HttpStatus status = result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(result.getOrder(), RequestIdFilter.current(request)));
    }

    /**
     * 接收订单ID并发起壁纸币付款；不要求请求体，身份由AuthFilter提供。
     * 调用位置：Spring MVC处理POST /orders/{id}/pay。
     * @param id 路径中的订单字符串ID
     * @param request 当前请求，用于取得requestId
     * @return HTTP 200的统一订单响应，重复付款也返回200
     */
    @PostMapping("/orders/{id}/pay")
    public ApiResponse<OrderVO> payOrder(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(orderService.payOrder(id), RequestIdFilter.current(request));
    }
}
