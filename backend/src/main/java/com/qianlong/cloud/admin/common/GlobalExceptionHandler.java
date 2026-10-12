package com.qianlong.cloud.admin.common;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理。
 *
 * <p><b>关键决定：一律返回 HTTP 200 + {@code code != 0}。</b>
 *
 * <p>前端 {@code src/api/http.ts} 把 404/501 当成「后端尚未实现」，
 * 而 {@code useResource} 把 HTTP 50x 当成「后端不可达」从而**降级到演示数据**。
 * 若业务错误（如「密码错误」「角色已存在」）返回 5xx，前端会拿假数据盖住真实故障 ——
 * 这是管理真实交易系统的后台，绝不允许。因此这里把所有业务异常映射到同一个 200 信封，
 * 由 {@code code} 表达失败。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：原样透出错误码与文案（前端按 code 分派） */
    @ExceptionHandler(BizException.class)
    public ApiResult<Void> handleBiz(BizException ex) {
        log.info("业务异常 code={} message={}", ex.getCode(), ex.getMessage());
        return ApiResult.fail(ex.getCode(), ex.getMessage());
    }

    /** 未登录 / token 失效 */
    @ExceptionHandler(NotLoginException.class)
    public ApiResult<Void> handleNotLogin(NotLoginException ex) {
        log.info("未登录或凭证失效：{}", ex.getMessage());
        return ApiResult.fail(ErrorCode.NOT_LOGIN, "登录状态已失效，请重新登录");
    }

    /** 权限不足 —— 后端才是安全边界，前端按钮置灰只是提示 */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ApiResult<Void> handleNoPermission(Exception ex) {
        log.warn("权限不足：{}", ex.getMessage());
        return ApiResult.fail(ErrorCode.NO_PERMISSION, "没有执行该操作的权限");
    }

    /** {@code @Valid} 参数校验失败：把第一条字段错误原样给出，便于定位 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResult<Void> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("请求参数不合法");
        return ApiResult.fail(ErrorCode.BAD_REQUEST, message);
    }

    /** 兜底：内部错误只回笼统文案，细节进日志（不把堆栈/SQL 泄露给前端） */
    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleOther(Exception ex) {
        log.error("未预期的服务端异常", ex);
        return ApiResult.fail(ErrorCode.INTERNAL, "服务端内部错误，请查看后端日志");
    }
}
