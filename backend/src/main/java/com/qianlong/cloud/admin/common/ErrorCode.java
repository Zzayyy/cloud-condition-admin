package com.qianlong.cloud.admin.common;

/**
 * 业务错误码。
 *
 * <p>登录相关的三个码**刻意与前端 {@code src/api/domain.ts} 的 {@code LOGIN_ERROR_CODES}
 * 保持同一组数值**：前端按 {@code code} 分派文案，不去匹配 message 字符串
 * （后端换一次措辞就会让前端判断静默失效）。
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    /** 参数或业务校验不通过 */
    public static final int BAD_REQUEST = 40000;
    /** 未登录 / token 失效 */
    public static final int NOT_LOGIN = 40100;
    /**
     * 账号或密码错误。
     *
     * <p>刻意把「账号不存在」与「密码错误」合并成同一个码 —— 分开返回等于给攻击者一个
     * 账号枚举接口（能逐个试出哪些账号真实存在）。前端文案同样是「账号或密码错误」。
     */
    public static final int BAD_CREDENTIALS = 40101;
    /** 账号已停用 */
    public static final int DISABLED = 40102;
    /** 账号因连续失败被锁定 */
    public static final int LOCKED = 40103;
    /** 没有权限 */
    public static final int NO_PERMISSION = 40300;
    /** 资源不存在 */
    public static final int NOT_FOUND = 40400;
    /** 与服务端状态冲突（唯一键重复、内置对象受保护等） */
    public static final int CONFLICT = 40900;
    /** 服务端内部错误 */
    public static final int INTERNAL = 50000;
}
