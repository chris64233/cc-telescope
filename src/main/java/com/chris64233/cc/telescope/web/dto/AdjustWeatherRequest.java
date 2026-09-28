package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** 天气关闭窗口范围调整请求（业务号取自路径）。 */
public record AdjustWeatherRequest(
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
