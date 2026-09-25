package com.chris64233.cc.telescope.dto;

import java.util.List;

public record ScheduleResponse(
        String telescopeCode,
        int switchOverMinutes,
        List<BookingResponse> bookings) {
}
