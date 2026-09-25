package com.chris64233.cc.telescope.dto;

public record QuotaResponse(
        String proposalNo,
        int totalMinutes,
        int usedMinutes,
        int remainingMinutes) {
}
