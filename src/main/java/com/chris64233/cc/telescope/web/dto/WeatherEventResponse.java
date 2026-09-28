package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.WeatherEvent;

import java.time.Instant;
import java.util.List;

/** 天气关闭事件响应：当前窗口、受影响预订审计快照与历次操作。 */
public record WeatherEventResponse(
        Long id,
        String businessKey,
        String telescopeCode,
        Instant windowStart,
        Instant windowEnd,
        List<WeatherAffectedReservationResponse> affectedReservations,
        List<WeatherOperationResponse> operations,
        Instant createdAt,
        Instant updatedAt) {

    public static WeatherEventResponse from(WeatherEvent e) {
        return new WeatherEventResponse(e.getId(), e.getBusinessKey(), e.getTelescopeCode(),
                e.getWindowStart(), e.getWindowEnd(),
                e.getAffectedReservations().stream().map(WeatherAffectedReservationResponse::from).toList(),
                e.getOperations().stream().map(WeatherOperationResponse::from).toList(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
