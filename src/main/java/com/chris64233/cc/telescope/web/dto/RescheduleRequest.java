package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 待重排预订的重排请求。instrument 缺省时沿用原预订仪器。
 */
public record RescheduleRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String telescopeCode,
        String instrument,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
