package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.service.PreemptionService.SwitchImpact;

public record SwitchImpactResponse(
        Long neighborReservationId,
        String neighborInstrument,
        long requiredSwitchMinutes,
        long availableGapMinutes,
        boolean feasible) {

    public static SwitchImpactResponse from(SwitchImpact impact) {
        if (impact == null) {
            return null;
        }
        return new SwitchImpactResponse(impact.neighborReservationId(), impact.neighborInstrument(),
                impact.requiredSwitchMinutes(), impact.availableGapMinutes(), impact.feasible());
    }
}
