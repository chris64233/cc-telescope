package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.WeatherWindow;

import java.time.Instant;

public record WeatherWindowResponse(
        Instant startTime,
        Instant endTime,
        long windowVersion,
        Instant appliedAt) {

    public static WeatherWindowResponse from(WeatherWindow w) {
        return new WeatherWindowResponse(w.getStartTime(), w.getEndTime(),
                w.getWindowVersion(), w.getAppliedAt());
    }
}
