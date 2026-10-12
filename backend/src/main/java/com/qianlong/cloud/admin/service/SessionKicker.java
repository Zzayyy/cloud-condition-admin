package com.qianlong.cloud.admin.service;

import org.springframework.stereotype.Component;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 让指定账号的已签发 token 立即失效。
 *
 * <p><b>为什么必须单独抽一个组件：</b>这是「停用 / 删除 / 改权限 / 改密码」四类操作的
 * 共同收尾动作，也是最容易漏掉的一步。漏掉的后果是真实的越权：
 * 库里的 {@code enabled} 已经是 0，但那个人的浏览器里还拿着有效 token，
 * 刷一下页面照样什么都能看、能点。前端 {@code SystemUser.vue} 的提示文案里
 * 明确写了「只改库字段等于没停」—— 这里就是那句话的落地。
 *
 * <p>实现上刻意<b>不</b>用「改密码让 token 失效」这种绕路做法：
 * Sa-Token 的 {@code kickout} 是语义清晰的会话注销，不会产生额外的密码变更副作用。
 */
@Slf4j
@Component
public class SessionKicker {

    /**
     * 踢掉该账号的全部会话。
     *
     * <p>失败不能让主流程失败（业务数据已经改完了，回滚反而更糟），
     * 但要打 error 日志 —— 这是安全相关的失败，不能静默。
     */
    public void kick(Integer userId) {
        if (userId == null) {
            return;
        }
        try {
            StpUtil.kickout(userId);
            log.info("已强制注销账号 {} 的全部会话（停用/删除/改权限/改密码）", userId);
        } catch (Exception ex) {
            log.error("强制注销账号 {} 的会话失败 —— 该账号的旧 token 可能仍然有效，请人工确认", userId, ex);
        }
    }
}
