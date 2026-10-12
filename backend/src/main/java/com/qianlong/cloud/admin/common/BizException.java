package com.qianlong.cloud.admin.common;

/**
 * 业务异常：携带前端可识别的错误码。
 *
 * <p>由 {@link GlobalExceptionHandler} 转成 {@code {code,message,data:null}} 且 HTTP 200。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(ErrorCode.BAD_REQUEST, message);
    }

    public int getCode() {
        return code;
    }

    public static BizException badRequest(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }

    public static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    public static BizException conflict(String message) {
        return new BizException(ErrorCode.CONFLICT, message);
    }
}
