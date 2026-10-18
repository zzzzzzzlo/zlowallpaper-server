package com.zlo.wallpaper.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 统一转换业务、校验、媒体类型、资源不存在及服务异常，不向前端暴露堆栈和密钥。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 把multipart大小超限映射为413及统一错误响应。
     * 框架调用位置：Spring MVC捕获对应异常后回调。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> uploadTooLarge(HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "上传文件或请求超过大小限制", request);
    }

    /**
     * 把损坏multipart或缺少文件部分映射为400及统一错误响应。
     * 框架调用位置：Spring MVC捕获对应异常后回调。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> malformedMultipart(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请使用 multipart/form-data 提交有效文件", request);
    }

    /**
     * 把不符合DTO契约的JSON读取失败映射为400，避免回显原始输入。
     * 框架调用位置：Spring MVC捕获对应异常后回调。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformedJson(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求体必须是符合接口约定的JSON", request);
    }

    /**
     * 把请求体类型与接口consumes不一致映射为415。
     * 框架调用位置：Spring MVC捕获对应异常后回调。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedMediaType(HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "VALIDATION_ERROR", "请求体类型不符合接口约定", request);
    }

    /**
     * 保留业务异常定义的HTTP状态、错误码和消息，包装为统一响应。
     * 框架调用位置：Spring MVC捕获对应异常后回调。
     *
     * @param exception 当前捕获的异常，仅向前端返回安全业务说明
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException exception, HttpServletRequest request) {
        return error(exception.getStatus(), exception.getCode(), exception.getMessage(), request);
    }

    /**
     * 将绑定、方法校验和参数转换失败映射为400，优先使用可展示的业务校验消息。
     * 框架调用位置：Spring MVC捕获对应异常后回调。
     *
     * @param exception 当前捕获的异常，仅向前端返回安全业务说明
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @ExceptionHandler({BindException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> validation(Exception exception, HttpServletRequest request) {
        // 不回显原始输入或框架异常中的 SQL、连接信息。
        String message = "请求参数不符合接口约定";
        if (exception instanceof BindException binding) {
            message = binding.getBindingResult().getAllErrors().stream()
                    .filter(e -> !(e instanceof org.springframework.validation.FieldError field)
                            || !field.isBindingFailure())
                    .map(org.springframework.validation.ObjectError::getDefaultMessage)
                    .findFirst().orElse(message);
        }
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    /**
     * 将数据库连接类故障映射为503 SERVICE_UNAVAILABLE并记录请求标识。
     * 框架调用位置：Spring MVC捕获对应异常后回调。
     *
     * @param exception 当前捕获的异常，仅向前端返回安全业务说明
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessResourceException.class})
    public ResponseEntity<ApiResponse<Void>> unavailable(Exception exception, HttpServletRequest request) {
        log.error("数据库不可用，requestId={}", RequestIdFilter.current(request), exception);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "数据库暂不可用", request);
    }

    /**
     * 将没有对应处理资源的请求映射为404 RESOURCE_NOT_FOUND。
     * 框架调用位置：Spring MVC捕获对应异常后回调。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "请求的资源不存在", request);
    }

    /**
     * 把当前路径不支持的HTTP方法映射为405。
     * 框架调用位置：Spring MVC捕获对应异常后回调。
     *
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "请求方法不受支持", request);
    }

    /**
     * 记录带requestId的内部异常并返回安全的500错误，不暴露内部堆栈。
     * 框架调用位置：Spring MVC捕获对应异常后回调。
     *
     * @param exception 当前捕获的异常，仅向前端返回安全业务说明
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception, HttpServletRequest request) {
        log.error("请求处理失败，requestId={}", RequestIdFilter.current(request), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时无法处理请求", request);
    }

    /**
     * 生成带实际HTTP状态的统一错误响应，供全局异常处理方法复用。
     * 调用/引用位置：GlobalExceptionHandler.business、GlobalExceptionHandler.malformedJson、GlobalExceptionHandler.malformedMultipart、GlobalExceptionHandler.methodNotAllowed、GlobalExceptionHandler.notFound、GlobalExceptionHandler.unavailable、GlobalExceptionHandler.unexpected、GlobalExceptionHandler.unsupportedMediaType、GlobalExceptionHandler.uploadTooLarge、GlobalExceptionHandler.validation。
     *
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     * @param code 统一响应使用的业务错误码
     * @param message 允许向前端展示的业务说明
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String code, String message,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiResponse.failure(code, message, RequestIdFilter.current(request)));
    }
}
