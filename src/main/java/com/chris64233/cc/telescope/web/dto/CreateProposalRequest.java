package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.Set;

public record CreateProposalRequest(
        @NotBlank String code,
        @NotEmpty Set<String> instruments,
        @Positive long totalQuotaMinutes) {
}
