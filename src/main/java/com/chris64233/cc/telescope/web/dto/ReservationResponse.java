package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;

import java.time.Instant;

public record ReservationResponse(
        Long id,
        String idempotencyKey,
        String proposalCode,
        String telescopeCode,
        String instrument,
        Instant startTime,
        Instant endTime,
        long durationMinutes,
        ReservationStatus status) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(reservation.getId(),
                reservation.getIdempotencyKey(),
                reservation.getProposal().getCode(),
                reservation.getTelescope().getCode(),
                reservation.getInstrument(),
                reservation.getStartTime(),
                reservation.getEndTime(),
                reservation.getDurationMinutes(),
                reservation.getStatus());
    }
}
