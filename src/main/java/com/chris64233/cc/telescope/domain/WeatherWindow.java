package com.chris64233.cc.telescope.domain;

import jakarta.persistence.Embeddable;

import java.time.Instant;

/**
 * 天气关闭事件某一版本的关闭窗口快照。
 */
@Embeddable
public class WeatherWindow {

    private Instant startTime;

    private Instant endTime;

    /** 窗口版本号：首次关闭为 0，每次范围调整 +1。 */
    private long windowVersion;

    private Instant appliedAt;

    protected WeatherWindow() {
    }

    public WeatherWindow(Instant startTime, Instant endTime, long windowVersion, Instant appliedAt) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.windowVersion = windowVersion;
        this.appliedAt = appliedAt;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getWindowVersion() {
        return windowVersion;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
