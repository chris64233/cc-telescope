package com.chris64233.cc.telescope.domain;

public enum PreemptionStatus {
    /** 抢占已确认：被覆盖预订全部取消（待重排）、配额已归还、机会预订已建立 */
    CONFIRMED,
    /** 抢占被拒绝：存在不可抢占任务、仪器不兼容或机会配额不足，原日程完全不变 */
    REJECTED
}
