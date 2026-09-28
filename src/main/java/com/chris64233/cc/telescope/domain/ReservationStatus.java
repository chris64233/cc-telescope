package com.chris64233.cc.telescope.domain;

public enum ReservationStatus {
    ACTIVE,
    CANCELLED,
    /** 被机会观测抢占，等待在抢占提案有效期内重新安排 */
    PENDING_RESCHEDULE,
    /** 已重排到新预订，原预订不再占用配额与时段 */
    RESCHEDULED,
    /** 因天气关闭而被中断的未开始观测：已释放时段与配额，等待天气恢复排期 */
    WEATHER_CANCELLED,
    /** 天气恢复排期成功后原预订的终态（配额已用于新预订） */
    WEATHER_RECOVERED
}
