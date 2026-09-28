package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.WeatherRecovery;

import java.time.Instant;
import java.util.List;

/**
 * 恢复资格详情：把<strong>原预订、天气事件、历次恢复建立的新预订与剩余可恢复分钟数</strong>关联在一起。
 */
public record WeatherRecoveryResponse(
        Long id,
        String weatherEventBusinessKey,
        String telescopeCode,
        ReservationResponse originalReservation,
        long blockedMinutes,
        long recoveredMinutes,
        long remainingRecoverableMinutes,
        String status,
        Integer priority,
        List<ReservationResponse> recoveredReservations,
        Instant blockedAt,
        Instant lastRecoveredAt) {

    public static WeatherRecoveryResponse from(WeatherRecovery w,
                                               List<com.chris64233.cc.telescope.domain.Reservation> newReservations) {
        return new WeatherRecoveryResponse(
                w.getId(),
                w.getWeatherEvent().getBusinessKey(),
                w.getWeatherEvent().getTelescopeCode(),
                ReservationResponse.from(w.getOriginalReservation()),
                w.getBlockedMinutes(),
                w.getRecoveredMinutes(),
                w.getRemainingRecoverableMinutes(),
                w.getStatus().name(),
                w.getOriginalReservation().getPriority(),
                newReservations.stream().map(ReservationResponse::from).toList(),
                w.getBlockedAt(),
                w.getLastRecoveredAt());
    }
}
