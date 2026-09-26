package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.ScheduleEntry;

import java.time.Instant;

public record ScheduleEntryResponse(
        Long reservationId,
        String ownerType,
        String ownerCode,
        String instrument,
        Instant startTime,
        Instant endTime,
        long durationMinutes,
        Integer priority) {

    public static ScheduleEntryResponse from(ScheduleEntry e) {
        return new ScheduleEntryResponse(e.getReservationId(), e.getOwnerType(), e.getOwnerCode(),
                e.getInstrument(), e.getStartTime(), e.getEndTime(), e.getDurationMinutes(), e.getPriority());
    }
}
