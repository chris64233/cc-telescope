package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.service.PreemptionService.AffectedReservation;
import com.chris64233.cc.telescope.service.PreemptionService.PreemptionPreview;
import com.chris64233.cc.telescope.service.PreemptionService.SwitchGap;

import java.util.List;

public record PreemptionPreviewResponse(
        boolean feasible,
        List<String> reasons,
        List<AffectedReservation> affectedReservations,
        SwitchGap switchGapBefore,
        SwitchGap switchGapAfter) {

    public static PreemptionPreviewResponse from(PreemptionPreview preview) {
        return new PreemptionPreviewResponse(preview.feasible(), preview.reasons(),
                preview.affectedReservations(), preview.switchGapBefore(), preview.switchGapAfter());
    }
}
