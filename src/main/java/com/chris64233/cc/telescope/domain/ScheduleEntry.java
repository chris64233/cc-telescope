package com.chris64233.cc.telescope.domain;

import jakarta.persistence.Embeddable;

import java.time.Instant;

/**
 * 抢占前后日程中单个有效预订的快照。
 */
@Embeddable
public class ScheduleEntry {

    private Long reservationId;

    /** NORMAL / OPPORTUNITY */
    private String ownerType;

    private String ownerCode;

    private String instrument;

    private Instant startTime;

    private Instant endTime;

    private long durationMinutes;

    private Integer priority;

    protected ScheduleEntry() {
    }

    public ScheduleEntry(Long reservationId, String ownerType, String ownerCode, String instrument,
                         Instant startTime, Instant endTime, long durationMinutes, Integer priority) {
        this.reservationId = reservationId;
        this.ownerType = ownerType;
        this.ownerCode = ownerCode;
        this.instrument = instrument;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMinutes = durationMinutes;
        this.priority = priority;
    }

    public static ScheduleEntry from(Reservation reservation) {
        Reservation r = reservation;
        return new ScheduleEntry(r.getId(),
                r.isOpportunityReservation() ? "OPPORTUNITY" : "NORMAL",
                r.getOwnerCode(),
                r.getInstrument(),
                r.getStartTime(),
                r.getEndTime(),
                r.getDurationMinutes(),
                r.getPriority());
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
}
