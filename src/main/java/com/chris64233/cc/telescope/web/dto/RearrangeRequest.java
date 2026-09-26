package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RearrangeRequest(
        @NotBlank String idempotencyKey,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
