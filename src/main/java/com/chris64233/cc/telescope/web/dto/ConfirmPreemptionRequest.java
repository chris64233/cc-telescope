package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record ConfirmPreemptionRequest(
        @NotBlank String businessKey,
        @NotBlank String proposalCode,
        @NotBlank String telescopeCode,
        @NotBlank String instrument,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
