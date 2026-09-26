package com.chris64233.cc.telescope.domain;

public enum ReservationStatus {
    ACTIVE,
    CANCELLED,
    /** 被机会观测抢占，等待在抢占提案有效期内重新安排 */
    PENDING_RESCHEDULE,
    /** 已重排到新预订，原预订不再占用配额与时段 */
    RESCHEDULED
}
