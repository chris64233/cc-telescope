package com.chris64233.cc.telescope.domain;

import jakarta.persistence.Embeddable;

/**
 * 一次抢占中某个配额账户的分钟数变化。
 *
 * @param ownerType   NORMAL（普通提案）或 OPPORTUNITY（机会提案）
 * @param ownerCode   提案编号
 * @param deltaMinutes 正数表示归还（剩余配额增加），负数表示消费（剩余配额扣减）
 * @param reason      REFUND_PREEMPTED（抢占归还）/ CONSUME_OPPORTUNITY（机会预订扣减）
 */
@Embeddable
public class QuotaChange {

    private String ownerType;
    private String ownerCode;
    private long deltaMinutes;
    private String reason;

    protected QuotaChange() {
    }

    public QuotaChange(String ownerType, String ownerCode, long deltaMinutes, String reason) {
        this.ownerType = ownerType;
        this.ownerCode = ownerCode;
        this.deltaMinutes = deltaMinutes;
        this.reason = reason;
    }

    public String getOwnerType() {
        return ownerType;
    }

    public String getOwnerCode() {
        return ownerCode;
    }

    public long getDeltaMinutes() {
        return deltaMinutes;
    }

    public String getReason() {
        return reason;
    }
}
