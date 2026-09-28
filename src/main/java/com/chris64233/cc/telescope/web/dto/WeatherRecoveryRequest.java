package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 天气恢复排期请求：受影响提案使用原优先级与剩余可恢复分钟数申请新时段。
 * instrument 缺省时沿用原观测仪器；{@code idempotencyKey} 保证恢复请求幂等。
 */
public record WeatherRecoveryRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String telescopeCode,
        String instrument,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
