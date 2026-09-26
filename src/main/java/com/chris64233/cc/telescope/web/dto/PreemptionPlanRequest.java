package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 抢占试算请求：不落库、不改变日程，返回受影响预订与前后切换时间评估。
 */
public record PreemptionPlanRequest(
        @NotBlank String opportunityCode,
        @NotBlank String telescopeCode,
        @NotBlank String instrument,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
