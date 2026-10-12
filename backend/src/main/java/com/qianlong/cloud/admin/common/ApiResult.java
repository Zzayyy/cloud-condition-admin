package com.qianlong.cloud.admin.common;

/**
 * 统一响应信封 —— 与前端 {@code src/api/types.ts} 的 {@code ApiEnvelope<T>} 完全对应。
 *
 * <p><b>为什么所有响应（含错误）都返回 HTTP 200：</b>前端 {@code useResource} 的降级判定是
 * 「连不上才降级」。若把业务错误写成 HTTP 500，前端的 {@code isUnreachable()} 会把
 * {@code HTTP 50x} 认成「后端不可达」从而**降级到演示数据** —— 真实故障会被假数据盖住。
 * 所以业务错误一律走 {@code code != 0}（前端抛 {@code BizError}，不降级），
 * HTTP 状态码只留给网络层与网关。
 */
public record ApiResult<T>(int code, String message, T data) {

    public static final int CODE_OK = 0;

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(CODE_OK, "ok", data);
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(CODE_OK, "ok", null);
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
