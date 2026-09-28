package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.WeatherEvent;

import java.time.Instant;
import java.util.List;

/**
 * 天气关闭事件响应：含当前窗口、窗口版本历史与日程版本。
 */
public record WeatherEventResponse(
        Long id,
        String businessKey,
        String telescopeCode,
        String reason,
        Instant startTime,
        Instant endTime,
        long windowVersion,
        long scheduleVersionBefore,
        long scheduleVersionAfter,
        List<WeatherWindowResponse> windowHistory,
        Instant createdAt) {

    public static WeatherEventResponse from(WeatherEvent e) {
        return new WeatherEventResponse(e.getId(), e.getBusinessKey(), e.getTelescope().getCode(),
                e.getReason(), e.getStartTime(), e.getEndTime(), e.getWindowVersion(),
                e.getScheduleVersionBefore(), e.getScheduleVersionAfter(),
                e.getWindowHistory().stream().map(WeatherWindowResponse::from).toList(),
                e.getCreatedAt());
    }
}
