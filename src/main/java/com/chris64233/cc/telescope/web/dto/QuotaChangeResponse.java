package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.QuotaChange;

public record QuotaChangeResponse(
        String ownerType,
        String ownerCode,
        long deltaMinutes,
        String reason) {

    public static QuotaChangeResponse from(QuotaChange change) {
        return new QuotaChangeResponse(change.getOwnerType(), change.getOwnerCode(),
                change.getDeltaMinutes(), change.getReason());
    }
}
