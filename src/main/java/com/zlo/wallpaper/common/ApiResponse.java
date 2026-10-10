package com.zlo.wallpaper.common;

/** 与现有 OpenAPI 保持一致的响应外壳；业务模块后续复用。 */
public record ApiResponse<T>(String code, String message, T data, String requestId) {

    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>("OK", "", data, requestId);
    }

    public static ApiResponse<Void> failure(String code, String message, String requestId) {
        return new ApiResponse<>(code, message, null, requestId);
    }
}
