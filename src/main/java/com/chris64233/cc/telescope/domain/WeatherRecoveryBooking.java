package com.chris64233.cc.telescope.domain;

import jakarta.persistence.Embeddable;

import java.time.Instant;

/**
 * 一次天气恢复排期建立的新预订登记（支持部分恢复，一条受影响记录可能对应多条）。
 */
@Embeddable
public class WeatherRecoveryBooking {

    private Long newReservationId;

    private long usedMinutes;

    private Instant bookedAt;

    protected WeatherRecoveryBooking() {
    }

    public WeatherRecoveryBooking(Long newReservationId, long usedMinutes, Instant bookedAt) {
        this.newReservationId = newReservationId;
        this.usedMinutes = usedMinutes;
        this.bookedAt = bookedAt;
    }

    public Long getNewReservationId() {
        return newReservationId;
    }

    public long getUsedMinutes() {
        return usedMinutes;
    }

    public Instant getBookedAt() {
        return bookedAt;
    }
}
