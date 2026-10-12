package com.qianlong.cloud.admin.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.common.ErrorCode;
import com.qianlong.cloud.admin.common.PageResult;
import com.qianlong.cloud.admin.dto.SystemDtos.AdminUserPayload;
import com.qianlong.cloud.admin.dto.SystemDtos.AdminUserView;
import com.qianlong.cloud.admin.dto.SystemDtos.PasswordPolicyDto;
import com.qianlong.cloud.admin.dto.SystemDtos.UserQuery;
import com.qianlong.cloud.admin.entity.AdminUserEntity;
import com.qianlong.cloud.admin.entity.AdminUserRoleEntity;
import com.qianlong.cloud.admin.mapper.AdminRoleMapper;
import com.qianlong.cloud.admin.mapper.AdminRolePermissionMapper;
import com.qianlong.cloud.admin.mapper.AdminUserMapper;
import com.qianlong.cloud.admin.mapper.AdminUserRoleMapper;
import com.qianlong.cloud.admin.dto.AdminPrincipal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 后台管理账号：查询、增删改、启停、解锁、重置密码，以及登录时的身份与锁定判定。
 *
 * <p>三条不能含糊的约束（与前端页面注释一致）：
 * <ol>
 *   <li><b>密码只写不读</b>：出参 DTO 里根本没有密码字段；</li>
 *   <li><b>停用 / 改权限要让已签发的 token 立即失效</b> —— 只改库字段等于没停；</li>
 *   <li><b>内置账号 admin 不允许删除</b>：删掉超管，后台就再也进不去了。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final AdminUserMapper userMapper;
    private final AdminUserRoleMapper userRoleMapper;
    private final AdminRoleMapper roleMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final PermissionCatalog catalog;
    private final PasswordPolicyService policyService;
    private final PasswordEncoderHolder encoderHolder;
    private final SessionKicker sessionKicker;

    /* ============================== 查询 ============================== */

    /** 分页查询管理账号（关键字 / 角色 / 状态三个筛选，与前端一致） */
    public PageResult<AdminUserView> listUsers(UserQuery query) {
        LambdaQueryWrapper<AdminUserEntity> wrapper = Wrappers.lambdaQuery();

        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            // 前端提示是「搜索登录名 / 显示名 / 备注」，三处都要覆盖，否则搜不到还以为没数据
            wrapper.and(w -> w.like(AdminUserEntity::getUsername, keyword)
                    .or().like(AdminUserEntity::getDisplayName, keyword)
                    .or().like(AdminUserEntity::getRemark, keyword));
        }

        String status = trimToNull(query.getStatus());
        if (status != null) {
            switch (status) {
                case "enabled" -> wrapper.eq(AdminUserEntity::getEnabled, true);
                case "disabled" -> wrapper.eq(AdminUserEntity::getEnabled, false);
                case "locked" -> wrapper.eq(AdminUserEntity::getLocked, true);
                default -> throw BizException.badRequest(
                        "status 取值只能是 enabled / disabled / locked，实际为：" + status);
            }
        }

        Integer roleId = query.getRoleId();
        if (roleId != null) {
            // 按角色过滤必须走关系表子查询，否则「该角色是否有账号」无法在单表上表达
            List<Integer> userIds = userIdsOfRole(roleId);
            if (userIds.isEmpty()) {
                return PageResult.empty(query.pageOrDefault(), query.pageSizeOrDefault());
            }
            wrapper.in(AdminUserEntity::getId, userIds);
        }

        applySort(wrapper, query.getSortBy(), query.getSortOrder());

        Page<AdminUserEntity> page = userMapper.selectPage(
                Page.of(query.pageOrDefault(), query.pageSizeOrDefault()), wrapper);

        List<Integer> ids = page.getRecords().stream().map(AdminUserEntity::getId).toList();
        Map<Integer, List<Integer>> roleMap = roleIdsOf(ids);

        List<AdminUserView> list = page.getRecords().stream()
                .map(entity -> toView(entity, roleMap.getOrDefault(entity.getId(), List.of())))
                .toList();

        return PageResult.of(list, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 排序白名单。
     *
     * <p><b>为什么必须白名单：</b>{@code sortBy} 直接来自前端查询串，
     * 若拼进 SQL 就是注入面；而且前端传的字段名与数据库列名相同，
     * 一旦某个字段被改名，未白名单会**静默按默认排序**返回，
     * 用户以为排序生效了。所以未知字段直接报错。
     */
    private void applySort(LambdaQueryWrapper<AdminUserEntity> wrapper, String sortBy, String sortOrder) {
        boolean desc = !"asc".equalsIgnoreCase(sortOrder);
        String field = trimToNull(sortBy);
        if (field == null) {
            // 默认按 id 倒序：新建的账号排在最前面，符合「刚建完就想看到它」的直觉
            wrapper.orderByDesc(AdminUserEntity::getId);
            return;
        }
        switch (field) {
            case "username" -> wrapper.orderBy(true, !desc, AdminUserEntity::getUsername);
            case "lastLoginTime" -> wrapper.orderBy(true, !desc, AdminUserEntity::getLastLoginTime);
            case "createdAt" -> wrapper.orderBy(true, !desc, AdminUserEntity::getCreatedAt);
            case "updatedAt" -> wrapper.orderBy(true, !desc, AdminUserEntity::getUpdatedAt);
            default -> throw BizException.badRequest("不支持按 " + field + " 排序");
        }
        // 加一个稳定次序，避免同值行在翻页时来回跳动（会让用户看到重复/漏行）
        wrapper.orderByDesc(AdminUserEntity::getId);
    }

    public AdminUserView getUser(Integer id) {
        AdminUserEntity entity = requireUser(id);
        return toView(entity, userRoleMapper.selectRoleIdsByUserId(id));
    }

    /** 权限键集合（超管返回单元素 {@code *}） */
    public List<String> permissionsOf(Integer userId) {
        List<String> keys = rolePermissionMapper.selectPermissionKeysByUserId(userId);
        if (keys.contains(PermissionCatalog.WILDCARD)) {
            // 通配符不展开成具体键：菜单新增后自动覆盖，前端 hasPermission 也认它
            return List.of(PermissionCatalog.WILDCARD);
        }
        // 去重并保持稳定顺序（同一份账号每次返回一致，便于比对）
        return List.copyOf(new LinkedHashSet<>(keys));
    }

    public List<String> roleCodesOf(Integer userId) {
        return rolePermissionMapper.selectRoleCodesByUserId(userId);
    }

    /** 组装当前登录人身份；账号不存在时返回 {@code null} */
    public AdminPrincipal principalOf(Integer userId) {
        AdminUserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            return null;
        }
        return new AdminPrincipal(
                entity.getId(),
                entity.getUsername(),
                entity.getDisplayName(),
                roleCodesOf(userId),
                permissionsOf(userId),
                Boolean.TRUE.equals(entity.getMustChangePassword()));
    }

    /* ============================== 新建 / 编辑 ============================== */

    /**
     * 新建账号。
     *
     * <p>新建时密码必填，并且要按当前策略校验（前端也校验，但那只是体验）。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminUserView createUser(AdminUserPayload payload) {
        String username = payload.username().trim();
        if (existsUsername(username, null)) {
            throw BizException.conflict("登录名 " + username + " 已存在");
        }
        if (!StringUtils.hasText(payload.password())) {
            throw BizException.badRequest("新建账号必须设置初始密码");
        }
        policyService.validatePassword(payload.password(), username, null);
        validateRoleIds(payload.roleIds());

        AdminUserEntity entity = new AdminUserEntity();
        entity.setUsername(username);
        entity.setDisplayName(trimToNull(payload.displayName()));
        entity.setPasswordHash(encoderHolder.encode(payload.password()));
        entity.setEnabled(payload.enabled() == null || payload.enabled());
        entity.setLocked(false);
        entity.setFailedAttempts(0);
        // 新账号是否需改密按策略来（forceChangeOnFirstLogin），不拍脑袋
        PasswordPolicyDto policy = policyService.getPolicy();
        entity.setMustChangePassword(Boolean.TRUE.equals(policy.forceChangeOnFirstLogin()));
        entity.setLoginCount(0);
        entity.setBuiltin(false);
        entity.setRemark(trimToNull(payload.remark()));
        LocalDateTime now = LocalDateTime.now();
        entity.setPasswordUpdateTime(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        userMapper.insert(entity);

        replaceUserRoles(entity.getId(), payload.roleIds());
        return getUser(entity.getId());
    }

    /**
     * 编辑账号。
     *
     * <p>刻意<b>不改密码</b>（前端编辑表单里也没有密码字段）：改密必须走「重置密码」，
     * 后端要单独写审计。登录名同样不可修改 —— 审计靠它认人。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminUserView updateUser(Integer id, AdminUserPayload payload) {
        AdminUserEntity entity = requireUser(id);
        String username = payload.username().trim();
        if (!entity.getUsername().equals(username)) {
            throw BizException.badRequest(
                    "登录名不可修改：审计记录靠登录名认人，改名会让历史记录对不上（原登录名 "
                            + entity.getUsername() + "）");
        }
        validateRoleIds(payload.roleIds());

        entity.setDisplayName(trimToNull(payload.displayName()));
        entity.setEnabled(payload.enabled() == null || payload.enabled());
        entity.setRemark(trimToNull(payload.remark()));
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(entity);

        boolean rolesChanged = replaceUserRoles(id, payload.roleIds());

        // 停用要让已签发 token 失效，只改库字段等于没停（前端页面上也写明了这一点）
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            sessionKicker.kick(id);
        } else if (rolesChanged) {
            /*
             * 改角色同样要踢：权限是每次校验时查库的，理论上立即生效，
             * 但用户当前打开的页面仍持有旧权限渲染出的按钮，且前端在
             * `/system/auth/profile` 里缓存了 permissions。
             * 踢下线让他重新登录、重新取一次权限，避免「改动生效了但界面还是旧的」这种
             * 说不清楚的中间态。
             */
            sessionKicker.kick(id);
        }
        return getUser(id);
    }

    /** 启用 / 停用。停用立即踢掉该账号全部会话。 */
    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(Integer id, boolean enabled, Integer operatorUserId) {
        AdminUserEntity entity = requireUser(id);
        if (!enabled) {
            if (Boolean.TRUE.equals(entity.getBuiltin())) {
                throw BizException.conflict(
                        "内置账号 " + entity.getUsername() + " 不允许停用：停掉它后台就再也进不去了");
            }
            if (id.equals(operatorUserId)) {
                // 允许停自己是有意义的（交接场景），但极易误操作，明确拦下并说明
                throw BizException.badRequest("不能停用当前登录的账号，请用其他管理员账号操作");
            }
            ensureNotLastWildcardUser(id, "停用");
        }
        entity.setEnabled(enabled);
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(entity);

        if (!enabled) {
            sessionKicker.kick(id);
        }
    }

    /** 解除登录失败锁定 */
    @Transactional(rollbackFor = Exception.class)
    public void unlock(Integer id) {
        AdminUserEntity entity = requireUser(id);
        entity.setLocked(false);
        entity.setFailedAttempts(0);
        entity.setLockTime(null);
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(entity);
    }

    /**
     * 重置密码。
     *
     * <p>明文密码只在这一个请求里出现，接口不回显；审计只记「谁重置了谁」，不记密码。
     *
     * @param mustChange 是否同时强制该账号下次登录修改
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Integer id, String rawPassword, boolean mustChange) {
        AdminUserEntity entity = requireUser(id);
        policyService.validatePassword(rawPassword, entity.getUsername(), id);

        // 先把当前密码写进历史，这样「改回上一版」会被 historySize 规则拦住
        policyService.preparePasswordChange(id, entity.getPasswordHash());

        String newHash = encoderHolder.encode(rawPassword);
        entity.setPasswordHash(newHash);
        entity.setMustChangePassword(mustChange);
        entity.setPasswordUpdateTime(LocalDateTime.now());
        // 重置密码后应当解锁：管理员重置密码的意图通常就是「让人重新进来」
        entity.setLocked(false);
        entity.setFailedAttempts(0);
        entity.setLockTime(null);
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(entity);

        policyService.recordNewPassword(id, newHash);
        // 密码变了，旧会话必须失效
        sessionKicker.kick(id);
    }

    /** 修改自己的密码 */
    @Transactional(rollbackFor = Exception.class)
    public void changeOwnPassword(Integer userId, String oldPassword, String newPassword) {
        AdminUserEntity entity = requireUser(userId);
        if (!encoderHolder.matches(oldPassword, entity.getPasswordHash())) {
            throw BizException.badRequest("原密码不正确");
        }
        if (oldPassword.equals(newPassword)) {
            throw BizException.badRequest("新密码不能与原密码相同");
        }
        policyService.validatePassword(newPassword, entity.getUsername(), userId);
        policyService.preparePasswordChange(userId, entity.getPasswordHash());

        String newHash = encoderHolder.encode(newPassword);
        entity.setPasswordHash(newHash);
        entity.setMustChangePassword(false);
        entity.setPasswordUpdateTime(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(entity);
        policyService.recordNewPassword(userId, newHash);

        // 改密成功后让旧 token 失效（前端会重新登录），这是防止「改完密码旧会话还在」的关键
        sessionKicker.kick(userId);
    }

    /** 删除账号 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Integer id, Integer operatorUserId) {
        AdminUserEntity entity = requireUser(id);
        if (Boolean.TRUE.equals(entity.getBuiltin())) {
            throw BizException.conflict(
                    "内置账号 " + entity.getUsername() + " 不允许删除：删掉超管，后台就再也进不去了");
        }
        if (id.equals(operatorUserId)) {
            throw BizException.badRequest("不能删除当前登录的账号");
        }
        ensureNotLastWildcardUser(id, "删除");

        // 审计记录里存的是登录名，账号删了记录仍在 —— 这正是「审计要认人」的意思
        userRoleMapper.delete(Wrappers.<AdminUserRoleEntity>lambdaQuery()
                .eq(AdminUserRoleEntity::getUserId, id));
        userMapper.deleteById(id);
        sessionKicker.kick(id);
    }

    /* ============================== 登录相关 ============================== */

    /**
     * 登录时的账号与状态判定。
     *
     * <p>顺序刻意是「锁定 → 停用 → 密码」：
     * 若先校密码，被锁的管理员会看到「密码错误」，从而以为是密码问题而去反复重试，
     * 实际上他需要的是解锁。前端 mock 里也是这个顺序。
     *
     * <p>「账号不存在」与「密码错误」返回<b>同一个码</b>，避免变成账号枚举接口。
     *
     * @return 通过校验的账号实体
     */
    public AdminUserEntity authenticate(String username, String rawPassword) {
        AdminUserEntity entity = findByUsername(username);

        if (entity == null) {
            // 与密码错误同码同文案 —— 分开返回等于提供账号枚举能力
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "账号或密码错误");
        }

        // 锁定到期自动解锁（策略里的 lockMinutes 必须真的起作用，否则就是个装饰字段）
        autoUnlockIfExpired(entity);

        if (Boolean.TRUE.equals(entity.getLocked())) {
            throw new BizException(ErrorCode.LOCKED,
                    "账号已被锁定（连续失败 " + entity.getFailedAttempts() + " 次），请联系管理员解锁");
        }
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new BizException(ErrorCode.DISABLED, "账号已停用，请联系管理员");
        }
        if (!encoderHolder.matches(rawPassword, entity.getPasswordHash())) {
            throw registerFailure(entity);
        }
        return entity;
    }

    /**
     * 记一次失败：累加计数，达到阈值即锁定。
     *
     * <p>返回要抛出的异常（而不是直接抛），让调用方决定上下文/日志。
     *
     * <p><b>⚠️ 这里刻意不在文案里带「还可尝试 N 次」。</b>
     * 早先的版本会拼上剩余次数，结果「密码错」与「账号不存在」两条分支的 message
     * 不一样（前者带次数、后者不带）—— E2E 测试直接把这个差异抓了出来。
     * 那等于**把登录接口变成了账号枚举探针**：攻击者输一个错误密码，
     * 看有没有「还可尝试 N 次」就知道账号存不存在。前端 README 里
     * 「账号不存在与密码错误必须同码」的用意正在于此，只统一 code 是不够的，
     * 文案也必须逐字一致。所以剩余次数只写进日志，不回给调用方。
     */
    private BizException registerFailure(AdminUserEntity entity) {
        PasswordPolicyDto policy = policyService.getPolicy();
        int max = policy.maxFailedAttempts() == null ? 0 : policy.maxFailedAttempts();
        int attempts = (entity.getFailedAttempts() == null ? 0 : entity.getFailedAttempts()) + 1;

        entity.setFailedAttempts(attempts);
        entity.setUpdatedAt(LocalDateTime.now());

        if (max > 0 && attempts >= max) {
            entity.setLocked(true);
            entity.setLockTime(LocalDateTime.now());
            userMapper.updateById(entity);
            return new BizException(ErrorCode.LOCKED,
                    "账号或密码错误。连续失败 " + attempts + " 次，账号已被锁定 "
                            + policy.lockMinutes() + " 分钟，请联系管理员解锁");
        }

        userMapper.updateById(entity);
        // 剩余次数只进日志（见方法注释：写进响应就是账号枚举漏洞）
        log.info("登录失败：username={} 已连续失败 {}/{} 次",
                entity.getUsername(), attempts, max > 0 ? String.valueOf(max) : "不限");
        // 文案与「账号不存在」分支逐字相同
        return new BizException(ErrorCode.BAD_CREDENTIALS, "账号或密码错误");
    }

    /** 登录成功后的收尾：清零失败计数、累计登录次数、刷新最后登录信息 */
    @Transactional(rollbackFor = Exception.class)
    public void onLoginSuccess(Integer userId, String ip) {
        AdminUserEntity entity = requireUser(userId);
        entity.setFailedAttempts(0);
        entity.setLocked(false);
        entity.setLockTime(null);
        entity.setLastLoginTime(LocalDateTime.now());
        entity.setLastLoginIP(ip);
        entity.setLoginCount((entity.getLoginCount() == null ? 0 : entity.getLoginCount()) + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(entity);
    }

    /** 锁定到期则自动解锁（按策略的 lockMinutes） */
    private void autoUnlockIfExpired(AdminUserEntity entity) {
        if (!Boolean.TRUE.equals(entity.getLocked())) {
            return;
        }
        PasswordPolicyDto policy = policyService.getPolicy();
        int minutes = policy.lockMinutes() == null ? 0 : policy.lockMinutes();
        if (minutes <= 0) {
            // 策略写 0 表示「锁定时长不限」，那就只能人工解锁 —— 不擅自放行
            return;
        }
        LocalDateTime lockTime = entity.getLockTime();
        if (lockTime == null) {
            // 历史数据可能只有 locked 而没有 lockTime：按「未知即仍需人工解锁」处理，
            // 不擅自解锁（宁可让管理员多点一次，也不能自动放开一个状态不明的锁定）
            return;
        }
        if (Duration.between(lockTime, LocalDateTime.now()).toMinutes() >= minutes) {
            entity.setLocked(false);
            entity.setFailedAttempts(0);
            entity.setLockTime(null);
            entity.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(entity);
        }
    }

    public AdminUserEntity findByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return userMapper.selectOne(Wrappers.<AdminUserEntity>lambdaQuery()
                .eq(AdminUserEntity::getUsername, username.trim())
                .last("LIMIT 1"));
    }

    /* ============================== 内部工具 ============================== */

    private AdminUserEntity requireUser(Integer id) {
        AdminUserEntity entity = id == null ? null : userMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("管理账号不存在（id=" + id + "）");
        }
        return entity;
    }

    private boolean existsUsername(String username, Integer excludeId) {
        LambdaQueryWrapper<AdminUserEntity> wrapper = Wrappers.<AdminUserEntity>lambdaQuery()
                .eq(AdminUserEntity::getUsername, username);
        if (excludeId != null) {
            wrapper.ne(AdminUserEntity::getId, excludeId);
        }
        return userMapper.selectCount(wrapper) > 0;
    }

    private void validateRoleIds(List<Integer> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw BizException.badRequest("至少选择一个角色：没有角色的账号登录后什么都看不到");
        }
        List<Integer> distinct = roleIds.stream().distinct().toList();
        long found = roleMapper.selectCount(Wrappers.<com.qianlong.cloud.admin.entity.AdminRoleEntity>lambdaQuery()
                .in(com.qianlong.cloud.admin.entity.AdminRoleEntity::getId, distinct));
        if (found != distinct.size()) {
            throw BizException.badRequest("部分角色不存在，请刷新角色列表后重试");
        }
    }

    /**
     * 替换账号的角色集合。
     *
     * @return 是否发生了变化（用于决定要不要踢会话）
     */
    private boolean replaceUserRoles(Integer userId, List<Integer> roleIds) {
        List<Integer> before = userRoleMapper.selectRoleIdsByUserId(userId);
        Set<Integer> desired = new LinkedHashSet<>(roleIds);
        if (new HashSet<>(before).equals(desired)) {
            return false;
        }
        userRoleMapper.delete(Wrappers.<AdminUserRoleEntity>lambdaQuery()
                .eq(AdminUserRoleEntity::getUserId, userId));
        for (Integer roleId : desired) {
            AdminUserRoleEntity link = new AdminUserRoleEntity();
            link.setUserId(userId);
            link.setRoleId(roleId);
            userRoleMapper.insert(link);
        }
        return true;
    }

    /**
     * 护栏：不能把最后一个启用的超管停用/删除。
     *
     * <p>现场没有别的恢复手段 —— 超管清空 = 后台永久进不去，只能上数据库改。
     */
    private void ensureNotLastWildcardUser(Integer targetUserId, String action) {
        List<String> targetPermissions = permissionsOf(targetUserId);
        if (!targetPermissions.contains(PermissionCatalog.WILDCARD)) {
            return; // 本来就不是超管，不涉及这条护栏
        }
        long remaining = roleMapper.countEnabledWildcardUsers();
        if (remaining <= 1) {
            throw BizException.conflict(
                    "这是当前唯一启用的超级管理员账号，不允许" + action
                            + "：清空超管后后台将无法进入，现场没有别的恢复手段");
        }
    }

    private List<Integer> userIdsOfRole(Integer roleId) {
        return userRoleMapper.selectList(Wrappers.<AdminUserRoleEntity>lambdaQuery()
                        .eq(AdminUserRoleEntity::getRoleId, roleId))
                .stream().map(AdminUserRoleEntity::getUserId).distinct().toList();
    }

    /** 批量取多个账号的角色 id，避免列表页 N+1 */
    private Map<Integer, List<Integer>> roleIdsOf(List<Integer> userIds) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        if (userIds.isEmpty()) {
            return map;
        }
        for (AdminUserRoleMapper.UserRoleRow row : userRoleMapper.selectRoleIdsByUserIds(userIds)) {
            map.computeIfAbsent(row.userId(), k -> new ArrayList<>()).add(row.roleId());
        }
        return map;
    }

    private static AdminUserView toView(AdminUserEntity e, List<Integer> roleIds) {
        return new AdminUserView(
                e.getId(),
                e.getUsername(),
                e.getDisplayName(),
                roleIds == null ? List.of() : List.copyOf(roleIds),
                Boolean.TRUE.equals(e.getEnabled()),
                Boolean.TRUE.equals(e.getLocked()),
                e.getFailedAttempts() == null ? 0 : e.getFailedAttempts(),
                Boolean.TRUE.equals(e.getMustChangePassword()),
                DateTimes.format(e.getLastLoginTime()),
                e.getLastLoginIP(),
                e.getLoginCount() == null ? 0 : e.getLoginCount(),
                DateTimes.format(e.getCreatedAt()),
                DateTimes.format(e.getUpdatedAt()),
                e.getRemark());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 供 RoleService 判断「角色是否还有账号在用」时复用 */
    public long countUsersOfRole(Integer roleId) {
        return roleMapper.countUsersOfRole(roleId);
    }

    /** 稳定排序工具：让集合按 id 升序（导出/详情用） */
    static Comparator<AdminUserEntity> byId() {
        return Comparator.comparing(AdminUserEntity::getId);
    }
}
