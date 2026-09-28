package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 天气关闭事件登记请求：指定望远镜与关闭窗口 {@code [startTime, endTime)}。
 * {@code businessKey} 为关闭事件业务号，用于登记与重放的幂等控制。
 */
public record DeclareWeatherRequest(
        @NotBlank String businessKey,
        @NotBlank String telescopeCode,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
