package com.chris64233.cc.telescope.service;

import org.springframework.http.HttpStatus;

public class IdempotencyConflictException extends BookingException {

    public IdempotencyConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
