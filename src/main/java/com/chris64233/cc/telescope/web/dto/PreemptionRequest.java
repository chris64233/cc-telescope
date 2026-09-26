package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 抢占申请：目标机会在期望区间 [startTime, endTime) 上申请观测。
 * {@code businessKey} 为抢占业务号，用于确认接口的幂等控制。
 */
public record PreemptionRequest(
        @NotBlank String businessKey,
        @NotBlank String opportunityCode,
        @NotBlank String telescopeCode,
        @NotBlank String instrument,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
