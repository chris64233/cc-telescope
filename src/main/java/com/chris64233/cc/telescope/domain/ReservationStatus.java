package com.chris64233.cc.telescope.domain;

public enum ReservationStatus {
    ACTIVE,
    CANCELLED,
    /** 被机会观测抢占，等待在抢占提案有效期内重新安排 */
    PENDING_RESCHEDULE,
    /** 已重排到新预订，原预订不再占用配额与时段 */
    RESCHEDULED,
    /** 被天气关闭事件阻断：不占用日程，原占用分钟冻结为可恢复资格，保留原优先级 */
    WEATHER_BLOCKED,
    /** 可恢复分钟已全部用于恢复排期，原预订终态 */
    RECOVERED
}
