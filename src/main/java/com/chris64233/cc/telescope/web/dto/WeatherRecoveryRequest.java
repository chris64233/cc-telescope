package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 天气恢复排期请求：为被阻断的原预订申请新时段。
 * {@code idempotencyKey} 保证恢复请求幂等；{@code instrument} 缺省时沿用原预订仪器。
 * 新预订继承原归属与优先级，只消耗可恢复分钟，不扣减提案配额。
 */
public record WeatherRecoveryRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String telescopeCode,
        String instrument,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
