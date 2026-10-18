package com.zlo.wallpaper.common;

/**
 * 定义成功或失败的统一JSON外壳，包含code、message、data与requestId。
 * 创建方式：调用方通过record构造器创建，编译器生成字段访问方法；Jackson用于序列化或反序列化。
 * 显式实例化位置：ApiResponse.failure、ApiResponse.success。
 */
public record ApiResponse<T>(String code, String message, T data, String requestId) {

    /**
     * 构造code为OK的统一响应，保留业务数据与本次请求标识。
     * 调用/引用位置：AdminCategoryController.createCategory、AdminCategoryController.deleteCategory、AdminCategoryController.getAdminCategories、AdminCategoryController.updateCategory、AdminWallpaperController.createWallpaper、AdminWallpaperController.getWallpapers、AdminWallpaperController.setPublication、AdminWallpaperController.updateWallpaper、AuthController.login、AuthController.logout、AuthController.refresh、AuthController.register、CategoryController.getCategories、HealthController.health、OrderController.createOrder、UserController.getCurrentUser、WallpaperController.detail、WallpaperController.page。
     *
     * @param data 允许返回给前端的业务数据
     * @param requestId 当前HTTP请求标识，不包含认证令牌
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>("OK", "", data, requestId);
    }

    /**
     * 构造业务错误统一响应，data为null并保留错误码与请求标识。
     * 调用/引用位置：AuthFilter.writeError、GlobalExceptionHandler.error、HealthController.unavailable。
     *
     * @param code 统一响应使用的业务错误码
     * @param message 允许向前端展示的业务说明
     * @param requestId 当前HTTP请求标识，不包含认证令牌
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    public static ApiResponse<Void> failure(String code, String message, String requestId) {
        return new ApiResponse<>(code, message, null, requestId);
    }
}
