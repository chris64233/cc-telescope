package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.ReservationStatus;

import java.time.Instant;

public record ReservationResponse(
        Long id,
        String idempotencyKey,
        /** NORMAL 普通预订 / OPPORTUNITY 机会预订 */
        String ownerType,
        String ownerCode,
        String proposalCode,
        String telescopeCode,
        String instrument,
        Instant startTime,
        Instant endTime,
        long durationMinutes,
        ReservationStatus status,
        Integer priority,
        String preemptedByOpportunityCode,
        String preemptedByBusinessKey,
        Long rescheduledToId,
        Instant rescheduledAt) {

    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(r.getId(),
                r.getIdempotencyKey(),
                r.isOpportunityReservation() ? "OPPORTUNITY" : "NORMAL",
                r.getOwnerCode(),
                r.getProposal() == null ? null : r.getProposal().getCode(),
                r.getTelescope().getCode(),
                r.getInstrument(),
                r.getStartTime(),
                r.getEndTime(),
                r.getDurationMinutes(),
                r.getStatus(),
                r.getPriority(),
                r.getPreemptedBy() == null ? null : r.getPreemptedBy().getCode(),
                r.getPreemptedByBusinessKey(),
                r.getRescheduledToId(),
                r.getRescheduledAt());
    }
}
