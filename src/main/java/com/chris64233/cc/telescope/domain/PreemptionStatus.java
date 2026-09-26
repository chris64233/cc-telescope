package com.chris64233.cc.telescope.domain;

public enum PreemptionStatus {
    /** 抢占已确认：被影响预订已退出日程并归还配额，目标机会新预订已建立。 */
    CONFIRMED,
    /** 抢占被拒绝（存在不可抢占任务、仪器不兼容或配额不足等），原日程完全不变。 */
    REJECTED
}
