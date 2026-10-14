package com.qianlong.cloud.admin.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 云条件单业务用户（表 {@code T_User}）—— **客户**的账号，不是后台管理员。
 *
 * <p>⚠️ 与 {@link AdminUserEntity}（后台管理账号）是两条完全不同的线：
 * 这里是客户的钱包账号（会挂条件单、跑在策略/委托服务器上），
 * 那边是「谁能登录后台」。混在一起权限就形同虚设了。
 *
 * <p><b>列名是既有表的真实列名，不改动</b>（这张表现场在用，不允许动结构）：
 * 首字母大写的 {@code Account} / {@code AccType} 等是原样保持的，
 * 与 T_Admin* 那批小驼峰新表风格不一致，但**以现场为准**。
 *
 * <p>{@code MeType} 语义见 {@code MainType} 的实地矛盾（README 已记录）：
 * 列注释写「0 股票/1 期权/2 期货」，但实际值 = 6 且账号串形如
 * {@code FAccount_AccountType_AccAtt}，说明它存的是**渠道 AccAtt**。
 * 本模块按实际数据（AccAtt 语义）展示，不按列注释映射。
 */
@Data
@TableName("T_User")
public class UserAccountEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 账号（如 010100011300_7_6） */
    private String Account;

    /** 0 资金账号 / 1 客户号（现场实际值为 1 与 7，按实际数据解释） */
    private Integer AccType;

    /** 列注释写股票/期权/期货，实际存的是渠道 AccAtt（0 股票 / 6 期权） */
    private Integer MainType;

    /**
     * 账号密码（密文）。
     *
     * <p><b>不出任何接口</b>：出参 DTO 里没有这个字段，
     * 与 T_AdminUser.passwordHash 同样处理 —— 用类型杜绝回显。
     */
    private String Passwd;

    private LocalDateTime RegTime;

    private LocalDateTime LoginTime;

    private String LoginIP;

    private String LoginMAC;

    private String LoginOS;

    /** 签署的协议。binary(1) 列，MySQL 驱动给到的是 byte[] */
    private byte[] RegProtocol;

    /** 唯一账号（不含账号类型与渠道后缀） */
    private String UserAccount;

    private String Name;

    private String TradeAccount;

    /** 股东号 JSON 字符串（[{SAccount, ExchangeNum}]） */
    private String ShareHolders;

    private String BranchNo;
}
