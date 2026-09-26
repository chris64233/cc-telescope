package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.Set;

public record CreateOpportunityRequest(
        @NotBlank String code,
        @NotNull Integer priority,
        @NotNull Instant responseDeadline,
        @NotEmpty Set<String> instruments,
        @Positive long totalQuotaMinutes) {
}
