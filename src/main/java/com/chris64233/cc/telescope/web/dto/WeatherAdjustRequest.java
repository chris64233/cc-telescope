package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 天气关闭范围调整请求：在原天气关闭业务号上扩大或缩小关闭窗口。
 */
public record WeatherAdjustRequest(
        @NotBlank String businessKey,
        @NotBlank String telescopeCode,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
