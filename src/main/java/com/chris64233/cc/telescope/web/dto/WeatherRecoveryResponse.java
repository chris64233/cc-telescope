package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.service.WeatherService.AffectedView;
import com.chris64233.cc.telescope.service.WeatherService.RecoveryOutcome;

/**
 * 天气恢复排期结果：新建预订与受影响记录（含剩余可恢复分钟数）。
 * {@code created=false} 表示幂等键重放，返回原预订。
 */
public record WeatherRecoveryResponse(
        boolean created,
        ReservationResponse reservation,
        WeatherAffectedResponse affected) {

    public static WeatherRecoveryResponse from(RecoveryOutcome outcome, AffectedView view) {
        return new WeatherRecoveryResponse(outcome.created(),
                ReservationResponse.from(outcome.newReservation()),
                WeatherAffectedResponse.from(view));
    }
}
