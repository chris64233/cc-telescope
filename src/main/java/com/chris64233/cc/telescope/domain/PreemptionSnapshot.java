package com.chris64233.cc.telescope.domain;

import jakarta.persistence.Embeddable;

import java.time.Instant;

/**
 * 抢占时受影响预订的只读快照。
 */
@Embeddable
public class PreemptionSnapshot {

    private Long reservationId;

    /** NORMAL / OPPORTUNITY */
    private String ownerType;

    private String ownerCode;

    private String instrument;

    private Instant startTime;

    private Instant endTime;

    private long durationMinutes;

    /** 抢占发生时预订的状态 */
    private String reservationStatus;

    /** true 表示该预订会被本次抢占取消（可抢占），false 表示它是导致拒绝的不可抢占预订 */
    private boolean preemptable;

    private Integer priority;

    protected PreemptionSnapshot() {
    }

    public PreemptionSnapshot(Long reservationId, String ownerType, String ownerCode, String instrument,
                              Instant startTime, Instant endTime, long durationMinutes,
                              String reservationStatus, boolean preemptable, Integer priority) {
        this.reservationId = reservationId;
        this.ownerType = ownerType;
        this.ownerCode = ownerCode;
        this.instrument = instrument;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMinutes = durationMinutes;
        this.reservationStatus = reservationStatus;
        this.preemptable = preemptable;
        this.priority = priority;
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

    public String getReservationStatus() {
        return reservationStatus;
    }

    public boolean isPreemptable() {
        return preemptable;
    }

    public Integer getPriority() {
        return priority;
    }
}
