package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.OpportunityProposal;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

public record OpportunityQuotaResponse(
        String code,
        int priority,
        Instant responseDeadline,
        Set<String> instruments,
        long totalQuotaMinutes,
        long usedQuotaMinutes,
        long remainingQuotaMinutes) {

    public static OpportunityQuotaResponse from(OpportunityProposal proposal) {
        return new OpportunityQuotaResponse(proposal.getCode(),
                proposal.getPriority(),
                proposal.getResponseDeadline(),
                new TreeSet<>(proposal.getAllowedInstruments()),
                proposal.getTotalQuotaMinutes(),
                proposal.getTotalQuotaMinutes() - proposal.getRemainingQuotaMinutes(),
                proposal.getRemainingQuotaMinutes());
    }
}
