package com.chris64233.cc.telescope.domain;

public enum ReservationStatus {
    /** 有效预订，占用望远镜日程。 */
    ACTIVE,
    /** 已取消（用户主动取消），配额已归还。 */
    CANCELLED,
    /** 被目标机会抢占、等待重排，已不占用日程，原配额已归还。 */
    PENDING_REARRANGE,
    /** 被抢占且已完成重排（或最终放弃），终态。 */
    PREEMPTED
}
