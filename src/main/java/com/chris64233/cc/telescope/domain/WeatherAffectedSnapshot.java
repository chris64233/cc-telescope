package com.chris64233.cc.telescope.domain;

import jakarta.persistence.Embeddable;

import java.time.Instant;

/**
 * 天气关闭事件中单条受影响预订的审计快照（累积追加，不删除）。
 */
@Embeddable
public class WeatherAffectedSnapshot {

    private Long reservationId;

    /** NORMAL / OPPORTUNITY */
    private String ownerType;

    private String ownerCode;

    private String instrument;

    private Instant startTime;

    private Instant endTime;

    private long durationMinutes;

    private Integer priority;

    /** BLOCKED：被关闭窗口阻断；RESTORED：窗口缩小后落出范围、恢复为有效 */
    private String action;

    private Instant at;

    protected WeatherAffectedSnapshot() {
    }

    public WeatherAffectedSnapshot(Long reservationId, String ownerType, String ownerCode, String instrument,
                                   Instant startTime, Instant endTime, long durationMinutes,
                                   Integer priority, String action, Instant at) {
        this.reservationId = reservationId;
        this.ownerType = ownerType;
        this.ownerCode = ownerCode;
        this.instrument = instrument;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMinutes = durationMinutes;
        this.priority = priority;
        this.action = action;
        this.at = at;
    }

    public static WeatherAffectedSnapshot blocked(Reservation r, Instant now) {
        return new WeatherAffectedSnapshot(r.getId(),
                r.isOpportunityReservation() ? "OPPORTUNITY" : "NORMAL",
                r.getOwnerCode(), r.getInstrument(), r.getStartTime(), r.getEndTime(),
                r.getDurationMinutes(), r.getPriority(), "BLOCKED", now);
    }

    public static WeatherAffectedSnapshot restored(Reservation r, Instant now) {
        return new WeatherAffectedSnapshot(r.getId(),
                r.isOpportunityReservation() ? "OPPORTUNITY" : "NORMAL",
                r.getOwnerCode(), r.getInstrument(), r.getStartTime(), r.getEndTime(),
                r.getDurationMinutes(), r.getPriority(), "RESTORED", now);
    }

    public Long getReservationId() {
        return reservationId;
    }

    public String getOwnerType() {
        return ownerType;
    }

    public String getOwnerCode() {
        return ownerCode;
    }

    public String getInstrument() {
        return instrument;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getDurationMinutes() {
        return durationMinutes;
    }

    public Integer getPriority() {
        return priority;
    }

    public String getAction() {
        return action;
    }

    public Instant getAt() {
        return at;
    }
}
