package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.PreemptionRecord;

import java.time.Instant;
import java.util.List;

/**
 * 抢占记录响应：包含日程版本、受影响预订快照、抢占前后日程、配额变化与冲突原因。
 */
public record PreemptionRecordResponse(
        Long id,
        String businessKey,
        String opportunityCode,
        String telescopeCode,
        String instrument,
        Instant requestedStart,
        Instant requestedEnd,
        long durationMinutes,
        String status,
        long scheduleVersionBefore,
        long scheduleVersionAfter,
        long switchBeforeMinutes,
        long switchAfterMinutes,
        Long opportunityReservationId,
        List<AffectedReservationResponse> affectedReservations,
        List<QuotaChangeResponse> quotaChanges,
        List<String> conflictReasons,
        List<ScheduleEntryResponse> scheduleBefore,
        List<ScheduleEntryResponse> scheduleAfter,
        Instant createdAt) {

    public static PreemptionRecordResponse from(PreemptionRecord r) {
        return new PreemptionRecordResponse(r.getId(), r.getBusinessKey(), r.getOpportunityCode(),
                r.getTelescopeCode(), r.getInstrument(), r.getRequestedStart(), r.getRequestedEnd(),
                r.getDurationMinutes(), r.getStatus().name(), r.getScheduleVersionBefore(),
                r.getScheduleVersionAfter(), r.getSwitchBeforeMinutes(), r.getSwitchAfterMinutes(),
                r.getOpportunityReservationId(),
                r.getAffectedReservations().stream().map(AffectedReservationResponse::from).toList(),
                r.getQuotaChanges().stream().map(QuotaChangeResponse::from).toList(),
                r.getConflictReasons(),
                r.getScheduleBefore().stream().map(ScheduleEntryResponse::from).toList(),
                r.getScheduleAfter().stream().map(ScheduleEntryResponse::from).toList(),
                r.getCreatedAt());
    }
}
