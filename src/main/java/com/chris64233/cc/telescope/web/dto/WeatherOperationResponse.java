package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.WeatherOperation;

import java.time.Instant;

public record WeatherOperationResponse(
        String operation,
        Instant windowStart,
        Instant windowEnd,
        int blocked,
        int restored,
        Instant at) {

    public static WeatherOperationResponse from(WeatherOperation o) {
        return new WeatherOperationResponse(o.getOperation(), o.getWindowStart(), o.getWindowEnd(),
                o.getBlocked(), o.getRestored(), o.getAt());
    }
}
