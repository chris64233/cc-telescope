package com.chris64233.cc.telescope.service;

import org.springframework.http.HttpStatus;

public class RescheduleNotAllowedException extends BookingException {

    public RescheduleNotAllowedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
