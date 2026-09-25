package com.chris64233.cc.telescope.dto;

import java.time.Instant;

public record BookingResponse(
        Long id,
        String proposalNo,
        String telescopeCode,
        String instrument,
        Instant startAt,
        Instant endAt,
        int durationMinutes,
        String status,
        String idempotencyKey) {
}
