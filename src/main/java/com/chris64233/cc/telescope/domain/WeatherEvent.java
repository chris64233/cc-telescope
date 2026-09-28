package com.chris64233.cc.telescope.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次天气关闭事件：指定望远镜与关闭时段 [startTime, endTime)。
 *
 * <p>以 {@code businessKey}（天气关闭业务号）保证幂等：相同业务号重放返回原事件；
 * 相同业务号但望远镜不同返回冲突。天气范围可在原业务号上调整（扩大/缩小），
 * 每次调整的窗口版本记录在 {@link #windowHistory} 中。
 *
 * <p>关闭时仅中断与关闭时段重叠且<strong>尚未开始</strong>的有效观测：
 * 原子标记为 {@link ReservationStatus#WEATHER_CANCELLED} 并释放其占用的配额与日程；
 * 已完成或正在执行（已开始）的观测保持不变。
 */
@Entity
@Table(name = "weather_events")
public class WeatherEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String businessKey;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "telescope_id", nullable = false)
    private Telescope telescope;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private Instant startTime;

    @Column(nullable = false)
    private Instant endTime;

    /** 当前窗口版本号：首次关闭为 0，每次成功的范围调整 +1。 */
    @Column(nullable = false)
    private long windowVersion;

    /** 关闭/最近一次范围调整时的望远镜日程版本。 */
    @Column(nullable = false)
    private long scheduleVersionBefore;

    @Column(nullable = false)
    private long scheduleVersionAfter;

    /** 窗口历史：第 0 条为首次关闭窗口，之后每次范围调整追加一条。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "weather_event_windows", joinColumns = @JoinColumn(name = "weather_event_id"))
    @OrderColumn(name = "position")
    private List<WeatherWindow> windowHistory = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    protected WeatherEvent() {
    }

    public WeatherEvent(String businessKey, Telescope telescope, String reason,
                        Instant startTime, Instant endTime,
                        long scheduleVersionBefore, long scheduleVersionAfter, Instant createdAt) {
        this.businessKey = businessKey;
        this.telescope = telescope;
        this.reason = reason;
        this.startTime = startTime;
        this.endTime = endTime;
        this.windowVersion = 0;
        this.scheduleVersionBefore = scheduleVersionBefore;
        this.scheduleVersionAfter = scheduleVersionAfter;
        this.createdAt = createdAt;
        this.windowHistory.add(new WeatherWindow(startTime, endTime, 0, createdAt));
    }

    public Long getId() {
        return id;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public Telescope getTelescope() {
        return telescope;
    }

    public String getReason() {
        return reason;
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

    public long getScheduleVersionBefore() {
        return scheduleVersionBefore;
    }

    public long getScheduleVersionAfter() {
        return scheduleVersionAfter;
    }

    public List<WeatherWindow> getWindowHistory() {
        return windowHistory;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * 应用一次范围调整：更新当前窗口、自增窗口版本并记录历史。
     */
    public void applyWindow(Instant newStart, Instant newEnd, long scheduleVersionAfter, Instant now) {
        this.startTime = newStart;
        this.endTime = newEnd;
        this.windowVersion += 1;
        this.scheduleVersionAfter = scheduleVersionAfter;
        this.windowHistory.add(new WeatherWindow(newStart, newEnd, this.windowVersion, now));
    }
}
