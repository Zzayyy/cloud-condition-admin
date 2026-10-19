package com.qianlong.cloud.admin.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.dto.AccountDtos.AccountListMeta;
import com.qianlong.cloud.admin.dto.AccountDtos.AccountQuery;
import com.qianlong.cloud.admin.dto.AccountDtos.AccountRow;
import com.qianlong.cloud.admin.dto.AccountDtos.RedisAccountView;
import com.qianlong.cloud.admin.dto.AccountDtos.ShareholderDto;
import com.qianlong.cloud.admin.dto.AccountDtos.TraderServerDto;
import com.qianlong.cloud.admin.entity.UserAccountEntity;
import com.qianlong.cloud.admin.mapper.UserAccountMapper;
import com.qianlong.cloud.admin.service.RedisAccountRepository.RedisAccountValue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户账号：把 MySQL {@code T_User} 与 Redis {@code acc-*} 合并成前端要的行。
 *
 * <h2>为什么合并必须放后端</h2>
 * 前端页面要在一张表里同时看到「库里有这个人」与「Redis 里这个人是活的」，
 * 而且筛选条件（在线 / 已签署 / 渠道）横跨两边数据源。
 * 若拆成两个接口让前端拼，会出现：分页在两边各分一遍、
 * 跨源筛选无法下推（只能取回全量再在浏览器里筛）。
 * 所以按前端 README 的约定：合并由后端做，前端只调一个接口。
 *
 * <h2>合并口径（三条，都会影响页面读数）</h2>
 * <ol>
 *   <li><b>以两侧账号的并集为准</b>：只在 MySQL 有（Redis 对象已过期/未分配）、
 *       只在 Redis 有（库里还没落数据）都要展示 —— 这两种差异本身就是要运维关注的信息。</li>
 *   <li><b>两边都有的字段以 Redis 为准</b>：Redis 是实时态，
 *       MySQL 那行可能很久没更新（现场 T_User 的 LoginTime 停在 2026-09-15）。</li>
 *   <li><b>{@code hasRedis} 明确标出</b>「这个账号在 Redis 侧没有对象」，
 *       而不是把 Redis 字段静默填 null —— 否则「没有实时态」和「实时态是空值」
 *       在页面上长得一模一样，运维没法区分。</li>
 * </ol>
 *
 * <h2>性能与扩展性</h2>
 * 现场 {@code T_User} 只有 1 行、Redis acc-* 共 14 个，所以两边全量取回后
 * 在内存里归并、筛选、分页是合适的（一次 SCAN + 一次全表 SELECT，毫秒级）。
 * ⚠️ 但 {@code T_User} 的 AUTO_INCREMENT 已到 160707，说明历史上写过多行；
 * 若将来真涨到几十万行，**必须改成下推式**：
 * 先在 MySQL 按条件取候选、Redis 侧按前缀筛，再归并，
 * 不能继续全量读到内存里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    /** Redis 键形如 acc-<账号>_<账号类型>_<渠道> */
    private static final String ACC_PREFIX = "acc-";

    private final UserAccountMapper userAccountMapper;
    private final RedisAccountRepository redisRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 合并结果 + 元信息。
     *
     * @param rows  当前页的行
     * @param total **筛选后**的总条数（不是两侧原始数量）——
     *              前端表格的 itemCount 用它，若用原始数量会出现
     *              「共 14 条但筛选后只有 1 条，翻页翻出空白」
     * @param meta  数据源元信息（Redis 可达性），页面据此判断 hasRedis 是否可信
     */
    public record MergedAccounts(List<AccountRow> rows, long total, AccountListMeta meta) {
    }

    /**
     * 查用户账号（合并 + 筛选 + 分页 + 排序）。
     */
    public MergedAccounts listAccounts(AccountQuery query) {
        // 合并 + 筛选走共用实现，保证与 stats() 的口径完全一致。
        // 顺带把 meta 一起带回来，避免这里再查一次 Redis（每次都是真实往返）。
        AccountStatsSource src = mergedFiltered(query);
        List<AccountRow> filtered = src.rows();

        // ---- 排序 ----
        sort(filtered, query.getSortBy(), query.getSortOrder());

        // ---- 分页 ----
        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        int from = Math.min((page - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        List<AccountRow> pageRows = filtered.subList(from, to);

        long unavailable = redisRepository.unavailableInstances().size();
        AccountListMeta meta = new AccountListMeta(
                redisRepository.availableInstanceNames(),
                List.copyOf(redisRepository.unavailableInstances().keySet()),
                src.mysqlRows(),
                src.redisKeys());

        if (unavailable > 0) {
            log.warn("有 {} 个 Redis 实例不可用，本次账号列表的 hasRedis 可能偏低", unavailable);
        }
        return new MergedAccounts(List.copyOf(pageRows), filtered.size(), meta);
    }

    /** 单个账号详情（合并行） */
    public AccountRow getAccount(String account) {
        UserAccountEntity mysql = userAccountMapper.selectByAccount(account);
        RedisAccountValue redis = redisRepository.findOne(account);
        if (mysql == null && redis == null) {
            return null;
        }
        if (redis == null) {
            return fromMysql(mysql);
        }
        AccountRow redisRow = fromRedis(redis);
        return mysql == null ? redisRow : mergePreferRedis(fromMysql(mysql), redisRow);
    }

    /**
     * 账号汇总（**全量口径**，卡片用）。
     *
     * <p>口径说明：账号列表本身就是「MySQL 与 Redis 两侧合并」的结果
     * （见 {@link #listAccounts}），所以这里的「全量」= 合并并集经筛选后的全部行，
     * 与列表的 {@code total} 同源 —— 不是全库表行数，那两边的含义不同。
     *
     * <p>原先前端是对当前页做 filter：「已签署」「在线」这类数字翻页就变。
     */
    public AccountStats stats(AccountQuery query) {
        AccountStatsSource src = mergedFiltered(query);
        List<AccountRow> all = src.rows();
        long signed = all.stream().filter(AccountRow::signed).count();
        long online = all.stream().filter(r -> r.onlineflag() != null && r.onlineflag() == 1).count();
        // 只有 Redis、MySQL 里没有的（现场很常见）
        long redisOnly = all.stream().filter(r -> r.hasRedis() && r.userId() == null).count();
        // 「签署与股东号」页用：至少一个股东号的账号数、股东号总数
        long withShareholders = all.stream()
                .filter(r -> r.shareHolders() != null && !r.shareHolders().isEmpty()).count();
        long totalShares = all.stream()
                .filter(r -> r.shareHolders() != null)
                .mapToLong(r -> r.shareHolders().size()).sum();

        /*
         * 签署状态 × 股东号完备度的交叉计数（「签署与股东号」页的堆叠柱状图用）。
         *
         * 为什么值得单算：卡片只给「已签署 13 / 有股东号 12」这类总数，
         * 看不出两者的**交叉**。而真正要跟进的是这两类：
         *   - 已签署但**没有**股东号 → 协议签了却配不了单，最该处理；
         *   - 未签署但有股东号 → 号配了、协议没签，也下不了单。
         * 只给总数的话，这两类会被「13 和 12 差不多」掩盖掉。
         */
        long signedWithShares = all.stream()
                .filter(r -> r.signed() && hasShares(r)).count();
        long signedNoShares = all.stream()
                .filter(r -> r.signed() && !hasShares(r)).count();
        long unsignedWithShares = all.stream()
                .filter(r -> !r.signed() && hasShares(r)).count();
        long unsignedNoShares = all.stream()
                .filter(r -> !r.signed() && !hasShares(r)).count();

        /*
         * 各市场的股东号数量（全量口径）。
         * 前端原来按当前页 `rows` 数，翻页就变 —— 与前面修掉的「本页口径」是同一类问题。
         */
        Map<Integer, Long> marketCounts = new java.util.TreeMap<>();
        for (AccountRow r : all) {
            if (r.shareHolders() == null) {
                continue;
            }
            for (ShareholderDto sh : r.shareHolders()) {
                if (sh.ExchangeNum() != null) {
                    marketCounts.merge(sh.ExchangeNum(), 1L, Long::sum);
                }
            }
        }

        return new AccountStats(all.size(), signed, online, redisOnly, withShareholders, totalShares,
                signedWithShares, signedNoShares, unsignedWithShares, unsignedNoShares,
                marketCounts);
    }

    /** 该账号是否至少有一个股东号（null 与空数组都算「没有」） */
    private static boolean hasShares(AccountRow r) {
        return r.shareHolders() != null && !r.shareHolders().isEmpty();
    }

    /**
     * 账号汇总结果（全量口径）。
     *
     * <p>后三组字段是「签署与股东号」页专用的交叉统计，其它页忽略即可。
     */
    public record AccountStats(
            long totalCount,
            long signedCount,
            long onlineCount,
            long redisOnlyCount,
            long withShareholdersCount,
            long totalShares,
            /** 已签署 且 有股东号（正常） */
            long signedWithShares,
            /** 已签署 但 无股东号（协议签了却配不了单，需跟进） */
            long signedNoShares,
            /** 未签署 但 有股东号（号配了、协议没签） */
            long unsignedWithShares,
            /** 未签署 且 无股东号（都缺） */
            long unsignedNoShares,
            /** 各市场的股东号数量：key = ExchangeNum（1 上海 / 2 深圳） */
            Map<Integer, Long> marketShareCounts) {
    }

    /**
     * 「合并 + 筛选」的结果，附带 meta 需要的两个计数。
     *
     * <p>把计数一起返回，是为了让 {@link #listAccounts} 不必为了填 meta
     * 再查一次 MySQL 与 Redis —— 那两次都是真实往返，白花的。
     */
    private record AccountStatsSource(List<AccountRow> rows, int mysqlRows, int redisKeys) {
    }

    /**
     * 把「合并 + 筛选」抽出来，供 {@link #listAccounts} 与 {@link #stats} 共用。
     *
     * <p>抽出来是为了不让两处走偏：汇总若自己再写一遍合并与筛选，
     * 迟早出现「列表 14 条、汇总 15 条」这种对不上的情况。
     */
    private AccountStatsSource mergedFiltered(AccountQuery query) {
        Map<String, RedisAccountValue> redisMap = redisRepository.findAll();
        List<UserAccountEntity> mysqlRows = userAccountMapper.selectAllForMerge();

        Map<String, AccountRow> merged = new LinkedHashMap<>();
        for (UserAccountEntity row : mysqlRows) {
            String account = row.getAccount();
            if (!StringUtils.hasText(account)) {
                continue;
            }
            merged.put(account, fromMysql(row));
        }
        for (Map.Entry<String, RedisAccountValue> entry : redisMap.entrySet()) {
            String account = accountFromKey(entry.getKey());
            if (!StringUtils.hasText(account)) {
                continue;
            }
            AccountRow redisRow = fromRedis(entry.getValue());
            AccountRow existing = merged.get(account);
            merged.put(account, existing == null ? redisRow : mergePreferRedis(existing, redisRow));
        }

        List<AccountRow> filtered = new ArrayList<>();
        for (AccountRow row : merged.values()) {
            if (matches(row, query)) {
                filtered.add(row);
            }
        }
        return new AccountStatsSource(filtered, mysqlRows.size(), redisMap.size());
    }

    /** Redis 侧原始内容（页面「Redis 实时态」tab 要展示原始 JSON） */
    public RedisAccountView getRedisAccount(String account) {
        RedisAccountValue value = redisRepository.findOne(account);
        return value == null ? null : toRedisView(value);
    }

    /**
     * 全部 {@code acc-*} 实时对象（**不合并 MySQL**，就是 Redis 侧的原样）。
     *
     * <p>委托服务器页的「账号分配关系」用它，而不是自己再解析一遍 {@code acc-*}：
     * 前端那两个页面用的是**同一个类型** {@code RedisAccount}，若各自映射一套，
     * 同一个账号在账号页与委托服务器页迟早会出现字段口径不一致
     * （例如 {@code syncflag} 一边是数字、一边是布尔）。
     * 所以映射只有这里一份，谁要用谁来取。
     *
     * <p>顺序沿用 {@code scanJson} 的实例配置顺序 + 「键名去重保留第一份」口径
     * （现场 {@code acc-1000006_1_6} 在 136 db1 与 137 db1 都有）。
     */
    public List<RedisAccountView> listRedisAccounts() {
        List<RedisAccountView> out = new ArrayList<>();
        redisRepository.findAll().values().forEach(value -> out.add(toRedisView(value)));
        return out;
    }

    public AccountListMeta meta() {
        return new AccountListMeta(
                redisRepository.availableInstanceNames(),
                List.copyOf(redisRepository.unavailableInstances().keySet()),
                0,
                0);
    }

    /**
     * Redis 实例连接状况（供排查）。
     *
     * <p>页面上的 {@code hasRedis} 依赖 Redis 可达性。若某个实例连不上，
     * hasRedis 会偏低 —— 需要有个地方能区分
     * 「账号真的没有实时对象」与「我们没连上 Redis」。
     */
    public record RedisInstanceStatus(List<String> available, List<String> unavailable) {
    }

    public RedisInstanceStatus redisInstanceStatus() {
        return new RedisInstanceStatus(
                redisRepository.availableInstanceNames(),
                List.copyOf(redisRepository.unavailableInstances().keySet()));
    }

    /* ================================ 映射 ================================ */

    private AccountRow fromMysql(UserAccountEntity row) {
        return new AccountRow(
                row.getAccount(),
                row.getUserAccount(),
                row.getName(),
                row.getAccType(),
                // 列注释写的是「0 股票/1 期权/2 期货」，但实际值 = 6（期权），
                // 且账号串是 FAccount_AccountType_AccAtt —— 按 AccAtt 语义直接用，
                // 不做任何「纠正」，否则会和账号串里的渠道后缀对不上。
                row.getMainType(),
                row.getTradeAccount(),
                row.getBranchNo(),
                DateTimes.format(row.getRegTime()),
                DateTimes.format(row.getLoginTime()),
                row.getLoginIP(),
                row.getLoginMAC(),
                row.getLoginOS(),
                parseShareholders(row.getShareHolders()),
                // RegProtocol 是 binary(1)，现场值为 0x31（字符 '1'）表示已签署
                isSigned(row.getRegProtocol()),
                false,
                null,
                null,
                null,
                null,
                row.getId(),
                null);
    }

    private AccountRow fromRedis(RedisAccountValue value) {
        JsonNode n = value.json();
        return new AccountRow(
                text(n, "account"),
                text(n, "useraccount"),
                text(n, "name"),
                integer(n, "accounttype"),
                integer(n, "maintype"),
                text(n, "tradeAccount"),
                text(n, "branchNo"),
                text(n, "regtime"),
                text(n, "logintime"),
                text(n, "loginIP"),
                text(n, "loginMAC"),
                text(n, "loginOS"),
                parseShareholders(text(n, "shareHolders")),
                // Redis 侧的 regprotocal：1 / true 都表示已签署
                isSignedRedis(n.get("regprotocal")),
                true,
                integer(n, "onlineflag"),
                raw(n, "syncflag"),
                integer(n, "strategyserverid"),
                traderServer(n.get("traderserver")),
                null,
                value.instanceDisplay());
    }

    /**
     * 合并一行：两边都有的字段**以 Redis 为准**（实时态），
     * Redis 缺的字段回落到 MySQL，并保留 MySQL 的 userId。
     */
    private AccountRow mergePreferRedis(AccountRow mysql, AccountRow redis) {
        return new AccountRow(
                firstNonBlank(redis.account(), mysql.account()),
                firstNonBlank(redis.useraccount(), mysql.useraccount()),
                firstNonBlank(redis.name(), mysql.name()),
                redis.accounttype() != null ? redis.accounttype() : mysql.accounttype(),
                redis.accatt() != null ? redis.accatt() : mysql.accatt(),
                firstNonBlank(redis.tradeAccount(), mysql.tradeAccount()),
                firstNonBlank(redis.branchNo(), mysql.branchNo()),
                firstNonBlank(redis.regtime(), mysql.regtime()),
                firstNonBlank(redis.logintime(), mysql.logintime()),
                firstNonBlank(redis.loginIP(), mysql.loginIP()),
                firstNonBlank(redis.loginMAC(), mysql.loginMAC()),
                firstNonBlank(redis.loginOS(), mysql.loginOS()),
                redis.shareHolders().isEmpty() ? mysql.shareHolders() : redis.shareHolders(),
                redis.signed() || mysql.signed(),
                true,
                redis.onlineflag(),
                redis.syncflag(),
                redis.strategyserverid(),
                redis.traderserver(),
                mysql.userId(),
                redis.redisInstance());
    }

    private RedisAccountView toRedisView(RedisAccountValue value) {
        JsonNode n = value.json();
        return new RedisAccountView(
                integer(n, "id"),
                text(n, "useraccount"),
                text(n, "account"),
                integer(n, "accounttype"),
                integer(n, "maintype"),
                text(n, "regtime"),
                text(n, "logintime"),
                text(n, "loginIP"),
                text(n, "loginMAC"),
                text(n, "loginOS"),
                text(n, "name"),
                text(n, "tradeAccount"),
                text(n, "branchNo"),
                text(n, "shareHolders"),
                raw(n, "regprotocal"),
                integer(n, "strategyserverid"),
                integer(n, "onlineflag"),
                raw(n, "syncflag"),
                traderServer(n.get("traderserver")),
                value.instanceDisplay(),
                n.toPrettyString());
    }

    /* ================================ 判定 ================================ */

    /** 筛选：与前端 mock 的口径保持一致（AND 关系） */
    private boolean matches(AccountRow row, AccountQuery q) {
        String keyword = trimToNull(q.getKeyword());
        if (keyword != null) {
            boolean hit = contains(row.account(), keyword)
                    || contains(row.useraccount(), keyword)
                    || contains(row.name(), keyword)
                    || contains(row.tradeAccount(), keyword);
            if (!hit) {
                return false;
            }
        }
        if (q.getAccounttype() != null && !Objects.equals(row.accounttype(), q.getAccounttype())) {
            return false;
        }
        if (q.getAccatt() != null && !Objects.equals(row.accatt(), q.getAccatt())) {
            return false;
        }
        if (q.getSigned() != null && row.signed() != q.getSigned()) {
            return false;
        }
        if (q.getOnline() != null) {
            // onlineflag = 1 视为在线；没有 Redis 对象的一律不算在线
            boolean online = row.hasRedis() && row.onlineflag() != null && row.onlineflag() == 1;
            if (online != q.getOnline()) {
                return false;
            }
        }
        String branch = trimToNull(q.getBranchNo());
        if (branch != null && !contains(row.branchNo(), branch)) {
            return false;
        }
        return true;
    }

    /** 排序白名单：未知名直接报错，避免「传了没生效」的静默行为 */
    private void sort(List<AccountRow> rows, String sortBy, String sortOrder) {
        boolean desc = !"asc".equalsIgnoreCase(sortOrder);
        String field = trimToNull(sortBy);
        Comparator<AccountRow> comparator;
        if (field == null || "account".equals(field)) {
            comparator = Comparator.comparing(r -> nullSafe(r.account()));
        } else if ("regtime".equals(field)) {
            comparator = Comparator.comparing(r -> nullSafe(r.regtime()));
        } else if ("logintime".equals(field)) {
            comparator = Comparator.comparing(r -> nullSafe(r.logintime()));
        } else {
            throw com.qianlong.cloud.admin.common.BizException.badRequest(
                    "不支持按 " + field + " 排序");
        }
        if (desc) {
            comparator = comparator.reversed();
        }
        rows.sort(comparator);
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    /* ================================ 小工具 ================================ */

    /** acc-300130000461_1_6 -> 300130000461_1_6 */
    private static String accountFromKey(String key) {
        if (key == null) {
            return null;
        }
        return key.startsWith(ACC_PREFIX) ? key.substring(ACC_PREFIX.length()) : key;
    }

    private static boolean isSigned(byte[] regProtocol) {
        if (regProtocol == null || regProtocol.length == 0) {
            return false;
        }
        // 现场值为 0x31（字符 '1'）= 已签署；0x30（'0'）或 0x00 = 未签署
        byte b = regProtocol[0];
        return b == (byte) '1' || b == 1;
    }

    private static boolean isSignedRedis(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.intValue() == 1;
        }
        String s = node.asText("");
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private List<ShareholderDto> parseShareholders(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<ShareholderDto> out = new ArrayList<>();
            for (JsonNode item : node) {
                out.add(new ShareholderDto(
                        item.path("SAccount").asText(null),
                        item.hasNonNull("ExchangeNum") ? item.get("ExchangeNum").asInt() : null));
            }
            return out;
        } catch (Exception ex) {
            // 股东号是 JSON 字符串列，格式坏了不要抛 500 —— 页面显示为空即可，
            // 但要在日志里留痕，否则「页面没有股东号」查不出原因
            log.warn("ShareHolders 解析失败，按空处理：{}", ex.getMessage());
            return List.of();
        }
    }

    private static TraderServerDto traderServer(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return new TraderServerDto(
                node.hasNonNull("id") ? node.get("id").asInt() : null,
                node.path("ip").asText(null),
                node.path("lastactivetime").asText(null),
                node.hasNonNull("lastActiveTimeLong") ? node.get("lastActiveTimeLong").asLong() : null,
                node.hasNonNull("enable") ? node.get("enable").asBoolean() : null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        // 现场有 loginIP="" 这种空串，统一按「没有」处理，避免页面显示空白格
        return s == null || s.isEmpty() ? null : s;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.asInt();
    }

    private static Object raw(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.numberValue();
        }
        return v.asText();
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.contains(needle);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
