package com.chris64233.cc.telescope.web.dto;

import com.chris64233.cc.telescope.domain.Reservation;
import com.chris64233.cc.telescope.domain.WeatherAffectedRecord;
import com.chris64233.cc.telescope.service.WeatherService.AffectedView;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 天气受影响记录响应：关联原预订、天气事件、历次恢复建立的新预订，以及剩余可恢复分钟数。
 */
public record WeatherAffectedResponse(
        Long id,
        String status,
        String weatherEventBusinessKey,
        Long originalReservationId,
        String originalReservationStatus,
        Long weatherRecoveredToId,
        String ownerType,
        String ownerCode,
        Integer priority,
        String instrument,
        Instant originalStartTime,
        Instant originalEndTime,
        long originalDurationMinutes,
        long recoverableMinutes,
        List<RecoveryBookingResponse> recoveryBookings,
        Instant createdAt,
        Instant recoveredAt,
        Instant abandonedAt,
        Instant restoredAt) {

    /** 一次恢复排期登记：使用分钟数及对应新预订详情。 */
    public record RecoveryBookingResponse(
            Long newReservationId,
            long usedMinutes,
            Instant bookedAt,
            ReservationResponse reservation) {
    }

    public static WeatherAffectedResponse from(AffectedView view) {
        WeatherAffectedRecord a = view.affected();
        Map<Long, Reservation> newById = view.newReservations().stream()
                .collect(Collectors.toMap(Reservation::getId, Function.identity()));
        List<RecoveryBookingResponse> bookings = a.getRecoveryBookings().stream()
                .map(b -> new RecoveryBookingResponse(b.getNewReservationId(), b.getUsedMinutes(),
                        b.getBookedAt(),
                        newById.containsKey(b.getNewReservationId())
                                ? ReservationResponse.from(newById.get(b.getNewReservationId()))
                                : null))
                .toList();
        return new WeatherAffectedResponse(
                a.getId(),
                a.getStatus().name(),
                a.getEvent().getBusinessKey(),
                a.getOriginalReservation().getId(),
                a.getOriginalReservation().getStatus().name(),
                a.getOriginalReservation().getWeatherRecoveredToId(),
                a.getOwnerType(),
                a.getOwnerCode(),
                a.getPriority(),
                a.getInstrument(),
                a.getOriginalStartTime(),
                a.getOriginalEndTime(),
                a.getOriginalDurationMinutes(),
                a.getRecoverableMinutes(),
                bookings,
                a.getCreatedAt(),
                a.getRecoveredAt(),
                a.getAbandonedAt(),
                a.getRestoredAt());
    }
}
