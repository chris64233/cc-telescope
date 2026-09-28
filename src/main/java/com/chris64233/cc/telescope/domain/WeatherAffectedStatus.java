package com.chris64233.cc.telescope.domain;

/**
 * 天气关闭受影响记录的生命周期状态。
 */
public enum WeatherAffectedStatus {
    /** 已被天气关闭中断、释放资源，可使用原优先级与可恢复分钟数重新申请时段 */
    AFFECTED,
    /** 可恢复分钟数已全部用于新预订 */
    RECOVERED,
    /** 提案主动放弃（取消待恢复任务），剩余可恢复分钟数清零，不再占资源 */
    ABANDONED,
    /** 天气范围调整后不再受影响，资源已重新扣减、原预订恢复有效 */
    RESTORED
}
