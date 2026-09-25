package com.chris64233.cc.telescope.service;

import org.springframework.http.HttpStatus;

public class ScheduleConflictException extends BookingException {

    public ScheduleConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
