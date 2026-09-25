package com.chris64233.cc.telescope.service;

import org.springframework.http.HttpStatus;

public class CancellationNotAllowedException extends BookingException {

    public CancellationNotAllowedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
