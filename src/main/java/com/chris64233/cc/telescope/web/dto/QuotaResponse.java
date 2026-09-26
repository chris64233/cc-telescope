package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.Proposal;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

public record QuotaResponse(
        String code,
        Set<String> instruments,
        long totalQuotaMinutes,
        long usedQuotaMinutes,
        long remainingQuotaMinutes,
        boolean targetOpportunity,
        Integer priority,
        Instant validUntil) {

    public static QuotaResponse from(Proposal proposal) {
        return new QuotaResponse(proposal.getCode(),
                new TreeSet<>(proposal.getAllowedInstruments()),
                proposal.getTotalQuotaMinutes(),
                proposal.getTotalQuotaMinutes() - proposal.getRemainingQuotaMinutes(),
                proposal.getRemainingQuotaMinutes(),
                proposal.isTargetOpportunity(),
                proposal.getPriority(),
                proposal.getValidUntil());
    }
}
