package com.chris64233.cc.telescope.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 天气关闭请求：指定望远镜与关闭时段 [startTime, endTime)。
 * {@code businessKey} 为天气关闭业务号，用于关闭接口的幂等控制与后续范围调整。
 */
public record WeatherCloseRequest(
        @NotBlank String businessKey,
        @NotBlank String telescopeCode,
        String reason,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {
}
