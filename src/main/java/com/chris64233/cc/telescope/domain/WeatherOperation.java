package com.chris64233.cc.telescope.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Instant;

/**
 * 天气关闭事件的操作审计条目。
 */
@Embeddable
public class WeatherOperation {

    /** DECLARED：首次登记关闭窗口；ADJUSTED：窗口范围调整 */
    private String operation;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    /** 该次操作新阻断的预订条数 */
    private int blocked;

    /** 该次操作恢复回日程的预订条数 */
    private int restored;

    private Instant at;

    protected WeatherOperation() {
    }

    public WeatherOperation(String operation, Instant windowStart, Instant windowEnd,
                            int blocked, int restored, Instant at) {
        this.operation = operation;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.blocked = blocked;
        this.restored = restored;
        this.at = at;
    }

    public String getOperation() {
        return operation;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public int getBlocked() {
        return blocked;
    }

    public int getRestored() {
        return restored;
    }

    public Instant getAt() {
        return at;
    }
}
