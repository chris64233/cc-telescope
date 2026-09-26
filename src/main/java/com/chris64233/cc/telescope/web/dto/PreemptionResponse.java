package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.Preemption;
import com.chris64233.cc.telescope.domain.PreemptionItem;
import com.chris64233.cc.telescope.domain.PreemptionReason;
import com.chris64233.cc.telescope.domain.PreemptionStatus;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.util.List;

public record PreemptionResponse(
        Long id,
        String businessKey,
        String proposalCode,
        String telescopeCode,
        String instrument,
        Instant startTime,
        Instant endTime,
        long durationMinutes,
        PreemptionStatus status,
        long scheduleVersionBefore,
        long scheduleVersionAfter,
        @JsonRawValue String scheduleBefore,
        @JsonRawValue String scheduleAfter,
        long tooQuotaBeforeMinutes,
        long tooQuotaAfterMinutes,
        List<PreemptionItemResponse> affectedReservations,
        List<String> reasons,
        Long newReservationId,
        Instant createdAt) {

    public static PreemptionResponse from(Preemption preemption) {
        return new PreemptionResponse(preemption.getId(),
                preemption.getBusinessKey(),
                preemption.getProposal().getCode(),
                preemption.getTelescope().getCode(),
                preemption.getInstrument(),
                preemption.getStartTime(),
                preemption.getEndTime(),
                preemption.getDurationMinutes(),
                preemption.getStatus(),
                preemption.getScheduleVersionBefore(),
                preemption.getScheduleVersionAfter(),
                preemption.getScheduleBeforeJson(),
                preemption.getScheduleAfterJson(),
                preemption.getTooQuotaBeforeMinutes(),
                preemption.getTooQuotaAfterMinutes(),
                preemption.getItems().stream().map(PreemptionItemResponse::from).toList(),
                preemption.getReasons().stream().map(PreemptionReason::getReason).toList(),
                preemption.getNewReservation() != null ? preemption.getNewReservation().getId() : null,
                preemption.getCreatedAt());
    }

    public record PreemptionItemResponse(
            Long reservationId,
            String proposalCode,
            String instrument,
            Instant startTime,
            Instant endTime,
            long durationMinutes) {

        public static PreemptionItemResponse from(PreemptionItem item) {
            return new PreemptionItemResponse(item.getReservationId(), item.getProposalCode(),
                    item.getInstrument(), item.getStartTime(), item.getEndTime(),
                    item.getDurationMinutes());
        }
    }
}
