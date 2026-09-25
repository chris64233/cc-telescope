package com.chris64233.cc.telescope.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BookingRequest(
        @NotBlank String proposalNo,
        @NotBlank String telescopeCode,
        @NotBlank String instrument,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        String idempotencyKey) {
}
