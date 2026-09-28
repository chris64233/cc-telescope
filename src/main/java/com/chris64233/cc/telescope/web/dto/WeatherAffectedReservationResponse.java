package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.WeatherAffectedSnapshot;

import java.time.Instant;

public record WeatherAffectedReservationResponse(
        Long reservationId,
        String ownerType,
        String ownerCode,
        String instrument,
        Instant startTime,
        Instant endTime,
        long durationMinutes,
        Integer priority,
        String action,
        Instant at) {

    public static WeatherAffectedReservationResponse from(WeatherAffectedSnapshot s) {
        return new WeatherAffectedReservationResponse(s.getReservationId(), s.getOwnerType(), s.getOwnerCode(),
                s.getInstrument(), s.getStartTime(), s.getEndTime(), s.getDurationMinutes(),
                s.getPriority(), s.getAction(), s.getAt());
    }
}
