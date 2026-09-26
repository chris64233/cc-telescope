package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.service.PreemptionService.PreemptionPlan;

import java.time.Instant;
import java.util.List;

/**
 * 抢占试算响应：不落库、不改变日程与配额。
 */
public record PreemptionPlanResponse(
        String opportunityCode,
        int opportunityPriority,
        String telescopeCode,
        String instrument,
        Instant startTime,
        Instant endTime,
        long durationMinutes,
        boolean feasible,
        List<String> conflictReasons,
        List<AffectedReservationResponse> affectedReservations,
        SwitchImpactResponse switchBefore,
        SwitchImpactResponse switchAfter,
        long opportunityRemainingBefore,
        long scheduleVersionBefore) {

    public static PreemptionPlanResponse from(PreemptionPlan plan) {
        return new PreemptionPlanResponse(plan.opportunityCode(), plan.opportunityPriority(),
                plan.telescopeCode(), plan.instrument(), plan.startTime(), plan.endTime(),
                plan.durationMinutes(), plan.feasible(), plan.conflictReasons(),
                plan.affectedReservations().stream().map(AffectedReservationResponse::from).toList(),
                SwitchImpactResponse.from(plan.switchBefore()),
                SwitchImpactResponse.from(plan.switchAfter()),
                plan.opportunityRemainingBefore(), plan.scheduleVersionBefore());
    }
}
