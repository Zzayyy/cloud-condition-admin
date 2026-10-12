package com.qianlong.cloud.admin.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.dto.SystemDtos.PasswordPolicyDto;
import com.qianlong.cloud.admin.entity.AdminPasswordHistoryEntity;
import com.qianlong.cloud.admin.entity.AdminPasswordPolicyEntity;
import com.qianlong.cloud.admin.mapper.AdminPasswordHistoryMapper;
import com.qianlong.cloud.admin.mapper.AdminPasswordPolicyMapper;

import lombok.RequiredArgsConstructor;

/**
 * 密码策略：读取、保存，以及**真正的密码判定**。
 *
 * <p><b>为什么判定必须在后端：</b>前端 {@code SystemUser.vue} 里也有一份即时校验清单，
 * 但那是体验（策略可能过期，前端也能被绕过），不能作为安全边界。
 * 前端注释里已经写明这一点，这里就是那句话的落地。
 *
 * <p><b>「不得与历史密码重复」怎么算：</b>BCrypt 自带随机 salt，同一条密码每次哈希都不同，
 * 所以只能逐条 {@code matches}，不能用哈希值相等来比较。改密流程固定为：
 * <ol>
 *   <li>把<b>当前</b>正在用的哈希先写进历史表（否则「改回上一个密码」会被放行）；</li>
 *   <li>再拿新密码跟最近 {@code historySize} 条历史逐个比对。</li>
 * </ol>
 * 这个顺序由 {@link #preparePasswordChange} + {@link #validatePassword} 一起保证。
 */
@Service
@RequiredArgsConstructor
public class PasswordPolicyService {

    /** 单行配置表的固定主键 */
    private static final int POLICY_ID = 1;

    private final AdminPasswordPolicyMapper policyMapper;
    private final AdminPasswordHistoryMapper historyMapper;
    private final PasswordEncoderHolder encoderHolder;

    /** 读取策略；表里没有这一行时返回默认值（不报错，避免首次部署就不可用） */
    public PasswordPolicyDto getPolicy() {
        AdminPasswordPolicyEntity entity = policyMapper.selectById(POLICY_ID);
        return entity == null ? defaultPolicy() : toDto(entity);
    }

    public PasswordPolicyDto savePolicy(PasswordPolicyDto payload) {
        AdminPasswordPolicyEntity entity = policyMapper.selectById(POLICY_ID);
        boolean insert = entity == null;
        if (insert) {
            entity = new AdminPasswordPolicyEntity();
            entity.setId(POLICY_ID);
        }
        entity.setMinLength(require(payload.minLength(), "最小长度"));
        entity.setRequireUpper(bool(payload.requireUpper()));
        entity.setRequireLower(bool(payload.requireLower()));
        entity.setRequireDigit(bool(payload.requireDigit()));
        entity.setRequireSymbol(bool(payload.requireSymbol()));
        entity.setMaxFailedAttempts(nonNegative(payload.maxFailedAttempts(), "失败锁定次数"));
        entity.setLockMinutes(nonNegative(payload.lockMinutes(), "锁定时长"));
        entity.setExpireDays(nonNegative(payload.expireDays(), "密码有效期"));
        entity.setForceChangeOnFirstLogin(bool(payload.forceChangeOnFirstLogin()));
        entity.setHistorySize(nonNegative(payload.historySize(), "历史密码条数"));
        if (insert) {
            policyMapper.insert(entity);
        } else {
            policyMapper.updateById(entity);
        }
        return getPolicy();
    }

    /**
     * 改密前调用：把「当前密码哈希」写进历史表。
     *
     * <p>这样紧接着的 {@link #validatePassword} 就能拦住「把密码改回上一版」。
     */
    public void preparePasswordChange(Integer userId, String currentHash) {
        if (userId == null || !StringUtils.hasText(currentHash)) {
            return;
        }
        appendHistory(userId, currentHash);
    }

    /**
     * 按当前策略校验新密码。
     *
     * @param raw      明文密码
     * @param username 登录名（策略要求密码不得与登录名相同）
     * @param userId   账号 id；为 {@code null}（新建账号）时跳过历史密码校验
     * @throws BizException 不合规时抛出，文案直接面向使用者
     */
    public void validatePassword(String raw, String username, Integer userId) {
        PasswordPolicyDto policy = getPolicy();

        if (!StringUtils.hasText(raw)) {
            throw BizException.badRequest("密码不能为空");
        }
        if (policy.minLength() == null || raw.length() < policy.minLength()) {
            throw BizException.badRequest("密码长度不得少于 " + policy.minLength() + " 位");
        }
        if (Boolean.TRUE.equals(policy.requireUpper()) && raw.chars().noneMatch(Character::isUpperCase)) {
            throw BizException.badRequest("密码必须包含大写字母");
        }
        if (Boolean.TRUE.equals(policy.requireLower()) && raw.chars().noneMatch(Character::isLowerCase)) {
            throw BizException.badRequest("密码必须包含小写字母");
        }
        if (Boolean.TRUE.equals(policy.requireDigit()) && raw.chars().noneMatch(Character::isDigit)) {
            throw BizException.badRequest("密码必须包含数字");
        }
        if (Boolean.TRUE.equals(policy.requireSymbol())
                && raw.chars().allMatch(Character::isLetterOrDigit)) {
            throw BizException.badRequest("密码必须包含符号（非字母数字）");
        }
        if (StringUtils.hasText(username) && raw.equalsIgnoreCase(username.trim())) {
            throw BizException.badRequest("密码不得与登录名相同");
        }

        int keep = policy.historySize() == null ? 0 : policy.historySize();
        if (userId != null && keep > 0) {
            for (AdminPasswordHistoryEntity row : recentHistory(userId, keep)) {
                if (encoderHolder.matches(raw, row.getPasswordHash())) {
                    throw BizException.badRequest(
                            "新密码不得与最近 " + keep + " 次使用过的密码重复");
                }
            }
        }
    }

    /** 改密成功后调用：把新哈希写入历史，并把表裁剪到策略约定的条数 */
    public void recordNewPassword(Integer userId, String newHash) {
        appendHistory(userId, newHash);
        int keep = getPolicy().historySize() == null ? 0 : getPolicy().historySize();
        if (keep <= 0) {
            // 策略关闭历史校验时不留存（但当前正在用的密码不在本表里，所以全删是安全的）
            historyMapper.delete(Wrappers.<AdminPasswordHistoryEntity>lambdaQuery()
                    .eq(AdminPasswordHistoryEntity::getUserId, userId));
            return;
        }
        // 保留最近 keep 条（含刚插入的这条），更早的删掉，避免这张表无限增长
        List<Integer> keepIds = recentHistory(userId, keep).stream()
                .map(AdminPasswordHistoryEntity::getId)
                .toList();
        historyMapper.delete(Wrappers.<AdminPasswordHistoryEntity>lambdaQuery()
                .eq(AdminPasswordHistoryEntity::getUserId, userId)
                .notIn(AdminPasswordHistoryEntity::getId, keepIds));
    }

    private void appendHistory(Integer userId, String hash) {
        AdminPasswordHistoryEntity entity = new AdminPasswordHistoryEntity();
        entity.setUserId(userId);
        entity.setPasswordHash(hash);
        entity.setCreatedAt(LocalDateTime.now());
        historyMapper.insert(entity);
    }

    private List<AdminPasswordHistoryEntity> recentHistory(Integer userId, int keep) {
        return historyMapper.selectList(Wrappers.<AdminPasswordHistoryEntity>lambdaQuery()
                .eq(AdminPasswordHistoryEntity::getUserId, userId)
                .orderByDesc(AdminPasswordHistoryEntity::getCreatedAt)
                .orderByDesc(AdminPasswordHistoryEntity::getId)
                .last("LIMIT " + keep));
    }

    private static Integer require(Integer value, String name) {
        if (value == null) {
            throw BizException.badRequest(name + "不能为空");
        }
        return value;
    }

    private static Integer nonNegative(Integer value, String name) {
        Integer v = require(value, name);
        if (v < 0) {
            throw BizException.badRequest(name + "不能为负数");
        }
        return v;
    }

    private static Boolean bool(Boolean value) {
        return value != null && value;
    }

    /**
     * 表里没有策略行时的兜底默认值。
     *
     * <p>⚠️ 必须与 DDL 的 {@code DEFAULT}（{@code 01_schema_admin.sql}）及
     * 初始化数据（{@code 02_data_admin.sql} 的 INSERT）**三处保持一致**。
     * 不一致会造成很难查的偏差：表里看着是宽松策略，缺行时却按严格的校验。
     *
     * <p>2026-10-12 按需求方要求放宽复杂度：只保留长度 ≥ 6，
     * 不再要求大小写/数字/符号；锁定与历史密码保留（防暴力破解，与密码复杂度无关）。
     */
    private static PasswordPolicyDto defaultPolicy() {
        return new PasswordPolicyDto(6, false, false, false, false, 5, 30, 90, true, 5);
    }

    private static PasswordPolicyDto toDto(AdminPasswordPolicyEntity e) {
        return new PasswordPolicyDto(
                e.getMinLength(),
                Boolean.TRUE.equals(e.getRequireUpper()),
                Boolean.TRUE.equals(e.getRequireLower()),
                Boolean.TRUE.equals(e.getRequireDigit()),
                Boolean.TRUE.equals(e.getRequireSymbol()),
                e.getMaxFailedAttempts(),
                e.getLockMinutes(),
                e.getExpireDays(),
                Boolean.TRUE.equals(e.getForceChangeOnFirstLogin()),
                e.getHistorySize());
    }
}
