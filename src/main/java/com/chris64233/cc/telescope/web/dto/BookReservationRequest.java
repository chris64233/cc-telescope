package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record BookReservationRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String proposalCode,
        @NotBlank String telescopeCode,
        @NotBlank String instrument,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
