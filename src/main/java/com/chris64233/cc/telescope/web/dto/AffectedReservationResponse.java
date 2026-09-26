package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.PreemptionSnapshot;

import java.time.Instant;

public record AffectedReservationResponse(
        Long reservationId,
        String ownerType,
        String ownerCode,
        String instrument,
        Instant startTime,
        Instant endTime,
        long durationMinutes,
        String reservationStatus,
        boolean preemptable,
        Integer priority) {

    public static AffectedReservationResponse from(PreemptionSnapshot s) {
        return new AffectedReservationResponse(s.getReservationId(), s.getOwnerType(), s.getOwnerCode(),
                s.getInstrument(), s.getStartTime(), s.getEndTime(), s.getDurationMinutes(),
                s.getReservationStatus(), s.isPreemptable(), s.getPriority());
    }
}
