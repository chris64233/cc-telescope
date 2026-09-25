package com.chris64233.cc.telescope.dto;

import java.util.List;

public record ProposalResponse(
        Long id,
        String proposalNo,
        int totalMinutes,
        int usedMinutes,
        int remainingMinutes,
        List<String> allowedInstruments) {
}
