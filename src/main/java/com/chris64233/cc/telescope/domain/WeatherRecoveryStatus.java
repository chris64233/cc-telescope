package com.chris64233.cc.telescope.domain;

/**
 * 单条被天气阻断预订的恢复资格状态。
 */
public enum WeatherRecoveryStatus {
    /** 已阻断：资格可用，尚未消耗任何可恢复分钟 */
    BLOCKED,
    /** 部分恢复：已用部分可恢复分钟建立新预订，剩余分钟仍可继续申请 */
    PARTIALLY_RECOVERED,
    /** 已全部恢复：可恢复分钟耗尽，原预订终态 */
    RECOVERED,
    /** 放弃恢复（取消被阻断预订）：资格作废，分钟不退还 */
    FORFEITED,
    /** 关闭窗口缩小后落出范围：资格撤销，原预订恢复为有效 */
    RESTORED
}
